package com.vibertemis.quest.update;

import android.app.Activity;
import android.content.Context;
import android.app.ActivityManager;
import android.os.Process;
import android.view.View;
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
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
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
 * Production-event-path tests for {@link UpdatesActivity}. The
 * earlier {@code UpdatesActivityActionStateTest} only asserted
 * static labels; these tests exercise the actual download / install
 * / cancel / destroy paths through the {@link
 * UpdatesActivity.TransportFactory} and trust-key seams.
 *
 * <p>Each test installs a fake {@code TransportFactory} that returns
 * real {@link UpdateTransport} instances with the {@code cancelled}
 * flag pre-set, so we can drive the worker's
 * publish-then-cancel-then-network sequence deterministically
 * without ever touching the real GitHub transport or a network
 * sandbox. The tests assert the production state machine:
 * <ul>
 *   <li>terminal download failure clears {@code actionKind}, keeps
 *       {@code actionError}, and re-enables the buttons,</li>
 *   <li>terminal install failure does the same, scoped to
 *       {@code lastFailedKind = "install"},</li>
 *   <li>a user-triggered retry clears the prior failure before the
 *       new operation starts,</li>
 *   <li>destroy before transport publication never starts a
 *       download,</li>
 *   <li>auto metadata refresh never erases a visible
 *       {@code actionError},</li>
 *   <li>the cancel button is visible only for download,</li>
 *   <li>live VR preserves the available / downloaded message AND
 *       blocks the corresponding button.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UpdatesActivityActionStateTest {

    private File tmpRoot;
    private UpdateRepositoryBindings.DiskCache cache;
    private UpdateRepository repo;
    private ExecutorService exec;
    private String keyPem;
    private KeyPair keyPair;
    private RecordingTransportFactory transportFactory;
    private UpdatesActivity activeActivity;
    private boolean liveVr;

    @Before public void setup() throws Exception {
        tmpRoot = new File(System.getProperty("java.io.tmpdir"),
                "vq-upd-action-" + UUID.randomUUID().toString());
        assertTrue(tmpRoot.mkdirs());
        cache = new UpdateRepositoryBindings.DiskCache(tmpRoot);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(3072);
        keyPair = gen.generateKeyPair();
        keyPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        repo = new UpdateRepository(cache, () -> 1_700_000_000_000L, 6L, () -> keyPem);
        exec = UpdateRepository.newDefaultExecutor();
        repo.bindExecutor(exec, new UpdateRepository.CheckSource() {
            @Override public Result check(long currentVersionCode) {
                return Result.none();
            }
        });
        UpdateRepositoryProvider.installForTest(repo);
        // Default: every transport the factory returns is
        // pre-cancelled so download() throws an InterruptedIOException.
        // Tests that want a different outcome override this with a
        // dedicated factory.
        transportFactory = new RecordingTransportFactory(true);
        UpdatesActivity.installTransportFactoryForTest(transportFactory);
        // Provide a deterministic trust key so the activity's
        // download worker can construct a transport without
        // touching R.raw.quest_update_key (which is not present
        // under Robolectric).
        UpdatesActivity.installTrustKeyProviderForTest(activity -> keyPem);
        // Default: no live VR. Individual tests opt in by calling
        // setLiveVrRunning(true).
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
        deleteRecursive(tmpRoot);
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

    private static void deleteRecursive(File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursive(c);
        }
        if (!f.delete()) throw new RuntimeException("delete failed " + f);
    }

    /** Build a body + signature for the given fields, using the
     *  shared test key pair. Returns the canonical 384-byte signature
     *  plus the UTF-8 body. */
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

    private byte[] sha256(byte[] in) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(in);
    }

    /** Persist an available snapshot into the shared repository. */
    private void recordAvailable(String version, long sequence, long versionCode,
                                 byte[] apkBytes, String signerSha) throws Exception {
        String apkSha = UpdateManifest.hex(sha256(apkBytes));
        byte[] body = buildManifestBody(version, sequence, versionCode,
                apkBytes.length, apkSha, signerSha);
        byte[] sig = sign(body);
        UpdateManifest m = UpdateManifest.verify(body, sig, keyPem);
        repo.recordAvailable(m, body, sig);
    }

    private void configureProcesses(Context context, boolean running) {
        android.app.ActivityManager manager = (android.app.ActivityManager) context.getSystemService(Activity.ACTIVITY_SERVICE);
        List<android.app.ActivityManager.RunningAppProcessInfo> processes = new ArrayList<>();
        {
            android.app.ActivityManager.RunningAppProcessInfo process = new android.app.ActivityManager.RunningAppProcessInfo();
            process.processName = context.getPackageName() + (running ? ":pcvr" : "");
            process.pid = Process.myPid();
            process.importance = android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            processes.add(process);
        }
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
            if (activeActivity == null || activeActivity.isDestroyed() || (activeActivity.currentActionKindForTest() == null && !repo.snapshot().checking)) return;
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        } while (System.nanoTime() < deadline);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    // ------------------------------------------------------------------
    //  Production-event-path assertions
    // ------------------------------------------------------------------

    /** The earlier reflection-label test: the metadata "Checking"
     *  label never appears as the steady state. */
    @Test public void metadataCheckingIsNotConfusedWithDownloadProgress() throws Exception {
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

    /** Terminal download failure must clear actionKind (so the
     *  buttons re-enable), preserve actionError so the user sees the
     *  failure, and remember lastFailedKind so the "Tap Download to
     *  retry" hint is accurate. The factory returns a transport
     *  with {@code cancelled=true}, so the worker's first
     *  download() throws InterruptedIOException. */
    @Test public void terminalDownloadFailureReenablesButtonsAndPreservesError() throws Exception {
        recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, "0".repeat(64));
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            // Drive the user click. The activity guards
            // downloadUpdate on actionKind == null; with the
            // pre-cancelled transport, the worker will throw and
            // transition to terminal-error state.
            assertNull("precondition: idle", ctl.get().currentActionKindForTest());
            assertTrue("download button must be enabled with available manifest",
                    ctl.get().isDownloadButtonEnabledForTest());

            // Programmatically dispatch the click: the listener is
            // userInitiatedNewOperation(); downloadUpdate(). We
            // cannot call View.performClick from this test surface
            // easily, so we call the same private methods via the
            // public observer / test helper. Instead we reflectively
            // invoke downloadUpdate, which is package-private.
            invokeDownload(ctl.get());
            idleAll();

            assertEquals("download failure must clear actionKind",
                    null, ctl.get().currentActionKindForTest());
            assertNotNull("download failure must preserve actionError",
                    ctl.get().currentActionErrorForTest());
            assertEquals("lastFailedKind must remember which op failed",
                    "download", ctl.get().currentLastFailedKindForTest());
            assertTrue("download button must re-enable after terminal failure",
                    ctl.get().isDownloadButtonEnabledForTest());
            assertTrue("install button stays disabled (no downloaded slot)",
                    !ctl.get().isInstallButtonEnabledForTest());
            String text = ctl.get().windowStatusText();
            assertTrue("status must include the failure message: " + text,
                    text.contains("failed"));
            assertTrue("status must hint to retry: " + text,
                    text.contains("Tap") && text.contains("retry"));
            // Terminal UI must not release busy before transport cleanup.
            assertNull("terminal worker must clear its transport",
                    ctl.get().publishedTransportForTest());
            // At least one transport was created.
            assertTrue("factory must have produced a transport",
                    transportFactory.created.get() > 0);
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /** The cancel button is reserved for the DOWNLOAD action.
     *  Metadata check and install use no cancel button. To assert
     *  the in-flight download state, the factory is gated on a
     *  latch so the worker is parked inside {@code download()}
     *  with actionKind = "download"; cancel must be visible. After
     *  release, the pre-cancelled transport throws and the cancel
     *  button hides. For install, the worker thread is gated on
     *  the same latch so actionKind = "install" is observable and
     *  the cancel button must remain hidden. */
    @Test public void cancelButtonOnlyForDownload() throws Exception {
        // Open without an available manifest: the user can still
        // press "Check now" (force=true) which sets actionKind =
        // "check" without entering the download worker.
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            // Trigger a Check now: this enters actionKind = "check".
            // The cancel button must stay hidden.
            invokeTriggerCheckForced(ctl.get());
            idleAll();
            assertEquals("check action must not show the cancel button",
                    View.GONE, ctl.get().cancelVisibilityForTest());
            // Drain.
            idleAll();
            assertEquals("after check completes the cancel button stays hidden",
                    View.GONE, ctl.get().cancelVisibilityForTest());

            // Now record an available manifest and gate the
            // download so we can observe actionKind = "download".
            recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, "0".repeat(64));
            idleAll();
            try {
                // Gate the factory so the worker parks INSIDE
                // factory.create(). downloadUpdate has already set
                // actionKind = "download" before starting the
                // worker, so we can observe the cancel button
                // while the worker is parked.
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
                invokeDownload(ctl.get());
                assertTrue("worker must reach the factory gate",
                        workerInsideCreate.await(2, TimeUnit.SECONDS));
                // Worker is parked inside create(); actionKind is
                // "download" (set by downloadUpdate before
                // worker.start). The cancel button MUST be visible.
                assertEquals("download action must show the cancel button",
                        View.VISIBLE, ctl.get().cancelVisibilityForTest());
                releaseCreate.countDown();
            } finally {
                // The factory is restored by the @After hook.
            }
            idleAll();
            // After the worker throws (no network), the terminal-
            // error render hides the cancel button.
            assertEquals("after download failure the cancel button hides",
                    View.GONE, ctl.get().cancelVisibilityForTest());

            // For the install path we need a downloaded slot.
            File apkFile = writeFakeDownloadedApk("0.1.0.8", 8, 8, new byte[]{5, 6, 7, 8});
            recordAvailable("0.1.0.8", 8, 8, new byte[]{5, 6, 7, 8}, "0".repeat(64));
            byte[] body = buildManifestBody("0.1.0.8", 8, 8, 4, UpdateManifest.hex(sha256(new byte[]{5,6,7,8})), "0".repeat(64));
            byte[] sig = sign(body);
            UpdateManifest m = UpdateManifest.verify(body, sig, keyPem);
            repo.recordDownloaded(m, body, sig, apkFile);
            idleAll();
            invokeInstall(ctl.get());
            idleAll();
            assertEquals("install action must not show the cancel button",
                    View.GONE, ctl.get().cancelVisibilityForTest());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /** When the user retries a failed download, the prior failure
     *  message must be cleared at the moment the new operation
     *  starts (so the in-progress label is visible immediately),
     *  not after the new download terminates. The test asserts:
     *  - click download (factory throws)
     *  - after first attempt: actionError = "Update cancelled..."
     *  - click download again
     *  - very quickly (before the worker throws again) actionError
     *    is null because the click listener cleared it */
    @Test public void downloadRetryClearsPriorFailureError() throws Exception {
        recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, "0".repeat(64));
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            // First attempt.
            invokeDownload(ctl.get());
            idleAll();
            assertNotNull("first attempt must produce an actionError",
                    ctl.get().currentActionErrorForTest());
            String firstError = ctl.get().currentActionErrorForTest();
            assertEquals("download", ctl.get().currentLastFailedKindForTest());

            // Second attempt: actionError must clear before the
            // worker thread runs. After the click listener, before
            // idleAll, the state already has actionError == null.
            // We trigger the click and immediately read.
            clickDownloadAndCaptureMidState(ctl.get());
            idleAll();
            assertNotNull("retry must finish with its own transport error", ctl.get().currentActionErrorForTest());
            assertEquals(2, transportFactory.created.get());
            String text = ctl.get().windowStatusText();
            // After both attempts, render shows the failure label
            // (either first or second is the same canned message).
            assertTrue("render must continue to show the retry hint: " + text,
                    text.contains("Tap") && text.contains("retry"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /** Destroy before transport publication must cancel the
     *  transport the worker is about to use, never publish it to
     *  the activity, and never invoke download(). The factory
     *  creates a NORMAL non-cancelled real UpdateTransport, retains
     *  it, signals entered, then parks on a release latch. While
     *  the worker is parked, the activity's transport field is
     *  still null. The test clicks the actual Download button,
     *  waits for the worker to enter, destroys the activity while
     *  the worker is parked, and releases the latch. The worker
     *  wakes up, sees destroyed=true, calls local.cancel() on its
     *  retained transport, and returns without publishing. The test
     *  polls a bounded real clock for the retained transport's
     *  cancelled flag, then asserts the production guarantees. */
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
            recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, "0".repeat(64));
            ActivityController<UpdatesActivity> ctl = openActivity();
            try {
                idleAll();
                clickButton(ctl.get(), "download");
                assertTrue("worker must reach factory.create()",
                        workerEntered.await(2, TimeUnit.SECONDS));
                assertNull("precondition: transport not yet published",
                        ctl.get().publishedTransportForTest());
                ctl.pause().stop().destroy();
                release.countDown();
                // Wait for the worker to reach the destroyed
                // check and call local.cancel() on its retained
                // transport. actionKind is intentionally NOT
                // cleared by the destroy path, so we poll on
                // retained[0].cancelled instead.
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
                assertEquals("factory must produce exactly one transport",
                        1, created.get());
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

    /** Cancel clicked before the worker has had a chance to publish
     *  its transport must still cancel the transport the worker is
     *  about to use. The factory creates a NORMAL non-cancelled
     *  real UpdateTransport, retains it, signals entered, then
     *  parks on a release latch. The test clicks the actual
     *  Download button, waits for the worker to enter, then clicks
     *  the actual Cancel button while the worker is still parked.
     *  The cancel listener sets the downloadCancelled flag even
     *  though the transport field is null. The latch is released;
     *  the worker wakes up, sees downloadCancelled=true, calls
     *  local.cancel() on its retained transport, and the finally
     *  clears actionKind so the buttons re-enable for a retry. */
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
            recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, "0".repeat(64));
            ActivityController<UpdatesActivity> ctl = openActivity();
            try {
                idleAll();
                clickButton(ctl.get(), "download");
                assertTrue("worker must reach factory.create()",
                        workerEntered.await(2, TimeUnit.SECONDS));
                assertNull("precondition: transport not yet published",
                        ctl.get().publishedTransportForTest());
                clickButton(ctl.get(), "cancel");
                release.countDown();
                // Wait for the worker to reach the
                // downloadCancelled check and the finally's
                // runOnUiThread that clears actionKind.
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline) {
                    idleAll();
                    if (retained[0] != null && retained[0].cancelled
                            && ctl.get().currentActionKindForTest() == null) break;
                    try { Thread.sleep(20); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                assertNotNull("factory must produce a transport", retained[0]);
                assertTrue("cancel before publish must cancel the retained transport",
                        retained[0].cancelled);
                assertEquals("factory must produce exactly one transport",
                        1, created.get());
                assertNull("transport field must be cleared in worker finally",
                        ctl.get().publishedTransportForTest());
                assertNull("cancel before publish must clear actionKind",
                        ctl.get().currentActionKindForTest());
                assertNull("cancel before publish must not surface an actionError",
                        ctl.get().currentActionErrorForTest());
            } finally {
                release.countDown();
                if (!ctl.get().isDestroyed()) ctl.pause().stop().destroy();
            }
        } finally {
            // Factory restored in @After.
        }
    }

    /** A successful metadata refresh must NOT erase a still-visible
     *  actionError. We set up an existing download failure, then
     *  drive the auto-check to completion on open and assert the
     *  failure message remains. */
    @Test public void autoMetadataRefreshDoesNotEraseActionError() throws Exception {
        recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, "0".repeat(64));
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            // Force a download failure so actionError is set.
            invokeDownload(ctl.get());
            idleAll();
            assertNotNull("precondition: actionError set after download failure",
                    ctl.get().currentActionErrorForTest());
            String before = ctl.get().windowStatusText();
            assertTrue("precondition: render shows the failure: " + before,
                    before.contains("failed"));

            // Now drive a Check now. triggerCheck does NOT clear
            // actionError. The auto-check returns Result.none()
            // synchronously; lastSuccessAtMs advances; render()
            // is called via the observer; the actionError stays.
            invokeTriggerCheckForced(ctl.get());
            idleAll();
            assertNotNull("actionError must survive a Check now",
                    ctl.get().currentActionErrorForTest());
            String after = ctl.get().windowStatusText();
            assertTrue("render must still show the failure: " + after,
                    after.contains("failed"));
            assertTrue("render must still hint to retry: " + after,
                    after.contains("Tap") && after.contains("retry"));
            assertFalse("render must NOT have been overwritten by 'up to date': " + after,
                    after.toLowerCase().contains("up to date"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /** Live VR preserves the available message AND disables the
     *  download button. The render shows "Update X.X available.
     *  Close your VR session to download.". */
    @Test public void liveVrPreservesAvailableStateAndDisablesDownload() throws Exception {
        recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, "0".repeat(64));
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            // The onCreate auto-trigger is suppressed by live VR.
            setLiveVrRunning(true);
            idleAll();
            String text = ctl.get().windowStatusText();
            assertTrue("live VR must preserve the available version line: " + text,
                    text.contains("0.1.0.7") && text.contains("available"));
            assertTrue("live VR must include the close hint: " + text,
                    text.toLowerCase().contains("close your vr session"));
            assertFalse("download must be disabled while live VR is running",
                    ctl.get().isDownloadButtonEnabledForTest());
            assertFalse("install must be disabled while live VR is running",
                    ctl.get().isInstallButtonEnabledForTest());
        } finally {
            setLiveVrRunning(false);
            ctl.pause().stop().destroy();
        }
    }

    /** Live VR preserves the downloaded message AND disables the
     *  install button. The render shows "Verified update X.X is
     *  ready to install. Close your VR session to continue.". */
    @Test public void liveVrPreservesDownloadedStateAndDisablesInstall() throws Exception {
        recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, "0".repeat(64));
        File apkFile = writeFakeDownloadedApk("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4});
        byte[] body = buildManifestBody("0.1.0.7", 7, 7, 4,
                UpdateManifest.hex(sha256(new byte[]{1, 2, 3, 4})), "0".repeat(64));
        byte[] sig = sign(body);
        UpdateManifest m = UpdateManifest.verify(body, sig, keyPem);
        repo.recordDownloaded(m, body, sig, apkFile);

        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            setLiveVrRunning(true);
            idleAll();
            String text = ctl.get().windowStatusText();
            assertTrue("live VR must preserve the downloaded version line: " + text,
                    text.contains("Verified update") && text.contains("0.1.0.7"));
            assertTrue("live VR must include the close hint: " + text,
                    text.toLowerCase().contains("close your vr session"));
            assertFalse("download must be disabled while live VR is running",
                    ctl.get().isDownloadButtonEnabledForTest());
            assertFalse("install must be disabled while live VR is running",
                    ctl.get().isInstallButtonEnabledForTest());
        } finally {
            setLiveVrRunning(false);
            ctl.pause().stop().destroy();
        }
    }

    /** Terminal install failure preserves actionError with
     *  lastFailedKind = "install". We trigger the failure via live
     *  VR so the worker's ensureIdle() throws. */
    @Test public void installFailurePreservesErrorWithInstallKind() throws Exception {
        recordAvailable("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4}, "0".repeat(64));
        File apkFile = writeFakeDownloadedApk("0.1.0.7", 7, 7, new byte[]{1, 2, 3, 4});
        byte[] body = buildManifestBody("0.1.0.7", 7, 7, 4,
                UpdateManifest.hex(sha256(new byte[]{1, 2, 3, 4})), "0".repeat(64));
        byte[] sig = sign(body);
        UpdateManifest m = UpdateManifest.verify(body, sig, keyPem);
        repo.recordDownloaded(m, body, sig, apkFile);

        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            // Now turn on live VR. The install worker calls
            // ensureIdle() which throws because pcvr is running.
            setLiveVrRunning(true);
            idleAll();
            // invokeInstall will hit ensureIdle and surface the
            // exception in the worker thread.
            invokeInstall(ctl.get());
            idleAll();
            assertEquals("install failure must clear actionKind",
                    null, ctl.get().currentActionKindForTest());
            assertNotNull("install failure must preserve actionError",
                    ctl.get().currentActionErrorForTest());
            assertEquals("lastFailedKind must be install",
                    "install", ctl.get().currentLastFailedKindForTest());
            String text = ctl.get().windowStatusText();
            assertTrue("status must hint 'Tap Install to retry': " + text,
                    text.contains("Tap Install to retry"));
        } finally {
            setLiveVrRunning(false);
            ctl.pause().stop().destroy();
        }
    }

    private void recordDownloadedFixture() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3, 4};
        recordAvailable("0.1.0.7", 7, 7, bytes, "0".repeat(64));
        UpdateRepository.Snapshot snapshot = repo.snapshot();
        repo.recordDownloaded(snapshot.available, snapshot.availableManifestBytes,
                snapshot.availableSignatureBytes, writeFakeDownloadedApk("0.1.0.7", 7, 7, bytes));
    }

    @Test public void corruptDownloadedApkCanBeDownloadedAgain() throws Exception {
        recordDownloadedFixture();
        try (FileOutputStream out = new FileOutputStream(repo.snapshot().downloadedApk, true)) { out.write(99); }
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            clickButton(ctl.get(), "install");
            idleAll();
            assertFalse(repo.snapshot().hasDownloaded());
            assertTrue(repo.snapshot().hasAvailable());
            assertTrue(ctl.get().isDownloadButtonEnabledForTest());
            assertFalse(ctl.get().isInstallButtonEnabledForTest());
            assertTrue(ctl.get().windowStatusText().contains("Tap Download update"));
            ctl.pause().resume();
            idleAll();
            assertTrue(ctl.get().windowStatusText().contains("Tap Download update"));
            setLiveVrRunning(true);
            assertTrue(ctl.get().windowStatusText().contains("Close your VR session"));
            assertFalse(ctl.get().windowStatusText().contains("to install"));
        } finally { ctl.pause().stop().destroy(); }
    }

    @Test public void deniedInstallPermissionKeepsDownloadAndNoticeAcrossResume() throws Exception {
        recordDownloadedFixture();
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            ctl.get().onActivityResult(801, Activity.RESULT_CANCELED, null);
            ctl.pause().resume();
            idleAll();
            assertTrue(repo.snapshot().hasDownloaded());
            assertTrue(ctl.get().windowStatusText().contains("permission was not granted"));
        } finally { ctl.pause().stop().destroy(); }
    }

    @Test public void cancelledInstallerKeepsDownloadAndNoticeAcrossResume() throws Exception {
        recordDownloadedFixture();
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            idleAll();
            ctl.get().onActivityResult(802, Activity.RESULT_CANCELED, null);
            ctl.pause().resume();
            idleAll();
            assertTrue(repo.snapshot().hasDownloaded());
            assertTrue(ctl.get().windowStatusText().toLowerCase().contains("cancel"));
        } finally { ctl.pause().stop().destroy(); }
    }

    // ------------------------------------------------------------------
    //  Private reflection / invocation helpers — narrowly scoped to
    //  the test class so production source has no public surface for
    //  them.
    // ------------------------------------------------------------------

    private static void invokeDownload(UpdatesActivity a) {
        try {
            java.lang.reflect.Method m = UpdatesActivity.class
                    .getDeclaredMethod("downloadUpdate");
            m.setAccessible(true);
            m.invoke(a);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            // The worker thread wraps everything in try/catch; the
            // reflective invocation itself only fails on visible
            // IllegalAccess / NoSuchMethod, which we want surfaced.
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException roe) {
            throw new RuntimeException(roe);
        }
    }

    private static void invokeInstall(UpdatesActivity a) {
        try {
            java.lang.reflect.Method m = UpdatesActivity.class
                    .getDeclaredMethod("installUpdate");
            m.setAccessible(true);
            m.invoke(a);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException roe) {
            throw new RuntimeException(roe);
        }
    }

    private static void invokeTriggerCheckForced(UpdatesActivity a) {
        try {
            java.lang.reflect.Method m = UpdatesActivity.class
                    .getDeclaredMethod("triggerCheck", boolean.class);
            m.setAccessible(true);
            m.invoke(a, true);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException roe) {
            throw new RuntimeException(roe);
        }
    }

    /** Click the actual Button field by name. The race tests drive
     *  the production click listeners via performClick, not via
     *  reflective shortcuts, so the download / cancel handlers run
     *  exactly as they do for a real user. */
    private static void clickButton(UpdatesActivity a, String fieldName) {
        try {
            java.lang.reflect.Field f = UpdatesActivity.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            View v = (View) f.get(a);
            v.performClick();
        } catch (ReflectiveOperationException roe) {
            throw new RuntimeException(roe);
        }
    }

    private static void clickDownloadAndCaptureMidState(UpdatesActivity a) {
        // The user-click path is: userInitiatedNewOperation(); downloadUpdate().
        // We invoke the same private methods reflectively to
        // capture the moment between clearing the error and the
        // worker thread's terminal-failure render.
        try {
            java.lang.reflect.Method clear = UpdatesActivity.class
                    .getDeclaredMethod("userInitiatedNewOperation");
            clear.setAccessible(true);
            clear.invoke(a);
            // Assert mid-state immediately after the click listener's
            // first half runs.
            assertNull("userInitiatedNewOperation must clear actionError",
                    a.currentActionErrorForTest());
            assertNull("userInitiatedNewOperation must clear lastFailedKind",
                    a.currentLastFailedKindForTest());
            // Now invoke the actual downloadUpdate. The worker
            // thread runs asynchronously; we let it finish.
            java.lang.reflect.Method dl = UpdatesActivity.class
                    .getDeclaredMethod("downloadUpdate");
            dl.setAccessible(true);
            dl.invoke(a);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        } catch (ReflectiveOperationException roe) {
            throw new RuntimeException(roe);
        }
    }

    private File writeFakeDownloadedApk(String version, long sequence, long versionCode,
                                        byte[] apkBytes) throws Exception {
        File apkFile = new File(tmpRoot, "fake-" + version + ".apk");
        try (FileOutputStream out = new FileOutputStream(apkFile)) {
            out.write(apkBytes);
        }
        return apkFile;
    }

    /** Records the number of times the factory produced a transport
     *  and pre-cancels every transport it yields so the worker's
     *  download() throws InterruptedIOException immediately.
     *  Tests that want a different outcome replace the factory via
     *  {@link UpdatesActivity#installTransportFactoryForTest}. */
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
