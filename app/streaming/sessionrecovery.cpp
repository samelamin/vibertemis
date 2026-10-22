#include "sessionrecovery.h"
#ifdef Q_OS_LINUX
#include <time.h>
#endif

namespace SessionRecovery {
bool SuspendDetector::sample(qint64 bootNs, qint64 monotonicNs, bool valid)
{
    if (!valid || bootNs < 0 || monotonicNs < 0 || bootNs < monotonicNs) {
        // A failed/preempted read must not erase a pre-sleep baseline.
        return false;
    }
    const qint64 offset = bootNs - monotonicNs;
    const bool woke = m_Initialized && offset > m_Offset &&
                     offset - m_Offset > 1000000000LL;
    m_Offset = offset;
    m_Initialized = true;
    return woke;
}

bool RecoveryGate::beginTermination(bool recoverable, bool unexpected)
{
    unsigned state = m_State.load();
    do {
        if (state & Terminating) return false;
    } while (!m_State.compare_exchange_weak(
        state, state | Terminating | (recoverable ? Recoverable : 0) |
               (unexpected ? Interrupted : 0)));
    return true;
}

bool RecoveryGate::mayResume(unsigned state)
{
    return (state & (Cleaned | Recoverable | Cancelled | ResumeTaken)) ==
           (Cleaned | Recoverable);
}

bool RecoveryGate::canResume() const
{
    return mayResume(m_State.load());
}

bool RecoveryGate::takeResume()
{
    unsigned state = m_State.load();
    do {
        if (!mayResume(state)) return false;
    } while (!m_State.compare_exchange_weak(state, state | ResumeTaken));
    return true;
}

CleanupDecision cleanupDecision(bool unexpected, bool quitHostPreference, bool explicitQuit,
                                bool applicationQuit, bool preserveHost)
{
    return {!preserveHost && (explicitQuit || (!unexpected && quitHostPreference)), explicitQuit || applicationQuit};
}

#ifdef Q_OS_LINUX
bool readSuspendClocks(qint64& bootNs, qint64& monotonicNs)
{
    timespec boot{}, mono{}, after{};
    if (clock_gettime(CLOCK_MONOTONIC, &mono) != 0 ||
        clock_gettime(CLOCK_BOOTTIME, &boot) != 0 ||
        clock_gettime(CLOCK_MONOTONIC, &after) != 0) return false;
    const qint64 beforeNs = qint64(mono.tv_sec) * 1000000000LL + mono.tv_nsec;
    monotonicNs = qint64(after.tv_sec) * 1000000000LL + after.tv_nsec;
    // Reject a sample interrupted by a long scheduling delay between reads.
    if (monotonicNs < beforeNs || monotonicNs - beforeNs > 50000000LL) return false;
    monotonicNs = beforeNs;
    bootNs = qint64(boot.tv_sec) * 1000000000LL + boot.tv_nsec;
    return true;
}
#endif
}
