#include "desktopinstallerportal.h"

#include <QTimer>

#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
#include <QDBusConnection>
#include <QDBusError>
#include <QDBusInterface>
#include <QDBusObjectPath>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#include <QDBusUnixFileDescriptor>
#endif

InstallerPortal::InstallerPortal(QObject *parent) :
    QObject(parent)
{
}

InstallerPortal::~InstallerPortal() = default;

DesktopInstallerPortal::DesktopInstallerPortal(QObject *parent) :
    InstallerPortal(parent)
{
}

DesktopInstallerPortal::~DesktopInstallerPortal()
{
#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
    if (!m_RequestPath.isEmpty()) {
        QDBusConnection::sessionBus().disconnect(
            serviceName(), m_RequestPath,
            QStringLiteral("org.freedesktop.portal.Request"),
            QStringLiteral("Response"), this,
            SLOT(handlePortalResponse(uint,QVariantMap)));
    }
#endif
}

QString DesktopInstallerPortal::serviceName()
{
    return QStringLiteral("org.freedesktop.portal.Desktop");
}

QString DesktopInstallerPortal::desktopObjectPath()
{
    return QStringLiteral("/org/freedesktop/portal/desktop");
}

QString DesktopInstallerPortal::openUriInterface()
{
    return QStringLiteral("org.freedesktop.portal.OpenURI");
}

QString DesktopInstallerPortal::openFileMethod()
{
    return QStringLiteral("OpenFile");
}

QVariantMap DesktopInstallerPortal::openFileOptions()
{
    QVariantMap options;
    options.insert(QStringLiteral("writable"), false);
    options.insert(QStringLiteral("ask"), true);
    return options;
}

void DesktopInstallerPortal::openFlatpak(
    const QSharedPointer<QFile> &verifiedFile)
{
    if (!verifiedFile || !verifiedFile->isOpen()
            || !(verifiedFile->openMode() & QIODevice::ReadOnly)
            || (verifiedFile->openMode() & QIODevice::WriteOnly)
            || verifiedFile->handle() < 0) {
        QTimer::singleShot(0, this, [this]() {
            finish(false,
                   QStringLiteral("The verified update file is unavailable."));
        });
        return;
    }

#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
    if (!m_RequestPath.isEmpty() || m_VerifiedFile) {
        QTimer::singleShot(0, this, [this]() {
            emit response(
                false,
                QStringLiteral("An installer request is already active."));
        });
        return;
    }

    QDBusConnection connection = QDBusConnection::sessionBus();
    if (!connection.isConnected()) {
        QTimer::singleShot(0, this, [this]() {
            finish(false,
                   QStringLiteral("The desktop portal is unavailable."));
        });
        return;
    }

    m_VerifiedFile = verifiedFile;
    QDBusUnixFileDescriptor descriptor(verifiedFile->handle());
    if (!descriptor.isValid()) {
        QTimer::singleShot(0, this, [this]() {
            finish(false,
                   QStringLiteral("The verified update descriptor is invalid."));
        });
        return;
    }

    QDBusInterface portal(serviceName(), desktopObjectPath(),
                          openUriInterface(), connection);
    if (!portal.isValid()) {
        const QString message = portal.lastError().message();
        QTimer::singleShot(0, this, [this, message]() {
            finish(false, message.isEmpty()
                       ? QStringLiteral("The desktop portal is unavailable.")
                       : message);
        });
        return;
    }

    QVariantList arguments;
    arguments.append(QString());
    arguments.append(QVariant::fromValue(descriptor));
    arguments.append(openFileOptions());
    QDBusPendingCallWatcher *watcher = new QDBusPendingCallWatcher(
        portal.asyncCallWithArgumentList(openFileMethod(), arguments), this);
    connect(watcher, &QDBusPendingCallWatcher::finished,
            this, [this](QDBusPendingCallWatcher *finished) {
        QDBusPendingReply<QDBusObjectPath> reply = *finished;
        finished->deleteLater();
        if (reply.isError()) {
            finish(false, reply.error().message());
            return;
        }

        const QString requestPath = reply.value().path();
        const QString expectedPrefix =
            QStringLiteral("/org/freedesktop/portal/desktop/request/");
        if (!requestPath.startsWith(expectedPrefix)
                || requestPath.size() <= expectedPrefix.size()) {
            finish(false,
                   QStringLiteral("The desktop portal returned an invalid request path."));
            return;
        }
        m_RequestPath = requestPath;
        if (!QDBusConnection::sessionBus().connect(
                serviceName(), m_RequestPath,
                QStringLiteral("org.freedesktop.portal.Request"),
                QStringLiteral("Response"), this,
                SLOT(handlePortalResponse(uint,QVariantMap)))) {
            finish(false,
                   QStringLiteral("The desktop portal response could not be monitored."));
        }
    });
#else
    Q_UNUSED(verifiedFile)
    QTimer::singleShot(0, this, [this]() {
        finish(false,
               QStringLiteral("Installer handoff is only available on Linux."));
    });
#endif
}

void DesktopInstallerPortal::handlePortalResponse(
    uint responseCode, const QVariantMap &results)
{
    Q_UNUSED(results)
    if (responseCode == 0) {
        finish(true, QStringLiteral("Installer requested."));
        return;
    }
    finish(false, responseCode == 1
        ? QStringLiteral("The installer request was cancelled.")
        : QStringLiteral("The installer request was rejected."));
}

void DesktopInstallerPortal::finish(bool accepted,
                                    const QString &message)
{
#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
    if (!m_RequestPath.isEmpty()) {
        QDBusConnection::sessionBus().disconnect(
            serviceName(), m_RequestPath,
            QStringLiteral("org.freedesktop.portal.Request"),
            QStringLiteral("Response"), this,
            SLOT(handlePortalResponse(uint,QVariantMap)));
    }
#endif
    m_RequestPath.clear();
    m_VerifiedFile.reset();
    emit response(accepted, message);
}
