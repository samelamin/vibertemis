#pragma once

#include <QProcessEnvironment>

class SteamDeckSession
{
public:
    enum Mode { Desktop, Gaming, Unknown };

    static Mode classify(const QProcessEnvironment &environment);
    static Mode current();
};

class SessionModeProvider
{
public:
    virtual ~SessionModeProvider() = default;
    virtual SteamDeckSession::Mode mode() const = 0;
};

class EnvironmentSessionModeProvider final : public SessionModeProvider
{
public:
    SteamDeckSession::Mode mode() const override;
};
