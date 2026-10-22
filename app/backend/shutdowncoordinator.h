#pragma once

#include <QtGlobal>
#include <QString>
#include <functional>
#include <mutex>

namespace ProcessLifecycle {
enum class ShutdownReason { None, ApplicationExit, SignalTerminate, SignalInterrupt,
                            SdlApplicationExit, ReplaceRequest, StartupFailure };
constexpr int kShutdownDeadlineMs = 5000;
constexpr int kShutdownForcedExitCode = 70;
const char* shutdownReasonName(ShutdownReason reason);

// Process-lifetime Linux services. The watchdog remains alive through Qt and
// SDL destruction; no GUI event pump is needed to receive a termination signal.
class ShutdownCoordinator {
public:
    static ShutdownCoordinator& instance();
    bool installProcessSignalHandling();
    void requestShutdown(ShutdownReason reason);
    bool shutdownRequested() const;
    ShutdownReason shutdownReason() const;
    qint64 remainingMs() const;
    static qint64 monotonicNowNs();
    // Clear under the same mutex before destroying the dispatcher's Qt target.
    void setQuitDispatcher(std::function<void()> dispatcher);
    // Used only by subprocess tests, before installation.
    void setDeadlineDurationMs(int ms);
private:
    ShutdownCoordinator() = default;
};

class ActiveSessionRegistry {
public:
    struct Snapshot { QString hostUuid; int appId = 0; };
    using Notifier = std::function<void(ShutdownReason)>;
    static ActiveSessionRegistry& instance();
    void registerSession(void* context, Notifier notifier, const Snapshot& snapshot);
    void unregisterSession(void* context, bool retainRecovery = false);
    void clearRecovery(void* context);
    bool notify(ShutdownReason reason);
    Snapshot snapshot();
private:
    std::mutex m_Mutex;
    void* m_Context = nullptr;
    Notifier m_Notifier;
    Snapshot m_Snapshot;
    void* m_RecoveryContext = nullptr;
    Snapshot m_RecoverySnapshot;
};
}
