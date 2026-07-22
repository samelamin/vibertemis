#include "releaseversionselector.h"

#include <QJsonObject>
#include <QRegularExpression>
#include <QStringList>

ReleaseVersionSelection ReleaseVersionSelector::select(const QJsonArray &releases)
{
    static const QRegularExpression numericComponent(QStringLiteral("^[0-9]+$"));

    for (const QJsonValue &releaseValue : releases) {
        const QJsonObject release = releaseValue.toObject();
        const QString tagName = release.value(QStringLiteral("tag_name")).toString();
        const QString url = release.value(QStringLiteral("html_url")).toString();

        if (tagName == QStringLiteral("steam-deck-latest") || url.isEmpty()) {
            continue;
        }

        const QString version = tagName.startsWith(QLatin1Char('v'))
            ? tagName.mid(1)
            : tagName;
        const QStringList components = version.split(QLatin1Char('.'));
        if (components.size() < 2 ||
            !numericComponent.match(components.at(0)).hasMatch() ||
            !numericComponent.match(components.at(1)).hasMatch()) {
            continue;
        }

        return {true, version, url};
    }

    return {};
}
