#pragma once

#include <QByteArray>
#include <QNetworkAccessManager>
#include <QObject>
#include <QScopedPointer>
#include <QSharedPointer>
#include <QTemporaryFile>
#include <QTimer>

#include "pendingupdate.h"
#include "rollingupdateparser.h"
#include "steamdecksession.h"

class QNetworkReply;
class QCryptographicHash;

class AutoUpdateChecker : public QObject
{
    Q_OBJECT
    Q_PROPERTY(State state READ state NOTIFY stateChanged)
    Q_PROPERTY(QString currentBuild READ currentBuild CONSTANT)
    Q_PROPERTY(QString availableBuild READ availableBuild NOTIFY candidateChanged)
    Q_PROPERTY(QString releaseUrl READ releaseUrl NOTIFY candidateChanged)
    Q_PROPERTY(QString downloadedPath READ downloadedPath NOTIFY downloadedPathChanged)
    Q_PROPERTY(qint64 bytesReceived READ bytesReceived NOTIFY progressChanged)
    Q_PROPERTY(qint64 bytesTotal READ bytesTotal NOTIFY progressChanged)
    Q_PROPERTY(QString errorMessage READ errorMessage NOTIFY errorChanged)
    Q_PROPERTY(bool rollingInstallSupported READ rollingInstallSupported CONSTANT)

public:
    enum State {
        Idle,
        RestoringPending,
        Checking,
        NoUpdate,
        Available,
        Downloading,
        Verifying,
        ReadyForDesktop,
        ReadyToHandOff,
        CheckError,
        DownloadError,
        VerificationError,
        RestoreError,
        Cancelled
    };
    Q_ENUM(State)

    explicit AutoUpdateChecker(QObject *parent = nullptr);
    AutoUpdateChecker(QNetworkAccessManager *network,
                      UpdateFileStore *files,
                      SessionModeProvider *session,
                      QObject *parent = nullptr);
    ~AutoUpdateChecker() override;

    State state() const;
    QString currentBuild() const;
    QString availableBuild() const;
    QString releaseUrl() const;
    QString downloadedPath() const;
    qint64 bytesReceived() const;
    qint64 bytesTotal() const;
    QString errorMessage() const;
    bool rollingInstallSupported() const;

    static bool isApprovedRedirect(const QUrl &url);
    static int maximumRedirects();
    static qint64 jsonResponseLimit();
    static qint64 manifestResponseLimit();

    Q_INVOKABLE void start();
    Q_INVOKABLE void checkNow();
    Q_INVOKABLE void downloadUpdate();
    Q_INVOKABLE void cancel();
    Q_INVOKABLE void retry();
    Q_INVOKABLE void discardPendingUpdate();
    Q_INVOKABLE void openReleasePage();

signals:
    void onUpdateAvailable(QString newVersion, QString url);
    void stateChanged();
    void candidateChanged();
    void downloadedPathChanged();
    void progressChanged();
    void errorChanged();

private slots:
    void handleUpdateCheckRequestFinished(QNetworkReply *reply);
    void handleReplyReadyRead();
    void handleReplyFinished();
    void handleConnectTimeout();
    void handleIdleTimeout();
    void handleOverallTimeout();

private:
    enum RequestStage {
        NoRequest,
        StableReleases,
        RollingRelease,
        RollingManifest,
        RollingTagReference,
        RollingTagObjectRequest,
        RollingCompare,
        FlatpakAsset,
        RevalidateRelease,
        RevalidateManifest,
        RevalidateTagReference,
        RevalidateTagObjectRequest
    };
    enum RetryOrigin { NoRetry, CheckRetry, DownloadRetry, VerificationRetry, RestoreRetry };

    void initialize();
    bool applyTransition(int event);
    void setStateDirect(State state);
    void setError(UpdateError error, const QString &message, RetryOrigin origin);
    void clearError();
    void setCandidate(const RollingUpdateCandidate &candidate);
    void clearCandidate();
    void invalidatePendingAndCheck(const QString &diagnostic);
    void beginStableCheck();
    void beginRollingCheck();
    void beginRestoration(const PendingUpdateRecord &record);
    void beginTagResolution(bool revalidation);
    void resolveCurrentTagObject(bool revalidation);
    void beginRevalidation(bool restoring);
    void finishRevalidation();
    void issueRequest(const QUrl &url, RequestStage stage, qint64 limit,
                      const QByteArray &accept = QByteArrayLiteral("application/vnd.github+json"),
                      int redirects = 0);
    void consumeReplyBody();
    void handleRequestSuccess(RequestStage stage, const QByteArray &body);
    void handleRequestFailure(UpdateError error, const QString &message);
    void failForActiveOperation(UpdateError error, const QString &message);
    void stopRequest();
    void finishStableResponse(const QByteArray &body);
    void finishRollingRelease(const QByteArray &body);
    void finishRollingManifest(const QByteArray &body);
    void finishTagReference(const QByteArray &body, bool revalidation);
    void finishTagObject(const QByteArray &body, bool revalidation);
    void finishCompare(const QByteArray &body);
    void finishFlatpakDownload();
    void saveVerifiedPending();
    void parseStringToVersionQuad(QString &string, QVector<int> &version);
    int compareVersion(QVector<int> &version1, QVector<int> &version2);
    bool supportsStableCheck() const;
    QString rateLimitMessage(QNetworkReply *reply) const;

    State m_State;
    RetryOrigin m_RetryOrigin;
    UpdateError m_LastError;
    QString m_ErrorMessage;
    QVector<int> m_CurrentVersionQuad;
    QNetworkAccessManager *m_Nam;
    UpdateFileStore *m_Files;
    SessionModeProvider *m_Session;
    QScopedPointer<UpdateFileStore> m_OwnedFiles;
    QScopedPointer<SessionModeProvider> m_OwnedSession;
    QNetworkReply *m_Reply;
    RequestStage m_RequestStage;
    qint64 m_ResponseLimit;
    QByteArray m_ResponseBody;
    QUrl m_RequestUrl;
    QByteArray m_Accept;
    int m_Redirects;
    QTimer m_ConnectTimer;
    QTimer m_IdleTimer;
    QTimer m_OverallTimer;

    RollingUpdateCandidate m_Release;
    RollingUpdateCandidate m_Candidate;
    RollingUpdateCandidate m_ExpectedCandidate;
    RollingTagObject m_TagReference;
    QList<RollingTagObject> m_TagObjects;
    PendingUpdateRecord m_RestoreRecord;
    bool m_Restoring;

    QSharedPointer<QTemporaryFile> m_Temporary;
    QSharedPointer<QFile> m_VerifiedFile;
    QScopedPointer<QCryptographicHash> m_DownloadHash;
    QString m_DownloadedPath;
    qint64 m_BytesReceived;
    qint64 m_BytesTotal;
};
