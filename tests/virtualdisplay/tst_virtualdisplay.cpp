#include <QtTest>
#include <thread>
#include "streaming/sessionrecovery.h"

#include <QProcessEnvironment>
#include <QTemporaryFile>

#include <Limelight.h>

#include "backend/steamdecksession.h"
#include "settings/refreshrateparser.h"
#include "streaming/virtualdisplaylaunch.h"

class VirtualDisplayLaunchTest : public QObject
{
    Q_OBJECT

private slots:
    void suspendDetectorDistinguishesSleepFromScheduling();
    void suspendDetectorRejectsInvalidSamples();
    void recoveryRequiresCleanupAndAllowsOneResume();
    void recoveryCancelWinsAndRepeatedSessionsWork();
    void cleanupDecisionPreservesQuitAfterFailure();
    void resolveEffectiveSopsForcesNonNvidiaVirtualDisplay();
    void resolveEffectiveSopsDoesNotForceNvidia();
    void resolveEffectiveSopsPreservesGfeUnsupportedModeSafeguard();
    void resolveEffectiveSopsPreservesNonVirtualPreference();
    void maxConnectionAttemptsIsBoundedOnlyForNonNvidiaVirtualDisplay();
    void deckNativeDisplayPolicy_data();
    void deckNativeDisplayPolicy();
    void steamDeckIdentityRecognizesLcdAndOledBoards();
    void steamDeckModelClassifiesLcdAndOled();
    void explicitHdrPolicyRequiresHostAndClient10Bit();
    void retryLaunchesOnceAndUsesAllAttempts();
    void retryLaunchFailureProducesOneFinalCallback();
    void retryResetsFailureStateBeforeEveryAttempt();
    void retryWithoutFailureCallbackAlwaysEmitsGenericFinalError();
    void retryFinalStageFailureIsActionable();
    void retryTransientTerminationDoesNotBecomeSuccess();
    void retryDelayIsCancellable();
    void recoveryOnlyUsesResumeForTheExistingApp();
    void disconnectGracefulHostShowsNothing();
    void disconnectIntentionalLocalShowsNothing();
    void disconnectEarlyFailureSurfacesLegacyErrorOnly();
    void disconnectUnexpectedMidStreamSurfacesRecoveryOnly();
    void disconnectOutcomesAreMutuallyExclusiveAndCoverAllInputs();
    void retryWithFixedDelayReturnsAttemptOnSuccess();
    void retryWithFixedDelayReturnsZeroWhenEveryAttemptFails();
    void retryWithFixedDelayWaitsBetweenAttemptsOnly();
    void cancellationStartsUncancelledAndSurvivesCancelUntilReset();
    void cancellationResetBetweenAttemptsAllowsReconnectAfterDeactivation();
    void cancellationRacePreservesUserCancelAfterAttemptBegins();
    void cancellationLifecycleCoversInitialAttemptReconnectAndUserCancel();
    void connectionAttemptStateResetClearsAllSignals();
    void reconnectClassificationIgnoresLeftoverIntentionalAndStartedFlags();
    void gamescopeWsiEnablesOnlyInGamescopeSessionWithLayerPresent();
    void rtspSessionUrlFromStorageReturnsNullForEmptyLegacyUrl();
    void rtspSessionUrlFromStorageReturnsPointerIntoStorageForReturnedUrl();
    void rtspSessionUrlHandoffPopulatesHostInfoFromOwnedStorage();
    void rtspSessionUrlHandoffWithEmptyLegacyUrlLeavesHostInfoNull();
    void rtspSessionUrlHandoffSurvivesConnectRetriesWithReturnedUrl();
};

void VirtualDisplayLaunchTest::resolveEffectiveSopsForcesNonNvidiaVirtualDisplay()
{
    QVERIFY(VirtualDisplayLaunchPolicy::resolveEffectiveSops(true, false, true, false));
    QVERIFY(VirtualDisplayLaunchPolicy::resolveEffectiveSops(true, false, false, true));
}

void VirtualDisplayLaunchTest::resolveEffectiveSopsDoesNotForceNvidia()
{
    QVERIFY(VirtualDisplayLaunchPolicy::resolveEffectiveSops(true, true, true, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::resolveEffectiveSops(true, true, true, false));
}

void VirtualDisplayLaunchTest::resolveEffectiveSopsPreservesGfeUnsupportedModeSafeguard()
{
    QVERIFY(!VirtualDisplayLaunchPolicy::resolveEffectiveSops(false, true, false, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::resolveEffectiveSops(false, true, true, false));
    QVERIFY(VirtualDisplayLaunchPolicy::resolveEffectiveSops(false, true, true, true));
}

void VirtualDisplayLaunchTest::resolveEffectiveSopsPreservesNonVirtualPreference()
{
    QVERIFY(VirtualDisplayLaunchPolicy::resolveEffectiveSops(false, false, true, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::resolveEffectiveSops(false, false, true, false));
}

void VirtualDisplayLaunchTest::maxConnectionAttemptsIsBoundedOnlyForNonNvidiaVirtualDisplay()
{
    QCOMPARE(VirtualDisplayLaunchPolicy::maxConnectionAttempts(true, true), 1);
    QCOMPARE(VirtualDisplayLaunchPolicy::maxConnectionAttempts(false, false), 1);
    QCOMPARE(
        VirtualDisplayLaunchPolicy::maxConnectionAttempts(true, false),
        VirtualDisplayLaunchPolicy::kVirtualDisplayConnectionAttempts);
}

void VirtualDisplayLaunchTest::deckNativeDisplayPolicy_data()
{
    QTest::addColumn<bool>("settingEnabled");
    QTest::addColumn<bool>("useVirtualDisplay");
    QTest::addColumn<bool>("isSteamDeck");
    QTest::addColumn<bool>("isGamingMode");
    QTest::addColumn<int>("savedWidth");
    QTest::addColumn<int>("savedHeight");
    QTest::addColumn<int>("savedProtocolFps");
    QTest::addColumn<int>("detectedWidth");
    QTest::addColumn<int>("detectedHeight");
    QTest::addColumn<double>("detectedRefreshHz");
    QTest::addColumn<bool>("expectedApplied");
    QTest::addColumn<int>("expectedWidth");
    QTest::addColumn<int>("expectedHeight");
    QTest::addColumn<int>("expectedProtocolFps");

    QTest::newRow("off") << false << true << true << true << 1920 << 1080 << 60000 << 1280 << 800 << 90.0 << false << 1920 << 1080 << 60000;
    QTest::newRow("virtual disabled") << true << false << true << true << 1920 << 1080 << 60000 << 1280 << 800 << 90.0 << false << 1920 << 1080 << 60000;
    QTest::newRow("non deck") << true << true << false << true << 1920 << 1080 << 60000 << 1280 << 800 << 90.0 << false << 1920 << 1080 << 60000;
    QTest::newRow("desktop mode") << true << true << true << false << 1920 << 1080 << 60000 << 1280 << 800 << 90.0 << false << 1920 << 1080 << 60000;
    QTest::newRow("detection failure") << true << true << true << true << 1920 << 1080 << 60000 << 0 << 0 << 90.0 << false << 1920 << 1080 << 60000;
    QTest::newRow("40 Hz") << true << true << true << true << 1920 << 1080 << 60000 << 1280 << 720 << 40.0 << true << 1280 << 720 << 40000;
    QTest::newRow("45 Hz") << true << true << true << true << 1920 << 1080 << 60000 << 1024 << 768 << 45.0 << true << 1024 << 768 << 45000;
    QTest::newRow("60 Hz") << true << true << true << true << 1920 << 1080 << 90000 << 1280 << 800 << 60.0 << true << 1280 << 800 << 60000;
    QTest::newRow("90 Hz") << true << true << true << true << 1920 << 1080 << 60000 << 1280 << 800 << 90.0 << true << 1280 << 800 << 90000;
    QTest::newRow("fractional") << true << true << true << true << 1920 << 1080 << 60000 << 1280 << 800 << 45.75 << true << 1280 << 800 << 45750;
}

void VirtualDisplayLaunchTest::deckNativeDisplayPolicy()
{
    QFETCH(bool, settingEnabled);
    QFETCH(bool, useVirtualDisplay);
    QFETCH(bool, isSteamDeck);
    QFETCH(bool, isGamingMode);
    QFETCH(int, savedWidth);
    QFETCH(int, savedHeight);
    QFETCH(int, savedProtocolFps);
    QFETCH(int, detectedWidth);
    QFETCH(int, detectedHeight);
    QFETCH(double, detectedRefreshHz);
    QFETCH(bool, expectedApplied);
    QFETCH(int, expectedWidth);
    QFETCH(int, expectedHeight);
    QFETCH(int, expectedProtocolFps);

    const VirtualDisplayLaunchPolicy::DeckDisplayResolution result =
        VirtualDisplayLaunchPolicy::resolveSteamDeckNativeDisplay(
            settingEnabled,
            useVirtualDisplay,
            isSteamDeck,
            isGamingMode,
            savedWidth,
            savedHeight,
            savedProtocolFps,
            detectedWidth,
            detectedHeight,
            detectedRefreshHz);
    QCOMPARE(result.applied, expectedApplied);
    QCOMPARE(result.width, expectedWidth);
    QCOMPARE(result.height, expectedHeight);
    QCOMPARE(result.protocolFps, expectedProtocolFps);
}

void VirtualDisplayLaunchTest::steamDeckIdentityRecognizesLcdAndOledBoards()
{
    QVERIFY(SteamDeckSession::isSteamDeckIdentity(
        "Jupiter", "Valve Corporation", "Jupiter"));
    QVERIFY(SteamDeckSession::isSteamDeckIdentity(
        "Aerith", "Valve Corporation", "Aerith"));
    QVERIFY(SteamDeckSession::isSteamDeckIdentity(
        "Galileo", "Valve Corporation", "Galileo"));
    QVERIFY(SteamDeckSession::isSteamDeckIdentity(
        "Custom PC", "Valve Corporation", "Galileo"));
    QVERIFY(!SteamDeckSession::isSteamDeckIdentity(
        "90 Hz Panel", "Example Vendor", "Jupiter"));
}

void VirtualDisplayLaunchTest::steamDeckModelClassifiesLcdAndOled()
{
    QCOMPARE(SteamDeckSession::modelFromIdentity(
        "Jupiter", "Valve Corporation", "Jupiter"), SteamDeckSession::LCD);
    QCOMPARE(SteamDeckSession::modelFromIdentity(
        "Aerith", "Valve Corporation", "Aerith"), SteamDeckSession::LCD);
    QCOMPARE(SteamDeckSession::modelFromIdentity(
        "Galileo", "Valve Corporation", "Galileo"), SteamDeckSession::OLED);
    QCOMPARE(SteamDeckSession::modelFromIdentity(
        "Jupiter", "Example Vendor", "Jupiter"), SteamDeckSession::NotSteamDeck);
    QCOMPARE(SteamDeckSession::modelFromIdentity(
        "Custom PC", "Valve Corporation", "Galileo"), SteamDeckSession::OLED);
    QCOMPARE(SteamDeckSession::modelFromIdentity(
        "Jupiter", "Valve Corporation", ""), SteamDeckSession::LCD);

    // Generic "Steam Deck" product name with no board info: identity is true
    // but no exact codename matches, so the fallback must classify as LCD.
    QCOMPARE(SteamDeckSession::modelFromIdentity(
        "Steam Deck", "Valve Corporation", ""), SteamDeckSession::LCD);

    // Known LCD product name with an unrecognised board name: product match
    // wins, so model is LCD.
    QCOMPARE(SteamDeckSession::modelFromIdentity(
        "Jupiter", "Valve Corporation", "SomeUnknownBoard"), SteamDeckSession::LCD);

    // Parity loop: every triple used by steamDeckIdentityRecognizesLcdAndOledBoards()
    // (plus the two new fall-through cases) must agree that "is a Steam Deck"
    // and "modelFromIdentity != NotSteamDeck" are the same predicate. Any
    // future divergence between the two functions will be caught here with a
    // triple-named failure message.
    struct IdentityTriple {
        const char *product;
        const char *vendor;
        const char *board;
    };
    const IdentityTriple triples[] = {
        {"Jupiter",          "Valve Corporation", "Jupiter"},
        {"Aerith",           "Valve Corporation", "Aerith"},
        {"Galileo",          "Valve Corporation", "Galileo"},
        {"Custom PC",        "Valve Corporation", "Galileo"},
        {"90 Hz Panel",      "Example Vendor",    "Jupiter"},
        {"Steam Deck",       "Valve Corporation", ""},
        {"Jupiter",          "Valve Corporation", "SomeUnknownBoard"},
    };
    for (const IdentityTriple &triple : triples) {
        const bool recognizedIdentity =
            SteamDeckSession::isSteamDeckIdentity(
                QString::fromLatin1(triple.product),
                QString::fromLatin1(triple.vendor),
                QString::fromLatin1(triple.board));
        const bool classifiedAsDeck =
            SteamDeckSession::modelFromIdentity(
                QString::fromLatin1(triple.product),
                QString::fromLatin1(triple.vendor),
                QString::fromLatin1(triple.board))
            != SteamDeckSession::NotSteamDeck;
        QVERIFY2(recognizedIdentity == classifiedAsDeck,
                 qPrintable(QStringLiteral(
                     "modelFromIdentity parity diverged from isSteamDeckIdentity for "
                     "product='%1' vendor='%2' board='%3'")
                     .arg(QString::fromLatin1(triple.product))
                     .arg(QString::fromLatin1(triple.vendor))
                     .arg(QString::fromLatin1(triple.board))));
    }
}

void VirtualDisplayLaunchTest::explicitHdrPolicyRequiresHostAndClient10Bit()
{
    QVERIFY(VirtualDisplayLaunchPolicy::explicitHdrCanStart(false, false, false));
    QVERIFY(VirtualDisplayLaunchPolicy::explicitHdrCanStart(true, true, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::explicitHdrCanStart(true, true, false));
    QVERIFY(!VirtualDisplayLaunchPolicy::explicitHdrCanStart(true, false, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::explicitHdrCanStart(true, false, false));
}

void VirtualDisplayLaunchTest::retryLaunchesOnceAndUsesAllAttempts()
{
    int launchCount = 0;
    int connectCount = 0;
    int waitCount = 0;
    int preparingCount = 0;
    int finalStageCount = 0;
    int finalGenericCount = 0;
    int launchFailedCount = 0;
    int cancellationCount = 0;
    int resetCount = 0;
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;

    const VirtualDisplayLaunchPolicy::ConnectionRetryOperations operations = {
        [&launchCount]() {
            launchCount++;
            return true;
        },
        [&state, &connectCount, &resetCount](int) {
            resetCount++;
            connectCount++;
            if (connectCount == 1) {
                state.setStageFailure(7, -7);
                return -7;
            }
            if (connectCount == 3) {
                return 0;
            }
            return -8;
        },
        [&waitCount](int) {
            waitCount++;
        }
    };
    const VirtualDisplayLaunchPolicy::ConnectionRetryCallbacks callbacks = {
        [&preparingCount]() {
            preparingCount++;
        },
        [&launchFailedCount]() {
            launchFailedCount++;
        },
        [&finalStageCount](int, int) {
            finalStageCount++;
        },
        [&finalGenericCount]() {
            finalGenericCount++;
        },
        [&cancellationCount]() {
            cancellationCount++;
        }
    };

    QVERIFY(VirtualDisplayLaunchPolicy::runConnectionAttempts(
        VirtualDisplayLaunchPolicy::kVirtualDisplayConnectionAttempts,
        state,
        cancellation,
        operations,
        callbacks));
    QCOMPARE(launchCount, 1);
    QCOMPARE(connectCount, 3);
    QCOMPARE(waitCount, 120);
    QCOMPARE(resetCount, 3);
    QCOMPARE(preparingCount, 1);
    QCOMPARE(finalStageCount, 0);
    QCOMPARE(finalGenericCount, 0);
    QCOMPARE(launchFailedCount, 0);
    QCOMPARE(cancellationCount, 0);
}

void VirtualDisplayLaunchTest::retryLaunchFailureProducesOneFinalCallback()
{
    int launchCount = 0;
    int connectCount = 0;
    int launchFailedCount = 0;
    int finalGenericCount = 0;
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;

    const bool connected = VirtualDisplayLaunchPolicy::runConnectionAttempts(
        3,
        state,
        cancellation,
        {
            [&launchCount]() {
                launchCount++;
                return false;
            },
            [&connectCount](int) {
                connectCount++;
                return -1;
            },
            [](int) {}
        },
        {
            []() {},
            [&launchFailedCount]() {
                launchFailedCount++;
            },
            [](int, int) {},
            [&finalGenericCount]() {
                finalGenericCount++;
            },
            []() {}
        });

    QVERIFY(!connected);
    QCOMPARE(launchCount, 1);
    QCOMPARE(connectCount, 0);
    QCOMPARE(launchFailedCount, 1);
    QCOMPARE(finalGenericCount, 0);
}

void VirtualDisplayLaunchTest::retryResetsFailureStateBeforeEveryAttempt()
{
    int connectCount = 0;
    int waitCount = 0;
    int genericCount = 0;
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;

    const bool connected = VirtualDisplayLaunchPolicy::runConnectionAttempts(
        3,
        state,
        cancellation,
        {
            []() -> bool { return true; },
            [&state, &connectCount](int) {
                if (connectCount++ == 0) {
                    state.setStageFailure(4, -4);
                }
                return -1;
            },
            [&waitCount](int) {
                waitCount++;
            }
        },
        {
            []() {},
            []() {},
            [](int, int) {},
            [&genericCount]() {
                genericCount++;
            },
            []() {}
        });

    QVERIFY(!connected);
    QCOMPARE(waitCount, 120);
    QCOMPARE(genericCount, 1);
}

void VirtualDisplayLaunchTest::retryWithoutFailureCallbackAlwaysEmitsGenericFinalError()
{
    int connectCount = 0;
    int waitCount = 0;
    int genericCount = 0;
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;

    QVERIFY(!VirtualDisplayLaunchPolicy::runConnectionAttempts(
        3,
        state,
        cancellation,
        {
            []() -> bool { return true; },
            [&connectCount](int) {
                connectCount++;
                return -1;
            },
            [&waitCount](int) {
                waitCount++;
            }
        },
        {
            []() {},
            []() {},
            [](int, int) {},
            [&genericCount]() {
                genericCount++;
            },
            []() {}
        }));

    QCOMPARE(connectCount, 3);
    QCOMPARE(waitCount, 120);
    QCOMPARE(genericCount, 1);
    QVERIFY(!state.hasStageFailure());
    QCOMPARE(state.stage(), -1);
    QCOMPARE(state.stageErrorCode(), 0);
}

void VirtualDisplayLaunchTest::retryFinalStageFailureIsActionable()
{
    int finalStageCount = 0;
    int capturedStage = -1;
    int capturedError = 0;
    int genericCount = 0;
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;

    const bool connected = VirtualDisplayLaunchPolicy::runConnectionAttempts(
        1,
        state,
        cancellation,
        {
            []() -> bool { return true; },
            [&state](int) {
                state.setStageFailure(4, -4);
                return -1;
            },
            [](int) {}
        },
        {
            []() {},
            []() {},
            [&finalStageCount, &capturedStage, &capturedError](int stage, int error) {
                finalStageCount++;
                capturedStage = stage;
                capturedError = error;
            },
            [&genericCount]() {
                genericCount++;
            },
            []() {}
        });

    QVERIFY(!connected);
    QCOMPARE(finalStageCount, 1);
    QCOMPARE(capturedStage, 4);
    QCOMPARE(capturedError, -4);
    QCOMPARE(genericCount, 0);
}

void VirtualDisplayLaunchTest::retryTransientTerminationDoesNotBecomeSuccess()
{
    int genericCount = 0;
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;

    const bool connected = VirtualDisplayLaunchPolicy::runConnectionAttempts(
        1,
        state,
        cancellation,
        {
            []() -> bool { return true; },
            [&state](int) {
                state.setConnectionTermination();
                return 0;
            },
            [](int) {}
        },
        {
            []() {},
            []() {},
            [](int, int) {},
            [&genericCount]() {
                genericCount++;
            },
            []() {}
        });

    QVERIFY(!connected);
    QCOMPARE(genericCount, 1);
}

void VirtualDisplayLaunchTest::retryDelayIsCancellable()
{
    int connectCount = 0;
    int waitCount = 0;
    int cancellationCount = 0;
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;

    const bool connected = VirtualDisplayLaunchPolicy::runConnectionAttempts(
        3,
        state,
        cancellation,
        {
            []() -> bool { return true; },
            [&connectCount](int) {
                connectCount++;
                return -1;
            },
            [&waitCount, &cancellation](int) {
                waitCount++;
                cancellation.cancel();
            }
        },
        {
            []() {},
            []() {},
            [](int, int) {},
            []() {},
            [&cancellationCount]() {
                cancellationCount++;
            }
        });

    QVERIFY(!connected);
    QCOMPARE(connectCount, 1);
    QCOMPARE(waitCount, 1);
    QCOMPARE(cancellationCount, 1);
}

void VirtualDisplayLaunchTest::recoveryOnlyUsesResumeForTheExistingApp()
{
    QVERIFY(VirtualDisplayLaunchPolicy::isRecoverableTermination(-100));
    QVERIFY(!VirtualDisplayLaunchPolicy::isRecoverableTermination(0));
    QVERIFY(VirtualDisplayLaunchPolicy::canResumeExistingHostSession(42, 42));
    QVERIFY(!VirtualDisplayLaunchPolicy::canResumeExistingHostSession(42, 0));
    QVERIFY(!VirtualDisplayLaunchPolicy::canResumeExistingHostSession(0, 42));
}

void VirtualDisplayLaunchTest::disconnectGracefulHostShowsNothing()
{
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(0, false, false));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(0, false, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(0, true, false));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(0, true, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceError(0, false, false));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceError(0, false, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceError(0, true, false));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceError(0, true, true));
}

void VirtualDisplayLaunchTest::disconnectIntentionalLocalShowsNothing()
{
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(-100, true, false));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(-100, true, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(1017, true, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceError(-100, true, false));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceError(-100, true, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceError(1017, true, true));
}

void VirtualDisplayLaunchTest::disconnectEarlyFailureSurfacesLegacyErrorOnly()
{
    QVERIFY(VirtualDisplayLaunchPolicy::shouldSurfaceError(-100, false, false));
    QVERIFY(VirtualDisplayLaunchPolicy::shouldSurfaceError(1017, false, false));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(-100, false, false));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(1017, false, false));
}

void VirtualDisplayLaunchTest::disconnectUnexpectedMidStreamSurfacesRecoveryOnly()
{
    QVERIFY(VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(-100, false, true));
    QVERIFY(VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(-200, false, true));
    QVERIFY(VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(1017, false, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceError(-100, false, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceError(1017, false, true));
}

void VirtualDisplayLaunchTest::disconnectOutcomesAreMutuallyExclusiveAndCoverAllInputs()
{
    struct Sample {
        int errorCode;
        bool intentional;
        bool started;
    };
    const Sample samples[] = {
        {0, false, false},
        {0, false, true},
        {0, true, false},
        {0, true, true},
        {-100, false, false},
        {-100, false, true},
        {-100, true, false},
        {-100, true, true},
        {1017, false, true},
        {1017, true, true},
    };

    for (const Sample& sample : samples) {
        const bool recovery = VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(
            sample.errorCode, sample.intentional, sample.started);
        const bool legacyError = VirtualDisplayLaunchPolicy::shouldSurfaceError(
            sample.errorCode, sample.intentional, sample.started);
        QVERIFY2(!recovery || !legacyError,
                 "Recovery and legacy error dialog must never both fire.");
    }
}

void VirtualDisplayLaunchTest::retryWithFixedDelayReturnsAttemptOnSuccess()
{
    int attempts = 0;
    int waits = 0;
    const int attempt = VirtualDisplayLaunchPolicy::retryWithFixedDelay(
        6,
        [&attempts](int) {
            attempts++;
            return attempts == 3;
        },
        [&waits]() {
            waits++;
        });
    QCOMPARE(attempt, 3);
    QCOMPARE(attempts, 3);
    QCOMPARE(waits, 2);
}

void VirtualDisplayLaunchTest::retryWithFixedDelayReturnsZeroWhenEveryAttemptFails()
{
    int attempts = 0;
    int waits = 0;
    const int attempt = VirtualDisplayLaunchPolicy::retryWithFixedDelay(
        6,
        [&attempts](int) {
            attempts++;
            return false;
        },
        [&waits]() {
            waits++;
        });
    QCOMPARE(attempt, 0);
    QCOMPARE(attempts, 6);
    QCOMPARE(waits, 5);
}

void VirtualDisplayLaunchTest::retryWithFixedDelayWaitsBetweenAttemptsOnly()
{
    int waits = 0;
    const int attempt = VirtualDisplayLaunchPolicy::retryWithFixedDelay(
        1,
        [](int) { return false; },
        [&waits]() {
            waits++;
        });
    QCOMPARE(attempt, 0);
    QCOMPARE(waits, 0);
}

void VirtualDisplayLaunchTest::cancellationStartsUncancelledAndSurvivesCancelUntilReset()
{
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;
    QVERIFY(!cancellation.isCancelled());
    cancellation.cancel();
    QVERIFY(cancellation.isCancelled());
    cancellation.reset();
    QVERIFY(!cancellation.isCancelled());
}

void VirtualDisplayLaunchTest::cancellationResetBetweenAttemptsAllowsReconnectAfterDeactivation()
{
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;
    int connectCount = 0;

    const VirtualDisplayLaunchPolicy::ConnectionRetryOperations operations = {
        []() -> bool { return true; },
        [&connectCount](int) {
            connectCount++;
            return connectCount == 4 ? 0 : -1;
        },
        [](int) {}
    };
    const VirtualDisplayLaunchPolicy::ConnectionRetryCallbacks callbacks = {
        []() {}, []() {}, [](int, int) {}, []() {}, []() {}
    };

    // First authorized attempt: succeeds on the third connect.
    QVERIFY(!VirtualDisplayLaunchPolicy::runConnectionAttempts(
        3, state, cancellation, operations, callbacks));
    QCOMPARE(connectCount, 3);

    // Simulate StreamSegue deactivating after the successful stream:
    // cancelRetry() leaves the flag set.
    cancellation.cancel();
    QVERIFY(cancellation.isCancelled());

    // A subsequent one-tap reconnect must reset cancellation at the start
    // of the authorized attempt; otherwise runConnectionAttempts would
    // return false immediately and the user would see a misleading
    // "cancelled" outcome. We model Session::startConnectionAsync()'s
    // m_RetryCancellation.reset() right before kicking off the next run.
    cancellation.reset();
    QVERIFY(VirtualDisplayLaunchPolicy::runConnectionAttempts(
        3, state, cancellation, operations, callbacks));
    QCOMPARE(connectCount, 4);
}

void VirtualDisplayLaunchTest::cancellationRacePreservesUserCancelAfterAttemptBegins()
{
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;
    int cancellationCount = 0;

    // Simulate the authorized attempt having already started: cancellation
    // was reset before runConnectionAttempts was called. From this point
    // forward, any cancel() must be observed - we must not clear it again
    // until the next authorized attempt.
    cancellation.reset();

    const VirtualDisplayLaunchPolicy::ConnectionRetryOperations operations = {
        []() -> bool { return true; },
        [](int) { return -1; },
        [&cancellation](int) { cancellation.cancel(); }
    };
    const VirtualDisplayLaunchPolicy::ConnectionRetryCallbacks callbacks = {
        []() {}, []() {}, [](int, int) {}, []() {},
        [&cancellationCount]() { cancellationCount++; }
    };

    QVERIFY(!VirtualDisplayLaunchPolicy::runConnectionAttempts(
        2, state, cancellation, operations, callbacks));
    QCOMPARE(cancellationCount, 1);
    QVERIFY(cancellation.isCancelled());
}

void VirtualDisplayLaunchTest::cancellationLifecycleCoversInitialAttemptReconnectAndUserCancel()
{
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;
    int connectCount = 0;

    const VirtualDisplayLaunchPolicy::ConnectionRetryOperations operations = {
        []() -> bool { return true; },
        [&connectCount](int) {
            connectCount++;
            return 0;
        },
        [](int) {}
    };
    const VirtualDisplayLaunchPolicy::ConnectionRetryCallbacks callbacks = {
        []() {}, []() {}, [](int, int) {}, []() {}, []() {}
    };

    // 1. Initial authorized attempt succeeds.
    cancellation.reset();
    QVERIFY(VirtualDisplayLaunchPolicy::runConnectionAttempts(
        3, state, cancellation, operations, callbacks));
    QCOMPARE(connectCount, 1);

    // 2. StreamSegue deactivates after a successful stream; cancelRetry()
    //    marks the cancellation flag set.
    cancellation.cancel();
    QVERIFY(cancellation.isCancelled());

    // 3. One-tap reconnect: cancellation must be reset at the start of the
    //    authorized attempt before any callback can observe it.
    cancellation.reset();
    QVERIFY(VirtualDisplayLaunchPolicy::runConnectionAttempts(
        3, state, cancellation, operations, callbacks));
    QCOMPARE(connectCount, 2);

    // 4. Unexpected host termination during the stream would not flip this
    //    flag. The reconnect above already cleared the deactivation cancel.
    QVERIFY(!cancellation.isCancelled());

    // 5. A genuine user cancel mid-attempt is still honoured because we do
    //    not touch the flag again until the next authorized attempt.
    cancellation.cancel();
    QVERIFY(!VirtualDisplayLaunchPolicy::runConnectionAttempts(
        3, state, cancellation, operations, callbacks));
    QCOMPARE(connectCount, 2);
}

void VirtualDisplayLaunchTest::connectionAttemptStateResetClearsAllSignals()
{
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    state.setStageFailure(7, -7);
    state.setConnectionTermination();
    QVERIFY(state.hasStageFailure());
    QCOMPARE(state.stage(), 7);
    QCOMPARE(state.stageErrorCode(), -7);
    QVERIFY(state.hasConnectionTermination());

    state.reset();
    QVERIFY(!state.hasStageFailure());
    QVERIFY(!state.hasConnectionTermination());
    QCOMPARE(state.stage(), -1);
    QCOMPARE(state.stageErrorCode(), 0);
}

void VirtualDisplayLaunchTest::reconnectClassificationIgnoresLeftoverIntentionalAndStartedFlags()
{
    // After a successful initial stream followed by an intentional disconnect
    // (user clicked Disconnect in the Quick Menu), reconnectSession clears
    // both m_IntentionalDisconnect and m_UnexpectedTermination before the
    // preflight /startApp /resume. While the preflight is in flight, the
    // cancellation reset has already happened, so the *next* surface call
    // (e.g. clConnectionTerminated for a true mid-stream failure) must be
    // allowed to surface recovery. We pin that the policy would *not*
    // suppress recovery for an unexpected mid-stream error purely because of
    // a leftover intentional flag.
    QVERIFY(VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(-100, false, true));
    QVERIFY(!VirtualDisplayLaunchPolicy::shouldSurfaceRecovery(-100, true, true));
}

void VirtualDisplayLaunchTest::gamescopeWsiEnablesOnlyInGamescopeSessionWithLayerPresent()
{
    QTemporaryFile layerFile;
    QVERIFY(layerFile.open());
    const QString existingPath = layerFile.fileName();
    const QString missingPath = existingPath + QLatin1String(".does.not.exist");

    {
        QProcessEnvironment gamescopeEnv;
        gamescopeEnv.insert(QStringLiteral("GAMESCOPE_WAYLAND_DISPLAY"), QStringLiteral("wayland-0"));
        QVERIFY(SteamDeckSession::shouldEnableGamescopeWsi(gamescopeEnv, existingPath));
    }

    {
        QProcessEnvironment gamescopeEnv;
        gamescopeEnv.insert(QStringLiteral("GAMESCOPE_WAYLAND_DISPLAY"), QStringLiteral("wayland-0"));
        QVERIFY(!SteamDeckSession::shouldEnableGamescopeWsi(gamescopeEnv, missingPath));
    }

    {
        QProcessEnvironment kdeEnv;
        kdeEnv.insert(QStringLiteral("XDG_CURRENT_DESKTOP"), QStringLiteral("KDE"));
        kdeEnv.insert(QStringLiteral("KDE_FULL_SESSION"), QStringLiteral("true"));
        kdeEnv.insert(QStringLiteral("XDG_SESSION_TYPE"), QStringLiteral("wayland"));
        QVERIFY(!SteamDeckSession::shouldEnableGamescopeWsi(kdeEnv, existingPath));
    }

    {
        QProcessEnvironment gamescopeEnv;
        gamescopeEnv.insert(QStringLiteral("GAMESCOPE_WAYLAND_DISPLAY"), QStringLiteral("wayland-0"));
        gamescopeEnv.insert(QStringLiteral("ENABLE_GAMESCOPE_WSI"), QStringLiteral("0"));
        QVERIFY(!SteamDeckSession::shouldEnableGamescopeWsi(gamescopeEnv, existingPath));
    }

    {
        QProcessEnvironment gamescopeEnv;
        gamescopeEnv.insert(QStringLiteral("GAMESCOPE_WAYLAND_DISPLAY"), QStringLiteral("wayland-0"));
        gamescopeEnv.insert(QStringLiteral("DISABLE_GAMESCOPE_WSI"), QStringLiteral("1"));
        QVERIFY(!SteamDeckSession::shouldEnableGamescopeWsi(gamescopeEnv, existingPath));
    }
}

void VirtualDisplayLaunchTest::rtspSessionUrlFromStorageReturnsNullForEmptyLegacyUrl()
{
    // Empty storage -> nullptr (legacy Sunshine / old GFE no-URL path).
    QCOMPARE(
        VirtualDisplayLaunchPolicy::rtspSessionUrlFromStorage(QByteArray()),
        static_cast<const char*>(nullptr));
}

void VirtualDisplayLaunchTest::rtspSessionUrlFromStorageReturnsPointerIntoStorageForReturnedUrl()
{
    // Non-empty storage -> pointer into the same QByteArray so callers
    // can rely on bytewise lifetime for retry attempts.
    const QByteArray storage =
        QByteArrayLiteral("rtspenc://example.local:9999/1234abcd");
    const char* returned =
        VirtualDisplayLaunchPolicy::rtspSessionUrlFromStorage(storage);
    QVERIFY(returned != nullptr);
    QCOMPARE(returned, storage.constData());
    QCOMPARE(QByteArray(returned), storage);
}

void VirtualDisplayLaunchTest::rtspSessionUrlHandoffPopulatesHostInfoFromOwnedStorage()
{
    // Mirror the production wiring: launch mirrors /launch URL into a
    // QByteArray; connect repopulates hostInfo.rtspSessionUrl from that
    // storage before LiStartConnection() would be invoked.
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;
    QByteArray rtspSessionUrlStorage;
    QString rtspSessionUrl;
    SERVER_INFORMATION hostInfo = {};
    const QByteArray returnedUrl =
        QByteArrayLiteral("rtspenc://deck-host.local:9998/deadbeef");

    const VirtualDisplayLaunchPolicy::ConnectionRetryOperations operations = {
        [&rtspSessionUrl, &rtspSessionUrlStorage, returnedUrl]() {
            rtspSessionUrl = QString::fromLatin1(returnedUrl);
            if (!rtspSessionUrl.isEmpty()) {
                rtspSessionUrlStorage = rtspSessionUrl.toLatin1();
            }
            return true;
        },
        [&hostInfo, &rtspSessionUrlStorage](int) {
            hostInfo.rtspSessionUrl =
                VirtualDisplayLaunchPolicy::rtspSessionUrlFromStorage(
                    rtspSessionUrlStorage);
            return 0;
        },
        [](int) {}
    };
    const VirtualDisplayLaunchPolicy::ConnectionRetryCallbacks callbacks = {
        nullptr, nullptr, nullptr, nullptr, nullptr
    };

    QVERIFY(VirtualDisplayLaunchPolicy::runConnectionAttempts(
        3, state, cancellation, operations, callbacks));

    // Pointer must point into the still-live QByteArray and equal the URL.
    QVERIFY(hostInfo.rtspSessionUrl != nullptr);
    QCOMPARE(QByteArray(hostInfo.rtspSessionUrl), returnedUrl);
    // Pointer-stability: appending to the source QString must not dangle it.
    rtspSessionUrl.append(QLatin1String("discarded"));
    QVERIFY(hostInfo.rtspSessionUrl != nullptr);
    QCOMPARE(QByteArray(hostInfo.rtspSessionUrl), returnedUrl);
}

void VirtualDisplayLaunchTest::rtspSessionUrlHandoffWithEmptyLegacyUrlLeavesHostInfoNull()
{
    // /launch returns no URL -> storage stays empty -> hostInfo.rtspSessionUrl
    // must remain nullptr across every retry.
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;
    QByteArray rtspSessionUrlStorage;
    QString rtspSessionUrl;
    SERVER_INFORMATION hostInfo = {};

    int connectCount = 0;
    QByteArray urlSeenAtAttempt[3] = { QByteArray(), QByteArray(), QByteArray() };
    bool nullAtAttempt[3] = { false, false, false };

    const VirtualDisplayLaunchPolicy::ConnectionRetryOperations operations = {
        [&rtspSessionUrl, &rtspSessionUrlStorage]() {
            rtspSessionUrl.clear();
            rtspSessionUrlStorage.clear();
            return true;
        },
        [&hostInfo, &rtspSessionUrlStorage, &connectCount,
         &urlSeenAtAttempt, &nullAtAttempt](int) {
            connectCount++;
            hostInfo.rtspSessionUrl =
                VirtualDisplayLaunchPolicy::rtspSessionUrlFromStorage(
                    rtspSessionUrlStorage);
            int idx = connectCount - 1;
            if (idx >= 0 && idx < 3) {
                nullAtAttempt[idx] = (hostInfo.rtspSessionUrl == nullptr);
                urlSeenAtAttempt[idx] =
                    hostInfo.rtspSessionUrl
                        ? QByteArray(hostInfo.rtspSessionUrl)
                        : QByteArray();
            }
            return connectCount == 3 ? 0 : -1;
        },
        [](int) {}
    };
    const VirtualDisplayLaunchPolicy::ConnectionRetryCallbacks callbacks = {
        nullptr, nullptr, nullptr, nullptr, nullptr
    };

    QVERIFY(VirtualDisplayLaunchPolicy::runConnectionAttempts(
        3, state, cancellation, operations, callbacks));

    QVERIFY(rtspSessionUrlStorage.isEmpty());
    QCOMPARE(connectCount, 3);
    QCOMPARE(hostInfo.rtspSessionUrl, static_cast<const char*>(nullptr));
    for (int i = 0; i < 3; i++) {
        QVERIFY2(nullAtAttempt[i], qPrintable(QStringLiteral(
            "attempt %1 must see nullptr URL, got '%2'")
            .arg(i + 1).arg(QString::fromLatin1(urlSeenAtAttempt[i]))));
        QVERIFY(urlSeenAtAttempt[i].isEmpty());
    }
}

void VirtualDisplayLaunchTest::rtspSessionUrlHandoffSurvivesConnectRetriesWithReturnedUrl()
{
    // /launch returns a URL but the first two connects fail. The URL bytes
    // must remain alive and pointed-to by hostInfo.rtspSessionUrl at every
    // attempt, including the successful third.
    VirtualDisplayLaunchPolicy::ConnectionAttemptState state;
    VirtualDisplayLaunchPolicy::ConnectionCancellation cancellation;
    QByteArray rtspSessionUrlStorage;
    QString rtspSessionUrl;
    SERVER_INFORMATION hostInfo = {};
    const QByteArray returnedUrl =
        QByteArrayLiteral("rtspenc://deck-host.local:9998/abcd1234");

    int launchCount = 0;
    int connectCount = 0;
    QByteArray urlSeenAtAttempt[3];
    bool nonNullAtAttempt[3] = { false, false, false };

    const VirtualDisplayLaunchPolicy::ConnectionRetryOperations operations = {
        [&rtspSessionUrl, &rtspSessionUrlStorage, &launchCount, returnedUrl]() {
            launchCount++;
            rtspSessionUrl = QString::fromLatin1(returnedUrl);
            if (!rtspSessionUrl.isEmpty()) {
                rtspSessionUrlStorage = rtspSessionUrl.toLatin1();
            }
            return true;
        },
        [&hostInfo, &rtspSessionUrlStorage, &connectCount,
         &urlSeenAtAttempt, &nonNullAtAttempt](int) {
            connectCount++;
            hostInfo.rtspSessionUrl =
                VirtualDisplayLaunchPolicy::rtspSessionUrlFromStorage(
                    rtspSessionUrlStorage);
            int idx = connectCount - 1;
            if (idx >= 0 && idx < 3) {
                nonNullAtAttempt[idx] = (hostInfo.rtspSessionUrl != nullptr);
                urlSeenAtAttempt[idx] =
                    hostInfo.rtspSessionUrl
                        ? QByteArray(hostInfo.rtspSessionUrl)
                        : QByteArray();
            }
            return connectCount < 3 ? -1 : 0;
        },
        [](int) {}
    };
    const VirtualDisplayLaunchPolicy::ConnectionRetryCallbacks callbacks = {
        nullptr, nullptr, nullptr, nullptr, nullptr
    };

    QVERIFY(VirtualDisplayLaunchPolicy::runConnectionAttempts(
        3, state, cancellation, operations, callbacks));

    QCOMPARE(launchCount, 1);
    QCOMPARE(connectCount, 3);
    for (int i = 0; i < 3; i++) {
        QVERIFY2(nonNullAtAttempt[i], qPrintable(QStringLiteral(
            "attempt %1 must see non-null URL").arg(i + 1)));
        QCOMPARE(urlSeenAtAttempt[i], returnedUrl);
    }
    QCOMPARE(QByteArray(hostInfo.rtspSessionUrl), returnedUrl);
    QCOMPARE(hostInfo.rtspSessionUrl, rtspSessionUrlStorage.constData());
}

void VirtualDisplayLaunchTest::suspendDetectorDistinguishesSleepFromScheduling()
{
    SessionRecovery::SuspendDetector detector;
    // Zero is a valid offset. A long stall advances both clocks equally.
    QVERIFY(!detector.sample(0, 0));
    QVERIFY(!detector.sample(30000000000LL, 30000000000LL));
    // A timespec second boundary is ordinary elapsed time, not an underflow.
    QVERIFY(!detector.sample(30999000000LL, 30999000000LL));
    QVERIFY(!detector.sample(31001000000LL, 31001000000LL));
    QVERIFY(detector.sample(37001000000LL, 32001000000LL));
    QVERIFY(!detector.sample(38001000000LL, 33001000000LL));
    QVERIFY(detector.sample(45001000000LL, 34001000000LL));
}

void VirtualDisplayLaunchTest::suspendDetectorRejectsInvalidSamples()
{
    SessionRecovery::SuspendDetector detector;
    QVERIFY(!detector.sample(0, 0));
    QVERIFY(!detector.sample(-1, -1, false));
    QVERIFY(!detector.sample(10000000000LL, 1000000000LL)); // new baseline
    QVERIFY(!detector.sample(0, 100));
    QVERIFY(!detector.sample(0, 0)); // reset clock, no wake
    QVERIFY(!detector.sample(500000000LL, 0)); // sub-threshold drift
}

void VirtualDisplayLaunchTest::recoveryRequiresCleanupAndAllowsOneResume()
{
    SessionRecovery::RecoveryGate gate;
    QVERIFY(!gate.takeResume());
    QVERIFY(gate.beginTermination(true));
    QVERIFY(!gate.beginTermination(true));
    QVERIFY(!gate.takeResume());
    gate.beginCleanup();
    QVERIFY(!gate.takeResume());
    gate.completeCleanup();
    QVERIFY(gate.canResume());
    std::atomic<int> winners{0};
    auto take = [&] { if (gate.takeResume()) ++winners; };
    std::thread first(take), second(take);
    first.join(); second.join();
    QCOMPARE(winners.load(), 1);
    QVERIFY(!gate.canResume());
    QVERIFY(!gate.takeResume());
}

void VirtualDisplayLaunchTest::recoveryCancelWinsAndRepeatedSessionsWork()
{
    SessionRecovery::RecoveryGate cancelled;
    QVERIFY(cancelled.beginTermination(true));
    cancelled.cancel();
    cancelled.beginCleanup();
    cancelled.completeCleanup();
    QVERIFY(!cancelled.canResume());
    QVERIFY(!cancelled.takeResume());

    SessionRecovery::RecoveryGate normal;
    normal.beginCleanup();
    QVERIFY(!normal.beginTermination(true)); // late callback cannot post SDL_QUIT
    normal.completeCleanup();
    QVERIFY(!normal.canResume());
    QVERIFY(!normal.wasInterrupted());
    SessionRecovery::RecoveryGate earlyFailure;
    QVERIFY(earlyFailure.beginTermination(false, true));
    QVERIFY(earlyFailure.wasInterrupted());
    QVERIFY(!SessionRecovery::cleanupDecision(earlyFailure.wasInterrupted(), true, false).quitHost);

    for (int cycle = 0; cycle < 5; ++cycle) {
        SessionRecovery::RecoveryGate fresh;
        QVERIFY(fresh.beginTermination(true));
        fresh.beginCleanup();
        fresh.completeCleanup();
        QVERIFY(fresh.takeResume()); // every new Session may recover again
    }
}

void VirtualDisplayLaunchTest::cleanupDecisionPreservesQuitAfterFailure()
{
    for (bool unexpected : {false, true}) {
        for (bool preference : {false, true}) {
            const auto quit = SessionRecovery::cleanupDecision(unexpected, preference, true);
            QVERIFY(quit.exitClient);
            QVERIFY(quit.quitHost);
            const auto disconnect = SessionRecovery::cleanupDecision(unexpected, preference, false);
            QVERIFY(!disconnect.exitClient);
            QCOMPARE(disconnect.quitHost, !unexpected && preference);
        }
    }
}

QTEST_GUILESS_MAIN(VirtualDisplayLaunchTest)

#include "tst_virtualdisplay.moc"
