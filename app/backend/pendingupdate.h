#pragma once

#include <QByteArray>
#include <QDateTime>
#include <QFile>
#include <QScopedPointer>
#include <QSharedPointer>
#include <QString>
#include <QTemporaryFile>

#include "rollingupdateparser.h"
#include "updateresult.h"

class StorageProbe
{
public:
    virtual ~StorageProbe() {}
    virtual quint64 bytesAvailable(const QString &directory) const = 0;
    virtual QDateTime nowUtc() const = 0;
    virtual void verificationFileOpened(const QString &) const {}
};

struct PendingUpdateRecord {
    int schema = 1;
    QString canonicalPath;
    RollingUpdateCandidate candidate;
    quint64 verifiedSize = 0;
    QByteArray verifiedSha256;
};

class UpdateFileStore
{
public:
    virtual ~UpdateFileStore() {}

    struct OpenVerifiedFile {
        QSharedPointer<QFile> file;
        QString canonicalPath;
        QByteArray sha256;
        quint64 size = 0;
    };

    virtual UpdateResult<QSharedPointer<QTemporaryFile>> createDownload(
        quint64 expectedSize) = 0;
    virtual UpdateResult<OpenVerifiedFile> finalizeAndVerify(
        QSharedPointer<QTemporaryFile> temporary,
        const RollingUpdateCandidate &candidate) = 0;
    virtual UpdateResult<OpenVerifiedFile> reopenAndVerify(
        const PendingUpdateRecord &record,
        const RollingUpdateCandidate &currentBinding) = 0;
    virtual bool save(const PendingUpdateRecord &record) = 0;
    virtual UpdateResult<PendingUpdateRecord> load() = 0;
    virtual void clear(bool removeOwnedPayload) = 0;
    virtual void cleanStaleParts() = 0;
};

class PendingUpdateStore final : public UpdateFileStore
{
public:
    explicit PendingUpdateStore(QString downloadsRoot = QString(),
                                QString privateDataRoot = QString(),
                                StorageProbe *probe = nullptr);
    ~PendingUpdateStore() override;

    static quint64 safetyMarginBytes();

    UpdateResult<QSharedPointer<QTemporaryFile>> createDownload(
        quint64 expectedSize) override;
    UpdateResult<OpenVerifiedFile> finalizeAndVerify(
        QSharedPointer<QTemporaryFile> temporary,
        const RollingUpdateCandidate &candidate) override;
    UpdateResult<OpenVerifiedFile> reopenAndVerify(
        const PendingUpdateRecord &record,
        const RollingUpdateCandidate &currentBinding) override;
    bool save(const PendingUpdateRecord &record) override;
    UpdateResult<PendingUpdateRecord> load() override;
    void clear(bool removeOwnedPayload) override;
    void cleanStaleParts() override;

private:
    UpdateResult<OpenVerifiedFile> openAndVerify(
        const QString &path, quint64 expectedSize, const QByteArray &expectedSha256) const;
    QString downloadsCanonicalPath() const;
    QString finalPath(const RollingUpdateCandidate &candidate) const;
    QString recordPath() const;
    bool isOwnedFinalPath(const QString &path,
                          const RollingUpdateCandidate &candidate) const;
    bool removeOwnedPayload(const PendingUpdateRecord &record) const;
    static bool isValidDigest(const QByteArray &digest);
    static UpdateResult<OpenVerifiedFile> failure(UpdateError error,
                                                  const QString &message);
    static void discardTemporary(const QSharedPointer<QTemporaryFile> &temporary);

    QString m_DownloadsRoot;
    QString m_PrivateDataRoot;
    StorageProbe *m_Probe;
    QScopedPointer<StorageProbe> m_OwnedProbe;
};
