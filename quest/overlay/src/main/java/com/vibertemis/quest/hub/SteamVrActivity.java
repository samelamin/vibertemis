package com.vibertemis.quest.hub;

import android.app.ActivityManager;
import android.app.NativeActivity;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.util.List;

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
 */
public class SteamVrActivity extends NativeActivity {

    private static final String TAG = "SteamVrActivity";

    /** Process suffix declared in AndroidManifest.xml. */
    static final String PROCESS_SUFFIX = ":pcvr";

    /** Called once by the native decoder after the first successfully decoded frame. */
    public void onPcvrCodec(String codec) {
        com.vibertemis.quest.pcvr.PcvrHistory.decoded(this, codec);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "SteamVrActivity onCreate (lib_name=alvr_client_openxr)");

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
