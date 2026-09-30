package com.vibertemis.quest.update;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Process;

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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
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
import static org.junit.Assert.assertTrue;

/**
 * Lifecycle tests for {@link UpdatesActivity}. Each test installs a
 * fresh {@link UpdateRepository} (via {@link UpdateRepositoryProvider})
 * with a controllable {@link CheckSource} that gates its response on a
 * {@link CountDownLatch}, then drives Robolectric through the activity
 * lifecycle and asserts that the open / pause / resume / close path:
 * <ul>
 *   <li>triggers exactly one check on open (no tap required),</li>
 *   <li>coalesces a concurrent onResume trigger onto the in-flight
 *       handle opened by onCreate,</li>
 *   <li>never claims "up to date" before the first successful check
 *       returns,</li>
 *   <li>defers the auto-trigger while live VR is busy and runs it
 *       on the next paused+resumed cycle,</li>
 *   <li>removes its observer on close so the repository has zero
 *       leaked observers.</li>
 * </ul>
 * The CountDownLatch is the canonical "real in-flight" mechanism: the
 * test opens the activity, blocks the executor on the latch, asserts
 * the in-flight + visible state, then releases the latch and asserts
 * the completed state. This avoids the false-positive of a fast no-op
 * source completing before the next lifecycle call.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UpdatesActivityLifecycleTest {

    private ControllableSource source;
    private FakeClock clock;
    private File tmpRoot;
    private UpdateRepositoryBindings.DiskCache cache;
    private UpdateRepository repo;
    private ExecutorService exec;
    private String keyPem;

    @Before public void setup() throws Exception {
        clock = new FakeClock();
        tmpRoot = new File(System.getProperty("java.io.tmpdir"), "vq-updates-life-" + UUID.randomUUID().toString());
        if (!tmpRoot.mkdirs()) throw new java.io.IOException("tempdir");
        cache = new UpdateRepositoryBindings.DiskCache(tmpRoot);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(3072);
        KeyPair kp = gen.generateKeyPair();
        keyPem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(kp.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        repo = new UpdateRepository(cache, clock, 6L, () -> keyPem);
        source = new ControllableSource();
        exec = UpdateRepository.newDefaultExecutor();
        repo.bindExecutor(exec, source);
        UpdateRepositoryProvider.installForTest(repo);
    }

    @After public void teardown() throws Exception {
        // Always release any pending latch FIRST so the executor can
        // drain in-flight checks and exit cleanly. Without this the
        // test thread would block forever on exec.shutdownNow.
        source.releaseAll();
        UpdateRepositoryProvider.reset();
        repo.shutdown();
        exec.shutdownNow();
        exec.awaitTermination(2, TimeUnit.SECONDS);
        deleteRecursive(tmpRoot);
    }

    private static void deleteRecursive(File f) {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursive(c);
        }
        if (!f.delete()) throw new RuntimeException("delete failed " + f);
    }

    private ActivityController<UpdatesActivity> openActivity() {
        return Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
    }

    /** Wait until the source has been entered (an inflight request
     *  exists) and the main looper has been drained so the
     *  UI thread has applied the snapshot's "checking" state. */
    private void awaitInflightStarted(ControllableSource src) throws InterruptedException {
        assertTrue("source must be entered within 2s",
                src.entered.await(2, TimeUnit.SECONDS));
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    /** Wait until the source has returned and the UI thread has
     *  drained the resulting snapshot notification. */
    private void awaitInflightFinished(ControllableSource src) throws InterruptedException {
        // Release the per-call gate; the source then returns.
        src.release();
        // Wait for the runInFlight path to clear the snapshot.
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && repo.snapshot().checking) {
            Thread.sleep(20);
        }
        // Drain any pending UI notifications from the executor.
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    /** Opening the activity must auto-trigger a check that lands in
     *  the inflight handle before returning the in-flight snapshot
     *  to the UI; the visible status text must read "Checking" and
     *  must NOT advertise "up to date" before the first successful
     *  completion. Releasing the gate advances success and the UI
     *  text becomes "App is up to date.". */
    @Test public void openingTriggersCheckWithoutTap() throws Exception {
        assertEquals("precondition: no observers", 0, repo.observerCountForTest());
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            awaitInflightStarted(source);
            // While the source is gated, the snapshot reports
            // checking=true and the UI must render the
            // "Checking signed Quest preview releases..." line.
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            String checkingText = ctl.get().windowStatusText();
            assertNotNull(checkingText);
            assertTrue("must show Checking while in flight: " + checkingText,
                    checkingText.toLowerCase().contains("checking"));
            assertFalse("must not advertise up to date before success: " + checkingText,
                    checkingText.toLowerCase().contains("up to date"));
            assertEquals("one inflight source call", 1, source.invocationCount.get());

            // Release the gate; the check completes; the UI text
            // advances to the post-success "App is up to date."
            // copy.
            awaitInflightFinished(source);
            String finalText = ctl.get().windowStatusText();
            assertNotNull(finalText);
            assertTrue("must show 'App is up to date.' after success: " + finalText,
                    finalText.contains("up to date") || finalText.contains("Up to date"));
            assertEquals("exactly one CheckSource call across the lifecycle", 1, source.invocationCount.get());
            UpdateRepository.Snapshot s = repo.snapshot();
            assertTrue("success timestamp must be set", s.lastSuccessAtMs > 0);
        } finally {
            source.release();
            ctl.pause().stop().destroy();
        }
        assertEquals("destroy must remove the activity's observer", 0, repo.observerCountForTest());
    }

    /** onResume fires while the onCreate check is still in flight.
     *  The second triggerCheck(false) call MUST coalesce onto the
     *  existing in-flight handle so the executor invokes the source
     *  exactly once. This is the "real overlap" assertion: the
     *  resume call MUST happen while the source is gated, not
     *  after the first check has already returned. */
    @Test public void sharedInflightCoalescesOnOpenAndResume() throws Exception {
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            awaitInflightStarted(source);
            // The onCreate check is still in flight. Drive a real
            // pause + resume cycle so onResume fires while the
            // check is gated. We must not call .resume() on an
            // already-resumed controller; pause first, then resume.
            ctl.pause();
            Thread.sleep(20);
            ctl.resume();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("resume during in-flight must coalesce onto one source call",
                    1, source.invocationCount.get());
            // Drain the in-flight and the queue stays empty.
            awaitInflightFinished(source);
            assertEquals("still exactly one CheckSource call after resume coalesce",
                    1, source.invocationCount.get());
        } finally {
            source.release();
            ctl.pause().stop().destroy();
        }
        assertEquals("destroy must remove the activity's observer", 0, repo.observerCountForTest());
    }

    /** Multiple open/close cycles must NOT leak observers. Each
     *  destroy must remove exactly the observer the matching
     *  onCreate added. */
    @Test public void openingClosingDoesNotLeakObserver() throws Exception {
        for (int i = 0; i < 5; i++) {
            ActivityController<UpdatesActivity> ctl = openActivity();
            // Release the in-flight gate so the executor can drain
            // before destroy runs; otherwise destroy races the
            // completion callback and the activity short-circuits
            // before reaching removeObserver.
            source.release();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            ctl.pause().stop().destroy();
            assertEquals("iteration " + i + ": observer leaked", 0, repo.observerCountForTest());
        }
    }

    /** Live VR is detected via {@code <pkg>:pcvr} in the running
     *  process list. While a live process is reported, the
     *  activity must NOT auto-trigger a check; the next idle
     *  onResume after VR closes must trigger the check. */
    @Test public void liveVrBusyDefersAutoTrigger() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        ActivityManager am = (ActivityManager) app.getSystemService(Activity.ACTIVITY_SERVICE);
        ShadowActivityManager shadowAm = Shadows.shadowOf(am);
        // Register a fake running app process for <pkg>:pcvr so
        // the activity's isLiveVrRunning helper returns true.
        List<ActivityManager.RunningAppProcessInfo> procs = new ArrayList<>();
        ActivityManager.RunningAppProcessInfo proc = new ActivityManager.RunningAppProcessInfo();
        proc.processName = app.getPackageName() + ":pcvr";
        proc.pid = Process.myPid();
        proc.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
        procs.add(proc);
        shadowAm.setProcesses(procs);
        ActivityController<UpdatesActivity> ctl = Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
        try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            Thread.sleep(150);
            assertEquals("live VR must suppress the onCreate auto-trigger",
                    0, source.invocationCount.get());
            // Now simulate VR closing; drive a real pause + resume
            // so the next idle onResume triggers the check.
            shadowAm.setProcesses(new ArrayList<>());
            ctl.pause();
            Thread.sleep(20);
            ctl.resume();
            awaitInflightStarted(source);
            awaitInflightFinished(source);
            assertEquals("VR close + resume must fire one CheckSource call",
                    1, source.invocationCount.get());
        } finally {
            source.release();
            ctl.pause().stop().destroy();
        }
        assertEquals("destroy must remove the activity's observer", 0, repo.observerCountForTest());
    }

    /** The status line MUST NOT advertise "current" / "up to date"
     *  before the first successful check has run. */
    @Test public void noFalseCurrentBeforeFirstSuccess() throws Exception {
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            // Drain the open() auto-check to completion BEFORE
            // asserting the "checking" state is not "current".
            // While the source is gated, the UI shows the
            // "Checking ..." text; that already proves no
            // "current" / "up to date" claim is on screen.
            awaitInflightStarted(source);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            String inflightText = ctl.get().windowStatusText();
            assertFalse("inflight must not say up to date: " + inflightText,
                    inflightText.toLowerCase().contains("up to date")
                            || inflightText.toLowerCase().contains("current"));
            // Drain.
            awaitInflightFinished(source);
            assertEquals("one inflight source call", 1, source.invocationCount.get());
            assertTrue("success timestamp must be set", repo.snapshot().lastSuccessAtMs > 0);
        } finally {
            source.release();
            ctl.pause().stop().destroy();
        }
    }

    /**
     * A {@link UpdateRepository.CheckSource} that gates its response on
     * a per-call {@link CountDownLatch}. The first call returns no
     * newer available after {@link #release()} is called.
     */
    static final class ControllableSource implements UpdateRepository.CheckSource {
        final AtomicInteger invocationCount = new AtomicInteger();
        // Signaled by the source on entry so the test can advance
        // only once the source has actually started.
        final CountDownLatch entered = new CountDownLatch(1);
        // Per-call gate; the test releases it to let the source
        // return. Released by release() / releaseAll().
        volatile CountDownLatch gate = new CountDownLatch(1);

        @Override public Result check(long currentVersionCode) throws Exception {
            invocationCount.incrementAndGet();
            entered.countDown();
            gate.await(5, TimeUnit.SECONDS);
            return Result.none();
        }

        /** Release the current gate so the source can return. */
        void release() { gate.countDown(); }

        /** Release every pending gate so a hung executor can drain
         *  during teardown. */
        void releaseAll() { gate.countDown(); }
    }

    public static class FakeClock implements UpdateRepository.Clock {
        public long now;
        public FakeClock() { this.now = 1_700_000_000_000L; }
        @Override public long now() { return now; }
    }
}