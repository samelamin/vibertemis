#include "instanceguard.h"
#include <QCryptographicHash>
#include <QDir>
#include <QJsonDocument>
#include <QJsonObject>
#include <QUuid>
#ifdef Q_OS_LINUX
#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#endif

namespace ProcessLifecycle {
namespace {
#ifdef Q_OS_LINUX
qint64 nowMs() { return ShutdownCoordinator::monotonicNowNs() / 1000000; }
struct Fd {
    int value;
    explicit Fd(int fd) : value(fd) {}
    ~Fd() { if (value >= 0) close(value); }
};
bool ready(int fd, short events, qint64 deadline) {
    for (;;) {
        const int remaining = int(qMax<qint64>(0, deadline - nowMs()));
        if (!remaining) return false;
        pollfd p = {fd, events, 0};
        const int rc = poll(&p, 1, remaining);
        if (rc < 0 && errno == EINTR) continue;
        return rc > 0 && (p.revents & events);
    }
}
bool sameUser(int fd) {
    ucred peer = {}; socklen_t length = sizeof(peer);
    return getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &peer, &length) == 0 && peer.uid == getuid();
}
bool address(const QString& path, sockaddr_un* addr) {
    const QByteArray bytes = path.toLocal8Bit();
    if (bytes.size() >= int(sizeof(addr->sun_path))) return false;
    *addr = {}; addr->sun_family = AF_UNIX;
    memcpy(addr->sun_path, bytes.constData(), bytes.size()); return true;
}
bool sendObject(int fd, const QJsonObject& object, qint64 deadline) {
    QByteArray bytes = QJsonDocument(object).toJson(QJsonDocument::Compact) + '\n';
    int offset = 0;
    while (offset < bytes.size()) {
        if (!ready(fd, POLLOUT, deadline)) return false;
        const ssize_t n = send(fd, bytes.constData() + offset, bytes.size() - offset, MSG_NOSIGNAL);
        if (n > 0) offset += n;
        else if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        else return false;
    }
    return true;
}
QJsonObject readObject(int fd, qint64 deadline) {
    QByteArray bytes;
    while (bytes.size() < 2048) {
        if (!ready(fd, POLLIN, deadline)) return {};
        char buffer[256];
        const ssize_t n = recv(fd, buffer, sizeof(buffer), 0);
        if (n > 0) bytes.append(buffer, int(n));
        else if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        else return {};
        const int end = bytes.indexOf('\n');
        if (end >= 0) return QJsonDocument::fromJson(bytes.left(end)).object();
    }
    return {};
}
QJsonObject exchange(const QString& path, const QJsonObject& request, qint64 deadline) {
    Fd fd(socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0));
    sockaddr_un addr;
    if (fd.value < 0 || !address(path, &addr)) return {};
    if (connect(fd.value, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) != 0) {
        if (errno != EINPROGRESS || !ready(fd.value, POLLOUT, deadline)) return {};
        int error = 0; socklen_t size = sizeof(error);
        if (getsockopt(fd.value, SOL_SOCKET, SO_ERROR, &error, &size) || error) return {};
    }
    if (!sameUser(fd.value) || !sendObject(fd.value, request, deadline)) return {};
    return readObject(fd.value, deadline);
}
bool privateDirectory(const QString& path) {
    if (!QDir().mkpath(path)) return false;
    struct stat info = {};
    const auto bytes = path.toLocal8Bit();
    return lstat(bytes.constData(), &info) == 0 && S_ISDIR(info.st_mode) &&
           info.st_uid == getuid() && chmod(bytes.constData(), 0700) == 0;
}
#endif
}
InstanceGuard& InstanceGuard::instance() { static auto* instance = new InstanceGuard; return *instance; }
StreamHandoff InstanceGuard::handoff() {
    std::lock_guard<std::mutex> lock(m_HandoffMutex); return m_Handoff;
}
void InstanceGuard::clearHandoff() {
    std::lock_guard<std::mutex> lock(m_HandoffMutex); m_Handoff = {};
}
void InstanceGuard::setActivationHandler(std::function<void()> handler) {
    std::lock_guard<std::mutex> lock(m_ActivationMutex); m_Activate = std::move(handler);
}
int InstanceGuard::acquire(QString* error) {
#ifdef Q_OS_LINUX
    if (m_LockFd >= 0) return 1;
    Fd fd(open(m_LockPath.toLocal8Bit().constData(), O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600));
    struct stat info = {};
    if (fd.value < 0 || fstat(fd.value, &info) || !S_ISREG(info.st_mode) || info.st_uid != getuid()) {
        *error = QStringLiteral("Cannot open the Vibertemis instance lock safely."); return -1;
    }
    if (flock(fd.value, LOCK_EX | LOCK_NB) != 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
        *error = QStringLiteral("Cannot lock the Vibertemis data directory."); return -1;
    }
    m_LockFd = fd.value; fd.value = -1; // Never close or unlink this lock in userspace.
    return 1;
#else
    Q_UNUSED(error); return 1;
#endif
}
InstanceGuard::Result InstanceGuard::enter(const QString& dataDir, const QString& runtimeDir,
                                         bool gaming, qint64 launchTimeNs, QString* error) {
#ifdef Q_OS_LINUX
    m_Gaming = gaming; m_StartedNs = launchTimeNs;
    m_Generation = QUuid::createUuid().toString(QUuid::WithoutBraces);
    if (dataDir.isEmpty() || !QDir().mkpath(dataDir)) {
        *error = QStringLiteral("Cannot create the Vibertemis data directory."); return Failed;
    }
    m_LockPath = QDir(dataDir).filePath(QStringLiteral("artemis-single-instance.lock"));
    const auto hash = QCryptographicHash::hash(QDir(dataDir).canonicalPath().toUtf8(), QCryptographicHash::Sha256).toHex().left(16);
    if (!runtimeDir.isEmpty()) m_SocketPath = QDir(runtimeDir).filePath(QStringLiteral("vb-%1/ipc").arg(QString::fromLatin1(hash)));
    qint64 deadline = nowMs() + 6000;
    QString observed;
    bool replacementSent = false, acknowledged = false;
    while (!ShutdownCoordinator::instance().shutdownRequested()) {
        const int result = acquire(error);
        if (result < 0) return Failed;
        if (result > 0) {
            if (!startService(error)) return Failed;
            return Primary;
        }
        if (nowMs() >= deadline) break;
        QJsonObject status = exchange(m_SocketPath, {{"op", "status"}}, qMin(deadline, nowMs() + 1000));
        const QString generation = status.value("generation").toString();
        if (!generation.isEmpty()) {
            if (observed.isEmpty()) observed = generation;
            const bool shutting = status.value("shuttingDown").toBool();
            if (shutting) {
                m_Handoff.hostUuid = status.value("host").toString();
                m_Handoff.appId = status.value("app").toInt();
                if (!acknowledged) { deadline = nowMs() + 6000; acknowledged = true; }
            } else if (!(replacementSent && generation == observed)) {
                const bool replace = gaming && status.value("gaming").toBool() && !replacementSent &&
                    generation == observed && status.value("started").toString().toLongLong() < launchTimeNs;
                QJsonObject request{{"op", replace ? "replace" : "activate"}, {"generation", generation}};
                if (replace) replacementSent = true;
                const auto reply = exchange(m_SocketPath, request, qMin(deadline, nowMs() + 1000));
                if (reply.value("state").toString() == "shutting-down") {
                    m_Handoff.hostUuid = reply.value("host").toString();
                    m_Handoff.appId = reply.value("app").toInt();
                    if (!acknowledged) { deadline = nowMs() + 6000; acknowledged = true; }
                } else if (reply.value("state").toString() == "active") return Activated;
            }
        }
        timespec nap = {0, 25 * 1000 * 1000}; nanosleep(&nap, nullptr);
    }
    *error = QStringLiteral("Vibertemis is still closing. Wait a moment and open it again.");
    return Failed;
#else
    Q_UNUSED(dataDir); Q_UNUSED(runtimeDir); Q_UNUSED(gaming); Q_UNUSED(launchTimeNs); Q_UNUSED(error);
    return Primary;
#endif
}
bool InstanceGuard::startService(QString* error) {
#ifdef Q_OS_LINUX
    sockaddr_un addr;
    if (m_SocketPath.isEmpty() || !address(m_SocketPath, &addr) ||
        !privateDirectory(QFileInfo(m_SocketPath).absolutePath())) {
        *error = QStringLiteral("Cannot create a private Vibertemis control socket."); return false;
    }
    m_ListenFd = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (m_ListenFd < 0) { *error = QStringLiteral("Cannot open the control socket."); return false; }
    unlink(m_SocketPath.toLocal8Bit().constData()); // Only called while holding the flock.
    if (bind(m_ListenFd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) ||
        chmod(m_SocketPath.toLocal8Bit().constData(), 0600) || listen(m_ListenFd, 8)) {
        *error = QStringLiteral("Cannot listen on the Vibertemis control socket."); return false;
    }
    pthread_attr_t attr;
    if (pthread_attr_init(&attr)) return false;
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);
    pthread_t thread;
    const int rc = pthread_create(&thread, &attr, &InstanceGuard::serviceThread, this);
    pthread_attr_destroy(&attr);
    if (rc) *error = QStringLiteral("Cannot start the Vibertemis control service.");
    return rc == 0;
#else
    Q_UNUSED(error); return true;
#endif
}
void* InstanceGuard::serviceThread(void* context) { static_cast<InstanceGuard*>(context)->serve(); return nullptr; }
void InstanceGuard::serve() {
#ifdef Q_OS_LINUX
    StreamHandoff replacementHandoff;
    for (;;) {
        Fd client(accept4(m_ListenFd, nullptr, nullptr, SOCK_CLOEXEC | SOCK_NONBLOCK));
        if (client.value < 0) {
            if (errno == EINTR || errno == ECONNABORTED) continue;
            // Resource pressure must not permanently remove the control service.
            timespec backoff = {0, 250 * 1000 * 1000}; nanosleep(&backoff, nullptr);
            continue;
        }
        if (!sameUser(client.value)) continue;
        const qint64 deadline = nowMs() + 1000;
        const auto request = readObject(client.value, deadline);
        if (request.isEmpty()) continue;
        auto& shutdown = ShutdownCoordinator::instance();
        QJsonObject reply{{"generation", m_Generation}, {"started", QString::number(m_StartedNs)},
                          {"gaming", m_Gaming}, {"shuttingDown", shutdown.shutdownRequested()}};
        const QString op = request.value("op").toString();
        bool replace = false;
        if (op == "status") {
            // No Session/Qt locks: must work during teardown.
            reply["host"] = replacementHandoff.hostUuid; reply["app"] = replacementHandoff.appId;
        } else if (request.value("generation").toString() != m_Generation) {
            reply["state"] = "stale";
        } else if (op == "replace" && m_Gaming) {
            if (!shutdown.shutdownRequested()) {
                const auto snapshot = ActiveSessionRegistry::instance().snapshot();
                replacementHandoff.hostUuid = snapshot.hostUuid; replacementHandoff.appId = snapshot.appId;
                if (!replacementHandoff.isValid()) replacementHandoff = handoff();
                replace = true;
            }
            reply["state"] = "shutting-down";
            reply["host"] = replacementHandoff.hostUuid; reply["app"] = replacementHandoff.appId;
        } else if (op == "activate" || op == "replace") {
            reply["state"] = shutdown.shutdownRequested() ? "shutting-down" : "active";
            if (!shutdown.shutdownRequested()) {
                std::lock_guard<std::mutex> lock(m_ActivationMutex);
                if (m_Activate) m_Activate();
            }
        } else reply["state"] = "invalid";
        sendObject(client.value, reply, deadline);
        // The waiter gets its handoff before Qt is allowed to destroy anything.
        if (replace) shutdown.requestShutdown(ShutdownReason::ReplaceRequest);
    }
#endif
}
}
