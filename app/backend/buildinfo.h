#pragma once

#include <QByteArray>
#include <QJsonObject>
#include <QMetaType>
#include <QString>
#include <QStringList>
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

    struct Preflight {
        bool handled;
        QByteArray output;
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
    static Preflight preflight(const QStringList &arguments);
    static bool requiresParentConsoleAttachment(bool stdoutUnspecified, bool stderrUnspecified);
};

Q_DECLARE_METATYPE(BuildInfo::Identity)
