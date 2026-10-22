#pragma once

#include "desktopinstallerportal.h"
#include <QStringList>

// Deliberately separate from OpenURI: a successful response means the child
// installer exited successfully, never merely that an application was opened.
class FlatpakHostTransport : public QObject
{
    Q_OBJECT
public:
    explicit FlatpakHostTransport(QObject *parent = nullptr) : QObject(parent) {}
    virtual void start(int verifiedDescriptor) = 0;
signals:
    void started(bool success, uint pid, bool outcomeUnknown, const QString &message);
    void exited(uint pid, uint waitStatus, const QString &diagnostic);
    void connectionLost();
};

class FlatpakInstaller final : public InstallerPortal
{
    Q_OBJECT
public:
    explicit FlatpakInstaller(QObject *parent = nullptr);
    FlatpakInstaller(FlatpakHostTransport *transport, int timeoutMs,
                     QObject *parent = nullptr);
    bool installsDirectly() const override { return true; }
    bool installationOutcomeUnknown() const override { return m_Unknown; }
    void openFlatpak(const QSharedPointer<QFile> &verifiedFile) override;
    static QStringList commandArguments();

private:
    void initialize();
    void complete(uint status, const QString &diagnostic);
    void finish(bool success, const QString &message, bool unknown = false);
    struct Exit { uint status; QString diagnostic; };
    FlatpakHostTransport *m_Transport;
    QScopedPointer<FlatpakHostTransport> m_OwnedTransport;
    QSharedPointer<QFile> m_File;
    QHash<uint, Exit> m_EarlyExits;
    QTimer m_Timer;
    int m_TimeoutMs;
    uint m_Pid = 0;
    bool m_Busy = false;
    bool m_Unknown = false;
};
