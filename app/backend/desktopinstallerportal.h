#pragma once

#include <QFile>
#include <QHash>
#include <QObject>
#include <QScopedPointer>
#include <QSharedPointer>
#include <QString>
#include <QTimer>
#include <QVariantMap>

#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
class QDBusMessage;
#endif

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

class DesktopPortalTransport : public QObject
{
    Q_OBJECT

public:
    explicit DesktopPortalTransport(QObject *parent = nullptr);
    ~DesktopPortalTransport() override;

    virtual QString callerUniqueName() const = 0;
    virtual bool subscribeAllResponses() = 0;
    virtual void unsubscribeAllResponses() = 0;
    virtual bool subscribeResponse(const QString &requestPath) = 0;
    virtual void unsubscribeResponse(const QString &requestPath) = 0;
    virtual void openFile(int descriptor,
                          const QVariantMap &options) = 0;

signals:
    void methodFinished(bool succeeded, const QString &requestPath,
                        const QString &message);
    void requestResponse(const QString &requestPath, uint responseCode,
                         const QVariantMap &results);

protected:
    void setSubscribedResponsePath(const QString &requestPath);

private slots:
    void forwardResponse(uint responseCode, const QVariantMap &results);
#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
    void forwardBroadResponse(uint responseCode, const QVariantMap &results,
                              const QDBusMessage &message);
#endif

private:
    QString m_SubscribedResponsePath;
};

class DesktopInstallerPortal final : public InstallerPortal
{
    Q_OBJECT

public:
    explicit DesktopInstallerPortal(QObject *parent = nullptr);
    explicit DesktopInstallerPortal(DesktopPortalTransport *transport,
                                    QObject *parent = nullptr);
    DesktopInstallerPortal(DesktopPortalTransport *transport,
                           int responseTimeoutMs,
                           QObject *parent = nullptr);
    ~DesktopInstallerPortal() override;

    static QString serviceName();
    static QString desktopObjectPath();
    static QString openUriInterface();
    static QString openFileMethod();
    static QVariantMap openFileOptions();
    static bool isValidHandleToken(const QString &token);
    static QString requestPath(const QString &callerUniqueName,
                               const QString &handleToken);

    void openFlatpak(
        const QSharedPointer<QFile> &verifiedFile) override;

private:
    struct CachedResponse {
        uint responseCode = 0;
        QVariantMap results;
    };

    void initialize();
    void handleMethodFinished(bool succeeded, const QString &requestPath,
                              const QString &message);
    void handleRequestResponse(const QString &requestPath,
                               uint responseCode,
                               const QVariantMap &results);
    bool isValidReturnedPath(const QString &requestPath) const;
    void processResponse(uint responseCode,
                         const QVariantMap &results);
    void finish(bool accepted, const QString &message);

    DesktopPortalTransport *m_Transport;
    QScopedPointer<DesktopPortalTransport> m_OwnedTransport;
    QSharedPointer<QFile> m_VerifiedFile;
    QString m_CallerUniqueName;
    QString m_PredictedRequestPath;
    QString m_ActiveRequestPath;
    QHash<QString, CachedResponse> m_CachedResponses;
    QTimer m_ResponseTimer;
    int m_ResponseTimeoutMs;
    bool m_BroadResponseSubscription;
    bool m_MethodValidated;
};
