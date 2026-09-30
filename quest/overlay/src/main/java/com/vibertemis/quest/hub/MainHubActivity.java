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
    private boolean connectPending;
    private long restartConsentUntil;
    private boolean resumed;
    private volatile int connectGeneration;
    private com.vibertemis.quest.update.UpdateRepository updateRepository;
    private com.vibertemis.quest.update.UpdateRepository.Observer updateObserver;
    private volatile HostClient hostClient;
    private com.vibertemis.quest.pcvr.ExistingPairingBootstrap vrBootstrap;
    private android.app.AlertDialog connectDialog;
    private final java.util.concurrent.ExecutorService connectWorker = java.util.concurrent.Executors.newSingleThreadExecutor();
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
        triggerUpdateCheckIfIdle(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        cancelHostConnection();
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

    protected boolean usesNativeRuntime() { return PcvrOptions.PYROWAVE_BUILD; }

    private void launchSteamVr() {
        if (launchPending || connectPending || !VrCapabilities.isHeadset(this) || !hasMicPermission()) return;
        if (!hasPairedHost()) { showVrSetup(); return; }
        if (!usesNativeRuntime()) { startPcvrConnection(); return; }
        confirmPcvrRestart(this::startPcvrConnection);
    }

    private void showVrAdvanced() {
        if (!resumed || connectPending || launchPending || requestPending) return;
        new android.app.AlertDialog.Builder(this).setTitle("Advanced VR pairing")
            .setMessage("Use a pairing file for an older host, or open VR manually if the PC is already prepared.")
            .setPositiveButton("Import pairing file", (d,w) -> {
                if(resumed && !connectPending && !launchPending) startActivity(new Intent(this,PcvrSettingsActivity.class).putExtra(PcvrSettingsActivity.EXTRA_MANUAL_PAIRING, true));
            })
            .setNeutralButton("Manual VR", (d,w) -> {
                if(!resumed || connectPending || launchPending || !VrCapabilities.isHeadset(this) || !hasMicPermission()) return;
                if(usesNativeRuntime()) confirmPcvrRestart(this::dispatchSteamVr); else dispatchSteamVr();
            })
            .setNegativeButton("Back", (d,w) -> { if(resumed) showVrSetup(); }).show();
    }

    private void showVrSetup() {
        if (connectPending || launchPending) return;
        java.util.List<com.limelight.nvstream.http.ComputerDetails> paired = new java.util.ArrayList<>();
        com.limelight.computers.ComputerDatabaseManager db = new com.limelight.computers.ComputerDatabaseManager(this);
        try { for (com.limelight.nvstream.http.ComputerDetails pc : db.getAllComputers()) if (pc.serverCert != null) paired.add(pc); }
        finally { db.close(); }
        if (paired.isEmpty()) {
            new android.app.AlertDialog.Builder(this).setTitle("Pair your PC first")
                .setMessage("Add and pair your PC in Screen gaming. Then return to Setup VR; the existing pairing will be reused.")
                .setPositiveButton("Screen gaming", (d,w) -> { if(resumed) launchScreenGaming(); })
                .setNeutralButton("Advanced pairing", (d,w) -> showVrAdvanced())
                .setNegativeButton("Cancel",null).show();
            return;
        }
        String[] names = new String[paired.size()];
        for (int i=0;i<names.length;i++) names[i]=paired.get(i).name;
        new android.app.AlertDialog.Builder(this).setTitle("Set up VR with a paired PC")
            .setItems(names,(d,which)->enrollVrHost(paired.get(which)))
            .setNeutralButton("Advanced pairing",(d,w)->showVrAdvanced())
            .setNegativeButton("Cancel",null).show();
    }

    private void enrollVrHost(com.limelight.nvstream.http.ComputerDetails pc) {
        if (connectPending || launchPending || !resumed) return;
        connectPending=true;
        final int generation=++connectGeneration;
        final com.vibertemis.quest.pcvr.ExistingPairingBootstrap bootstrap =
            new com.vibertemis.quest.pcvr.ExistingPairingBootstrap(getApplicationContext());
        vrBootstrap=bootstrap;
        connectDialog=new android.app.AlertDialog.Builder(this).setTitle("Setting up VR")
            .setMessage("Connecting to "+pc.name+" using your existing pairing…")
            .setNegativeButton("Cancel",(d,w)->cancelHostConnection())
            .setOnCancelListener(d->cancelHostConnection()).show();
        connectWorker.execute(()->{
            try {
                HostPairing existing = new PairingStore(getApplicationContext()).load();
                HostPairing enrolled;
                String selectedPin=HostPairing.hex(java.security.MessageDigest.getInstance("SHA-256").digest(pc.serverCert.getEncoded()));
                boolean reused=false;
                if (existing!=null && selectedPin.equals(existing.hostCertificatePin)) {
                    HostClient check=createHostClient();hostClient=check;
                    try { check.request(existing,"GET","/status",new byte[0]);reused=true; }
                    catch(Exception ignored) { if (generation!=connectGeneration) return; }
                }
                enrolled=reused ? existing : bootstrap.enroll(pc);
                final HostPairing result=enrolled;
                runOnUiThread(()->{
                    if (generation!=connectGeneration || !resumed || isFinishing()) return;
                    try { new PairingStore(getApplicationContext()).save(result); }
                    catch(Exception e) {
                        finishHostConnection();Toast.makeText(this,"Could not save VR pairing. Retry Setup VR.",Toast.LENGTH_LONG).show();return;
                    }
                    finishHostConnection();
                    new android.app.AlertDialog.Builder(this).setTitle("VR pairing ready")
                        .setMessage("Your PC is paired for VR. Choose Connect when you’re ready; SteamVR starts from the headset.")
                        .setPositiveButton("Done",null).show();
                });
            } catch(Exception e) {
                final String message=e instanceof com.vibertemis.quest.pcvr.ExistingPairingBootstrap.SetupFailure
                    ? e.getMessage() : "Could not finish VR setup. Open Setup VR in the Windows VR Host Manager, then retry. Your previous pairing is kept.";
                runOnUiThread(()->{
                    if(generation!=connectGeneration || !resumed || isFinishing())return;
                    finishHostConnection();
                    new android.app.AlertDialog.Builder(this).setTitle("VR setup needs attention").setMessage(message)
                        .setPositiveButton("Retry",(d,w)->showVrSetup()).setNegativeButton("Close",null).show();
                });
            }
        });
    }

    private void confirmPcvrRestart(Runnable connect) {
        connectPending = true;
        final int generation = ++connectGeneration;
        connectDialog = new android.app.AlertDialog.Builder(this)
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
            }).show();
    }

    private void startPcvrConnection() {
        if (launchPending || connectPending || !VrCapabilities.isHeadset(this) || !hasMicPermission()) return;
        if (!hasPairedHost()) { showVrSetup(); return; }
        connectPending = true;
        final int generation = ++connectGeneration;
        final HostClient client = createHostClient();
        hostClient = client;
        connectDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Starting PCVR")
                .setMessage("Contacting your PC and waiting for SteamVR…")
                .setNegativeButton("Cancel", (d,w) -> cancelHostConnection())
                .setOnCancelListener(d -> cancelHostConnection()).show();
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
                    new android.app.AlertDialog.Builder(this).setTitle("PCVR connection stopped").setMessage(message)
                        .setPositiveButton("Retry", (d,w) -> launchSteamVr())
                        .setNegativeButton("Cancel", null)
                        .setNeutralButton("Connection options", (d,w) -> showConnectionOptions()).show();
                });
            }
        });
    }

    private void showConnectionOptions() {
        new android.app.AlertDialog.Builder(this).setTitle("PCVR connection options")
            .setItems(new String[]{"Pair or change PC", "Open PCVR manually"}, (d,which) -> {
                if (which == 0) showVrSetup();
                else if (resumed && hasMicPermission() && VrCapabilities.isHeadset(this)) {
                    if (usesNativeRuntime()) confirmPcvrRestart(this::dispatchSteamVr);
                    else dispatchSteamVr();
                }
            }).setNegativeButton("Cancel", null).show();
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
     * throttles repeated calls (6 h success, 15 min failure); the
     * hub never blocks the user or cancels an existing in-flight
     * check. Errors are intentionally absorbed because a failed
     * background check must not block gaming.
     *
     * <p>A live PCVR session ({@code <pkg>:pcvr} process) suppresses
     * the auto-trigger: in-VR update prompts are distracting and the
     * metadata itself does not need to be checked while the headset
     * is being used. The user can still tap "Check now" in the
     * updates screen for a forced refresh.
     */
    private void triggerUpdateCheckIfIdle(boolean force) {
        com.vibertemis.quest.update.UpdateRepository repository =
                com.vibertemis.quest.update.UpdateRepositoryProvider.get(getApplicationContext());
        if (repository == null) return;
        if (!force) {
            if (!repository.shouldRunByThrottle(false)) return;
            if (isLivePcvrSessionRunning()) return;
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
        if (snap.hasAvailable()) {
            if (snap.hasDownloaded()) {
                badge.setText(getString(R.string.hub_updates_badge_ready, snap.downloaded.version));
            } else {
                badge.setText(getString(R.string.hub_updates_badge_available, snap.available.version));
            }
            badge.setVisibility(View.VISIBLE);
            return;
        }
        if (snap.hasDownloaded()) {
            badge.setText(getString(R.string.hub_updates_badge_ready, snap.downloaded.version));
            badge.setVisibility(View.VISIBLE);
            return;
        }
        if (snap.lastError != null) {
            badge.setText(R.string.hub_updates_badge_offline);
            badge.setVisibility(View.VISIBLE);
            return;
        }
        badge.setVisibility(View.GONE);
    }
}
