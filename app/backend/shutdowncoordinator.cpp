#include "shutdowncoordinator.h"
#include <atomic>
#include <utility>
#ifdef Q_OS_LINUX
#include <cerrno>
#include <csignal>
#include <ctime>
#include <fcntl.h>
#include <pthread.h>
#include <sys/eventfd.h>
#include <unistd.h>
#endif

namespace ProcessLifecycle {
namespace {
struct State {
    std::atomic<int> reason{0};
    std::atomic<qint64> deadline{0};
    int durationMs = kShutdownDeadlineMs;
    std::mutex dispatchMutex;
    std::function<void()> dispatch;
#ifdef Q_OS_LINUX
    int signalRead = -1;
    int watchdogFd = -1;
    bool installed = false;
#endif
};
// Intentionally survive all destructors and atexit handlers.
State& state() { static State* s = new State; return *s; }
#ifdef Q_OS_LINUX
volatile sig_atomic_t signalWrite = -1;
void terminationSignal(int signo) {
    const int savedErrno = errno;
    const unsigned char byte = static_cast<unsigned char>(signo);
    ssize_t result;
    do { result = ::write(static_cast<int>(signalWrite), &byte, 1); }
    while (result < 0 && errno == EINTR);
    // EAGAIN means the pipe already contains a shutdown request.
    errno = savedErrno;
}
void* signalReader(void*) {
    unsigned char byte;
    for (;;) {
        const ssize_t n = ::read(state().signalRead, &byte, 1);
        if (n == 1) ShutdownCoordinator::instance().requestShutdown(
            byte == SIGINT ? ShutdownReason::SignalInterrupt : ShutdownReason::SignalTerminate);
        else if (n < 0 && errno == EINTR) continue;
        else return nullptr;
    }
}
void* watchdog(void*) {
    uint64_t value;
    while (::read(state().watchdogFd, &value, sizeof(value)) < 0 && errno == EINTR) {}
    const qint64 deadline = state().deadline.load();
    timespec target = {static_cast<time_t>(deadline / 1000000000LL),
                       static_cast<long>(deadline % 1000000000LL)};
    while (::clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &target, nullptr) == EINTR) {}
    // No logging here: even stderr or a locale lock can block indefinitely.
    // The shutdown request is logged by the normal app path instead.
    ::_exit(kShutdownForcedExitCode);
}
bool spawn(void* (*entry)(void*)) {
    pthread_attr_t attr;
    if (pthread_attr_init(&attr)) return false;
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_t thread;
    const int result = pthread_create(&thread, &attr, entry, nullptr);
    pthread_attr_destroy(&attr);
    return result == 0;
}
#endif
}

const char* shutdownReasonName(ShutdownReason reason) {
    switch (reason) {
    case ShutdownReason::ApplicationExit: return "quit";
    case ShutdownReason::SignalTerminate: return "sigterm";
    case ShutdownReason::SignalInterrupt: return "sigint";
    case ShutdownReason::SdlApplicationExit: return "window-exit";
    case ShutdownReason::ReplaceRequest: return "replace";
    case ShutdownReason::StartupFailure: return "startup-failure";
    default: return "none";
    }
}
ShutdownCoordinator& ShutdownCoordinator::instance() {
    static auto* instance = new ShutdownCoordinator; return *instance;
}
qint64 ShutdownCoordinator::monotonicNowNs() {
#ifdef Q_OS_LINUX
    timespec now = {};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return qint64(now.tv_sec) * 1000000000LL + now.tv_nsec;
#else
    return 0;
#endif
}
bool ShutdownCoordinator::installProcessSignalHandling() {
#ifdef Q_OS_LINUX
    auto& s = state();
    if (s.installed) return true;
    int pipeFd[2];
    if (::pipe2(pipeFd, O_CLOEXEC) != 0) return false;
    s.signalRead = pipeFd[0];
    const int flags = fcntl(pipeFd[1], F_GETFL);
    if (flags < 0 || fcntl(pipeFd[1], F_SETFL, flags | O_NONBLOCK) < 0) return false;
    signalWrite = pipeFd[1];
    s.watchdogFd = eventfd(0, EFD_CLOEXEC);
    if (s.watchdogFd < 0 || !spawn(watchdog) || !spawn(signalReader)) return false;
    struct sigaction action = {};
    action.sa_handler = terminationSignal;
    action.sa_flags = SA_RESTART;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGTERM, &action, nullptr) || sigaction(SIGINT, &action, nullptr)) return false;
    s.installed = true;
    return true;
#else
    return false;
#endif
}
void ShutdownCoordinator::requestShutdown(ShutdownReason reason) {
    auto& s = state();
    int expected = 0;
    if (!s.reason.compare_exchange_strong(expected, static_cast<int>(reason))) return;
    // The pre-created watchdog is awakened before any mutex, logging or Qt call.
    s.deadline.store(monotonicNowNs() + qint64(s.durationMs) * 1000000LL);
#ifdef Q_OS_LINUX
    const uint64_t one = 1;
    ssize_t result;
    do { result = ::write(s.watchdogFd, &one, sizeof(one)); }
    while (result < 0 && errno == EINTR);
#endif
    // A registered Session owns cleanup. Its Qt completion callback quits only
    // after host cleanup; aboutToQuit must not abort those network requests.
    if (ActiveSessionRegistry::instance().notify(reason)) return;
    std::lock_guard<std::mutex> lock(s.dispatchMutex);
    if (s.dispatch) s.dispatch();
}
bool ShutdownCoordinator::shutdownRequested() const { return state().reason.load() != 0; }
ShutdownReason ShutdownCoordinator::shutdownReason() const {
    return static_cast<ShutdownReason>(state().reason.load());
}
qint64 ShutdownCoordinator::remainingMs() const {
    const auto deadline = state().deadline.load();
    if (!deadline) return state().durationMs;
    return qMax<qint64>(0, (deadline - monotonicNowNs()) / 1000000LL);
}
void ShutdownCoordinator::setQuitDispatcher(std::function<void()> dispatcher) {
    auto& s = state();
    std::lock_guard<std::mutex> lock(s.dispatchMutex);
    s.dispatch = std::move(dispatcher);
    if (shutdownRequested() && s.dispatch) s.dispatch();
}
void ShutdownCoordinator::setDeadlineDurationMs(int ms) {
    if (ms > 0 && !shutdownRequested()) state().durationMs = ms;
}
ActiveSessionRegistry& ActiveSessionRegistry::instance() {
    static auto* instance = new ActiveSessionRegistry; return *instance;
}
void ActiveSessionRegistry::registerSession(void* context, Notifier notifier, const Snapshot& snapshot) {
    std::lock_guard<std::mutex> lock(m_Mutex);
    m_RecoveryContext = nullptr; m_RecoverySnapshot = {};
    m_Context = context; m_Notifier = std::move(notifier); m_Snapshot = snapshot;
    if (ShutdownCoordinator::instance().shutdownRequested())
        m_Notifier(ShutdownCoordinator::instance().shutdownReason());
}
void ActiveSessionRegistry::unregisterSession(void* context, bool retainRecovery) {
    std::lock_guard<std::mutex> lock(m_Mutex);
    if (m_Context == context) {
        if (retainRecovery) { m_RecoveryContext = context; m_RecoverySnapshot = m_Snapshot; }
        m_Context = nullptr; m_Notifier = {}; m_Snapshot = {};
    }
}
void ActiveSessionRegistry::clearRecovery(void* context) {
    std::lock_guard<std::mutex> lock(m_Mutex);
    if (m_RecoveryContext == context) { m_RecoveryContext = nullptr; m_RecoverySnapshot = {}; }
}
bool ActiveSessionRegistry::notify(ShutdownReason reason) {
    std::lock_guard<std::mutex> lock(m_Mutex);
    if (!m_Notifier) return false;
    m_Notifier(reason); // lock protects the callback target's lifetime
    return true;
}
ActiveSessionRegistry::Snapshot ActiveSessionRegistry::snapshot() {
    std::lock_guard<std::mutex> lock(m_Mutex); return m_Context ? m_Snapshot : m_RecoverySnapshot;
}
}
