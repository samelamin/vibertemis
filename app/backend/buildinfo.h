#pragma once

#include <QJsonObject>
#include <QString>
#include <QtGlobal>

class BuildInfo
{
public:
    enum Channel { NoChannel, StableChannel, RollingChannel };

    struct Identity {
        QString commit;
        Channel channel;
        quint64 sequence;
        QString applicationId;
        QString version;
    };

    static Identity current();
    static bool validate(const Identity &identity);

    static QString commit();
    static Channel channel();
    static QString channelName();
    static quint64 sequence();
    static QString applicationId();
    static QString version();
    static bool isInternallyConsistent();
    static QJsonObject toJson();
};
