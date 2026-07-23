#include "updatestatemachine.h"

namespace {

UpdateResult<AutoUpdateChecker::State> transition(AutoUpdateChecker::State state)
{
    UpdateResult<AutoUpdateChecker::State> result;
    result.ok = true;
    result.value = state;
    return result;
}

UpdateResult<AutoUpdateChecker::State> rejected()
{
    UpdateResult<AutoUpdateChecker::State> result;
    result.error = UpdateError::InvalidMetadata;
    result.message = QStringLiteral("The requested update action is not valid in the current state.");
    return result;
}

} // namespace

UpdateResult<AutoUpdateChecker::State> UpdateStateMachine::reduce(
    AutoUpdateChecker::State current, Event event)
{
    typedef AutoUpdateChecker::State S;
    typedef UpdateStateMachine::Event E;
    switch (current) {
    case S::Idle:
        if (event == E::BeginRestore) return transition(S::RestoringPending);
        if (event == E::BeginCheck) return transition(S::Checking);
        break;
    case S::RestoringPending:
        if (event == E::VerificationPassedDesktop) return transition(S::ReadyToHandOff);
        if (event == E::VerificationPassedNonDesktop) return transition(S::ReadyForDesktop);
        if (event == E::CandidateCurrent) return transition(S::NoUpdate);
        if (event == E::BeginCheck) return transition(S::Checking);
        if (event == E::RestoreFailed) return transition(S::RestoreError);
        if (event == E::Cancel) return transition(S::Cancelled);
        break;
    case S::Checking:
        if (event == E::CandidateCurrent) return transition(S::NoUpdate);
        if (event == E::CandidateAvailable) return transition(S::Available);
        if (event == E::CheckFailed) return transition(S::CheckError);
        if (event == E::Cancel) return transition(S::Cancelled);
        break;
    case S::NoUpdate:
        if (event == E::BeginCheck) return transition(S::Checking);
        break;
    case S::Available:
        if (event == E::BeginDownload) return transition(S::Downloading);
        if (event == E::BeginCheck) return transition(S::Checking);
        if (event == E::Cancel) return transition(S::Cancelled);
        break;
    case S::Downloading:
        if (event == E::DownloadComplete) return transition(S::Verifying);
        if (event == E::DownloadFailed) return transition(S::DownloadError);
        if (event == E::Cancel) return transition(S::Cancelled);
        break;
    case S::Verifying:
        if (event == E::VerificationPassedDesktop) return transition(S::ReadyToHandOff);
        if (event == E::VerificationPassedNonDesktop) return transition(S::ReadyForDesktop);
        if (event == E::VerificationFailed) return transition(S::VerificationError);
        if (event == E::Cancel) return transition(S::Cancelled);
        break;
    case S::ReadyForDesktop:
        if (event == E::Retry) return transition(S::Verifying);
        if (event == E::BeginCheck) return transition(S::Checking);
        if (event == E::Cancel) return transition(S::Cancelled);
        break;
    case S::ReadyToHandOff:
        if (event == E::BeginHandOff) return transition(S::HandingOff);
        if (event == E::Retry) return transition(S::Verifying);
        if (event == E::BeginCheck) return transition(S::Checking);
        if (event == E::Cancel) return transition(S::Cancelled);
        break;
    case S::HandingOff:
        if (event == E::HandOffAccepted) return transition(S::HandOffRequested);
        if (event == E::HandOffFailed) return transition(S::HandOffError);
        break;
    case S::HandOffRequested:
        if (event == E::BeginCheck) return transition(S::Checking);
        if (event == E::Retry) return transition(S::Verifying);
        if (event == E::DiscardPending) return transition(S::Cancelled);
        break;
    case S::HandOffError:
        if (event == E::Retry) return transition(S::Verifying);
        if (event == E::DiscardPending) return transition(S::Cancelled);
        break;
    case S::CheckError:
        if (event == E::Retry || event == E::BeginCheck) return transition(S::Checking);
        break;
    case S::DownloadError:
    case S::VerificationError:
        if (event == E::Retry) return transition(S::Available);
        if (event == E::BeginCheck) return transition(S::Checking);
        break;
    case S::RestoreError:
        if (event == E::Retry) return transition(S::RestoringPending);
        if (event == E::BeginCheck) return transition(S::Checking);
        if (event == E::Cancel) return transition(S::Cancelled);
        break;
    case S::Cancelled:
        if (event == E::BeginCheck) return transition(S::Checking);
        break;
    }
    return rejected();
}
