#include "desktopinstallerportal.h"

#include <QTimer>
#include <QUuid>

#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
#include <QDBusConnection>
#include <QDBusError>
#include <QDBusInterface>
#include <QDBusObjectPath>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#include <QDBusUnixFileDescriptor>
#endif

namespace {

const char PortalService[] = "org.freedesktop.portal.Desktop";
const char PortalObject[] = "/org/freedesktop/portal/desktop";
const char OpenUriInterface[] = "org.freedesktop.portal.OpenURI";
const char OpenFileMethod[] = "OpenFile";
#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
const char RequestInterface[] = "org.freedesktop.portal.Request";
const char RequestSignal[] = "Response";
#endif
const char RequestPrefix[] =
    "/org/freedesktop/portal/desktop/request/";
const int PortalResponseTimeoutMs = 30000;

#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
class QtDesktopPortalTransport final : public DesktopPortalTransport
{
public:
    explicit QtDesktopPortalTransport(QObject *parent = nullptr) :
        DesktopPortalTransport(parent)
    {
    }

    ~QtDesktopPortalTransport() override
    {
        if (m_AllResponsesSubscribed) {
            unsubscribeAllResponses();
        }
        if (!m_RequestPath.isEmpty()) {
            unsubscribeResponse(m_RequestPath);
        }
    }

    QString callerUniqueName() const override
    {
        return QDBusConnection::sessionBus().baseService();
    }

    bool subscribeAllResponses() override
    {
        if (m_AllResponsesSubscribed) {
            return false;
        }
        if (!QDBusConnection::sessionBus().connect(
                QString::fromLatin1(PortalService), QString(),
                QString::fromLatin1(RequestInterface),
                QString::fromLatin1(RequestSignal), this,
                SLOT(forwardBroadResponse(uint,QVariantMap,QDBusMessage)))) {
            return false;
        }
        m_AllResponsesSubscribed = true;
        return true;
    }

    void unsubscribeAllResponses() override
    {
        if (!m_AllResponsesSubscribed) {
            return;
        }
        QDBusConnection::sessionBus().disconnect(
            QString::fromLatin1(PortalService), QString(),
            QString::fromLatin1(RequestInterface),
            QString::fromLatin1(RequestSignal), this,
            SLOT(forwardBroadResponse(uint,QVariantMap,QDBusMessage)));
        m_AllResponsesSubscribed = false;
    }

    bool subscribeResponse(const QString &requestPath) override
    {
        if (!m_RequestPath.isEmpty()) {
            return false;
        }
        if (!QDBusConnection::sessionBus().connect(
                QString::fromLatin1(PortalService), requestPath,
                QString::fromLatin1(RequestInterface),
                QString::fromLatin1(RequestSignal), this,
                SLOT(forwardResponse(uint,QVariantMap)))) {
            return false;
        }
        m_RequestPath = requestPath;
        setSubscribedResponsePath(requestPath);
        return true;
    }

    void unsubscribeResponse(const QString &requestPath) override
    {
        if (requestPath != m_RequestPath) {
            return;
        }
        QDBusConnection::sessionBus().disconnect(
            QString::fromLatin1(PortalService), requestPath,
            QString::fromLatin1(RequestInterface),
            QString::fromLatin1(RequestSignal), this,
            SLOT(forwardResponse(uint,QVariantMap)));
        m_RequestPath.clear();
        setSubscribedResponsePath(QString());
    }

    void openFile(int descriptor,
                  const QVariantMap &options) override
    {
        QDBusConnection connection = QDBusConnection::sessionBus();
        if (!connection.isConnected()) {
            emit methodFinished(
                false, QString(),
                QStringLiteral("The desktop portal is unavailable."));
            return;
        }

        QDBusUnixFileDescriptor dbusDescriptor(descriptor);
        if (!dbusDescriptor.isValid()) {
            emit methodFinished(
                false, QString(),
                QStringLiteral("The verified update descriptor is invalid."));
            return;
        }

        QDBusInterface portal(
            QString::fromLatin1(PortalService),
            QString::fromLatin1(PortalObject),
            QString::fromLatin1(OpenUriInterface), connection);
        if (!portal.isValid()) {
            const QString message = portal.lastError().message();
            emit methodFinished(
                false, QString(),
                message.isEmpty()
                    ? QStringLiteral("The desktop portal is unavailable.")
                    : message);
            return;
        }

        QVariantList arguments;
        arguments.append(QString());
        arguments.append(QVariant::fromValue(dbusDescriptor));
        arguments.append(options);
        QDBusPendingCallWatcher *watcher =
            new QDBusPendingCallWatcher(
                portal.asyncCallWithArgumentList(
                    QString::fromLatin1(OpenFileMethod), arguments),
                this);
        connect(watcher, &QDBusPendingCallWatcher::finished,
                this, [this](QDBusPendingCallWatcher *finished) {
            QDBusPendingReply<QDBusObjectPath> reply = *finished;
            finished->deleteLater();
            if (reply.isError()) {
                emit methodFinished(
                    false, QString(), reply.error().message());
                return;
            }
            emit methodFinished(
                true, reply.value().path(), QString());
        });
    }

private:
    QString m_RequestPath;
    bool m_AllResponsesSubscribed = false;
};
#else
class QtDesktopPortalTransport final : public DesktopPortalTransport
{
public:
    explicit QtDesktopPortalTransport(QObject *parent = nullptr) :
        DesktopPortalTransport(parent)
    {
    }

    QString callerUniqueName() const override
    {
        return QStringLiteral(":0.0");
    }

    bool subscribeAllResponses() override
    {
        return true;
    }

    void unsubscribeAllResponses() override
    {
    }

    bool subscribeResponse(const QString &requestPath) override
    {
        Q_UNUSED(requestPath)
        return true;
    }

    void unsubscribeResponse(const QString &requestPath) override
    {
        Q_UNUSED(requestPath)
    }

    void openFile(int descriptor,
                  const QVariantMap &options) override
    {
        Q_UNUSED(descriptor)
        Q_UNUSED(options)
        QTimer::singleShot(0, this, [this]() {
            emit methodFinished(
                false, QString(),
                QStringLiteral(
                    "Installer handoff is only available on Linux."));
        });
    }
};
#endif

QString makeHandleToken()
{
    QString token = QUuid::createUuid().toString();
    token.remove(QLatin1Char('{'));
    token.remove(QLatin1Char('}'));
    token.remove(QLatin1Char('-'));
    return QStringLiteral("vibertemis_") + token;
}

} // namespace

InstallerPortal::InstallerPortal(QObject *parent) :
    QObject(parent)
{
}

InstallerPortal::~InstallerPortal() = default;

DesktopPortalTransport::DesktopPortalTransport(QObject *parent) :
    QObject(parent)
{
}

DesktopPortalTransport::~DesktopPortalTransport() = default;

void DesktopPortalTransport::setSubscribedResponsePath(
    const QString &requestPath)
{
    m_SubscribedResponsePath = requestPath;
}

void DesktopPortalTransport::forwardResponse(
    uint responseCode, const QVariantMap &results)
{
    emit requestResponse(m_SubscribedResponsePath,
                         responseCode, results);
}

#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
void DesktopPortalTransport::forwardBroadResponse(
    uint responseCode, const QVariantMap &results,
    const QDBusMessage &message)
{
    emit requestResponse(message.path(), responseCode, results);
}
#endif

DesktopInstallerPortal::DesktopInstallerPortal(QObject *parent) :
    InstallerPortal(parent),
    m_Transport(nullptr),
    m_ResponseTimeoutMs(PortalResponseTimeoutMs),
    m_BroadResponseSubscription(false),
    m_MethodValidated(false)
{
    m_OwnedTransport.reset(new QtDesktopPortalTransport);
    m_Transport = m_OwnedTransport.data();
    initialize();
}

DesktopInstallerPortal::DesktopInstallerPortal(
    DesktopPortalTransport *transport, QObject *parent) :
    DesktopInstallerPortal(transport, PortalResponseTimeoutMs, parent)
{
}

DesktopInstallerPortal::DesktopInstallerPortal(
    DesktopPortalTransport *transport, int responseTimeoutMs,
    QObject *parent) :
    InstallerPortal(parent),
    m_Transport(transport),
    m_ResponseTimeoutMs(responseTimeoutMs > 0
        ? responseTimeoutMs : PortalResponseTimeoutMs),
    m_BroadResponseSubscription(false),
    m_MethodValidated(false)
{
    Q_ASSERT(m_Transport);
    initialize();
}

DesktopInstallerPortal::~DesktopInstallerPortal()
{
    m_ResponseTimer.stop();
    if (m_Transport && m_BroadResponseSubscription) {
        m_Transport->unsubscribeAllResponses();
    }
    if (m_Transport && !m_ActiveRequestPath.isEmpty()) {
        m_Transport->unsubscribeResponse(m_ActiveRequestPath);
    }
}

void DesktopInstallerPortal::initialize()
{
    m_ResponseTimer.setSingleShot(true);
    connect(&m_ResponseTimer, &QTimer::timeout,
            this, [this]() {
        if (!m_VerifiedFile) {
            return;
        }
        finish(false,
               QStringLiteral(
                   "The desktop portal response timed out; retry the installer."));
    });
    connect(m_Transport, &DesktopPortalTransport::methodFinished,
            this, &DesktopInstallerPortal::handleMethodFinished);
    connect(m_Transport, &DesktopPortalTransport::requestResponse,
            this, &DesktopInstallerPortal::handleRequestResponse);
}

QString DesktopInstallerPortal::serviceName()
{
    return QString::fromLatin1(PortalService);
}

QString DesktopInstallerPortal::desktopObjectPath()
{
    return QString::fromLatin1(PortalObject);
}

QString DesktopInstallerPortal::openUriInterface()
{
    return QString::fromLatin1(OpenUriInterface);
}

QString DesktopInstallerPortal::openFileMethod()
{
    return QString::fromLatin1(OpenFileMethod);
}

QVariantMap DesktopInstallerPortal::openFileOptions()
{
    QVariantMap options;
    options.insert(QStringLiteral("writable"), false);
    options.insert(QStringLiteral("ask"), true);
    return options;
}

bool DesktopInstallerPortal::isValidHandleToken(
    const QString &token)
{
    if (token.isEmpty() || token.size() > 255) {
        return false;
    }
    for (const QChar character : token) {
        const ushort code = character.unicode();
        const bool asciiLetter =
            (code >= 'a' && code <= 'z')
            || (code >= 'A' && code <= 'Z');
        const bool asciiDigit = code >= '0' && code <= '9';
        if (!asciiLetter && !asciiDigit && code != '_') {
            return false;
        }
    }
    return true;
}

QString DesktopInstallerPortal::requestPath(
    const QString &callerUniqueName, const QString &handleToken)
{
    if (!callerUniqueName.startsWith(QLatin1Char(':'))
            || !isValidHandleToken(handleToken)) {
        return QString();
    }
    QString sender = callerUniqueName.mid(1);
    sender.replace(QLatin1Char('.'), QLatin1Char('_'));
    if (!isValidHandleToken(sender)) {
        return QString();
    }
    return QString::fromLatin1(RequestPrefix)
        + sender + QLatin1Char('/') + handleToken;
}

void DesktopInstallerPortal::openFlatpak(
    const QSharedPointer<QFile> &verifiedFile)
{
    if (m_VerifiedFile || !verifiedFile || !verifiedFile->isOpen()
            || !(verifiedFile->openMode() & QIODevice::ReadOnly)
            || (verifiedFile->openMode() & QIODevice::WriteOnly)
            || verifiedFile->handle() < 0) {
        QTimer::singleShot(0, this, [this]() {
            finish(false,
                   QStringLiteral(
                       "The verified update file is unavailable."));
        });
        return;
    }

    m_CallerUniqueName = m_Transport->callerUniqueName();
    const QString handleToken = makeHandleToken();
    m_PredictedRequestPath =
        requestPath(m_CallerUniqueName, handleToken);
    if (m_PredictedRequestPath.isEmpty()) {
        QTimer::singleShot(0, this, [this]() {
            finish(false,
                   QStringLiteral(
                       "The desktop portal caller identity is invalid."));
        });
        return;
    }

    m_VerifiedFile = verifiedFile;
    m_ActiveRequestPath = m_PredictedRequestPath;
    if (!m_Transport->subscribeAllResponses()) {
        QTimer::singleShot(0, this, [this]() {
            finish(false,
                   QStringLiteral(
                       "The desktop portal response could not be monitored."));
        });
        return;
    }
    m_BroadResponseSubscription = true;

    QVariantMap options = openFileOptions();
    options.insert(QStringLiteral("handle_token"), handleToken);
    m_ResponseTimer.start(m_ResponseTimeoutMs);
    m_Transport->openFile(verifiedFile->handle(), options);
}

void DesktopInstallerPortal::handleMethodFinished(
    bool succeeded, const QString &returnedPath,
    const QString &message)
{
    if (!m_VerifiedFile) {
        return;
    }
    if (!succeeded) {
        finish(false, message.isEmpty()
            ? QStringLiteral("The installer request failed.")
            : message);
        return;
    }
    if (!isValidReturnedPath(returnedPath)) {
        finish(false,
               QStringLiteral(
                   "The desktop portal returned an invalid request path."));
        return;
    }

    m_ActiveRequestPath = returnedPath;
    m_MethodValidated = true;
    const auto cached = m_CachedResponses.constFind(m_ActiveRequestPath);
    if (cached != m_CachedResponses.constEnd()) {
        const CachedResponse response = cached.value();
        m_CachedResponses.clear();
        processResponse(response.responseCode, response.results);
        return;
    }
    m_CachedResponses.clear();
    if (!m_Transport->subscribeResponse(m_ActiveRequestPath)) {
        finish(false,
               QStringLiteral(
                   "The desktop portal response could not be monitored."));
        return;
    }
    if (m_BroadResponseSubscription) {
        m_Transport->unsubscribeAllResponses();
        m_BroadResponseSubscription = false;
    }
}

void DesktopInstallerPortal::handleRequestResponse(
    const QString &requestPath, uint responseCode,
    const QVariantMap &results)
{
    if (!m_VerifiedFile) {
        return;
    }
    if (!m_MethodValidated) {
        if (!isValidReturnedPath(requestPath)
                || m_CachedResponses.contains(requestPath)
                || m_CachedResponses.size() >= 8) {
            return;
        }
        CachedResponse response;
        response.responseCode = responseCode;
        response.results = results;
        m_CachedResponses.insert(requestPath, response);
        return;
    }
    if (requestPath != m_ActiveRequestPath) {
        return;
    }
    processResponse(responseCode, results);
}

bool DesktopInstallerPortal::isValidReturnedPath(
    const QString &requestPath) const
{
    const QString expected = DesktopInstallerPortal::requestPath(
        m_CallerUniqueName, QStringLiteral("placeholder"));
    const int separator = expected.lastIndexOf(QLatin1Char('/'));
    if (separator < 0) {
        return false;
    }
    const QString callerPrefix = expected.left(separator + 1);
    if (!requestPath.startsWith(callerPrefix)) {
        return false;
    }
    return isValidHandleToken(requestPath.mid(callerPrefix.size()));
}

void DesktopInstallerPortal::processResponse(
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
    m_ResponseTimer.stop();
    if (m_Transport && m_BroadResponseSubscription) {
        m_Transport->unsubscribeAllResponses();
        m_BroadResponseSubscription = false;
    }
    if (m_Transport && !m_ActiveRequestPath.isEmpty()) {
        m_Transport->unsubscribeResponse(m_ActiveRequestPath);
    }
    m_ActiveRequestPath.clear();
    m_PredictedRequestPath.clear();
    m_CallerUniqueName.clear();
    m_CachedResponses.clear();
    m_MethodValidated = false;
    m_VerifiedFile.reset();
    emit response(accepted, message);
}
