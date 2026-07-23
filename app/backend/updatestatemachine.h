#pragma once

#include "autoupdatechecker.h"
#include "updateresult.h"

class UpdateStateMachine
{
public:
    enum Event {
        BeginRestore,
        BeginCheck,
        CandidateAvailable,
        CandidateCurrent,
        BeginDownload,
        DownloadComplete,
        VerificationPassedDesktop,
        VerificationPassedNonDesktop,
        CheckFailed,
        DownloadFailed,
        VerificationFailed,
        RestoreFailed,
        BeginHandOff,
        HandOffAccepted,
        HandOffFailed,
        Retry,
        Cancel
    };

    static UpdateResult<AutoUpdateChecker::State> reduce(
        AutoUpdateChecker::State current, Event event);
};
