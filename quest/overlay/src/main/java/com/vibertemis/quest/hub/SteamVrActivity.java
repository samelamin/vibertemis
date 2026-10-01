package com.vibertemis.quest.hub;

import android.app.ActivityManager;
import android.app.NativeActivity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ALVR v20.14.1 OpenXR client entry point. Subclass of
 * {@link android.app.NativeActivity}; the framework calls
 * {@code ANativeActivity_onCreate} in
 * {@code lib/arm64-v8a/libalvr_client_openxr.so} via the manifest's
 * {@code android.app.lib_name} meta-data.
 *
 * <p>Runs in process {@code :pcvr} (manifest {@code android:process}). The
 * Meta Horizon OS OpenXR runtime broker multiplexes the immersive
 * session across our PID and the hub PID; we do not need an app-level
 * cross-process lock.
 *
 * <p>Microphone permission is requested by {@link MainHubActivity} before
 * this activity is launched. We do not request it here because ALVR's
 * native code asks via JNI itself; a second request would race the
 * runtime grant dialog. A denial is NOT a safe mute — voice chat simply
 * will not work, and {@link MainHubActivity} does not launch on denial.
 *
 * <p>Launch options arrive by Intent. Last decoded codec is recorded through
 * AtomicFile, never cross-process SharedPreferences.
 *
 * <p><b>Reporting a real connection issue to the hub.</b> The dispatch
 * Intent carries two values with it:
 * <ul>
 *   <li>{@link MainHubActivity#EXTRA_PCVR_LAUNCH_NONCE} — the random
 *       one-shot correlation id minted for exactly this dispatch;</li>
 *   <li>{@link MainHubActivity#EXTRA_PCVR_HOST_PIN} — the public pairing
 *       certificate pin.</li>
 * </ul>
 * Neither is a credential, and no credential is ever placed in an
 * Intent. When (and only when) the native runtime hits an ACTUAL config
 * mismatch or a failed restart helper it calls
 * {@link #onPcvrConnectionIssue(String)} from JNI with
 * {@code restart_required} or {@code restart_failed}; any other string is
 * dropped here and never reaches the hub.
 *
 * <p>The callback is a JNI thread, so it hops to the UI thread, is
 * one-shot (a native retry cannot produce a second Intent), returns an
 * explicit {@code MainHubActivity} Intent with
 * {@code CLEAR_TOP|SINGLE_TOP} — so a live hub receives it through
 * {@code onNewIntent} without a second instance — and then finishes this
 * activity gracefully. {@link #onDestroy()} still runs the upstream
 * {@code super.onDestroy()} that joins the native render thread before
 * it kills our own pid, and the hub independently proves that pid gone
 * before dispatching anything new, so the return never relies on how
 * fast the user reacts.
 */
public class SteamVrActivity extends NativeActivity {

    private static final String TAG = "SteamVrActivity";

    /** Process suffix declared in AndroidManifest.xml. */
    static final String PROCESS_SUFFIX = ":pcvr";

    /** Called once by the native decoder after the first successfully decoded frame. */
    public void onPcvrCodec(String codec) {
        com.vibertemis.quest.pcvr.PcvrHistory.decoded(this, codec);
    }

    /**
     * JNI entry point for a real PCVR connection issue.
     *
     * <p>Invoked from the native runtime (not the UI thread) with one of
     * the two known values. The value is validated against the same
     * {@link PcvrReturnGate} vocabulary the hub validates against, so an
     * unknown or malformed string can never be forwarded as a known
     * state.
     */
    public void onPcvrConnectionIssue(String issue) {
        final String normalized = PcvrReturnGate.normalizeIssue(issue);
        if (normalized == null) {
            // Bounded log: this is a JNI boundary, so the raw string is
            // untrusted native input of unknown length. Report that the
            // value was unusable, never the value itself.
            Log.w(TAG, "Ignoring unknown PCVR connection issue from native");
            return;
        }
        runOnUiThread(() -> reportPcvrConnectionIssue(normalized));
    }

    /** One-shot, UI-thread half of {@link #onPcvrConnectionIssue}. */
    private void reportPcvrConnectionIssue(String issue) {
        if (isFinishing() || isDestroyed()) return;
        if (!issueReported.compareAndSet(false, true)) {
            Log.i(TAG, "PCVR connection issue already reported (" + issue + ")");
            return;
        }
        Intent back = new Intent(this, MainHubActivity.class);
        back.setAction(MainHubActivity.ACTION_PCVR_RETURN);
        // CLEAR_TOP|SINGLE_TOP: a live hub is brought forward and gets
        // this through onNewIntent instead of a second hub instance.
        back.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        back.putExtra(MainHubActivity.EXTRA_PCVR_ISSUE, issue);
        back.putExtra(MainHubActivity.EXTRA_PCVR_LAUNCH_NONCE, launchNonce == null ? "" : launchNonce);
        back.putExtra(MainHubActivity.EXTRA_PCVR_HOST_PIN, hostPin == null ? "" : hostPin);
        // The hub proves this pid gone before any new immersive dispatch.
        back.putExtra(MainHubActivity.EXTRA_PCVR_OLD_PID, Process.myPid());
        Log.i(TAG, "Returning PCVR connection issue " + issue + " to the hub");
        try {
            startActivity(back);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not return the PCVR issue to the hub: " + e.getMessage());
        }
        // Finish gracefully: onDestroy joins the native render thread
        // and only then kills our own pid.
        finish();
    }

    /** Correlation identity handed to us by the dispatch. Neither is a
     *  credential; both are echoed back so the hub can validate the
     *  callback against the pairing it holds now. */
    private String launchNonce;
    private String hostPin;
    /** A native retry must not produce a second return Intent. */
    private final AtomicBoolean issueReported = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "SteamVrActivity onCreate (lib_name=alvr_client_openxr)");

        Intent launch = getIntent();
        if (launch != null) {
            launchNonce = launch.getStringExtra(MainHubActivity.EXTRA_PCVR_LAUNCH_NONCE);
            hostPin = launch.getStringExtra(MainHubActivity.EXTRA_PCVR_HOST_PIN);
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                  | View.SYSTEM_UI_FLAG_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override
    public void onDestroy() {
        // NativeActivity.onDestroy dispatches the native Destroy event and
        // joins the render thread upstream. We must let that complete
        // before terminating the process — killing before teardown returns
        // can leave native code live on the GPU/compositor.
        //
        // This ordering is also what makes the return contract race-free:
        // the hub may start its process gate as soon as the return Intent
        // lands, but the gate only opens once this join has finished and
        // our pid has actually disappeared.
        super.onDestroy();

        if (isOwnPcvrProcess()) {
            int myPid = Process.myPid();
            Log.i(TAG, "Verified :pcvr; killing own pid=" + myPid);
            Process.killProcess(myPid);
        } else {
            Log.w(TAG, "Process identity not :pcvr; refusing to killProcess");
        }
    }

    /**
     * True if and only if this Activity is running in the {@code :pcvr}
     * process. Fail-closed: any uncertainty returns false.
     *
     * <p>API 28+ uses {@link Application#getProcessName()} and requires
     * exact equality with {@code context.getPackageName() + ":pcvr"}.
     * API 26/27 falls back to scanning
     * {@link ActivityManager.RunningAppProcessInfo} for a row whose
     * {@code pid} matches {@link Process#myPid()} and whose
     * {@code processName} exactly equals that same expected name.
     */
    boolean isOwnPcvrProcess() {
        Context ctx = getApplicationContext();
        if (ctx == null) {
            return false;
        }
        String expected = ctx.getPackageName() + PROCESS_SUFFIX;
        int myPid = Process.myPid();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Application.getProcessName() is a static helper added in
            // API 28. Calling it directly avoids the previous instance
            // round-trip and the null-on-no-Application path; the
            // fail-closed exact-equality guard against the expected
            // "<package>:pcvr" string is unchanged.
            String name = Application.getProcessName();
            return name != null && name.equals(expected);
        }

        // API 26/27 fallback.
        ActivityManager am = (ActivityManager)
                ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return false;
        }
        List<ActivityManager.RunningAppProcessInfo> procs =
                am.getRunningAppProcesses();
        if (procs == null) {
            return false;
        }
        for (ActivityManager.RunningAppProcessInfo p : procs) {
            if (p.pid == myPid
                    && p.processName != null
                    && p.processName.equals(expected)) {
                return true;
            }
        }
        return false;
    }
}
