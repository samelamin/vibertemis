#include <QtTest>

#include <cstring>

#include <QCryptographicHash>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QPointer>
#include <QProcessEnvironment>
#include <QQueue>
#include <QTemporaryDir>
#include <QTimer>

#include "backend/autoupdatechecker.h"
#include "backend/buildinfo.h"
#include "backend/pendingupdate.h"
#include "backend/releaseversionselector.h"
#include "backend/rollingupdateparser.h"
#include "backend/steamdecksession.h"
#include "backend/updatestatemachine.h"

struct NetworkScript {
    QByteArray body;
    int status = 200;
    QUrl redirect;
    QNetworkReply::NetworkError error = QNetworkReply::NoError;
    QByteArray errorText;
    QList<QPair<QByteArray, QByteArray>> headers;
    bool stall = false;
    int chunkSize = 0;
};

class ScriptedNetworkReply : public QNetworkReply
{
public:
    ScriptedNetworkReply(const QNetworkRequest &request,
                         QNetworkAccessManager::Operation operation,
        const NetworkScript &script,
                         QObject *parent) :
        QNetworkReply(parent),
        aborted(false),
        m_Body(script.body),
        m_Offset(0),
        m_Available(script.chunkSize > 0 ? 0 : script.body.size()),
        m_Stall(script.stall),
        m_ChunkSize(script.chunkSize)
    {
        setRequest(request);
        setUrl(request.url());
        setOperation(operation);
        setAttribute(QNetworkRequest::HttpStatusCodeAttribute, script.status);
        if (script.redirect.isValid()) {
            setAttribute(QNetworkRequest::RedirectionTargetAttribute,
                         script.redirect);
        }
        for (const QPair<QByteArray, QByteArray> &header : script.headers) {
            setRawHeader(header.first, header.second);
        }
        setError(script.error,
                 script.errorText.isEmpty()
                    ? QStringLiteral("scripted network error")
                    : QString::fromUtf8(script.errorText));
        open(QIODevice::ReadOnly | QIODevice::Unbuffered);
        if (!m_Stall) {
            QTimer::singleShot(0, this, [this]() { releaseNextChunk(); });
        }
    }

    void abort() override
    {
        aborted = true;
        if (!isFinished()) {
            setError(QNetworkReply::OperationCanceledError,
                     QStringLiteral("cancelled"));
            setFinished(true);
            emit finished();
        }
    }

    qint64 bytesAvailable() const override
    {
        return m_Available - m_Offset + QIODevice::bytesAvailable();
    }

    bool aborted;

protected:
    qint64 readData(char *data, qint64 maxSize) override
    {
        if (m_Offset >= m_Available) {
            return m_Available >= m_Body.size() ? -1 : 0;
        }
        const qint64 count = qMin(maxSize, m_Available - m_Offset);
        memcpy(data, m_Body.constData() + m_Offset,
               static_cast<size_t>(count));
        m_Offset += count;
        return count;
    }

private:
    void releaseNextChunk()
    {
        if (isFinished()) {
            return;
        }
        if (m_ChunkSize > 0) {
            m_Available = qMin<qint64>(
                m_Body.size(), m_Available + m_ChunkSize);
        }
        emit readyRead();
        if (m_Available < m_Body.size()) {
            QTimer::singleShot(0, this, [this]() { releaseNextChunk(); });
            return;
        }
        setFinished(true);
        emit finished();
    }

    QByteArray m_Body;
    qint64 m_Offset;
    qint64 m_Available;
    bool m_Stall;
    int m_ChunkSize;
};

class FakeNetworkAccessManager : public QNetworkAccessManager
{
public:
    explicit FakeNetworkAccessManager(QObject *parent = nullptr) :
        QNetworkAccessManager(parent)
    {
    }

    void enqueue(const NetworkScript &script)
    {
        scripts.enqueue(script);
    }

    QList<QNetworkRequest> requests;
    QList<QPointer<ScriptedNetworkReply>> replies;
    QQueue<NetworkScript> scripts;

protected:
    QNetworkReply *createRequest(Operation operation,
                                 const QNetworkRequest &request,
                                 QIODevice *outgoingData = nullptr) override
    {
        Q_UNUSED(outgoingData)
        requests.append(request);
        NetworkScript script;
        if (!scripts.isEmpty()) {
            script = scripts.dequeue();
        } else {
            script.status = 500;
            script.error = QNetworkReply::UnknownNetworkError;
            script.errorText = QByteArrayLiteral("unexpected request");
        }
        ScriptedNetworkReply *reply =
            new ScriptedNetworkReply(request, operation, script, this);
        replies.append(reply);
        return reply;
    }
};

class FakeSessionModeProvider : public SessionModeProvider
{
public:
    SteamDeckSession::Mode value = SteamDeckSession::Desktop;
    SteamDeckSession::Mode mode() const override { return value; }
};

class FakeUpdateCheckPolicy : public UpdateCheckPolicy
{
public:
    bool rolling = true;
    bool stable = false;

    bool rollingInstallSupported() const override { return rolling; }
    bool stableCheckSupported() const override { return stable; }
};

class FakeUpdateFileStore : public UpdateFileStore
{
public:
    FakeUpdateFileStore()
    {
        root.setAutoRemove(true);
    }

    UpdateResult<QSharedPointer<QTemporaryFile>> createDownload(
        quint64 expectedSize) override
    {
        Q_UNUSED(expectedSize)
        UpdateResult<QSharedPointer<QTemporaryFile>> result;
        if (createError != UpdateError::None) {
            result.error = createError;
            result.message = QStringLiteral("create failed");
            return result;
        }
        QSharedPointer<QTemporaryFile> file(new QTemporaryFile(
            root.path() + QStringLiteral("/download-XXXXXX.part")));
        result.ok = file->open();
        result.value = file;
        lastTemporaryPath = file->fileName();
        return result;
    }

    UpdateResult<OpenVerifiedFile> finalizeAndVerify(
        QSharedPointer<QTemporaryFile> temporary,
        const RollingUpdateCandidate &candidate) override
    {
        ++finalizeCalls;
        UpdateResult<OpenVerifiedFile> result;
        if (finalizeError != UpdateError::None) {
            result.error = finalizeError;
            result.message = QStringLiteral("verification failed");
            return result;
        }
        if (!temporary || !temporary->flush()
                || static_cast<quint64>(temporary->size())
                    != candidate.flatpak.size) {
            result.error = UpdateError::SizeMismatch;
            result.message = QStringLiteral("wrong size");
            return result;
        }
        temporary->setAutoRemove(false);
        const QString finalPath =
            root.path() + QStringLiteral("/artemis-steam-deck-")
            + candidate.sourceCommit.left(12) + QStringLiteral(".flatpak");
        temporary->close();
        QFile::remove(finalPath);
        if (!QFile::rename(temporary->fileName(), finalPath)) {
            result.error = UpdateError::IoFailure;
            return result;
        }
        QSharedPointer<QFile> file(new QFile(finalPath));
        if (!file->open(QIODevice::ReadOnly)) {
            result.error = UpdateError::IoFailure;
            return result;
        }
        result.ok = true;
        result.value.file = file;
        result.value.canonicalPath = finalPath;
        result.value.size = candidate.flatpak.size;
        result.value.sha256 = candidate.flatpak.sha256;
        return result;
    }

    UpdateResult<OpenVerifiedFile> reopenAndVerify(
        const PendingUpdateRecord &record,
        const RollingUpdateCandidate &binding) override
    {
        ++reopenCalls;
        UpdateResult<OpenVerifiedFile> result;
        if (reopenError != UpdateError::None
                || !RollingUpdateParser::matchesCandidate(
                    record.candidate, binding).ok) {
            result.error = reopenError == UpdateError::None
                ? UpdateError::PublisherChanged : reopenError;
            result.message = QStringLiteral("reopen failed");
            return result;
        }
        QSharedPointer<QFile> file(new QFile(record.canonicalPath));
        if (!file->open(QIODevice::ReadOnly)) {
            result.error = UpdateError::IoFailure;
            result.message = QStringLiteral("missing file");
            return result;
        }
        result.ok = true;
        result.value.file = file;
        result.value.canonicalPath = record.canonicalPath;
        result.value.size = record.verifiedSize;
        result.value.sha256 = record.verifiedSha256;
        return result;
    }

    bool save(const PendingUpdateRecord &record) override
    {
        ++saveCalls;
        saved = record;
        return saveSucceeds;
    }

    UpdateResult<PendingUpdateRecord> load() override
    {
        ++loadCalls;
        return loadResult;
    }

    void clear(bool removeOwnedPayload) override
    {
        ++clearCalls;
        lastClearRemovedPayload = removeOwnedPayload;
    }

    void cleanStaleParts() override { ++cleanupCalls; }

    QTemporaryDir root;
    UpdateError createError = UpdateError::None;
    UpdateError finalizeError = UpdateError::None;
    UpdateError reopenError = UpdateError::None;
    bool saveSucceeds = true;
    UpdateResult<PendingUpdateRecord> loadResult;
    PendingUpdateRecord saved;
    QString lastTemporaryPath;
    int finalizeCalls = 0;
    int reopenCalls = 0;
    int saveCalls = 0;
    int loadCalls = 0;
    int clearCalls = 0;
    int cleanupCalls = 0;
    bool lastClearRemovedPayload = false;
};

class AutoUpdateTest : public QObject
{
    Q_OBJECT

private slots:
    void rollingReleaseDoesNotMaskVersionedRelease();
    void normalFirstReleaseIsSelected();
    void emptyReleaseArrayHasNoSelection();
    void invalidTagsHaveNoSelection();
    void releaseWithoutUrlHasNoSelection();
    void developmentPrereleaseIsSelected();
    void optionalLeadingVIsNormalized_data();
    void optionalLeadingVIsNormalized();
    void updateDecisionUsesSemverNumericCore_data();
    void updateDecisionUsesSemverNumericCore();
    void buildIdentity();
    void buildIdentityValidation_data();
    void buildIdentityValidation();
    void buildIdentityIsMetaType();
    void buildInfoPreflight_data();
    void buildInfoPreflight();
    void buildInfoPreflightConsoleAttachment_data();
    void buildInfoPreflightConsoleAttachment();
    void rollingParserAcceptsExactReleaseAndManifest();
    void rollingParserAcceptsGitHubNumericReleaseFields();
    void rollingParserRejectsInvalidReleaseAndManifest_data();
    void rollingParserRejectsInvalidReleaseAndManifest();
    void rollingParserDetectsCapturedIdentityChanges();
    void rollingParserResolvesAnnotatedTags();
    void rollingParserRejectsInvalidTagGraphs();
    void rollingParserClassifiesCommitRelations();
    void rollingParserRequiresNewerSequence();
    void steamDeckSessionClassifiesEnvironment_data();
    void steamDeckSessionClassifiesEnvironment();
    void secureUpdateFiles();
    void pendingUpdateRecord();
    void pendingUpdateRejectsHostileCandidate_data();
    void pendingUpdateRejectsHostileCandidate();
    void pendingUpdateRejectsAlternatePath();
    void pendingUpdatePreservesFractionalTimestamps();
    void pendingUpdateRejectsOversizedRecord();
    void pendingUpdatePreservesTransientIoFailure();
    void rollingParser();
    void steamDeckSession();
    void stateMachine();
    void boundedNetwork();
};

static const QString RollingCommit(40, QLatin1Char('b'));
static const QString TagObject(40, QLatin1Char('d'));
static const QString TagCommit(40, QLatin1Char('e'));
static const QString FlatpakDigest(64, QLatin1Char('c'));

class FakeStorageProbe : public StorageProbe
{
public:
    quint64 available = 0;
    QDateTime now = QDateTime::fromString(
        QStringLiteral("2026-07-23T12:00:00Z"), Qt::ISODate);
#ifdef Q_OS_UNIX
    mutable bool swapPathAfterOpen = false;
    mutable bool swapSucceeded = false;
    mutable QString openedInodePath;
#endif

    quint64 bytesAvailable(const QString &) const override
    {
        return available;
    }

    QDateTime nowUtc() const override
    {
        return now;
    }

#ifdef Q_OS_UNIX
    void verificationFileOpened(const QString &path) const override
    {
        if (!swapPathAfterOpen) {
            return;
        }
        swapPathAfterOpen = false;
        openedInodePath = path + QStringLiteral(".opened-inode");
        if (!QFile::rename(path, openedInodePath)) {
            return;
        }
        QFile replacement(path);
        if (!replacement.open(QIODevice::WriteOnly)
                || replacement.write("replacement") != 11) {
            return;
        }
        replacement.close();
        swapSucceeded = true;
    }
#endif
};

class ScriptedPendingRecordReader : public PendingRecordReader
{
public:
    UpdateResult<QByteArray> read(const QString &path,
                                  qint64 maximumSize) const override
    {
        ++calls;
        paths.append(path);
        limits.append(maximumSize);
        if (results.isEmpty()) {
            UpdateResult<QByteArray> unexpected;
            unexpected.error = UpdateError::IoFailure;
            unexpected.message = QStringLiteral("unexpected record read");
            return unexpected;
        }
        return results.takeFirst();
    }

    mutable QList<UpdateResult<QByteArray>> results;
    mutable QStringList paths;
    mutable QList<qint64> limits;
    mutable int calls = 0;
};

static RollingUpdateCandidate storageCandidate(const QByteArray &payload)
{
    RollingUpdateCandidate candidate;
    candidate.releaseId = 24680;
    candidate.releaseLabel = QStringLiteral("steam-deck-latest");
    candidate.releasePage =
        QUrl(QStringLiteral("https://github.com/samelamin/vibertemis/releases/tag/steam-deck-latest"));
    candidate.releaseUpdatedAt = QDateTime::fromString(
        QStringLiteral("2026-07-23T10:00:00Z"), Qt::ISODate);
    candidate.sourceCommit = QString(40, QLatin1Char('b'));
    candidate.sequence = 5678;
    candidate.tagRefObjectId = QString(40, QLatin1Char('d'));
    candidate.tagObjectId = QString(40, QLatin1Char('e'));
    candidate.manifestSchema = 1;
    candidate.publishedAt = QDateTime::fromString(
        QStringLiteral("2026-07-23T10:00:00Z"), Qt::ISODate);
    candidate.manifest.id = 11223;
    candidate.manifest.name = QStringLiteral("artemis-steam-deck-update.json");
    candidate.manifest.size = 512;
    candidate.manifest.apiUrl = QUrl(
        QStringLiteral("https://api.github.com/repos/samelamin/vibertemis/releases/assets/11223"));
    candidate.manifest.downloadUrl = QUrl(
        QStringLiteral("https://github.com/samelamin/vibertemis/releases/download/"
                       "steam-deck-latest/artemis-steam-deck-update.json"));
    candidate.manifest.updatedAt = candidate.releaseUpdatedAt;
    candidate.manifest.sha256 = QByteArray(64, 'a');
    candidate.flatpak.id = 13579;
    candidate.flatpak.name = QStringLiteral("artemis-steam-deck.flatpak");
    candidate.flatpak.size = static_cast<quint64>(payload.size());
    candidate.flatpak.apiUrl = QUrl(
        QStringLiteral("https://api.github.com/repos/samelamin/vibertemis/releases/assets/13579"));
    candidate.flatpak.downloadUrl = QUrl(
        QStringLiteral("https://github-releases.githubusercontent.com/asset/flatpak"));
    candidate.flatpak.updatedAt = candidate.releaseUpdatedAt;
    candidate.flatpak.sha256 =
        QCryptographicHash::hash(payload, QCryptographicHash::Sha256).toHex();
    return candidate;
}

static QByteArray validReleaseJson()
{
    return R"json({
      "id":"24680",
      "tag_name":"steam-deck-latest",
      "html_url":"https://github.com/samelamin/vibertemis/releases/tag/steam-deck-latest",
      "updated_at":"2026-07-23T10:00:00Z",
      "assets":[
        {"id":"11223","name":"artemis-steam-deck-update.json","size":"512","url":"https://api.github.com/repos/samelamin/vibertemis/releases/assets/11223","browser_download_url":"https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck-update.json","updated_at":"2026-07-23T10:00:00Z"},
        {"id":"13579","name":"artemis-steam-deck.flatpak","size":"1048576","url":"https://api.github.com/repos/samelamin/vibertemis/releases/assets/13579","browser_download_url":"https://github-releases.githubusercontent.com/asset/flatpak","updated_at":"2026-07-23T10:00:00Z"}
      ]
    })json";
}

static QByteArray validManifestJson()
{
    return QStringLiteral(R"json({
      "schema":1,
      "repository":"samelamin/vibertemis",
      "application_id":"com.artemisdesktop.ArtemisDesktopDev",
      "source_commit":"%1",
      "build_sequence":"5678",
      "release_id":"24680",
      "tag":"steam-deck-latest",
      "tag_commit":"%1",
      "flatpak":{"asset_id":"13579","name":"artemis-steam-deck.flatpak","size":"1048576","sha256":"%2"},
      "published_at":"2026-07-23T10:00:00Z"
    })json").arg(RollingCommit, FlatpakDigest).toUtf8();
}

static QByteArray validGitHubReleaseJson();
static QByteArray replaceOnce(QByteArray value, const QByteArray &needle,
                              const QByteArray &replacement);

static QByteArray rollingPayload()
{
    return QByteArray(1024 * 1024, 'p');
}

static QByteArray manifestForPayload(const QByteArray &payload)
{
    const QByteArray digest =
        QCryptographicHash::hash(payload, QCryptographicHash::Sha256).toHex();
    return replaceOnce(validManifestJson(), FlatpakDigest.toLatin1(), digest);
}

static RollingUpdateCandidate boundCandidateForPayload(const QByteArray &payload)
{
    const UpdateResult<RollingUpdateCandidate> release =
        RollingUpdateParser::parseRelease(validGitHubReleaseJson());
    Q_ASSERT(release.ok);
    const UpdateResult<RollingUpdateCandidate> parsed =
        RollingUpdateParser::parseManifest(manifestForPayload(payload),
                                           release.value);
    Q_ASSERT(parsed.ok);
    const UpdateResult<RollingUpdateCandidate> bound =
        RollingUpdateParser::bindTagResolution(
            parsed.value, TagResolution{RollingCommit, QString(), RollingCommit});
    Q_ASSERT(bound.ok);
    return bound.value;
}

static NetworkScript networkScript(const QByteArray &body, int status = 200)
{
    NetworkScript result;
    result.body = body;
    result.status = status;
    return result;
}

static QByteArray lightweightTagJson()
{
    return QStringLiteral("{\"object\":{\"type\":\"commit\",\"sha\":\"%1\"}}")
        .arg(RollingCommit).toUtf8();
}

static void enqueueAvailableCheck(FakeNetworkAccessManager *network,
                                  const QByteArray &manifest)
{
    network->enqueue(networkScript(validGitHubReleaseJson()));
    network->enqueue(networkScript(manifest));
    network->enqueue(networkScript(lightweightTagJson()));
    network->enqueue(networkScript(QByteArrayLiteral("{\"status\":\"ahead\"}")));
}

static void enqueueUnchangedRefetch(FakeNetworkAccessManager *network,
                                    const QByteArray &manifest)
{
    network->enqueue(networkScript(validGitHubReleaseJson()));
    network->enqueue(networkScript(manifest));
    network->enqueue(networkScript(lightweightTagJson()));
}

static QByteArray validGitHubReleaseJson()
{
    QByteArray json = validReleaseJson();
    json.replace("\"id\":\"24680\"", "\"id\":24680");
    json.replace("\"id\":\"11223\"", "\"id\":11223");
    json.replace("\"size\":\"512\"", "\"size\":512");
    json.replace("\"id\":\"13579\"", "\"id\":13579");
    json.replace("\"size\":\"1048576\"", "\"size\":1048576");
    return json;
}

static QByteArray replaceOnce(QByteArray value, const QByteArray &needle, const QByteArray &replacement)
{
    const int index = value.indexOf(needle);
    Q_ASSERT(index >= 0);
    value.replace(index, needle.size(), replacement);
    return value;
}

static QJsonArray releases(const QByteArray &json)
{
    return QJsonDocument::fromJson(json).array();
}

void AutoUpdateTest::rollingReleaseDoesNotMaskVersionedRelease()
{
    const ReleaseVersionSelection selection = ReleaseVersionSelector::select(releases(R"json(
        [
          {"tag_name":"steam-deck-latest","html_url":"https://github.com/samelamin/vibertemis/releases/tag/steam-deck-latest"},
          {"tag_name":"v6.1.1","html_url":"https://github.com/samelamin/vibertemis/releases/tag/v6.1.1"}
        ]
    )json"));

    QVERIFY(selection.valid);
    QCOMPARE(selection.version, QStringLiteral("6.1.1"));
    QCOMPARE(selection.url,
             QStringLiteral("https://github.com/samelamin/vibertemis/releases/tag/v6.1.1"));
}

void AutoUpdateTest::normalFirstReleaseIsSelected()
{
    const ReleaseVersionSelection selection = ReleaseVersionSelector::select(releases(R"json(
        [
          {"tag_name":"6.2.0","html_url":"https://github.com/samelamin/vibertemis/releases/tag/6.2.0"},
          {"tag_name":"v6.1.1","html_url":"https://github.com/samelamin/vibertemis/releases/tag/v6.1.1"}
        ]
    )json"));

    QVERIFY(selection.valid);
    QCOMPARE(selection.version, QStringLiteral("6.2.0"));
}

void AutoUpdateTest::emptyReleaseArrayHasNoSelection()
{
    const ReleaseVersionSelection selection = ReleaseVersionSelector::select(QJsonArray());

    QVERIFY(!selection.valid);
    QVERIFY(selection.version.isEmpty());
    QVERIFY(selection.url.isEmpty());
}

void AutoUpdateTest::invalidTagsHaveNoSelection()
{
    const ReleaseVersionSelection selection = ReleaseVersionSelector::select(releases(R"json(
        [
          {"tag_name":"steam-deck-latest","html_url":"https://example.invalid/rolling"},
          {"tag_name":"latest","html_url":"https://example.invalid/latest"},
          {"tag_name":"v6","html_url":"https://example.invalid/major-only"},
          {"tag_name":"6.beta.1","html_url":"https://example.invalid/nonnumeric-minor"}
        ]
    )json"));

    QVERIFY(!selection.valid);
}

void AutoUpdateTest::releaseWithoutUrlHasNoSelection()
{
    const ReleaseVersionSelection selection = ReleaseVersionSelector::select(releases(R"json(
        [{"tag_name":"v6.1.1"}]
    )json"));

    QVERIFY(!selection.valid);
}

void AutoUpdateTest::developmentPrereleaseIsSelected()
{
    const ReleaseVersionSelection selection = ReleaseVersionSelector::select(releases(R"json(
        [{"tag_name":"v6.2.0-dev.3","html_url":"https://github.com/samelamin/vibertemis/releases/tag/v6.2.0-dev.3"}]
    )json"));

    QVERIFY(selection.valid);
    QCOMPARE(selection.version, QStringLiteral("6.2.0-dev.3"));
}

void AutoUpdateTest::optionalLeadingVIsNormalized_data()
{
    QTest::addColumn<QString>("tag");
    QTest::addColumn<QString>("expectedVersion");

    QTest::newRow("leading v") << QStringLiteral("v7.4") << QStringLiteral("7.4");
    QTest::newRow("no leading v") << QStringLiteral("7.5") << QStringLiteral("7.5");
}

void AutoUpdateTest::optionalLeadingVIsNormalized()
{
    QFETCH(QString, tag);
    QFETCH(QString, expectedVersion);

    QJsonObject release;
    release.insert(QStringLiteral("tag_name"), tag);
    release.insert(QStringLiteral("html_url"), QStringLiteral("https://example.invalid/release"));

    const ReleaseVersionSelection selection = ReleaseVersionSelector::select(QJsonArray{release});
    QVERIFY(selection.valid);
    QCOMPARE(selection.version, expectedVersion);
}

void AutoUpdateTest::updateDecisionUsesSemverNumericCore_data()
{
    QTest::addColumn<QString>("releaseVersion");
    QTest::addColumn<bool>("updateExpected");

    QTest::newRow("newer stable") << QStringLiteral("0.6.8") << true;
    QTest::newRow("older stable") << QStringLiteral("0.6.6") << false;
    QTest::newRow("equal stable") << QStringLiteral("0.6.7") << false;
    QTest::newRow("newer prerelease")
        << QStringLiteral("0.6.8-dev.20260722.1") << true;
    QTest::newRow("older prerelease")
        << QStringLiteral("0.6.6-dev.20260722.1") << false;
    QTest::newRow("equal-core prerelease")
        << QStringLiteral("0.6.7-dev.20260722.1") << false;
}

void AutoUpdateTest::updateDecisionUsesSemverNumericCore()
{
    QFETCH(QString, releaseVersion);
    QFETCH(bool, updateExpected);

    FakeNetworkAccessManager network;
    FakeUpdateFileStore files;
    FakeSessionModeProvider session;
    FakeUpdateCheckPolicy policy;
    policy.rolling = false;
    policy.stable = true;
    AutoUpdateChecker checker(&network, &files, &session, &policy);
    QSignalSpy updateSpy(&checker, &AutoUpdateChecker::onUpdateAvailable);

    QJsonObject release;
    release.insert(QStringLiteral("tag_name"), releaseVersion);
    release.insert(QStringLiteral("html_url"), QStringLiteral("https://example.invalid/release"));
    const QByteArray responseBody = QJsonDocument(QJsonArray{release}).toJson();
    network.enqueue(networkScript(responseBody));
    checker.start();

    QTRY_COMPARE(checker.state(), updateExpected
                 ? AutoUpdateChecker::Available : AutoUpdateChecker::NoUpdate);
    QCOMPARE(network.requests.size(), 1);
    QCOMPARE(updateSpy.count(), updateExpected ? 1 : 0);
    if (updateExpected) {
        QCOMPARE(updateSpy.at(0).at(0).toString(), releaseVersion);
        QCOMPARE(updateSpy.at(0).at(1).toString(), QStringLiteral("https://example.invalid/release"));
    }
}

void AutoUpdateTest::buildIdentity()
{
    QCOMPARE(BuildInfo::commit(), QString(40, QLatin1Char('a')));
    QCOMPARE(BuildInfo::channel(), BuildInfo::RollingChannel);
    QCOMPARE(BuildInfo::sequence(), quint64(1234));
    QVERIFY(BuildInfo::isInternallyConsistent());

    const QJsonObject json = BuildInfo::toJson();
    QCOMPARE(json.value(QStringLiteral("schema")).toInt(), 1);
    QCOMPARE(json.value(QStringLiteral("applicationId")).toString(),
             QStringLiteral("com.artemisdesktop.ArtemisDesktopDev"));
    QCOMPARE(json.value(QStringLiteral("commit")).toString(), QString(40, QLatin1Char('a')));
    QCOMPARE(json.value(QStringLiteral("channel")).toString(), QStringLiteral("rolling"));
    QCOMPARE(json.value(QStringLiteral("sequence")).toString(), QStringLiteral("1234"));
}

void AutoUpdateTest::buildIdentityValidation_data()
{
    QTest::addColumn<BuildInfo::Identity>("identity");
    QTest::addColumn<bool>("valid");

    const QString commit(40, QLatin1Char('a'));
    const QString applicationId = QStringLiteral("com.artemisdesktop.ArtemisDesktopDev");
    QTest::newRow("none unknown zero")
        << BuildInfo::Identity{QStringLiteral("unknown"), BuildInfo::NoChannel, 0, applicationId, QStringLiteral("0.6.7")}
        << true;
    QTest::newRow("none clean commit zero")
        << BuildInfo::Identity{commit, BuildInfo::NoChannel, 0, applicationId, QStringLiteral("0.6.7")}
        << true;
    QTest::newRow("malformed short commit")
        << BuildInfo::Identity{QStringLiteral("abcdef"), BuildInfo::NoChannel, 0, applicationId, QStringLiteral("0.6.7")}
        << false;
    QTest::newRow("malformed uppercase commit")
        << BuildInfo::Identity{QString(40, QLatin1Char('A')), BuildInfo::StableChannel, 0, applicationId, QStringLiteral("0.6.7")}
        << false;
    QTest::newRow("rolling zero sequence")
        << BuildInfo::Identity{commit, BuildInfo::RollingChannel, 0, applicationId, QStringLiteral("0.6.7")}
        << false;
    QTest::newRow("stable nonzero sequence")
        << BuildInfo::Identity{commit, BuildInfo::StableChannel, 1, applicationId, QStringLiteral("0.6.7")}
        << false;
    QTest::newRow("none nonzero sequence")
        << BuildInfo::Identity{QStringLiteral("unknown"), BuildInfo::NoChannel, 1, applicationId, QStringLiteral("0.6.7")}
        << false;
    QTest::newRow("mismatched application id")
        << BuildInfo::Identity{commit, BuildInfo::RollingChannel, 1234, QStringLiteral("com.example.Artemis"), QStringLiteral("0.6.7")}
        << false;
}

void AutoUpdateTest::buildIdentityValidation()
{
    QFETCH(BuildInfo::Identity, identity);
    QFETCH(bool, valid);

    QCOMPARE(BuildInfo::validate(identity), valid);
}

void AutoUpdateTest::buildIdentityIsMetaType()
{
    QVERIFY(qMetaTypeId<BuildInfo::Identity>() > 0);
}

void AutoUpdateTest::buildInfoPreflight_data()
{
    QTest::addColumn<QStringList>("arguments");
    QTest::addColumn<bool>("handled");

    QTest::newRow("exact request")
        << QStringList{QStringLiteral("Artemis"), QStringLiteral("--build-info")}
        << true;
    QTest::newRow("no request")
        << QStringList{QStringLiteral("Artemis")}
        << false;
    QTest::newRow("extra argument")
        << QStringList{QStringLiteral("Artemis"), QStringLiteral("--build-info"), QStringLiteral("extra")}
        << false;
    QTest::newRow("option before request")
        << QStringList{QStringLiteral("Artemis"), QStringLiteral("--version"), QStringLiteral("--build-info")}
        << false;
}

void AutoUpdateTest::buildInfoPreflight()
{
    QFETCH(QStringList, arguments);
    QFETCH(bool, handled);

    const BuildInfo::Preflight result = BuildInfo::preflight(arguments);
    QCOMPARE(result.handled, handled);

    if (!handled) {
        QVERIFY(result.output.isEmpty());
        return;
    }

    QCOMPARE(result.output.count('\n'), 1);
    QVERIFY(result.output.endsWith('\n'));

    QJsonParseError error;
    const QJsonDocument json = QJsonDocument::fromJson(result.output, &error);
    QCOMPARE(error.error, QJsonParseError::NoError);
    QCOMPARE(json.toJson(QJsonDocument::Compact) + '\n', result.output);
}

void AutoUpdateTest::buildInfoPreflightConsoleAttachment_data()
{
    QTest::addColumn<bool>("stdoutUnspecified");
    QTest::addColumn<bool>("stderrUnspecified");
    QTest::addColumn<bool>("expected");

    QTest::newRow("no inherited handles") << true << true << true;
    QTest::newRow("stdout redirected") << false << true << true;
    QTest::newRow("stderr redirected") << true << false << true;
    QTest::newRow("both redirected") << false << false << false;
}

void AutoUpdateTest::buildInfoPreflightConsoleAttachment()
{
    QFETCH(bool, stdoutUnspecified);
    QFETCH(bool, stderrUnspecified);
    QFETCH(bool, expected);

    QCOMPARE(BuildInfo::requiresParentConsoleAttachment(stdoutUnspecified, stderrUnspecified), expected);
}

void AutoUpdateTest::rollingParserAcceptsExactReleaseAndManifest()
{
    const UpdateResult<RollingUpdateCandidate> release =
        RollingUpdateParser::parseRelease(validReleaseJson());
    QVERIFY2(release.ok, qPrintable(release.message));
    QCOMPARE(release.value.releaseId, quint64(24680));
    QCOMPARE(release.value.releaseLabel, QStringLiteral("steam-deck-latest"));
    QCOMPARE(release.value.releasePage,
             QUrl(QStringLiteral("https://github.com/samelamin/vibertemis/releases/tag/steam-deck-latest")));
    QCOMPARE(release.value.releaseUpdatedAt.toUTC(),
             QDateTime::fromString(QStringLiteral("2026-07-23T10:00:00Z"), Qt::ISODate).toUTC());
    QCOMPARE(release.value.manifest.id, quint64(11223));
    QCOMPARE(release.value.manifest.name, QStringLiteral("artemis-steam-deck-update.json"));
    QCOMPARE(release.value.manifest.size, quint64(512));
    QCOMPARE(release.value.manifest.apiUrl,
             QUrl(QStringLiteral("https://api.github.com/repos/samelamin/vibertemis/releases/assets/11223")));
    QCOMPARE(release.value.manifest.downloadUrl,
             QUrl(QStringLiteral("https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck-update.json")));
    QCOMPARE(release.value.manifest.sha256, QByteArray());

    const UpdateResult<RollingUpdateCandidate> candidate =
        RollingUpdateParser::parseManifest(validManifestJson(), release.value);
    QVERIFY2(candidate.ok, qPrintable(candidate.message));
    QCOMPARE(candidate.value.manifestSchema, 1);
    QCOMPARE(candidate.value.sourceCommit, RollingCommit);
    QCOMPARE(candidate.value.sequence, quint64(5678));
    QCOMPARE(candidate.value.flatpak.id, quint64(13579));
    QCOMPARE(candidate.value.flatpak.name, QStringLiteral("artemis-steam-deck.flatpak"));
    QCOMPARE(candidate.value.flatpak.size, quint64(1048576));
    QCOMPARE(candidate.value.flatpak.sha256, FlatpakDigest.toLatin1());
    QCOMPARE(candidate.value.flatpak.apiUrl,
             QUrl(QStringLiteral("https://api.github.com/repos/samelamin/vibertemis/releases/assets/13579")));
    QCOMPARE(candidate.value.flatpak.downloadUrl,
             QUrl(QStringLiteral("https://github-releases.githubusercontent.com/asset/flatpak")));
    QCOMPARE(candidate.value.publishedAt.toUTC(),
             QDateTime::fromString(QStringLiteral("2026-07-23T10:00:00Z"), Qt::ISODate).toUTC());
}

void AutoUpdateTest::rollingParserAcceptsGitHubNumericReleaseFields()
{
    const UpdateResult<RollingUpdateCandidate> release =
        RollingUpdateParser::parseRelease(validGitHubReleaseJson());
    QVERIFY2(release.ok, qPrintable(release.message));
    QCOMPARE(release.value.releaseId, quint64(24680));
    QCOMPARE(release.value.manifest.id, quint64(11223));
    QCOMPARE(release.value.manifest.size, quint64(512));
    QCOMPARE(release.value.flatpak.id, quint64(13579));
    QCOMPARE(release.value.flatpak.size, quint64(1048576));
}

void AutoUpdateTest::rollingParserRejectsInvalidReleaseAndManifest_data()
{
    QTest::addColumn<QByteArray>("release");
    QTest::addColumn<QByteArray>("manifest");
    QTest::addColumn<bool>("invalidRelease");

    QTest::newRow("wrong release tag")
        << replaceOnce(validReleaseJson(), "steam-deck-latest", "Steam-Deck-Latest") << validManifestJson() << true;
    QTest::newRow("duplicate Flatpak asset")
        << replaceOnce(validReleaseJson(), "\n      ]\n    }", ",{\"id\":\"13580\",\"name\":\"artemis-steam-deck.flatpak\",\"size\":\"1\",\"url\":\"https://api.github.com/repos/samelamin/vibertemis/releases/assets/13580\",\"browser_download_url\":\"https://github-releases.githubusercontent.com/asset/duplicate\",\"updated_at\":\"2026-07-23T10:00:00Z\"}\n      ]\n    }") << validManifestJson() << true;
    QTest::newRow("missing manifest asset")
        << replaceOnce(validReleaseJson(), "artemis-steam-deck-update.json", "other.json") << validManifestJson() << true;
    QTest::newRow("foreign API repository")
        << replaceOnce(validReleaseJson(), "samelamin/vibertemis/releases/assets/11223", "evil/vibertemis/releases/assets/11223") << validManifestJson() << true;
    QTest::newRow("API endpoint is not an asset")
        << replaceOnce(validReleaseJson(), "releases/assets/11223", "issues/1") << validManifestJson() << true;
    QTest::newRow("API asset ID does not bind")
        << replaceOnce(validReleaseJson(), "assets/11223", "assets/11224") << validManifestJson() << true;
    QTest::newRow("API and download URLs cannot swap")
        << replaceOnce(validReleaseJson(), "https://api.github.com/repos/samelamin/vibertemis/releases/assets/11223", "https://github.com/samelamin/vibertemis/releases/download/steam-deck-latest/artemis-steam-deck-update.json") << validManifestJson() << true;
    QTest::newRow("non HTTPS")
        << replaceOnce(validReleaseJson(), "https://github.com/samelamin/vibertemis/releases/tag", "http://github.com/samelamin/vibertemis/releases/tag") << validManifestJson() << true;
    QTest::newRow("URL user info")
        << replaceOnce(validReleaseJson(), "https://github-releases.githubusercontent.com/asset/flatpak", "https://user@github-releases.githubusercontent.com/asset/flatpak") << validManifestJson() << true;
    QTest::newRow("URL query")
        << replaceOnce(validReleaseJson(), "https://github-releases.githubusercontent.com/asset/flatpak", "https://github-releases.githubusercontent.com/asset/flatpak?token=x") << validManifestJson() << true;
    QTest::newRow("URL fragment")
        << replaceOnce(validReleaseJson(), "https://github-releases.githubusercontent.com/asset/flatpak", "https://github-releases.githubusercontent.com/asset/flatpak#fragment") << validManifestJson() << true;
    QTest::newRow("bad CDN boundary")
        << replaceOnce(validReleaseJson(), "github-releases.githubusercontent.com/asset/flatpak", "notgithub-releases.githubusercontent.com/asset/flatpak") << validManifestJson() << true;
    QTest::newRow("unsafe numeric release ID")
        << replaceOnce(validReleaseJson(), "\"24680\"", "9007199254740993") << validManifestJson() << true;
    QTest::newRow("fractional numeric release ID")
        << replaceOnce(validReleaseJson(), "\"24680\"", "24680.5") << validManifestJson() << true;
    QTest::newRow("negative numeric release ID")
        << replaceOnce(validReleaseJson(), "\"24680\"", "-1") << validManifestJson() << true;
    QTest::newRow("fractional numeric asset ID")
        << replaceOnce(validReleaseJson(), "\"11223\"", "11223.5") << validManifestJson() << true;
    QTest::newRow("negative numeric asset size")
        << replaceOnce(validReleaseJson(), "\"512\"", "-1") << validManifestJson() << true;
    QTest::newRow("unsafe numeric asset size")
        << replaceOnce(validReleaseJson(), "\"512\"", "9007199254740993") << validManifestJson() << true;
    QTest::newRow("release ID overflow")
        << replaceOnce(validReleaseJson(), "\"24680\"", "\"18446744073709551616\"") << validManifestJson() << true;
    QTest::newRow("unknown manifest schema")
        << validReleaseJson() << replaceOnce(validManifestJson(), "\"schema\":1", "\"schema\":2") << false;
    QTest::newRow("foreign manifest repository")
        << validReleaseJson() << replaceOnce(validManifestJson(), "samelamin/vibertemis", "evil/vibertemis") << false;
    QTest::newRow("bad application ID")
        << validReleaseJson() << replaceOnce(validManifestJson(), "com.artemisdesktop.ArtemisDesktopDev", "com.example.Artemis") << false;
    QTest::newRow("abbreviated commit")
        << validReleaseJson() << replaceOnce(validManifestJson(), RollingCommit.toLatin1(), "bbbbbbb") << false;
    QTest::newRow("uppercase commit")
        << validReleaseJson() << replaceOnce(validManifestJson(), RollingCommit.toLatin1(), QString(40, QLatin1Char('B')).toLatin1()) << false;
    QTest::newRow("numeric sequence")
        << validReleaseJson() << replaceOnce(validManifestJson(), "\"5678\"", "5678") << false;
    QTest::newRow("sequence overflow")
        << validReleaseJson() << replaceOnce(validManifestJson(), "\"5678\"", "\"18446744073709551616\"") << false;
    QTest::newRow("large manifest")
        << validReleaseJson() << validManifestJson().append(65537, ' ') << false;
}

void AutoUpdateTest::rollingParserRejectsInvalidReleaseAndManifest()
{
    QFETCH(QByteArray, release);
    QFETCH(QByteArray, manifest);
    QFETCH(bool, invalidRelease);
    const UpdateResult<RollingUpdateCandidate> parsedRelease = RollingUpdateParser::parseRelease(release);
    if (invalidRelease) {
        QVERIFY(!parsedRelease.ok);
        QVERIFY(parsedRelease.error == UpdateError::InvalidMetadata || parsedRelease.error == UpdateError::UnsafeUrl);
        return;
    }
    QVERIFY(parsedRelease.ok);
    const UpdateResult<RollingUpdateCandidate> parsedManifest =
        RollingUpdateParser::parseManifest(manifest, parsedRelease.value);
    QVERIFY(!parsedManifest.ok);
    QVERIFY(parsedManifest.error == UpdateError::InvalidMetadata || parsedManifest.error == UpdateError::ResponseTooLarge);
}

void AutoUpdateTest::rollingParserDetectsCapturedIdentityChanges()
{
    const QList<QPair<QByteArray, QByteArray>> releaseChanges = {
        qMakePair(QByteArray("\"24680\""), QByteArray("\"24681\"")),
        qMakePair(QByteArray("steam-deck-latest"), QByteArray("other-tag")),
        qMakePair(QByteArray("2026-07-23T10:00:00Z"), QByteArray("2026-07-24T10:00:00Z")),
        qMakePair(QByteArray("\"11223\""), QByteArray("\"11224\"")),
        qMakePair(QByteArray("artemis-steam-deck-update.json"), QByteArray("other.json")),
        qMakePair(QByteArray("\"512\""), QByteArray("\"513\"")),
        qMakePair(QByteArray("assets/11223"), QByteArray("assets/11224")),
        qMakePair(QByteArray("artemis-steam-deck-update.json\""), QByteArray("different.json\"")),
        qMakePair(QByteArray("\"13579\""), QByteArray("\"13580\"")),
        qMakePair(QByteArray("artemis-steam-deck.flatpak"), QByteArray("other.flatpak")),
        qMakePair(QByteArray("\"1048576\""), QByteArray("\"1048577\"")),
        qMakePair(QByteArray("asset/flatpak"), QByteArray("asset/changed"))
    };
    const UpdateResult<RollingUpdateCandidate> release = RollingUpdateParser::parseRelease(validReleaseJson());
    QVERIFY(release.ok);
    const UpdateResult<RollingUpdateCandidate> expected =
        RollingUpdateParser::parseManifest(validManifestJson(), release.value);
    QVERIFY(expected.ok);
    for (const QPair<QByteArray, QByteArray> &change : releaseChanges) {
        const UpdateResult<bool> matched = RollingUpdateParser::matchesRelease(
            expected.value, replaceOnce(validReleaseJson(), change.first, change.second));
        QVERIFY(!matched.ok);
        QCOMPARE(matched.error, UpdateError::PublisherChanged);
    }
    const QList<QPair<QByteArray, QByteArray>> manifestChanges = {
        qMakePair(QByteArray("\"schema\":1"), QByteArray("\"schema\":2")),
        qMakePair(QByteArray("samelamin/vibertemis"), QByteArray("evil/vibertemis")),
        qMakePair(QByteArray("com.artemisdesktop.ArtemisDesktopDev"), QByteArray("com.example.Artemis")),
        qMakePair(RollingCommit.toLatin1(), TagCommit.toLatin1()),
        qMakePair(QByteArray("\"5678\""), QByteArray("\"5679\"")),
        qMakePair(QByteArray("\"24680\""), QByteArray("\"24681\"")),
        qMakePair(QByteArray("steam-deck-latest"), QByteArray("other-tag")),
        qMakePair(QByteArray("\"13579\""), QByteArray("\"13580\"")),
        qMakePair(QByteArray("artemis-steam-deck.flatpak"), QByteArray("other.flatpak")),
        qMakePair(QByteArray("\"1048576\""), QByteArray("\"1048577\"")),
        qMakePair(FlatpakDigest.toLatin1(), QByteArray(64, 'd')),
        qMakePair(QByteArray("2026-07-23T10:00:00Z"), QByteArray("2026-07-24T10:00:00Z"))
    };
    for (const QPair<QByteArray, QByteArray> &change : manifestChanges) {
        const UpdateResult<bool> matched = RollingUpdateParser::matchesManifest(
            expected.value, replaceOnce(validManifestJson(), change.first, change.second));
        QVERIFY(!matched.ok);
        QCOMPARE(matched.error, UpdateError::PublisherChanged);
    }
}

void AutoUpdateTest::rollingParserResolvesAnnotatedTags()
{
    const UpdateResult<RollingTagObject> ref = RollingUpdateParser::parseTagReference(
        QStringLiteral("{\"object\":{\"type\":\"tag\",\"sha\":\"%1\"}}").arg(TagObject).toUtf8());
    QVERIFY(ref.ok);
    const UpdateResult<RollingTagObject> tag = RollingUpdateParser::parseTagObject(
        QStringLiteral("{\"sha\":\"%1\",\"object\":{\"type\":\"tag\",\"sha\":\"%2\"}}").arg(TagObject, TagCommit).toUtf8());
    QVERIFY(tag.ok);
    const UpdateResult<RollingTagObject> commit = RollingUpdateParser::parseTagObject(
        QStringLiteral("{\"sha\":\"%1\",\"object\":{\"type\":\"commit\",\"sha\":\"%2\"}}").arg(TagCommit, RollingCommit).toUtf8());
    QVERIFY(commit.ok);
    const UpdateResult<TagResolution> resolved =
        RollingUpdateParser::resolveTagCommit(ref.value, QList<RollingTagObject>{tag.value, commit.value});
    QVERIFY(resolved.ok);
    QCOMPARE(resolved.value.tagRefObjectId, TagObject);
    QCOMPARE(resolved.value.tagObjectId, TagCommit);
    QCOMPARE(resolved.value.commit, RollingCommit);

    const UpdateResult<RollingTagObject> lightweight = RollingUpdateParser::parseTagReference(
        QStringLiteral("{\"object\":{\"type\":\"commit\",\"sha\":\"%1\"}}").arg(RollingCommit).toUtf8());
    QVERIFY(lightweight.ok);
    const UpdateResult<TagResolution> lightweightResolved =
        RollingUpdateParser::resolveTagCommit(lightweight.value, QList<RollingTagObject>());
    QVERIFY(lightweightResolved.ok);
    QCOMPARE(lightweightResolved.value.tagRefObjectId, RollingCommit);
    QVERIFY(lightweightResolved.value.tagObjectId.isEmpty());
    QCOMPARE(lightweightResolved.value.commit, RollingCommit);

    const UpdateResult<RollingUpdateCandidate> release = RollingUpdateParser::parseRelease(validReleaseJson());
    QVERIFY(release.ok);
    const UpdateResult<RollingUpdateCandidate> candidate =
        RollingUpdateParser::parseManifest(validManifestJson(), release.value);
    QVERIFY(candidate.ok);
    const UpdateResult<RollingUpdateCandidate> bound =
        RollingUpdateParser::bindTagResolution(candidate.value, resolved.value);
    QVERIFY(bound.ok);
    QCOMPARE(bound.value.tagRefObjectId, TagObject);
    QCOMPARE(bound.value.tagObjectId, TagCommit);
    QVERIFY(!RollingUpdateParser::matchesTagResolution(
        bound.value, TagResolution{QString(40, QLatin1Char('f')), TagCommit, RollingCommit}).ok);
    QVERIFY(!RollingUpdateParser::matchesTagResolution(
        bound.value, TagResolution{TagObject, QString(40, QLatin1Char('f')), RollingCommit}).ok);

    RollingUpdateCandidate changedTagField = bound.value;
    changedTagField.tagRefObjectId = QString(40, QLatin1Char('f'));
    QVERIFY(!RollingUpdateParser::matchesCandidate(bound.value, changedTagField).ok);
    changedTagField = bound.value;
    changedTagField.tagObjectId = QString(40, QLatin1Char('f'));
    QVERIFY(!RollingUpdateParser::matchesCandidate(bound.value, changedTagField).ok);
}

void AutoUpdateTest::rollingParserRejectsInvalidTagGraphs()
{
    QVERIFY(!RollingUpdateParser::parseTagReference(QByteArray("{\"object\":{\"type\":\"tree\",\"sha\":\"deadbeef\"}}")) .ok);
    const RollingTagObject reference{TagObject, QStringLiteral("tag"), TagObject};
    QVERIFY(!RollingUpdateParser::resolveTagCommit(reference, QList<RollingTagObject>()).ok);
    QVERIFY(!RollingUpdateParser::resolveTagCommit(reference,
        QList<RollingTagObject>{RollingTagObject{TagObject, QStringLiteral("tag"), TagObject}}).ok);
    const RollingTagObject tag{TagObject, QStringLiteral("commit"), RollingCommit};
    QVERIFY(!RollingUpdateParser::resolveTagCommit(reference,
        QList<RollingTagObject>{tag, tag}).ok);
    QVERIFY(!RollingUpdateParser::resolveTagCommit(reference,
        QList<RollingTagObject>{
            tag,
            RollingTagObject{TagObject, QStringLiteral("commit"), QString(40, QLatin1Char('f'))}
        }).ok);
}

void AutoUpdateTest::rollingParserClassifiesCommitRelations()
{
    QCOMPARE(RollingUpdateParser::parseCommitRelation(QByteArray("{\"status\":\"ahead\"}")), CommitRelation::CandidateAhead);
    QCOMPARE(RollingUpdateParser::parseCommitRelation(QByteArray("{\"status\":\"identical\"}")), CommitRelation::Equal);
    QCOMPARE(RollingUpdateParser::parseCommitRelation(QByteArray("{\"status\":\"behind\"}")), CommitRelation::CandidateBehind);
    QCOMPARE(RollingUpdateParser::parseCommitRelation(QByteArray("{\"status\":\"diverged\"}")), CommitRelation::Diverged);
    QCOMPARE(RollingUpdateParser::parseCommitRelation(QByteArray("{\"status\":\"unknown\"}")), CommitRelation::Unknown);
}

void AutoUpdateTest::rollingParserRequiresNewerSequence()
{
    const UpdateResult<RollingUpdateCandidate> release = RollingUpdateParser::parseRelease(validReleaseJson());
    QVERIFY(release.ok);
    const UpdateResult<RollingUpdateCandidate> candidate = RollingUpdateParser::parseManifest(validManifestJson(), release.value);
    QVERIFY(candidate.ok);
    QVERIFY(RollingUpdateParser::isInstallable(RollingCommit, 1234, candidate.value, CommitRelation::CandidateAhead).ok);
    QVERIFY(!RollingUpdateParser::isInstallable(RollingCommit, 5678, candidate.value, CommitRelation::CandidateAhead).ok);
    QVERIFY(!RollingUpdateParser::isInstallable(RollingCommit, 9999, candidate.value, CommitRelation::CandidateAhead).ok);
    QVERIFY(!RollingUpdateParser::isInstallable(RollingCommit, 1234, candidate.value, CommitRelation::Equal).ok);
    QVERIFY(!RollingUpdateParser::isInstallable(RollingCommit, 1234, candidate.value, CommitRelation::CandidateBehind).ok);
    QVERIFY(!RollingUpdateParser::isInstallable(RollingCommit, 1234, candidate.value, CommitRelation::Diverged).ok);
    QVERIFY(!RollingUpdateParser::isInstallable(RollingCommit, 1234, candidate.value, CommitRelation::Unknown).ok);
}

void AutoUpdateTest::steamDeckSessionClassifiesEnvironment_data()
{
    QTest::addColumn<QProcessEnvironment>("environment");
    QTest::addColumn<SteamDeckSession::Mode>("expected");
    QProcessEnvironment gaming;
    gaming.insert(QStringLiteral("GAMESCOPE_WAYLAND_DISPLAY"), QStringLiteral("gamescope-0"));
    QTest::newRow("gamescope display") << gaming << SteamDeckSession::Gaming;
    QProcessEnvironment desktopName;
    desktopName.insert(QStringLiteral("XDG_CURRENT_DESKTOP"), QStringLiteral("gamescope"));
    QTest::newRow("gamescope desktop") << desktopName << SteamDeckSession::Gaming;
    QProcessEnvironment desktop;
    desktop.insert(QStringLiteral("XDG_CURRENT_DESKTOP"), QStringLiteral("KDE"));
    desktop.insert(QStringLiteral("KDE_FULL_SESSION"), QStringLiteral("true"));
    desktop.insert(QStringLiteral("XDG_SESSION_TYPE"), QStringLiteral("wayland"));
    QTest::newRow("KDE Wayland") << desktop << SteamDeckSession::Desktop;
    desktop.insert(QStringLiteral("XDG_CURRENT_DESKTOP"), QStringLiteral("plasma:kDe"));
    desktop.insert(QStringLiteral("XDG_SESSION_TYPE"), QStringLiteral("x11"));
    QTest::newRow("KDE X11 case insensitive") << desktop << SteamDeckSession::Desktop;
    desktop.insert(QStringLiteral("GAMESCOPE_WAYLAND_DISPLAY"), QStringLiteral("gamescope-0"));
    QTest::newRow("gaming wins conflict") << desktop << SteamDeckSession::Gaming;
    QTest::newRow("missing") << QProcessEnvironment() << SteamDeckSession::Unknown;
    QProcessEnvironment partial;
    partial.insert(QStringLiteral("XDG_CURRENT_DESKTOP"), QStringLiteral("KDE"));
    QTest::newRow("partial") << partial << SteamDeckSession::Unknown;
    QProcessEnvironment unrelated;
    unrelated.insert(QStringLiteral("XDG_CURRENT_DESKTOP"), QStringLiteral("GNOME"));
    unrelated.insert(QStringLiteral("KDE_FULL_SESSION"), QStringLiteral("true"));
    unrelated.insert(QStringLiteral("XDG_SESSION_TYPE"), QStringLiteral("wayland"));
    QTest::newRow("unrelated") << unrelated << SteamDeckSession::Unknown;
    QProcessEnvironment gamescopeNearMiss;
    gamescopeNearMiss.insert(QStringLiteral("XDG_CURRENT_DESKTOP"), QStringLiteral("notgamescope"));
    QTest::newRow("gamescope token near miss") << gamescopeNearMiss << SteamDeckSession::Unknown;
    QProcessEnvironment kdeNearMiss;
    kdeNearMiss.insert(QStringLiteral("XDG_CURRENT_DESKTOP"), QStringLiteral("fakekde"));
    kdeNearMiss.insert(QStringLiteral("KDE_FULL_SESSION"), QStringLiteral("true"));
    kdeNearMiss.insert(QStringLiteral("XDG_SESSION_TYPE"), QStringLiteral("wayland"));
    QTest::newRow("KDE token near miss") << kdeNearMiss << SteamDeckSession::Unknown;
}

void AutoUpdateTest::steamDeckSessionClassifiesEnvironment()
{
    QFETCH(QProcessEnvironment, environment);
    QFETCH(SteamDeckSession::Mode, expected);
    QCOMPARE(SteamDeckSession::classify(environment), expected);
}

void AutoUpdateTest::secureUpdateFiles()
{
    QTemporaryDir root;
    QVERIFY(root.isValid());
    const QString downloads = root.path() + QStringLiteral("/Downloads");
    const QString privateData = root.path() + QStringLiteral("/private");
    QVERIFY(QDir().mkpath(downloads));
    QVERIFY(QDir().mkpath(privateData));

    const QByteArray payload("verified flatpak payload");
    const RollingUpdateCandidate candidate = storageCandidate(payload);
    FakeStorageProbe probe;
    probe.available = candidate.flatpak.size
        + PendingUpdateStore::safetyMarginBytes() - 1;
    PendingUpdateStore store(downloads, privateData, &probe);

    const UpdateResult<QSharedPointer<QTemporaryFile>> insufficient =
        store.createDownload(candidate.flatpak.size);
    QVERIFY(!insufficient.ok);
    QCOMPARE(insufficient.error, UpdateError::InsufficientSpace);

    probe.available = candidate.flatpak.size
        + PendingUpdateStore::safetyMarginBytes();
    const UpdateResult<QSharedPointer<QTemporaryFile>> created =
        store.createDownload(candidate.flatpak.size);
    QVERIFY(created.ok);
    QVERIFY(created.value);
    QVERIFY(created.value->isOpen());
    const QString temporaryPath = QFileInfo(created.value->fileName()).absoluteFilePath();
    QVERIFY(temporaryPath.startsWith(QFileInfo(downloads).canonicalFilePath()
                                     + QDir::separator()));
    QVERIFY(QFileInfo(temporaryPath).fileName().startsWith(
        QStringLiteral(".vibertemis-update-")));
    QVERIFY(temporaryPath.endsWith(QStringLiteral(".part")));
    QCOMPARE(created.value->write(payload), qint64(payload.size()));

    const UpdateResult<UpdateFileStore::OpenVerifiedFile> finalized =
        store.finalizeAndVerify(created.value, candidate);
    QVERIFY2(finalized.ok, qPrintable(finalized.message));
    QVERIFY(finalized.value.file);
    QVERIFY(finalized.value.file->isOpen());
    QCOMPARE(finalized.value.size, candidate.flatpak.size);
    QCOMPARE(finalized.value.sha256, candidate.flatpak.sha256);
    QCOMPARE(QFileInfo(finalized.value.canonicalPath).fileName(),
             QStringLiteral("artemis-steam-deck-bbbbbbbbbbbb.flatpak"));
    QVERIFY(!QFileInfo::exists(temporaryPath));

#ifdef Q_OS_UNIX
    finalized.value.file->close();
    const UpdateResult<QSharedPointer<QTemporaryFile>> racePart =
        store.createDownload(candidate.flatpak.size);
    QVERIFY(racePart.ok);
    QCOMPARE(racePart.value->write(payload), qint64(payload.size()));
    probe.swapPathAfterOpen = true;
    const UpdateResult<UpdateFileStore::OpenVerifiedFile> raced =
        store.finalizeAndVerify(racePart.value, candidate);
    QVERIFY2(raced.ok, qPrintable(raced.message));
    QVERIFY(probe.swapSucceeded);
    QVERIFY(raced.value.file->seek(0));
    QCOMPARE(raced.value.file->readAll(), payload);
    raced.value.file->close();
    QFile replacement(finalized.value.canonicalPath);
    QVERIFY(replacement.open(QIODevice::ReadOnly));
    QCOMPARE(replacement.readAll(), QByteArray("replacement"));
    replacement.close();
    QVERIFY(QFile::remove(finalized.value.canonicalPath));
    QVERIFY(QFile::rename(probe.openedInodePath, finalized.value.canonicalPath));
#endif

    auto verifyRejectedPayload = [&](const QByteArray &contents,
                                     const RollingUpdateCandidate &expected,
                                     UpdateError error) {
        QFile::remove(downloads + QStringLiteral("/artemis-steam-deck-bbbbbbbbbbbb.flatpak"));
        const UpdateResult<QSharedPointer<QTemporaryFile>> part =
            store.createDownload(expected.flatpak.size);
        QVERIFY(part.ok);
        const QString partPath = part.value->fileName();
        QCOMPARE(part.value->write(contents), qint64(contents.size()));
        const UpdateResult<UpdateFileStore::OpenVerifiedFile> result =
            store.finalizeAndVerify(part.value, expected);
        QVERIFY(!result.ok);
        QCOMPARE(result.error, error);
        QVERIFY(!QFileInfo::exists(partPath));
    };

    verifyRejectedPayload(payload.left(payload.size() - 1),
                          candidate, UpdateError::SizeMismatch);
    verifyRejectedPayload(payload + QByteArray("x"),
                          candidate, UpdateError::SizeMismatch);
    QByteArray wrongDigestPayload = payload;
    wrongDigestPayload[0] = 'V';
    verifyRejectedPayload(wrongDigestPayload,
                          candidate, UpdateError::DigestMismatch);

    const auto writePart = [&](const QByteArray &contents)
            -> QSharedPointer<QTemporaryFile> {
        const UpdateResult<QSharedPointer<QTemporaryFile>> part =
            store.createDownload(candidate.flatpak.size);
        if (!part.ok || part.value->write(contents) != contents.size()) {
            return QSharedPointer<QTemporaryFile>();
        }
        return part.value;
    };
    QSharedPointer<QTemporaryFile> validPart = writePart(payload);
    QVERIFY(validPart);
    UpdateResult<UpdateFileStore::OpenVerifiedFile> first =
        store.finalizeAndVerify(validPart, candidate);
    QVERIFY(first.ok);
    first.value.file->close();

    QSharedPointer<QTemporaryFile> duplicatePart = writePart(QByteArray(payload.size(), 'x'));
    QVERIFY(duplicatePart);
    const QString duplicatePath = duplicatePart->fileName();
    const UpdateResult<UpdateFileStore::OpenVerifiedFile> reused =
        store.finalizeAndVerify(duplicatePart, candidate);
    QVERIFY(reused.ok);
    QVERIFY(!QFileInfo::exists(duplicatePath));
    QCOMPARE(reused.value.sha256, candidate.flatpak.sha256);
    reused.value.file->close();

    QFile corruptedFinal(reused.value.canonicalPath);
    QVERIFY(corruptedFinal.open(QIODevice::WriteOnly | QIODevice::Truncate));
    QCOMPARE(corruptedFinal.write(QByteArray(payload.size(), 'z')), qint64(payload.size()));
    corruptedFinal.close();
    QSharedPointer<QTemporaryFile> replacementPart = writePart(payload);
    QVERIFY(replacementPart);
    const UpdateResult<UpdateFileStore::OpenVerifiedFile> replaced =
        store.finalizeAndVerify(replacementPart, candidate);
    QVERIFY(replaced.ok);
    replaced.value.file->close();

#ifdef Q_OS_UNIX
    QVERIFY(QFile::remove(replaced.value.canonicalPath));
    const QString symlinkTarget = root.path() + QStringLiteral("/outside.flatpak");
    QFile outside(symlinkTarget);
    QVERIFY(outside.open(QIODevice::WriteOnly));
    QCOMPARE(outside.write(payload), qint64(payload.size()));
    outside.close();
    QVERIFY(QFile::link(symlinkTarget, replaced.value.canonicalPath));
    QSharedPointer<QTemporaryFile> symlinkPart = writePart(payload);
    QVERIFY(symlinkPart);
    const UpdateResult<UpdateFileStore::OpenVerifiedFile> symlinkResult =
        store.finalizeAndVerify(symlinkPart, candidate);
    QVERIFY(!symlinkResult.ok);
    QCOMPARE(symlinkResult.error, UpdateError::UnsafePath);
    QVERIFY(QFileInfo(replaced.value.canonicalPath).isSymLink());
    QVERIFY(QFile::remove(replaced.value.canonicalPath));
#endif

    QFile recentOwned(downloads + QStringLiteral("/.vibertemis-update-AbC123.part"));
    QVERIFY(recentOwned.open(QIODevice::WriteOnly));
    recentOwned.close();
    QFile unrelated(downloads + QStringLiteral("/someone-else.part"));
    QVERIFY(unrelated.open(QIODevice::WriteOnly));
    unrelated.close();
    probe.now = QDateTime::currentDateTimeUtc();
    store.cleanStaleParts();
    QVERIFY(QFileInfo::exists(recentOwned.fileName()));
    probe.now = QDateTime::currentDateTimeUtc().addDays(2);
    store.cleanStaleParts();
    QVERIFY(!QFileInfo::exists(recentOwned.fileName()));
    QVERIFY(QFileInfo::exists(unrelated.fileName()));
}

void AutoUpdateTest::pendingUpdateRecord()
{
    QTemporaryDir root;
    QVERIFY(root.isValid());
    const QString downloads = root.path() + QStringLiteral("/Downloads");
    const QString privateData = root.path() + QStringLiteral("/private");
    QVERIFY(QDir().mkpath(downloads));
    QVERIFY(QDir().mkpath(privateData));

    const QByteArray payload("persistent verified flatpak");
    const RollingUpdateCandidate candidate = storageCandidate(payload);
    FakeStorageProbe probe;
    probe.available = candidate.flatpak.size
        + PendingUpdateStore::safetyMarginBytes();
    PendingUpdateStore store(downloads, privateData, &probe);

    const auto makeVerifiedRecord = [&]()
            -> PendingUpdateRecord {
        PendingUpdateRecord record;
        const UpdateResult<QSharedPointer<QTemporaryFile>> part =
            store.createDownload(candidate.flatpak.size);
        if (!part.ok || part.value->write(payload) != payload.size()) {
            return record;
        }
        const UpdateResult<UpdateFileStore::OpenVerifiedFile> verified =
            store.finalizeAndVerify(part.value, candidate);
        if (!verified.ok) {
            return record;
        }
        record.canonicalPath = verified.value.canonicalPath;
        record.candidate = candidate;
        record.verifiedSize = verified.value.size;
        record.verifiedSha256 = verified.value.sha256;
        verified.value.file->close();
        return record;
    };

    PendingUpdateRecord record = makeVerifiedRecord();
    QVERIFY(!record.canonicalPath.isEmpty());
    QVERIFY(store.save(record));
    const QString recordPath = privateData + QStringLiteral("/pending-update.json");
    QFile recordFile(recordPath);
    QVERIFY(recordFile.open(QIODevice::ReadOnly));
    const QJsonDocument storedDocument = QJsonDocument::fromJson(recordFile.readAll());
    recordFile.close();
    QVERIFY(storedDocument.isObject());
    const QJsonObject stored = storedDocument.object();
    QCOMPARE(stored.value(QStringLiteral("schema")).toInt(), 1);
    QCOMPARE(stored.value(QStringLiteral("verified_size")).toString(),
             QString::number(record.verifiedSize));
    QCOMPARE(stored.value(QStringLiteral("verified_sha256")).toString(),
             QString::fromLatin1(record.verifiedSha256));
    const QJsonObject storedCandidate =
        stored.value(QStringLiteral("candidate")).toObject();
    QCOMPARE(storedCandidate.value(QStringLiteral("release_id")).toString(),
             QStringLiteral("24680"));
    QCOMPARE(storedCandidate.value(QStringLiteral("release_label")).toString(),
             candidate.releaseLabel);
    QCOMPARE(storedCandidate.value(QStringLiteral("release_page")).toString(),
             candidate.releasePage.toString());
    QCOMPARE(storedCandidate.value(QStringLiteral("release_updated_at")).toString(),
             QStringLiteral("2026-07-23T10:00:00Z"));
    QCOMPARE(storedCandidate.value(QStringLiteral("source_commit")).toString(),
             candidate.sourceCommit);
    QCOMPARE(storedCandidate.value(QStringLiteral("build_sequence")).toString(),
             QStringLiteral("5678"));
    QCOMPARE(storedCandidate.value(QStringLiteral("tag_ref_object_id")).toString(),
             candidate.tagRefObjectId);
    QCOMPARE(storedCandidate.value(QStringLiteral("tag_object_id")).toString(),
             candidate.tagObjectId);
    QCOMPARE(storedCandidate.value(QStringLiteral("manifest_schema")).toInt(), 1);
    QCOMPARE(storedCandidate.value(QStringLiteral("published_at")).toString(),
             QStringLiteral("2026-07-23T10:00:00Z"));
    for (const QString &assetName :
         {QStringLiteral("manifest"), QStringLiteral("flatpak")}) {
        const QJsonObject asset = storedCandidate.value(assetName).toObject();
        QVERIFY(!asset.isEmpty());
        QVERIFY(asset.value(QStringLiteral("id")).isString());
        QVERIFY(asset.value(QStringLiteral("size")).isString());
        QVERIFY(asset.value(QStringLiteral("name")).isString());
        QVERIFY(asset.value(QStringLiteral("api_url")).isString());
        QVERIFY(asset.value(QStringLiteral("download_url")).isString());
        QVERIFY(asset.value(QStringLiteral("updated_at")).isString());
        QVERIFY(asset.value(QStringLiteral("sha256")).isString());
    }

    const UpdateResult<PendingUpdateRecord> loaded = store.load();
    QVERIFY2(loaded.ok, qPrintable(loaded.message));
    QCOMPARE(loaded.value.canonicalPath, record.canonicalPath);
    QCOMPARE(loaded.value.verifiedSize, record.verifiedSize);
    QCOMPARE(loaded.value.verifiedSha256, record.verifiedSha256);
    QVERIFY(RollingUpdateParser::matchesCandidate(
        record.candidate, loaded.value.candidate).ok);
    const UpdateResult<UpdateFileStore::OpenVerifiedFile> reopened =
        store.reopenAndVerify(loaded.value, candidate);
    QVERIFY2(reopened.ok, qPrintable(reopened.message));
    reopened.value.file->close();

    RollingUpdateCandidate changedBinding = candidate;
    ++changedBinding.releaseId;
    const UpdateResult<UpdateFileStore::OpenVerifiedFile> changed =
        store.reopenAndVerify(loaded.value, changedBinding);
    QVERIFY(!changed.ok);
    QCOMPARE(changed.error, UpdateError::PublisherChanged);
    QVERIFY(!QFileInfo::exists(recordPath));

    QFile unrelated(downloads + QStringLiteral("/family-photo.flatpak"));
    QVERIFY(unrelated.open(QIODevice::WriteOnly));
    QCOMPARE(unrelated.write("do not touch"), qint64(12));
    unrelated.close();

    auto writeRawRecord = [&](const QByteArray &json) {
        QFile file(recordPath);
        if (!file.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
            return false;
        }
        return file.write(json) == json.size();
    };

    QVERIFY(writeRawRecord(QByteArray("{")));
    QVERIFY(!store.load().ok);
    QVERIFY(!QFileInfo::exists(recordPath));
    QVERIFY(QFileInfo::exists(unrelated.fileName()));

    record = makeVerifiedRecord();
    QVERIFY(store.save(record));
    QVERIFY(recordFile.open(QIODevice::ReadOnly));
    QJsonObject hostile = QJsonDocument::fromJson(recordFile.readAll()).object();
    recordFile.close();
    hostile.insert(QStringLiteral("unexpected"), true);
    QVERIFY(writeRawRecord(QJsonDocument(hostile).toJson(QJsonDocument::Compact)));
    QVERIFY(!store.load().ok);
    QVERIFY(QFileInfo::exists(record.canonicalPath));
    QVERIFY(QFileInfo::exists(unrelated.fileName()));

    QVERIFY(store.save(record));
    QVERIFY(recordFile.open(QIODevice::ReadOnly));
    hostile = QJsonDocument::fromJson(recordFile.readAll()).object();
    recordFile.close();
    QJsonObject hostileCandidate =
        hostile.value(QStringLiteral("candidate")).toObject();
    QJsonObject hostileManifest =
        hostileCandidate.value(QStringLiteral("manifest")).toObject();
    hostileManifest.insert(
        QStringLiteral("api_url"),
        QStringLiteral("https://api.github.com:444/repos/samelamin/vibertemis/"
                       "releases/assets/11223"));
    hostileCandidate.insert(QStringLiteral("manifest"), hostileManifest);
    hostile.insert(QStringLiteral("candidate"), hostileCandidate);
    QVERIFY(writeRawRecord(QJsonDocument(hostile).toJson(QJsonDocument::Compact)));
    QVERIFY(!store.load().ok);
    QVERIFY(QFileInfo::exists(record.canonicalPath));
    QVERIFY(QFileInfo::exists(unrelated.fileName()));

    QVERIFY(store.save(record));
    QVERIFY(recordFile.open(QIODevice::ReadOnly));
    hostile = QJsonDocument::fromJson(recordFile.readAll()).object();
    recordFile.close();
    hostile.insert(QStringLiteral("canonical_path"), unrelated.fileName());
    QVERIFY(writeRawRecord(QJsonDocument(hostile).toJson(QJsonDocument::Compact)));
    QVERIFY(!store.load().ok);
    QVERIFY(QFileInfo::exists(unrelated.fileName()));
    QVERIFY(!QFileInfo::exists(recordPath));

    QVERIFY(store.save(record));
    QVERIFY(QFile::remove(record.canonicalPath));
    QVERIFY(!store.load().ok);
    QVERIFY(!QFileInfo::exists(recordPath));
    QVERIFY(QFileInfo::exists(unrelated.fileName()));

    record = makeVerifiedRecord();
    QVERIFY(store.save(record));
    QFile replaced(record.canonicalPath);
    QVERIFY(replaced.open(QIODevice::WriteOnly | QIODevice::Truncate));
    QCOMPARE(replaced.write(QByteArray(payload.size(), 'x')), qint64(payload.size()));
    replaced.close();
    QVERIFY(!store.load().ok);
    QVERIFY(!QFileInfo::exists(recordPath));
    QVERIFY(QFileInfo::exists(unrelated.fileName()));

#ifdef Q_OS_UNIX
    record = makeVerifiedRecord();
    QVERIFY(store.save(record));
    QVERIFY(QFile::remove(record.canonicalPath));
    QVERIFY(QFile::link(unrelated.fileName(), record.canonicalPath));
    QVERIFY(!store.load().ok);
    QVERIFY(QFileInfo::exists(unrelated.fileName()));
    QVERIFY(!QFileInfo::exists(recordPath));
    QVERIFY(!QFileInfo::exists(record.canonicalPath));
#endif

    record = makeVerifiedRecord();
    QVERIFY(store.save(record));
    store.clear(true);
    QVERIFY(!QFileInfo::exists(recordPath));
    QVERIFY(!QFileInfo::exists(record.canonicalPath));
    QVERIFY(QFileInfo::exists(unrelated.fileName()));
}

void AutoUpdateTest::pendingUpdateRejectsHostileCandidate_data()
{
    QTest::addColumn<QString>("objectName");
    QTest::addColumn<QString>("fieldName");
    QTest::addColumn<QString>("replacement");
    QTest::addColumn<bool>("accepted");

    QTest::newRow("release label")
        << QString() << QStringLiteral("release_label")
        << QStringLiteral("not-the-rolling-tag") << false;
    QTest::newRow("release page path")
        << QString() << QStringLiteral("release_page")
        << QStringLiteral("https://github.com/samelamin/vibertemis/releases/other")
        << false;
    QTest::newRow("manifest name")
        << QStringLiteral("manifest") << QStringLiteral("name")
        << QStringLiteral("replacement.json") << false;
    QTest::newRow("manifest API path")
        << QStringLiteral("manifest") << QStringLiteral("api_url")
        << QStringLiteral("https://api.github.com/repos/samelamin/vibertemis/releases/assets/999")
        << false;
    QTest::newRow("manifest GitHub download path")
        << QStringLiteral("manifest") << QStringLiteral("download_url")
        << QStringLiteral("https://github.com/samelamin/vibertemis/releases/other.json")
        << false;
    QTest::newRow("manifest unapproved CDN")
        << QStringLiteral("manifest") << QStringLiteral("download_url")
        << QStringLiteral("https://downloads.example.com/update.json") << false;
    QTest::newRow("Flatpak name")
        << QStringLiteral("flatpak") << QStringLiteral("name")
        << QStringLiteral("replacement.flatpak") << false;
    QTest::newRow("Flatpak API path")
        << QStringLiteral("flatpak") << QStringLiteral("api_url")
        << QStringLiteral("https://api.github.com/repos/samelamin/vibertemis/releases/assets/999")
        << false;
    QTest::newRow("Flatpak GitHub download path")
        << QStringLiteral("flatpak") << QStringLiteral("download_url")
        << QStringLiteral("https://github.com/samelamin/vibertemis/releases/not-an-asset")
        << false;
    QTest::newRow("Flatpak deceptive CDN")
        << QStringLiteral("flatpak") << QStringLiteral("download_url")
        << QStringLiteral("https://release-assets.githubusercontent.com.evil.example/asset")
        << false;
    QTest::newRow("approved release-assets subdomain")
        << QStringLiteral("flatpak") << QStringLiteral("download_url")
        << QStringLiteral("https://production.release-assets.githubusercontent.com/asset/flatpak")
        << true;
}

void AutoUpdateTest::pendingUpdateRejectsHostileCandidate()
{
    QFETCH(QString, objectName);
    QFETCH(QString, fieldName);
    QFETCH(QString, replacement);
    QFETCH(bool, accepted);

    QTemporaryDir root;
    QVERIFY(root.isValid());
    const QString downloads = root.path() + QStringLiteral("/Downloads");
    const QString privateData = root.path() + QStringLiteral("/private");
    QVERIFY(QDir().mkpath(downloads));
    QVERIFY(QDir().mkpath(privateData));

    const QByteArray payload("candidate policy payload");
    const RollingUpdateCandidate candidate = storageCandidate(payload);
    FakeStorageProbe probe;
    probe.available = candidate.flatpak.size
        + PendingUpdateStore::safetyMarginBytes();
    PendingUpdateStore store(downloads, privateData, &probe);
    const UpdateResult<QSharedPointer<QTemporaryFile>> part =
        store.createDownload(candidate.flatpak.size);
    QVERIFY(part.ok);
    QCOMPARE(part.value->write(payload), qint64(payload.size()));
    const UpdateResult<UpdateFileStore::OpenVerifiedFile> verified =
        store.finalizeAndVerify(part.value, candidate);
    QVERIFY(verified.ok);

    PendingUpdateRecord record;
    record.canonicalPath = verified.value.canonicalPath;
    record.candidate = candidate;
    record.verifiedSize = verified.value.size;
    record.verifiedSha256 = verified.value.sha256;
    verified.value.file->close();
    QVERIFY(store.save(record));

    const QString unrelatedPath = downloads + QStringLiteral("/unrelated.flatpak");
    QFile unrelated(unrelatedPath);
    QVERIFY(unrelated.open(QIODevice::WriteOnly));
    QCOMPARE(unrelated.write("unrelated"), qint64(9));
    unrelated.close();

    const QString recordPath = privateData + QStringLiteral("/pending-update.json");
    QFile recordFile(recordPath);
    QVERIFY(recordFile.open(QIODevice::ReadOnly));
    QJsonObject rootObject =
        QJsonDocument::fromJson(recordFile.readAll()).object();
    recordFile.close();
    QJsonObject candidateObject =
        rootObject.value(QStringLiteral("candidate")).toObject();
    if (objectName.isEmpty()) {
        candidateObject.insert(fieldName, replacement);
    } else {
        QJsonObject child = candidateObject.value(objectName).toObject();
        child.insert(fieldName, replacement);
        candidateObject.insert(objectName, child);
    }
    rootObject.insert(QStringLiteral("candidate"), candidateObject);
    QVERIFY(recordFile.open(QIODevice::WriteOnly | QIODevice::Truncate));
    const QByteArray mutated =
        QJsonDocument(rootObject).toJson(QJsonDocument::Compact);
    QCOMPARE(recordFile.write(mutated), qint64(mutated.size()));
    recordFile.close();

    const UpdateResult<PendingUpdateRecord> loaded = store.load();
    QCOMPARE(loaded.ok, accepted);
    QVERIFY(QFileInfo::exists(unrelatedPath));
    QVERIFY(QFileInfo::exists(record.canonicalPath));
}

void AutoUpdateTest::pendingUpdateRejectsAlternatePath()
{
#ifndef Q_OS_UNIX
    QSKIP("Intermediate symlink path regression is Unix-specific");
#else
    QTemporaryDir root;
    QVERIFY(root.isValid());
    const QString downloads = root.path() + QStringLiteral("/Downloads");
    const QString privateData = root.path() + QStringLiteral("/private");
    const QString outside = root.path() + QStringLiteral("/outside");
    const QString outsideChild = outside + QStringLiteral("/child");
    QVERIFY(QDir().mkpath(downloads));
    QVERIFY(QDir().mkpath(privateData));
    QVERIFY(QDir().mkpath(outsideChild));
    QVERIFY(QFile::link(outsideChild, downloads + QStringLiteral("/escape")));

    const QByteArray payload("alternate path payload");
    const RollingUpdateCandidate candidate = storageCandidate(payload);
    FakeStorageProbe probe;
    probe.available = candidate.flatpak.size
        + PendingUpdateStore::safetyMarginBytes();
    PendingUpdateStore store(downloads, privateData, &probe);
    const UpdateResult<QSharedPointer<QTemporaryFile>> part =
        store.createDownload(candidate.flatpak.size);
    QVERIFY(part.ok);
    QCOMPARE(part.value->write(payload), qint64(payload.size()));
    const UpdateResult<UpdateFileStore::OpenVerifiedFile> verified =
        store.finalizeAndVerify(part.value, candidate);
    QVERIFY(verified.ok);

    PendingUpdateRecord record;
    record.canonicalPath = verified.value.canonicalPath;
    record.candidate = candidate;
    record.verifiedSize = verified.value.size;
    record.verifiedSha256 = verified.value.sha256;
    verified.value.file->close();
    QVERIFY(store.save(record));

    const QString filename = QFileInfo(record.canonicalPath).fileName();
    const QString outsidePayload = outside + QDir::separator() + filename;
    QFile outsideFile(outsidePayload);
    QVERIFY(outsideFile.open(QIODevice::WriteOnly));
    QCOMPARE(outsideFile.write(payload), qint64(payload.size()));
    outsideFile.close();
    const QString alternate =
        downloads + QStringLiteral("/escape/../") + filename;

    const QString recordPath = privateData + QStringLiteral("/pending-update.json");
    QFile recordFile(recordPath);
    QVERIFY(recordFile.open(QIODevice::ReadOnly));
    const QJsonObject validObject =
        QJsonDocument::fromJson(recordFile.readAll()).object();
    recordFile.close();
    auto writeRecordPath = [&](const QString &path) {
        QJsonObject object = validObject;
        object.insert(QStringLiteral("canonical_path"), path);
        const QByteArray hostile =
            QJsonDocument(object).toJson(QJsonDocument::Compact);
        if (!recordFile.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
            return false;
        }
        const bool written = recordFile.write(hostile) == hostile.size();
        recordFile.close();
        return written;
    };

    const QString dotted =
        downloads + QStringLiteral("/./") + filename;
    PendingUpdateRecord dottedRecord = record;
    dottedRecord.canonicalPath = dotted;
    QVERIFY(!store.save(dottedRecord));
    QVERIFY(!store.reopenAndVerify(dottedRecord, candidate).ok);
    QVERIFY(writeRecordPath(dotted));
    QVERIFY(!store.load().ok);
    QVERIFY(QFileInfo::exists(record.canonicalPath));
    QVERIFY(writeRecordPath(dotted));
    store.clear(true);
    QVERIFY(QFileInfo::exists(record.canonicalPath));

    QVERIFY(store.save(record));
    QVERIFY(writeRecordPath(alternate));
    QVERIFY(!store.load().ok);
    QVERIFY(QFileInfo::exists(outsidePayload));

    QVERIFY(writeRecordPath(alternate));
    store.clear(true);
    QVERIFY(QFileInfo::exists(outsidePayload));
    QVERIFY(!QFileInfo::exists(recordPath));
#endif
}

void AutoUpdateTest::pendingUpdatePreservesFractionalTimestamps()
{
    QTemporaryDir root;
    QVERIFY(root.isValid());
    const QString downloads = root.path() + QStringLiteral("/Downloads");
    const QString privateData = root.path() + QStringLiteral("/private");
    QVERIFY(QDir().mkpath(downloads));
    QVERIFY(QDir().mkpath(privateData));

    const QByteArray payload("fractional timestamp payload");
    RollingUpdateCandidate candidate = storageCandidate(payload);
    candidate.releaseUpdatedAt = QDateTime::fromString(
        QStringLiteral("2026-07-23T10:00:00.123Z"), Qt::ISODate);
    candidate.publishedAt = QDateTime::fromString(
        QStringLiteral("2026-07-23T10:00:00.234Z"), Qt::ISODate);
    candidate.manifest.updatedAt = QDateTime::fromString(
        QStringLiteral("2026-07-23T10:00:00.345Z"), Qt::ISODate);
    candidate.flatpak.updatedAt = QDateTime::fromString(
        QStringLiteral("2026-07-23T10:00:00.456Z"), Qt::ISODate);
    FakeStorageProbe probe;
    probe.available = candidate.flatpak.size
        + PendingUpdateStore::safetyMarginBytes();
    PendingUpdateStore store(downloads, privateData, &probe);
    const UpdateResult<QSharedPointer<QTemporaryFile>> part =
        store.createDownload(candidate.flatpak.size);
    QVERIFY(part.ok);
    QCOMPARE(part.value->write(payload), qint64(payload.size()));
    const UpdateResult<UpdateFileStore::OpenVerifiedFile> verified =
        store.finalizeAndVerify(part.value, candidate);
    QVERIFY(verified.ok);

    PendingUpdateRecord record;
    record.canonicalPath = verified.value.canonicalPath;
    record.candidate = candidate;
    record.verifiedSize = verified.value.size;
    record.verifiedSha256 = verified.value.sha256;
    verified.value.file->close();
    QVERIFY(store.save(record));
    const UpdateResult<PendingUpdateRecord> loaded = store.load();
    QVERIFY(loaded.ok);
    QCOMPARE(loaded.value.candidate.releaseUpdatedAt,
             candidate.releaseUpdatedAt);
    QCOMPARE(loaded.value.candidate.publishedAt, candidate.publishedAt);
    QCOMPARE(loaded.value.candidate.manifest.updatedAt,
             candidate.manifest.updatedAt);
    QCOMPARE(loaded.value.candidate.flatpak.updatedAt,
             candidate.flatpak.updatedAt);
    QVERIFY(RollingUpdateParser::matchesCandidate(
        candidate, loaded.value.candidate).ok);
}

void AutoUpdateTest::pendingUpdateRejectsOversizedRecord()
{
    QTemporaryDir root;
    QVERIFY(root.isValid());
    const QString downloads = root.path() + QStringLiteral("/Downloads");
    const QString privateData = root.path() + QStringLiteral("/private");
    QVERIFY(QDir().mkpath(downloads));
    QVERIFY(QDir().mkpath(privateData));

    const QByteArray payload("oversized record payload");
    RollingUpdateCandidate candidate = storageCandidate(payload);
    FakeStorageProbe probe;
    probe.available = candidate.flatpak.size
        + PendingUpdateStore::safetyMarginBytes();
    PendingUpdateStore store(downloads, privateData, &probe);
    const UpdateResult<QSharedPointer<QTemporaryFile>> part =
        store.createDownload(candidate.flatpak.size);
    QVERIFY(part.ok);
    QCOMPARE(part.value->write(payload), qint64(payload.size()));
    const UpdateResult<UpdateFileStore::OpenVerifiedFile> verified =
        store.finalizeAndVerify(part.value, candidate);
    QVERIFY(verified.ok);

    PendingUpdateRecord record;
    record.canonicalPath = verified.value.canonicalPath;
    record.candidate = candidate;
    record.verifiedSize = verified.value.size;
    record.verifiedSha256 = verified.value.sha256;
    verified.value.file->close();
    record.candidate.flatpak.downloadUrl = QUrl(
        QStringLiteral("https://objects.githubusercontent.com/")
        + QString(70 * 1024, QLatin1Char('a')));
    QVERIFY(RollingUpdateParser::validateCandidate(record.candidate).ok);
    QVERIFY(!store.save(record));
    QVERIFY(!QFileInfo::exists(
        privateData + QStringLiteral("/pending-update.json")));
}

void AutoUpdateTest::pendingUpdatePreservesTransientIoFailure()
{
    QTemporaryDir root;
    QVERIFY(root.isValid());
    const QString downloads = root.path() + QStringLiteral("/Downloads");
    const QString privateData = root.path() + QStringLiteral("/private");
    QVERIFY(QDir().mkpath(downloads));
    QVERIFY(QDir().mkpath(privateData));

    const QByteArray payload = rollingPayload();
    const RollingUpdateCandidate candidate =
        boundCandidateForPayload(payload);
    FakeStorageProbe probe;
    probe.available = candidate.flatpak.size
        + PendingUpdateStore::safetyMarginBytes();
    PendingUpdateStore writer(downloads, privateData, &probe);
    const UpdateResult<PendingUpdateRecord> absent = writer.load();
    QVERIFY(!absent.ok);
    QCOMPARE(absent.error, UpdateError::InvalidMetadata);

    const UpdateResult<QSharedPointer<QTemporaryFile>> part =
        writer.createDownload(candidate.flatpak.size);
    QVERIFY(part.ok);
    QCOMPARE(part.value->write(payload), qint64(payload.size()));
    const UpdateResult<UpdateFileStore::OpenVerifiedFile> verified =
        writer.finalizeAndVerify(part.value, candidate);
    QVERIFY(verified.ok);

    PendingUpdateRecord record;
    record.canonicalPath = verified.value.canonicalPath;
    record.candidate = candidate;
    record.verifiedSize = verified.value.size;
    record.verifiedSha256 = verified.value.sha256;
    verified.value.file->close();
    QVERIFY(writer.save(record));
    const QString recordPath =
        privateData + QStringLiteral("/pending-update.json");
    QFile recordFile(recordPath);
    QVERIFY(recordFile.open(QIODevice::ReadOnly));
    const QByteArray document = recordFile.readAll();
    recordFile.close();

    ScriptedPendingRecordReader unsafeReader;
    UpdateResult<QByteArray> unsafe;
    unsafe.error = UpdateError::UnsafePath;
    unsafe.message = QStringLiteral("unsafe record fixture");
    unsafeReader.results.append(unsafe);
    PendingUpdateStore unsafeStore(
        downloads, privateData, &probe, &unsafeReader);
    const UpdateResult<PendingUpdateRecord> unsafeLoad = unsafeStore.load();
    QVERIFY(!unsafeLoad.ok);
    QCOMPARE(unsafeLoad.error, UpdateError::UnsafePath);
    QVERIFY(QFileInfo::exists(recordPath));

    ScriptedPendingRecordReader reader;
    UpdateResult<QByteArray> unavailable;
    unavailable.error = UpdateError::IoFailure;
    unavailable.message = QStringLiteral("record storage temporarily unavailable");
    reader.results.append(unavailable);
    UpdateResult<QByteArray> recovered;
    recovered.ok = true;
    recovered.value = document;
    reader.results.append(recovered);

    PendingUpdateStore store(downloads, privateData, &probe, &reader);
    FakeNetworkAccessManager network;
    FakeSessionModeProvider session;
    enqueueUnchangedRefetch(&network, manifestForPayload(payload));
    AutoUpdateChecker checker(&network, &store, &session);
    checker.start();

    QCOMPARE(checker.state(), AutoUpdateChecker::RestoreError);
    QCOMPARE(reader.calls, 1);
    QCOMPARE(network.requests.size(), 0);
    QVERIFY(checker.availableBuild().isEmpty());
    QVERIFY(QFileInfo::exists(recordPath));
    QVERIFY(QFileInfo::exists(record.canonicalPath));

    checker.retry();
    QTRY_COMPARE(checker.state(), AutoUpdateChecker::ReadyToHandOff);
    QCOMPARE(reader.calls, 2);
    QCOMPARE(network.requests.size(), 3);
    QVERIFY(RollingUpdateParser::matchesCandidate(
        record.candidate, candidate).ok);
    QVERIFY(!checker.downloadedPath().isEmpty());
}

void AutoUpdateTest::rollingParser()
{
    const UpdateResult<RollingUpdateCandidate> release = RollingUpdateParser::parseRelease(validGitHubReleaseJson());
    QVERIFY(release.ok);
    const UpdateResult<RollingUpdateCandidate> candidate =
        RollingUpdateParser::parseManifest(validManifestJson(), release.value);
    QVERIFY(candidate.ok);
    QCOMPARE(candidate.value.sequence, quint64(5678));
    QVERIFY(!RollingUpdateParser::parseRelease(replaceOnce(
        validReleaseJson(), "releases/assets/11223", "issues/1")).ok);
}

void AutoUpdateTest::steamDeckSession()
{
    QProcessEnvironment gaming;
    gaming.insert(QStringLiteral("GAMESCOPE_WAYLAND_DISPLAY"), QStringLiteral("gamescope-0"));
    QCOMPARE(SteamDeckSession::classify(gaming), SteamDeckSession::Gaming);

    QProcessEnvironment desktop;
    desktop.insert(QStringLiteral("XDG_CURRENT_DESKTOP"), QStringLiteral("KDE"));
    desktop.insert(QStringLiteral("KDE_FULL_SESSION"), QStringLiteral("true"));
    desktop.insert(QStringLiteral("XDG_SESSION_TYPE"), QStringLiteral("wayland"));
    QCOMPARE(SteamDeckSession::classify(desktop), SteamDeckSession::Desktop);

    desktop.insert(QStringLiteral("XDG_CURRENT_DESKTOP"), QStringLiteral("fakekde"));
    QCOMPARE(SteamDeckSession::classify(desktop), SteamDeckSession::Unknown);
}

void AutoUpdateTest::stateMachine()
{
    typedef AutoUpdateChecker::State S;
    typedef UpdateStateMachine::Event E;
    struct Transition {
        S from;
        E event;
        S to;
    };
    const QList<Transition> allowed{
        {S::Idle, E::BeginRestore, S::RestoringPending},
        {S::Idle, E::BeginCheck, S::Checking},
        {S::RestoringPending, E::VerificationPassedDesktop, S::ReadyToHandOff},
        {S::RestoringPending, E::VerificationPassedNonDesktop, S::ReadyForDesktop},
        {S::RestoringPending, E::CandidateCurrent, S::NoUpdate},
        {S::RestoringPending, E::BeginCheck, S::Checking},
        {S::RestoringPending, E::RestoreFailed, S::RestoreError},
        {S::RestoringPending, E::Cancel, S::Cancelled},
        {S::Checking, E::CandidateCurrent, S::NoUpdate},
        {S::Checking, E::CandidateAvailable, S::Available},
        {S::Checking, E::CheckFailed, S::CheckError},
        {S::Checking, E::Cancel, S::Cancelled},
        {S::NoUpdate, E::BeginCheck, S::Checking},
        {S::Available, E::BeginDownload, S::Downloading},
        {S::Available, E::BeginCheck, S::Checking},
        {S::Available, E::Cancel, S::Cancelled},
        {S::Downloading, E::DownloadComplete, S::Verifying},
        {S::Downloading, E::DownloadFailed, S::DownloadError},
        {S::Downloading, E::Cancel, S::Cancelled},
        {S::Verifying, E::VerificationPassedDesktop, S::ReadyToHandOff},
        {S::Verifying, E::VerificationPassedNonDesktop, S::ReadyForDesktop},
        {S::Verifying, E::VerificationFailed, S::VerificationError},
        {S::Verifying, E::Cancel, S::Cancelled},
        {S::ReadyForDesktop, E::Retry, S::Verifying},
        {S::ReadyForDesktop, E::BeginCheck, S::Checking},
        {S::ReadyForDesktop, E::Cancel, S::Cancelled},
        {S::ReadyToHandOff, E::Retry, S::Verifying},
        {S::ReadyToHandOff, E::BeginCheck, S::Checking},
        {S::ReadyToHandOff, E::Cancel, S::Cancelled},
        {S::CheckError, E::Retry, S::Checking},
        {S::CheckError, E::BeginCheck, S::Checking},
        {S::DownloadError, E::Retry, S::Available},
        {S::DownloadError, E::BeginCheck, S::Checking},
        {S::VerificationError, E::Retry, S::Available},
        {S::VerificationError, E::BeginCheck, S::Checking},
        {S::RestoreError, E::Retry, S::RestoringPending},
        {S::RestoreError, E::BeginCheck, S::Checking},
        {S::RestoreError, E::Cancel, S::Cancelled},
        {S::Cancelled, E::BeginCheck, S::Checking}
    };
    for (const Transition &transition : allowed) {
        const UpdateResult<S> result =
            UpdateStateMachine::reduce(transition.from, transition.event);
        QVERIFY2(result.ok, qPrintable(result.message));
        QCOMPARE(result.value, transition.to);
    }

    QVERIFY(!UpdateStateMachine::reduce(S::Checking, E::BeginCheck).ok);
    QVERIFY(!UpdateStateMachine::reduce(S::Downloading, E::BeginDownload).ok);
    QVERIFY(!UpdateStateMachine::reduce(S::Idle, E::BeginDownload).ok);
    QVERIFY(!UpdateStateMachine::reduce(S::ReadyForDesktop,
                                        E::VerificationPassedDesktop).ok);

    const QMetaObject &meta = AutoUpdateChecker::staticMetaObject;
    QVERIFY(meta.indexOfEnumerator("State") >= 0);
    QVERIFY(meta.indexOfProperty("state") >= 0);
    QVERIFY(meta.indexOfProperty("currentBuild") >= 0);
    QVERIFY(meta.indexOfProperty("availableBuild") >= 0);
    QVERIFY(meta.indexOfProperty("releaseUrl") >= 0);
    QVERIFY(meta.indexOfProperty("downloadedPath") >= 0);
    QVERIFY(meta.indexOfProperty("bytesReceived") >= 0);
    QVERIFY(meta.indexOfProperty("bytesTotal") >= 0);
    QVERIFY(meta.indexOfProperty("errorMessage") >= 0);
    QVERIFY(meta.indexOfProperty("rollingInstallSupported") >= 0);

}

void AutoUpdateTest::boundedNetwork()
{
    QVERIFY(AutoUpdateChecker::isApprovedRedirect(
        QUrl(QStringLiteral("https://api.github.com/repos/samelamin/vibertemis/releases/tags/steam-deck-latest"))));
    QVERIFY(AutoUpdateChecker::isApprovedRedirect(
        QUrl(QStringLiteral("https://objects.githubusercontent.com/github-production-release-asset/file"))));
    QVERIFY(AutoUpdateChecker::isApprovedRedirect(
        QUrl(QStringLiteral("https://release-assets.githubusercontent.com/file"
                            "?sp=r&sig=expiring-signature"))));
    QVERIFY(!AutoUpdateChecker::isApprovedRedirect(
        QUrl(QStringLiteral("https://example.com/update"))));
    QVERIFY(!AutoUpdateChecker::isApprovedRedirect(
        QUrl(QStringLiteral("https://api.github.com/repos/samelamin/vibertemis"
                            "?token=surprise"))));
    QCOMPARE(AutoUpdateChecker::maximumRedirects(), 5);
    QCOMPARE(AutoUpdateChecker::jsonResponseLimit(), qint64(1024 * 1024));
    QCOMPARE(AutoUpdateChecker::manifestResponseLimit(), qint64(64 * 1024));

    const QByteArray payload = rollingPayload();
    const QByteArray manifest = manifestForPayload(payload);
    const RollingUpdateCandidate candidate =
        boundCandidateForPayload(payload);

    // The complete rolling flow keeps one manager alive across check,
    // download, verification, and the post-hash identity refetch.
    FakeNetworkAccessManager network;
    FakeUpdateFileStore files;
    FakeSessionModeProvider desktop;
    files.loadResult.error = UpdateError::InvalidMetadata;
    enqueueAvailableCheck(&network, manifest);
    AutoUpdateChecker checker(&network, &files, &desktop);
    QSignalSpy candidateSignal(&checker, &AutoUpdateChecker::candidateChanged);
    checker.start();
    QTRY_COMPARE(checker.state(), AutoUpdateChecker::Available);
    QCOMPARE(candidateSignal.count(), 1);
    QCOMPARE(network.requests.size(), 4);
    QCOMPARE(checker.availableBuild(), RollingCommit.left(12));
    QCOMPARE(files.cleanupCalls, 1);
    QCOMPARE(files.loadCalls, 1);
    QCOMPARE(files.clearCalls, 1);
    for (const QNetworkRequest &request : network.requests) {
        QVERIFY(request.url().scheme() == QStringLiteral("https"));
        QVERIFY(request.rawHeader(QByteArrayLiteral("Authorization")).isEmpty());
        QVERIFY(request.rawHeader(QByteArrayLiteral("Cookie")).isEmpty());
    }

    NetworkScript assetRedirect =
        networkScript(QByteArrayLiteral("redirect body must be ignored"), 302);
    assetRedirect.redirect = QUrl(
        QStringLiteral("https://release-assets.githubusercontent.com/file"
                       "?sp=r&sig=expiring-signature"));
    network.enqueue(assetRedirect);
    network.enqueue(networkScript(payload));
    enqueueUnchangedRefetch(&network, manifest);
    checker.downloadUpdate();
    QTRY_COMPARE(checker.state(), AutoUpdateChecker::ReadyToHandOff);
    QCOMPARE(checker.bytesReceived(), qint64(payload.size()));
    QCOMPARE(checker.bytesTotal(), qint64(payload.size()));
    QCOMPARE(files.finalizeCalls, 1);
    QCOMPARE(files.saveCalls, 1);
    QCOMPARE(files.saved.candidate.sourceCommit, RollingCommit);
    QCOMPARE(files.saved.candidate.sequence, quint64(5678));
    QCOMPARE(files.saved.candidate.flatpak.sha256,
             QCryptographicHash::hash(payload, QCryptographicHash::Sha256).toHex());
    QVERIFY(!checker.downloadedPath().isEmpty());
    QCOMPARE(network.scripts.size(), 0);
    NetworkScript recheckStall = networkScript(QByteArray());
    recheckStall.stall = true;
    network.enqueue(recheckStall);
    checker.checkNow();
    QCOMPARE(checker.state(), AutoUpdateChecker::Checking);
    QCOMPARE(candidateSignal.count(), 2);
    QVERIFY(checker.availableBuild().isEmpty());
    QVERIFY(checker.releaseUrl().isEmpty());
    checker.cancel();

    FakeNetworkAccessManager noUpdateNetwork;
    FakeUpdateFileStore noUpdateFiles;
    noUpdateFiles.loadResult.error = UpdateError::InvalidMetadata;
    noUpdateNetwork.enqueue(networkScript(validGitHubReleaseJson()));
    noUpdateNetwork.enqueue(networkScript(manifest));
    noUpdateNetwork.enqueue(networkScript(lightweightTagJson()));
    noUpdateNetwork.enqueue(networkScript(
        QByteArrayLiteral("{\"status\":\"identical\"}")));
    AutoUpdateChecker noUpdateChecker(
        &noUpdateNetwork, &noUpdateFiles, &desktop);
    QSignalSpy noUpdateCandidateSignal(
        &noUpdateChecker, &AutoUpdateChecker::candidateChanged);
    noUpdateChecker.start();
    QTRY_COMPARE(noUpdateChecker.state(), AutoUpdateChecker::NoUpdate);
    QCOMPARE(noUpdateCandidateSignal.count(), 2);
    QVERIFY(noUpdateChecker.availableBuild().isEmpty());
    QVERIFY(noUpdateChecker.releaseUrl().isEmpty());

    // Non-2xx and rate-limited API responses are typed check errors and
    // never reflect request credentials into the user-facing message.
    FakeNetworkAccessManager rateNetwork;
    FakeUpdateFileStore rateFiles;
    rateFiles.loadResult.error = UpdateError::InvalidMetadata;
    NetworkScript rate = networkScript(QByteArrayLiteral("token=secret"), 403);
    rate.error = QNetworkReply::ContentAccessDenied;
    rate.headers.append(qMakePair(
        QByteArrayLiteral("X-RateLimit-Remaining"), QByteArrayLiteral("0")));
    rate.headers.append(qMakePair(
        QByteArrayLiteral("X-RateLimit-Reset"), QByteArrayLiteral("2000000000")));
    rateNetwork.enqueue(rate);
    AutoUpdateChecker rateChecker(&rateNetwork, &rateFiles, &desktop);
    rateChecker.start();
    QTRY_COMPARE(rateChecker.state(), AutoUpdateChecker::CheckError);
    QVERIFY(rateChecker.errorMessage().contains(QStringLiteral("rate limit"),
                                                 Qt::CaseInsensitive));
    QVERIFY(!rateChecker.errorMessage().contains(QStringLiteral("secret")));

    FakeNetworkAccessManager statusNetwork;
    FakeUpdateFileStore statusFiles;
    statusFiles.loadResult.error = UpdateError::InvalidMetadata;
    statusNetwork.enqueue(networkScript(QByteArrayLiteral("{}"), 304));
    AutoUpdateChecker statusChecker(&statusNetwork, &statusFiles, &desktop);
    statusChecker.start();
    QTRY_COMPARE(statusChecker.state(), AutoUpdateChecker::CheckError);
    QVERIFY(statusChecker.errorMessage().contains(QStringLiteral("HTTP 304")));

    // JSON is capped before parsing.
    FakeNetworkAccessManager cappedNetwork;
    FakeUpdateFileStore cappedFiles;
    cappedFiles.loadResult.error = UpdateError::InvalidMetadata;
    cappedNetwork.enqueue(networkScript(
        QByteArray(AutoUpdateChecker::jsonResponseLimit() + 1, 'x')));
    AutoUpdateChecker cappedChecker(&cappedNetwork, &cappedFiles, &desktop);
    cappedChecker.start();
    QTRY_COMPARE(cappedChecker.state(), AutoUpdateChecker::CheckError);
    QVERIFY(cappedChecker.errorMessage().contains(QStringLiteral("too large")));

    FakeNetworkAccessManager manifestCapNetwork;
    FakeUpdateFileStore manifestCapFiles;
    manifestCapFiles.loadResult.error = UpdateError::InvalidMetadata;
    manifestCapNetwork.enqueue(networkScript(validGitHubReleaseJson()));
    manifestCapNetwork.enqueue(networkScript(
        QByteArray(AutoUpdateChecker::manifestResponseLimit() + 1, 'x')));
    AutoUpdateChecker manifestCapChecker(
        &manifestCapNetwork, &manifestCapFiles, &desktop);
    manifestCapChecker.start();
    QTRY_COMPARE(manifestCapChecker.state(), AutoUpdateChecker::CheckError);
    QVERIFY(manifestCapChecker.errorMessage().contains(
        QStringLiteral("too large")));

    // Unknown or malformed ancestry is not proof that there is no update.
    FakeNetworkAccessManager unknownCompareNetwork;
    FakeUpdateFileStore unknownCompareFiles;
    unknownCompareFiles.loadResult.error = UpdateError::InvalidMetadata;
    unknownCompareNetwork.enqueue(networkScript(validGitHubReleaseJson()));
    unknownCompareNetwork.enqueue(networkScript(manifest));
    unknownCompareNetwork.enqueue(networkScript(lightweightTagJson()));
    unknownCompareNetwork.enqueue(networkScript(
        QByteArrayLiteral("{\"status\":\"mystery\"}")));
    AutoUpdateChecker unknownCompareChecker(
        &unknownCompareNetwork, &unknownCompareFiles, &desktop);
    QSignalSpy unknownCandidateSignal(
        &unknownCompareChecker, &AutoUpdateChecker::candidateChanged);
    unknownCompareChecker.start();
    QTRY_COMPARE(unknownCompareChecker.state(),
                 AutoUpdateChecker::CheckError);
    QVERIFY(unknownCompareChecker.errorMessage().contains(
        QStringLiteral("ancestry"), Qt::CaseInsensitive));
    QVERIFY(unknownCompareChecker.availableBuild().isEmpty());
    QVERIFY(unknownCompareChecker.releaseUrl().isEmpty());
    QCOMPARE(unknownCandidateSignal.count(), 2);

    FakeNetworkAccessManager malformedCompareNetwork;
    FakeUpdateFileStore malformedCompareFiles;
    malformedCompareFiles.loadResult.error = UpdateError::InvalidMetadata;
    malformedCompareNetwork.enqueue(networkScript(validGitHubReleaseJson()));
    malformedCompareNetwork.enqueue(networkScript(manifest));
    malformedCompareNetwork.enqueue(networkScript(lightweightTagJson()));
    malformedCompareNetwork.enqueue(networkScript(
        QByteArrayLiteral("{not-json")));
    AutoUpdateChecker malformedCompareChecker(
        &malformedCompareNetwork, &malformedCompareFiles, &desktop);
    malformedCompareChecker.start();
    QTRY_COMPARE(malformedCompareChecker.state(),
                 AutoUpdateChecker::CheckError);
    QVERIFY(malformedCompareChecker.errorMessage().contains(
        QStringLiteral("ancestry"), Qt::CaseInsensitive));

    // Every redirect is revalidated and the sixth hop is rejected.
    FakeNetworkAccessManager redirectNetwork;
    FakeUpdateFileStore redirectFiles;
    redirectFiles.loadResult.error = UpdateError::InvalidMetadata;
    for (int index = 0; index < 6; ++index) {
        NetworkScript redirect = networkScript(QByteArray(), 302);
        redirect.redirect = QUrl(
            QStringLiteral("https://api.github.com/repos/samelamin/vibertemis/"
                           "releases/tags/steam-deck-latest"));
        redirectNetwork.enqueue(redirect);
    }
    AutoUpdateChecker redirectChecker(
        &redirectNetwork, &redirectFiles, &desktop);
    redirectChecker.start();
    QTRY_COMPARE(redirectChecker.state(), AutoUpdateChecker::CheckError);
    QCOMPARE(redirectNetwork.requests.size(), 6);
    QVERIFY(redirectChecker.errorMessage().contains(
        QStringLiteral("redirect"), Qt::CaseInsensitive));

    FakeNetworkAccessManager evilRedirectNetwork;
    FakeUpdateFileStore evilRedirectFiles;
    evilRedirectFiles.loadResult.error = UpdateError::InvalidMetadata;
    NetworkScript evilRedirect = networkScript(QByteArray(), 302);
    evilRedirect.redirect =
        QUrl(QStringLiteral("https://evil.example/update"));
    evilRedirectNetwork.enqueue(evilRedirect);
    AutoUpdateChecker evilRedirectChecker(
        &evilRedirectNetwork, &evilRedirectFiles, &desktop);
    evilRedirectChecker.start();
    QTRY_COMPARE(evilRedirectChecker.state(),
                 AutoUpdateChecker::CheckError);
    QCOMPARE(evilRedirectNetwork.requests.size(), 1);

    // Redirect requests are rebuilt from an allow-list, so credentials are
    // absent even when the approved target has a different origin.
    FakeNetworkAccessManager crossOriginNetwork;
    FakeUpdateFileStore crossOriginFiles;
    crossOriginFiles.loadResult.error = UpdateError::InvalidMetadata;
    NetworkScript crossOrigin = networkScript(QByteArray(), 302);
    crossOrigin.redirect = QUrl(
        QStringLiteral("https://objects.githubusercontent.com/release-object"));
    crossOriginNetwork.enqueue(crossOrigin);
    crossOriginNetwork.enqueue(networkScript(QByteArrayLiteral("{}")));
    AutoUpdateChecker crossOriginChecker(
        &crossOriginNetwork, &crossOriginFiles, &desktop);
    crossOriginChecker.start();
    QTRY_COMPARE(crossOriginChecker.state(), AutoUpdateChecker::CheckError);
    QCOMPARE(crossOriginNetwork.requests.size(), 2);
    QVERIFY(crossOriginNetwork.requests.at(1)
                .rawHeader(QByteArrayLiteral("Authorization")).isEmpty());
    QVERIFY(crossOriginNetwork.requests.at(1)
                .rawHeader(QByteArrayLiteral("Cookie")).isEmpty());

    // A stalled request can be timed out without waiting in the test. The
    // active reply is aborted and the error remains retryable.
    FakeNetworkAccessManager timeoutNetwork;
    FakeUpdateFileStore timeoutFiles;
    timeoutFiles.loadResult.error = UpdateError::InvalidMetadata;
    NetworkScript stalled = networkScript(QByteArray());
    stalled.stall = true;
    timeoutNetwork.enqueue(stalled);
    AutoUpdateChecker timeoutChecker(
        &timeoutNetwork, &timeoutFiles, &desktop);
    timeoutChecker.start();
    QCOMPARE(timeoutChecker.state(), AutoUpdateChecker::Checking);
    QVERIFY(QMetaObject::invokeMethod(
        &timeoutChecker, "handleConnectTimeout", Qt::DirectConnection));
    QCOMPARE(timeoutChecker.state(), AutoUpdateChecker::CheckError);
    QVERIFY(timeoutChecker.errorMessage().contains(
        QStringLiteral("Timed out")));
    QVERIFY(timeoutNetwork.replies.constLast()->aborted);

    FakeNetworkAccessManager idleNetwork;
    FakeUpdateFileStore idleFiles;
    idleFiles.loadResult.error = UpdateError::InvalidMetadata;
    idleNetwork.enqueue(stalled);
    AutoUpdateChecker idleChecker(&idleNetwork, &idleFiles, &desktop);
    idleChecker.start();
    QVERIFY(QMetaObject::invokeMethod(
        &idleChecker, "handleIdleTimeout", Qt::DirectConnection));
    QCOMPARE(idleChecker.state(), AutoUpdateChecker::CheckError);
    QVERIFY(idleChecker.errorMessage().contains(
        QStringLiteral("stopped responding")));
    QVERIFY(idleNetwork.replies.constLast()->aborted);

    FakeNetworkAccessManager overallNetwork;
    FakeUpdateFileStore overallFiles;
    overallFiles.loadResult.error = UpdateError::InvalidMetadata;
    overallNetwork.enqueue(stalled);
    AutoUpdateChecker overallChecker(
        &overallNetwork, &overallFiles, &desktop);
    overallChecker.start();
    QVERIFY(QMetaObject::invokeMethod(
        &overallChecker, "handleOverallTimeout", Qt::DirectConnection));
    QCOMPARE(overallChecker.state(), AutoUpdateChecker::CheckError);
    QVERIFY(overallChecker.errorMessage().contains(
        QStringLiteral("timed out")));
    QVERIFY(overallNetwork.replies.constLast()->aborted);

    // Cancellation aborts an in-flight download and releases the randomized
    // partial file.
    FakeNetworkAccessManager cancelNetwork;
    FakeUpdateFileStore cancelFiles;
    cancelFiles.loadResult.error = UpdateError::InvalidMetadata;
    enqueueAvailableCheck(&cancelNetwork, manifest);
    AutoUpdateChecker cancelChecker(
        &cancelNetwork, &cancelFiles, &desktop);
    cancelChecker.start();
    QTRY_COMPARE(cancelChecker.state(), AutoUpdateChecker::Available);
    NetworkScript stalledAsset = networkScript(QByteArray());
    stalledAsset.stall = true;
    cancelNetwork.enqueue(stalledAsset);
    cancelChecker.downloadUpdate();
    QCOMPARE(cancelChecker.state(), AutoUpdateChecker::Downloading);
    const QString partialPath = cancelFiles.lastTemporaryPath;
    QVERIFY(QFileInfo::exists(partialPath));
    cancelChecker.cancel();
    QCOMPARE(cancelChecker.state(), AutoUpdateChecker::Cancelled);
    QVERIFY(cancelNetwork.replies.constLast()->aborted);
    QTRY_VERIFY(!QFileInfo::exists(partialPath));

    // More or fewer bytes than the manifest binds never reaches hashing or
    // handoff.
    FakeNetworkAccessManager sizeNetwork;
    FakeUpdateFileStore sizeFiles;
    sizeFiles.loadResult.error = UpdateError::InvalidMetadata;
    enqueueAvailableCheck(&sizeNetwork, manifest);
    AutoUpdateChecker sizeChecker(&sizeNetwork, &sizeFiles, &desktop);
    sizeChecker.start();
    QTRY_COMPARE(sizeChecker.state(), AutoUpdateChecker::Available);
    sizeNetwork.enqueue(networkScript(payload + QByteArrayLiteral("x")));
    sizeChecker.downloadUpdate();
    QTRY_COMPARE(sizeChecker.state(), AutoUpdateChecker::DownloadError);
    QCOMPARE(sizeFiles.finalizeCalls, 0);

    FakeNetworkAccessManager shortNetwork;
    FakeUpdateFileStore shortFiles;
    shortFiles.loadResult.error = UpdateError::InvalidMetadata;
    enqueueAvailableCheck(&shortNetwork, manifest);
    AutoUpdateChecker shortChecker(&shortNetwork, &shortFiles, &desktop);
    shortChecker.start();
    QTRY_COMPARE(shortChecker.state(), AutoUpdateChecker::Available);
    shortNetwork.enqueue(networkScript(payload.left(payload.size() - 1)));
    shortChecker.downloadUpdate();
    QTRY_COMPARE(shortChecker.state(), AutoUpdateChecker::DownloadError);
    QCOMPARE(shortFiles.finalizeCalls, 0);

    // Digest verification is maintained incrementally by the service, before
    // the secure file store performs its independent defensive rehash.
    const QByteArray wrongDigestManifest = replaceOnce(
        manifest,
        QCryptographicHash::hash(
            payload, QCryptographicHash::Sha256).toHex(),
        QByteArray(64, 'f'));
    FakeNetworkAccessManager digestNetwork;
    FakeUpdateFileStore digestFiles;
    digestFiles.loadResult.error = UpdateError::InvalidMetadata;
    enqueueAvailableCheck(&digestNetwork, wrongDigestManifest);
    AutoUpdateChecker digestChecker(
        &digestNetwork, &digestFiles, &desktop);
    digestChecker.start();
    QTRY_COMPARE(digestChecker.state(), AutoUpdateChecker::Available);
    NetworkScript chunkedPayload = networkScript(payload);
    chunkedPayload.chunkSize = 64 * 1024;
    digestNetwork.enqueue(chunkedPayload);
    QSignalSpy digestProgress(
        &digestChecker, &AutoUpdateChecker::progressChanged);
    digestChecker.downloadUpdate();
    QTRY_COMPARE(digestChecker.state(),
                 AutoUpdateChecker::VerificationError);
    QVERIFY(digestProgress.count() > 2);
    QCOMPARE(digestFiles.finalizeCalls, 0);
    QVERIFY(digestChecker.errorMessage().contains(
        QStringLiteral("checksum"), Qt::CaseInsensitive));

    // Post-hash release mutation is publisher-in-progress, remains
    // retryable, and cannot retain a ready state.
    FakeNetworkAccessManager changedNetwork;
    FakeUpdateFileStore changedFiles;
    changedFiles.loadResult.error = UpdateError::InvalidMetadata;
    enqueueAvailableCheck(&changedNetwork, manifest);
    AutoUpdateChecker changedChecker(
        &changedNetwork, &changedFiles, &desktop);
    changedChecker.start();
    QTRY_COMPARE(changedChecker.state(), AutoUpdateChecker::Available);
    changedNetwork.enqueue(networkScript(payload));
    changedNetwork.enqueue(networkScript(replaceOnce(
        validGitHubReleaseJson(), "\"id\":24680", "\"id\":24681")));
    changedChecker.downloadUpdate();
    QTRY_COMPARE(changedChecker.state(), AutoUpdateChecker::VerificationError);
    QVERIFY(changedChecker.errorMessage().contains(
        QStringLiteral("changed"), Qt::CaseInsensitive));
    QCOMPARE(changedFiles.saveCalls, 0);
    changedChecker.retry();
    QCOMPARE(changedChecker.state(), AutoUpdateChecker::Checking);

    const auto mutatedAssetRelease = [](int assetIndex,
                                        const QString &field,
                                        const QJsonValue &value) {
        QJsonObject release =
            QJsonDocument::fromJson(validGitHubReleaseJson()).object();
        QJsonArray assets = release.value(QStringLiteral("assets")).toArray();
        QJsonObject asset = assets.at(assetIndex).toObject();
        asset.insert(field, value);
        assets.replace(assetIndex, asset);
        release.insert(QStringLiteral("assets"), assets);
        return QJsonDocument(release).toJson(QJsonDocument::Compact);
    };
    const auto expectPublisherRace =
        [&](const QByteArray &releaseRefetch,
            const QByteArray &manifestRefetch,
            const QByteArray &tagRefetch) {
        FakeNetworkAccessManager raceNetwork;
        FakeUpdateFileStore raceFiles;
        raceFiles.loadResult.error = UpdateError::InvalidMetadata;
        enqueueAvailableCheck(&raceNetwork, manifest);
        AutoUpdateChecker raceChecker(
            &raceNetwork, &raceFiles, &desktop);
        raceChecker.start();
        QTRY_COMPARE(raceChecker.state(), AutoUpdateChecker::Available);
        raceNetwork.enqueue(networkScript(payload));
        raceNetwork.enqueue(networkScript(releaseRefetch));
        if (!manifestRefetch.isNull()) {
            raceNetwork.enqueue(networkScript(manifestRefetch));
        }
        if (!tagRefetch.isNull()) {
            raceNetwork.enqueue(networkScript(tagRefetch));
        }
        raceChecker.downloadUpdate();
        QTRY_COMPARE(raceChecker.state(),
                     AutoUpdateChecker::VerificationError);
        QCOMPARE(raceFiles.saveCalls, 0);
        QVERIFY(raceChecker.errorMessage().contains(
            QStringLiteral("changed"), Qt::CaseInsensitive));
        raceChecker.retry();
        QCOMPARE(raceChecker.state(), AutoUpdateChecker::Checking);
    };

    QJsonObject changedReleaseId =
        QJsonDocument::fromJson(validGitHubReleaseJson()).object();
    changedReleaseId.insert(QStringLiteral("id"), 24681);
    QJsonObject changedReleaseTime =
        QJsonDocument::fromJson(validGitHubReleaseJson()).object();
    changedReleaseTime.insert(
        QStringLiteral("updated_at"),
        QStringLiteral("2026-07-24T10:00:00Z"));
    const QList<QByteArray> releaseBindingMutations{
        QJsonDocument(changedReleaseId).toJson(QJsonDocument::Compact),
        QJsonDocument(changedReleaseTime).toJson(QJsonDocument::Compact),
        mutatedAssetRelease(0, QStringLiteral("id"), 11224),
        mutatedAssetRelease(0, QStringLiteral("name"),
                            QStringLiteral("changed-update.json")),
        mutatedAssetRelease(0, QStringLiteral("size"), 513),
        mutatedAssetRelease(0, QStringLiteral("updated_at"),
                            QStringLiteral("2026-07-24T10:00:00Z")),
        mutatedAssetRelease(1, QStringLiteral("id"), 13580),
        mutatedAssetRelease(1, QStringLiteral("name"),
                            QStringLiteral("changed.flatpak")),
        mutatedAssetRelease(1, QStringLiteral("size"), 1048577),
        mutatedAssetRelease(1, QStringLiteral("updated_at"),
                            QStringLiteral("2026-07-24T10:00:00Z"))
    };
    for (const QByteArray &changedRelease : releaseBindingMutations) {
        expectPublisherRace(changedRelease, QByteArray(), QByteArray());
    }

    const QList<QByteArray> manifestBindingMutations{
        replaceOnce(manifest, "\"schema\":1", "\"schema\":2"),
        replaceOnce(manifest, "\"published_at\":\"2026-07-23T10:00:00Z\"",
                    "\"published_at\":\"2026-07-24T10:00:00Z\""),
        replaceOnce(manifest, RollingCommit.toLatin1(),
                    QByteArray(40, 'f')),
        replaceOnce(manifest, "\"5678\"", "\"5679\""),
        replaceOnce(manifest, "\"13579\"", "\"13580\""),
        replaceOnce(manifest, "artemis-steam-deck.flatpak",
                    "changed.flatpak"),
        replaceOnce(manifest, "\"1048576\"", "\"1048577\""),
        replaceOnce(
            manifest,
            QCryptographicHash::hash(
                payload, QCryptographicHash::Sha256).toHex(),
            QByteArray(64, 'f'))
    };
    for (const QByteArray &changedManifest : manifestBindingMutations) {
        expectPublisherRace(validGitHubReleaseJson(),
                            changedManifest, QByteArray());
    }
    expectPublisherRace(
        validGitHubReleaseJson(), manifest,
        QStringLiteral("{\"object\":{\"type\":\"commit\",\"sha\":\"%1\"}}")
            .arg(QString(40, QLatin1Char('f'))).toUtf8());

    FakeNetworkAccessManager annotatedNetwork;
    FakeUpdateFileStore annotatedFiles;
    annotatedFiles.loadResult.error = UpdateError::InvalidMetadata;
    const QByteArray annotatedReference =
        QStringLiteral("{\"object\":{\"type\":\"tag\",\"sha\":\"%1\"}}")
            .arg(TagObject).toUtf8();
    const QByteArray annotatedObject =
        QStringLiteral(
            "{\"sha\":\"%1\",\"object\":{\"type\":\"commit\",\"sha\":\"%2\"}}")
            .arg(TagObject, RollingCommit).toUtf8();
    annotatedNetwork.enqueue(networkScript(validGitHubReleaseJson()));
    annotatedNetwork.enqueue(networkScript(manifest));
    annotatedNetwork.enqueue(networkScript(annotatedReference));
    annotatedNetwork.enqueue(networkScript(annotatedObject));
    annotatedNetwork.enqueue(networkScript(
        QByteArrayLiteral("{\"status\":\"ahead\"}")));
    AutoUpdateChecker annotatedChecker(
        &annotatedNetwork, &annotatedFiles, &desktop);
    annotatedChecker.start();
    QTRY_COMPARE(annotatedChecker.state(), AutoUpdateChecker::Available);
    annotatedNetwork.enqueue(networkScript(payload));
    annotatedNetwork.enqueue(networkScript(validGitHubReleaseJson()));
    annotatedNetwork.enqueue(networkScript(manifest));
    annotatedNetwork.enqueue(networkScript(annotatedReference));
    annotatedNetwork.enqueue(networkScript(replaceOnce(
        annotatedObject, TagObject.toLatin1(), QByteArray(40, 'f'))));
    annotatedChecker.downloadUpdate();
    QTRY_COMPARE(annotatedChecker.state(),
                 AutoUpdateChecker::VerificationError);
    annotatedChecker.retry();
    QCOMPARE(annotatedChecker.state(), AutoUpdateChecker::Checking);

    // Startup restoration refetches every binding before reopening the exact
    // local file. Desktop alone becomes ReadyToHandOff.
    FakeNetworkAccessManager restoreNetwork;
    FakeUpdateFileStore restoreFiles;
    const QString restorePath =
        restoreFiles.root.path() + QStringLiteral("/pending.flatpak");
    QFile restorePayload(restorePath);
    QVERIFY(restorePayload.open(QIODevice::WriteOnly));
    QCOMPARE(restorePayload.write(payload), qint64(payload.size()));
    restorePayload.close();
    PendingUpdateRecord record;
    record.canonicalPath = restorePath;
    record.candidate = candidate;
    record.verifiedSize = candidate.flatpak.size;
    record.verifiedSha256 = candidate.flatpak.sha256;
    restoreFiles.loadResult.ok = true;
    restoreFiles.loadResult.value = record;
    enqueueUnchangedRefetch(&restoreNetwork, manifest);
    AutoUpdateChecker restoreChecker(
        &restoreNetwork, &restoreFiles, &desktop);
    restoreChecker.start();
    QTRY_COMPARE(restoreChecker.state(),
                 AutoUpdateChecker::ReadyToHandOff);
    QCOMPARE(restoreFiles.reopenCalls, 1);

    FakeNetworkAccessManager gamingNetwork;
    FakeUpdateFileStore gamingFiles;
    gamingFiles.loadResult = restoreFiles.loadResult;
    FakeSessionModeProvider gaming;
    gaming.value = SteamDeckSession::Gaming;
    enqueueUnchangedRefetch(&gamingNetwork, manifest);
    AutoUpdateChecker gamingChecker(
        &gamingNetwork, &gamingFiles, &gaming);
    gamingChecker.start();
    QTRY_COMPARE(gamingChecker.state(),
                 AutoUpdateChecker::ReadyForDesktop);

    FakeNetworkAccessManager unknownSessionNetwork;
    FakeUpdateFileStore unknownSessionFiles;
    unknownSessionFiles.loadResult = restoreFiles.loadResult;
    FakeSessionModeProvider unknownSession;
    unknownSession.value = SteamDeckSession::Unknown;
    enqueueUnchangedRefetch(&unknownSessionNetwork, manifest);
    AutoUpdateChecker unknownSessionChecker(
        &unknownSessionNetwork, &unknownSessionFiles, &unknownSession);
    unknownSessionChecker.start();
    QTRY_COMPARE(unknownSessionChecker.state(),
                 AutoUpdateChecker::ReadyForDesktop);

    // Invalid local files and stale bindings are cleared and immediately
    // start a fresh check instead of retrying a record that no longer exists.
    FakeNetworkAccessManager missingNetwork;
    FakeUpdateFileStore missingFiles;
    missingFiles.loadResult = restoreFiles.loadResult;
    missingFiles.reopenError = UpdateError::UnsafePath;
    enqueueUnchangedRefetch(&missingNetwork, manifest);
    enqueueAvailableCheck(&missingNetwork, manifest);
    AutoUpdateChecker missingChecker(
        &missingNetwork, &missingFiles, &desktop);
    missingChecker.start();
    QTRY_COMPARE(missingChecker.state(), AutoUpdateChecker::Available);
    QCOMPARE(missingFiles.reopenCalls, 1);
    QCOMPARE(missingFiles.clearCalls, 1);

    FakeNetworkAccessManager replacedNetwork;
    FakeUpdateFileStore replacedFiles;
    replacedFiles.loadResult = restoreFiles.loadResult;
    replacedFiles.reopenError = UpdateError::SizeMismatch;
    enqueueUnchangedRefetch(&replacedNetwork, manifest);
    enqueueAvailableCheck(&replacedNetwork, manifest);
    AutoUpdateChecker replacedChecker(
        &replacedNetwork, &replacedFiles, &desktop);
    replacedChecker.start();
    QTRY_COMPARE(replacedChecker.state(), AutoUpdateChecker::Available);
    QCOMPARE(replacedFiles.clearCalls, 1);

    FakeNetworkAccessManager staleRestoreNetwork;
    FakeUpdateFileStore staleRestoreFiles;
    staleRestoreFiles.loadResult = restoreFiles.loadResult;
    staleRestoreNetwork.enqueue(networkScript(replaceOnce(
        validGitHubReleaseJson(), "\"id\":24680", "\"id\":24681")));
    enqueueAvailableCheck(&staleRestoreNetwork, manifest);
    AutoUpdateChecker staleRestoreChecker(
        &staleRestoreNetwork, &staleRestoreFiles, &desktop);
    staleRestoreChecker.start();
    QTRY_COMPARE(staleRestoreChecker.state(),
                 AutoUpdateChecker::Available);
    QCOMPARE(staleRestoreFiles.reopenCalls, 0);
    QCOMPARE(staleRestoreFiles.clearCalls, 1);

    // Transient network and filesystem errors preserve the pending record and
    // retry restoration rather than entering a destructive fresh check.
    FakeNetworkAccessManager transientFileNetwork;
    FakeUpdateFileStore transientFileFiles;
    transientFileFiles.loadResult = restoreFiles.loadResult;
    transientFileFiles.reopenError = UpdateError::IoFailure;
    enqueueUnchangedRefetch(&transientFileNetwork, manifest);
    AutoUpdateChecker transientFileChecker(
        &transientFileNetwork, &transientFileFiles, &desktop);
    transientFileChecker.start();
    QTRY_COMPARE(transientFileChecker.state(),
                 AutoUpdateChecker::RestoreError);
    QCOMPARE(transientFileFiles.clearCalls, 0);
    transientFileFiles.reopenError = UpdateError::None;
    enqueueUnchangedRefetch(&transientFileNetwork, manifest);
    transientFileChecker.retry();
    QTRY_COMPARE(transientFileChecker.state(),
                 AutoUpdateChecker::ReadyToHandOff);

    FakeNetworkAccessManager transientNetwork;
    FakeUpdateFileStore transientFiles;
    transientFiles.loadResult = restoreFiles.loadResult;
    NetworkScript transientStall = networkScript(QByteArray());
    transientStall.stall = true;
    transientNetwork.enqueue(transientStall);
    AutoUpdateChecker transientChecker(
        &transientNetwork, &transientFiles, &desktop);
    transientChecker.start();
    QVERIFY(QMetaObject::invokeMethod(
        &transientChecker, "handleOverallTimeout", Qt::DirectConnection));
    QCOMPARE(transientChecker.state(), AutoUpdateChecker::RestoreError);
    QCOMPARE(transientFiles.clearCalls, 0);
    enqueueUnchangedRefetch(&transientNetwork, manifest);
    transientChecker.retry();
    QTRY_COMPARE(transientChecker.state(),
                 AutoUpdateChecker::ReadyToHandOff);

    FakeNetworkAccessManager loadFailureNetwork;
    FakeUpdateFileStore loadFailureFiles;
    loadFailureFiles.loadResult.error = UpdateError::IoFailure;
    loadFailureFiles.loadResult.value = record;
    AutoUpdateChecker loadFailureChecker(
        &loadFailureNetwork, &loadFailureFiles, &desktop);
    loadFailureChecker.start();
    QCOMPARE(loadFailureChecker.state(), AutoUpdateChecker::RestoreError);
    QCOMPARE(loadFailureFiles.clearCalls, 0);
    QCOMPARE(loadFailureNetwork.requests.size(), 0);

    FakeNetworkAccessManager currentNetwork;
    FakeUpdateFileStore currentFiles;
    currentFiles.loadResult = restoreFiles.loadResult;
    currentFiles.loadResult.value.candidate.sourceCommit =
        BuildInfo::commit();
    AutoUpdateChecker currentChecker(
        &currentNetwork, &currentFiles, &desktop);
    currentChecker.start();
    QCOMPARE(currentChecker.state(), AutoUpdateChecker::NoUpdate);
    QCOMPARE(currentFiles.reopenCalls, 0);
    QCOMPARE(currentNetwork.requests.size(), 0);
    QCOMPARE(currentFiles.clearCalls, 1);
}

QTEST_MAIN(AutoUpdateTest)

#include "tst_autoupdate.moc"
