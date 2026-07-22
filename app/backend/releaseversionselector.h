#pragma once

#include <QJsonArray>
#include <QString>

struct ReleaseVersionSelection
{
    ReleaseVersionSelection() :
        valid(false)
    {
    }

    ReleaseVersionSelection(bool isValid, const QString &selectedVersion,
                            const QString &selectedUrl) :
        valid(isValid),
        version(selectedVersion),
        url(selectedUrl)
    {
    }

    bool valid;
    QString version;
    QString url;
};

class ReleaseVersionSelector
{
public:
    static ReleaseVersionSelection select(const QJsonArray &releases);
};
