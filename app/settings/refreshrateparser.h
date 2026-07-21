#pragma once

#include <QObject>
#include <QString>
#include <QVariantMap>

class RefreshRateParser : public QObject
{
    Q_OBJECT

public:
    explicit RefreshRateParser(QObject* parent = nullptr);

    Q_INVOKABLE QVariantMap parse(const QString& text) const;

    static bool parseValue(const QString& text, double* hz);
    static bool toMilliHz(double hz, int* milliHz);
    static int resolveStreamFps(bool customEnabled,
                                double customHz,
                                int fallbackFps,
                                bool* usedCustomRate = nullptr);
};
