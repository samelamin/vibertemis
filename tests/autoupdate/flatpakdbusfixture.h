#pragma once

#ifdef VIBERTEMIS_HAS_DESKTOP_PORTAL
#include <QDBusConnection>
#include <QDBusMessage>
#include <QDBusMetaType>
#include <QDBusUnixFileDescriptor>
#include <QDBusVirtualObject>
#include <fcntl.h>
#include <unistd.h>

using TestDescriptorMap = QMap<uint, QDBusUnixFileDescriptor>;
Q_DECLARE_METATYPE(TestDescriptorMap)

// Exported only on a private test bus. Never launches a host command.
class FlatpakDbusFixture final : public QDBusVirtualObject
{
public:
    QString introspect(const QString &) const override { return QString(); }
    bool handleMessage(const QDBusMessage &message, const QDBusConnection &bus) override
    {
        if (message.member() != QStringLiteral("HostCommand")) return false;
        ++calls;
        const auto arguments = message.arguments();
        if (message.signature() != QStringLiteral("ayaaya{uh}a{ss}u")) {
            error = QStringLiteral("incorrect wire signature: ") + message.signature();
        } else {
            cwd = arguments[0].toByteArray();
            argv = qdbus_cast<QList<QByteArray>>(arguments[1]);
            const auto fds = qdbus_cast<TestDescriptorMap>(arguments[2]);
            flags = arguments[4].toUInt();
            descriptorKeys = fds.keys();
            const int fd = fds.value(3).fileDescriptor();
            readOnly = (fcntl(fd, F_GETFL) & O_ACCMODE) == O_RDONLY;
            offset = lseek(fd, 0, SEEK_CUR);
            char buffer[128];
            const ssize_t count = pread(fd, buffer, sizeof(buffer), 0);
            if (count > 0) payload = QByteArray(buffer, int(count));
            const QByteArray diagnostic("fixture: insufficient disk space");
            if (write(fds.value(2).fileDescriptor(), diagnostic.constData(), diagnostic.size()) < 0) {
                error = QStringLiteral("diagnostic descriptor not writable");
            }
        }
        QDBusMessage exit = QDBusMessage::createSignal(
            QStringLiteral("/org/freedesktop/Flatpak/Development"),
            QStringLiteral("org.freedesktop.Flatpak.Development"),
            QStringLiteral("HostCommandExited"));
        exit << uint(430) << status;
        bus.send(exit); // Deliberately before the method's PID response.
        QTimer::singleShot(10, this, [message, bus]() {
            bus.send(message.createReply(QVariant::fromValue(uint(430))));
        });
        return true;
    }
    uint status = 256;
    int calls = 0;
    QString error;
    QByteArray cwd;
    QList<QByteArray> argv;
    QList<uint> descriptorKeys;
    uint flags = 999;
    bool readOnly = false;
    qint64 offset = -1;
    QByteArray payload;
};
#endif
