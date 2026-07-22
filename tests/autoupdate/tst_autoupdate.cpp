#include <QtTest>

#include <cstring>

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>

#include "backend/autoupdatechecker.h"
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
};

static QJsonArray releases(const QByteArray &json)
{
    return QJsonDocument::fromJson(json).array();
}

void AutoUpdateTest::rollingReleaseDoesNotMaskVersionedRelease()
{
    const ReleaseVersionSelection selection = ReleaseVersionSelector::select(releases(R"json(
        [
          {"tag_name":"steam-deck-latest","html_url":"https://github.com/samelamin/artemis/releases/tag/steam-deck-latest"},
          {"tag_name":"v6.1.1","html_url":"https://github.com/samelamin/artemis/releases/tag/v6.1.1"}
        ]
    )json"));

    QVERIFY(selection.valid);
    QCOMPARE(selection.version, QStringLiteral("6.1.1"));
    QCOMPARE(selection.url,
             QStringLiteral("https://github.com/samelamin/artemis/releases/tag/v6.1.1"));
}

void AutoUpdateTest::normalFirstReleaseIsSelected()
{
    const ReleaseVersionSelection selection = ReleaseVersionSelector::select(releases(R"json(
        [
          {"tag_name":"6.2.0","html_url":"https://github.com/samelamin/artemis/releases/tag/6.2.0"},
          {"tag_name":"v6.1.1","html_url":"https://github.com/samelamin/artemis/releases/tag/v6.1.1"}
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
        [{"tag_name":"v6.2.0-dev.3","html_url":"https://github.com/samelamin/artemis/releases/tag/v6.2.0-dev.3"}]
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

QTEST_MAIN(AutoUpdateTest)

#include "tst_autoupdate.moc"
