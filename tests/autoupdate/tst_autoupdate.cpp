#include <QtTest>

#include <cstring>

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QProcessEnvironment>

#include "backend/autoupdatechecker.h"
#include "backend/buildinfo.h"
#include "backend/releaseversionselector.h"
#include "backend/rollingupdateparser.h"
#include "backend/steamdecksession.h"

class StaticNetworkReply : public QNetworkReply
{
public:
    StaticNetworkReply(const QByteArray &body, QObject *parent) :
        QNetworkReply(parent),
        m_Body(body),
        m_Offset(0)
    {
        open(QIODevice::ReadOnly | QIODevice::Unbuffered);
        setFinished(true);
        setError(QNetworkReply::NoError, QString());
    }

    void abort() override
    {
    }

    qint64 bytesAvailable() const override
    {
        return m_Body.size() - m_Offset + QIODevice::bytesAvailable();
    }

protected:
    qint64 readData(char *data, qint64 maxSize) override
    {
        if (m_Offset >= m_Body.size()) {
            return -1;
        }

        const qint64 bytesToRead = qMin(maxSize, m_Body.size() - m_Offset);
        memcpy(data, m_Body.constData() + m_Offset, static_cast<size_t>(bytesToRead));
        m_Offset += bytesToRead;
        return bytesToRead;
    }

private:
    QByteArray m_Body;
    qint64 m_Offset;
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
    void rollingParser();
    void steamDeckSession();
};

static const QString RollingCommit(40, QLatin1Char('b'));
static const QString TagObject(40, QLatin1Char('d'));
static const QString TagCommit(40, QLatin1Char('e'));
static const QString FlatpakDigest(64, QLatin1Char('c'));

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

    AutoUpdateChecker checker;
    QSignalSpy updateSpy(&checker, &AutoUpdateChecker::onUpdateAvailable);

    QJsonObject release;
    release.insert(QStringLiteral("tag_name"), releaseVersion);
    release.insert(QStringLiteral("html_url"), QStringLiteral("https://example.invalid/release"));
    const QByteArray responseBody = QJsonDocument(QJsonArray{release}).toJson();
    StaticNetworkReply *reply = new StaticNetworkReply(responseBody, &checker);

    QVERIFY(QMetaObject::invokeMethod(
        &checker,
        "handleUpdateCheckRequestFinished",
        Qt::DirectConnection,
        Q_ARG(QNetworkReply *, reply)));

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

QTEST_MAIN(AutoUpdateTest)

#include "tst_autoupdate.moc"
