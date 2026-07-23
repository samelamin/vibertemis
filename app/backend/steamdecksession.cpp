#include "steamdecksession.h"

SteamDeckSession::Mode SteamDeckSession::classify(const QProcessEnvironment &environment)
{
    const QString desktop = environment.value(QStringLiteral("XDG_CURRENT_DESKTOP"));
    if (!environment.value(QStringLiteral("GAMESCOPE_WAYLAND_DISPLAY")).isEmpty()
        || desktop.contains(QStringLiteral("gamescope"), Qt::CaseInsensitive)) {
        return Gaming;
    }
    if (desktop.contains(QStringLiteral("kde"), Qt::CaseInsensitive)
        && environment.value(QStringLiteral("KDE_FULL_SESSION")) == QStringLiteral("true")
        && (environment.value(QStringLiteral("XDG_SESSION_TYPE")) == QStringLiteral("wayland")
            || environment.value(QStringLiteral("XDG_SESSION_TYPE")) == QStringLiteral("x11"))) {
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
