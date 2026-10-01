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
import java.nio.file.Files;
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
 * Regression tests for the bytes: which copy is verified, which copy is
 * handed to Android, and which failures may throw a verified download
 * away.
 *
 * <ul>
 *   <li>a live VR session is a busy state, not evidence of corruption:
 *       the verified cache survives and a retry reuses it;</li>
 *   <li>the newest signed release wins, and the cache only serves a
 *       target it is an exact match for;</li>
 *   <li>a destroyed instance whose transport finishes anyway cannot
 *       record, purge or dispatch anything;</li>
 *   <li>a paused instance cannot hand Android the bytes a newer attempt
 *       overwrote: the hand-off is always the immutable per-version
 *       copy.</li>
 * </ul>
 *
 * <p>The network is the only fake; the digest, archive, package and
 * signer checks are the production ones.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UpdatesActivityDownloadBoundaryTest {

    private KeyPair keyPair;
    private String keyPem;
    private long installed;
    private UpdateRepository repo;
    private ExecutorService exec;
    private final Map<String, byte[]> payloads = new HashMap<>();
    private final StagingFactory transport = new StagingFactory();

    @Before public void setup() throws Exception {
        keyPair = UpdateTestFixture.newKeyPair();
        keyPem = UpdateTestFixture.pemFor(keyPair);
        Context app = UpdateTestFixture.context();
        UpdateTestFixture.installSigningIdentity(app, 1L, true);
        installed = UpdateTestFixture.installedVersionCode(app);

        UpdateTestFixture.resetFileProviderStrategyCache();
        UpdateRepositoryProvider.reset();
        // The production cache root, so the FileProvider hand-off keeps
        // resolving the real per-version files.
        repo = new UpdateRepository(
                new UpdateRepositoryBindings.DiskCache(app.getCacheDir()),
                () -> 1_700_000_000_000L,
                installed,
                () -> keyPem);
        exec = UpdateRepository.newDefaultExecutor();
        repo.bindExecutor(exec, versionCode -> UpdateRepository.CheckSource.Result.none());
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
    //  Busy is not corruption
    // ------------------------------------------------------------------

    @Test public void busyVrKeepsTheVerifiedCacheAndTheRetryDoesNotDownloadAgain() throws Exception {
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, false);
        seedVerifiedCache("0.1.0.7", installed + 1, 7);

        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::awaitingPermissionForTest);

            // The user starts a VR session before answering the dialog.
            setVrRunning(true);
            clickDialogButton(a, AlertDialog.BUTTON_POSITIVE);
            idleUntil(a, () -> "IDLE".equals(a.runningStageForTest()));

            assertNull("a busy hand-off must not open Settings", settingsIntent(a));
            assertNull("a busy hand-off must not open the installer", installerIntent(a));
            assertNotNull("the failure must be reported", a.currentActionErrorForTest());
            assertTrue("the failure must name the real cause: " + a.currentActionErrorForTest(),
                    a.currentActionErrorForTest().toLowerCase().contains("vr"));
            assertTrue("a busy session must not purge a verified download",
                    repo.snapshot().hasDownloaded());
            assertEquals("0.1.0.7", repo.snapshot().downloaded.version);
            assertTrue("the failure must be visible and retryable: " + a.windowStatusText(),
                    a.windowStatusText().contains("failed")
                            && a.windowStatusText().contains("Tap Retry"));

            // Close VR and retry: the verified bytes are reused.
            setVrRunning(false);
            ctl.pause().resume();
            idle();
            assertEquals(UpdatesActivity.PrimaryAction.RETRY, a.primaryActionForTest());
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::awaitingPermissionForTest);

            assertEquals("a cached retry must not download again", 0, transport.created.get());
            assertTrue("the verified cache must still be there",
                    repo.snapshot().hasDownloaded());

            clickDialogButton(a, AlertDialog.BUTTON_POSITIVE);
            assertNotNull(settingsIntent(a));
            UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, true);
            ctl.pause().resume();
            idleUntil(a, a::handedToSystemForTest);

            assertNotNull("the retry must reach the installer", installerIntent(a));
            assertEquals(1, countInstallerLaunches(a));
            assertEquals("no download may ever have happened",
                    0, transport.created.get());
            assertTrue("the cache must survive the whole journey",
                    repo.snapshot().hasDownloaded());
        } finally {
            setVrRunning(false);
            if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  Newest signed release wins
    // ------------------------------------------------------------------

    @Test public void newerAvailableOutranksAnOlderCacheAndIsFetchedOnce() throws Exception {
        seedVerifiedCache("0.1.0.7", installed + 1, 7);
        File cachedApk = repo.snapshot().downloadedApk;
        publishAvailable("0.1.0.8", installed + 2, 8);

        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            idle();

            assertEquals("the newest signed release must be the target",
                    "Update to 0.1.0.8", a.primaryLabelForTest());
            assertEquals("0.1.0.8", a.offeredAttemptForTest().version());
            assertFalse("an older cache must not stand in for a newer target",
                    a.offeredAttemptForTest().hasCachedApk());
            assertTrue("the older cache must be preserved on disk", cachedApk.isFile());

            a.primaryButtonForTest().performClick();
            idleUntil(a, a::handedToSystemForTest);

            assertEquals("the newer release must be fetched exactly once",
                    1, transport.created.get());
            assertEquals("the cache must be bound to the newer release",
                    "0.1.0.8", repo.snapshot().downloaded.version);
            assertTrue("the older cache must survive", cachedApk.isFile());
            assertNotNull("the installer must be launched", installerIntent(a));
            assertEquals(1, countInstallerLaunches(a));
            assertTrue("the newer per-version copy must be handed over: "
                            + installerIntent(a).intent.getData(),
                    installerIntent(a).intent.getData().toString().contains("0.1.0.8"));
        } finally {
            if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
        }
    }

    /**
     * The mirror of {@link #newerAvailableOutranksAnOlderCacheAndIsFetchedOnce}:
     * the newest signed release is the one already cached, so the older
     * advertised release must not turn the offered target into a fresh
     * download. The bytes for the target are already verified on disk,
     * and re-fetching them would be pointless at best — offline it is a
     * failed update, and the verified copy is right there.
     */
    @Test public void newerCacheServesTheTargetWithoutRedownloadingTheOlderAvailable() throws Exception {
        seedVerifiedCache("0.1.0.9", installed + 2, 9);
        publishAvailable("0.1.0.7", installed + 1, 7);

        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            idle();

            assertEquals("the newest signed release must be the target",
                    "0.1.0.9", a.offeredAttemptForTest().version());
            assertTrue("the verified cache must serve that exact target",
                    a.offeredAttemptForTest().hasCachedApk());
            assertEquals("Update to 0.1.0.9", a.primaryLabelForTest());

            a.primaryButtonForTest().performClick();
            idleUntil(a, a::handedToSystemForTest);

            assertEquals("a target that is already cached must not be downloaded again",
                    0, transport.created.get());
            assertEquals("the attempt must pin the cached release",
                    "0.1.0.9", a.pinnedVersionForTest());
            assertEquals("the cache must stay bound to the cached release",
                    "0.1.0.9", repo.snapshot().downloaded.version);
            assertTrue("the verified cache must survive the hand-off",
                    repo.snapshot().hasDownloaded());
            assertNotNull("the installer must be launched", installerIntent(a));
            assertEquals(1, countInstallerLaunches(a));
            assertTrue("the cached release's own copy must be handed over: "
                            + installerIntent(a).intent.getData(),
                    installerIntent(a).intent.getData().toString().contains("0.1.0.9"));
        } finally {
            if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  A late, cancelled transport changes nothing
    // ------------------------------------------------------------------

    @Test public void destroyedInstanceCannotRecordOrDispatchAfterItsTransportFinishes() throws Exception {
        publishAvailable("0.1.0.7", installed + 1, 7);

        transport.block();
        ActivityController<UpdatesActivity> first = openActivity();
        first.get().primaryButtonForTest().performClick();
        assertTrue("the worker must reach the transport factory", transport.entered());
        idleUntil(first.get(), () -> "DOWNLOADING".equals(first.get().runningStageForTest()));

        // The screen goes away while the transport is still being built:
        // the cancel has nothing to reach, so this transport finishes
        // regardless.
        first.pause().stop().destroy();
        transport.unblock();
        idleFor(1000);

        assertNull("a destroyed instance must launch nothing", installerIntent(first.get()));
        assertFalse("a destroyed instance must not claim a hand-off",
                first.get().handedToSystemForTest());
        assertNull("a cancelled attempt must not record a download", repo.snapshot().downloaded);
        assertFalse("nothing may be cached by the abandoned attempt",
                repo.snapshot().hasDownloaded());

        // The recreated screen downloads the newer release.
        publishAvailable("0.1.0.9", installed + 3, 9);
        ActivityController<UpdatesActivity> second = openActivity();
        try {
            UpdatesActivity b = second.get();
            idle();
            b.primaryButtonForTest().performClick();
            idleUntil(b, b::handedToSystemForTest);

            assertEquals("one transport for the abandoned attempt, one for the new one",
                    2, transport.created.get());
            assertEquals("only the live attempt may bind a download",
                    "0.1.0.9", repo.snapshot().downloaded.version);
            assertFalse("the abandoned release must have no cached copy",
                    versionDir("0.1.0.7").exists());
            assertNotNull("the live attempt must reach the installer", installerIntent(b));
            assertTrue("the live release's own copy must be handed over: "
                            + installerIntent(b).intent.getData(),
                    installerIntent(b).intent.getData().toString().contains("0.1.0.9"));
        } finally {
            if (!second.get().isDestroyed()) second.pause().stop().destroy();
        }
    }

    @Test public void pausedInstanceCannotHandOverAnotherTargetsOverwrittenBytes() throws Exception {
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, false);
        seedVerifiedCache("0.1.0.7", installed + 1, 7);
        File pinnedCopy = repo.snapshot().downloadedApk;

        ActivityController<UpdatesActivity> first = openActivity();
        first.get().primaryButtonForTest().performClick();
        idleUntil(first.get(), first.get()::awaitingPermissionForTest);
        clickDialogButton(first.get(), AlertDialog.BUTTON_POSITIVE);
        assertNotNull("the Settings screen must be launched", settingsIntent(first.get()));
        first.pause();

        // While the first screen is away, another one downloads a newer
        // release into the shared staging file.
        publishAvailable("0.1.0.9", installed + 2, 9);
        ActivityController<UpdatesActivity> second = openActivity();
        try {
            UpdatesActivity b = second.get();
            idle();
            b.primaryButtonForTest().performClick();
            idleUntil(b, () -> b.awaitingPermissionForTest() || b.handedToSystemForTest());
            assertEquals("the newer attempt must own the cache",
                    "0.1.0.9", repo.snapshot().downloaded.version);
            assertTrue("the newer attempt must have overwritten the shared staging file",
                    sha256(stagingFile()).equals(sha256(payloads.get("0.1.0.9"))));

            // The first screen comes back with the permission granted.
            UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, true);
            first.resume();
            idleUntil(first.get(), first.get()::handedToSystemForTest);

            UpdatesActivity a = first.get();
            assertEquals("the paused attempt must still be pinned to its own release",
                    "0.1.0.7", a.pinnedVersionForTest());
            assertNotNull("the paused attempt must reach the installer", installerIntent(a));
            String handed = installerIntent(a).intent.getData().toString();
            assertTrue("it must be handed its own release: " + handed, handed.contains("0.1.0.7"));
            assertFalse("it must never be handed the newer release: " + handed,
                    handed.contains("0.1.0.9"));
            assertTrue("the pinned bytes must be intact under the hand-off: " + pinnedCopy,
                    sha256(pinnedCopy).equals(sha256(payloads.get("0.1.0.7"))));
        } finally {
            if (!second.get().isDestroyed()) second.pause().stop().destroy();
            if (!first.get().isDestroyed()) first.pause().stop().destroy();
        }
    }

    /**
     * Verification must read the release-bound copy, not the shared
     * staging file. The staging file is still owned by the download
     * boundary when the download finishes, but the verification runs
     * after that boundary is released — so a second instance may have
     * overwritten it by then. Reading it would fail the integrity check
     * for a perfectly good download and purge the pinned release.
     *
     * <p>Both screens are settled before either tap, so the automatic
     * metadata check can never be what makes a tap land on a disabled
     * button. After the first tap nothing drains the main looper: the
     * first attempt's UI callback (and therefore its verification) stays
     * queued while the second download overwrites the staging file, and
     * the test asserts that before it lets the queue run.
     *
     * <p>The second screen is paused only once its download has taken
     * the staging file, so the race is already a fact when its lifecycle
     * defers the hand-off: its verification still runs, but a paused
     * Activity does not hand anything to Android. That is also what
     * keeps the hand-off attributable, because Robolectric records
     * every {@code startActivityForResult} of every instance in one
     * queue — a second launch would be read back as the first screen's.
     */
    @Test public void verificationIgnoresAnotherAttemptsOverwrittenStagingFile() throws Exception {
        // Not attached to a window: attaching would drain the main
        // looper, and the first attempt's verification must still be
        // waiting on the queue when the second one overwrites staging.
        ActivityController<UpdatesActivity> first =
                Robolectric.buildActivity(UpdatesActivity.class).create().start().resume();
        ActivityController<UpdatesActivity> second =
                Robolectric.buildActivity(UpdatesActivity.class).create().start().resume();
        boolean secondPaused = false;
        try {
            UpdatesActivity a = first.get();
            UpdatesActivity b = second.get();
            idleUntil(a, () -> "IDLE".equals(a.runningStageForTest()));
            idleUntil(b, () -> "IDLE".equals(b.runningStageForTest()));

            publishAvailable("0.1.0.7", installed + 1, 7);
            assertEquals("precondition: the first screen offers 0.1.0.7",
                    "0.1.0.7", a.offeredAttemptForTest().version());
            a.primaryButtonForTest().performClick();

            // The download runs and is bound to its release copy, but its
            // UI callback stays queued: nothing has verified yet.
            awaitDownloaded("0.1.0.7");
            assertEquals("nothing may have been handed to Android yet",
                    "DOWNLOADING", a.runningStageForTest());
            assertTrue("the pinned copy must already exist",
                    UpdateTestFixture.downloadedApkFile(UpdateTestFixture.context(), "0.1.0.7").isFile());

            // A second instance fetches a different release, overwriting
            // the shared staging file while the first attempt is still
            // waiting. Published without draining the looper so that
            // queued callback stays queued.
            recordAvailable("0.1.0.9", installed + 2, 9);
            assertEquals("precondition: the second screen must offer the newer release",
                    "0.1.0.9", b.offeredAttemptForTest().version());
            b.primaryButtonForTest().performClick();
            awaitDownloaded("0.1.0.9");
            assertTrue("the shared staging file must now hold the other release",
                    sha256(stagingFile()).equals(sha256(payloads.get("0.1.0.9"))));

            // The overwrite is a fact and nothing has been drained: now
            // the second screen leaves the foreground, so the attempt
            // that owns the newer bytes can no longer reach Android.
            second.pause();
            secondPaused = true;

            // The race under test, asserted before anything is drained.
            assertEquals("the first attempt must still be waiting to verify",
                    "DOWNLOADING", a.runningStageForTest());
            assertEquals("the first attempt must still be pinned to its own release",
                    "0.1.0.7", a.pinnedVersionForTest());
            assertFalse("the first attempt must not have verified on a stale staging file",
                    a.handedToSystemForTest());
            assertNull("a stale staging file must not be a verification failure yet",
                    a.currentActionErrorForTest());

            // Now let the queued verifications run. The first one hands
            // off; the second one is verified but paused, so its
            // installer is deferred instead of launched.
            idleUntil(a, a::handedToSystemForTest);
            idleUntil(b, () -> "VERIFYING".equals(b.runningStageForTest()));

            assertNull("a stale staging file must not be a verification failure",
                    a.currentActionErrorForTest());
            assertEquals("the first attempt must still be pinned to its own release",
                    "0.1.0.7", a.pinnedVersionForTest());

            // One queue holds every launch of every instance, so this
            // read takes everything there is; the counts after it
            // therefore describe that whole history. Read the first
            // screen first, so the shared queue is not attributed to
            // the wrong instance.
            assertNotNull("the first attempt must reach the installer", installerIntent(a));
            String handed = installerIntent(a).intent.getData().toString();
            assertTrue("it must be handed its own release: " + handed, handed.contains("0.1.0.7"));
            assertFalse("it must never be handed the newer release: " + handed,
                    handed.contains("0.1.0.9"));
            assertEquals("only the first attempt may hand off",
                    1, countInstallerLaunches(a));

            assertFalse("a paused attempt must not claim a hand-off",
                    b.handedToSystemForTest());
            assertNull("a paused attempt must not open the installer",
                    installerIntent(b));
            assertEquals("a paused attempt must launch nothing", 0, countInstallerLaunches(b));
            assertNull("a deferred hand-off is not a failure",
                    b.currentActionErrorForTest());

            assertTrue("a good verified download must never be purged by a stale read",
                    UpdateTestFixture.downloadedApkFile(
                            UpdateTestFixture.context(), "0.1.0.7").isFile());
            assertTrue("its bytes must be untouched",
                    sha256(UpdateTestFixture.downloadedApkFile(
                            UpdateTestFixture.context(), "0.1.0.7"))
                            .equals(sha256(payloads.get("0.1.0.7"))));
        } finally {
            if (!secondPaused && !second.get().isDestroyed()) second.pause().stop().destroy();
            if (!first.get().isDestroyed()) first.pause().stop().destroy();
        }
    }

    /**
     * Wait until the repository has bound a download to {@code version}.
     * Deliberately does not touch the main looper: the caller uses that
     * to hold an activity's own UI callback (and so its verification)
     * until the right moment.
     */
    private void awaitDownloaded(String version) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (repo.snapshot().hasDownloaded()
                    && version.equals(repo.snapshot().downloaded.version)) return;
            Thread.sleep(5);
        }
        assertEquals("timed out waiting for the download of " + version,
                version, repo.snapshot().downloaded == null
                        ? null : repo.snapshot().downloaded.version);
    }

    // ------------------------------------------------------------------
    //  Fixtures
    // ------------------------------------------------------------------

    /** Seed the verified cache with real bytes bound to their release. */
    private void seedVerifiedCache(String version, long versionCode, long sequence) throws Exception {
        byte[] apk = payloadFor(version);
        byte[] body = body(version, sequence, versionCode, apk);
        byte[] sig = sign(body);
        UpdateManifest m = UpdateManifest.verify(body, sig, keyPem);
        File scratch = UpdateTestFixture.writeBytes(
                UpdateTestFixture.scratchFile("-" + version + ".apk"), apk);
        repo.recordDownloaded(m, body, sig, scratch);
        UpdateTestFixture.publishArchiveInfo(UpdateTestFixture.context(),
                repo.snapshot().downloadedApk, versionCode);
        idle();
    }

    private void publishAvailable(String version, long versionCode, long sequence) throws Exception {
        recordAvailable(version, versionCode, sequence);
        idle();
    }

    /** Publish without draining the main looper: a UI callback another
     *  attempt has already queued must stay queued. */
    private void recordAvailable(String version, long versionCode, long sequence) throws Exception {
        byte[] apk = payloadFor(version);
        byte[] body = body(version, sequence, versionCode, apk);
        byte[] sig = sign(body);
        repo.recordAvailable(UpdateManifest.verify(body, sig, keyPem), body, sig);
    }

    private byte[] payloadFor(String version) {
        return payloads.computeIfAbsent(version,
                v -> ("apk-" + v).getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    private static String sha256(File f) throws Exception {
        assertNotNull("missing file: " + f, f);
        assertTrue("missing file: " + f, f.isFile());
        return sha256(Files.readAllBytes(f.toPath()));
    }

    private static String sha256(byte[] bytes) {
        assertNotNull("missing payload", bytes);
        return UpdateTestFixture.hex(UpdateTestFixture.sha256(bytes));
    }

    private File versionDir(String version) {
        return new File(UpdateTestFixture.context().getCacheDir(),
                "updates/downloaded/" + version);
    }

    private static File stagingFile() {
        return new File(UpdateTestFixture.context().getCacheDir(), "updates/update.apk");
    }

    private ActivityController<UpdatesActivity> openActivity() {
        return Robolectric.buildActivity(UpdatesActivity.class).create().start().resume().visible();
    }

    private void setVrRunning(boolean running) {
        Context app = UpdateTestFixture.context();
        ActivityManager manager = (ActivityManager) app.getSystemService(Activity.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = new ArrayList<>();
        if (running) {
            ActivityManager.RunningAppProcessInfo p = new ActivityManager.RunningAppProcessInfo();
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

    private static ShadowActivity.IntentForResult settingsIntent(UpdatesActivity a) {
        for (ShadowActivity.IntentForResult i : allIntents(a)) {
            if (Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES.equals(i.intent.getAction())) return i;
        }
        return null;
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
     * Stands in for the network: stages a digest-verified update.apk for
     * the newest offered release so the real transport short-circuits,
     * and can hold the worker at a chosen point.
     */
    private final class StagingFactory implements UpdatesActivity.TransportFactory {
        final AtomicInteger created = new AtomicInteger();
        private volatile CountDownLatch gate = new CountDownLatch(0);
        private volatile CountDownLatch entered = new CountDownLatch(1);

        void block() {
            gate = new CountDownLatch(1);
            entered = new CountDownLatch(1);
        }

        void unblock() { gate.countDown(); }

        boolean entered() {
            try {
                return entered.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override public UpdateTransport create(String trustedKey) {
            created.incrementAndGet();
            entered.countDown();
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
