#pragma once
#include "shutdowncoordinator.h"
#include <QString>
#include <functional>
#include <mutex>

namespace ProcessLifecycle {
struct StreamHandoff {
    QString hostUuid;
    int appId = 0;
    bool isValid() const { return !hostUuid.isEmpty() && appId > 0; }
};
// Linux normal-GUI launches only. All state, including the flock fd, survives
// destruction of Qt and is released by the kernel at process death.
class InstanceGuard {
public:
    enum Result { Primary, Activated, Failed };
    static InstanceGuard& instance();
    Result enter(const QString& dataDir, const QString& runtimeDir, bool gaming,
                 qint64 launchTimeNs, QString* error);
    StreamHandoff handoff();
    void clearHandoff();
    void setActivationHandler(std::function<void()> handler);
private:
    int acquire(QString* error);
    bool startService(QString* error);
    void serve();
    static void* serviceThread(void* context);
    QString m_LockPath, m_SocketPath;
    int m_LockFd = -1, m_ListenFd = -1;
    bool m_Gaming = false;
    QString m_Generation;
    qint64 m_StartedNs = 0;
    StreamHandoff m_Handoff;
    std::mutex m_HandoffMutex;
    std::mutex m_ActivationMutex;
    std::function<void()> m_Activate;
};
}
