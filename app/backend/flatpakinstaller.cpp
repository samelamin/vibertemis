#include "flatpakinstaller.h"

#include <QFileInfo>
#include <QSettings>
#include <QTemporaryFile>

#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
#include <QDBusConnection>
#include <QDBusError>
#include <QDBusMessage>
#include <QDBusMetaType>
#include <QDBusPendingCallWatcher>
#include <QDBusPendingReply>
#include <QDBusServiceWatcher>
#include <QDBusUnixFileDescriptor>
#include <sys/stat.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <unistd.h>

using DescriptorMap = QMap<uint, QDBusUnixFileDescriptor>;
using EnvironmentMap = QMap<QString, QString>;
Q_DECLARE_METATYPE(DescriptorMap)
Q_DECLARE_METATYPE(EnvironmentMap)
#endif

namespace {
const int InstallTimeoutMs = 30 * 60 * 1000;
const int DiagnosticLimit = 8192;
#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
const char Service[] = "org.freedesktop.Flatpak";
const char Object[] = "/org/freedesktop/Flatpak/Development";
const char Interface[] = "org.freedesktop.Flatpak.Development";

class QtFlatpakHostTransport final : public FlatpakHostTransport
{
    Q_OBJECT
public:
    explicit QtFlatpakHostTransport(QObject *parent = nullptr) :
        FlatpakHostTransport(parent),
        m_ServiceWatcher(QString::fromLatin1(Service), QDBusConnection::sessionBus(),
                         QDBusServiceWatcher::WatchForOwnerChange, this)
    {
        qDBusRegisterMetaType<DescriptorMap>();
        qDBusRegisterMetaType<EnvironmentMap>();
        qDBusRegisterMetaType<QList<QByteArray>>();
        connect(&m_ServiceWatcher, &QDBusServiceWatcher::serviceOwnerChanged,
                this, [this](const QString &, const QString &oldOwner,
                             const QString &newOwner) {
            if (m_Active && !oldOwner.isEmpty() && oldOwner != newOwner) {
                emit connectionLost();
            }
        });
        QDBusConnection::sessionBus().connect(QString(),
            QStringLiteral("/org/freedesktop/DBus/Local"),
            QStringLiteral("org.freedesktop.DBus.Local"), QStringLiteral("Disconnected"),
            this, SLOT(busDisconnected()));
        m_Subscribed = QDBusConnection::sessionBus().connect(
            QString::fromLatin1(Service), QString::fromLatin1(Object),
            QString::fromLatin1(Interface), QStringLiteral("HostCommandExited"),
            this, SLOT(childExited(uint,uint)));
    }

    void start(int descriptor) override
    {
        if (m_Active) return;
        if (!m_Subscribed || !QDBusConnection::sessionBus().isConnected()) {
            emit started(false, 0, false, tr("The Flatpak installation service is unavailable. Reopen Vibertemis and try again."));
            return;
        }
        // Updating a system deployment into --user creates a second installation.
        // The documented Steam Deck installation is per-user; don't silently fork it.
        if (QFileInfo::exists(QStringLiteral("/.flatpak-info"))) {
            QSettings info(QStringLiteral("/.flatpak-info"), QSettings::IniFormat);
            const QString path = info.value(QStringLiteral("Instance/app-path")).toString();
            if (path.startsWith(QStringLiteral("/var/lib/flatpak/"))) {
                emit started(false, 0, false, tr("This is a system installation. In-app updates require the per-user Steam Deck installation described in the setup guide."));
                return;
            }
        }
        m_Output.reset(new QTemporaryFile);
        QFile nullInput(QStringLiteral("/dev/null"));
        if (!m_Output->open() || !nullInput.open(QIODevice::ReadOnly)) {
            emit started(false, 0, false, tr("Could not prepare the update installer. Check free disk space and retry."));
            return;
        }
        // A regular temporary file prevents SIGPIPE if the app closes mid-install.
        // The child's open descriptor survives unlinking on application exit.
        DescriptorMap descriptors;
        descriptors.insert(0, QDBusUnixFileDescriptor(nullInput.handle()));
        descriptors.insert(1, QDBusUnixFileDescriptor(m_Output->handle()));
        descriptors.insert(2, QDBusUnixFileDescriptor(m_Output->handle()));
        descriptors.insert(3, QDBusUnixFileDescriptor(descriptor));
        for (const auto &fd : descriptors) {
            if (!fd.isValid()) {
                emit started(false, 0, false, tr("Could not pass the verified update to Flatpak."));
                return;
            }
        }
        QList<QByteArray> argv;
        for (const QString &argument : FlatpakInstaller::commandArguments()) {
            QByteArray encoded = argument.toUtf8();
            encoded.append('\0');
            argv.append(encoded);
        }
        QByteArray cwd("/");
        cwd.append('\0');
        EnvironmentMap environment;
        environment.insert(QStringLiteral("LC_ALL"), QStringLiteral("C.UTF-8"));
        QDBusMessage request = QDBusMessage::createMethodCall(
            QString::fromLatin1(Service), QString::fromLatin1(Object),
            QString::fromLatin1(Interface), QStringLiteral("HostCommand"));
        request << cwd << QVariant::fromValue(argv) << QVariant::fromValue(descriptors)
                << QVariant::fromValue(environment) << uint(0);
        // No WATCH_BUS: closing the app must not kill the transaction.
        m_Active = true;
        auto *watcher = new QDBusPendingCallWatcher(
            QDBusConnection::sessionBus().asyncCall(request, 30000), this);
        connect(watcher, &QDBusPendingCallWatcher::finished, this,
                [this](QDBusPendingCallWatcher *finished) {
            QDBusPendingReply<uint> reply = *finished;
            finished->deleteLater();
            if (reply.isError()) {
                const auto type = reply.error().type();
                const bool unknown = type == QDBusError::NoReply
                    || type == QDBusError::Timeout || type == QDBusError::Disconnected;
                m_Active = unknown;
                const QString detail = reply.error().message().left(DiagnosticLimit);
                emit started(false, 0, unknown, unknown
                    ? tr("The installer did not confirm startup and may still be running. Wait for it to finish, then close and reopen Vibertemis to check the installed build. Details: %1").arg(detail)
                    : tr("Flatpak could not start the update: %1").arg(detail));
            } else {
                emit started(true, reply.value(), false, QString());
            }
        });
    }

private slots:
    void busDisconnected() { if (m_Active) emit connectionLost(); }
    void childExited(uint pid, uint status)
    {
        if (!m_Active) return;
        // Do not clear m_Active here: an unrelated (or early) exit is matched by
        // FlatpakInstaller against the PID in the HostCommand method response.
        QString diagnostic;
        if (m_Output) {
            QByteArray bytes(DiagnosticLimit, '\0');
            const ssize_t count = ::pread(m_Output->handle(), bytes.data(), bytes.size(),
                qMax<qint64>(0, m_Output->size() - DiagnosticLimit));
            if (count > 0) diagnostic = QString::fromUtf8(bytes.constData(), int(count)).trimmed();
        }
        emit exited(pid, status, diagnostic);
    }
public:
    // Installer calls through object lifetime: a fresh transport is not required
    // on retry; known completion releases this invocation only.
    void releaseInvocation() { m_Active = false; m_Output.reset(); }
private:
    QDBusServiceWatcher m_ServiceWatcher;
    QScopedPointer<QTemporaryFile> m_Output;
    bool m_Subscribed = false;
    bool m_Active = false;
};
#else
class QtFlatpakHostTransport final : public FlatpakHostTransport
{
public:
    void start(int) override {
        emit started(false, 0, false,
                     tr("Direct Flatpak installation is available only on Linux."));
    }
    void releaseInvocation() {}
};
#endif
} // namespace

FlatpakInstaller::FlatpakInstaller(QObject *parent) :
    InstallerPortal(parent), m_Transport(nullptr), m_TimeoutMs(InstallTimeoutMs)
{
    m_OwnedTransport.reset(new QtFlatpakHostTransport);
    m_Transport = m_OwnedTransport.data();
    initialize();
}

FlatpakInstaller::FlatpakInstaller(FlatpakHostTransport *transport, int timeoutMs,
                                 QObject *parent) :
    InstallerPortal(parent), m_Transport(transport), m_TimeoutMs(timeoutMs)
{
    Q_ASSERT(transport);
    initialize();
}

QStringList FlatpakInstaller::commandArguments()
{
    return {QStringLiteral("/usr/bin/flatpak"), QStringLiteral("install"),
            QStringLiteral("--user"), QStringLiteral("--or-update"),
            QStringLiteral("--noninteractive"), QStringLiteral("--bundle"),
            QStringLiteral("/proc/self/fd/3")};
}

void FlatpakInstaller::initialize()
{
    m_Timer.setSingleShot(true);
    connect(&m_Timer, &QTimer::timeout, this, [this]() {
        finish(false, tr("The installer has not reported completion. It may still be running. Wait for it to finish, then close and reopen Vibertemis to check the installed build."), true);
    });
    connect(m_Transport, &FlatpakHostTransport::connectionLost, this, [this]() {
        if (m_Busy) finish(false, tr("Lost contact with the installer. It may still be running. Close and reopen Vibertemis after it finishes to check the installed build."), true);
    });
    connect(m_Transport, &FlatpakHostTransport::started, this,
            [this](bool success, uint pid, bool unknown, const QString &message) {
        if (!m_Busy) return;
        if (!success) { finish(false, message, unknown); return; }
        if (!pid) { finish(false, tr("The installer returned an invalid process identity. Close and reopen Vibertemis to check the installed build."), true); return; }
        m_Pid = pid;
        const auto found = m_EarlyExits.constFind(pid);
        if (found != m_EarlyExits.constEnd()) {
            const Exit exit = found.value();
            m_EarlyExits.clear();
            complete(exit.status, exit.diagnostic);
            return; // response() can destroy this installer.
        }
        m_EarlyExits.clear();
    });
    connect(m_Transport, &FlatpakHostTransport::exited, this,
            [this](uint pid, uint status, const QString &diagnostic) {
        if (!m_Busy) return;
        if (!m_Pid) {
            if (m_EarlyExits.size() < 16 && !m_EarlyExits.contains(pid)) {
                m_EarlyExits.insert(pid, Exit{status, diagnostic.left(DiagnosticLimit)});
            }
        } else if (m_Pid == pid) {
            complete(status, diagnostic);
        }
    });
}

void FlatpakInstaller::openFlatpak(const QSharedPointer<QFile> &file)
{
    if (m_Busy || m_Unknown) return;
    if (!file || !file->isOpen() || file->handle() < 0
            || !(file->openMode() & QIODevice::ReadOnly)
            || (file->openMode() & QIODevice::WriteOnly) || !file->seek(0)) {
        emit response(false, tr("The verified update file is unavailable."));
        return;
    }
#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
    struct stat st;
    if (fstat(file->handle(), &st) != 0 || !S_ISREG(st.st_mode)
            || (fcntl(file->handle(), F_GETFL) & O_ACCMODE) != O_RDONLY) {
        emit response(false, tr("The verified update must be an open read-only regular file."));
        return;
    }
#endif
    m_File = file;
    m_Pid = 0;
    m_EarlyExits.clear();
    m_Busy = true;
    m_Timer.start(m_TimeoutMs);
    m_Transport->start(file->handle());
}

void FlatpakInstaller::complete(uint status, const QString &diagnostic)
{
    if (status == 0) {
        finish(true, tr("Update installed. Close and reopen Vibertemis to use it."));
        return;
    }
    QString message = tr("Flatpak could not install the update.");
#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
    if (WIFEXITED(status)) message += tr(" Exit code: %1.").arg(WEXITSTATUS(status));
    else if (WIFSIGNALED(status)) message += tr(" Installer stopped by signal %1.").arg(WTERMSIG(status));
#endif
    if (!diagnostic.isEmpty()) message += QLatin1Char('\n') + diagnostic.left(DiagnosticLimit);
    finish(false, message);
}

void FlatpakInstaller::finish(bool success, const QString &message, bool unknown)
{
    if (!m_Busy) return;
    m_Timer.stop();
    m_Busy = false;
    m_Unknown = unknown;
    m_EarlyExits.clear();
    if (!unknown) {
        m_File.reset();
        if (m_OwnedTransport) {
            static_cast<QtFlatpakHostTransport *>(m_Transport)->releaseInvocation();
        }
    }
    emit response(success, message);
}

#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
#include "flatpakinstaller.moc"
#endif
