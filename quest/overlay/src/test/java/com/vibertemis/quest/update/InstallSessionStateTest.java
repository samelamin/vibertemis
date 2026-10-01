package com.vibertemis.quest.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.robolectric.Shadows.shadowOf;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInstaller;
import android.content.pm.Signature;
import android.os.Looper;

import com.vibertemis.quest.update.InstallSessionState.Stage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Prepare/commit behaviour of {@link InstallSessionState} against the
 * real Robolectric {@code PackageInstaller} shadows: only built-in shadows
 * are used, so nothing here fakes a session, a stage or a commit.
 *
 * <p>The verify-and-write half of {@code prepare} runs on the state IO
 * thread and publishes on the main looper, so every wait here idles the
 * main looper under a bounded deadline instead of sleeping blindly.
 *
 * <p>These are isolated state tests. {@code InstallSessionState} keeps a
 * static singleton that outlives the per-test Robolectric sandbox, so each
 * test builds its own instance reflectively in {@link #setUp()} rather than
 * sharing {@code get()}. Every test then drives that instance directly:
 * nothing is delivered to a broadcast receiver and no service is started,
 * so no cross-test receiver or sandbox state is involved. {@link #tearDown()}
 * drops any session the test left staged and stops the IO executor that
 * instance owns, so no thread or queued work leaks into the next test.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 32})
public class InstallSessionStateTest {

    private static final long INSTALLED_VERSION = 10L;
    private static final long TARGET_VERSION = 11L;
    private static final long IO_SHUTDOWN_MS = 5_000L;

    private Context ctx;
    private InstallSessionState state;
    private byte[] payload;
    private File apk;

    @Before
    public void setUp() throws Exception {
        ctx = UpdateTestFixture.context();
        // A private instance, not the static singleton: the singleton survives
        // across Robolectric tests, and a stale COMMITTED stage from an earlier
        // test would decide this one before it ever calls prepare.
        state = ReflectionHelpers.callConstructor(InstallSessionState.class);
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        // Production vrIdle refuses a null process list, and Robolectric turns an
        // empty list into null, so publish one valid own main process instead.
        shadowOf(am).setProcesses(Collections.singletonList(ownProcess(ctx.getPackageName())));
        UpdateTestFixture.installSigningIdentity(ctx, INSTALLED_VERSION, true);
        payload = "vibertemis update apk payload".getBytes(StandardCharsets.UTF_8);
        apk = new File(ctx.getCacheDir(), "updates/test.apk");
        //noinspection ResultOfMethodCallIgnored
        apk.getParentFile().mkdirs();
        UpdateTestFixture.writeBytes(apk, payload);
        UpdateTestFixture.publishArchiveInfo(ctx, apk, TARGET_VERSION);
    }

    /**
     * Abandon only a session this test left staged, then stop the IO executor
     * this instance owns so no thread or pending verify-and-write work survives
     * into the next test. Nothing else is faked.
     */
    @After
    public void tearDown() {
        if (state == null) return;
        Stage s = state.stage();
        if (s == Stage.PREPARING || s == Stage.PREPARED) state.cancelBeforeCommit();
        ExecutorService io = ioExecutor(state);
        if (io == null) return;
        io.shutdownNow();
        try {
            io.awaitTermination(IO_SHUTDOWN_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService ioExecutor(InstallSessionState instance) {
        return ReflectionHelpers.getField(instance, "io");
    }

    @Test
    public void preparedSessionCommitsExactlyOnceAndOnlyWhenForeground() {
        state.prepare(ctx, target());

        awaitStage(Stage.PREPARED);
        int id = state.sessionId();
        assertTrue("a staged session must exist", id > 0);
        PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
        assertNotNull(installer.getSessionInfo(id));

        assertFalse(state.commitWhenForeground(false));
        assertEquals(Stage.PREPARED, state.stage());

        boolean committed = state.commitWhenForeground(true);
        assertTrue(commitDiagnostics(), committed);
        assertEquals(Stage.COMMITTED, state.stage());

        // Second foreground request must not commit the same session again.
        assertFalse(state.commitWhenForeground(true));
        assertEquals(Stage.COMMITTED, state.stage());
    }

    @Test
    public void wrongDigestFailsWithoutStagingASession() {
        byte[] other = "a different payload".getBytes(StandardCharsets.UTF_8);
        InstallSessionState.Target t = new InstallSessionState.Target(
                apk.getAbsolutePath(), ctx.getPackageName(), TARGET_VERSION,
                payload.length, UpdateTestFixture.hex(UpdateTestFixture.sha256(other)));

        state.prepare(ctx, t);

        awaitStage(Stage.FAILED);
        assertNull(ctx.getPackageManager().getPackageInstaller()
                .getSessionInfo(state.sessionId()));
        assertTrue("the downloaded file must survive a refused install",
                apk.isFile() && apk.length() == payload.length);
    }

    @Test
    public void wrongSignerFailsWithoutStagingASession() {
        UpdateTestFixture.publishArchiveInfo(ctx, apk, TARGET_VERSION,
                new Signature(new byte[]{4, 3, 2, 1}));

        state.prepare(ctx, target());

        awaitStage(Stage.FAILED);
        assertEquals(-1, state.sessionId());
        assertNull(ctx.getPackageManager().getPackageInstaller()
                .getSessionInfo(state.sessionId()));
        assertTrue(apk.isFile());
    }

    @Test
    public void commitWithoutInstallPermissionIsRefusedThenSucceedsOnceGranted() {
        shadowOf(ctx.getPackageManager()).setCanRequestPackageInstalls(false);
        state.prepare(ctx, target());

        awaitStage(Stage.PREPARED);

        assertFalse(state.commitWhenForeground(true));
        assertEquals(Stage.PREPARED, state.stage());
        assertNotNull("a refused commit must explain itself", state.gateReason());

        shadowOf(ctx.getPackageManager()).setCanRequestPackageInstalls(true);
        boolean committed = state.commitWhenForeground(true);
        assertTrue(commitDiagnostics(), committed);
        assertEquals(Stage.COMMITTED, state.stage());
    }

    @Test
    public void ownPcvrProcessGatesPreparedCommitUntilRemoved() {
        setRunningProcesses(ctx.getPackageName() + ":pcvr");

        state.prepare(ctx, target());

        awaitStage(Stage.PREPARED);
        assertFalse("a live own :pcvr process must hold the commit gate",
                state.commitWhenForeground(true));
        assertEquals(Stage.PREPARED, state.stage());
        assertNotNull("a gated commit must explain itself", state.gateReason());

        setRunningProcesses();
        assertTrue(commitDiagnostics(), state.commitWhenForeground(true));
        assertEquals(Stage.COMMITTED, state.stage());
    }

    private InstallSessionState.Target target() {
        return new InstallSessionState.Target(apk.getAbsolutePath(), ctx.getPackageName(),
                TARGET_VERSION, payload.length,
                UpdateTestFixture.hex(UpdateTestFixture.sha256(payload)));
    }

    /** Own main process plus the given extras, as the VR gate would see them. */
    private void setRunningProcesses(String... extraProcessNames) {
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = new ArrayList<>();
        processes.add(ownProcess(ctx.getPackageName()));
        for (String name : extraProcessNames) processes.add(ownProcess(name));
        shadowOf(am).setProcesses(processes);
    }

    private ActivityManager.RunningAppProcessInfo ownProcess(String name) {
        ActivityManager.RunningAppProcessInfo p = new ActivityManager.RunningAppProcessInfo(
                name, android.os.Process.myPid(), new String[]{ctx.getPackageName()});
        p.uid = android.os.Process.myUid();
        return p;
    }

    /**
     * Full observable state after a commit attempt, so a refused commit never
     * reports a bare false. Build it after the call, not inside the assert.
     */
    private String commitDiagnostics() {
        return "commit refused: stage=" + state.stage()
                + " message=" + state.message()
                + " gateReason=" + state.gateReason()
                + " sessionId=" + state.sessionId();
    }

    private void awaitStage(Stage want) {
        long deadline = System.currentTimeMillis() + 10_000L;
        do {
            shadowOf(Looper.getMainLooper()).idle();
            if (state.stage() == want) return;
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting for " + want);
            }
        } while (System.currentTimeMillis() < deadline);
        fail("timed out in stage " + state.stage() + ": " + state.message());
    }
}
