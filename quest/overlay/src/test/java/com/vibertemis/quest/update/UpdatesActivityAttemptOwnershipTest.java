package com.vibertemis.quest.update;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.provider.Settings;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowActivityManager;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for who owns the update attempt across a pause.
 *
 * <p>Covered here:
 * <ul>
 *   <li>a verification that completes while the activity is paused still
 *       reaches the OS installer exactly once on resume;</li>
 *   <li>an install-permission result that arrives while paused is applied
 *       on resume, and never dispatches the installer from inside the
 *       result callback;</li>
 *   <li>a Settings return with no callback at all resolves once;</li>
 *   <li>a late permission result cannot authorise or cancel anything
 *       once the attempt is gone;</li>
 *   <li>Back / outside dismissal of the permission dialog releases the
 *       hand-off instead of leaving it stuck, and a dialog left over
 *       from an earlier attempt cannot touch the live one;</li>
 *   <li>the Retry button re-runs the failed update rather than slipping
 *       a metadata check in first.</li>
 * </ul>
 *
 * <p>Everything is driven through the real screen: the one primary
 * button, the real cancel button and the real dialog. Only the network
 * is faked, by a {@link UpdatesActivity.TransportFactory} that gates the
 * worker and stages a digest-verified payload so the production
 * transport short-circuits without reaching GitHub.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UpdatesActivityAttemptOwnershipTest {

    private KeyPair keyPair;
    private String keyPem;
    private long installed;
    private UpdateRepository repo;
    private ExecutorService exec;
    private final Map<String, byte[]> payloads = new HashMap<>();
    private final AtomicInteger checks = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong clockNow =
            new java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L);
    private final StagingFactory transport = new StagingFactory();

    @Before public void setup() throws Exception {
        keyPair = UpdateTestFixture.newKeyPair();
        keyPem = UpdateTestFixture.pemFor(keyPair);
        Context app = UpdateTestFixture.context();
        UpdateTestFixture.installSigningIdentity(app, 1L, true);
        installed = UpdateTestFixture.installedVersionCode(app);

        UpdateTestFixture.resetFileProviderStrategyCache();
        UpdateRepositoryProvider.reset();
        // Rooted at the real cache dir so the FileProvider path stays
        // the production one for the hand-off.
        UpdateRepository built = new UpdateRepository(
                new UpdateRepositoryBindings.DiskCache(app.getCacheDir()),
                clockNow::get,
                installed,
                () -> keyPem);
        exec = UpdateRepository.newDefaultExecutor();
        built.bindExecutor(exec, versionCode -> {
            checks.incrementAndGet();
            return UpdateRepository.CheckSource.Result.none();
        });
        repo = built;
        UpdateRepositoryProvider.installForTest(repo);

        UpdatesActivity.installTransportFactoryForTest(transport);
        UpdatesActivity.installTrustKeyProviderForTest(a -> keyPem);
        setVrRunning(false);
        idle();
    }

    @After public void teardown() {
        transport.unblock();
        UpdateRepositoryProvider.reset();
        repo.shutdown();
        if (exec != null) exec.shutdownNow();
        UpdatesActivity.installTransportFactoryForTest(new UpdatesActivity.TransportFactory() {
            @Override public UpdateTransport create(String trustedKey) {
                return new UpdateTransport(trustedKey);
            }
        });
    }

    // ------------------------------------------------------------------
    //  1. A completion that lands while paused is never stranded
    // ------------------------------------------------------------------

    @Test public void pausedVerificationResumesExactlyOneInstaller() throws Exception {
        publishAvailable("0.1.0.7", installed + 1, 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            transport.block();
            a.primaryButtonForTest().performClick();
            idleUntil(a, () -> "DOWNLOADING".equals(a.runningStageForTest()));

            // The activity goes away (a notification, the home key)
            // while the download and the verification are still running.
            ctl.pause();
            transport.unblock();
            idleUntil(a, () -> "VERIFYING".equals(a.runningStageForTest()));
            idleFor(300);

            assertNull("nothing may be dispatched while the activity is paused",
                    installerIntent(a));
            assertFalse("no hand-off may be claimed while paused", a.handedToSystemForTest());

            ctl.resume();
            idleUntil(a, a::handedToSystemForTest);

            assertTrue("the resumed attempt must be awaiting Android", a.awaitingSystemForTest());
            assertNotNull("the resumed attempt must open the installer", installerIntent(a));
            assertEquals("exactly one installer launch", 1, countInstallerLaunches(a));

            // Resuming again is the installer returning with no callback
            // at all: the outcome is unknown, not an idle screen and not a
            // failure, so nothing is relaunched and the verified bytes
            // stay for an explicit retry of the same pinned release.
            ctl.pause().resume();
            idleUntil(a, a::installerUnconfirmedForTest);
            assertEquals("a second resume must not launch the installer again",
                    1, countInstallerLaunches(a));
            assertFalse("the hand-off flag must be cleared", a.handedToSystemForTest());
            assertTrue("the verified bytes must be retained for a retry",
                    repo.snapshot().hasDownloaded());
            assertEquals("the retry must re-offer the same pinned release",
                    UpdatesActivity.PrimaryAction.RETRY_UNCONFIRMED, a.primaryActionForTest());
            assertEquals("Retry", a.primaryLabelForTest());
            assertEquals("the pinned release must still be the retry target",
                    "0.1.0.7", a.pinnedVersionForTest());
            assertTrue("the outcome must be reported as unknown: " + a.windowStatusText(),
                    a.windowStatusText().contains("not confirmed"));
            assertTrue("the status must name the release in question: "
                            + a.windowStatusText(),
                    a.windowStatusText().contains("0.1.0.7"));
        } finally {
            if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  2. A permission result is applied on resume, never in the callback
    // ------------------------------------------------------------------

    @Test public void permissionResultDeliveredWhilePausedIsAppliedOnResume() throws Exception {
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, false);
        publishAvailable("0.1.0.7", installed + 1, 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::awaitingPermissionForTest);
            assertEquals("the hand-off is still ours while the dialog is up",
                    "INSTALLER", a.runningStageForTest());

            clickDialogButton(a, AlertDialog.BUTTON_POSITIVE);
            ShadowActivity.IntentForResult settings = settingsIntent(a);
            assertNotNull("the Settings screen must be launched", settings);
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    settings.intent.getAction());

            // Android delivers the answer while we are still away.
            ctl.pause();
            a.onActivityResult(settings.requestCode, Activity.RESULT_CANCELED, null);
            idleFor(300);

            assertNull("a result must not dispatch from inside the callback",
                    installerIntent(a));
            assertEquals("the attempt must wait for the resume",
                    "INSTALLER", a.runningStageForTest());
            assertEquals("0.1.0.7", a.pinnedVersionForTest());

            ctl.resume();
            idle();

            assertEquals("the resume must clear the busy state", "IDLE", a.runningStageForTest());
            assertEquals(0, countInstallerLaunches(a));
            assertFalse("the permission must no longer be pending", a.awaitingPermissionForTest());
            assertTrue("the verified download must be kept: " + a.windowStatusText(),
                    a.windowStatusText().contains("permission was not granted"));
            assertTrue("the verified cache must survive a denial", repo.snapshot().hasDownloaded());
            assertEquals("the update must be offered again",
                    "Update to 0.1.0.7", a.primaryLabelForTest());
        } finally {
            if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
        }
    }

    @Test public void settingsReturnWithNoCallbackResumesTheInstallerOnce() throws Exception {
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, false);
        publishAvailable("0.1.0.7", installed + 1, 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::awaitingPermissionForTest);
            clickDialogButton(a, AlertDialog.BUTTON_POSITIVE);
            assertNotNull(settingsIntent(a));

            // The user grants the permission and comes back. Android
            // delivers no callback at all.
            UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, true);
            ctl.pause().resume();
            idleUntil(a, () -> a.handedToSystemForTest() && a.awaitingSystemForTest());

            assertNotNull("the installer must resume on the grant", installerIntent(a));
            assertEquals("exactly one installer launch", 1, countInstallerLaunches(a));
            assertEquals("the pinned release must survive the round trip",
                    "0.1.0.7", repo.snapshot().downloaded.version);

            ctl.pause().resume();
            idleUntil(a, a::installerUnconfirmedForTest);
            assertEquals("resuming again must not relaunch", 1, countInstallerLaunches(a));
            assertTrue("the outcome must be reported as unknown: " + a.windowStatusText(),
                    a.windowStatusText().contains("not confirmed"));
            assertTrue("the verified cache must be kept for a retry",
                    repo.snapshot().hasDownloaded());
        } finally {
            if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
        }
    }

    @Test public void latePermissionResultCannotAuthoriseTheAttemptAfterCancel() throws Exception {
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, false);
        publishAvailable("0.1.0.7", installed + 1, 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::awaitingPermissionForTest);

            // The user goes to Settings and then changes their mind.
            clickDialogButton(a, AlertDialog.BUTTON_POSITIVE);
            int staleRequestCode = settingsIntent(a).requestCode;
            a.cancelButtonForTest().performClick();
            idle();
            assertNull("cancel must release the pinned target", a.pinnedVersionForTest());
            assertFalse("cancel must retire the permission request", a.awaitingPermissionForTest());

            // A fresh attempt takes a request code of its own.
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::awaitingPermissionForTest);
            clickDialogButton(a, AlertDialog.BUTTON_POSITIVE);
            ShadowActivity.IntentForResult live = latestSettingsIntent(a);
            assertNotNull("the live attempt must launch Settings", live);
            int liveRequestCode = live.requestCode;
            assertTrue("each launch must take its own request code",
                    liveRequestCode != staleRequestCode);

            // A grant for the abandoned attempt arrives late, while the
            // permission is still actually missing.
            a.onActivityResult(staleRequestCode, Activity.RESULT_OK, null);
            idleFor(300);

            assertNull("a stale callback must never launch the installer", installerIntent(a));
            assertFalse("a stale callback must not claim a hand-off", a.handedToSystemForTest());
            assertEquals("the live attempt must still own the hand-off",
                    "INSTALLER", a.runningStageForTest());
            assertTrue("the live attempt must still be pending", a.awaitingPermissionForTest());
            assertEquals("0.1.0.7", a.pinnedVersionForTest());
            assertTrue("the verified cache must be kept", repo.snapshot().hasDownloaded());

            // The live request still works once the permission exists.
            UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, true);
            ctl.pause().resume();
            idleUntil(a, a::handedToSystemForTest);
            assertNotNull("the live request must still resume the installer", installerIntent(a));
            assertEquals("exactly one installer launch", 1, countInstallerLaunches(a));
        } finally {
            if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
        }
    }

    @Test public void permissionResultIsIgnoredWhileOnlyTheDialogIsShown() throws Exception {
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, false);
        publishAvailable("0.1.0.7", installed + 1, 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::awaitingPermissionForTest);

            // Nothing has been launched yet, so no result can belong to
            // this screen: every code is stale, including the one the
            // previous attempt used.
            UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, true);
            a.onActivityResult(801, Activity.RESULT_OK, null);
            a.onActivityResult(802, Activity.RESULT_OK, null);
            idleFor(300);

            assertNull("a result must not be accepted while the dialog is up",
                    installerIntent(a));
            assertFalse("no hand-off may be claimed", a.handedToSystemForTest());
            assertEquals("the attempt must be untouched by a stale result",
                    "INSTALLER", a.runningStageForTest());
            assertTrue("the dialog must still be waiting for the user",
                    a.awaitingPermissionForTest());
            assertEquals("0.1.0.7", a.pinnedVersionForTest());
        } finally {
            if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  3. The permission dialog cannot strand the hand-off
    // ------------------------------------------------------------------

    @Test public void dismissingThePermissionDialogReleasesTheHandoff() throws Exception {
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, false);
        publishAvailable("0.1.0.7", installed + 1, 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::awaitingPermissionForTest);
            AlertDialog first = latestDialog(a);

            // Back, or a tap outside the dialog: no button is pressed.
            first.cancel();
            idle();

            assertEquals("dismissal must clear the busy state", "IDLE", a.runningStageForTest());
            assertNull("dismissal must release the pinned target", a.pinnedVersionForTest());
            assertFalse("dismissal must retire the permission request",
                    a.awaitingPermissionForTest());
            assertFalse("dismissal must not claim a hand-off", a.handedToSystemForTest());
            assertTrue("the user must be told the download was kept: " + a.windowStatusText(),
                    a.windowStatusText().contains("kept"));
            assertTrue("the verified cache must survive a dismissal",
                    repo.snapshot().hasDownloaded());
            assertTrue("the single action must be live again", a.isPrimaryEnabledForTest());
            assertEquals("the update must be offered again",
                    UpdatesActivity.PrimaryAction.UPDATE, a.primaryActionForTest());

            // A callback from the dismissed dialog must not reach the
            // attempt that is live now.
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::awaitingPermissionForTest);
            assertEquals("0.1.0.7", a.pinnedVersionForTest());
            UpdateTestFixture.launchedIntents(a);

            android.widget.Button stale = first.getButton(AlertDialog.BUTTON_POSITIVE);
            assertNotNull("a shown dialog must have answered its buttons", stale);
            stale.performClick();
            idleFor(200);
            assertNull("a dismissed dialog must not open Settings", settingsIntent(a));
            assertEquals("the live attempt must be untouched",
                    "INSTALLER", a.runningStageForTest());
            assertTrue("the live attempt must still be pending", a.awaitingPermissionForTest());
            assertEquals("0.1.0.7", a.pinnedVersionForTest());
        } finally {
            if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  4. An unconfirmed installer outcome
    // ------------------------------------------------------------------

    /**
     * A resume that is not the installer returning must not decide
     * anything. Android still owns the installer, so the screen rests in
     * the unknown state and keeps its request identity so a late result
     * can still settle it.
     */
    @Test public void unrelatedResumeLeavesTheInstallerUnconfirmed() throws Exception {
        publishAvailable("0.1.0.7", installed + 1, 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::handedToSystemForTest);
            ShadowActivity.IntentForResult launched = installerIntent(a);
            assertNotNull("the installer must be launched", launched);

            // The app comes back while the installer is still up
            // (multiwindow), with no result from Android.
            ctl.pause().resume();
            idleUntil(a, a::installerUnconfirmedForTest);

            assertEquals("nothing may be reinstalled automatically",
                    1, countInstallerLaunches(a));
            assertTrue("an explicit retry must be available",
                    a.isPrimaryEnabledForTest());
            assertEquals(UpdatesActivity.PrimaryAction.RETRY_UNCONFIRMED,
                    a.primaryActionForTest());
            assertTrue("the verified cache must be kept",
                    repo.snapshot().hasDownloaded());
            assertTrue("Back must remain available: " + a.windowStatusText(),
                    a.isBackVisibleForTest());

            // A late result for that exact launch still settles it.
            a.onActivityResult(launched.requestCode, Activity.RESULT_CANCELED, null);
            idleUntil(a, () -> "IDLE".equals(a.runningStageForTest()));
            assertTrue("a matching late result must settle the attempt: "
                            + a.windowStatusText(),
                    a.windowStatusText().contains("not installed"));
            assertEquals("settling must not relaunch the installer",
                    1, countInstallerLaunches(a));
        } finally {
            if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
        }
    }

    /** A callbackless retry is explicit, uses a new request code, and
     *  cannot be answered by the result of the launch it replaced. */
    @Test public void callbacklessRetryLaunchesOnceAndIgnoresTheOldResult() throws Exception {
        publishAvailable("0.1.0.7", installed + 1, 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::handedToSystemForTest);
            int staleCode = installerIntent(a).requestCode;

            ctl.pause().resume();
            idleUntil(a, a::installerUnconfirmedForTest);

            a.primaryButtonForTest().performClick();
            idleUntil(a, () -> countInstallerLaunches(a) == 2);
            ShadowActivity.IntentForResult retry = latestInstallerIntent(a);
            assertNotNull("the retry must open the installer", retry);
            assertTrue("the retry must take a new request code",
                    retry.requestCode != staleCode);

            // The replaced launch's result arrives late: it must not
            // settle the attempt that owns the screen now.
            a.onActivityResult(staleCode, Activity.RESULT_CANCELED, null);
            idleFor(300);
            assertTrue("a stale result must not settle the new launch",
                    a.awaitingSystemForTest());
            assertTrue("the new launch must still be pending",
                    a.handedToSystemForTest());
            assertEquals("no third launch may happen", 2, countInstallerLaunches(a));
        } finally {
            if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
        }
    }

    /** A Settings result delivered before the resume settles once, on
     *  resume, and never launches while paused. */
    @Test public void settingsBeforeResumeWithNoCallbackDispatchesOnce() throws Exception {
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, false);
        publishAvailable("0.1.0.7", installed + 1, 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::awaitingPermissionForTest);
            clickDialogButton(a, AlertDialog.BUTTON_POSITIVE);
            ShadowActivity.IntentForResult settings = settingsIntent(a);
            assertNotNull("the Settings screen must be launched", settings);

            ctl.pause();
            // No result at all: the grant itself is the answer.
            UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, true);
            idleFor(200);
            assertNull("nothing may be dispatched while paused", installerIntent(a));

            ctl.resume();
            idleUntil(a, a::handedToSystemForTest);
            assertNotNull("the grant must dispatch the installer on resume",
                    installerIntent(a));
            assertEquals("exactly one installer launch", 1, countInstallerLaunches(a));
        } finally {
            if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  5. Retry re-runs the operation that failed
    // ------------------------------------------------------------------

    @Test public void retryButtonReRunsTheUpdateAndSkipsTheCheck() throws Exception {
        publishAvailable("0.1.0.7", installed + 1, 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            idleUntil(a, () -> !repo.snapshot().checking);
            // A transport that fails the way a dead connection does.
            UpdatesActivity.installTransportFactoryForTest(new UpdatesActivity.TransportFactory() {
                @Override public UpdateTransport create(String trustedKey) {
                    UpdateTransport t = new UpdateTransport(trustedKey);
                    t.cancelled = true;
                    transport.created.incrementAndGet();
                    return t;
                }
            });

            a.primaryButtonForTest().performClick();
            idleUntil(a, () -> "IDLE".equals(a.runningStageForTest()));
            assertNotNull("the first attempt must fail", a.currentActionErrorForTest());
            assertEquals("update", a.currentLastFailedKindForTest());
            assertEquals("Retry", a.primaryLabelForTest());
            // Past the six-hour window, so a wrongly-inserted metadata
            // check would really run.
            clockNow.addAndGet(7L * 60L * 60L * 1000L);
            int checksBefore = checks.get();

            a.primaryButtonForTest().performClick();
            idleUntil(a, () -> "IDLE".equals(a.runningStageForTest()));

            assertEquals("a retry must re-run the update", "update",
                    a.currentLastFailedKindForTest());
            assertEquals("a retry must build a second transport", 2, transport.created.get());
            assertEquals("a retry must not slip a metadata check in first",
                    checksBefore, checks.get());
            assertEquals(UpdatesActivity.PrimaryAction.RETRY, a.primaryActionForTest());
        } finally {
            if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  Fixtures
    // ------------------------------------------------------------------

    /** Publish a signed release and stage the bytes a download of it
     *  will find. */
    private void publishAvailable(String version, long versionCode, long sequence)
            throws Exception {
        byte[] apk = ("apk-" + version).getBytes("UTF-8");
        payloads.put(version, apk);
        byte[] body = body(version, sequence, versionCode, apk);
        byte[] sig = sign(body);
        repo.recordAvailable(UpdateManifest.verify(body, sig, keyPem), body, sig);
    }

    private byte[] body(String version, long sequence, long versionCode, byte[] apk)
            throws Exception {
        String filename = "vibertemis-quest-preview-" + version + ".apk";
        return new JSONObject()
                .put("schema", 1)
                .put("channel", "quest-preview")
                .put("sequence", sequence)
                .put("version", version)
                .put("native_protocol", UpdateManifest.PROTOCOL)
                .put("assets", new JSONObject().put("android", new JSONObject()
                        .put("filename", filename)
                        .put("url", UpdateManifest.PREFIX + "quest-preview-v" + version
                                + "/" + filename)
                        .put("bytes", apk.length)
                        .put("sha256", UpdateTestFixture.hex(UpdateTestFixture.sha256(apk)))
                        .put("package", UpdateTestFixture.packageName(UpdateTestFixture.context()))
                        .put("version_code", versionCode)
                        .put("signer_sha256", UpdateTestFixture.SIGNER_SHA256)))
                .toString().getBytes("UTF-8");
    }

    private byte[] sign(byte[] body) throws Exception {
        java.security.Signature s = java.security.Signature.getInstance("SHA256withRSA");
        s.initSign(keyPair.getPrivate());
        s.update(body);
        return s.sign();
    }

    private ActivityController<UpdatesActivity> openActivity() {
        return Robolectric.buildActivity(UpdatesActivity.class).create().start().resume().visible();
    }

    private void setVrRunning(boolean running) {
        Context app = UpdateTestFixture.context();
        ActivityManager manager = (ActivityManager) app.getSystemService(Activity.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = new ArrayList<>();
        if (running) {
            ActivityManager.RunningAppProcessInfo p =
                    new ActivityManager.RunningAppProcessInfo();
            p.processName = app.getPackageName() + ":pcvr";
            p.pid = Process.myPid();
            p.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            processes.add(p);
        }
        Shadows.shadowOf(manager).setProcesses(processes);
    }

    /**
     * Press a dialog button and drain the looper. AlertController
     * dispatches the press through the main looper, so the intent it
     * launches only exists once the queue has run.
     */
    private static void clickDialogButton(UpdatesActivity a, int which) throws Exception {
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("a dialog must be on screen", dialog);
        android.widget.Button button = dialog.getButton(which);
        assertNotNull("the dialog must have a button for " + which, button);
        button.performClick();
        idle();
    }

    private static AlertDialog latestDialog(UpdatesActivity a) {
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("a dialog must be on screen", dialog);
        return dialog;
    }

    /** Every intent this activity launched for result, in order. Read
     *  as often as you like: Robolectric's queue is drained once and the
     *  history is kept. */
    private static List<ShadowActivity.IntentForResult> allIntents(UpdatesActivity a) {
        return UpdateTestFixture.launchedIntents(a);
    }

    private static ShadowActivity.IntentForResult installerIntent(UpdatesActivity a) {
        for (ShadowActivity.IntentForResult i : allIntents(a)) {
            if (Intent.ACTION_INSTALL_PACKAGE.equals(i.intent.getAction())) return i;
        }
        return null;
    }

    /** The newest ACTION_INSTALL_PACKAGE launch, if any: the launch a
     *  retry supersedes is the last one, not the first recorded. */
    private static ShadowActivity.IntentForResult latestInstallerIntent(UpdatesActivity a) {
        ShadowActivity.IntentForResult newest = null;
        for (ShadowActivity.IntentForResult i : allIntents(a)) {
            if (Intent.ACTION_INSTALL_PACKAGE.equals(i.intent.getAction())) newest = i;
        }
        return newest;
    }

    private static ShadowActivity.IntentForResult settingsIntent(UpdatesActivity a) {
        for (ShadowActivity.IntentForResult i : allIntents(a)) {
            if (Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES.equals(i.intent.getAction())) return i;
        }
        return null;
    }

    /** The newest Settings launch, if any. */
    private static ShadowActivity.IntentForResult latestSettingsIntent(UpdatesActivity a) {
        ShadowActivity.IntentForResult newest = null;
        for (ShadowActivity.IntentForResult i : allIntents(a)) {
            if (Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES.equals(i.intent.getAction())) newest = i;
        }
        return newest;
    }

    private static int countInstallerLaunches(UpdatesActivity a) {
        int count = 0;
        for (ShadowActivity.IntentForResult i : allIntents(a)) {
            if (Intent.ACTION_INSTALL_PACKAGE.equals(i.intent.getAction())) count++;
        }
        return count;
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private static void idleUntil(UpdatesActivity a, BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            idle();
            if (done.getAsBoolean()) return;
            Thread.sleep(5);
        }
        idle();
        assertTrue("timed out (stage=" + a.runningStageForTest()
                        + ", status=" + a.windowStatusText() + ")", done.getAsBoolean());
    }

    private static void idleFor(long millis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            idle();
            Thread.sleep(5);
        }
        idle();
    }

    /**
     * Stands in for the network. The transport handed back is the real
     * one and the payload is staged as a digest-verified update.apk in
     * the download directory, so the production transport short-circuits
     * and every digest / archive / signer check still runs.
     */
    private final class StagingFactory implements UpdatesActivity.TransportFactory {
        final AtomicInteger created = new AtomicInteger();
        private volatile CountDownLatch gate = new CountDownLatch(0);

        void block() { gate = new CountDownLatch(1); }

        void unblock() { gate.countDown(); }

        @Override public UpdateTransport create(String trustedKey) {
            created.incrementAndGet();
            try {
                gate.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                stage();
            } catch (Exception e) {
                throw new IllegalStateException("could not stage the updater payload", e);
            }
            return new UpdateTransport(trustedKey);
        }

        /** Stage the bytes for the newest release the screen is
         *  offering. The production transport then finds a
         *  digest-verified update.apk and never touches the network. */
        private void stage() throws Exception {
            UpdateRepository.Snapshot s = repo.snapshot();
            UpdateManifest target = s.available;
            if (s.downloaded != null && (target == null
                    || s.downloaded.versionCode > target.versionCode
                    || (s.downloaded.versionCode == target.versionCode
                        && s.downloaded.sequence > target.sequence))) {
                target = s.downloaded;
            }
            byte[] bytes = target == null ? null : payloads.get(target.version);
            if (bytes == null) return;
            File dir = new File(UpdateTestFixture.context().getCacheDir(), "updates");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File apk = new File(dir, "update.apk");
            try (FileOutputStream out = new FileOutputStream(apk)) {
                out.write(bytes);
            }
            UpdateTestFixture.publishArchiveInfo(UpdateTestFixture.context(),
                    apk, target.versionCode);
            // The release-bound copy the repository persisted is what
            // gets verified and handed over, so publish it too.
            UpdateTestFixture.publishDownloadedArchiveInfo(UpdateTestFixture.context(),
                    target.version, target.versionCode);
        }
    }
}
