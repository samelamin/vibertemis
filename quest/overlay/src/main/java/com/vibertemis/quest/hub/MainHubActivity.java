package com.vibertemis.quest.hub;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
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

import java.util.List;

import com.vibertemis.quest.pcvr.HostClient;
import com.vibertemis.quest.pcvr.HostPairing;
import com.vibertemis.quest.pcvr.PairingStore;
import com.vibertemis.quest.pcvr.PcvrOptions;
import com.vibertemis.quest.pcvr.PcvrSettingsActivity;
import com.vibertemis.quest.pcvr.VrSetupDiscovery;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.preferences.StreamSettings;

/**
 * Panel-mode launch hub. NOT itself immersive / NOT an OpenXR activity.
 *
 * <p>The hub exposes a single primary <b>Connect</b> action whose target
 * is decided by {@link VrCapabilities#isHeadset(android.content.Context)} —
 * the {@code PackageManager.FEATURE_VR_HEADTRACKING} signal — and never
 * by user-visible device names or heuristics:
 * <ul>
 *   <li><b>Real headset (FEATURE_VR_HEADTRACKING true)</b> — Connect
 *       routes into the authenticated PCVR+SteamVR start
 *       ({@link SteamVrActivity}). Mic permission is requested up front
 *       (instead of letting ALVR race with our NativeActivity subclass
 *       over the same runtime grant dialog). On denial we do NOT launch;
 *       the user sees a clear recovery dialog with an "Open app
 *       settings" shortcut for permanent-denial states.</li>
 *   <li><b>Phone / TV / other non-headset</b> — Connect routes into the
 *       existing flat {@link PcView} start. The upstream
 *       {@code PreferenceConfiguration.readPreferences} gates
 *       {@code enableVrMode} against {@link VrCapabilities#isHeadset} so
 *       phones never reach {@code com.limelight.GameXR}.</li>
 * </ul>
 *
 * <p>On a headset, an explicit <b>Screen gaming</b> row is the per-launch
 * flat-screen override for the auto-detected PCVR target. On a phone,
 * the same row is hidden — the primary Connect already goes to
 * {@code PcView} and a second "screen" button would be a redundant
 * duplicate of the primary action.
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
    private boolean permissionContinuationPending;
    private boolean connectPending;
    private long restartConsentUntil;
    private boolean resumed;
    private volatile int connectGeneration;
    private com.vibertemis.quest.update.UpdateRepository updateRepository;
    private com.vibertemis.quest.update.UpdateRepository.Observer updateObserver;
    private volatile HostClient hostClient;
    private com.vibertemis.quest.pcvr.PairingSession vrBootstrap;
    private android.app.AlertDialog connectDialog;
    private final java.util.concurrent.ExecutorService connectWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
    /**
     * Transient dialogs that survive a single user action but
     * MUST be dismissed when the hub is destroyed. The setup and
     * connect dialogs have their own dedicated fields and lifecycle;
     * everything else (the Advanced picker, the post-enrollment
     * "Paired" notice, the deferred pairingNotice dialog shown on
     * resume, the VR-setup-needs-attention error dialog, the
     * connect-options picker, the mic recovery dialog, and the
     * restart-confirmation dialog) is recorded here so onDestroy
     * can dismiss them. A late callback landing on an already-
     * destroyed activity must NEVER show a new dialog.
     */
    private final java.util.List<android.app.AlertDialog> transientDialogs =
            new java.util.ArrayList<>();
    private boolean launchPending;
    /**
     * Set true by {@code onPause} when {@code launchPending} was true
     * (i.e. the hub actually paused because the user was sent to a
     * launched activity, NOT because a permission dialog overlay
     * appeared). {@code onResume} uses this to decide whether to
     * clear {@code launchPending}.
     */
    private boolean launchLeftHub;
    private String pairingNotice;
    /** The Setup-VR discovery dialog currently being shown to the
     *  user (searching / found / empty / manual). Tracked so onPause
     *  can dismiss it and onDestroy / generation bumps can ignore
     *  late discovery callbacks. */
    private android.app.AlertDialog setupDialog;
    /** Monotonic id for VR-setup discoveries. Every discovery pass
     *  bumps this counter; late callbacks from a cancelled pass see
     *  a stale id and ignore themselves. */
    private volatile int discoveryGeneration;
    /** The currently running setup discovery session. Cancellable. */
    private volatile com.vibertemis.quest.pcvr.VrSetupDiscovery runningDiscovery;

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

        Button connectBtn = findViewById(R.id.hub_btn_connect);
        Button screenBtn = findViewById(R.id.hub_btn_screen);
        Button settingsBtn = findViewById(R.id.hub_btn_settings);
        Button setupBtn = findViewById(R.id.hub_btn_setup);
        TextView subtitle = findViewById(R.id.hub_subtitle);
        TextView connectNote = findViewById(R.id.hub_connect_note);
        TextView screenNote = findViewById(R.id.hub_screen_note);
        TextView updatesBadge = findViewById(R.id.hub_updates_badge);

        boolean isHeadset = VrCapabilities.isHeadset(this);
        if (isHeadset) {
            // Headset: primary Connect goes to authenticated PCVR; the
            // Screen gaming row is the explicit per-launch flat
            // override. The companion reminder is now attached to the
            // connect-note row because every headset Connect opens
            // PCVR.
            subtitle.setText(R.string.hub_subtitle_detected_headset);
            connectNote.setText(R.string.hub_steamvr_companion_reminder);
            screenBtn.setVisibility(View.VISIBLE);
            screenNote.setVisibility(View.VISIBLE);
        } else {
            // Phone / TV: primary Connect already opens PcView, so the
            // separate Screen gaming row is hidden — surfacing it
            // would be a redundant duplicate of the primary action.
            // The companion reminder line is generic for both modes.
            subtitle.setText(R.string.hub_subtitle_detected_phone);
            connectNote.setText(R.string.hub_btn_connect_screen_summary);
            screenBtn.setVisibility(View.GONE);
            screenNote.setVisibility(View.GONE);
        }

        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onConnectTapped();
            }
        });
        screenBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchScreenGaming();
            }
        });
        settingsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                launchStreamingSettings();
            }
        });
        findViewById(R.id.hub_btn_updates).setOnClickListener(v -> {
            if (launchPending || requestPending || connectPending) return;
            try {
                launchPending = true;
                startActivity(new Intent(this, com.vibertemis.quest.update.UpdatesActivity.class));
            } catch (ActivityNotFoundException | SecurityException e) {
                launchPending = false;
                Toast.makeText(this, "Updates screen unavailable", Toast.LENGTH_LONG).show();
            }
        });
        // Shared update repository: install an observer that re-renders
        // the badge when the background check produces new state. We
        // never cancel the shared check from here; the repository owns
        // its executor.
        com.vibertemis.quest.update.UpdateRepository repository =
                com.vibertemis.quest.update.UpdateRepositoryProvider.get(getApplicationContext());
        if (repository != null) {
            updateRepository = repository;
            updateObserver = snap -> runOnUiThread(() -> { if (!isDestroyed()) renderUpdatesBadge(snap); });
            repository.addObserver(updateObserver);
            renderUpdatesBadge(repository.snapshot());
        } else {
            updatesBadge.setVisibility(View.GONE);
        }
        if (VrCapabilities.isHeadset(this)) setupBtn.setText("Setup VR");
        setupBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (VrCapabilities.isHeadset(MainHubActivity.this)) showVrSetup(); else launchSetup();
            }
        });
    }

    /**
     * Primary Connect entry. The hub auto-detects device class and
     * routes to the authenticated PCVR start (headset) or to the
     * existing flat {@link PcView} start (everything else). Mic
     * permission is only required on a headset — phone Connect never
     * asks for it because the flat PcView path does not need it.
     */
    private void onConnectTapped() {
        if (launchPending || requestPending || connectPending) {
            return;
        }
        if (!VrCapabilities.isHeadset(this)) {
            // Phone / TV: route the primary Connect to flat PcView.
            // We intentionally do NOT request mic permission here — the
            // flat path does not need it, and asking on a phone would
            // surface an unrelated permission dialog that does not
            // belong to the user's selected action.
            launchScreenGaming();
            return;
        }
        if (hasMicPermission()) {
            launchSteamVr();
        } else {
            requestMicForSteamVr();
        }
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
        resumed = true;
        if (pairingNotice != null) {
            trackDialog(new android.app.AlertDialog.Builder(this).setTitle("VR pairing").setMessage(pairingNotice).setPositiveButton("Done", null).show());
            pairingNotice = null;
        }
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
        // Continue only an explicit Connect whose permission result arrived
        // while paused. Ordinary resumes never initiate a VR connection.
        if (permissionContinuationPending) {
            permissionContinuationPending = false;
            if (!isFinishing() && !isDestroyed() && hasMicPermission()
                    && VrCapabilities.isHeadset(this)) launchSteamVr();
        }
        triggerUpdateCheckIfIdle(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // Removing the headset to approve on the PC must not cancel enrollment.
        // Actual VR launch requests still cancel when leaving the foreground.
        if (vrBootstrap == null) cancelHostConnection();
        // Setup VR discovery is a UI affordance: it is always
        // bounded to the hub lifetime. Cancelling here protects the
        // hub against a stale callback landing after the user
        // dismissed the picker. Enrollment is intentionally NOT
        // cancelled here: the user may take off the headset to
        // approve on the PC and the request must survive the pause.
        cancelVrSetupDiscovery();
        if (setupDialog != null) { try { setupDialog.dismiss(); } catch (Exception ignored) { } setupDialog = null; }
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

    /** Register a transient dialog so it is dismissed on destroy.
     *  The dialog itself is returned so call sites can keep the
     *  fluent {@code .show()} pattern. Late callbacks that try
     *  to show a new dialog after destroy return null from the
     *  {@link #showLateDialogIfAlive(android.app.AlertDialog)}
     *  guard below. */
    private android.app.AlertDialog trackDialog(android.app.AlertDialog d) {
        if (d != null) {
            transientDialogs.add(d);
            d.setOnDismissListener(ignored -> transientDialogs.remove(d));
        }
        return d;
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
        if (!resumed) permissionContinuationPending = true;
        else launchSteamVr();
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
        trackDialog(b.show());
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
        if (launchPending || requestPending || connectPending) return;
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
        if (launchPending || requestPending || connectPending) return;
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
        if (launchPending || requestPending || connectPending) return;
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

    protected boolean hasPairedHost() { return new PairingStore(this).hasPairing(); }
    protected HostPairing loadHostPairing() throws Exception { return new PairingStore(getApplicationContext()).load(); }
    protected HostClient createHostClient() { return new HostClient(); }
    protected String loadNativeHeadsetIdentity() throws Exception {
        return com.vibertemis.quest.pcvr.NativePeerIdentity.loadOrCreate(getApplicationContext());
    }

    /** Discovery seam so tests can inject a fake NSD driver.
     *  Production always uses the real {@link android.net.nsd.NsdManager}. */
    protected com.vibertemis.quest.pcvr.VrSetupDiscovery createVrSetupDiscovery() {
        return new com.vibertemis.quest.pcvr.VrSetupDiscovery(this);
    }

    /** Pairing-session factory seam. Tests override this to inject
     *  a deterministic fake {@link com.vibertemis.quest.pcvr.PairingSession}
     *  so the enrollment lifecycle (code callback, deadline, save,
     *  cancel) can be driven without the real TLS server. Production
     *  returns a fresh {@link com.vibertemis.quest.pcvr.StandalonePairingClient}
     *  for every call. */
    protected com.vibertemis.quest.pcvr.PairingSession createStandalonePairingClient() {
        return new com.vibertemis.quest.pcvr.StandalonePairingClient();
    }

    /** PairingStore factory seam. Production wraps AndroidKeyStore;
     *  tests inject a deterministic AES key via the package-private
     *  constructor so the save path can be verified without a real
     *  keystore. */
    protected com.vibertemis.quest.pcvr.PairingStore createPairingStore() {
        return new com.vibertemis.quest.pcvr.PairingStore(getApplicationContext());
    }

    protected boolean usesNativeRuntime() { return PcvrOptions.PYROWAVE_BUILD; }

    private void launchSteamVr() {
        if (launchPending || connectPending || !VrCapabilities.isHeadset(this) || !hasMicPermission()) return;
        if (!hasPairedHost()) { showVrSetup(); return; }
        if (!usesNativeRuntime()) { startPcvrConnection(); return; }
        confirmPcvrRestart(this::startPcvrConnection);
    }

    private void showVrAdvanced() {
        if (!resumed || connectPending || launchPending || requestPending) return;
        trackDialog(new android.app.AlertDialog.Builder(this).setTitle("Advanced VR pairing")
            .setMessage("Use a pairing file for an older host, or open VR manually if the PC is already prepared.")
            .setPositiveButton("Import pairing file", (d,w) -> {
                if (!resumed || connectPending || launchPending) return;
                launchPending = true;
                try {
                    startActivity(new Intent(this, PcvrSettingsActivity.class)
                            .putExtra(PcvrSettingsActivity.EXTRA_MANUAL_PAIRING, true));
                } catch (android.content.ActivityNotFoundException | SecurityException e) {
                    launchPending = false;
                    Toast.makeText(this, "Could not open pairing settings. Try again.", Toast.LENGTH_LONG).show();
                }
            })
            .setNeutralButton("Manual VR", (d,w) -> {
                if(!resumed || connectPending || launchPending || !VrCapabilities.isHeadset(this) || !hasMicPermission()) return;
                if(usesNativeRuntime()) confirmPcvrRestart(this::dispatchSteamVr); else dispatchSteamVr();
            })
            .setNegativeButton("Back", (d,w) -> { if(resumed) showVrSetup(); }).show());
    }

    /** Show the Setup VR flow. Discovers candidates via NSD and
     *  dedupes them against saved Moonlight addresses (which are
     *  treated as hints even without a saved serverCert). When
     *  nothing is found the user sees an explicit empty state with
     *  Retry / Enter address / Advanced actions. The Screen-gaming
     *  detour is intentionally NOT surfaced here: pairing VR to a
     *  PC is a separate workflow from pairing Screen gaming, and
     *  routing the user through Screen gaming would force them
     *  through a Vibeshine flow they do not need. */
    private void showVrSetup() {
        if (!resumed || isFinishing() || isDestroyed() || connectPending || launchPending) return;
        // Cancel any in-flight discovery from a previous pass.
        cancelVrSetupDiscovery();
        final int generation = ++discoveryGeneration;
        // Show the "Searching…" dialog synchronously so the user
        // gets immediate feedback. Late callbacks check generation
        // before swapping the dialog body.
        android.app.AlertDialog searching = new android.app.AlertDialog.Builder(this)
                .setTitle("Set up VR")
                .setMessage("Searching for your PC on the local network…")
                .setNegativeButton("Cancel", (d, w) -> cancelVrSetupDiscovery())
                .setOnCancelListener(d -> cancelVrSetupDiscovery())
                .show();
        setupDialog = searching;
        final com.vibertemis.quest.pcvr.VrSetupDiscovery discovery = createVrSetupDiscovery();
        runningDiscovery = discovery;
        // discovered is final, populated by the worker, then read
        // by the post-onUiThread lambda. Capture it inside an
        // effectively-final holder so the lambda compiles.
        final java.util.concurrent.atomic.AtomicReference<java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate>> discoveredRef =
                new java.util.concurrent.atomic.AtomicReference<>(
                        new java.util.ArrayList<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate>());
        // The saved Moonlight DB hint scan runs on the same
        // single-thread connectWorker as the discovery so the UI
        // thread never blocks on a potentially-expensive SQLite
        // query. The result is captured by dbHintsRef and the UI
        // only sees the merged list once both are ready.
        final java.util.concurrent.atomic.AtomicReference<java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate>> dbHintsRef =
                new java.util.concurrent.atomic.AtomicReference<>(
                        new java.util.ArrayList<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate>());
        connectWorker.execute(() -> {
            java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate> discovered =
                    new java.util.ArrayList<>();
            try {
                discovered = discovery.browse();
            } catch (java.io.IOException ignored) {
                // LAN discovery unavailable; treat as empty so the
                // user still sees the explicit empty state below.
            }
            discoveredRef.set(discovered);
            // Off-UI: scan the saved Moonlight database for hints.
            dbHintsRef.set(loadSavedMoonlightHints(discovered));
            runOnUiThread(() -> {
                if (generation != discoveryGeneration || isFinishing() || isDestroyed()) return;
                if (runningDiscovery == discovery) runningDiscovery = null;
                if (setupDialog != null && setupDialog.isShowing()) {
                    try { setupDialog.dismiss(); } catch (Exception ignored) { }
                }
                setupDialog = null;
                renderSetupCandidates(discoveredRef.get(), dbHintsRef.get(), generation);
            });
        });
    }

    /** Read saved Moonlight PC addresses off the UI thread and
     *  return them as untrusted VR-setup hints. A DB hint is
     *  skipped entirely when its IP matches a discovered
     *  candidate so the discovered custom port wins (a hostile
     *  or stale DB row never overrides the freshly-advertised
     *  service port). Hints keep the schema reserved for the
     *  picker even when they have no VR server cert: the
     *  authenticated TLS handshake still has to succeed, the hint
     *  is only a list entry.
     *
     *  <p><b>Address shape:</b>
     *  {@link com.limelight.nvstream.http.ComputerDetails.AddressTuple}
     *  carries the host as {@code a.address} (bare, no
     *  {@code host:port} concatenation) and the port as a
     *  separate {@code int} field. The stored port is the
     *  Moonlight / Sunshine GameStream port (47989 etc.), NOT a
     *  VR port, so it must NEVER be reused for the VR
     *  enrollment. Every hint uses {@link
     *  VrSetupDiscovery#DEFAULT_VR_PORT} and lets the
     *  authenticated TLS handshake confirm the real listener port
     *  on the PC. IPv6 hints are rejected because the VR
     *  discovery path only accepts IPv4 (see
     *  {@link com.vibertemis.quest.pcvr.VrSetupDiscovery#sanitize});
     *  an IPv6 row in the Moonlight DB has no useful VR mapping. */
    java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate> loadSavedMoonlightHints(
            java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate> discovered) {
        java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate> out =
                new java.util.ArrayList<>();
        if (discovered == null) return out;
        java.util.Set<String> discoveredIps = new java.util.HashSet<>();
        for (com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate c : discovered) {
            if (c != null && c.address != null) discoveredIps.add(c.address);
        }
        com.limelight.computers.ComputerDatabaseManager db = null;
        try {
            db = new com.limelight.computers.ComputerDatabaseManager(getApplicationContext());
            for (com.limelight.nvstream.http.ComputerDetails pc : db.getAllComputers()) {
                com.limelight.nvstream.http.ComputerDetails.AddressTuple a =
                        pc.activeAddress != null
                                ? pc.activeAddress
                                : (pc.manualAddress != null ? pc.manualAddress : pc.localAddress);
                if (a == null || a.address == null || a.address.isEmpty()) continue;
                String host = a.address;
                // Reject IPv6 hints. AddressTuple strips brackets
                // from IPv6 literals, so the colon count is the
                // giveaway — a single colon means IPv4 port is
                // encoded in the address (a malformed row), more
                // than one colon means IPv6.
                if (host.indexOf(':') >= 0) continue;
                // The hint port is always the VR default. The
                // stored GameStream port is never the VR port.
                int dbPort = VrSetupDiscovery.DEFAULT_VR_PORT;
                // Discovered custom port wins over the DB row for
                // the same IP. Skip the hint entirely so the user
                // sees one entry per machine, not two.
                if (discoveredIps.contains(host)) continue;
                String displayName = (pc.name == null || pc.name.isEmpty()) ? host : pc.name;
                out.add(new com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate(
                        displayName, host, dbPort));
            }
        } catch (Throwable dbScanFailure) {
            // The Moonlight SQLite layer is OPTIONAL for VR pairing.
            // A missing / locked / non-Writable / corrupt DB row
            // must NEVER leave the picker stuck on the searching
            // dialog. Swallow the failure and return whatever hints
            // we have; the caller still has the discovered list
            // and the user still has the manual-entry + advanced
            // escape hatches.
            Log.w(TAG, "saved Moonlight DB scan failed; continuing without hints", dbScanFailure);
        } finally {
            if (db != null) { try { db.close(); } catch (Exception ignored) { } }
        }
        return out;
    }

    /** Cancel the current discovery pass. Bumps the generation so
     *  late callbacks short-circuit and the VrSetupDiscovery's own
     *  cancellation flag wakes the bounded result poll within
     *  ~CANCEL_POLL_MS. Safe to call from any state. */
    private void cancelVrSetupDiscovery() {
        if (runningDiscovery != null) {
            try { runningDiscovery.cancel(); } catch (Exception ignored) { }
            runningDiscovery = null;
        }
        discoveryGeneration++;
    }

    /** Render the candidate picker. Discovered PCs come first; saved
     *  Moonlight PCs are deduped and shown second as hints even when
     *  they have no serverCert. Both lists are pre-loaded off the UI
     *  thread by {@link #loadSavedMoonlightHints}; this method only
     *  shapes and shows the picker. When nothing was found the user
     *  sees the explicit "No VR PC found" empty state with Retry /
     *  Enter address / Advanced options. Screen-gaming is NOT offered
     *  here: that route is reserved for the Screen-gaming flow,
     *  not for VR pairing. */
    private void renderSetupCandidates(java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate> discovered,
                                       java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate> dbHints,
                                       int generation) {
        if (!resumed || connectPending || launchPending || isFinishing() || isDestroyed()) return;
        if (generation != discoveryGeneration) return;
        java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate> merged = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (discovered != null) {
            for (com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate c : discovered) {
                String key = c.address + ":" + c.port;
                if (seen.add(key)) merged.add(c);
            }
        }
        // DB hints: dedup by host+port against the discovered list.
        // The hint list itself is already filtered to skip IPs that
        // matched a discovered candidate, so this loop is the second
        // line of defence against double entries from a same-host
        // collision.
        if (dbHints != null) {
            for (com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate c : dbHints) {
                String key = c.address + ":" + c.port;
                if (seen.add(key)) merged.add(c);
            }
        }
        if (merged.isEmpty()) {
            // Explicit empty state. Screen-gaming is NOT a route
            // here; VR pairing is a separate workflow that runs on
            // top of the same LAN reachability as Screen gaming
            // but uses its own approval on the PC.
            android.app.AlertDialog empty = new android.app.AlertDialog.Builder(this)
                    .setTitle("No VR PC found")
                    .setMessage("We did not find a PC running VR Host Manager on the local network. Open VR Host Manager on the PC and choose Setup VR (or Pair headset), then choose Retry. You can also enter the address by hand or import a saved pairing file under Advanced.")
                    .setPositiveButton("Retry", (d, w) -> { if (resumed && !connectPending && !launchPending) showVrSetup(); })
                    .setNeutralButton("Enter address", (d, w) -> { if (resumed && !connectPending && !launchPending) showManualEntry(); })
                    .setNegativeButton("Advanced", (d, w) -> { if (resumed && !connectPending && !launchPending) showVrAdvanced(); })
                    .show();
            setupDialog = empty;
            return;
        }
        String[] names = new String[merged.size()];
        for (int i = 0; i < names.length; i++) names[i] = merged.get(i).display();
        final VrSetupDiscovery.Candidate[] selected = merged.toArray(new VrSetupDiscovery.Candidate[0]);
        android.app.AlertDialog picker = new android.app.AlertDialog.Builder(this)
                .setTitle("Choose your PC for VR")
                .setItems(names, (d, which) -> {
                    if (generation != discoveryGeneration || !resumed || isFinishing() || isDestroyed()) return;
                    if (connectPending || launchPending) return;
                    enrollVrHost(selected[which]);
                })
                .setPositiveButton("Enter address", (d, w) -> { if (resumed && !connectPending && !launchPending) showManualEntry(); })
                .setNeutralButton("Retry", (d, w) -> { if (resumed && !connectPending && !launchPending) showVrSetup(); })
                .setNegativeButton("Advanced", (d, w) -> { if (resumed && !connectPending && !launchPending) showVrAdvanced(); })
                .show();
        setupDialog = picker;
    }

    /** Manual entry dialog. Accepts a validated PC endpoint with an
     *  optional VR port (default 28540). URI credentials, paths,
     *  queries, fragments, and invalid ports are rejected with a
     *  clear error so a typo never reaches the wire. */
    private void showManualEntry() {
        if (!resumed || connectPending || launchPending) return;
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("pc-host[:port]");
        input.setSingleLine(true);
        input.setMinHeight(80);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Enter PC address")
                .setMessage("Type the PC's local address (for example, 192.168.1.10). Default VR port is 28540.")
                .setView(input)
                .setPositiveButton("Pair", null)
                .setNegativeButton("Back", (d, w) -> { if (resumed && !connectPending && !launchPending) showVrSetup(); })
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String text = input.getText().toString().trim();
                    com.vibertemis.quest.pcvr.VrSetupDiscovery.ParsedEndpoint endpoint;
                    try {
                        endpoint = VrSetupDiscovery.parseManualEndpoint(
                                text, VrSetupDiscovery.DEFAULT_VR_PORT);
                    } catch (IllegalArgumentException ex) {
                        input.setError(ex.getMessage());
                        return;
                    }
                    dialog.dismiss();
                    // No resplit: parseManualEndpoint returns the
                    // validated host/port as a typed pair.
                    enrollVrHost(endpoint.host, endpoint.port);
                }));
        dialog.show();
        setupDialog = dialog;
    }

    /** Enrollment entry point used by the setup picker. The candidate
     *  is fully validated by {@link VrSetupDiscovery#sanitize} before
     *  this point. The enrollment driver computes the comparison
     *  code and the monotonic deadline from the server-supplied
     *  {@code ttl_seconds} (1..180). The dialog shows the code; a
     *  Handler ticks the countdown each second. The deadline cannot
     *  be extended by repeated callbacks or poll responses. */
    void enrollVrHost(com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate candidate) {
        enrollVrHost(candidate.address, candidate.port);
    }

    void enrollVrHost(String host, int port) {
        if (connectPending || launchPending || !resumed) return;
        connectPending=true;
        final int generation=++connectGeneration;
        final com.vibertemis.quest.pcvr.PairingSession bootstrap =
            createStandalonePairingClient();
        vrBootstrap=bootstrap;
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] tick = new Runnable[1];
        final long[] lastShownSecs = new long[] { -1L };
        connectDialog=trackDialog(new android.app.AlertDialog.Builder(this).setTitle("Connecting to your PC")
            .setMessage("Contacting " + host + ". Windows Setup VR enables pairing automatically. If receiving is off, choose Pair headset on the PC.")
            .setNegativeButton("Cancel",(d,w)->cancelHostConnection())
            .setOnCancelListener(d->cancelHostConnection()).show());
        connectWorker.execute(() -> {
            try {
                HostPairing enrolled = bootstrap.enroll(host, port, code -> runOnUiThread(() -> {
                    if (generation != connectGeneration || isFinishing() || isDestroyed() || connectDialog == null) return;
                    connectDialog.setTitle("Approve matching code on Windows");
                    String body = code
                            + "\n\nOn the PC, compare every character and approve. Mismatches mean a different PC; cancel and retry.";
                    connectDialog.setMessage(body);
                    android.widget.TextView message = connectDialog.findViewById(android.R.id.message);
                    if (message != null) message.setTypeface(android.graphics.Typeface.MONOSPACE);
                    // Kick the countdown. The deadline was set
                    // BEFORE this callback fired, so the first tick
                    // already observes a non-zero remaining time.
                    if (tick[0] == null) {
                        tick[0] = new Runnable() {
                            @Override public void run() {
                                if (generation != connectGeneration || isFinishing() || isDestroyed() || connectDialog == null) return;
                                long remaining = bootstrap.remainingDeadlineMs();
                                long secs = remaining / 1000L;
                                if (secs != lastShownSecs[0]) {
                                    lastShownSecs[0] = secs;
                                    StringBuilder b = new StringBuilder(code);
                                    if (secs >= 0L) {
                                        b.append("\n\nOn the PC, compare every character and approve. Mismatches mean a different PC; cancel and retry.")
                                         .append("\nTime remaining on PC window: ").append(secs).append("s.");
                                    }
                                    connectDialog.setMessage(b.toString());
                                    android.widget.TextView m = connectDialog.findViewById(android.R.id.message);
                                    if (m != null) m.setTypeface(android.graphics.Typeface.MONOSPACE);
                                }
                                if (remaining > 0L) {
                                    ui.postDelayed(this, 1000L);
                                }
                            }
                        };
                    }
                    ui.removeCallbacks(tick[0]);
                    ui.post(tick[0]);
                }));
                if (generation != connectGeneration || isFinishing() || isDestroyed()) return;
                // Persist an approved credential off the UI thread. If Cancel
                // races a save already in progress, retain that credential but
                // suppress all late UI and never start VR automatically.
                try { createPairingStore().save(enrolled); }
                catch (Exception e) {
                    throw new com.vibertemis.quest.pcvr.StandalonePairingClient.SetupFailure(
                            "Could not save VR pairing. Retry Setup VR.");
                }
                if (generation != connectGeneration || isFinishing() || isDestroyed()) return;
                runOnUiThread(() -> {
                    if (tick[0] != null) ui.removeCallbacks(tick[0]);
                    if (generation!=connectGeneration || isFinishing() || isDestroyed()) return;
                    finishHostConnection();
                    if (!resumed) {
                        pairingNotice = "Paired. Choose Connect when you're ready; SteamVR starts from the headset.";
                        return;
                    }
                    trackDialog(new android.app.AlertDialog.Builder(this).setTitle("Paired")
                        .setMessage("Your PC is paired for VR. Tap Connect on the headset to start SteamVR. SteamVR may prompt for confirmation on the PC before it restarts.")
                        .setPositiveButton("Done",null).show());
                });
            } catch(Exception e) {
                final String message;
                final boolean retryable;
                if (e instanceof com.vibertemis.quest.pcvr.StandalonePairingClient.SetupFailure) {
                    com.vibertemis.quest.pcvr.StandalonePairingClient.SetupFailure sf =
                            (com.vibertemis.quest.pcvr.StandalonePairingClient.SetupFailure) e;
                    message = sf.getMessage();
                    retryable = !com.vibertemis.quest.pcvr.StandalonePairingClient.CODE_INVALID.equals(sf.code);
                } else {
                    message = "Could not reach your PC. Check that VR Host Manager is running and the address is reachable, then retry.";
                    retryable = true;
                }
                runOnUiThread(() -> {
                    if (tick[0] != null) ui.removeCallbacks(tick[0]);
                    if(generation!=connectGeneration || isFinishing() || isDestroyed())return;
                    finishHostConnection();
                    if (!resumed) { pairingNotice = message; return; }
                    android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                            .setTitle("VR setup needs attention").setMessage(message)
                            .setNegativeButton("Close",null);
                    if (retryable) b.setPositiveButton("Retry", (d,w) -> showVrSetup());
                    trackDialog(b.show());
                });
            }
        });
    }

    private void confirmPcvrRestart(Runnable connect) {
        if (!resumed || isFinishing() || isDestroyed() || connectPending || launchPending) return;
        connectPending = true;
        final int generation = ++connectGeneration;
        connectDialog = trackDialog(new android.app.AlertDialog.Builder(this)
            .setTitle("Connect to PCVR?")
            .setMessage("SteamVR may restart to apply your headset and codec settings. Save any VR game in progress on the PC first.")
            .setNegativeButton("Cancel", (d, w) -> cancelHostConnection())
            .setOnCancelListener(d -> cancelHostConnection())
            .setPositiveButton("Connect", (d, w) -> {
                if (generation != connectGeneration || !connectPending) return;
                finishHostConnection();
                if (!resumed || isFinishing() || isDestroyed() || !VrCapabilities.isHeadset(this) || !hasMicPermission()) return;
                restartConsentUntil = android.os.SystemClock.elapsedRealtime() + 120000;
                connect.run();
            }).show());
    }

    private void startPcvrConnection() {
        if (launchPending || connectPending || !VrCapabilities.isHeadset(this) || !hasMicPermission()) return;
        if (!hasPairedHost()) { showVrSetup(); return; }
        connectPending = true;
        final int generation = ++connectGeneration;
        final HostClient client = createHostClient();
        hostClient = client;
        connectDialog = trackDialog(new android.app.AlertDialog.Builder(this)
                .setTitle("Starting PCVR")
                .setMessage("Contacting your PC and waiting for SteamVR…")
                .setNegativeButton("Cancel", (d,w) -> cancelHostConnection())
                .setOnCancelListener(d -> cancelHostConnection()).show());
        connectWorker.execute(() -> {
            try {
                HostPairing pairing = loadHostPairing();
                if (pairing == null) throw new HostClient.Failure("PAIRING", "Pair your PC again.");
                // First try the remembered endpoint without launching VR. Only
                // transport failures trigger a bounded LAN hint lookup.
                try { client.request(pairing, "GET", "/status", new byte[0]); }
                catch (java.io.IOException unreachable) {
                    if (client.isCancelled()) throw unreachable;
                    HostPairing candidate = com.vibertemis.quest.pcvr.PairedDiscovery.find(getApplicationContext(), pairing, client);
                    // Matching TXT is not proof: require the paired TLS pin and
                    // authenticated status before saving or starting anything.
                    client.request(candidate, "GET", "/status", new byte[0]);
                    if (client.isCancelled()) throw new java.io.IOException("Cancelled");
                    new PairingStore(getApplicationContext()).updateAddress(pairing, candidate);
                    pairing = candidate;
                }
                if (usesNativeRuntime()) client.setHeadsetHostname(
                    loadNativeHeadsetIdentity());
                client.start(pairing, new PcvrOptions(getApplicationContext()).requestedCodec());
                runOnUiThread(() -> {
                    if (generation != connectGeneration || !connectPending) return;
                    finishHostConnection();
                    if (resumed && !isFinishing() && !isDestroyed() && VrCapabilities.isHeadset(this) && hasMicPermission()) dispatchSteamVr();
                });
            } catch (Exception e) {
                final String message = e instanceof HostClient.Failure ? e.getMessage() : "Could not reach your PC or read its pairing. Check the companion, address and network, then retry.";
                runOnUiThread(() -> {
                    if (generation != connectGeneration || !connectPending) return;
                    finishHostConnection();
                    if (!resumed || isFinishing() || isDestroyed()) return;
                    trackDialog(new android.app.AlertDialog.Builder(this).setTitle("PCVR connection stopped").setMessage(message)
                        .setPositiveButton("Retry", (d,w) -> launchSteamVr())
                        .setNegativeButton("Cancel", null)
                        .setNeutralButton("Connection options", (d,w) -> showConnectionOptions()).show());
                });
            }
        });
    }

    private void showConnectionOptions() {
        trackDialog(new android.app.AlertDialog.Builder(this).setTitle("PCVR connection options")
            .setItems(new String[]{"Pair or change PC", "Open PCVR manually"}, (d,which) -> {
                if (which == 0) showVrSetup();
                else if (resumed && hasMicPermission() && VrCapabilities.isHeadset(this)) {
                    if (usesNativeRuntime()) confirmPcvrRestart(this::dispatchSteamVr);
                    else dispatchSteamVr();
                }
            }).setNegativeButton("Cancel", null).show());
    }

    private void finishHostConnection() {
        connectPending = false;
        hostClient = null;
        vrBootstrap = null;
        if (connectDialog != null) { connectDialog.dismiss(); connectDialog = null; }
    }

    private void cancelHostConnection() {
        ++connectGeneration;
        if (hostClient != null) hostClient.cancel();
        if (vrBootstrap != null) vrBootstrap.cancel();
        finishHostConnection();
    }

    @Override protected void onDestroy() {
        cancelHostConnection();
        cancelVrSetupDiscovery();
        permissionContinuationPending = false;
        // Dismiss listeners remove entries; iterate a snapshot.
        for (android.app.AlertDialog d : new java.util.ArrayList<>(transientDialogs)) {
            if (d != null && d.isShowing()) {
                try { d.dismiss(); } catch (Exception ignored) { }
            }
        }
        transientDialogs.clear();
        if (setupDialog != null) { try { setupDialog.dismiss(); } catch (Exception ignored) { } setupDialog = null; }
        if (connectDialog != null) { try { connectDialog.dismiss(); } catch (Exception ignored) { } connectDialog = null; }
        connectWorker.shutdownNow();
        if (updateRepository != null && updateObserver != null) updateRepository.removeObserver(updateObserver);
        super.onDestroy();
    }

    private void dispatchSteamVr() {
        if (launchPending || !hasMicPermission()) return;
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
            PcvrOptions options = new PcvrOptions(this);
            i.putExtra("vq_pcvr_codec", options.requestedCodec());
            i.putExtra("vq_pcvr_fallback", options.standardCodec());
            i.putExtra("vq_pcvr_travel", options.travel());
            i.putExtra("vq_pcvr_bitrate_mbps", options.bitrateMbps());
            i.putExtra("vq_pcvr_allow_restart", usesNativeRuntime() && android.os.SystemClock.elapsedRealtime() < restartConsentUntil);
            i.putExtra("vq_pcvr_restart_until_ms", restartConsentUntil);
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

    /**
     * Trigger a metadata-only update check when the hub is idle and
     * not in the middle of another dispatch. The shared repository
     * throttles repeated calls (6 h success, 15 min failure) and
     * coalesces concurrent triggers onto the in-flight handle; the
     * hub never blocks the user or cancels an existing in-flight
     * check. Errors are intentionally absorbed because a failed
     * background check must not block gaming.
     *
     * <p>The auto-trigger is suppressed while the host is busy:
     * <ul>
     *   <li>a live PCVR session ({@code <pkg>:pcvr} process) — in-VR
     *       update prompts are distracting and the metadata itself
     *       does not need to be checked while the headset is being
     *       used;</li>
     *   <li>an in-flight connect / VR launch / pairing request —
     *       the metadata check shares the same shared repository
     *       and would interfere with the user-visible flow;</li>
     *   <li>a pending permission request — the round-trip is short
     *       and dispatching a check on top would race with the
     *       upcoming launch.</li>
     * </ul>
     * The next {@code onResume} after the host goes idle will pick
     * up the deferred check through the same repository.
     *
     * <p>The user can always tap "Check now" in the updates screen
     * for a forced refresh.
     */
    private void triggerUpdateCheckIfIdle(boolean force) {
        com.vibertemis.quest.update.UpdateRepository repository =
                com.vibertemis.quest.update.UpdateRepositoryProvider.get(getApplicationContext());
        if (repository == null) return;
        if (!force) {
            if (!repository.shouldRunByThrottle(false)) return;
            if (isLivePcvrSessionRunning()) return;
            // Defer while any user-visible dispatch is in flight.
            // The next idle onResume will run the check naturally.
            if (launchPending || requestPending || connectPending) return;
        }
        try { repository.requestCheck(force); }
        catch (IllegalStateException ignored) { /* bind missing - skip */ }
    }

    /**
     * Live PCVR session is the process name {@code <pkg>:pcvr} that
     * the SteamVR runtime activity uses while a headset session is
     * active. We avoid blocking on system services: an absent
     * RunningAppProcessInfo list is treated as "no live session".
     */
    private boolean isLivePcvrSessionRunning() {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager == null) return false;
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes == null) return false;
            String pcvr = getPackageName() + ":pcvr";
            for (ActivityManager.RunningAppProcessInfo p : processes) {
                if (pcvr.equals(p.processName)) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Render the hub updates badge from a shared snapshot. The badge
     * is intentionally lightweight: a single status line that the
     * user can ignore. It NEVER blocks Connect or any other launch.
     *
     * <p>Status visibility ladder:
     * <ol>
     *   <li>checking — a metadata check is in flight;</li>
     *   <li>downloaded ready — verified APK on disk (always wins
     *       over an available metadata-only newer candidate because
     *       the verified download is what the user can install);</li>
     *   <li>available — newer signed metadata is known, no verified
     *       APK yet;</li>
     *   <li>offline — last check failed; user can retry from the
     *       updates screen;</li>
     *   <li>current — a successful check found no newer release;
     *       the badge stays visible until the user opens the screen
     *       so the check outcome is always visible.</li>
     * </ol>
     * No "up to date" badge is shown before a successful check has
     * happened — the badge is hidden when nothing is known yet.
     */
    private void renderUpdatesBadge(com.vibertemis.quest.update.UpdateRepository.Snapshot snap) {
        TextView badge = findViewById(R.id.hub_updates_badge);
        if (badge == null) return;
        if (snap == null) { badge.setVisibility(View.GONE); return; }
        if (snap.checking) {
            badge.setText(R.string.hub_updates_badge_checking);
            badge.setVisibility(View.VISIBLE);
            return;
        }
        if (snap.hasDownloaded()) {
            badge.setText(getString(R.string.hub_updates_badge_ready, snap.downloaded.version));
            badge.setVisibility(View.VISIBLE);
            return;
        }
        if (snap.hasAvailable()) {
            badge.setText(getString(R.string.hub_updates_badge_available, snap.available.version));
            badge.setVisibility(View.VISIBLE);
            return;
        }
        if (snap.lastError != null) {
            badge.setText(R.string.hub_updates_badge_offline);
            badge.setVisibility(View.VISIBLE);
            return;
        }
        if (snap.lastSuccessAtMs > 0) {
            // A successful check ran and reported no newer release.
            // Surface "current" until something newer shows up, so the
            // user has visible confirmation that the app is checked.
            badge.setText(R.string.hub_updates_badge_current);
            badge.setVisibility(View.VISIBLE);
            return;
        }
        badge.setVisibility(View.GONE);
    }
}
