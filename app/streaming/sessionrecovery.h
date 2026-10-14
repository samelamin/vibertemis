#pragma once

#include <QtGlobal>
#include <atomic>

namespace SessionRecovery {

// BOOTTIME advances during suspend; MONOTONIC does not. Ordinary scheduling
// delays advance both, and must never be interpreted as sleep.
class SuspendDetector {
public:
    bool sample(qint64 bootNs, qint64 monotonicNs, bool valid = true);
private:
    bool m_Initialized{false};
    qint64 m_Offset{0};
};

// Shared by callback, SDL and Qt threads. A cleaned Session is only a retry
// descriptor: it can hand out one fresh Session, never restart itself.
class RecoveryGate {
public:
    bool beginTermination(bool recoverable, bool unexpected = true);
    void beginCleanup() { m_State.fetch_or(Terminating); }
    void completeCleanup() { m_State.fetch_or(Cleaned); }
    void cancel() { m_State.fetch_or(Cancelled); }
    bool canResume() const;
    bool takeResume();
    bool wasInterrupted() const { return (m_State.load() & Interrupted) != 0; }
    bool cleaned() const { return (m_State.load() & Cleaned) != 0; }
private:
    enum : unsigned { Terminating = 1, Recoverable = 2, Cleaned = 4,
                      Cancelled = 8, ResumeTaken = 16, Interrupted = 32 };
    static bool mayResume(unsigned state);
    std::atomic<unsigned> m_State{0};
};

struct CleanupDecision { bool quitHost; bool exitClient; };
CleanupDecision cleanupDecision(bool unexpected, bool quitHostPreference, bool explicitQuit);

#ifdef Q_OS_LINUX
bool readSuspendClocks(qint64& bootNs, qint64& monotonicNs);
#endif
}
