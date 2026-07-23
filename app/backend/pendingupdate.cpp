#include "pendingupdate.h"

#include <limits>

#include <QCryptographicHash>
#include <QDir>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QRegularExpression>
#include <QSaveFile>
#include <QStandardPaths>
#include <QStorageInfo>

#ifdef Q_OS_UNIX
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

namespace {

const quint64 DownloadSafetyMargin = 64ULL * 1024ULL * 1024ULL;
const qint64 StalePartSeconds = 24LL * 60LL * 60LL;
const int PendingRecordLimit = 64 * 1024;

class SystemStorageProbe final : public StorageProbe
{
public:
    quint64 bytesAvailable(const QString &directory) const override
    {
        const qint64 available = QStorageInfo(directory).bytesAvailable();
        return available < 0 ? 0 : static_cast<quint64>(available);
    }

    QDateTime nowUtc() const override
    {
        return QDateTime::currentDateTimeUtc();
    }
};

bool isLowerHex(const QString &value, int length)
{
    static const QRegularExpression LowerHex(QStringLiteral("^[0-9a-f]+$"));
    return value.size() == length && LowerHex.match(value).hasMatch();
}

bool hasExactKeys(const QJsonObject &object, const QStringList &keys)
{
    if (object.size() != keys.size()) {
        return false;
    }
    for (const QString &key : keys) {
        if (!object.contains(key)) {
            return false;
        }
    }
    return true;
}

bool parseDecimal(const QJsonValue &value, quint64 *result, bool allowZero = false)
{
    if (!value.isString()) {
        return false;
    }
    const QString text = value.toString();
    static const QRegularExpression Decimal(QStringLiteral("^(0|[1-9][0-9]*)$"));
    bool ok = false;
    const quint64 parsed = text.toULongLong(&ok, 10);
    if (!ok || !Decimal.match(text).hasMatch() || (!allowZero && parsed == 0)) {
        return false;
    }
    *result = parsed;
    return true;
}

QString timestampText(const QDateTime &dateTime)
{
    const QDateTime utc = dateTime.toUTC();
    return utc.time().msec() == 0
        ? utc.toString(Qt::ISODate)
        : utc.toString(Qt::ISODateWithMs);
}

bool parseTimestamp(const QJsonValue &value, QDateTime *result)
{
    if (!value.isString()) {
        return false;
    }
    const QString text = value.toString();
    const QDateTime parsed = QDateTime::fromString(text, Qt::ISODate);
    if (!parsed.isValid() || parsed.offsetFromUtc() != 0
            || timestampText(parsed) != text) {
        return false;
    }
    *result = parsed;
    return true;
}

bool candidateIsValid(const RollingUpdateCandidate &candidate)
{
    return RollingUpdateParser::validateCandidate(candidate).ok;
}

QJsonObject serializeAsset(const RollingAssetIdentity &asset)
{
    QJsonObject object;
    object.insert(QStringLiteral("id"), QString::number(asset.id));
    object.insert(QStringLiteral("name"), asset.name);
    object.insert(QStringLiteral("size"), QString::number(asset.size));
    object.insert(QStringLiteral("api_url"), asset.apiUrl.toString());
    object.insert(QStringLiteral("download_url"), asset.downloadUrl.toString());
    object.insert(QStringLiteral("updated_at"), timestampText(asset.updatedAt));
    object.insert(QStringLiteral("sha256"), QString::fromLatin1(asset.sha256));
    return object;
}

bool parseAsset(const QJsonValue &value, RollingAssetIdentity *asset)
{
    if (!value.isObject()) {
        return false;
    }
    const QJsonObject object = value.toObject();
    const QStringList keys{
        QStringLiteral("id"), QStringLiteral("name"), QStringLiteral("size"),
        QStringLiteral("api_url"), QStringLiteral("download_url"),
        QStringLiteral("updated_at"), QStringLiteral("sha256")
    };
    if (!hasExactKeys(object, keys)
            || !parseDecimal(object.value(QStringLiteral("id")), &asset->id)
            || !parseDecimal(object.value(QStringLiteral("size")), &asset->size)
            || !object.value(QStringLiteral("name")).isString()
            || !object.value(QStringLiteral("api_url")).isString()
            || !object.value(QStringLiteral("download_url")).isString()
            || !object.value(QStringLiteral("sha256")).isString()
            || !parseTimestamp(object.value(QStringLiteral("updated_at")),
                               &asset->updatedAt)) {
        return false;
    }
    asset->name = object.value(QStringLiteral("name")).toString();
    asset->apiUrl = QUrl(object.value(QStringLiteral("api_url")).toString());
    asset->downloadUrl = QUrl(object.value(QStringLiteral("download_url")).toString());
    asset->sha256 =
        object.value(QStringLiteral("sha256")).toString().toLatin1();
    return asset->id != 0 && asset->size != 0 && !asset->name.isEmpty();
}

QJsonObject serializeCandidate(const RollingUpdateCandidate &candidate)
{
    QJsonObject object;
    object.insert(QStringLiteral("release_id"), QString::number(candidate.releaseId));
    object.insert(QStringLiteral("release_label"), candidate.releaseLabel);
    object.insert(QStringLiteral("release_page"), candidate.releasePage.toString());
    object.insert(QStringLiteral("release_updated_at"),
                  timestampText(candidate.releaseUpdatedAt));
    object.insert(QStringLiteral("source_commit"), candidate.sourceCommit);
    object.insert(QStringLiteral("build_sequence"), QString::number(candidate.sequence));
    object.insert(QStringLiteral("tag_ref_object_id"), candidate.tagRefObjectId);
    object.insert(QStringLiteral("tag_object_id"), candidate.tagObjectId);
    object.insert(QStringLiteral("manifest_schema"), candidate.manifestSchema);
    object.insert(QStringLiteral("published_at"), timestampText(candidate.publishedAt));
    object.insert(QStringLiteral("manifest"), serializeAsset(candidate.manifest));
    object.insert(QStringLiteral("flatpak"), serializeAsset(candidate.flatpak));
    return object;
}

bool parseCandidate(const QJsonValue &value, RollingUpdateCandidate *candidate)
{
    if (!value.isObject()) {
        return false;
    }
    const QJsonObject object = value.toObject();
    const QStringList keys{
        QStringLiteral("release_id"), QStringLiteral("release_label"),
        QStringLiteral("release_page"), QStringLiteral("release_updated_at"),
        QStringLiteral("source_commit"), QStringLiteral("build_sequence"),
        QStringLiteral("tag_ref_object_id"), QStringLiteral("tag_object_id"),
        QStringLiteral("manifest_schema"), QStringLiteral("published_at"),
        QStringLiteral("manifest"), QStringLiteral("flatpak")
    };
    if (!hasExactKeys(object, keys)
            || !parseDecimal(object.value(QStringLiteral("release_id")),
                             &candidate->releaseId)
            || !parseDecimal(object.value(QStringLiteral("build_sequence")),
                             &candidate->sequence)
            || !object.value(QStringLiteral("release_label")).isString()
            || !object.value(QStringLiteral("release_page")).isString()
            || !object.value(QStringLiteral("source_commit")).isString()
            || !object.value(QStringLiteral("tag_ref_object_id")).isString()
            || !object.value(QStringLiteral("tag_object_id")).isString()
            || !object.value(QStringLiteral("manifest_schema")).isDouble()
            || object.value(QStringLiteral("manifest_schema")).toDouble() != 1.0
            || !parseTimestamp(object.value(QStringLiteral("release_updated_at")),
                               &candidate->releaseUpdatedAt)
            || !parseTimestamp(object.value(QStringLiteral("published_at")),
                               &candidate->publishedAt)
            || !parseAsset(object.value(QStringLiteral("manifest")),
                           &candidate->manifest)
            || !parseAsset(object.value(QStringLiteral("flatpak")),
                           &candidate->flatpak)) {
        return false;
    }
    candidate->releaseLabel =
        object.value(QStringLiteral("release_label")).toString();
    candidate->releasePage =
        QUrl(object.value(QStringLiteral("release_page")).toString());
    candidate->sourceCommit =
        object.value(QStringLiteral("source_commit")).toString();
    candidate->tagRefObjectId =
        object.value(QStringLiteral("tag_ref_object_id")).toString();
    candidate->tagObjectId =
        object.value(QStringLiteral("tag_object_id")).toString();
    candidate->manifestSchema = 1;
    return candidateIsValid(*candidate);
}

QJsonObject serializeRecord(const PendingUpdateRecord &record)
{
    QJsonObject object;
    object.insert(QStringLiteral("schema"), 1);
    object.insert(QStringLiteral("canonical_path"), record.canonicalPath);
    object.insert(QStringLiteral("candidate"), serializeCandidate(record.candidate));
    object.insert(QStringLiteral("verified_size"), QString::number(record.verifiedSize));
    object.insert(QStringLiteral("verified_sha256"),
                  QString::fromLatin1(record.verifiedSha256));
    return object;
}

bool parseRecord(const QByteArray &document, PendingUpdateRecord *record)
{
    QJsonParseError parseError;
    const QJsonDocument parsed = QJsonDocument::fromJson(document, &parseError);
    if (parseError.error != QJsonParseError::NoError || !parsed.isObject()) {
        return false;
    }
    const QJsonObject object = parsed.object();
    const QStringList keys{
        QStringLiteral("schema"), QStringLiteral("canonical_path"),
        QStringLiteral("candidate"), QStringLiteral("verified_size"),
        QStringLiteral("verified_sha256")
    };
    if (!hasExactKeys(object, keys)
            || !object.value(QStringLiteral("schema")).isDouble()
            || object.value(QStringLiteral("schema")).toDouble() != 1.0
            || !object.value(QStringLiteral("canonical_path")).isString()
            || !object.value(QStringLiteral("verified_sha256")).isString()
            || !parseDecimal(object.value(QStringLiteral("verified_size")),
                             &record->verifiedSize)
            || !parseCandidate(object.value(QStringLiteral("candidate")),
                               &record->candidate)) {
        return false;
    }
    record->schema = 1;
    record->canonicalPath =
        object.value(QStringLiteral("canonical_path")).toString();
    record->verifiedSha256 =
        object.value(QStringLiteral("verified_sha256")).toString().toLatin1();
    return isLowerHex(QString::fromLatin1(record->verifiedSha256), 64)
        && record->verifiedSize == record->candidate.flatpak.size
        && record->verifiedSha256 == record->candidate.flatpak.sha256;
}

bool candidatesMatch(const RollingUpdateCandidate &left,
                     const RollingUpdateCandidate &right)
{
    return candidateIsValid(left) && candidateIsValid(right)
        && RollingUpdateParser::matchesCandidate(left, right).ok;
}

QByteArray readSmallNoFollowFile(const QString &path, bool *ok)
{
    *ok = false;
    QByteArray body;
#ifdef Q_OS_UNIX
    const QByteArray encoded = QFile::encodeName(path);
    struct stat before;
    if (::lstat(encoded.constData(), &before) != 0 || S_ISLNK(before.st_mode)
            || !S_ISREG(before.st_mode) || before.st_size > PendingRecordLimit) {
        return body;
    }
    const int descriptor = ::open(encoded.constData(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) {
        return body;
    }
    struct stat opened;
    if (::fstat(descriptor, &opened) != 0 || !S_ISREG(opened.st_mode)
            || before.st_dev != opened.st_dev || before.st_ino != opened.st_ino
            || opened.st_size > PendingRecordLimit) {
        ::close(descriptor);
        return body;
    }
    QFile file;
    if (!file.open(descriptor, QIODevice::ReadOnly, QFileDevice::AutoCloseHandle)) {
        ::close(descriptor);
        return body;
    }
    body = file.read(PendingRecordLimit + 1);
#else
    const QFileInfo info(path);
    if (info.isSymLink() || !info.isFile()
            || info.size() > PendingRecordLimit) {
        return body;
    }
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) {
        return body;
    }
    body = file.read(PendingRecordLimit + 1);
#endif
    *ok = body.size() <= PendingRecordLimit;
    return body;
}

bool isSafeDirectChild(const QString &root, const QString &path)
{
    const QString canonicalRoot = QFileInfo(root).canonicalFilePath();
    if (canonicalRoot.isEmpty()) {
        return false;
    }

    const QFileInfo pathInfo(path);
    return QDir::cleanPath(pathInfo.absolutePath()) == canonicalRoot;
}

UpdateResult<UpdateFileStore::OpenVerifiedFile> verifiedResult(
    const QSharedPointer<QFile> &file, const QString &path,
    quint64 size, const QByteArray &digest)
{
    UpdateResult<UpdateFileStore::OpenVerifiedFile> result;
    result.ok = true;
    result.value.file = file;
    result.value.canonicalPath = path;
    result.value.size = size;
    result.value.sha256 = digest;
    return result;
}

} // namespace

PendingUpdateStore::PendingUpdateStore(QString downloadsRoot,
                                       QString privateDataRoot,
                                       StorageProbe *probe) :
    m_DownloadsRoot(downloadsRoot.isEmpty()
                        ? QStandardPaths::writableLocation(QStandardPaths::DownloadLocation)
                        : QDir::cleanPath(downloadsRoot)),
    m_PrivateDataRoot(privateDataRoot.isEmpty()
                          ? QStandardPaths::writableLocation(QStandardPaths::AppDataLocation)
                          : QDir::cleanPath(privateDataRoot)),
    m_Probe(probe)
{
    if (!m_Probe) {
        m_OwnedProbe.reset(new SystemStorageProbe);
        m_Probe = m_OwnedProbe.data();
    }
}

PendingUpdateStore::~PendingUpdateStore() = default;

quint64 PendingUpdateStore::safetyMarginBytes()
{
    return DownloadSafetyMargin;
}

UpdateResult<QSharedPointer<QTemporaryFile>> PendingUpdateStore::createDownload(
    quint64 expectedSize)
{
    UpdateResult<QSharedPointer<QTemporaryFile>> result;
    if (expectedSize == 0
            || expectedSize > std::numeric_limits<quint64>::max() - DownloadSafetyMargin
            || m_Probe->bytesAvailable(m_DownloadsRoot)
                < expectedSize + DownloadSafetyMargin) {
        result.error = UpdateError::InsufficientSpace;
        result.message = QStringLiteral("Not enough free space in Downloads");
        return result;
    }

    const QString canonicalRoot = downloadsCanonicalPath();
    if (canonicalRoot.isEmpty()) {
        result.error = UpdateError::UnsafePath;
        result.message = QStringLiteral("Downloads is unavailable");
        return result;
    }

    QSharedPointer<QTemporaryFile> temporary(new QTemporaryFile(
        canonicalRoot + QDir::separator()
        + QStringLiteral(".vibertemis-update-XXXXXX.part")));
    temporary->setAutoRemove(true);
    if (!temporary->open()) {
        result.error = UpdateError::IoFailure;
        result.message = QStringLiteral("Unable to create an update file in Downloads");
        return result;
    }
    if (!isSafeDirectChild(canonicalRoot, temporary->fileName())
            || QFileInfo(temporary->fileName()).isSymLink()) {
        discardTemporary(temporary);
        result.error = UpdateError::UnsafePath;
        result.message = QStringLiteral("Unsafe temporary update path");
        return result;
    }

    result.ok = true;
    result.value = temporary;
    return result;
}

UpdateResult<UpdateFileStore::OpenVerifiedFile> PendingUpdateStore::finalizeAndVerify(
    QSharedPointer<QTemporaryFile> temporary,
    const RollingUpdateCandidate &candidate)
{
    const QString destination = finalPath(candidate);
    if (destination.isEmpty() || !isValidDigest(candidate.flatpak.sha256)
            || candidate.flatpak.size == 0) {
        discardTemporary(temporary);
        return failure(UpdateError::InvalidMetadata,
                       QStringLiteral("Invalid update file binding"));
    }

    const QFileInfo existing(destination);
    if (existing.exists() || existing.isSymLink()) {
        if (existing.isSymLink()) {
            discardTemporary(temporary);
            return failure(UpdateError::UnsafePath,
                           QStringLiteral("The update destination is a symbolic link"));
        }
        const UpdateResult<OpenVerifiedFile> verified =
            openAndVerify(destination, candidate.flatpak.size, candidate.flatpak.sha256);
        if (verified.ok) {
            discardTemporary(temporary);
            return verified;
        }
        if (!isOwnedFinalPath(destination, candidate) || !QFile::remove(destination)) {
            discardTemporary(temporary);
            return verified;
        }
    }

    if (!temporary || !temporary->isOpen()
            || !isSafeDirectChild(m_DownloadsRoot, temporary->fileName())
            || QFileInfo(temporary->fileName()).isSymLink()
            || !temporary->flush()
            || !temporary->seek(0)) {
        discardTemporary(temporary);
        return failure(UpdateError::UnsafePath,
                       QStringLiteral("Unsafe temporary update file"));
    }

    QCryptographicHash hash(QCryptographicHash::Sha256);
    quint64 total = 0;
    char buffer[64 * 1024];
    while (true) {
        const qint64 count = temporary->read(buffer, sizeof(buffer));
        if (count < 0) {
            discardTemporary(temporary);
            return failure(UpdateError::IoFailure,
                           QStringLiteral("Unable to read the downloaded update"));
        }
        if (count == 0) {
            break;
        }
        total += static_cast<quint64>(count);
        if (total > candidate.flatpak.size) {
            discardTemporary(temporary);
            return failure(UpdateError::SizeMismatch,
                           QStringLiteral("The downloaded update is larger than expected"));
        }
#if QT_VERSION >= QT_VERSION_CHECK(6, 4, 0)
        hash.addData(QByteArrayView(buffer, count));
#else
        hash.addData(buffer, count);
#endif
    }
    if (total != candidate.flatpak.size) {
        discardTemporary(temporary);
        return failure(UpdateError::SizeMismatch,
                       QStringLiteral("The downloaded update size does not match"));
    }
    if (hash.result().toHex() != candidate.flatpak.sha256) {
        discardTemporary(temporary);
        return failure(UpdateError::DigestMismatch,
                       QStringLiteral("The downloaded update checksum does not match"));
    }

    const QString source = temporary->fileName();
    temporary->setAutoRemove(false);
    temporary->close();
    if (!QFile::rename(source, destination)) {
        QFile::remove(source);
        return failure(UpdateError::IoFailure,
                       QStringLiteral("Unable to finalize the update file"));
    }

    const UpdateResult<OpenVerifiedFile> verified =
        openAndVerify(destination, candidate.flatpak.size, candidate.flatpak.sha256);
    if (!verified.ok) {
        QFile::remove(destination);
    }
    return verified;
}

UpdateResult<UpdateFileStore::OpenVerifiedFile> PendingUpdateStore::reopenAndVerify(
    const PendingUpdateRecord &record,
    const RollingUpdateCandidate &currentBinding)
{
    if (record.schema != 1 || !candidateIsValid(record.candidate)
            || record.verifiedSize != record.candidate.flatpak.size
            || record.verifiedSha256 != record.candidate.flatpak.sha256
            || !isOwnedFinalPath(record.canonicalPath, record.candidate)) {
        clear(false);
        return failure(UpdateError::InvalidMetadata,
                       QStringLiteral("The pending update record is invalid"));
    }
    if (!candidatesMatch(record.candidate, currentBinding)) {
        clear(true);
        return failure(UpdateError::PublisherChanged,
                       QStringLiteral("The rolling update changed; check again"));
    }
    const UpdateResult<OpenVerifiedFile> verified =
        openAndVerify(record.canonicalPath, record.verifiedSize,
                      record.verifiedSha256);
    if (!verified.ok) {
        if (verified.error != UpdateError::IoFailure) {
            clear(true);
        }
    }
    return verified;
}

bool PendingUpdateStore::save(const PendingUpdateRecord &record)
{
    if (record.schema != 1 || !candidateIsValid(record.candidate)
            || record.verifiedSize != record.candidate.flatpak.size
            || record.verifiedSha256 != record.candidate.flatpak.sha256
            || !isOwnedFinalPath(record.canonicalPath, record.candidate)) {
        return false;
    }
    const UpdateResult<OpenVerifiedFile> verified =
        openAndVerify(record.canonicalPath, record.verifiedSize,
                      record.verifiedSha256);
    if (!verified.ok) {
        return false;
    }
    verified.value.file->close();

    if (!QDir().mkpath(m_PrivateDataRoot)) {
        return false;
    }
    const QString path = recordPath();
    const QFileInfo existing(path);
    if (existing.isSymLink()) {
        return false;
    }
    const QByteArray document =
        QJsonDocument(serializeRecord(record)).toJson(QJsonDocument::Compact);
    if (document.size() > PendingRecordLimit) {
        return false;
    }
    QSaveFile file(path);
    file.setDirectWriteFallback(false);
    if (!file.open(QIODevice::WriteOnly)) {
        return false;
    }
    file.setPermissions(QFileDevice::ReadOwner | QFileDevice::WriteOwner);
    return file.write(document) == document.size() && file.commit();
}

UpdateResult<PendingUpdateRecord> PendingUpdateStore::load()
{
    UpdateResult<PendingUpdateRecord> result;
    bool readOk = false;
    const QByteArray document = readSmallNoFollowFile(recordPath(), &readOk);
    PendingUpdateRecord record;
    if (!readOk || !parseRecord(document, &record)
            || !isOwnedFinalPath(record.canonicalPath, record.candidate)) {
        QFile::remove(recordPath());
        result.error = UpdateError::InvalidMetadata;
        result.message = QStringLiteral("The pending update record is invalid");
        return result;
    }

    const UpdateResult<OpenVerifiedFile> verified =
        openAndVerify(record.canonicalPath, record.verifiedSize,
                      record.verifiedSha256);
    if (!verified.ok) {
        if (verified.error != UpdateError::IoFailure) {
            removeOwnedPayload(record);
            QFile::remove(recordPath());
        }
        result.error = verified.error;
        result.message = verified.message;
        if (verified.error == UpdateError::IoFailure) {
            result.value = record;
        }
        return result;
    }
    verified.value.file->close();
    result.ok = true;
    result.value = record;
    return result;
}

void PendingUpdateStore::clear(bool removeOwnedPayloadRequested)
{
    bool readOk = false;
    const QByteArray document = readSmallNoFollowFile(recordPath(), &readOk);
    PendingUpdateRecord record;
    if (removeOwnedPayloadRequested && readOk && parseRecord(document, &record)
            && isOwnedFinalPath(record.canonicalPath, record.candidate)) {
        removeOwnedPayload(record);
    }
    QFile::remove(recordPath());
}

void PendingUpdateStore::cleanStaleParts()
{
    const QString canonicalRoot = downloadsCanonicalPath();
    if (canonicalRoot.isEmpty()) {
        return;
    }
    const QRegularExpression ownedPart(
        QStringLiteral("^\\.vibertemis-update-[A-Za-z0-9]{6}\\.part$"));
    const QFileInfoList entries = QDir(canonicalRoot).entryInfoList(
        QDir::Files | QDir::Hidden | QDir::NoDotAndDotDot);
    const QDateTime now = m_Probe->nowUtc();
    for (const QFileInfo &entry : entries) {
        if (entry.isSymLink() || !entry.isFile()
                || !ownedPart.match(entry.fileName()).hasMatch()
                || entry.lastModified().toUTC().secsTo(now) < StalePartSeconds) {
            continue;
        }
        QFile::remove(entry.absoluteFilePath());
    }
}

UpdateResult<UpdateFileStore::OpenVerifiedFile> PendingUpdateStore::openAndVerify(
    const QString &path, quint64 expectedSize, const QByteArray &expectedSha256) const
{
    const QFileInfo pathInfo(path);
    if (!isSafeDirectChild(m_DownloadsRoot, path)
            || pathInfo.fileName().isEmpty()
            || !pathInfo.exists()
            || !pathInfo.isFile()
            || pathInfo.isSymLink()) {
        return failure(UpdateError::UnsafePath, QStringLiteral("Unsafe update path"));
    }

    QSharedPointer<QFile> file(new QFile);
#ifdef Q_OS_UNIX
    struct stat before;
    const QByteArray encoded = QFile::encodeName(path);
    if (::lstat(encoded.constData(), &before) != 0 || S_ISLNK(before.st_mode)
            || !S_ISREG(before.st_mode)) {
        return failure(UpdateError::UnsafePath,
                       QStringLiteral("Update path is not a regular file"));
    }
    const int descriptor = ::open(encoded.constData(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (descriptor < 0) {
        return failure(UpdateError::IoFailure,
                       QStringLiteral("Unable to open the update file"));
    }
    struct stat opened;
    if (::fstat(descriptor, &opened) != 0 || !S_ISREG(opened.st_mode)
            || before.st_dev != opened.st_dev || before.st_ino != opened.st_ino) {
        ::close(descriptor);
        return failure(UpdateError::UnsafePath,
                       QStringLiteral("Update file changed while opening"));
    }
    if (!file->open(descriptor, QIODevice::ReadOnly, QFileDevice::AutoCloseHandle)) {
        ::close(descriptor);
        return failure(UpdateError::IoFailure,
                       QStringLiteral("Unable to retain the update file"));
    }
#else
    file->setFileName(path);
    if (!file->open(QIODevice::ReadOnly)) {
        return failure(UpdateError::IoFailure,
                       QStringLiteral("Unable to open the update file"));
    }
#endif

    m_Probe->verificationFileOpened(path);
    QCryptographicHash hash(QCryptographicHash::Sha256);
    quint64 total = 0;
    char buffer[64 * 1024];
    while (true) {
        const qint64 count = file->read(buffer, sizeof(buffer));
        if (count < 0) {
            file->close();
            return failure(UpdateError::IoFailure,
                           QStringLiteral("Unable to read the update file"));
        }
        if (count == 0) {
            break;
        }
        total += static_cast<quint64>(count);
        if (total > expectedSize) {
            file->close();
            return failure(UpdateError::SizeMismatch,
                           QStringLiteral("The update file is larger than expected"));
        }
#if QT_VERSION >= QT_VERSION_CHECK(6, 4, 0)
        hash.addData(QByteArrayView(buffer, count));
#else
        hash.addData(buffer, count);
#endif
    }
    if (total != expectedSize) {
        file->close();
        return failure(UpdateError::SizeMismatch,
                       QStringLiteral("The update file size does not match"));
    }
    const QByteArray digest = hash.result().toHex();
    if (digest != expectedSha256) {
        file->close();
        return failure(UpdateError::DigestMismatch,
                       QStringLiteral("The update file checksum does not match"));
    }
    if (!file->seek(0)) {
        file->close();
        return failure(UpdateError::IoFailure,
                       QStringLiteral("Unable to rewind the update file"));
    }

    return verifiedResult(file, QFileInfo(path).absoluteFilePath(), total, digest);
}

QString PendingUpdateStore::downloadsCanonicalPath() const
{
    return QFileInfo(m_DownloadsRoot).canonicalFilePath();
}

QString PendingUpdateStore::finalPath(const RollingUpdateCandidate &candidate) const
{
    if (!isLowerHex(candidate.sourceCommit, 40)) {
        return QString();
    }
    const QString canonicalRoot = downloadsCanonicalPath();
    if (canonicalRoot.isEmpty()) {
        return QString();
    }
    return canonicalRoot + QDir::separator()
        + QStringLiteral("artemis-steam-deck-%1.flatpak")
              .arg(candidate.sourceCommit.left(12));
}

QString PendingUpdateStore::recordPath() const
{
    return QDir::cleanPath(m_PrivateDataRoot)
        + QDir::separator() + QStringLiteral("pending-update.json");
}

bool PendingUpdateStore::isOwnedFinalPath(
    const QString &path, const RollingUpdateCandidate &candidate) const
{
    const QString expected = finalPath(candidate);
    return !expected.isEmpty()
        && path == expected
        && isSafeDirectChild(m_DownloadsRoot, path);
}

bool PendingUpdateStore::removeOwnedPayload(
    const PendingUpdateRecord &record) const
{
    if (!isOwnedFinalPath(record.canonicalPath, record.candidate)) {
        return false;
    }
#ifdef Q_OS_UNIX
    const QByteArray encoded = QFile::encodeName(record.canonicalPath);
    struct stat pathInfo;
    if (::lstat(encoded.constData(), &pathInfo) != 0) {
        return false;
    }
    if (S_ISLNK(pathInfo.st_mode)) {
        return ::unlink(encoded.constData()) == 0;
    }
    if (!S_ISREG(pathInfo.st_mode)) {
        return false;
    }
#else
    const QFileInfo pathInfo(record.canonicalPath);
    if (pathInfo.isSymLink() || !pathInfo.isFile()) {
        return false;
    }
#endif
    return QFile::remove(record.canonicalPath);
}

bool PendingUpdateStore::isValidDigest(const QByteArray &digest)
{
    static const QRegularExpression Digest(QStringLiteral("^[0-9a-f]{64}$"));
    return Digest.match(QString::fromLatin1(digest)).hasMatch();
}

UpdateResult<UpdateFileStore::OpenVerifiedFile> PendingUpdateStore::failure(
    UpdateError error, const QString &message)
{
    UpdateResult<OpenVerifiedFile> result;
    result.error = error;
    result.message = message;
    return result;
}

void PendingUpdateStore::discardTemporary(
    const QSharedPointer<QTemporaryFile> &temporary)
{
    if (!temporary) {
        return;
    }
    const QString path = temporary->fileName();
    temporary->close();
    if (!path.isEmpty()) {
        QFile::remove(path);
    }
}
