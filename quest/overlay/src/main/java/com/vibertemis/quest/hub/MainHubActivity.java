package com.vibertemis.quest.hub;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.preferences.StreamSettings;

/**
 * Panel-mode launch hub. NOT itself immersive / NOT an OpenXR activity.
 *
 * <p>Two rows, exposed only on capable devices:
 * <ul>
 *   <li><b>Screen gaming</b> — launches the existing {@link PcView}. The
 *       hub does NOT have to know whether the user is on a headset;
 *       {@code PreferenceConfiguration.readPreferences} gates
 *       {@code enableVrMode} against {@link VrCapabilities#isHeadset} so
 *       the upstream {@code ServerHelper} routes phones to flat
 *       {@code com.limelight.Game} and headsets to
 *       {@code com.limelight.GameXR}.</li>
 *   <li><b>SteamVR (PCVR)</b> — launches {@link SteamVrActivity} only if
 *       the device reports VR headtracking AND we hold
 *       {@code RECORD_AUDIO} permission. The hub requests mic permission
 *       up front (instead of letting ALVR race with our NativeActivity
 *       subclass over the same runtime grant dialog). On denial we do
 *       NOT launch; the user sees a clear recovery dialog with a
 *       "Open app settings" shortcut for permanent-denial states.</li>
 * </ul>
 *
 * <p>Mode switching is only valid from the idle hub. Both target
 * activities own their own XR session lifecycle; the hub does not try
 * to forcibly terminate either.
 *
 * <p>The hub never auto-launches from {@code onResume}. The only path to
 * a target activity is an explicit user tap, and the hub guards each
 * tap against in-flight permission requests and pending launches so a
 * rapid double-tap or an asynchronous permission result for a different
 * request cannot start two activities at once.
 *
 * <p><b>Launch / permission guard lifecycle.</b> The
 * {@code launchPending} flag is set whenever the hub dispatches
 * {@code startActivity(...)} for Screen, PCVR, Settings, or Setup. It
 * is cleared by {@code onResume} ONLY when the hub actually left for
 * that launched activity (the {@code launchLeftHub} flag set in
 * {@code onPause} is the proof). Permission dialogs MAY pause the
 * hub on some platform versions before {@code launchPending} is
 * set, and the subsequent permission-return {@code onResume} must
 * therefore NOT clear the guard: a permission-driven pause must not
 * look like a launched-activity pause. Result: a rapid tap during
 * the permission round-trip cannot launch a second activity, and a
 * tap immediately after the permission grant still respects the
 * in-flight launch.
 */
public class MainHubActivity extends Activity {

    private static final String TAG = "MainHubActivity";
    static final int REQ_MIC_FOR_STEAMVR_FOR_TEST = 0x5356;
    private static final int REQ_MIC_FOR_STEAMVR = REQ_MIC_FOR_STEAMVR_FOR_TEST;

    private static final String STATE_REQUEST_PENDING = "vq_hub_request_pending";

    private HubPrefs prefs;
    private SettingsController settingsController;

    private boolean requestPending;
    private boolean launchPending;
    /**
     * Set true by {@code onPause} when {@code launchPending} was true
     * (i.e. the hub actually paused because the user was sent to a
     * launched activity, NOT because a permission dialog overlay
     * appeared). {@code onResume} uses this to decide whether to
     * clear {@code launchPending}.
     */
    private boolean launchLeftHub;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            requestPending = savedInstanceState.getBoolean(STATE_REQUEST_PENDING, false);
        }

        setContentView(R.layout.vibertemis_hub);
        prefs = new HubPrefs(this);
        settingsController = new SettingsController(this);
        // If a preset Apply or a manual edit fires while the hub is
        // visible (rare, but possible if the user comes back from the
        // settings screen with the app still in the foreground), the
        // cached snapshot is updated and the hub status line refreshes
        // without requiring a full onResume cycle.
        settingsController.addObserver(new SettingsController.Observer() {
            @Override
            public void onUpstreamPreferenceChanged(String key,
                                                     ProfileApplier.CurrentSettings snapshot) {
                if (!isFinishing() && !isDestroyed()) {
                    renderStatus();
                }
            }
        });

        Button screenBtn = findViewById(R.id.hub_btn_screen);
        Button steamvrBtn = findViewById(R.id.hub_btn_steamvr);
        Button settingsBtn = findViewById(R.id.hub_btn_settings);
        Button setupBtn = findViewById(R.id.hub_btn_setup);
        TextView subtitle = findViewById(R.id.hub_subtitle);
        TextView steamvrNote = findViewById(R.id.hub_steamvr_note);

        boolean canSteamVr = VrCapabilities.isHeadset(this);
        if (!canSteamVr) {
            // On phones the PCVR row stays hidden entirely so no
            // disabled-button affordance is offered.
            steamvrBtn.setVisibility(View.GONE);
            steamvrNote.setVisibility(View.GONE);
        } else {
            steamvrNote.setText(R.string.hub_steamvr_companion_reminder);
            steamvrNote.setVisibility(View.VISIBLE);
        }

        screenBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchScreenGaming();
            }
        });
        steamvrBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!VrCapabilities.isHeadset(MainHubActivity.this)) {
                    Toast.makeText(MainHubActivity.this,
                            R.string.hub_steamvr_unsupported,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (launchPending || requestPending) {
                    return;
                }
                if (hasMicPermission()) {
                    launchSteamVr();
                } else {
                    requestMicForSteamVr();
                }
            }
        });
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchStreamingSettings();
            }
        });
        setupBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchSetup();
            }
        });

        subtitle.setText(R.string.hub_subtitle_companion_required);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Only the request guard survives recreation. The launch guard
        // is released on the next onResume after the user explicitly
        // returns to the hub; recreating mid-launch clears the in-flight
        // launch and the new instance is ready for a fresh tap.
        outState.putBoolean(STATE_REQUEST_PENDING, requestPending);
    }

    @Override
    protected void onResume() {
        super.onResume();
        settingsController.refresh();
        settingsController.register();
        renderStatus();
        // Release the launch guard ONLY when we actually left the hub
        // for a launched activity. Permission dialogs MAY pause the
        // hub on some platform versions before launchPending is set,
        // so a permission-driven pause must not look like a
        // launched-activity pause — the permission-return onResume
        // therefore keeps the guard set when launchLeftHub is false.
        if (launchLeftHub) {
            launchPending = false;
            launchLeftHub = false;
        }
        // The hub NEVER auto-launches from onResume. Returning from the
        // streaming settings screen, the setup screen, the system
        // settings screen, or the PCVR / Screen gaming activity is not a
        // signal to launch anything. Status refresh only.
    }

    @Override
    protected void onPause() {
        super.onPause();
        settingsController.unregister();
        // Mark that we actually left for a launched activity. A
        // permission dialog pause may arrive BEFORE launchPending is
        // set (the dialog races with the activity transition), so on
        // that path launchLeftHub stays false and the upcoming
        // onResume does not release the guard. Permission-driven
        // pauses never set launchLeftHub; launched-activity pauses
        // do.
        if (launchPending) {
            launchLeftHub = true;
        }
    }

    private void renderStatus() {
        TextView status = findViewById(R.id.hub_status);
        try {
            ProfileApplier.CurrentSettings s = settingsController.current();
            String label = getString(R.string.hub_status_screen_summary,
                    s.resolution, s.fps,
                    ProfileApplier.formatMbps(s.bitrateKbps),
                    s.videoFormat);
            status.setText(label);
        } catch (Throwable t) {
            status.setText(R.string.hub_status_unknown);
        }
    }

    private boolean hasMicPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMicForSteamVr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        requestPending = true;
        try {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_MIC_FOR_STEAMVR);
        } catch (Throwable t) {
            requestPending = false;
            Log.w(TAG, "Failed to dispatch mic permission request: " + t.getMessage());
            Toast.makeText(this, R.string.mic_recovery_msg, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC_FOR_STEAMVR) return;

        // Ignore duplicate or stale results. The request flag is set
        // only at dispatch time and cleared here, so a re-delivery of
        // the same result (rare but possible) is a no-op.
        if (!requestPending) return;
        requestPending = false;

        // Ignore stale / mismatched results. The current request must
        // target RECORD_AUDIO and have at least one grant result, and
        // we must still be a headset with permission granted.
        if (permissions == null || permissions.length == 0
                || !Manifest.permission.RECORD_AUDIO.equals(permissions[0])) {
            return;
        }
        if (grantResults == null || grantResults.length == 0) {
            // System killed the activity before delivering a result;
            // the user has to retry manually.
            showMicRecoveryDialog(false);
            return;
        }
        if (!VrCapabilities.isHeadset(this)) {
            return;
        }
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            boolean permanentlyDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && !shouldShowRequestPermissionRationale(
                            Manifest.permission.RECORD_AUDIO);
            Log.w(TAG, "PCVR not started: RECORD_AUDIO denied"
                    + (permanentlyDenied ? " (permanent)" : ""));
            Toast.makeText(this, R.string.hub_mic_denied, Toast.LENGTH_LONG).show();
            showMicRecoveryDialog(permanentlyDenied);
            return;
        }
        if (!hasMicPermission()) {
            // Defensive: the framework granted us but a re-check says no.
            // Do not launch.
            return;
        }
        launchSteamVr();
    }

    private void showMicRecoveryDialog(final boolean permanentlyDenied) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.mic_recovery_title)
                .setMessage(R.string.mic_recovery_msg)
                .setNegativeButton(R.string.mic_recovery_dismiss, null);
        if (permanentlyDenied) {
            b.setPositiveButton(R.string.mic_recovery_open_settings,
                    (d, w) -> openAppSettings());
            b.setNeutralButton(R.string.mic_recovery_retry,
                    (d, w) -> requestMicForSteamVr());
        } else {
            b.setPositiveButton(R.string.mic_recovery_retry,
                    (d, w) -> requestMicForSteamVr());
        }
        b.show();
    }

    private void openAppSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.fromParts("package", getPackageName(), null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "App settings not available: " + e.getMessage());
            Toast.makeText(this, R.string.mic_recovery_settings_failed,
                    Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            Log.w(TAG, "App settings dispatch security exception: " + e.getMessage());
            Toast.makeText(this, R.string.mic_recovery_settings_failed,
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Launch helpers. Every entry point sets {@code launchPending}
     * synchronously and clears it on a synchronous dispatch failure
     * (ActivityNotFoundException, SecurityException) so a follow-up
     * tap from the same user gesture is not silently swallowed.
     */
    private void launchScreenGaming() {
        if (launchPending || requestPending) return;
        Log.i(TAG, "Launching Screen gaming -> PcView");
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(getPackageName(), PcView.class.getName()));
            launchPending = true;
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            launchPending = false;
            Log.w(TAG, "PcView not available: " + e.getMessage());
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            launchPending = false;
            Log.w(TAG, "PcView dispatch security exception: " + e.getMessage());
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void launchStreamingSettings() {
        if (launchPending || requestPending) return;
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(getPackageName(),
                    StreamSettings.class.getName()));
            launchPending = true;
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            launchPending = false;
            Log.w(TAG, "Streaming settings not available: " + e.getMessage());
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            launchPending = false;
            Log.w(TAG, "Streaming settings dispatch security exception: "
                    + e.getMessage());
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void launchSetup() {
        if (launchPending || requestPending) return;
        try {
            Intent i = new Intent(MainHubActivity.this, SetupActivity.class);
            launchPending = true;
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            launchPending = false;
            Log.w(TAG, "Setup not available: " + e.getMessage());
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            launchPending = false;
            Log.w(TAG, "Setup dispatch security exception: " + e.getMessage());
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void launchSteamVr() {
        if (launchPending) return;
        if (!VrCapabilities.isHeadset(this)) {
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
            return;
        }
        Log.i(TAG, "Launching PCVR -> SteamVrActivity");
        try {
            Intent i = new Intent();
            i.setComponent(new ComponentName(getPackageName(),
                    SteamVrActivity.class.getName()));
            i.setAction(Intent.ACTION_MAIN);
            i.addCategory("com.oculus.intent.category.VR");
            i.addCategory("org.khronos.openxr.intent.category.IMMERSIVE_HMD");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchPending = true;
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            launchPending = false;
            Log.w(TAG, "SteamVrActivity not available: " + e.getMessage());
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            launchPending = false;
            Log.w(TAG, "SteamVrActivity dispatch security exception: "
                    + e.getMessage());
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
        }
    }
}