#include "rollingupdateparser.h"

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonParseError>
#include <QRegularExpression>
#include <QSet>

namespace {

const char Repository[] = "samelamin/vibertemis";
const char ApplicationId[] = "com.artemisdesktop.ArtemisDesktopDev";
const char RollingTag[] = "steam-deck-latest";
const char ManifestName[] = "artemis-steam-deck-update.json";
const char FlatpakName[] = "artemis-steam-deck.flatpak";
const int ManifestLimit = 64 * 1024;

template<typename T>
UpdateResult<T> failure(UpdateError error, const QString &message)
{
    UpdateResult<T> result;
    result.error = error;
    result.message = message;
    return result;
}

bool fullLowerHex(const QString &value, int length)
{
    static const QRegularExpression hex(QStringLiteral("^[0-9a-f]+$"));
    return value.size() == length && hex.match(value).hasMatch();
}

bool decimalString(const QJsonValue &value, quint64 *number)
{
    if (!value.isString()) {
        return false;
    }
    const QString text = value.toString();
    static const QRegularExpression decimal(QStringLiteral("^(0|[1-9][0-9]*)$"));
    bool ok = false;
    const quint64 parsed = text.toULongLong(&ok, 10);
    if (!ok || !decimal.match(text).hasMatch()) {
        return false;
    }
    *number = parsed;
    return true;
}

bool utcTimestamp(const QJsonValue &value, QDateTime *timestamp)
{
    if (!value.isString()) {
        return false;
    }
    const QString text = value.toString();
    static const QRegularExpression rfc3339(QStringLiteral("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z$"));
    if (!rfc3339.match(text).hasMatch()) {
        return false;
    }
    const QDateTime parsed = QDateTime::fromString(text, Qt::ISODate);
    if (!parsed.isValid()) {
        return false;
    }
    *timestamp = parsed.toUTC();
    return true;
}

UpdateResult<QJsonObject> jsonObject(const QByteArray &document)
{
    QJsonParseError error;
    const QJsonDocument json = QJsonDocument::fromJson(document, &error);
    if (error.error != QJsonParseError::NoError || !json.isObject()) {
        return failure<QJsonObject>(UpdateError::InvalidMetadata,
                                    QStringLiteral("Update metadata is invalid."));
    }
    UpdateResult<QJsonObject> result;
    result.ok = true;
    result.value = json.object();
    return result;
}

bool exactPath(const QUrl &url, const QString &prefix)
{
    return url.path().startsWith(prefix)
        && (url.path().size() == prefix.size()
            || url.path().at(prefix.size()) == QLatin1Char('/'));
}

bool approvedCdnHost(const QString &host)
{
    const QStringList roots = {
        QStringLiteral("objects.githubusercontent.com"),
        QStringLiteral("github-releases.githubusercontent.com"),
        QStringLiteral("release-assets.githubusercontent.com")
    };
    for (const QString &root : roots) {
        if (host == root || host.endsWith(QLatin1Char('.') + root)) {
            return true;
        }
    }
    return false;
}

UpdateResult<RollingAssetIdentity> parseAsset(const QJsonObject &asset)
{
    RollingAssetIdentity result;
    if (!decimalString(asset.value(QStringLiteral("id")), &result.id)
        || result.id == 0
        || !asset.value(QStringLiteral("name")).isString()
        || asset.value(QStringLiteral("name")).toString().isEmpty()
        || !decimalString(asset.value(QStringLiteral("size")), &result.size)
        || !asset.value(QStringLiteral("url")).isString()
        || !asset.value(QStringLiteral("browser_download_url")).isString()
        || !utcTimestamp(asset.value(QStringLiteral("updated_at")), &result.updatedAt)) {
        return failure<RollingAssetIdentity>(UpdateError::InvalidMetadata,
                                             QStringLiteral("Release asset metadata is invalid."));
    }
    result.name = asset.value(QStringLiteral("name")).toString();
    result.apiUrl = QUrl(asset.value(QStringLiteral("url")).toString());
    result.downloadUrl = QUrl(asset.value(QStringLiteral("browser_download_url")).toString());
    if (!RollingUpdateParser::isApprovedUrl(result.apiUrl)
        || !RollingUpdateParser::isApprovedUrl(result.downloadUrl)) {
        return failure<RollingAssetIdentity>(UpdateError::UnsafeUrl,
                                             QStringLiteral("Release asset URL is not approved."));
    }
    UpdateResult<RollingAssetIdentity> parsed;
    parsed.ok = true;
    parsed.value = result;
    return parsed;
}

bool sameAsset(const RollingAssetIdentity &left, const RollingAssetIdentity &right, bool checkDigest)
{
    return left.id == right.id
        && left.name == right.name
        && left.size == right.size
        && left.apiUrl == right.apiUrl
        && left.downloadUrl == right.downloadUrl
        && left.updatedAt == right.updatedAt
        && (!checkDigest || left.sha256 == right.sha256);
}

bool sameCandidate(const RollingUpdateCandidate &left, const RollingUpdateCandidate &right)
{
    return left.releaseId == right.releaseId
        && left.releaseLabel == right.releaseLabel
        && left.releasePage == right.releasePage
        && left.releaseUpdatedAt == right.releaseUpdatedAt
        && left.sourceCommit == right.sourceCommit
        && left.sequence == right.sequence
        && left.manifestSchema == right.manifestSchema
        && left.publishedAt == right.publishedAt
        && sameAsset(left.manifest, right.manifest, false)
        && sameAsset(left.flatpak, right.flatpak, true);
}

} // namespace

bool RollingUpdateParser::isApprovedUrl(const QUrl &url)
{
    if (!url.isValid()
        || url.scheme() != QStringLiteral("https")
        || !url.userInfo().isEmpty()
        || url.hasQuery()
        || !url.fragment().isEmpty()
        || url.port() != -1) {
        return false;
    }

    const QString host = url.host().toLower();
    const QString apiPrefix = QStringLiteral("/repos/") + QString::fromLatin1(Repository);
    const QString releasePrefix = QStringLiteral("/") + QString::fromLatin1(Repository) + QStringLiteral("/releases");
    if (host == QStringLiteral("api.github.com")) {
        return exactPath(url, apiPrefix);
    }
    if (host == QStringLiteral("github.com")) {
        return exactPath(url, releasePrefix);
    }
    return approvedCdnHost(host);
}

UpdateResult<RollingUpdateCandidate> RollingUpdateParser::parseRelease(const QByteArray &document)
{
    const UpdateResult<QJsonObject> parsed = jsonObject(document);
    if (!parsed.ok) {
        return failure<RollingUpdateCandidate>(parsed.error, parsed.message);
    }
    const QJsonObject json = parsed.value;
    RollingUpdateCandidate candidate;
    if (!decimalString(json.value(QStringLiteral("id")), &candidate.releaseId)
        || candidate.releaseId == 0
        || json.value(QStringLiteral("tag_name")).toString() != QString::fromLatin1(RollingTag)
        || !json.value(QStringLiteral("html_url")).isString()
        || !utcTimestamp(json.value(QStringLiteral("updated_at")), &candidate.releaseUpdatedAt)
        || !json.value(QStringLiteral("assets")).isArray()) {
        return failure<RollingUpdateCandidate>(UpdateError::InvalidMetadata,
                                               QStringLiteral("Rolling release metadata is invalid."));
    }
    candidate.releaseLabel = QString::fromLatin1(RollingTag);
    candidate.releasePage = QUrl(json.value(QStringLiteral("html_url")).toString());
    const QString expectedPage = QStringLiteral("/") + QString::fromLatin1(Repository)
        + QStringLiteral("/releases/tag/") + QString::fromLatin1(RollingTag);
    if (!isApprovedUrl(candidate.releasePage) || candidate.releasePage.path() != expectedPage) {
        return failure<RollingUpdateCandidate>(UpdateError::UnsafeUrl,
                                               QStringLiteral("Rolling release page URL is not approved."));
    }

    int manifests = 0;
    int flatpaks = 0;
    for (const QJsonValue &value : json.value(QStringLiteral("assets")).toArray()) {
        if (!value.isObject()) {
            return failure<RollingUpdateCandidate>(UpdateError::InvalidMetadata,
                                                   QStringLiteral("Rolling release asset is invalid."));
        }
        const UpdateResult<RollingAssetIdentity> asset = parseAsset(value.toObject());
        if (!asset.ok) {
            return failure<RollingUpdateCandidate>(asset.error, asset.message);
        }
        if (asset.value.name == QString::fromLatin1(ManifestName)) {
            ++manifests;
            candidate.manifest = asset.value;
        } else if (asset.value.name == QString::fromLatin1(FlatpakName)) {
            ++flatpaks;
            candidate.flatpak = asset.value;
        }
    }
    if (manifests != 1 || flatpaks != 1) {
        return failure<RollingUpdateCandidate>(UpdateError::InvalidMetadata,
                                               QStringLiteral("Rolling release assets are incomplete."));
    }
    UpdateResult<RollingUpdateCandidate> result;
    result.ok = true;
    result.value = candidate;
    return result;
}

UpdateResult<RollingUpdateCandidate> RollingUpdateParser::parseManifest(
    const QByteArray &document, const RollingUpdateCandidate &release)
{
    if (document.size() > ManifestLimit) {
        return failure<RollingUpdateCandidate>(UpdateError::ResponseTooLarge,
                                               QStringLiteral("Update manifest is too large."));
    }
    const UpdateResult<QJsonObject> parsed = jsonObject(document);
    if (!parsed.ok) {
        return failure<RollingUpdateCandidate>(parsed.error, parsed.message);
    }
    const QJsonObject json = parsed.value;
    RollingUpdateCandidate candidate = release;
    if (!json.value(QStringLiteral("schema")).isDouble()
        || json.value(QStringLiteral("schema")).toDouble() != 1.0
        || json.value(QStringLiteral("repository")).toString() != QString::fromLatin1(Repository)
        || json.value(QStringLiteral("application_id")).toString() != QString::fromLatin1(ApplicationId)
        || !json.value(QStringLiteral("source_commit")).isString()
        || !fullLowerHex(json.value(QStringLiteral("source_commit")).toString(), 40)
        || !decimalString(json.value(QStringLiteral("build_sequence")), &candidate.sequence)
        || candidate.sequence == 0
        || !decimalString(json.value(QStringLiteral("release_id")), &candidate.releaseId)
        || candidate.releaseId != release.releaseId
        || json.value(QStringLiteral("tag")).toString() != release.releaseLabel
        || json.value(QStringLiteral("tag_commit")).toString() != json.value(QStringLiteral("source_commit")).toString()
        || !json.value(QStringLiteral("flatpak")).isObject()
        || !utcTimestamp(json.value(QStringLiteral("published_at")), &candidate.publishedAt)) {
        return failure<RollingUpdateCandidate>(UpdateError::InvalidMetadata,
                                               QStringLiteral("Update manifest is invalid."));
    }
    candidate.manifestSchema = 1;
    candidate.sourceCommit = json.value(QStringLiteral("source_commit")).toString();
    const QJsonObject flatpak = json.value(QStringLiteral("flatpak")).toObject();
    quint64 flatpakId = 0;
    quint64 flatpakSize = 0;
    const QString digest = flatpak.value(QStringLiteral("sha256")).toString();
    if (!decimalString(flatpak.value(QStringLiteral("asset_id")), &flatpakId)
        || !decimalString(flatpak.value(QStringLiteral("size")), &flatpakSize)
        || flatpak.value(QStringLiteral("name")).toString() != release.flatpak.name
        || flatpakId != release.flatpak.id
        || flatpakSize != release.flatpak.size
        || !fullLowerHex(digest, 64)) {
        return failure<RollingUpdateCandidate>(UpdateError::InvalidMetadata,
                                               QStringLiteral("Update manifest Flatpak binding is invalid."));
    }
    candidate.flatpak.sha256 = digest.toLatin1();
    UpdateResult<RollingUpdateCandidate> result;
    result.ok = true;
    result.value = candidate;
    return result;
}

UpdateResult<RollingTagObject> RollingUpdateParser::parseTagReference(const QByteArray &document)
{
    const UpdateResult<QJsonObject> parsed = jsonObject(document);
    if (!parsed.ok || !parsed.value.value(QStringLiteral("object")).isObject()) {
        return failure<RollingTagObject>(UpdateError::InvalidMetadata,
                                         QStringLiteral("Tag reference is invalid."));
    }
    const QJsonObject object = parsed.value.value(QStringLiteral("object")).toObject();
    const QString type = object.value(QStringLiteral("type")).toString();
    const QString sha = object.value(QStringLiteral("sha")).toString();
    if ((type != QStringLiteral("tag") && type != QStringLiteral("commit")) || !fullLowerHex(sha, 40)) {
        return failure<RollingTagObject>(UpdateError::InvalidMetadata,
                                         QStringLiteral("Tag reference is invalid."));
    }
    UpdateResult<RollingTagObject> result;
    result.ok = true;
    result.value = RollingTagObject{sha, type, sha};
    return result;
}

UpdateResult<RollingTagObject> RollingUpdateParser::parseTagObject(const QByteArray &document)
{
    const UpdateResult<QJsonObject> parsed = jsonObject(document);
    if (!parsed.ok || !parsed.value.value(QStringLiteral("object")).isObject()) {
        return failure<RollingTagObject>(UpdateError::InvalidMetadata,
                                         QStringLiteral("Tag object is invalid."));
    }
    const QJsonObject object = parsed.value.value(QStringLiteral("object")).toObject();
    const QString id = parsed.value.value(QStringLiteral("sha")).toString();
    const QString type = object.value(QStringLiteral("type")).toString();
    const QString target = object.value(QStringLiteral("sha")).toString();
    if (!fullLowerHex(id, 40) || !fullLowerHex(target, 40)
        || (type != QStringLiteral("tag") && type != QStringLiteral("commit"))) {
        return failure<RollingTagObject>(UpdateError::InvalidMetadata,
                                         QStringLiteral("Tag object is invalid."));
    }
    UpdateResult<RollingTagObject> result;
    result.ok = true;
    result.value = RollingTagObject{id, type, target};
    return result;
}

UpdateResult<TagResolution> RollingUpdateParser::resolveTagCommit(
    const RollingTagObject &reference, const QList<RollingTagObject> &objects)
{
    if ((reference.type != QStringLiteral("tag") && reference.type != QStringLiteral("commit"))
        || !fullLowerHex(reference.targetId, 40)) {
        return failure<TagResolution>(UpdateError::InvalidMetadata,
                                      QStringLiteral("Tag reference is invalid."));
    }
    QString current = reference.targetId;
    QSet<QString> visited;
    for (;;) {
        if (visited.contains(current)) {
            return failure<TagResolution>(UpdateError::InvalidMetadata,
                                          QStringLiteral("Tag object cycle detected."));
        }
        visited.insert(current);
        bool found = false;
        for (const RollingTagObject &object : objects) {
            if (object.id != current) {
                continue;
            }
            found = true;
            if (object.type == QStringLiteral("commit") && fullLowerHex(object.targetId, 40)) {
                UpdateResult<TagResolution> result;
                result.ok = true;
                result.value = TagResolution{reference.targetId, object.targetId, object.targetId};
                return result;
            }
            if (object.type != QStringLiteral("tag") || !fullLowerHex(object.targetId, 40)) {
                return failure<TagResolution>(UpdateError::InvalidMetadata,
                                              QStringLiteral("Tag object is invalid."));
            }
            current = object.targetId;
            break;
        }
        if (!found) {
            return failure<TagResolution>(UpdateError::InvalidMetadata,
                                          QStringLiteral("Tag object is missing."));
        }
    }
}

UpdateResult<RollingUpdateCandidate> RollingUpdateParser::bindTagResolution(
    const RollingUpdateCandidate &candidate, const TagResolution &resolution)
{
    if (!fullLowerHex(resolution.tagRefObjectId, 40)
        || !fullLowerHex(resolution.tagObjectId, 40)
        || resolution.commit != candidate.sourceCommit) {
        return failure<RollingUpdateCandidate>(UpdateError::InvalidMetadata,
                                               QStringLiteral("Tag does not resolve to the published commit."));
    }
    UpdateResult<RollingUpdateCandidate> result;
    result.ok = true;
    result.value = candidate;
    result.value.tagRefObjectId = resolution.tagRefObjectId;
    result.value.tagObjectId = resolution.tagObjectId;
    return result;
}

CommitRelation RollingUpdateParser::parseCommitRelation(const QByteArray &document)
{
    const UpdateResult<QJsonObject> parsed = jsonObject(document);
    if (!parsed.ok) {
        return CommitRelation::Unknown;
    }
    const QString status = parsed.value.value(QStringLiteral("status")).toString();
    if (status == QStringLiteral("ahead")) return CommitRelation::CandidateAhead;
    if (status == QStringLiteral("identical")) return CommitRelation::Equal;
    if (status == QStringLiteral("behind")) return CommitRelation::CandidateBehind;
    if (status == QStringLiteral("diverged")) return CommitRelation::Diverged;
    return CommitRelation::Unknown;
}

UpdateResult<bool> RollingUpdateParser::matchesRelease(const RollingUpdateCandidate &expected,
                                                       const QByteArray &document)
{
    const UpdateResult<RollingUpdateCandidate> parsed = parseRelease(document);
    if (!parsed.ok
        || parsed.value.releaseId != expected.releaseId
        || parsed.value.releaseLabel != expected.releaseLabel
        || parsed.value.releasePage != expected.releasePage
        || parsed.value.releaseUpdatedAt != expected.releaseUpdatedAt
        || !sameAsset(parsed.value.manifest, expected.manifest, false)
        || !sameAsset(parsed.value.flatpak, expected.flatpak, false)) {
        return failure<bool>(UpdateError::PublisherChanged,
                             QStringLiteral("Rolling release metadata changed."));
    }
    UpdateResult<bool> result;
    result.ok = true;
    result.value = true;
    return result;
}

UpdateResult<bool> RollingUpdateParser::matchesManifest(const RollingUpdateCandidate &expected,
                                                        const QByteArray &document)
{
    const UpdateResult<RollingUpdateCandidate> parsed = parseManifest(document, expected);
    if (!parsed.ok || !sameCandidate(parsed.value, expected)) {
        return failure<bool>(UpdateError::PublisherChanged,
                             QStringLiteral("Update manifest changed."));
    }
    UpdateResult<bool> result;
    result.ok = true;
    result.value = true;
    return result;
}

UpdateResult<bool> RollingUpdateParser::matchesTagResolution(const RollingUpdateCandidate &expected,
                                                              const TagResolution &resolution)
{
    if (resolution.tagRefObjectId != expected.tagRefObjectId
        || resolution.tagObjectId != expected.tagObjectId
        || resolution.commit != expected.sourceCommit) {
        return failure<bool>(UpdateError::PublisherChanged,
                             QStringLiteral("Rolling tag binding changed."));
    }
    UpdateResult<bool> result;
    result.ok = true;
    result.value = true;
    return result;
}

UpdateResult<bool> RollingUpdateParser::isInstallable(const QString &runningCommit,
                                                       quint64 runningSequence,
                                                       const RollingUpdateCandidate &candidate,
                                                       CommitRelation relation)
{
    if (!fullLowerHex(runningCommit, 40) || !fullLowerHex(candidate.sourceCommit, 40)
        || relation != CommitRelation::CandidateAhead
        || candidate.sequence <= runningSequence) {
        return failure<bool>(UpdateError::CandidateNotAhead,
                             QStringLiteral("No newer rolling update is available."));
    }
    UpdateResult<bool> result;
    result.ok = true;
    result.value = true;
    return result;
}
