#include <QtTest>

#include <cstring>

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>

#include "backend/autoupdatechecker.h"
#include "backend/buildinfo.h"
#include "backend/releaseversionselector.h"

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
};

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

QTEST_MAIN(AutoUpdateTest)

#include "tst_autoupdate.moc"
