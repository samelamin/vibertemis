#pragma once

#include <QString>

enum class UpdateError {
    None,
    InvalidBuildIdentity,
    InvalidMetadata,
    UnsafeUrl,
    HttpFailure,
    RateLimited,
    Timeout,
    RedirectRejected,
    ResponseTooLarge,
    CandidateNotAhead,
    InsufficientSpace,
    UnsafePath,
    IoFailure,
    SizeMismatch,
    DigestMismatch,
    PublisherChanged,
    Cancelled,
    SessionNotDesktop,
    PortalFailure
};

template<typename T>
struct UpdateResult {
    bool ok = false;
    T value {};
    UpdateError error = UpdateError::None;
    QString message;
};
