#include <QtTest>

#include <QJSEngine>
#include <QQmlComponent>
#include <QQmlEngine>
#include <QScopedPointer>
#include <qqml.h>

#include <cmath>
#include <limits>

#include "settings/refreshrateparser.h"

class RefreshRateParserTest : public QObject
{
    Q_OBJECT

private slots:
    void parseValueAcceptsValidInput_data();
    void parseValueAcceptsValidInput();
    void parseValueRejectsInvalidInput_data();
    void parseValueRejectsInvalidInput();
    void toMilliHzRoundsToNearestInteger();
    void toMilliHzRejectsUnsafeValues_data();
    void toMilliHzRejectsUnsafeValues();
    void parseReturnsQmlFriendlyMap();
    void resolveStreamFpsUsesValidCustomRate();
    void resolveStreamFpsFallsBackForInvalidPersistedRate_data();
    void resolveStreamFpsFallsBackForInvalidPersistedRate();
    void protocolFpsConvertsToRoundedActualFps_data();
    void protocolFpsConvertsToRoundedActualFps();
    void protocolFpsFormatsNvHttpModeConsistently_data();
    void protocolFpsFormatsNvHttpModeConsistently();
    void roundedCustomRateStaysConsistentAcrossProtocolConsumers();
    void quantizedHalfBoundaryStaysConsistentAcrossUiAndProtocol();
    void invalidCustomRateUsesFallbackAcrossProtocolConsumers();
    void qmlSingletonCanParseCommaDecimal();
};

void RefreshRateParserTest::parseValueAcceptsValidInput_data()
{
    QTest::addColumn<QString>("text");
    QTest::addColumn<double>("expectedHz");

    QTest::newRow("dot decimal") << QStringLiteral("59.94") << 59.94;
    QTest::newRow("comma decimal") << QStringLiteral("59,94") << 59.94;
    QTest::newRow("surrounding whitespace") << QStringLiteral(" \t59.94\n") << 59.94;
    QTest::newRow("integer") << QStringLiteral("60") << 60.0;
    QTest::newRow("lower boundary") << QStringLiteral("10") << 10.0;
    QTest::newRow("upper boundary") << QStringLiteral("500.0") << 500.0;
}

void RefreshRateParserTest::parseValueAcceptsValidInput()
{
    QFETCH(QString, text);
    QFETCH(double, expectedHz);

    double hz = 0.0;
    QVERIFY(RefreshRateParser::parseValue(text, &hz));
    QCOMPARE(hz, expectedHz);
}

void RefreshRateParserTest::parseValueRejectsInvalidInput_data()
{
    QTest::addColumn<QString>("text");

    QTest::newRow("empty") << QString();
    QTest::newRow("whitespace") << QStringLiteral(" \t\n");
    QTest::newRow("junk") << QStringLiteral("fast");
    QTest::newRow("numeric tail") << QStringLiteral("59.94Hz");
    QTest::newRow("mixed separators") << QStringLiteral("59,94.0");
    QTest::newRow("multiple dots") << QStringLiteral("59.9.4");
    QTest::newRow("multiple commas") << QStringLiteral("59,9,4");
    QTest::newRow("internal whitespace") << QStringLiteral("59 .94");
    QTest::newRow("below lower boundary") << QStringLiteral("9.999");
    QTest::newRow("above upper boundary") << QStringLiteral("500.001");
    QTest::newRow("nonfinite spelling") << QStringLiteral("nan");
    QTest::newRow("infinity spelling") << QStringLiteral("inf");
    QTest::newRow("overflow spelling") << QStringLiteral("1e309");
}

void RefreshRateParserTest::parseValueRejectsInvalidInput()
{
    QFETCH(QString, text);

    double hz = 123.0;
    QVERIFY(!RefreshRateParser::parseValue(text, &hz));
}

void RefreshRateParserTest::toMilliHzRoundsToNearestInteger()
{
    int milliHz = 0;
    QVERIFY(RefreshRateParser::toMilliHz(59.94, &milliHz));
    QCOMPARE(milliHz, 59940);

    QVERIFY(RefreshRateParser::toMilliHz(59.9406, &milliHz));
    QCOMPARE(milliHz, 59941);
}

void RefreshRateParserTest::toMilliHzRejectsUnsafeValues_data()
{
    QTest::addColumn<double>("hz");

    QTest::newRow("not a number") << std::numeric_limits<double>::quiet_NaN();
    QTest::newRow("positive infinity") << std::numeric_limits<double>::infinity();
    QTest::newRow("negative infinity") << -std::numeric_limits<double>::infinity();
    QTest::newRow("positive overflow") << static_cast<double>(std::numeric_limits<int>::max());
    QTest::newRow("negative overflow") << static_cast<double>(std::numeric_limits<int>::min());
}

void RefreshRateParserTest::toMilliHzRejectsUnsafeValues()
{
    QFETCH(double, hz);

    int milliHz = 321;
    QVERIFY(!RefreshRateParser::toMilliHz(hz, &milliHz));
}

void RefreshRateParserTest::parseReturnsQmlFriendlyMap()
{
    RefreshRateParser parser;

    const QVariantMap valid = parser.parse(QStringLiteral("59,94"));
    QCOMPARE(valid.value(QStringLiteral("valid")).toBool(), true);
    QCOMPARE(valid.value(QStringLiteral("hz")).toDouble(), 59.94);
    QCOMPARE(valid.value(QStringLiteral("milliHz")).toInt(), 59940);

    const QVariantMap invalid = parser.parse(QStringLiteral("59.94junk"));
    QCOMPARE(invalid.value(QStringLiteral("valid")).toBool(), false);
    QCOMPARE(invalid.value(QStringLiteral("hz")).toDouble(), 0.0);
    QCOMPARE(invalid.value(QStringLiteral("milliHz")).toInt(), 0);
}

void RefreshRateParserTest::resolveStreamFpsUsesValidCustomRate()
{
    bool usedCustomRate = false;
    QCOMPARE(RefreshRateParser::resolveStreamFps(true, 59.94, 60, &usedCustomRate), 59940);
    QVERIFY(usedCustomRate);
}

void RefreshRateParserTest::resolveStreamFpsFallsBackForInvalidPersistedRate_data()
{
    QTest::addColumn<bool>("enabled");
    QTest::addColumn<double>("customHz");

    QTest::newRow("disabled") << false << 59.94;
    QTest::newRow("below range") << true << 9.99;
    QTest::newRow("above range") << true << 500.01;
    QTest::newRow("not a number") << true << std::numeric_limits<double>::quiet_NaN();
    QTest::newRow("infinity") << true << std::numeric_limits<double>::infinity();
    QTest::newRow("overflow") << true << static_cast<double>(std::numeric_limits<int>::max());
}

void RefreshRateParserTest::resolveStreamFpsFallsBackForInvalidPersistedRate()
{
    QFETCH(bool, enabled);
    QFETCH(double, customHz);

    bool usedCustomRate = true;
    QCOMPARE(RefreshRateParser::resolveStreamFps(enabled, customHz, 60, &usedCustomRate), 60);
    QVERIFY(!usedCustomRate);
}

void RefreshRateParserTest::protocolFpsConvertsToRoundedActualFps_data()
{
    QTest::addColumn<int>("protocolFps");
    QTest::addColumn<int>("expectedActualFps");

    QTest::newRow("integer fps") << 60 << 60;
    QTest::newRow("59.94 milli-Hz") << 59940 << 60;
    QTest::newRow("59.999 milli-Hz") << 59999 << 60;
    QTest::newRow("59.44 milli-Hz") << 59440 << 59;
}

void RefreshRateParserTest::protocolFpsConvertsToRoundedActualFps()
{
    QFETCH(int, protocolFps);
    QFETCH(int, expectedActualFps);

    QCOMPARE(RefreshRateParser::toActualFps(protocolFps, 30), expectedActualFps);
}

void RefreshRateParserTest::protocolFpsFormatsNvHttpModeConsistently_data()
{
    QTest::addColumn<int>("protocolFps");
    QTest::addColumn<QString>("expectedModeFps");

    QTest::newRow("integer fps") << 60 << QStringLiteral("60");
    QTest::newRow("59.94 milli-Hz") << 59940 << QStringLiteral("59.940");
    QTest::newRow("59.999 milli-Hz") << 59999 << QStringLiteral("59.999");
}

void RefreshRateParserTest::protocolFpsFormatsNvHttpModeConsistently()
{
    QFETCH(int, protocolFps);
    QFETCH(QString, expectedModeFps);

    QCOMPARE(RefreshRateParser::formatModeFps(protocolFps, 30), expectedModeFps);
}

void RefreshRateParserTest::roundedCustomRateStaysConsistentAcrossProtocolConsumers()
{
    const int protocolFps = RefreshRateParser::resolveStreamFps(true, 59.9996, 60);

    QCOMPARE(protocolFps, 60000);
    QCOMPARE(RefreshRateParser::toActualFps(protocolFps, 60), 60);
    QCOMPARE(RefreshRateParser::formatModeFps(protocolFps, 60), QStringLiteral("60.000"));
}

void RefreshRateParserTest::quantizedHalfBoundaryStaysConsistentAcrossUiAndProtocol()
{
    RefreshRateParser parser;
    const QVariantMap parsed = parser.parse(QStringLiteral("59.4996"));
    const int protocolFps = parsed.value(QStringLiteral("milliHz")).toInt();
    const double storedCustomHz = static_cast<double>(protocolFps) / 1000.0;

    QCOMPARE(parsed.value(QStringLiteral("valid")).toBool(), true);
    QCOMPARE(protocolFps, 59500);
    QCOMPARE(storedCustomHz, 59.5);
    QCOMPARE(RefreshRateParser::resolveStreamFps(true, storedCustomHz, 60), protocolFps);
    QCOMPARE(RefreshRateParser::formatModeFps(protocolFps, 60), QStringLiteral("59.500"));
    QCOMPARE(RefreshRateParser::toActualFps(protocolFps, 60), 60);
}

void RefreshRateParserTest::invalidCustomRateUsesFallbackAcrossProtocolConsumers()
{
    const int protocolFps = RefreshRateParser::resolveStreamFps(
        true, std::numeric_limits<double>::quiet_NaN(), 60);

    QCOMPARE(protocolFps, 60);
    QCOMPARE(RefreshRateParser::toActualFps(protocolFps, 30), 60);
    QCOMPARE(RefreshRateParser::toActualFps(0, 60), 60);
    QCOMPARE(RefreshRateParser::formatModeFps(protocolFps, 30), QStringLiteral("60"));
    QCOMPARE(RefreshRateParser::formatModeFps(0, 60), QStringLiteral("60"));
}

void RefreshRateParserTest::qmlSingletonCanParseCommaDecimal()
{
    qmlRegisterSingletonType<RefreshRateParser>(
        "RefreshRateParser", 1, 0, "RefreshRateParser",
        [](QQmlEngine*, QJSEngine*) -> QObject* {
            return new RefreshRateParser();
        });

    QQmlEngine engine;
    QQmlComponent component(&engine);
    component.setData(
        "import QtQml 2.2\n"
        "import RefreshRateParser 1.0\n"
        "QtObject {\n"
        "    property var parsed: RefreshRateParser.parse(\"59,94\")\n"
        "    property bool parsedValid: parsed.valid\n"
        "    property real parsedHz: parsed.hz\n"
        "    property int parsedMilliHz: parsed.milliHz\n"
        "}\n",
        QUrl(QStringLiteral("inmemory:/RefreshRateParserTest.qml")));

    QScopedPointer<QObject> object(component.create());
    QVERIFY2(object, qPrintable(component.errorString()));

    QCOMPARE(object->property("parsedValid").toBool(), true);
    QCOMPARE(object->property("parsedHz").toDouble(), 59.94);
    QCOMPARE(object->property("parsedMilliHz").toInt(), 59940);
}

QTEST_GUILESS_MAIN(RefreshRateParserTest)

#include "tst_refreshrate.moc"
