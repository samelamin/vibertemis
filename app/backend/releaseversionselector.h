#pragma once

#include <QJsonArray>
#include <QString>

struct ReleaseVersionSelection
{
    bool valid = false;
    QString version;
    QString url;
};

class ReleaseVersionSelector
{
public:
    static ReleaseVersionSelection select(const QJsonArray &releases);
};
