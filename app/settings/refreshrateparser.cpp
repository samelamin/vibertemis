#include "refreshrateparser.h"

#include <QRegularExpression>
#include <QtGlobal>

#include <cmath>
#include <limits>

namespace {
const double kMinimumRefreshRateHz = 10.0;
const double kMaximumRefreshRateHz = 500.0;
const int kFractionalFpsThreshold = 4000;
}

RefreshRateParser::RefreshRateParser(QObject* parent)
    : QObject(parent)
{
}

QVariantMap RefreshRateParser::parse(const QString& text) const
{
    QVariantMap result;
    double hz = 0.0;
    int milliHz = 0;
    const bool valid = parseValue(text, &hz) && toMilliHz(hz, &milliHz);

    result.insert(QStringLiteral("valid"), valid);
    result.insert(QStringLiteral("hz"), valid ? hz : 0.0);
    result.insert(QStringLiteral("milliHz"), valid ? milliHz : 0);
    return result;
}

bool RefreshRateParser::parseValue(const QString& text, double* hz)
{
    if (!hz) {
        return false;
    }

    QString normalized = text.trimmed();
    const int commaCount = normalized.count(QLatin1Char(','));
    const int dotCount = normalized.count(QLatin1Char('.'));
    if (commaCount > 1 || dotCount > 1 || (commaCount != 0 && dotCount != 0)) {
        return false;
    }

    if (commaCount == 1) {
        normalized.replace(QLatin1Char(','), QLatin1Char('.'));
    }

    static const QRegularExpression numberPattern(
        QStringLiteral("^[0-9]+(?:\\.[0-9]+)?$"));
    if (!numberPattern.match(normalized).hasMatch()) {
        return false;
    }

    bool converted = false;
    const double value = normalized.toDouble(&converted);
    if (!converted || !std::isfinite(value) ||
            value < kMinimumRefreshRateHz || value > kMaximumRefreshRateHz) {
        return false;
    }

    *hz = value;
    return true;
}

bool RefreshRateParser::toMilliHz(double hz, int* milliHz)
{
    if (!milliHz || !std::isfinite(hz)) {
        return false;
    }

    const double scaled = hz * 1000.0;
    if (!std::isfinite(scaled) ||
            scaled < static_cast<double>(std::numeric_limits<int>::min()) - 0.5 ||
            scaled > static_cast<double>(std::numeric_limits<int>::max()) + 0.5) {
        return false;
    }

    const qint64 rounded = qRound64(scaled);
    if (rounded < std::numeric_limits<int>::min() ||
            rounded > std::numeric_limits<int>::max()) {
        return false;
    }

    *milliHz = static_cast<int>(rounded);
    return true;
}

int RefreshRateParser::resolveStreamFps(bool customEnabled,
                                        double customHz,
                                        int fallbackFps,
                                        bool* usedCustomRate)
{
    if (usedCustomRate) {
        *usedCustomRate = false;
    }

    if (!customEnabled || !std::isfinite(customHz) ||
            customHz < kMinimumRefreshRateHz || customHz > kMaximumRefreshRateHz) {
        return fallbackFps;
    }

    int milliHz = 0;
    if (!toMilliHz(customHz, &milliHz)) {
        return fallbackFps;
    }

    if (usedCustomRate) {
        *usedCustomRate = true;
    }
    return milliHz;
}

int RefreshRateParser::toActualFps(int protocolFps, int fallbackFps)
{
    const int safeProtocolFps = protocolFps > 0 ? protocolFps : fallbackFps;
    if (safeProtocolFps <= 0) {
        return 0;
    }

    if (safeProtocolFps > kFractionalFpsThreshold) {
        return qRound(static_cast<double>(safeProtocolFps) / 1000.0);
    }

    return safeProtocolFps;
}

QString RefreshRateParser::formatModeFps(int protocolFps, int fallbackFps)
{
    const int safeProtocolFps = protocolFps > 0 ? protocolFps : fallbackFps;
    if (safeProtocolFps <= 0) {
        return QStringLiteral("0");
    }

    if (safeProtocolFps > kFractionalFpsThreshold) {
        return QString::number(static_cast<double>(safeProtocolFps) / 1000.0, 'f', 3);
    }

    return QString::number(safeProtocolFps);
}
