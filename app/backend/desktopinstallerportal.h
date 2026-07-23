#pragma once

#include <QFile>
#include <QObject>
#include <QSharedPointer>
#include <QString>
#include <QVariantMap>

class InstallerPortal : public QObject
{
    Q_OBJECT

public:
    explicit InstallerPortal(QObject *parent = nullptr);
    ~InstallerPortal() override;

    virtual void openFlatpak(
        const QSharedPointer<QFile> &verifiedFile) = 0;

signals:
    void response(bool accepted, const QString &message);
};

class DesktopInstallerPortal final : public InstallerPortal
{
    Q_OBJECT

public:
    explicit DesktopInstallerPortal(QObject *parent = nullptr);
    ~DesktopInstallerPortal() override;

    static QString serviceName();
    static QString desktopObjectPath();
    static QString openUriInterface();
    static QString openFileMethod();
    static QVariantMap openFileOptions();

    void openFlatpak(
        const QSharedPointer<QFile> &verifiedFile) override;

private slots:
    void handlePortalResponse(uint responseCode,
                              const QVariantMap &results);

private:
    void finish(bool accepted, const QString &message);

    QSharedPointer<QFile> m_VerifiedFile;
    QString m_RequestPath;
};
