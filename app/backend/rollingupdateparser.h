#pragma once

#include <QByteArray>
#include <QDateTime>
#include <QList>
#include <QString>
#include <QUrl>

#include "updateresult.h"

struct RollingAssetIdentity {
    quint64 id = 0;
    QString name;
    quint64 size = 0;
    QUrl apiUrl;
    QUrl downloadUrl;
    QDateTime updatedAt;
    QByteArray sha256;
};

struct RollingUpdateCandidate {
    quint64 releaseId = 0;
    QString releaseLabel;
    QUrl releasePage;
    QDateTime releaseUpdatedAt;
    QString sourceCommit;
    quint64 sequence = 0;
    QString tagRefObjectId;
    QString tagObjectId;
    int manifestSchema = 0;
    QDateTime publishedAt;
    RollingAssetIdentity manifest;
    RollingAssetIdentity flatpak;
};

struct RollingTagObject {
    QString id;
    QString type;
    QString targetId;
};

struct TagResolution {
    QString tagRefObjectId;
    QString tagObjectId;
    QString commit;
};

enum class CommitRelation { CandidateAhead, Equal, CandidateBehind, Diverged, Unknown };

class RollingUpdateParser
{
public:
    static UpdateResult<RollingUpdateCandidate> parseRelease(const QByteArray &document);
    static UpdateResult<RollingUpdateCandidate> parseManifest(
        const QByteArray &document, const RollingUpdateCandidate &release);
    static UpdateResult<RollingTagObject> parseTagReference(const QByteArray &document);
    static UpdateResult<RollingTagObject> parseTagObject(const QByteArray &document);
    static UpdateResult<TagResolution> resolveTagCommit(
        const RollingTagObject &reference, const QList<RollingTagObject> &objects);
    static UpdateResult<RollingUpdateCandidate> bindTagResolution(
        const RollingUpdateCandidate &candidate, const TagResolution &resolution);
    static CommitRelation parseCommitRelation(const QByteArray &document);
    // GitHub compare URL for base...head that asks for page 2 of one commit
    // per page. That page still carries status and ahead_by but omits the
    // file list, which on page 1 can exceed the JSON response limit (a jump
    // across the Quest merge was 1.38 MB).
    static QUrl compareUrl(const QString &baseCommit, const QString &headCommit);
    static UpdateResult<bool> matchesRelease(const RollingUpdateCandidate &expected,
                                             const QByteArray &document);
    static UpdateResult<bool> matchesManifest(const RollingUpdateCandidate &expected,
                                              const QByteArray &document);
    static UpdateResult<bool> matchesCandidate(const RollingUpdateCandidate &expected,
                                               const RollingUpdateCandidate &actual);
    static UpdateResult<bool> matchesTagResolution(const RollingUpdateCandidate &expected,
                                                   const TagResolution &resolution);
    static UpdateResult<bool> isInstallable(const QString &runningCommit,
                                            quint64 runningSequence,
                                            const RollingUpdateCandidate &candidate,
                                            CommitRelation relation);
    static UpdateResult<bool> validateCandidate(
        const RollingUpdateCandidate &candidate);

    static bool isApprovedUrl(const QUrl &url);
};
