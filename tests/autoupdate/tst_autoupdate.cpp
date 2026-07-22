#include <QtTest>

#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>

#include "backend/releaseversionselector.h"

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

QTEST_MAIN(AutoUpdateTest)

#include "tst_autoupdate.moc"
