#include "buildinfo.h"

#include <QRegularExpression>

namespace {

const QString kCompatibilityFlatpakApplicationId =
    QStringLiteral("com.artemisdesktop.ArtemisDesktopDev");
const QString kNativeApplicationId = QStringLiteral("com.artemis_desktop.Artemis");

BuildInfo::Channel channelFromName(const QString &name)
{
    if (name == QStringLiteral("rolling")) {
        return BuildInfo::RollingChannel;
    }
    if (name == QStringLiteral("stable")) {
        return BuildInfo::StableChannel;
    }
    return BuildInfo::NoChannel;
}

bool hasCleanCommit(const QString &commit)
{
    static const QRegularExpression kCommitPattern(QStringLiteral("^[0-9a-f]{40}$"));
    return kCommitPattern.match(commit).hasMatch();
}

bool hasExpectedApplicationId(const QString &applicationId)
{
    return applicationId == kCompatibilityFlatpakApplicationId ||
        applicationId == kNativeApplicationId;
}

} // namespace

BuildInfo::Identity BuildInfo::current()
{
    return {
        QStringLiteral(VIBERTEMIS_BUILD_COMMIT),
        channelFromName(QStringLiteral(VIBERTEMIS_UPDATE_CHANNEL)),
        quint64(VIBERTEMIS_BUILD_SEQUENCE),
        QStringLiteral(VIBERTEMIS_APPLICATION_ID),
        QStringLiteral(VERSION_STR)
    };
}

bool BuildInfo::validate(const Identity &identity)
{
    if (!hasExpectedApplicationId(identity.applicationId)) {
        return false;
    }

    switch (identity.channel) {
    case RollingChannel:
        return hasCleanCommit(identity.commit) &&
            identity.sequence > 0 &&
            identity.applicationId == kCompatibilityFlatpakApplicationId;
    case StableChannel:
        return hasCleanCommit(identity.commit) && identity.sequence == 0;
    case NoChannel:
        return (identity.commit == QStringLiteral("unknown") || hasCleanCommit(identity.commit)) &&
            identity.sequence == 0;
    }

    return false;
}

QString BuildInfo::commit()
{
    return current().commit;
}

BuildInfo::Channel BuildInfo::channel()
{
    return current().channel;
}

QString BuildInfo::channelName()
{
    switch (channel()) {
    case RollingChannel:
        return QStringLiteral("rolling");
    case StableChannel:
        return QStringLiteral("stable");
    case NoChannel:
        return QStringLiteral("none");
    }

    return QStringLiteral("none");
}

quint64 BuildInfo::sequence()
{
    return current().sequence;
}

QString BuildInfo::applicationId()
{
    return current().applicationId;
}

QString BuildInfo::version()
{
    return current().version;
}

bool BuildInfo::isInternallyConsistent()
{
    return validate(current());
}

QJsonObject BuildInfo::toJson()
{
    const Identity identity = current();
    return {
        {QStringLiteral("schema"), 1},
        {QStringLiteral("applicationId"), identity.applicationId},
        {QStringLiteral("version"), identity.version},
        {QStringLiteral("commit"), identity.commit},
        {QStringLiteral("channel"), channelName()},
        {QStringLiteral("sequence"), QString::number(identity.sequence)},
        {QStringLiteral("internallyConsistent"), validate(identity)}
    };
}
