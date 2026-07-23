#include "steamdecksession.h"

namespace {

bool hasDesktopToken(QString desktop, const QString &expected)
{
    desktop.replace(QLatin1Char(';'), QLatin1Char(':'));
    const QStringList tokens = desktop.split(QLatin1Char(':'));
    for (const QString &token : tokens) {
        if (token.trimmed().compare(expected, Qt::CaseInsensitive) == 0) {
            return true;
        }
    }
    return false;
}

} // namespace

SteamDeckSession::Mode SteamDeckSession::classify(const QProcessEnvironment &environment)
{
    const QString desktop = environment.value(QStringLiteral("XDG_CURRENT_DESKTOP"));
    if (!environment.value(QStringLiteral("GAMESCOPE_WAYLAND_DISPLAY")).trimmed().isEmpty()
        || hasDesktopToken(desktop, QStringLiteral("gamescope"))) {
        return Gaming;
    }
    const QString fullSession = environment.value(QStringLiteral("KDE_FULL_SESSION")).trimmed();
    const QString sessionType = environment.value(QStringLiteral("XDG_SESSION_TYPE")).trimmed();
    if (hasDesktopToken(desktop, QStringLiteral("kde"))
        && fullSession.compare(QStringLiteral("true"), Qt::CaseInsensitive) == 0
        && (sessionType.compare(QStringLiteral("wayland"), Qt::CaseInsensitive) == 0
            || sessionType.compare(QStringLiteral("x11"), Qt::CaseInsensitive) == 0)) {
        return Desktop;
    }
    return Unknown;
}

SteamDeckSession::Mode SteamDeckSession::current()
{
    return classify(QProcessEnvironment::systemEnvironment());
}

SteamDeckSession::Mode EnvironmentSessionModeProvider::mode() const
{
    return SteamDeckSession::current();
}
