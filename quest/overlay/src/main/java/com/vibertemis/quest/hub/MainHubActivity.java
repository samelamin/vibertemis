package com.vibertemis.quest.hub;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.PcView;
import com.limelight.R;

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
 *   <li><b>SteamVR (PCVR)</b> — launches {@link SteamVrActivity} only if the
 *       device reports VR headtracking AND we hold {@code RECORD_AUDIO}
 *       permission. The hub requests mic permission up front (instead of
 *       letting ALVR race with our NativeActivity subclass over the same
 *       runtime grant dialog). On denial we do NOT launch; the user sees
 *       a clear "PCVR not started" message and can retry.</li>
 * </ul>
 *
 * <p>Mode switching is only valid from the idle hub. Both target activities
 * own their own XR session lifecycle; the hub does not try to forcibly
 * terminate either.
 */
public class MainHubActivity extends Activity {

    private static final String TAG = "MainHubActivity";
    private static final int REQ_MIC_FOR_STEAMVR = 0x5356;

    private HubPrefs prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.vibertemis_hub);
        prefs = new HubPrefs(this);

        Button screenBtn = findViewById(R.id.hub_btn_screen);
        Button steamvrBtn = findViewById(R.id.hub_btn_steamvr);
        Button settingsBtn = findViewById(R.id.hub_btn_settings);
        TextView subtitle = findViewById(R.id.hub_subtitle);
        TextView steamvrNote = findViewById(R.id.hub_steamvr_note);

        boolean canSteamVr = VrCapabilities.isHeadset(this);
        steamvrBtn.setEnabled(canSteamVr);
        steamvrBtn.setAlpha(canSteamVr ? 1.0f : 0.4f);

        if (!canSteamVr) {
            steamvrNote.setText(R.string.hub_steamvr_unsupported);
            steamvrNote.setVisibility(View.VISIBLE);
        } else {
            steamvrNote.setText(R.string.hub_steamvr_companion_reminder);
            steamvrNote.setVisibility(View.VISIBLE);
            if (!prefs.isCompanionReminderDismissed()) {
                showCompanionReminderDialog();
            }
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
                // Wait for the mic permission result BEFORE launching.
                // We never launch PCVR without confirmation that
                // RECORD_AUDIO has been granted (or refused). The deny
                // path explicitly states "PCVR not started".
                if (!hasMicPermission()) {
                    requestMicForSteamVr();
                    return;
                }
                launchSteamVr();
            }
        });
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent();
                i.setComponent(new ComponentName(getPackageName(),
                        "com.limelight.preferences.StreamSettings"));
                startActivity(i);
            }
        });

        subtitle.setText(R.string.hub_subtitle_companion_required);
    }

    private void showCompanionReminderDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.hub_companion_dialog_title)
                .setMessage(R.string.hub_companion_dialog_msg)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.hub_companion_dialog_dismiss,
                        (d, w) -> prefs.dismissCompanionReminder())
                .show();
    }

    private boolean hasMicPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMicForSteamVr() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                REQ_MIC_FOR_STEAMVR);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC_FOR_STEAMVR) return;
        if (grantResults.length == 0) return;
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            launchSteamVr();
        } else {
            // Explicit, user-facing message: the grant was refused, so
            // PCVR was NOT started. We never launch with mic denied.
            Toast.makeText(this, R.string.hub_mic_denied, Toast.LENGTH_LONG).show();
            Log.w(TAG, "PCVR not started: RECORD_AUDIO denied");
        }
    }

    private void launchScreenGaming() {
        Log.i(TAG, "Launching Screen gaming -> PcView");
        Intent i = new Intent();
        i.setComponent(new ComponentName(getPackageName(),
                "com.limelight.PcView"));
        startActivity(i);
    }

    private void launchSteamVr() {
        Log.i(TAG, "Launching PCVR -> SteamVrActivity");
        Intent i = new Intent();
        i.setComponent(new ComponentName(getPackageName(),
                "com.vibertemis.quest.hub.SteamVrActivity"));
        i.setAction(Intent.ACTION_MAIN);
        i.addCategory("com.oculus.intent.category.VR");
        i.addCategory("org.khronos.openxr.intent.category.IMMERSIVE_HMD");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }
}
