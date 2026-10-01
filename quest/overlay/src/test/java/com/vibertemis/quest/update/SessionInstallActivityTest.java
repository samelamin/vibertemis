package com.vibertemis.quest.update;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Process;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * A verified update commits itself: an asynchronously prepared session that
 * reaches {@code PREPARED} after the first resume must still be committed by
 * the same activity once it is resumed and focused, with no second Install
 * button and no user action of any kind.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SessionInstallActivityTest {

    private static final long INSTALLED = 10L;
    private static final long TARGET = 11L;
    private static final byte[] PAYLOAD = {0x50, 0x4b, 0x03, 0x04, 0x11, 0x22, 0x33, 0x44};

    private Context context;
    private File apk;
    private ActivityController<SessionInstallActivity> controller;

    @Before public void setUp() throws Exception {
        context = UpdateTestFixture.context();
        UpdateTestFixture.installSigningIdentity(context, INSTALLED, true);
        File staged = new File(context.getCacheDir(), "updates/test.apk");
        //noinspection ResultOfMethodCallIgnored
        staged.getParentFile().mkdirs();
        UpdateTestFixture.writeBytes(staged, PAYLOAD);
        apk = staged.getCanonicalFile();
        UpdateTestFixture.publishArchiveInfo(context, apk, TARGET);
        openVrGate();
    }

    @Test public void preparedSessionCommitsItselfOnResumeAndFocusWithoutASecondButton() {
        Intent intent = SessionInstallActivity.newIntent(context, apk.getAbsolutePath(),
                UpdateTestFixture.packageName(context), TARGET, PAYLOAD.length,
                UpdateTestFixture.hex(UpdateTestFixture.sha256(PAYLOAD)));

        controller = Robolectric.buildActivity(SessionInstallActivity.class, intent);
        SessionInstallActivity activity = controller.get();
        controller.create().start().resume().visible().windowFocusChanged(true);

        InstallSessionState state = InstallSessionState.get();
        awaitStage(state, InstallSessionState.Stage.COMMITTED, 5000L);

        assertEquals("a resumed, focused PREPARED session must commit itself",
                InstallSessionState.Stage.COMMITTED, state.stage());
        int sessionId = state.sessionId();
        assertTrue("commit must name the prepared session, was " + sessionId, sessionId > 0);
        assertTrue("PackageInstaller must still hold session " + sessionId, hasSession(sessionId));
        assertTrue("there is no second Install button",
                UpdateTestFixture.launchedIntents(activity).isEmpty());

        controller.pause().resume().windowFocusChanged(true);
        ShadowLooper.idleMainLooper();

        assertEquals("a further resume must not move a committed attempt",
                InstallSessionState.Stage.COMMITTED, state.stage());
        assertEquals("a further focus must not commit a second session",
                sessionId, state.sessionId());
    }

    @After public void tearDown() {
        if (controller != null) controller.pause().stop().destroy();
        InstallSessionState.get().fail("test cleanup", 0);
        assertEquals("the activity must not stay attached to the singleton", 0,
                InstallSessionState.get().listenerCountForTest());
    }

    private void awaitStage(InstallSessionState state, InstallSessionState.Stage stage, long millis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        do {
            ShadowLooper.idleMainLooper();
            if (state.stage() == stage) return;
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        } while (System.nanoTime() < deadline);
    }

    private boolean hasSession(int sessionId) {
        List<PackageInstaller.SessionInfo> sessions =
                context.getPackageManager().getPackageInstaller().getMySessions();
        if (sessions == null) return false;
        for (PackageInstaller.SessionInfo info : sessions) {
            if (info != null && info.getSessionId() == sessionId) return true;
        }
        return false;
    }

    private void openVrGate() {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        assertNotNull("ActivityManager is required for the VR gate", am);
        ActivityManager.RunningAppProcessInfo own = new ActivityManager.RunningAppProcessInfo();
        own.processName = context.getPackageName();
        own.pid = Process.myPid();
        own.uid = Process.myUid();
        List<ActivityManager.RunningAppProcessInfo> processes = new ArrayList<>();
        processes.add(own);
        Shadows.shadowOf(am).setProcesses(processes);
        assertTrue("only this app's own process may be running",
                InstallSessionState.vrIdle(context));
    }
}
