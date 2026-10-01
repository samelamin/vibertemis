package com.vibertemis.quest.update;

import android.app.Activity;
import android.content.Context;
import android.app.ActivityManager;
import android.os.Process;
import android.view.View;
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
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Production-event-path tests for the single-action
 * {@link UpdatesActivity}. Every test drives the one real primary
 * button through {@code performClick()}; there is no second in-app
 * Install tap, so an update is "installed" here exactly when the
 * activity hands the APK to the OS installer.
 *
 * <p>The remote is the only fake: a {@link
 * UpdatesActivity.TransportFactory} yields real {@link
 * UpdateTransport} instances whose {@code cancelled} flag is preset,
 * so the worker's publish/cancel sequence runs deterministically
 * without touching GitHub. The digest / package / versionCode /
 * signer checks are the production ones, driven through
 * {@link UpdateTestFixture}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UpdatesActivityActionStateTest {

    /** Installed versionCode for these tests: older than every release
     *  they publish, so an offer is legitimate. */
    private static final long INSTALLED_VERSION = 6L;

    private KeyPair keyPair;
    private String keyPem;
    private UpdateRepository repo;
    private ExecutorService exec;
    private RecordingTransportFactory transportFactory;
    private UpdatesActivity activeActivity;
    private boolean liveVr;

    @Before public void setup() throws Exception {
        keyPair = UpdateTestFixture.newKeyPair();
        keyPem = UpdateTestFixture.pemFor(keyPair);
        // The app under test reports whatever the real build declares, so
        // pin an installed version that is legitimately older than the
        // releases these tests publish. The production downgrade checks are
        // never relaxed to make an offer appear.
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(),
                INSTALLED_VERSION, true);
        // Production roots the repository cache at the application cache
        // dir, which puts every release-bound copy inside the FileProvider
        // path the manifest declares (cache-path updates/). Rooting the
        // fixture anywhere else would fail the hand-off on a provider
        // restriction that says nothing about the update, so the real
        // provider keeps checking the real layout.
        repo = new UpdateRepository(
                new UpdateRepositoryBindings.DiskCache(UpdateTestFixture.context().getCacheDir()),
                () -> 1_700_000_000_000L, 6L, () -> keyPem);
        exec = UpdateRepository.newDefaultExecutor();
        repo.bindExecutor(exec, new UpdateRepository.CheckSource() {
            @Override public Result check(long currentVersionCode) {
                return Result.none();
            }
        });
        UpdateRepositoryProvider.installForTest(repo);
        transportFactory = new RecordingTransportFactory(true);
        UpdatesActivity.installTransportFactoryForTest(transportFactory);
        UpdatesActivity.installTrustKeyProviderForTest(activity -> keyPem);
        // androidx memoises the FileProvider strategy per authority for the
        // whole JVM while Robolectric hands each test a fresh cache dir, so
        // the memo has to go before a real hand-off resolves its path.
        UpdateTestFixture.resetFileProviderStrategyCache();
        ShadowActivityManager am = Shadows.shadowOf(
                (android.app.ActivityManager) RuntimeEnvironment.getApplication()
                        .getSystemService(Activity.ACTIVITY_SERVICE));
        am.setProcesses(new ArrayList<>());
    }

    @After public void teardown() throws Exception {
        UpdateRepositoryProvider.reset();
        repo.shutdown();
        exec.shutdownNow();
        exec.awaitTermination(2, TimeUnit.SECONDS);
        UpdatesActivity.installTransportFactoryForTest(new UpdatesActivity.TransportFactory() {
            @Override public UpdateTransport create(String trustedKey) {
                return new UpdateTransport(trustedKey);
            }
        });
        UpdatesActivity.installTrustKeyProviderForTest(activity -> {
            try (java.io.InputStream in = activity.getResources().openRawResource(
                    com.limelight.R.raw.quest_update_key);
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] b = new byte[2048];
                int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                return new String(out.toByteArray(), "US-ASCII");
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private byte[] buildManifestBody(String version, long sequence, long versionCode,
                                     long apkBytes, String apkSha, String signerSha) throws Exception {
        JSONObject apk = new JSONObject()
                .put("filename", "vibertemis-quest-preview-" + version + ".apk")
                .put("url", UpdateManifest.PREFIX + "quest-preview-v" + version
                        + "/vibertemis-quest-preview-" + version + ".apk")
                .put("bytes", apkBytes)
                .put("sha256", apkSha)
                .put("package", "com.vibertemis.quest.preview.debug")
                .put("version_code", versionCode)
                .put("signer_sha256", signerSha);
        return new JSONObject()
                .put("schema", 1)
                .put("channel", "quest-preview")
                .put("sequence", sequence)
                .put("version", version)
                .put("native_protocol", UpdateManifest.PROTOCOL)
                .put("assets", new JSONObject().put("android", apk))
                .toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private byte[] sign(byte[] body) throws Exception {
        java.security.Signature s = java.security.Signature.getInstance("SHA256withRSA");
        s.initSign(keyPair.getPrivate());
        s.update(body);
        return s.sign();
    }

    private String sha256(byte[] in) throws Exception {
        return UpdateManifest.hex(MessageDigest.getInstance("SHA-256").digest(in));
    }

    private void recordAvailable(String version, long sequence, long versionCode,
                                 byte[] apkBytes, String signerSha) throws Exception {
        String apkSha = sha256(apkBytes);
        byte[] body = buildManifestBody(version, sequence, versionCode,
                apkBytes.length, apkSha, signerSha);
        byte[] sig = sign(body);
        UpdateManifest m = UpdateManifest.verify(body, sig, keyPem);
        repo.recordAvailable(m, body, sig);
    }

    private void configureProcesses(Context context, boolean running) {
        android.app.ActivityManager manager =
                (android.app.ActivityManager) context.getSystemService(Activity.ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> processes = new ArrayList<>();
        android.app.ActivityManager.RunningAppProcessInfo process =
                new android.app.ActivityManager.RunningAppProcessInfo();
        process.processName = context.getPackageName() + (running ? ":pcvr" : "");
        process.pid = Process.myPid();
        process.importance = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        processes.add(process);
        Shadows.shadowOf(manager).setProcesses(processes);
    }

    private void setLiveVrRunning(boolean running) {
        liveVr = running;
        configureProcesses(RuntimeEnvironment.getApplication(), running);
        if (activeActivity != null) {
            configureProcesses(activeActivity, running);
            activeActivity.onResume();
        }
    }

    private ActivityController<UpdatesActivity> openActivity() {
        ActivityController<UpdatesActivity> controller = Robolectric.buildActivity(UpdatesActivity.class).create();
        activeActivity = controller.get();
        configureProcesses(activeActivity, liveVr);
        return controller.start().resume().visible();
    }

    private void idleAll() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (activeActivity == null || activeActivity.isDestroyed()
                    || ("IDLE".equals(activeActivity.runningStageForTest()) && !repo.snapshot().checking)) return;
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        } while (System.nanoTime() < deadline);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    /** Wait for the install-permission dialog to come up. */
    private void idleUntilPermission(UpdatesActivity a) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (a.awaitingPermissionForTest()) return;
            Thread.sleep(10);
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue("the install-permission dialog must come up (stage="
                        + a.runningStageForTest() + ")", a.awaitingPermissionForTest());
    }

    /** Click the one real primary button. */
    private static void clickPrimary(UpdatesActivity a) {
        a.primaryButtonForTest().performClick();
    }

    private static void clickCancel(UpdatesActivity a) {
        a.cancelButtonForTest().performClick();
    }

    // ------------------------------------------------------------------
    //  Production-event-path assertions
    // ------------------------------------------------------------------

    /** The metadata "Checking" label is a transient stage, never the
     *  steady state the user is left looking at. */
    @Test public void metadataCheckingIsNotConfusedWithDownloadProgress() throws Exception {
        recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, UpdateTestFixture.SIGNER_SHA256);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            String text = ctl.get().windowStatusText();
            assertNotNull(text);
            assertFalse("metadata Checking label must not be the steady state: " + text,
                    text.equals("Checking signed Quest preview releases..."));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /**
     * A terminal download failure must clear the busy stage (so the
     * single button re-enables), preserve the error, and remember
     * that an UPDATE failed so Retry re-runs the update rather than a
     * fresh check.
     */
    @Test public void terminalDownloadFailureReenablesButtonsAndPreservesError() throws Exception {
        recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, UpdateTestFixture.SIGNER_SHA256);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            assertEquals("precondition: idle", "IDLE", ctl.get().runningStageForTest());
            assertEquals("precondition: primary offers the update",
                    UpdatesActivity.PrimaryAction.UPDATE, ctl.get().primaryActionForTest());
            assertTrue("primary must be enabled", ctl.get().isPrimaryEnabledForTest());

            clickPrimary(ctl.get());
            idleAll();

            assertEquals("download failure must clear the stage",
                    "IDLE", ctl.get().runningStageForTest());
            assertNotNull("download failure must preserve actionError",
                    ctl.get().currentActionErrorForTest());
            assertEquals("lastFailedKind must remember the update failed",
                    "update", ctl.get().currentLastFailedKindForTest());
            assertEquals("primary must bind RETRY after a failed update",
                    UpdatesActivity.PrimaryAction.RETRY, ctl.get().primaryActionForTest());
            assertEquals("visible label must be Retry", "Retry", ctl.get().primaryLabelForTest());
            assertTrue("primary must re-enable after terminal failure",
                    ctl.get().isPrimaryEnabledForTest());
            String text = ctl.get().windowStatusText();
            assertTrue("status must include the failure message: " + text,
                    text.contains("failed"));
            assertTrue("status must hint to retry: " + text,
                    text.contains("Tap Retry"));
            assertNull("terminal worker must clear its transport",
                    ctl.get().publishedTransportForTest());
            assertTrue("factory must have produced a transport",
                    transportFactory.created.get() > 0);
            // The pinned target is released on failure.
            assertNull("failure must release the pinned target",
                    ctl.get().pinnedVersionForTest());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /**
     * Cancel is available for the whole pre-handoff window (download,
     * verification, installer preparation) and retires once the OS
     * installer owns the screen.
     */
    @Test public void cancelIsAvailableUntilSystemHandoff() throws Exception {
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            // A metadata check is not cancellable.
            assertEquals("idle state hides cancel",
                    View.GONE, ctl.get().cancelVisibilityForTest());

            recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, UpdateTestFixture.SIGNER_SHA256);
            idleAll();

            final CountDownLatch workerInsideCreate = new CountDownLatch(1);
            final CountDownLatch releaseCreate = new CountDownLatch(1);
            UpdatesActivity.installTransportFactoryForTest(new UpdatesActivity.TransportFactory() {
                @Override public UpdateTransport create(String trustedKey) {
                    workerInsideCreate.countDown();
                    try { releaseCreate.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    UpdateTransport transport = new UpdateTransport(trustedKey);
                    transport.cancel();
                    return transport;
                }
            });
            clickPrimary(ctl.get());
            assertTrue("worker must reach the factory gate",
                    workerInsideCreate.await(2, TimeUnit.SECONDS));
            assertEquals("download in flight must show cancel",
                    View.VISIBLE, ctl.get().cancelVisibilityForTest());
            // While busy the label stays pinned to the release the
            // attempt is working on, and the primary is disabled so
            // the visible label can never promise a second tap.
            assertEquals("Update to 0.1.0.7", ctl.get().primaryLabelForTest());
            assertFalse("primary must be disabled while an attempt runs",
                    ctl.get().isPrimaryEnabledForTest());
            assertEquals("the pinned target must be the offered release",
                    "0.1.0.7", ctl.get().pinnedVersionForTest());
            releaseCreate.countDown();
            idleAll();
            assertEquals("after the attempt ends the cancel button hides",
                    View.GONE, ctl.get().cancelVisibilityForTest());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /**
     * The retry tap clears the prior failure immediately so the
     * in-progress stage is visible straight away, and the retry
     * re-runs the UPDATE (a second transport) rather than a check.
     */
    @Test public void updateRetryClearsPriorFailureError() throws Exception {
        recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, UpdateTestFixture.SIGNER_SHA256);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            clickPrimary(ctl.get());
            idleAll();
            assertNotNull("first attempt must produce an actionError",
                    ctl.get().currentActionErrorForTest());
            assertEquals("update", ctl.get().currentLastFailedKindForTest());

            assertEquals("Retry must be the visible label",
                    "Retry", ctl.get().primaryLabelForTest());
            clickPrimary(ctl.get());
            idleAll();
            assertNotNull("retry must finish with its own transport error",
                    ctl.get().currentActionErrorForTest());
            assertEquals("retry must re-run the update, not the check",
                    "update", ctl.get().currentLastFailedKindForTest());
            assertEquals("a retry must produce a second transport",
                    2, transportFactory.created.get());
            String text = ctl.get().windowStatusText();
            assertTrue("render must continue to show the retry hint: " + text,
                    text.contains("Tap Retry"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /**
     * Destroy before transport publication must cancel the transport
     * the worker is about to use, never publish it, and never leave a
     * live observer behind.
     */
    @Test public void destroyBeforeTransportPublicationCancelsTransport() throws Exception {
        final CountDownLatch workerEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final UpdateTransport[] retained = new UpdateTransport[1];
        final AtomicInteger created = new AtomicInteger();
        UpdatesActivity.installTransportFactoryForTest(new UpdatesActivity.TransportFactory() {
            @Override public UpdateTransport create(String trustedKey) {
                UpdateTransport t = new UpdateTransport(trustedKey);
                retained[0] = t;
                created.incrementAndGet();
                workerEntered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return t;
            }
        });
        try {
            recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, UpdateTestFixture.SIGNER_SHA256);
            ActivityController<UpdatesActivity> ctl = openActivity();
            try {
                idleAll();
                clickPrimary(ctl.get());
                assertTrue("worker must reach factory.create()",
                        workerEntered.await(2, TimeUnit.SECONDS));
                assertNull("precondition: transport not yet published",
                        ctl.get().publishedTransportForTest());
                ctl.pause().stop().destroy();
                release.countDown();
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline) {
                    idleAll();
                    if (retained[0] != null && retained[0].cancelled) break;
                    try { Thread.sleep(20); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                assertNotNull("factory must produce a transport", retained[0]);
                assertTrue("destroy before publish must cancel the retained transport",
                        retained[0].cancelled);
                assertEquals("factory must produce exactly one transport", 1, created.get());
                assertNull("transport field must never be published",
                        ctl.get().publishedTransportForTest());
                assertEquals("destroy must remove the activity's observer",
                        0, repo.observerCountForTest());
            } finally {
                release.countDown();
                if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
            }
        } finally {
            // Factory restored in @After.
        }
    }

    /**
     * Cancel clicked before the worker published its transport must
     * still invalidate the attempt: the retained transport is
     * cancelled, the stage returns to idle, and no error is raised
     * because the user asked for this.
     */
    @Test public void cancelBeforeTransportPublicationCancelsTransport() throws Exception {
        final CountDownLatch workerEntered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final UpdateTransport[] retained = new UpdateTransport[1];
        final AtomicInteger created = new AtomicInteger();
        UpdatesActivity.installTransportFactoryForTest(new UpdatesActivity.TransportFactory() {
            @Override public UpdateTransport create(String trustedKey) {
                UpdateTransport t = new UpdateTransport(trustedKey);
                retained[0] = t;
                created.incrementAndGet();
                workerEntered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return t;
            }
        });
        try {
            recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, UpdateTestFixture.SIGNER_SHA256);
            ActivityController<UpdatesActivity> ctl = openActivity();
            try {
                idleAll();
                clickPrimary(ctl.get());
                assertTrue("worker must reach factory.create()",
                        workerEntered.await(2, TimeUnit.SECONDS));
                assertNull("precondition: transport not yet published",
                        ctl.get().publishedTransportForTest());
                clickCancel(ctl.get());
                release.countDown();
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline) {
                    idleAll();
                    if (retained[0] != null && retained[0].cancelled
                            && "IDLE".equals(ctl.get().runningStageForTest())) break;
                    try { Thread.sleep(20); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                assertNotNull("factory must produce a transport", retained[0]);
                assertTrue("cancel before publish must cancel the retained transport",
                        retained[0].cancelled);
                assertEquals("factory must produce exactly one transport", 1, created.get());
                assertNull("transport field must be cleared in the worker",
                        ctl.get().publishedTransportForTest());
                assertEquals("cancel before publish must clear the stage",
                        "IDLE", ctl.get().runningStageForTest());
                assertNull("cancel must release the pinned target",
                        ctl.get().pinnedVersionForTest());
                assertNull("cancel must not surface an actionError",
                        ctl.get().currentActionErrorForTest());
                assertTrue("cancel must be explained to the user: "
                                + ctl.get().windowStatusText(),
                        ctl.get().windowStatusText().toLowerCase().contains("cancel"));
            } finally {
                release.countDown();
                if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
            }
        } finally {
            // Factory restored in @After.
        }
    }

    /**
     * A background metadata refresh must never erase a still-visible
     * failure the user has not acted on yet.
     */
    @Test public void autoMetadataRefreshDoesNotEraseActionError() throws Exception {
        recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, UpdateTestFixture.SIGNER_SHA256);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            clickPrimary(ctl.get());
            idleAll();
            assertNotNull("precondition: actionError set after download failure",
                    ctl.get().currentActionErrorForTest());
            String before = ctl.get().windowStatusText();
            assertTrue("precondition: render shows the failure: " + before,
                    before.contains("failed"));

            // Force another metadata check through the repository.
            repo.requestCheck(true).await(2_000L);
            idleAll();
            assertNotNull("actionError must survive a metadata refresh",
                    ctl.get().currentActionErrorForTest());
            String after = ctl.get().windowStatusText();
            assertTrue("render must still show the failure: " + after,
                    after.contains("failed"));
            assertTrue("render must still hint to retry: " + after,
                    after.contains("Tap Retry"));
            assertFalse("render must NOT have been overwritten by 'up to date': " + after,
                    after.toLowerCase().contains("up to date"));
            assertEquals("a background check must not retarget the button",
                    UpdatesActivity.PrimaryAction.RETRY, ctl.get().primaryActionForTest());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /**
     * Live VR keeps the offer visible (the user can see what is
     * waiting) but disables the single action, because downloading or
     * installing mid-session is exactly what we must not do.
     */
    @Test public void liveVrPreservesAvailableStateAndDisablesUpdate() throws Exception {
        recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, UpdateTestFixture.SIGNER_SHA256);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            setLiveVrRunning(true);
            idleAll();
            String text = ctl.get().windowStatusText();
            assertTrue("live VR must preserve the available version line: " + text,
                    text.contains("0.1.0.7") && text.contains("available"));
            assertTrue("live VR must include the close hint: " + text,
                    text.toLowerCase().contains("close your vr session"));
            assertEquals("live VR must disable the single action",
                    UpdatesActivity.PrimaryAction.NONE, ctl.get().primaryActionForTest());
            assertFalse("primary must be disabled while live VR is running",
                    ctl.get().isPrimaryEnabledForTest());
        } finally {
            setLiveVrRunning(false);
            ctl.pause().stop().destroy();
        }
    }

    /** Live VR also blocks the cached-verify/install path. */
    @Test public void liveVrPreservesDownloadedStateAndDisablesUpdate() throws Exception {
        long installed = UpdateTestFixture.installedVersionCode(UpdateTestFixture.context());
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, true);
        byte[] apk = new byte[]{1, 2, 3, 4};
        recordAvailable("0.1.0.7", 7, installed + 1, apk, UpdateTestFixture.SIGNER_SHA256);
        recordDownloadedFixture("0.1.0.7", 7, installed + 1, apk);

        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            setLiveVrRunning(true);
            idleAll();
            String text = ctl.get().windowStatusText();
            assertTrue("live VR must preserve the cached version line: " + text,
                    text.contains("0.1.0.7"));
            assertTrue("live VR must include the close hint: " + text,
                    text.toLowerCase().contains("close your vr session"));
            assertEquals("live VR must disable the single action",
                    UpdatesActivity.PrimaryAction.NONE, ctl.get().primaryActionForTest());
            assertFalse("primary must be disabled while live VR is running",
                    ctl.get().isPrimaryEnabledForTest());
        } finally {
            setLiveVrRunning(false);
            ctl.pause().stop().destroy();
        }
    }

    /**
     * A verification failure keeps the pinned release named, so the
     * status tells the user which update failed.
     */
    @Test public void verificationFailurePreservesErrorWithUpdateKind() throws Exception {
        long installed = UpdateTestFixture.installedVersionCode(UpdateTestFixture.context());
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, true);
        byte[] apk = new byte[]{1, 2, 3, 4};
        recordAvailable("0.1.0.7", 7, installed + 1, apk, UpdateTestFixture.SIGNER_SHA256);
        recordDownloadedFixture("0.1.0.7", 7, installed + 1, apk);
        // The cached bytes exist and hash correctly, but no archive
        // identity is published, so PackageManager cannot vouch for
        // the package / versionCode / signer.
        UpdateTestFixture.clearArchiveInfo(UpdateTestFixture.context(), repo.snapshot().downloadedApk);

        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            clickPrimary(ctl.get());
            idleAll();
            assertEquals("verification failure must clear the stage",
                    "IDLE", ctl.get().runningStageForTest());
            assertNotNull("verification failure must preserve actionError",
                    ctl.get().currentActionErrorForTest());
            assertEquals("lastFailedKind must be the update",
                    "update", ctl.get().currentLastFailedKindForTest());
            assertEquals("primary must bind RETRY",
                    UpdatesActivity.PrimaryAction.RETRY, ctl.get().primaryActionForTest());
            String text = ctl.get().windowStatusText();
            assertTrue("status must hint to retry the update: " + text,
                    text.contains("Tap Retry"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    private File writeFakeDownloadedApk(String version, byte[] apkBytes) throws Exception {
        File apkFile = UpdateTestFixture.writeBytes(
                UpdateTestFixture.scratchFile("fake-" + version + ".apk"), apkBytes);
        return apkFile;
    }

    private void recordDownloadedFixture(String version, long sequence, long versionCode,
                                         byte[] apkBytes) throws Exception {
        String apkSha = sha256(apkBytes);
        byte[] body = buildManifestBody(version, sequence, versionCode,
                apkBytes.length, apkSha, UpdateTestFixture.SIGNER_SHA256);
        byte[] sig = sign(body);
        UpdateManifest m = UpdateManifest.verify(body, sig, keyPem);
        File fakeApk = writeFakeDownloadedApk(version, apkBytes);
        repo.recordDownloaded(m, body, sig, fakeApk);
        UpdateTestFixture.publishArchiveInfo(UpdateTestFixture.context(),
                repo.snapshot().downloadedApk, versionCode);
    }

    /**
     * A cached APK that no longer verifies must be purged so the next
     * Update downloads fresh bytes, and the status must point at the
     * retry rather than pretending the cached copy is good.
     */
    @Test public void corruptDownloadedApkCanBeDownloadedAgain() throws Exception {
        long installed = UpdateTestFixture.installedVersionCode(UpdateTestFixture.context());
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, true);
        byte[] apk = new byte[]{1, 2, 3, 4};
        recordAvailable("0.1.0.7", 7, installed + 1, apk, UpdateTestFixture.SIGNER_SHA256);
        recordDownloadedFixture("0.1.0.7", 7, installed + 1, apk);

        // Corrupt the persisted APK so its digest no longer matches the
        // signed manifest, and drop the archive identity too.
        File cached = repo.snapshot().downloadedApk;
        try (FileOutputStream out = new FileOutputStream(cached, true)) { out.write(99); }
        UpdateTestFixture.clearArchiveInfo(UpdateTestFixture.context(), cached);

        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            clickPrimary(ctl.get());
            idleAll();

            assertFalse("a corrupt cached APK must be purged",
                    repo.snapshot().hasDownloaded());
            assertTrue("the verified metadata must survive the purge",
                    repo.snapshot().hasAvailable());
            assertEquals("the single action must offer the update again",
                    UpdatesActivity.PrimaryAction.RETRY, ctl.get().primaryActionForTest());
            assertTrue("the failure must be visible: " + ctl.get().windowStatusText(),
                    ctl.get().windowStatusText().contains("failed"));
            assertTrue("the status must name the retry path: "
                            + ctl.get().windowStatusText(),
                    ctl.get().windowStatusText().contains("Tap Retry"));

            // A pause/resume cycle must not resurrect the purged cache.
            ctl.pause().resume();
            idleAll();
            assertFalse("the purged cache must stay purged across resume",
                    repo.snapshot().hasDownloaded());
            assertNotNull("a retry must still be offered after resume",
                    ctl.get().currentActionErrorForTest());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /** Denying the source-install permission keeps the verified
     * download and explains what happened. The Settings screen is
     * answered for real and the result carries the request code that
     * launch actually used. */
    @Test public void deniedInstallPermissionKeepsDownloadAndNoticeAcrossResume() throws Exception {
        long installed = UpdateTestFixture.installedVersionCode(UpdateTestFixture.context());
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, false);
        byte[] apk = new byte[]{1, 2, 3, 4};
        recordAvailable("0.1.0.7", 7, installed + 1, apk, UpdateTestFixture.SIGNER_SHA256);
        recordDownloadedFixture("0.1.0.7", 7, installed + 1, apk);

        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            clickPrimary(ctl.get());
            idleUntilPermission(ctl.get());
            android.app.AlertDialog dialog = (android.app.AlertDialog)
                    org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull("the permission dialog must be on screen", dialog);
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            org.robolectric.shadows.ShadowActivity.IntentForResult settings =
                    Shadows.shadowOf(ctl.get()).getNextStartedActivityForResult();
            assertNotNull("the Settings screen must be launched", settings);
            // The user comes back without granting.
            ctl.pause();
            ctl.get().onActivityResult(settings.requestCode, Activity.RESULT_CANCELED, null);
            ctl.resume();
            idleAll();
            assertTrue("the verified download must survive a denial",
                    repo.snapshot().hasDownloaded());
            assertEquals("the stage must clear after a denial",
                    "IDLE", ctl.get().runningStageForTest());
            assertTrue("the user must be told the permission was refused: "
                            + ctl.get().windowStatusText(),
                    ctl.get().windowStatusText().contains("permission was not granted"));
            assertEquals("the single action must offer the cached update again",
                    UpdatesActivity.PrimaryAction.UPDATE, ctl.get().primaryActionForTest());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /**
     * A cancelled installer is not an install: the verified download
     * is kept and the status says so.
     */
    @Test public void cancelledInstallerKeepsDownloadAndNoticeAcrossResume() throws Exception {
        long installed = UpdateTestFixture.installedVersionCode(UpdateTestFixture.context());
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(), installed, true);
        byte[] apk = new byte[]{1, 2, 3, 4};
        recordAvailable("0.1.0.7", 7, installed + 1, apk, UpdateTestFixture.SIGNER_SHA256);
        recordDownloadedFixture("0.1.0.7", 7, installed + 1, apk);

        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            clickPrimary(ctl.get());
            idleAll();
            assertTrue("the APK must have been handed to the OS",
                    ctl.get().handedToSystemForTest());
            org.robolectric.shadows.ShadowActivity.IntentForResult launched =
                    Shadows.shadowOf(ctl.get()).getNextStartedActivityForResult();
            assertNotNull("the installer must be launched", launched);
            ctl.get().onActivityResult(launched.requestCode, Activity.RESULT_CANCELED, null);
            idleAll();
            assertTrue("the verified download must survive a cancelled install",
                    repo.snapshot().hasDownloaded());
            assertEquals("the stage must clear",
                    "IDLE", ctl.get().runningStageForTest());
            assertTrue("the status must not claim a completed install: "
                            + ctl.get().windowStatusText(),
                    ctl.get().windowStatusText().toLowerCase().contains("not installed"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /** Records the number of transports produced and pre-cancels each
     *  one so {@code download()} throws InterruptedIOException. */
    static final class RecordingTransportFactory implements UpdatesActivity.TransportFactory {
        final AtomicInteger created = new AtomicInteger();
        final boolean preCancel;
        RecordingTransportFactory(boolean preCancel) {
            this.preCancel = preCancel;
        }
        @Override public UpdateTransport create(String trustedKey) {
            UpdateTransport t = new UpdateTransport(trustedKey);
            if (preCancel) t.cancelled = true;
            created.incrementAndGet();
            return t;
        }
    }
}
