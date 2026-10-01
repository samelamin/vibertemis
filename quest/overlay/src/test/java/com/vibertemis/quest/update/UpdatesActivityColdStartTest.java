package com.vibertemis.quest.update;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.widget.Button;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivityManager;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Cold-start tests for {@link UpdateRepositoryProvider#get(Context)}
 * and for the activity's behaviour on a production provider.
 *
 * <p>These tests do NOT use {@link UpdateRepositoryProvider#installForTest};
 * they exercise the production binding path so the embedded PEM,
 * the {@link UpdateRepositoryBindings.DiskCache}, and the actual
 * {@link PackageInfo#getLongVersionCode()} are wired into the
 * shared repository exactly as the activity consumes them.
 *
 * <p>The {@link UpdateRepository.CheckSource} is the only fake: a
 * custom {@link UpdateRepositoryProvider.CheckSourceFactory} is
 * installed so the cold-start path runs through the production
 * {@link UpdateRepositoryBindings#create} wiring without ever
 * touching the GitHub transport. Package identity and signing are
 * supplied by {@link UpdateTestFixture} so the production
 * verification in the activity runs for real.
 *
 * <p>The screen has a single primary action, so these tests assert on
 * that one button: which action it is bound to, what it says, and
 * what a real {@code performClick()} does.
 *
 * <p>The hand-off boundary asserted here is parent screen to private
 * installer: the verified APK leaves the screen as an explicit
 * {@link SessionInstallActivity} component plus the pinned release's own
 * cached path and its verified package / versionCode / size / digest,
 * never as a public {@code FileProvider} content URI. What the private
 * installer then commits through {@code PackageInstaller} is a second
 * boundary, covered independently by {@code SessionInstallActivityTest}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UpdatesActivityColdStartTest {

    /** The hand-off contract, spelled out: this screen launches the
     *  private installer explicitly, with the pinned release's own cached
     *  path and the values that release was verified against. */
    private static final String SESSION_INSTALL_ACTION =
            "com.vibertemis.quest.update.action.SESSION_INSTALL";
    private static final String EXTRA_APK_PATH =
            "com.vibertemis.quest.update.extra.APK_PATH";
    private static final String EXTRA_PACKAGE =
            "com.vibertemis.quest.update.extra.PACKAGE";
    private static final String EXTRA_VERSION_CODE =
            "com.vibertemis.quest.update.extra.VERSION_CODE";
    private static final String EXTRA_BYTES =
            "com.vibertemis.quest.update.extra.BYTES";
    private static final String EXTRA_SHA256 =
            "com.vibertemis.quest.update.extra.SHA256";

    private KeyPair keyPair;
    private String keyPem;
    private Context appContext;

    @Before public void setup() throws Exception {
        keyPair = UpdateTestFixture.newKeyPair();
        keyPem = UpdateTestFixture.pemFor(keyPair);
        appContext = RuntimeEnvironment.getApplication();
        UpdateRepositoryProvider.installCheckSourceFactoryForTest(
                (context, trustedKey) -> code -> UpdateRepository.CheckSource.Result.none());
        UpdateTestFixture.resetFileProviderStrategyCache();
        UpdateRepositoryProvider.failNextInitForTest(false);
        UpdateRepositoryProvider.clearPersistentInitFailureForTest();
        UpdateRepositoryProvider.reset();
    }

    @After public void teardown() throws Exception {
        UpdateRepositoryProvider.reset();
        UpdateRepositoryProvider.installCheckSourceFactoryForTest(null);
        UpdateRepositoryProvider.failNextInitForTest(false);
        UpdateRepositoryProvider.clearPersistentInitFailureForTest();
        ShadowActivityManager am = Shadows.shadowOf(
                (android.app.ActivityManager) appContext.getSystemService(Activity.ACTIVITY_SERVICE));
        am.setProcesses(new ArrayList<>());
    }

    // ------------------------------------------------------------------
    //  Provider cold-start
    // ------------------------------------------------------------------

    @Test public void coldStartGetInitializesFromApplicationContext() throws Exception {
        assertNull("precondition: provider cleared",
                UpdateRepositoryProvider.initializationErrorForTest());
        UpdateRepository first = UpdateRepositoryProvider.get(appContext);
        assertNotNull("cold-start get() must initialize the repository", first);
        assertNotNull("the installed version code must be set",
                UpdateRepositoryProvider.currentVersionCodeForTest());
        assertEquals("installed versionCode must equal PackageManager's",
                readInstalledVersionCodeViaPm(),
                UpdateRepositoryProvider.currentVersionCodeForTest());
        File updatesDir = new File(appContext.getCacheDir(), "updates");
        assertTrue("DiskCache must be rooted at <cacheDir>/updates", updatesDir.isDirectory());

        UpdateRepository second = UpdateRepositoryProvider.get(appContext);
        assertSame("get() must be idempotent across calls", first, second);
        assertNull("successful initialization must clear any prior error",
                UpdateRepositoryProvider.initializationErrorForTest());
    }

    @Test public void getFailureShutsDownExecutorAndPreservesRetry() throws Exception {
        UpdateRepositoryProvider.failNextInitForTest(true);
        UpdateRepository first = UpdateRepositoryProvider.get(appContext);
        assertNull("first attempt with forced failure must return null", first);
        String err = UpdateRepositoryProvider.initializationErrorForTest();
        assertNotNull("initialization error must be surfaced", err);
        assertTrue("error must mention the underlying cause: " + err,
                err.toLowerCase().contains("forced init failure")
                        || err.toLowerCase().contains("ioexception"));

        // The retry against the same context succeeds because the
        // failNextInitForTest toggle auto-clears after one use.
        UpdateRepository ret = UpdateRepositoryProvider.get(appContext);
        assertNotNull("provider must retry once init is healthy", ret);
        assertNull("successful retry must clear the prior error",
                UpdateRepositoryProvider.initializationErrorForTest());
    }

    @Test public void concurrentColdStartsCoalesce() throws Exception {
        final UpdateRepository[] r = new UpdateRepository[2];
        Thread a = new Thread(() -> r[0] = UpdateRepositoryProvider.get(appContext));
        Thread b = new Thread(() -> r[1] = UpdateRepositoryProvider.get(appContext));
        a.start(); b.start(); a.join(); b.join();
        assertNotNull(r[0]);
        assertNotNull(r[1]);
        assertSame("concurrent get() must coalesce onto a single repo", r[0], r[1]);
    }

    /**
     * The production startup path must run its automatic metadata
     * check. This is the regression guard for the cold-start wiring
     * that previously meant the automatic check never ran at all.
     */
    @Test public void coldStartActivityRendersLoadingThenIdle() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger checks =
                new java.util.concurrent.atomic.AtomicInteger();
        UpdateRepositoryProvider.installCheckSourceFactoryForTest((context, trustedKey) -> versionCode -> {
            checks.incrementAndGet();
            return UpdateRepository.CheckSource.Result.none();
        });
        ActivityController<UpdatesActivity> ctl = Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            String text = ctl.get().windowStatusText();
            assertNotNull(text);
            assertFalse("idle state must not say checking: " + text,
                    text.equals("Checking signed Quest preview releases…"));
            assertTrue("primary button must be enabled after first check: " + text,
                    ctl.get().isPrimaryEnabledForTest());
            assertEquals("cold start must run exactly one automatic metadata check",
                    1, checks.get());
            assertEquals("with nothing available the primary offers a check",
                    UpdatesActivity.PrimaryAction.CHECK, ctl.get().primaryActionForTest());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  Initial-failure -> retry -> recover (without recreation)
    // ------------------------------------------------------------------

    @Test public void initialFailureRetryRecoversWithoutRecreation() throws Exception {
        // A cold start that stays broken: onCreate acquires a null
        // repository and binds the primary to RETRY, and every resume
        // retries the acquisition rather than self-healing, because the
        // failure has not actually been fixed.
        UpdateRepositoryProvider.failInitsUntilToggledForTest(true);
        ActivityController<UpdatesActivity> ctl = Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            UpdatesActivity a = ctl.get();
            assertEquals("initial failure must bind RETRY",
                    UpdatesActivity.PrimaryAction.RETRY, a.primaryActionForTest());
            assertEquals("the visible label must be Retry", "Retry", a.primaryLabelForTest());
            assertTrue("Retry primary must be enabled", a.isPrimaryEnabledForTest());
            assertNotNull("the failure must be surfaced",
                    a.currentActionErrorForTest());

            // Still broken, so a resume must keep offering Retry rather
            // than pretending the screen is healthy.
            ctl.pause();
            ctl.resume();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("an unrecovered provider must keep binding RETRY",
                    UpdatesActivity.PrimaryAction.RETRY, a.primaryActionForTest());

            // The user (or the environment) fixes it, then taps Retry.
            UpdateRepositoryProvider.clearPersistentInitFailureForTest();
            ((Button) getField(a, "primary")).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            assertNotNull("Retry must re-acquire the repository",
                    UpdateRepositoryProvider.get(appContext));
            assertNotNull("activity must hold a repository after retry",
                    (UpdateRepository) getField(a, "repository"));
            assertNull("a recovered provider must clear the stale error",
                    a.currentActionErrorForTest());
            assertEquals("primary action after recovery must be CHECK",
                    UpdatesActivity.PrimaryAction.CHECK, a.primaryActionForTest());
        } finally {
            UpdateRepositoryProvider.clearPersistentInitFailureForTest();
            ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  Primary-action enum: retry targets the failed operation
    // ------------------------------------------------------------------

    @Test public void retryCheckAfterCheckFailure() throws Exception {
        UpdateRepositoryProvider.failInitsUntilToggledForTest(true);
        ActivityController<UpdatesActivity> ctl = Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            UpdatesActivity a = ctl.get();
            assertEquals(UpdatesActivity.PrimaryAction.RETRY, a.primaryActionForTest());
            assertEquals("visible label", "Retry", ((Button) getField(a, "primary")).getText().toString());
            // The failure is still live, so the retry cannot recover
            // yet: it must report the failure again rather than start a
            // check against a repository it does not have.
            ((Button) getField(a, "primary")).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNotNull("an unrecovered retry must keep reporting the failure",
                    a.currentActionErrorForTest());
            assertEquals("the single action must stay on RETRY",
                    UpdatesActivity.PrimaryAction.RETRY, a.primaryActionForTest());

            // Once the cause is gone the same tap recovers and checks.
            UpdateRepositoryProvider.clearPersistentInitFailureForTest();
            ((Button) getField(a, "primary")).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNull("a recovered retry must clear the stale error",
                    a.currentActionErrorForTest());
            assertEquals("the recovered screen must settle to its real action",
                    UpdatesActivity.PrimaryAction.CHECK, a.primaryActionForTest());
        } finally {
            UpdateRepositoryProvider.clearPersistentInitFailureForTest();
            ctl.pause().stop().destroy();
        }
    }

    @Test public void retryUpdateAfterDownloadFailure() throws Exception {
        UpdateRepository repo = UpdateRepositoryProvider.get(appContext);
        recordAvailableFixture(repo, "0.1.0.7", 7);
        ActivityController<UpdatesActivity> ctl = Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            UpdatesActivity a = ctl.get();
            assertEquals(UpdatesActivity.PrimaryAction.UPDATE, a.primaryActionForTest());
            assertEquals("Update to 0.1.0.7", a.primaryLabelForTest());
            // Fail the download by making the transport pre-cancelled.
            UpdatesActivity.installTransportFactoryForTest(trustedKey -> {
                UpdateTransport t = new UpdateTransport(trustedKey);
                t.cancelled = true;
                return t;
            });
            ((Button) getField(a, "primary")).performClick();
            idleUntil(a, "IDLE");
            assertEquals("update must have terminated with an error",
                    "update", a.currentLastFailedKindForTest());
            assertNotNull("actionError must be set", a.currentActionErrorForTest());
            assertEquals("primary must bind RETRY after a failed update",
                    UpdatesActivity.PrimaryAction.RETRY, a.primaryActionForTest());
            assertEquals("visible label", "Retry", ((Button) getField(a, "primary")).getText().toString());
            // The retry must re-run the update, not a check.
            ((Button) getField(a, "primary")).performClick();
            idleUntil(a, "IDLE");
            assertEquals("retry must re-run the update, not the check",
                    "update", a.currentLastFailedKindForTest());
        } finally {
            UpdatesActivity.installTransportFactoryForTest(null);
            ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  Newer-available download outranks cached install
    // ------------------------------------------------------------------

    @Test public void newerAvailableDownloadOutranksCachedInstall() throws Exception {
        UpdateRepository repo = UpdateRepositoryProvider.get(appContext);
        long installed = readInstalledVersionCodeViaPm();
        // Seed a downloaded older release.
        seedDownloadedSlot(repo, "0.1.0.7", 7, installed + 1);
        // Then publish a newer available release.
        recordAvailableFixture(repo, "0.1.0.8", 8);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        ActivityController<UpdatesActivity> ctl = Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            UpdatesActivity a = ctl.get();
            // The single action must name the newer release: that is
            // what a tap would fetch and install.
            assertEquals("the newer available release must be the target",
                    UpdatesActivity.PrimaryAction.UPDATE, a.primaryActionForTest());
            assertEquals("the visible label must name the newer release",
                    "Update to 0.1.0.8", a.primaryLabelForTest());
            assertTrue("the update must be actionable",
                    a.isPrimaryEnabledForTest());
            // The cached slot is preserved: the user can still install
            // 0.1.0.7 if they choose, and the bytes stay valid.
            UpdateRepository.Snapshot s = repo.snapshot();
            assertTrue("older cached release must be preserved", s.hasDownloaded());
            assertTrue("newer available release must be advertised", s.hasAvailable());
            String text = a.windowStatusText();
            assertTrue("status must reference the newer release: " + text,
                    text.contains("0.1.0.8"));
            assertTrue("status must not claim the cached copy is ready: " + text,
                    text.contains("available"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  Install tracking: no-callback return preserves download
    // ------------------------------------------------------------------

    /**
     * A cached release is handed to the private session installer from
     * one tap and nothing else. There is no second in-app Install
     * button, so the whole update is: click Update, verify, hand off —
     * and the private installer is told exactly which pinned, verified
     * file to install.
     */
    @Test public void oneTapUpdateFromCacheReachesSystemInstaller() throws Exception {
        UpdateRepository repo = UpdateRepositoryProvider.get(appContext);
        long installed = readInstalledVersionCodeViaPm();
        UpdateTestFixture.installSigningIdentity(appContext, installed, true);
        File apk = seedDownloadedSlot(repo, "0.1.0.7", 7, installed + 1);
        byte[] cachedBytes = fakeApkBytes("0.1.0.7");
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        ActivityController<UpdatesActivity> ctl = Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            UpdatesActivity a = ctl.get();
            assertEquals(UpdatesActivity.PrimaryAction.UPDATE, a.primaryActionForTest());
            ((Button) getField(a, "primary")).performClick();
            idleUntilHandoff(a);
            assertTrue("one tap must hand the APK to the private installer",
                    a.handedToSystemForTest());
            assertTrue("the activity must be awaiting the installer's result",
                    a.awaitingSystemForTest());
            org.robolectric.shadows.ShadowActivity.IntentForResult launched =
                    Shadows.shadowOf(a).getNextStartedActivityForResult();
            assertNotNull("the private session installer must actually be launched", launched);
            android.content.Intent handoff = launched.intent;
            assertEquals("the hand-off must name the private installer explicitly",
                    SessionInstallActivity.class.getName(),
                    handoff.getComponent().getClassName());
            assertEquals("the hand-off must carry the session-install action",
                    SESSION_INSTALL_ACTION, handoff.getAction());
            assertNull("the hand-off must not share a public FileProvider URI",
                    handoff.getData());
            File handed = handedApk(launched);
            assertEquals("the private installer must be handed this release's own copy",
                    apk.getCanonicalPath(), handed.getPath());
            assertEquals("the pinned package must be handed over",
                    UpdateTestFixture.packageName(appContext),
                    stringExtra(handoff, EXTRA_PACKAGE));
            assertEquals("the pinned versionCode must be handed over",
                    installed + 1, longExtra(handoff, EXTRA_VERSION_CODE));
            assertEquals("the pinned byte count must be handed over",
                    (long) cachedBytes.length, longExtra(handoff, EXTRA_BYTES));
            assertEquals("the digest the release was verified against must be handed over",
                    UpdateTestFixture.hex(UpdateTestFixture.sha256(cachedBytes)),
                    stringExtra(handoff, EXTRA_SHA256));
            assertTrue("the handed bytes must still be the verified ones",
                    Arrays.equals(cachedBytes, java.nio.file.Files.readAllBytes(handed.toPath())));
            // No second tap happened, so there is nothing left to tap.
            assertFalse("the primary must be disabled once the installer owns the screen",
                    a.isPrimaryEnabledForTest());
            assertEquals("cancel is not ours once the installer owns the screen",
                    android.view.View.GONE, a.cancelVisibilityForTest());
            assertNotNull("the status must say we are waiting on Android: "
                    + a.windowStatusText(), a.windowStatusText());
            assertFalse("the status must not claim completion",
                    a.windowStatusText().contains("Update 0.1.0.7 installed."));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /**
     * Android gives no result at all. That is unknown, not a cancelled
     * install: the installer may simply still be open in multiwindow. The
     * verified download must survive and the screen must offer the same
     * release again without claiming anything about the outcome.
     */
    @Test public void installerReturnWithNoActivityResultIsUnconfirmedAndKeepsDownload() throws Exception {
        UpdateRepository repo = UpdateRepositoryProvider.get(appContext);
        long installed = readInstalledVersionCodeViaPm();
        UpdateTestFixture.installSigningIdentity(appContext, installed, true);
        seedDownloadedSlot(repo, "0.1.0.7", 7, installed + 1);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        ActivityController<UpdatesActivity> ctl = Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            UpdatesActivity a = ctl.get();
            ((Button) getField(a, "primary")).performClick();
            idleUntilHandoff(a);
            assertTrue("install must be dispatched", a.handedToSystemForTest());

            ctl.pause();
            // NO onActivityResult - the OS swallowed it.
            ctl.resume();
            idleUntil(a, "INSTALLER_UNCONFIRMED");

            assertFalse("the handoff flag must be cleared",
                    a.handedToSystemForTest());
            UpdateRepository.Snapshot s = repo.snapshot();
            assertTrue("downloaded slot must survive a no-callback return", s.hasDownloaded());
            String text = a.windowStatusText();
            assertFalse("must NOT claim install completed without a result: " + text,
                    text.contains("Update 0.1.0.7 installed."));
            assertTrue("must say the outcome is unknown: " + text,
                    text.contains("not confirmed"));
            assertFalse("must not claim the install was cancelled: " + text,
                    text.toLowerCase().contains("cancel"));
            assertFalse("must not claim the app is unchanged: " + text,
                    text.toLowerCase().contains("unchanged"));
            assertEquals("the same release must be offered again",
                    UpdatesActivity.PrimaryAction.RETRY_UNCONFIRMED, a.primaryActionForTest());
            assertTrue("the explicit retry must be usable", a.isPrimaryEnabledForTest());
            assertTrue("Back must remain available to leave",
                    a.isBackVisibleForTest());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    @Test public void installerResultOkWithOldVersionIsNotCompleted() throws Exception {
        UpdateRepository repo = UpdateRepositoryProvider.get(appContext);
        long installed = readInstalledVersionCodeViaPm();
        UpdateTestFixture.installSigningIdentity(appContext, installed, true);
        seedDownloadedSlot(repo, "0.1.0.7", 7, installed + 1);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        ActivityController<UpdatesActivity> ctl = Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            UpdatesActivity a = ctl.get();
            ((Button) getField(a, "primary")).performClick();
            idleUntilHandoff(a);
            // Android reports OK but the installed versionCode never
            // advanced (some OEM installers do this). The activity
            // must reconcile against the real installed version.
            org.robolectric.shadows.ShadowActivity.IntentForResult launched =
                    Shadows.shadowOf(a).getNextStartedActivityForResult();
            assertNotNull("the installer must be launched", launched);
            a.onActivityResult(launched.requestCode, Activity.RESULT_OK, null);
            idleUntil(a, "IDLE");

            assertTrue("a RESULT_OK with the old version is not an install",
                    repo.snapshot().hasDownloaded());
            String text = a.windowStatusText();
            assertFalse("must not claim completion: " + text,
                    text.contains("Update 0.1.0.7 installed."));
            assertTrue("must report the honest outcome: " + text,
                    text.contains("not installed"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    @Test public void installerResultOkWithNewVersionCompletes() throws Exception {
        UpdateRepository repo = UpdateRepositoryProvider.get(appContext);
        long installed = readInstalledVersionCodeViaPm();
        UpdateTestFixture.installSigningIdentity(appContext, installed, true);
        seedDownloadedSlot(repo, "0.1.0.7", 7, installed + 1);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        ActivityController<UpdatesActivity> ctl = Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            UpdatesActivity a = ctl.get();
            ((Button) getField(a, "primary")).performClick();
            idleUntilHandoff(a);
            org.robolectric.shadows.ShadowActivity.IntentForResult launched =
                    Shadows.shadowOf(a).getNextStartedActivityForResult();
            assertNotNull("the installer must be launched", launched);
            // The OS really did install it: the installed versionCode
            // now matches the update's.
            UpdateTestFixture.installSigningIdentity(appContext, installed + 1, true);
            a.onActivityResult(launched.requestCode, Activity.RESULT_OK, null);
            idleUntil(a, "IDLE");

            assertFalse("a confirmed install must clear the cached slot",
                    repo.snapshot().hasDownloaded());
            String text = a.windowStatusText();
            assertTrue("a confirmed install must say so: " + text,
                    text.contains("Update 0.1.0.7 installed."));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /**
     * A denied source-install permission must clear the busy state,
     * keep the verified download, and offer the update again. The
     * Settings screen is answered for real, and the result carries the
     * request code that launch actually used.
     */
    @Test public void permissionCancelPreservesDownload() throws Exception {
        UpdateRepository repo = UpdateRepositoryProvider.get(appContext);
        long installed = readInstalledVersionCodeViaPm();
        UpdateTestFixture.installSigningIdentity(appContext, installed, false);
        seedDownloadedSlot(repo, "0.1.0.7", 7, installed + 1);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

        ActivityController<UpdatesActivity> ctl = Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            UpdatesActivity a = ctl.get();
            ((Button) getField(a, "primary")).performClick();
            idleUntil(a, "INSTALLER");
            assertTrue("the permission dialog must be pending",
                    a.awaitingPermissionForTest());

            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull("the permission dialog must be on screen", dialog);
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            org.robolectric.shadows.ShadowActivity.IntentForResult settings =
                    Shadows.shadowOf(a).getNextStartedActivityForResult();
            assertNotNull("the Settings screen must be launched", settings);

            ctl.pause();
            a.onActivityResult(settings.requestCode, Activity.RESULT_CANCELED, null);
            ctl.resume();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();

            assertEquals("busy state must clear after permission denial",
                    "IDLE", a.runningStageForTest());
            assertFalse("the permission must no longer be pending",
                    a.awaitingPermissionForTest());
            assertTrue("downloaded slot must survive permission denial",
                    repo.snapshot().hasDownloaded());
            assertEquals("the update must be offered again",
                    UpdatesActivity.PrimaryAction.UPDATE, a.primaryActionForTest());
            String text = a.windowStatusText();
            assertTrue("status must surface the permission outcome: " + text,
                    text.toLowerCase().contains("permission"));
            assertTrue("status must say the download was kept: " + text,
                    text.toLowerCase().contains("kept"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  Visible button label: one contextual label for the single action
    // ------------------------------------------------------------------

    @Test public void primaryButtonShowsSingleContextualLabel() throws Exception {
        UpdateRepository repo = UpdateRepositoryProvider.get(appContext);
        ActivityController<UpdatesActivity> ctl = Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            UpdatesActivity a = ctl.get();
            Button primary = (Button) getField(a, "primary");
            assertNotNull(primary);
            assertTrue("primary must be enabled after first check",
                    primary.isEnabled());
            assertEquals(UpdatesActivity.PrimaryAction.CHECK, a.primaryActionForTest());
            assertEquals("Update", a.primaryLabelForTest());

            // Advertise a newer release: the label names it.
            recordAvailableFixture(repo, "0.1.0.7", 7);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("primary must offer the advertised release",
                    UpdatesActivity.PrimaryAction.UPDATE, a.primaryActionForTest());
            assertEquals("Update to 0.1.0.7", a.primaryLabelForTest());

            // Cache that exact release: the label must NOT fall back
            // to a download prompt - it stays a single Update and the
            // cached bytes are reused. This is the regression guard for
            // the hasAvailable-outranks-same-downloaded loop.
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            UpdateManifest m = repo.snapshot().available;
            // The bytes must be the ones the signed manifest names: the
            // repository refuses to bind a download of a different length
            // or digest, which is the production check, not a fixture
            // convenience.
            byte[] cached = fakeApkBytes("0.1.0.7");
            File fakeApk = UpdateTestFixture.writeBytes(
                    UpdateTestFixture.scratchFile(".apk"), cached);
            repo.recordDownloaded(m, repo.snapshot().availableManifestBytes,
                    repo.snapshot().availableSignatureBytes, fakeApk);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue("the release must now be cached", repo.snapshot().hasDownloaded());
            assertEquals("the cached release must still be offered",
                    UpdatesActivity.PrimaryAction.UPDATE, a.primaryActionForTest());
            assertEquals("Update to 0.1.0.7", a.primaryLabelForTest());
            assertEquals("the offered attempt must reuse the cached bytes",
                    "0.1.0.7", a.offeredAttemptForTest().version());
            assertTrue("the offered attempt must be marked as already cached",
                    a.offeredAttemptForTest().hasCachedApk());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** Drain the main looper until the activity reports the stage. */
    private static void idleUntil(UpdatesActivity a, String stage) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (stage.equals(a.runningStageForTest())) return;
            Thread.sleep(10);
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals("timed out waiting for stage " + stage, stage, a.runningStageForTest());
    }

    /** Drain until the OS installer has been handed the APK. */
    private static void idleUntilHandoff(UpdatesActivity a) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (a.handedToSystemForTest()) return;
            Thread.sleep(10);
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue("timed out waiting for the system hand-off", a.handedToSystemForTest());
    }

    private static String stringExtra(android.content.Intent handoff, String name) {
        Object value = handoff.getExtras() == null ? null : handoff.getExtras().get(name);
        assertNotNull("the hand-off must carry " + name, value);
        return String.valueOf(value);
    }

    private static long longExtra(android.content.Intent handoff, String name) {
        Object value = handoff.getExtras() == null ? null : handoff.getExtras().get(name);
        assertNotNull("the hand-off must carry " + name, value);
        assertTrue(name + " must be handed over as a number, not " + value,
                value instanceof Number);
        return ((Number) value).longValue();
    }

    /** The APK the private installer was handed, resolved from the
     *  explicit path extra. That installer reads the file itself, so the
     *  path must name a real file inside the app's own cache. */
    private File handedApk(org.robolectric.shadows.ShadowActivity.IntentForResult launch)
            throws Exception {
        File apk = new File(stringExtra(launch.intent, EXTRA_APK_PATH)).getCanonicalFile();
        File cache = appContext.getCacheDir().getCanonicalFile();
        assertTrue("the handed APK must live under the app cache: " + apk
                        + " (cache " + cache + ")",
                apk.getPath().startsWith(cache.getPath() + File.separator));
        assertTrue("the handed APK must exist: " + apk, apk.isFile());
        return apk;
    }

    private long readInstalledVersionCodeViaPm() throws Exception {
        PackageInfo info = appContext.getPackageManager().getPackageInfo(
                appContext.getPackageName(),
                Build.VERSION.SDK_INT >= 28
                        ? android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                        : android.content.pm.PackageManager.GET_SIGNATURES);
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private byte[] buildBody(String version, long sequence, long apkBytes,
                             String apkSha, long versionCode) throws Exception {
        String filename = "vibertemis-quest-preview-" + version + ".apk";
        String url = UpdateManifest.PREFIX + "quest-preview-v" + version + "/" + filename;
        return new JSONObject()
                .put("schema", 1)
                .put("channel", "quest-preview")
                .put("sequence", sequence)
                .put("version", version)
                .put("native_protocol", UpdateManifest.PROTOCOL)
                .put("assets", new JSONObject().put("android",
                        new JSONObject()
                                .put("filename", filename)
                                .put("url", url)
                                .put("bytes", apkBytes)
                                .put("sha256", apkSha)
                                .put("package", "com.vibertemis.quest.preview.debug")
                                .put("version_code", versionCode)
                                .put("signer_sha256", UpdateTestFixture.SIGNER_SHA256)))
                .toString().getBytes("UTF-8");
    }

    /** Distinct bytes per release. Two releases must never share an
     *  APK digest, or they are one release as far as the cache is
     *  concerned and the newest-wins selection cannot be exercised. */
    private static byte[] fakeApkBytes(String version) {
        return ("apk-" + version).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void recordAvailableFixture(UpdateRepository repo, String version, long sequence) throws Exception {
        long installed = readInstalledVersionCodeViaPm();
        byte[] fakeBytes = fakeApkBytes(version);
        String apkSha = UpdateTestFixture.hex(UpdateTestFixture.sha256(fakeBytes));
        byte[] body = buildBody(version, sequence, fakeBytes.length, apkSha, installed + 1);
        byte[] sig = sign(body);
        UpdateManifest m = UpdateManifest.verify(body, sig, keyPem);
        repo.recordAvailable(m, body, sig);
    }

    private File seedDownloadedSlot(UpdateRepository repo, String version, long sequence,
                                    long versionCode) throws Exception {
        byte[] fakeBytes = fakeApkBytes(version);
        String apkSha = UpdateTestFixture.hex(UpdateTestFixture.sha256(fakeBytes));
        byte[] body = buildBody(version, sequence, fakeBytes.length, apkSha, versionCode);
        byte[] sig = sign(body);
        UpdateManifest m = UpdateManifest.verify(body, sig, keyPem);
        File fakeApk = UpdateTestFixture.writeBytes(
                UpdateTestFixture.scratchFile("-" + version + ".apk"), fakeBytes);
        repo.recordDownloaded(m, body, sig, fakeApk);
        File persisted = repo.snapshot().downloadedApk;
        UpdateTestFixture.publishArchiveInfo(appContext, persisted, versionCode);
        return persisted;
    }

    private byte[] sign(byte[] body) throws Exception {
        java.security.Signature s = java.security.Signature.getInstance("SHA256withRSA");
        s.initSign(keyPair.getPrivate());
        s.update(body);
        return s.sign();
    }

    private static Object getField(Object o, String name) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
