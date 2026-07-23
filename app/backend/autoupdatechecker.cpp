#include "autoupdatechecker.h"

#include "buildinfo.h"
#include "releaseversionselector.h"
#include "updatestatemachine.h"

#include <QCryptographicHash>
#include <QDesktopServices>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QNetworkReply>
#include <QNetworkRequest>

namespace {

const char RollingReleaseUrl[] =
    "https://api.github.com/repos/samelamin/vibertemis/releases/tags/steam-deck-latest";
const char RollingTagReferenceUrl[] =
    "https://api.github.com/repos/samelamin/vibertemis/git/ref/tags/steam-deck-latest";
const char GitTagObjectPrefix[] =
    "https://api.github.com/repos/samelamin/vibertemis/git/tags/";
const char ComparePrefix[] =
    "https://api.github.com/repos/samelamin/vibertemis/compare/";
const qint64 JsonLimit = 1024 * 1024;
const qint64 ManifestLimit = 64 * 1024;
const int RedirectLimit = 5;
const int ConnectTimeoutMs = 10000;
const int IdleTimeoutMs = 15000;
const int JsonOverallTimeoutMs = 30000;
const int DownloadOverallTimeoutMs = 30 * 60 * 1000;

QString shortCommit(const QString &commit)
{
    return commit.size() == 40 ? commit.left(12) : commit;
}

} // namespace

AutoUpdateChecker::AutoUpdateChecker(QObject *parent) :
    QObject(parent),
    m_State(Idle),
    m_RetryOrigin(NoRetry),
    m_LastError(UpdateError::None),
    m_Nam(new QNetworkAccessManager(this)),
    m_Files(nullptr),
    m_Session(nullptr),
    m_Reply(nullptr),
    m_RequestStage(NoRequest),
    m_ResponseLimit(0),
    m_Redirects(0),
    m_Restoring(false),
    m_BytesReceived(0),
    m_BytesTotal(0)
{
    m_OwnedFiles.reset(new PendingUpdateStore);
    m_OwnedSession.reset(new EnvironmentSessionModeProvider);
    m_Files = m_OwnedFiles.data();
    m_Session = m_OwnedSession.data();
    initialize();
}

AutoUpdateChecker::AutoUpdateChecker(QNetworkAccessManager *network,
                                     UpdateFileStore *files,
                                     SessionModeProvider *session,
                                     QObject *parent) :
    QObject(parent),
    m_State(Idle),
    m_RetryOrigin(NoRetry),
    m_LastError(UpdateError::None),
    m_Nam(network),
    m_Files(files),
    m_Session(session),
    m_Reply(nullptr),
    m_RequestStage(NoRequest),
    m_ResponseLimit(0),
    m_Redirects(0),
    m_Restoring(false),
    m_BytesReceived(0),
    m_BytesTotal(0)
{
    Q_ASSERT(m_Nam);
    Q_ASSERT(m_Files);
    Q_ASSERT(m_Session);
    initialize();
}

AutoUpdateChecker::~AutoUpdateChecker()
{
    stopRequest();
}

void AutoUpdateChecker::initialize()
{
    m_Nam->setStrictTransportSecurityEnabled(true);
#if QT_VERSION >= QT_VERSION_CHECK(5, 14, 0) \
        && QT_VERSION < QT_VERSION_CHECK(5, 15, 1) \
        && !defined(QT_NO_BEARERMANAGEMENT)
    // QTBUG-80947: network accessibility could remain incorrectly disabled
    // after startup on Qt 5.14.0 through 5.15.0.
    QT_WARNING_PUSH
    QT_WARNING_DISABLE_DEPRECATED
    m_Nam->setNetworkAccessible(QNetworkAccessManager::Accessible);
    QT_WARNING_POP
#endif
    m_ConnectTimer.setSingleShot(true);
    m_IdleTimer.setSingleShot(true);
    m_OverallTimer.setSingleShot(true);
    connect(&m_ConnectTimer, &QTimer::timeout,
            this, &AutoUpdateChecker::handleConnectTimeout);
    connect(&m_IdleTimer, &QTimer::timeout,
            this, &AutoUpdateChecker::handleIdleTimeout);
    connect(&m_OverallTimer, &QTimer::timeout,
            this, &AutoUpdateChecker::handleOverallTimeout);

    QString currentVersion(VERSION_STR);
    parseStringToVersionQuad(currentVersion, m_CurrentVersionQuad);
    Q_ASSERT(m_CurrentVersionQuad.count() > 1);
}

AutoUpdateChecker::State AutoUpdateChecker::state() const { return m_State; }
QString AutoUpdateChecker::currentBuild() const
{
    return rollingInstallSupported() ? shortCommit(BuildInfo::commit()) : BuildInfo::version();
}
QString AutoUpdateChecker::availableBuild() const
{
    return m_Candidate.sourceCommit.isEmpty()
        ? m_Candidate.releaseLabel : shortCommit(m_Candidate.sourceCommit);
}
QString AutoUpdateChecker::releaseUrl() const
{
    return m_Candidate.releasePage.toString();
}
QString AutoUpdateChecker::downloadedPath() const { return m_DownloadedPath; }
qint64 AutoUpdateChecker::bytesReceived() const { return m_BytesReceived; }
qint64 AutoUpdateChecker::bytesTotal() const { return m_BytesTotal; }
QString AutoUpdateChecker::errorMessage() const { return m_ErrorMessage; }
bool AutoUpdateChecker::rollingInstallSupported() const
{
    return BuildInfo::channel() == BuildInfo::RollingChannel
        && BuildInfo::isInternallyConsistent();
}

bool AutoUpdateChecker::isApprovedRedirect(const QUrl &url)
{
    if (RollingUpdateParser::isApprovedUrl(url)) {
        return true;
    }
    // GitHub's asset API redirects public downloads to expiring signed CDN
    // URLs. The stored release URLs remain query-free; only a redirect to an
    // exact GitHub asset CDN host may carry that signed query.
    if (!url.isValid() || url.scheme() != QStringLiteral("https")
            || !url.userInfo().isEmpty() || !url.fragment().isEmpty()
            || url.port() != -1 || url.path().size() <= 1) {
        return false;
    }
    const QString host = url.host().toLower();
    const QStringList roots{
        QStringLiteral("objects.githubusercontent.com"),
        QStringLiteral("github-releases.githubusercontent.com"),
        QStringLiteral("release-assets.githubusercontent.com")
    };
    for (const QString &root : roots) {
        if (host == root || host.endsWith(QLatin1Char('.') + root)) {
            return true;
        }
    }
    return false;
}

int AutoUpdateChecker::maximumRedirects() { return RedirectLimit; }
qint64 AutoUpdateChecker::jsonResponseLimit() { return JsonLimit; }
qint64 AutoUpdateChecker::manifestResponseLimit() { return ManifestLimit; }

bool AutoUpdateChecker::applyTransition(int rawEvent)
{
    const UpdateStateMachine::Event event =
        static_cast<UpdateStateMachine::Event>(rawEvent);
    const UpdateResult<State> result =
        UpdateStateMachine::reduce(m_State, event);
    if (!result.ok) {
        qWarning() << result.message;
        return false;
    }
    setStateDirect(result.value);
    return true;
}

void AutoUpdateChecker::setStateDirect(State state)
{
    if (m_State == state) {
        return;
    }
    m_State = state;
    emit stateChanged();
}

void AutoUpdateChecker::clearError()
{
    if (m_ErrorMessage.isEmpty() && m_LastError == UpdateError::None) {
        return;
    }
    m_ErrorMessage.clear();
    m_LastError = UpdateError::None;
    emit errorChanged();
}

void AutoUpdateChecker::setError(UpdateError error, const QString &message,
                                 RetryOrigin origin)
{
    m_LastError = error;
    m_ErrorMessage = message;
    m_RetryOrigin = origin;
    emit errorChanged();
}

void AutoUpdateChecker::setCandidate(
    const RollingUpdateCandidate &candidate)
{
    const QString oldBuild = availableBuild();
    const QString oldReleaseUrl = releaseUrl();
    m_Candidate = candidate;
    if (availableBuild() != oldBuild || releaseUrl() != oldReleaseUrl) {
        emit candidateChanged();
    }
}

void AutoUpdateChecker::clearCandidate()
{
    setCandidate(RollingUpdateCandidate());
}

void AutoUpdateChecker::invalidatePendingAndCheck(
    const QString &diagnostic)
{
    Q_UNUSED(diagnostic)
    stopRequest();
    m_Files->clear(true);
    m_Temporary.reset();
    m_VerifiedFile.reset();
    m_DownloadHash.reset();
    m_RestoreRecord = PendingUpdateRecord();
    m_ExpectedCandidate = RollingUpdateCandidate();
    m_Restoring = false;
    if (!m_DownloadedPath.isEmpty()) {
        m_DownloadedPath.clear();
        emit downloadedPathChanged();
    }
    clearCandidate();
    clearError();
    if (applyTransition(UpdateStateMachine::BeginCheck)) {
        beginRollingCheck();
    }
}

void AutoUpdateChecker::start()
{
    if (m_State != Idle) {
        return;
    }
    if (!rollingInstallSupported()) {
        checkNow();
        return;
    }

    m_Files->cleanStaleParts();
    const UpdateResult<PendingUpdateRecord> loaded = m_Files->load();
    if (!loaded.ok) {
        if (loaded.error == UpdateError::IoFailure
                && RollingUpdateParser::validateCandidate(
                    loaded.value.candidate).ok) {
            if (!applyTransition(UpdateStateMachine::BeginRestore)) {
                return;
            }
            m_Restoring = true;
            m_RestoreRecord = loaded.value;
            m_ExpectedCandidate = loaded.value.candidate;
            setCandidate(loaded.value.candidate);
            applyTransition(UpdateStateMachine::RestoreFailed);
            setError(loaded.error,
                     loaded.message.isEmpty()
                        ? QStringLiteral("The pending update could not be read.")
                        : loaded.message,
                     RestoreRetry);
            return;
        }
        m_Files->clear(false);
        checkNow();
        return;
    }
    if (!applyTransition(UpdateStateMachine::BeginRestore)) {
        return;
    }
    if (loaded.value.candidate.sourceCommit == BuildInfo::commit()) {
        m_Files->clear(false);
        applyTransition(UpdateStateMachine::CandidateCurrent);
        return;
    }
    beginRestoration(loaded.value);
}

void AutoUpdateChecker::checkNow()
{
    if (m_Reply || m_State == Downloading || m_State == Verifying
            || m_State == RestoringPending) {
        return;
    }
    if (!applyTransition(UpdateStateMachine::BeginCheck)) {
        return;
    }
    clearError();
    m_Restoring = false;
    clearCandidate();
    if (rollingInstallSupported()) {
        beginRollingCheck();
    } else if (supportsStableCheck()) {
        beginStableCheck();
    } else {
        applyTransition(UpdateStateMachine::CandidateCurrent);
    }
}

void AutoUpdateChecker::beginStableCheck()
{
    issueRequest(
        QUrl(QStringLiteral("https://api.github.com/repos/samelamin/vibertemis/releases")),
        StableReleases, JsonLimit);
}

void AutoUpdateChecker::beginRollingCheck()
{
    m_Release = RollingUpdateCandidate();
    m_TagObjects.clear();
    issueRequest(QUrl(QString::fromLatin1(RollingReleaseUrl)),
                 RollingRelease, JsonLimit);
}

void AutoUpdateChecker::beginRestoration(const PendingUpdateRecord &record)
{
    m_Restoring = true;
    m_RestoreRecord = record;
    m_ExpectedCandidate = record.candidate;
    setCandidate(record.candidate);
    beginRevalidation(true);
}

void AutoUpdateChecker::downloadUpdate()
{
    if (m_State != Available || m_Reply || !rollingInstallSupported()) {
        return;
    }
    if (!applyTransition(UpdateStateMachine::BeginDownload)) {
        return;
    }
    clearError();
    const UpdateResult<QSharedPointer<QTemporaryFile>> created =
        m_Files->createDownload(m_Candidate.flatpak.size);
    if (!created.ok) {
        applyTransition(UpdateStateMachine::DownloadFailed);
        setError(created.error, created.message, DownloadRetry);
        return;
    }
    m_Temporary = created.value;
    m_DownloadHash.reset(
        new QCryptographicHash(QCryptographicHash::Sha256));
    m_BytesReceived = 0;
    m_BytesTotal = static_cast<qint64>(m_Candidate.flatpak.size);
    emit progressChanged();
    issueRequest(m_Candidate.flatpak.apiUrl, FlatpakAsset,
                 static_cast<qint64>(m_Candidate.flatpak.size),
                 QByteArrayLiteral("application/octet-stream"));
}

void AutoUpdateChecker::cancel()
{
    if (m_State != Checking && m_State != Available
            && m_State != Downloading && m_State != Verifying
            && m_State != RestoringPending && m_State != ReadyForDesktop
            && m_State != ReadyToHandOff && m_State != RestoreError) {
        return;
    }
    const State previous = m_State;
    stopRequest();
    m_Temporary.reset();
    m_VerifiedFile.reset();
    m_DownloadHash.reset();
    if (previous == ReadyForDesktop || previous == ReadyToHandOff
            || previous == RestoringPending || previous == RestoreError) {
        m_Files->clear(true);
        m_DownloadedPath.clear();
        emit downloadedPathChanged();
    }
    if (applyTransition(UpdateStateMachine::Cancel)) {
        setError(UpdateError::Cancelled, QStringLiteral("Update cancelled."), NoRetry);
    }
}

void AutoUpdateChecker::retry()
{
    if (m_State == CheckError) {
        checkNow();
        return;
    }
    if ((m_State == DownloadError || m_State == VerificationError)
            && m_LastError == UpdateError::PublisherChanged) {
        checkNow();
        return;
    }
    if (m_State == DownloadError || m_State == VerificationError) {
        if (!applyTransition(UpdateStateMachine::Retry)) {
            return;
        }
        clearError();
        if (m_RetryOrigin == VerificationRetry && m_VerifiedFile) {
            if (!applyTransition(UpdateStateMachine::BeginDownload)) {
                return;
            }
            applyTransition(UpdateStateMachine::DownloadComplete);
            beginRevalidation(false);
        }
        return;
    }
    if (m_State == RestoreError) {
        if (!applyTransition(UpdateStateMachine::Retry)) {
            return;
        }
        clearError();
        beginRevalidation(true);
        return;
    }
    if (m_State == ReadyForDesktop || m_State == ReadyToHandOff) {
        if (!applyTransition(UpdateStateMachine::Retry)) {
            return;
        }
        clearError();
        beginRevalidation(false);
    }
}

void AutoUpdateChecker::discardPendingUpdate()
{
    if (m_State == Checking || m_State == Available
            || m_State == Downloading || m_State == Verifying
            || m_State == RestoringPending || m_State == RestoreError
            || m_State == ReadyForDesktop || m_State == ReadyToHandOff) {
        cancel();
        return;
    }
}

void AutoUpdateChecker::openReleasePage()
{
    if (m_Candidate.releasePage.isValid()) {
        QDesktopServices::openUrl(m_Candidate.releasePage);
    }
}

void AutoUpdateChecker::issueRequest(const QUrl &url, RequestStage stage,
                                     qint64 limit, const QByteArray &accept,
                                     int redirects)
{
    if (m_Reply) {
        failForActiveOperation(UpdateError::HttpFailure,
                               QStringLiteral("Another update request is already active."));
        return;
    }
    if (!isApprovedRedirect(url) || redirects > RedirectLimit) {
        failForActiveOperation(UpdateError::RedirectRejected,
                               QStringLiteral("The update server redirected to an unapproved location."));
        return;
    }

    m_RequestStage = stage;
    m_ResponseLimit = limit;
    m_ResponseBody.clear();
    m_RequestUrl = url;
    m_Accept = accept;
    m_Redirects = redirects;

    QNetworkRequest request(url);
    request.setRawHeader(QByteArrayLiteral("Accept"), accept);
    request.setRawHeader(QByteArrayLiteral("User-Agent"), QByteArrayLiteral("Vibertemis-Updater"));
    request.setAttribute(QNetworkRequest::CookieLoadControlAttribute,
                         QNetworkRequest::Manual);
    request.setAttribute(QNetworkRequest::CookieSaveControlAttribute,
                         QNetworkRequest::Manual);
#if QT_VERSION >= QT_VERSION_CHECK(5, 15, 0)
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute,
                         QNetworkRequest::ManualRedirectPolicy);
    request.setAttribute(QNetworkRequest::Http2AllowedAttribute, true);
#else
    request.setAttribute(QNetworkRequest::FollowRedirectsAttribute, false);
    request.setAttribute(QNetworkRequest::HTTP2AllowedAttribute, true);
#endif
    m_Reply = m_Nam->get(request);
    connect(m_Reply, &QIODevice::readyRead,
            this, &AutoUpdateChecker::handleReplyReadyRead);
    connect(m_Reply, &QNetworkReply::finished,
            this, &AutoUpdateChecker::handleReplyFinished);
    m_ConnectTimer.start(ConnectTimeoutMs);
    m_IdleTimer.start(IdleTimeoutMs);
    m_OverallTimer.start(stage == FlatpakAsset
                         ? DownloadOverallTimeoutMs : JsonOverallTimeoutMs);
}

void AutoUpdateChecker::handleReplyReadyRead()
{
    if (!m_Reply) {
        return;
    }
    m_ConnectTimer.stop();
    m_IdleTimer.start(IdleTimeoutMs);
    consumeReplyBody();
}

void AutoUpdateChecker::consumeReplyBody()
{
    if (!m_Reply) {
        return;
    }
    const int status =
        m_Reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (m_Reply->attribute(
            QNetworkRequest::RedirectionTargetAttribute).isValid()
            || (status != 0 && (status < 200 || status > 299))) {
        // Redirect/error bodies are not part of the requested resource.
        // This is especially important for Flatpak redirects: writing a 302
        // HTML body would corrupt the streamed payload before the CDN hop.
        m_Reply->readAll();
        return;
    }
    const QByteArray chunk = m_Reply->readAll();
    if (chunk.isEmpty()) {
        return;
    }
    if (m_RequestStage == FlatpakAsset) {
        if (!m_Temporary
                || m_BytesReceived > m_ResponseLimit - chunk.size()
                || m_Temporary->write(chunk) != chunk.size()) {
            handleRequestFailure(
                m_BytesReceived > m_ResponseLimit - chunk.size()
                    ? UpdateError::SizeMismatch : UpdateError::IoFailure,
                m_BytesReceived > m_ResponseLimit - chunk.size()
                    ? QStringLiteral("The update download is larger than the published size.")
                    : QStringLiteral("The update could not be written to Downloads."));
            return;
        }
        if (!m_DownloadHash) {
            handleRequestFailure(
                UpdateError::IoFailure,
                QStringLiteral("The update checksum state is unavailable."));
            return;
        }
#if QT_VERSION >= QT_VERSION_CHECK(6, 4, 0)
        m_DownloadHash->addData(QByteArrayView(chunk));
#else
        m_DownloadHash->addData(chunk);
#endif
        m_BytesReceived += chunk.size();
        emit progressChanged();
        return;
    }
    if (m_ResponseBody.size() > m_ResponseLimit - chunk.size()) {
        handleRequestFailure(UpdateError::ResponseTooLarge,
                             QStringLiteral("The update server response is too large."));
        return;
    }
    m_ResponseBody += chunk;
}

void AutoUpdateChecker::handleReplyFinished()
{
    if (!m_Reply) {
        return;
    }
    consumeReplyBody();
    if (!m_Reply) {
        return;
    }

    QNetworkReply *reply = m_Reply;
    const RequestStage stage = m_RequestStage;
    const qint64 limit = m_ResponseLimit;
    const QByteArray accept = m_Accept;
    const int redirects = m_Redirects;
    const QUrl sourceUrl = m_RequestUrl;
    const QVariant redirectAttribute =
        reply->attribute(QNetworkRequest::RedirectionTargetAttribute);
    const int status =
        reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    const QNetworkReply::NetworkError networkError = reply->error();
    const QByteArray body = m_ResponseBody;
    const QString limitedMessage = rateLimitMessage(reply);

    m_Reply = nullptr;
    m_RequestStage = NoRequest;
    m_ConnectTimer.stop();
    m_IdleTimer.stop();
    m_OverallTimer.stop();
    reply->deleteLater();

    if (redirectAttribute.isValid()) {
        const QUrl target = sourceUrl.resolved(redirectAttribute.toUrl());
        if (redirects >= RedirectLimit || !isApprovedRedirect(target)) {
            handleRequestFailure(UpdateError::RedirectRejected,
                                 QStringLiteral("The update server redirect was rejected."));
            return;
        }
        // Rebuild the request from a minimal header allow-list. In particular,
        // cookies and authorization are never copied to another origin.
        issueRequest(target, stage, limit, accept, redirects + 1);
        return;
    }
    if (status < 200 || status > 299) {
        if (status == 403 && !limitedMessage.isEmpty()) {
            handleRequestFailure(UpdateError::RateLimited, limitedMessage);
        } else {
            handleRequestFailure(UpdateError::HttpFailure,
                                 QStringLiteral("Update server returned HTTP %1.").arg(status));
        }
        return;
    }
    if (networkError != QNetworkReply::NoError) {
        handleRequestFailure(UpdateError::HttpFailure,
                             QStringLiteral("Update request failed (network error %1).")
                                 .arg(static_cast<int>(networkError)));
        return;
    }
    handleRequestSuccess(stage, body);
}

void AutoUpdateChecker::handleRequestSuccess(RequestStage stage,
                                             const QByteArray &body)
{
    if (stage == StableReleases) {
        finishStableResponse(body);
    } else if (stage == RollingRelease) {
        finishRollingRelease(body);
    } else if (stage == RevalidateRelease) {
            const UpdateResult<bool> match =
                RollingUpdateParser::matchesRelease(m_ExpectedCandidate, body);
            if (!match.ok) {
                handleRequestFailure(match.error, match.message);
                return;
            }
            issueRequest(m_ExpectedCandidate.manifest.apiUrl,
                         RevalidateManifest, ManifestLimit);
    } else if (stage == FlatpakAsset) {
        finishFlatpakDownload();
    } else if (stage == RollingCompare) {
        finishCompare(body);
    } else if (stage == RollingTagReference) {
        finishTagReference(body, false);
    } else if (stage == RevalidateTagReference) {
        finishTagReference(body, true);
    } else if (stage == RollingTagObjectRequest) {
        finishTagObject(body, false);
    } else if (stage == RevalidateTagObjectRequest) {
        finishTagObject(body, true);
    } else if (stage == RollingManifest) {
        finishRollingManifest(body);
    } else if (stage == RevalidateManifest) {
        const UpdateResult<bool> match =
            RollingUpdateParser::matchesManifest(m_ExpectedCandidate, body);
        if (!match.ok) {
            handleRequestFailure(match.error, match.message);
            return;
        }
        beginTagResolution(true);
    }
}

void AutoUpdateChecker::handleRequestFailure(UpdateError error,
                                             const QString &message)
{
    stopRequest();
    failForActiveOperation(error, message);
}

void AutoUpdateChecker::failForActiveOperation(UpdateError error,
                                               const QString &message)
{
    if (m_State == Downloading) {
        m_Temporary.reset();
        m_DownloadHash.reset();
        applyTransition(UpdateStateMachine::DownloadFailed);
        setError(error, message, DownloadRetry);
    } else if (m_State == Verifying) {
        applyTransition(UpdateStateMachine::VerificationFailed);
        setError(error, message, VerificationRetry);
    } else if (m_State == RestoringPending) {
        if (error == UpdateError::PublisherChanged) {
            invalidatePendingAndCheck(message);
            return;
        }
        applyTransition(UpdateStateMachine::RestoreFailed);
        setError(error, message, RestoreRetry);
    } else if (m_State == Checking) {
        clearCandidate();
        applyTransition(UpdateStateMachine::CheckFailed);
        setError(error, message, CheckRetry);
    }
}

void AutoUpdateChecker::stopRequest()
{
    m_ConnectTimer.stop();
    m_IdleTimer.stop();
    m_OverallTimer.stop();
    if (m_Reply) {
        QNetworkReply *reply = m_Reply;
        m_Reply = nullptr;
        m_RequestStage = NoRequest;
        disconnect(reply, nullptr, this, nullptr);
        reply->abort();
        reply->deleteLater();
    }
}

void AutoUpdateChecker::handleConnectTimeout()
{
    handleRequestFailure(UpdateError::Timeout,
                         QStringLiteral("Timed out connecting to the update server."));
}
void AutoUpdateChecker::handleIdleTimeout()
{
    handleRequestFailure(UpdateError::Timeout,
                         QStringLiteral("The update download stopped responding."));
}
void AutoUpdateChecker::handleOverallTimeout()
{
    handleRequestFailure(UpdateError::Timeout,
                         QStringLiteral("The update request timed out."));
}

void AutoUpdateChecker::finishStableResponse(const QByteArray &body)
{
    QJsonParseError error;
    const QJsonDocument json = QJsonDocument::fromJson(body, &error);
    if (error.error != QJsonParseError::NoError || !json.isArray()) {
        failForActiveOperation(UpdateError::InvalidMetadata,
                               QStringLiteral("Update release response is malformed."));
        return;
    }
    const ReleaseVersionSelection release =
        ReleaseVersionSelector::select(json.array());
    if (!release.valid) {
        clearCandidate();
        applyTransition(UpdateStateMachine::CandidateCurrent);
        return;
    }
    QVector<int> latest;
    QString version = release.version;
    parseStringToVersionQuad(version, latest);
    if (compareVersion(m_CurrentVersionQuad, latest) < 0) {
        emit onUpdateAvailable(release.version, release.url);
        RollingUpdateCandidate candidate;
        candidate.releaseLabel = release.version;
        candidate.releasePage = QUrl(release.url);
        setCandidate(candidate);
        applyTransition(UpdateStateMachine::CandidateAvailable);
    } else {
        clearCandidate();
        applyTransition(UpdateStateMachine::CandidateCurrent);
    }
}

void AutoUpdateChecker::finishRollingRelease(const QByteArray &body)
{
    const UpdateResult<RollingUpdateCandidate> parsed =
        RollingUpdateParser::parseRelease(body);
    if (!parsed.ok) {
        failForActiveOperation(parsed.error, parsed.message);
        return;
    }
    m_Release = parsed.value;
    issueRequest(m_Release.manifest.apiUrl, RollingManifest, ManifestLimit);
}

void AutoUpdateChecker::finishRollingManifest(const QByteArray &body)
{
    const UpdateResult<RollingUpdateCandidate> parsed =
        RollingUpdateParser::parseManifest(body, m_Release);
    if (!parsed.ok) {
        failForActiveOperation(parsed.error, parsed.message);
        return;
    }
    setCandidate(parsed.value);
    beginTagResolution(false);
}

void AutoUpdateChecker::beginTagResolution(bool revalidation)
{
    m_TagObjects.clear();
    issueRequest(QUrl(QString::fromLatin1(RollingTagReferenceUrl)),
                 revalidation ? RevalidateTagReference : RollingTagReference,
                 JsonLimit);
}

void AutoUpdateChecker::finishTagReference(const QByteArray &body,
                                           bool revalidation)
{
    const UpdateResult<RollingTagObject> parsed =
        RollingUpdateParser::parseTagReference(body);
    if (!parsed.ok) {
        failForActiveOperation(
            revalidation ? UpdateError::PublisherChanged : parsed.error,
            revalidation
                ? QStringLiteral("The rolling tag binding changed; retry the update.")
                : parsed.message);
        return;
    }
    m_TagReference = parsed.value;
    if (m_TagReference.type == QStringLiteral("commit")) {
        const UpdateResult<TagResolution> resolution =
            RollingUpdateParser::resolveTagCommit(m_TagReference, m_TagObjects);
        if (!resolution.ok) {
            failForActiveOperation(
                revalidation ? UpdateError::PublisherChanged : resolution.error,
                revalidation
                    ? QStringLiteral("The rolling tag binding changed; retry the update.")
                    : resolution.message);
            return;
        }
        if (revalidation) {
            const UpdateResult<bool> match =
                RollingUpdateParser::matchesTagResolution(
                    m_ExpectedCandidate, resolution.value);
            if (!match.ok) {
                failForActiveOperation(match.error, match.message);
                return;
            }
            finishRevalidation();
        } else {
            const UpdateResult<RollingUpdateCandidate> bound =
                RollingUpdateParser::bindTagResolution(m_Candidate,
                                                       resolution.value);
            if (!bound.ok) {
                failForActiveOperation(bound.error, bound.message);
                return;
            }
            setCandidate(bound.value);
            const QUrl compare(QString::fromLatin1(ComparePrefix)
                + BuildInfo::commit() + QStringLiteral("...")
                + m_Candidate.sourceCommit);
            issueRequest(compare, RollingCompare, JsonLimit);
        }
        return;
    }
    resolveCurrentTagObject(revalidation);
}

void AutoUpdateChecker::resolveCurrentTagObject(bool revalidation)
{
    QString objectId = m_TagReference.targetId;
    if (!m_TagObjects.isEmpty()) {
        objectId = m_TagObjects.constLast().targetId;
    }
    if (m_TagObjects.size() >= 16) {
        failForActiveOperation(UpdateError::InvalidMetadata,
                               QStringLiteral("The rolling tag chain is too deep."));
        return;
    }
    issueRequest(QUrl(QString::fromLatin1(GitTagObjectPrefix) + objectId),
                 revalidation ? RevalidateTagObjectRequest : RollingTagObjectRequest,
                 JsonLimit);
}

void AutoUpdateChecker::finishTagObject(const QByteArray &body,
                                        bool revalidation)
{
    const UpdateResult<RollingTagObject> parsed =
        RollingUpdateParser::parseTagObject(body);
    if (!parsed.ok) {
        failForActiveOperation(
            revalidation ? UpdateError::PublisherChanged : parsed.error,
            revalidation
                ? QStringLiteral("The rolling tag binding changed; retry the update.")
                : parsed.message);
        return;
    }
    m_TagObjects.append(parsed.value);
    if (parsed.value.type == QStringLiteral("tag")) {
        resolveCurrentTagObject(revalidation);
        return;
    }
    const UpdateResult<TagResolution> resolution =
        RollingUpdateParser::resolveTagCommit(m_TagReference, m_TagObjects);
    if (!resolution.ok) {
        failForActiveOperation(
            revalidation ? UpdateError::PublisherChanged : resolution.error,
            revalidation
                ? QStringLiteral("The rolling tag binding changed; retry the update.")
                : resolution.message);
        return;
    }
    if (revalidation) {
        const UpdateResult<bool> match =
            RollingUpdateParser::matchesTagResolution(
                m_ExpectedCandidate, resolution.value);
        if (!match.ok) {
            failForActiveOperation(match.error, match.message);
            return;
        }
        finishRevalidation();
    } else {
        const UpdateResult<RollingUpdateCandidate> bound =
            RollingUpdateParser::bindTagResolution(m_Candidate,
                                                   resolution.value);
        if (!bound.ok) {
            failForActiveOperation(bound.error, bound.message);
            return;
        }
        setCandidate(bound.value);
        issueRequest(QUrl(QString::fromLatin1(ComparePrefix)
                          + BuildInfo::commit() + QStringLiteral("...")
                          + m_Candidate.sourceCommit),
                     RollingCompare, JsonLimit);
    }
}

void AutoUpdateChecker::finishCompare(const QByteArray &body)
{
    const CommitRelation relation =
        RollingUpdateParser::parseCommitRelation(body);
    if (relation == CommitRelation::Unknown) {
        failForActiveOperation(
            UpdateError::InvalidMetadata,
            QStringLiteral("Unable to prove update ancestry from the GitHub comparison response."));
        return;
    }
    const UpdateResult<bool> installable =
        RollingUpdateParser::isInstallable(
            BuildInfo::commit(), BuildInfo::sequence(), m_Candidate,
            relation);
    if (!installable.ok) {
        clearCandidate();
        applyTransition(UpdateStateMachine::CandidateCurrent);
        return;
    }
    applyTransition(UpdateStateMachine::CandidateAvailable);
}

void AutoUpdateChecker::finishFlatpakDownload()
{
    if (m_BytesReceived != m_BytesTotal) {
        m_Temporary.reset();
        m_DownloadHash.reset();
        failForActiveOperation(UpdateError::SizeMismatch,
                               QStringLiteral("The update download size does not match."));
        return;
    }
    if (!applyTransition(UpdateStateMachine::DownloadComplete)) {
        m_DownloadHash.reset();
        return;
    }
    if (!m_DownloadHash) {
        m_Temporary.reset();
        failForActiveOperation(
            UpdateError::IoFailure,
            QStringLiteral("The update checksum state is unavailable."));
        return;
    }
    const QByteArray streamedDigest =
        m_DownloadHash->result().toHex();
    m_DownloadHash.reset();
    if (streamedDigest != m_Candidate.flatpak.sha256) {
        m_Temporary.reset();
        failForActiveOperation(
            UpdateError::DigestMismatch,
            QStringLiteral("The downloaded update checksum does not match."));
        return;
    }
    const UpdateResult<UpdateFileStore::OpenVerifiedFile> verified =
        m_Files->finalizeAndVerify(m_Temporary, m_Candidate);
    m_Temporary.reset();
    if (!verified.ok) {
        failForActiveOperation(verified.error, verified.message);
        return;
    }
    m_VerifiedFile = verified.value.file;
    m_DownloadedPath = verified.value.canonicalPath;
    emit downloadedPathChanged();
    m_ExpectedCandidate = m_Candidate;
    beginRevalidation(false);
}

void AutoUpdateChecker::beginRevalidation(bool restoring)
{
    m_Restoring = restoring;
    m_TagObjects.clear();
    issueRequest(QUrl(QString::fromLatin1(RollingReleaseUrl)),
                 RevalidateRelease, JsonLimit);
}

void AutoUpdateChecker::finishRevalidation()
{
    if (m_Restoring) {
        const UpdateResult<UpdateFileStore::OpenVerifiedFile> reopened =
            m_Files->reopenAndVerify(m_RestoreRecord, m_ExpectedCandidate);
        if (!reopened.ok) {
            if (reopened.error == UpdateError::InvalidMetadata
                    || reopened.error == UpdateError::UnsafePath
                    || reopened.error == UpdateError::SizeMismatch
                    || reopened.error == UpdateError::DigestMismatch
                    || reopened.error == UpdateError::PublisherChanged) {
                invalidatePendingAndCheck(reopened.message);
                return;
            }
            failForActiveOperation(reopened.error, reopened.message);
            return;
        }
        m_VerifiedFile = reopened.value.file;
        m_DownloadedPath = reopened.value.canonicalPath;
        emit downloadedPathChanged();
    } else {
        saveVerifiedPending();
        if (m_State != Verifying) {
            return;
        }
    }

    const UpdateStateMachine::Event passed =
        m_Session->mode() == SteamDeckSession::Desktop
        ? UpdateStateMachine::VerificationPassedDesktop
        : UpdateStateMachine::VerificationPassedNonDesktop;
    applyTransition(passed);
}

void AutoUpdateChecker::saveVerifiedPending()
{
    if (!m_VerifiedFile) {
        failForActiveOperation(UpdateError::IoFailure,
                               QStringLiteral("The verified update file is unavailable."));
        return;
    }
    PendingUpdateRecord record;
    record.canonicalPath = m_DownloadedPath;
    record.candidate = m_ExpectedCandidate;
    record.verifiedSize = m_ExpectedCandidate.flatpak.size;
    record.verifiedSha256 = m_ExpectedCandidate.flatpak.sha256;
    if (!m_Files->save(record)) {
        failForActiveOperation(UpdateError::IoFailure,
                               QStringLiteral("The pending update could not be saved."));
    }
}

void AutoUpdateChecker::handleUpdateCheckRequestFinished(QNetworkReply *reply)
{
    if (!reply || !reply->isFinished()) {
        return;
    }
    const QByteArray body = reply->readAll();
    reply->deleteLater();
    if (reply->error() != QNetworkReply::NoError) {
        return;
    }
    QJsonParseError error;
    const QJsonDocument json = QJsonDocument::fromJson(body, &error);
    if (error.error != QJsonParseError::NoError || !json.isArray()) {
        return;
    }
    const ReleaseVersionSelection release =
        ReleaseVersionSelector::select(json.array());
    if (!release.valid) {
        return;
    }
    QString version = release.version;
    QVector<int> latest;
    parseStringToVersionQuad(version, latest);
    if (compareVersion(m_CurrentVersionQuad, latest) < 0) {
        emit onUpdateAvailable(release.version, release.url);
    }
}

QString AutoUpdateChecker::rateLimitMessage(QNetworkReply *reply) const
{
    const QByteArray remaining = reply->rawHeader(QByteArrayLiteral("X-RateLimit-Remaining"));
    if (remaining != QByteArrayLiteral("0")) {
        return QString();
    }
    bool ok = false;
    const qlonglong reset =
        reply->rawHeader(QByteArrayLiteral("X-RateLimit-Reset")).toLongLong(&ok);
    if (!ok || reset <= 0) {
        return QStringLiteral("GitHub API rate limit reached. Try again later.");
    }
    const QDateTime resetAt =
        QDateTime::fromSecsSinceEpoch(reset).toUTC();
    return QStringLiteral("GitHub API rate limit reached. Try again after %1.")
        .arg(resetAt.toString(Qt::ISODate));
}

bool AutoUpdateChecker::supportsStableCheck() const
{
#if defined(Q_OS_WIN32) || defined(Q_OS_DARWIN) || defined(STEAM_LINK) || defined(APP_IMAGE)
    return true;
#else
    return false;
#endif
}

void AutoUpdateChecker::parseStringToVersionQuad(QString &string,
                                                 QVector<int> &version)
{
    int numericCoreEnd = string.size();
    const int prerelease = string.indexOf(QLatin1Char('-'));
    const int metadata = string.indexOf(QLatin1Char('+'));
    if (prerelease >= 0) numericCoreEnd = prerelease;
    if (metadata >= 0 && metadata < numericCoreEnd) numericCoreEnd = metadata;
    const QStringList list =
        string.left(numericCoreEnd).split(QLatin1Char('.'));
    for (const QString &component : list) {
        version.append(component.toInt());
    }
}

int AutoUpdateChecker::compareVersion(QVector<int> &left,
                                      QVector<int> &right)
{
    for (int index = 0;; ++index) {
        const int a = index < left.count() ? left.at(index) : 0;
        const int b = index < right.count() ? right.at(index) : 0;
        if (index >= left.count() && index >= right.count()) return 0;
        if (a < b) return -1;
        if (a > b) return 1;
    }
}
