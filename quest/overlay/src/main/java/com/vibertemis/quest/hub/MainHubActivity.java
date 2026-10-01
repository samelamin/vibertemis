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
import android.widget.LinearLayout;
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
import org.json.JSONObject;

/**
 * Panel-mode launch hub. NOT itself immersive / NOT an OpenXR activity.
 *
 * <p>The hub exposes a single prominent <b>Connection card</b> with one
 * primary action whose label is decided by pairing state and device
 * class — NOT by user-visible device names or heuristics:
 * <ul>
 *   <li><b>Real headset, paired</b> — primary action reads "Connect".
 *       A tap issues an authenticated {@code /status} probe against the
 *       paired host (with bounded LAN rediscovery if the saved
 *       endpoint is unreachable) and then branches on the result:
 *       <ul>
 *         <li>{@code vrserver=false} — the PC is cold: we set the
 *             120-second restart-consent window and proceed straight
 *             to the start request. No modal dialog.</li>
 *         <li>{@code vrserver=true} — SteamVR is already running on
 *             the PC: we surface an explicit <b>Restart VR / Cancel</b>
 *             consent dialog. The user must confirm before we set the
 *             consent window and start the request.</li>
 *         <li>missing / non-boolean {@code vrserver} — fail closed:
 *             an inline error is drawn into the card with a Retry
 *             chip. We never invent a decision on an unknown state.</li>
 *       </ul>
 *   </li>
 *   <li><b>Real headset, unpaired</b> — primary action reads
 *       "Set up PC" and opens the VR-setup picker directly. The picker
 *       reaches enrollment, manual entry and the advanced pairing
 *       dialog the same way the legacy Setup button did.</li>
 *   <li><b>Phone / TV / other non-headset</b> — primary action reads
 *       "Connect" and routes into the existing flat {@link PcView}
 *       start. We never ask for the microphone permission on a phone
 *       because the flat path does not need it.</li>
 * </ul>
 *
 * <p>Connection progress and errors are drawn INTO the connection card
 * as an inline phase line and an inline error block. The hub never
 * stacks transient dialogs on success, never asks the user to confirm
 * a cold start (the probe already proved the PC is reachable), and
 * never starts a VR launch from a state we have not authenticated.
 *
 * <p><b>Transient pause and the continuation queue.</b> A connect
 * attempt that completes while the hub is paused (the user took the
 * headset off briefly) does not dispatch VR until the next resume,
 * and even then only if the hub is still alive, still a headset,
 * still has microphone permission, and the restart-consent window
 * has not silently expired. The continuation is bound to the
 * current {@code connectGeneration}; a Cancel / Destroy / new tap
 * bumps the generation and any queued continuation is dropped.
 *
 * <p>The hub never auto-launches from {@code onResume}. Only an
 * explicit user tap can dispatch a target activity or a queued
 * continuation, and the hub guards each tap against in-flight
 * permission requests and pending launches so a rapid double-tap or
 * an asynchronous permission result for a different request cannot
 * start two activities at once.
 *
 * <p><b>Microphone requests carry an explicit target.</b> Setup no
 * longer asks for the microphone up front, so the two headset actions
 * that need it (the primary Connect and the Manual VR fallback) each
 * capture which one they are while the request is dispatched. The
 * real grant callback consumes that target exactly once and resumes
 * only the action that asked for it: a Connect grant continues into
 * the authenticated probe, and a Manual VR grant still has to clear
 * the explicit legacy restart consent. The target is cleared on a
 * denied / empty / mismatched result, on a duplicate result, on an
 * explicit Cancel and on destroy, and it is never persisted — a hub
 * recreated behind the system dialog keeps the request guard but has
 * no action to resume, so a stray result cannot replay a stale intent.
 */
public class MainHubActivity extends Activity {

    private static final String TAG = "MainHubActivity";
    static final int REQ_MIC_FOR_STEAMVR_FOR_TEST = 0x5356;
    private static final int REQ_MIC_FOR_STEAMVR = REQ_MIC_FOR_STEAMVR_FOR_TEST;

    private static final String STATE_REQUEST_PENDING = "vq_hub_request_pending";

    /** No microphone permission request is in flight. */
    private static final int MIC_TARGET_NONE = 0;
    /** The primary Connect action on a paired host: the grant
     *  continues through the authenticated probe and the cold start. */
    private static final int MIC_TARGET_CONNECT = 1;
    /** The manual VR fallback (Advanced → Manual VR, Connection
     *  options → Open PCVR manually): the grant continues through the
     *  explicit legacy restart consent, never straight to a launch. */
    private static final int MIC_TARGET_MANUAL_VR = 2;

    /** Width of the restart-consent window. The same value is used by
     *  the manual VR fallback so a single numeric constant governs
     *  the contract. The window starts when the user confirms
     *  Restart VR (or the probe passes on a cold server) and ends
     *  two minutes later. */
    private static final long RESTART_CONSENT_WINDOW_MS = 120000L;

    private HubPrefs prefs;
    private SettingsController settingsController;

    private boolean requestPending;
    /**
     * The action that is waiting on the in-flight microphone
     * permission request, captured at request time so the grant
     * continuation resumes exactly the action the user started.
     *
     * <p>This is deliberately NOT persisted in instance state: a
     * recreated hub restores {@link #requestPending} so it cannot
     * dispatch a second request, but it deliberately has no idea what
     * the original request was for. A stray grant result landing on
     * the recreated instance therefore has no target and is dropped
     * instead of replaying a stale launch.
     */
    private int micPermissionTarget = MIC_TARGET_NONE;
    private boolean permissionContinuationPending;
    /** Target captured with {@link #permissionContinuationPending}
     *  when a grant result lands while the hub is paused. */
    private int permissionContinuationTarget = MIC_TARGET_NONE;
    private boolean connectPending;
    private long restartConsentUntil;
    private boolean resumed;
    private boolean restartPromptPending;
    private volatile int connectGeneration;
    private com.vibertemis.quest.update.UpdateRepository updateRepository;
    private com.vibertemis.quest.update.UpdateRepository.Observer updateObserver;
    private volatile HostClient hostClient;
    private com.vibertemis.quest.pcvr.PairingSession vrBootstrap;
    /** Generation captured when a successful connect callback landed
     *  while the hub was paused. The continuation only dispatches on
     *  resume if the current {@link #connectGeneration} still equals
     *  this value, so Cancel / Destroy / a new tap reliably drop the
     *  queued launch. */
    private volatile int pendingContinuationGeneration;
    private boolean pendingContinuation;
    /** Inline phase and error views, captured once in onCreate so the
     *  connect lifecycle does not repeatedly look them up. */
    private TextView phaseView;
    private LinearLayout errorRow;
    private TextView errorView;
    private Button errorRetryBtn;
    private Button errorCancelBtn;
    private android.app.AlertDialog connectDialog;
    /** Dialog shown to the user when the authenticated probe reports
     *  {@code vrserver=true} so SteamVR must be restarted. The user
     *  must press Restart VR before we set the consent window and
     *  start. */
    private android.app.AlertDialog restartVrDialog;
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
        TextView cardPcStatus = findViewById(R.id.hub_card_pc_status);
        TextView cardPcCaveat = findViewById(R.id.hub_card_pc_caveat);
        phaseView = findViewById(R.id.hub_card_phase);
        findViewById(R.id.hub_btn_cancel_connection).setOnClickListener(v -> cancelHostConnection());
        errorRow = findViewById(R.id.hub_card_error_row);
        errorView = findViewById(R.id.hub_card_error);
        errorRetryBtn = findViewById(R.id.hub_card_error_retry);
        errorCancelBtn = findViewById(R.id.hub_card_error_cancel);

        boolean isHeadset = VrCapabilities.isHeadset(this);
        boolean pairedHost = hasPairedHost();
        if (isHeadset) {
            subtitle.setText(R.string.hub_subtitle_detected_headset);
            connectNote.setText(R.string.hub_steamvr_companion_reminder);
            screenBtn.setVisibility(View.VISIBLE);
            screenNote.setVisibility(View.VISIBLE);
            // Primary action title depends on whether a paired host
            // exists. "Set up PC" is the discoverable affordance for
            // an unpaired headset — there is no separate manual
            // setup-only button.
            if (pairedHost) {
                connectBtn.setText(R.string.hub_btn_connect);
                try {
                    HostPairing saved = new PairingStore(getApplicationContext()).load();
                    if (saved != null) {
                        cardPcStatus.setText(getString(R.string.hub_card_paired, saved.address));
                    } else {
                        cardPcStatus.setText(R.string.hub_card_unpaired);
                    }
                } catch (Exception e) {
                    // A corrupted persisted pairing is shown as
                    // "unpaired" so the user can retry Setup; we
                    // never pretend it is still valid.
                    cardPcStatus.setText(R.string.hub_card_unpaired);
                }
                cardPcCaveat.setVisibility(View.VISIBLE);
            } else {
                connectBtn.setText(R.string.hub_btn_setup_pc);
                cardPcStatus.setText(R.string.hub_card_unpaired);
                cardPcCaveat.setVisibility(View.GONE);
            }
        } else {
            // Phone / TV: primary Connect already opens PcView, so the
            // separate Screen gaming row is hidden — surfacing it
            // would be a redundant duplicate of the primary action.
            // The companion reminder line is generic for both modes.
            subtitle.setText(R.string.hub_subtitle_detected_phone);
            connectNote.setText(R.string.hub_subtitle_detected_phone);
            screenBtn.setVisibility(View.GONE);
            screenNote.setVisibility(View.GONE);
            cardPcCaveat.setVisibility(View.GONE);
            cardPcStatus.setText(R.string.hub_card_unpaired);
            // Phones never reach Setup VR through the primary
            // Connect path; they go to PcView. The Setup link
            // below the settings row opens the SetupActivity
            // documentation screen for a phone.
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
        bindUpdateRepository();
        if (VrCapabilities.isHeadset(this)) setupBtn.setText(R.string.hub_btn_setup);
        setupBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (VrCapabilities.isHeadset(MainHubActivity.this)) showVrSetup(); else launchSetup();
            }
        });
        if (errorRetryBtn != null) {
            errorRetryBtn.setOnClickListener(v -> retryConnect());
        }
        if (errorCancelBtn != null) {
            errorCancelBtn.setOnClickListener(v -> cancelHostConnection());
        }
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
        if (!hasPairedHost()) {
            // Unpaired headset: "Set up PC" launches the VR-setup
            // picker directly. There is no separate "Setup" detour;
            // the primary affordance IS the setup path until the
            // user has a paired host.
            showVrSetup();
            return;
        }
        if (hasMicPermission()) {
            launchSteamVr();
        } else {
            requestMicForSteamVr(MIC_TARGET_CONNECT);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_REQUEST_PENDING, requestPending);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (pairingNotice != null) {
            showPairingNotice(pairingNotice);
            pairingNotice = null;
        }
        settingsController.refresh();
        settingsController.register();
        renderStatus();
        if (launchLeftHub) {
            launchPending = false;
            launchLeftHub = false;
        }
        // Continue only an explicit Connect (or an explicit Manual VR
        // entry) whose permission result arrived while paused, and only
        // into the action that actually asked for the microphone. An
        // ordinary resume never initiates a VR connection, and the
        // continuation is consumed here so a later resume cannot replay
        // it.
        if (permissionContinuationPending) {
            permissionContinuationPending = false;
            final int continuationTarget = permissionContinuationTarget;
            permissionContinuationTarget = MIC_TARGET_NONE;
            if (!isFinishing() && !isDestroyed() && hasMicPermission()
                    && VrCapabilities.isHeadset(this)) {
                continueAfterMicGrant(continuationTarget);
            }
        }
        if (restartPromptPending && connectPending) showRestartVrConsent(connectGeneration);
        refreshConnectionCard();
        // Drain a queued continuation that landed while paused. The
        // continuation is dropped if the generation moved on, the
        // hub is finishing/destroyed, the device is no longer a
        // headset, the microphone permission has been revoked, or
        // the restart-consent window has silently expired. A valid
        // continuation dispatches SteamVrActivity exactly once.
        if (pendingContinuation
                && pendingContinuationGeneration == connectGeneration
                && !isFinishing() && !isDestroyed()
                && VrCapabilities.isHeadset(this)
                && hasMicPermission()
                && (!usesNativeRuntime() || restartConsentValid())) {
            pendingContinuation = false;
            dispatchSteamVr();
        } else if (pendingContinuation) {
            // Drop the queued continuation: a Cancel / Destroy /
            // permission loss / consent expiry happened while paused.
            cancelHostConnection();
            showInlineError(hasMicPermission() ? R.string.hub_error_consent_expired
                    : R.string.hub_error_mic_required);
        }
        // Re-bind before the auto-check: the provider is allowed to
        // publish a repository later (a retried / idempotent init), and
        // an onCreate that saw null must still end up observing it, or
        // the badge would stay hidden until the next recreation.
        bindUpdateRepository();
        triggerUpdateCheckIfIdle(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        // Removing the headset to approve on the PC must not cancel
        // enrollment. Removing the headset while a connect attempt is
        // in flight (the user takes it off briefly to read the inline
        // phase) must also not cancel. Only onDestroy forces a hard
        // cancel; transient pause leaves the worker and the inline
        // phase intact so the result can land and queue a
        // continuation tied to the current generation.
        cancelVrSetupDiscovery();
        if (setupDialog != null) { try { setupDialog.dismiss(); } catch (Exception ignored) { } setupDialog = null; }
        settingsController.unregister();
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

    /**
     * Ask for RECORD_AUDIO on behalf of {@code target} and remember
     * that target so the real grant callback can continue exactly the
     * action the user started. A request is never dispatched while
     * another one is in flight or while a launch is pending, so the
     * request guard cannot be bypassed by a rapid tap.
     *
     * @param target one of {@link #MIC_TARGET_CONNECT} or
     *               {@link #MIC_TARGET_MANUAL_VR}
     */
    private void requestMicForSteamVr(int target) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (target == MIC_TARGET_NONE || requestPending || launchPending) return;
        micPermissionTarget = target;
        requestPending = true;
        try {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},
                    REQ_MIC_FOR_STEAMVR);
        } catch (Throwable t) {
            requestPending = false;
            micPermissionTarget = MIC_TARGET_NONE;
            Log.w(TAG, "Failed to dispatch mic permission request: " + t.getMessage());
            Toast.makeText(this, R.string.mic_recovery_msg, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Route a granted microphone permission back into the action that
     * requested it. Exactly one continuation is produced per grant:
     * the target is consumed by the caller before this runs, so a
     * duplicated grant result can never run it twice.
     */
    private void continueAfterMicGrant(int target) {
        if (target == MIC_TARGET_MANUAL_VR) {
            // Manual VR keeps its explicit legacy restart consent even
            // when the grant had to be requested first.
            startManualVr();
        } else if (target == MIC_TARGET_CONNECT) {
            launchSteamVr();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_MIC_FOR_STEAMVR) return;

        if (!requestPending) return;
        requestPending = false;
        final int target = micPermissionTarget;
        // The target is consumed here, so a denied / empty / mismatched
        // result, and every duplicate delivery, can never start anything.
        micPermissionTarget = MIC_TARGET_NONE;
        if (target == MIC_TARGET_NONE) {
            // The hub was recreated while the system dialog was up:
            // requestPending survived but the action that asked for the
            // microphone did not. A stray result must not invent a
            // launch or replay a stale action.
            Log.w(TAG, "Dropping mic result for a request this instance did not start");
            return;
        }

        if (permissions == null || permissions.length == 0
                || !Manifest.permission.RECORD_AUDIO.equals(permissions[0])) {
            return;
        }
        if (grantResults == null || grantResults.length == 0) {
            showMicRecoveryDialog(target, false);
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
            showMicRecoveryDialog(target, permanentlyDenied);
            return;
        }
        if (!hasMicPermission()) {
            return;
        }
        if (!resumed) {
            // The permission dialog paused the hub. Remember which
            // action asked for the mic and continue it exactly once on
            // the next resume.
            permissionContinuationPending = true;
            permissionContinuationTarget = target;
            return;
        }
        continueAfterMicGrant(target);
    }

    private void showMicRecoveryDialog(final int target, final boolean permanentlyDenied) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.mic_recovery_title)
                .setMessage(R.string.mic_recovery_msg)
                .setNegativeButton(R.string.mic_recovery_dismiss, null);
        if (permanentlyDenied) {
            b.setPositiveButton(R.string.mic_recovery_open_settings,
                    (d, w) -> openAppSettings());
            b.setNeutralButton(R.string.mic_recovery_retry,
                    (d, w) -> requestMicForSteamVr(target));
        } else {
            b.setPositiveButton(R.string.mic_recovery_retry,
                    (d, w) -> requestMicForSteamVr(target));
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

    /**
     * Primary headset-Connect entry. The connect lifecycle runs in
     * the existing single-thread {@code connectWorker} so all
     * network I/O is bounded off the UI thread:
     *
     * <ol>
     *   <li><b>Probe.</b> Issue an authenticated {@code GET /status}
     *       against the paired host. On a transport failure (the
     *       saved address is unreachable) run the bounded LAN
     *       rediscovery helper against the paired TLS pin and retry
     *       the probe once the hint is verified. The probe result is
     *       the only authority for the connect decision — we never
     *       inspect a cached value, never guess.</li>
     *   <li><b>Decision.</b> Inspect the {@code vrserver} field of
     *       the probe reply:
     *       <ul>
     *         <li>{@code vrserver=false} (boolean) — the PC is cold:
     *             set the 120-second restart-consent window and
     *             proceed straight to the start request. No modal
     *             dialog, no consent bypass: the probe already
     *             proved the PC is reachable and SteamVR is not
     *             running, so a cold start cannot disrupt a live VR
     *             session.</li>
     *         <li>{@code vrserver=true} (boolean) — SteamVR is
     *             running: surface an explicit Restart VR / Cancel
     *             consent dialog. Only the user's Restart VR tap
     *             sets the consent window and continues.</li>
     *         <li>missing or non-boolean — fail closed: draw an
     *             inline error into the card and surface an inline
     *             Retry chip. We never invent a decision on an
     *             unknown state.</li>
     *       </ul>
     *   </li>
     *   <li><b>Start.</b> Once the consent decision is made, the
     *       worker issues the {@code POST /start_pcvr} request
     *       through the same authenticated TLS pin. A successful
     *       start queues a continuation tied to the current
     *       generation; if the hub is paused when the success
     *       lands, the next resume dispatches SteamVrActivity
     *       exactly once and only if the hub is still alive, still
     *       a headset, still has microphone permission, and the
     *       restart-consent window has not silently expired.</li>
     * </ol>
     *
     * <p>The legacy "confirm then start" two-step is intentionally
     * removed from this entry: the probe has already established
     * what the cold-start confirmation used to ask. The confirm
     * dialog is still reachable through the Manual VR fallback
     * paths (Advanced → Manual VR, Connection options → Open PCVR
     * manually) so a user who explicitly wants the old behaviour can
     * still get it.
     */
    private void launchSteamVr() {
        if (launchPending || connectPending || !VrCapabilities.isHeadset(this) || !hasMicPermission()) return;
        if (!hasPairedHost()) { showVrSetup(); return; }
        connectPending = true;
        final int generation = ++connectGeneration;
        pendingContinuation = false;
        // Drop the queued continuation: a fresh tap supersedes any
        // pending dispatch from an earlier in-flight attempt.
        final HostClient client = createHostClient();
        hostClient = client;
        showInlinePhase(R.string.hub_phase_probing);
        clearInlineError();
        connectWorker.execute(() -> {
            try {
                HostPairing pairing = loadHostPairing();
                if (pairing == null) throw new HostClient.Failure("PAIRING", "Pair your PC again.");
                if (client.isCancelled()) throw new java.io.IOException("Cancelled");
                JSONObject status;
                try {
                    status = client.request(pairing, "GET", "/status", new byte[0]);
                } catch (java.io.IOException unreachable) {
                    if (client.isCancelled()) throw unreachable;
                    HostPairing candidate = com.vibertemis.quest.pcvr.PairedDiscovery.find(
                            getApplicationContext(), pairing, client);
                    status = client.request(candidate, "GET", "/status", new byte[0]);
                    if (client.isCancelled()) throw new java.io.IOException("Cancelled");
                    createPairingStore().updateAddress(pairing, candidate);
                }
                if (client.isCancelled()) throw new java.io.IOException("Cancelled");
                if (usesNativeRuntime()) client.setHeadsetHostname(loadNativeHeadsetIdentity());
                boolean vrserver = status.opt("vrserver") instanceof Boolean
                        && status.getBoolean("vrserver");
                if (client.isCancelled()) throw new java.io.IOException("Cancelled");
                if (status.isNull("vrserver") || !(status.opt("vrserver") instanceof Boolean)) {
                    // Fail closed on missing/non-boolean. We do NOT
                    // pretend a missing field is "false"; an unknown
                    // state means we cannot decide whether SteamVR
                    // is running.
                    if (!status.has("vrserver")) {
                        runOnUiThread(() -> handleProbeUnknown(generation, "missing"));
                    } else {
                        runOnUiThread(() -> handleProbeUnknown(generation, "non-boolean"));
                    }
                    return;
                }
                if (!vrserver) {
                    // Cold server. The probe already proved the PC
                    // is reachable and SteamVR is not running, so a
                    // cold start cannot disrupt a live VR session.
                    // Set the consent window and continue — no modal
                    // dialog.
                    runOnUiThread(() -> {
                        if (generation != connectGeneration || isFinishing() || isDestroyed()) return;
                        restartConsentUntil = android.os.SystemClock.elapsedRealtime() + RESTART_CONSENT_WINDOW_MS;
                        startPcvrConnection(generation);
                    });
                } else {
                    // Warm server. Surface an explicit Restart VR /
                    // Cancel dialog. Only the user's tap sets the
                    // consent window and continues; Cancel drops
                    // the attempt.
                    runOnUiThread(() -> {
                        if (generation != connectGeneration || isFinishing() || isDestroyed()) return;
                        showRestartVrConsent(generation);
                    });
                }
            } catch (Exception e) {
                final String message = e instanceof HostClient.Failure ? e.getMessage()
                        : "Could not reach your PC. Check the companion, address and network, then retry.";
                runOnUiThread(() -> {
                    if (generation != connectGeneration) return;
                    connectPending = false;
                    hostClient = null;
                    clearInlinePhase();
                    showInlineError(message);
                });
            }
        });
    }

    /** Fail-closed handler for an unknown probe state. Draws an
     *  inline error into the connection card and surfaces an inline
     *  Retry chip. The user can re-attempt the connect without
     *  re-doing the consent dialog. */
    private void handleProbeUnknown(int generation, String reason) {
        if (generation != connectGeneration || isFinishing() || isDestroyed()) return;
        connectPending = false;
        hostClient = null;
        clearInlinePhase();
        showInlineError(getString(R.string.hub_error_state_unknown));
    }

    /** Surface the Restart VR / Cancel consent dialog for the warm
     *  server case. The dialog is generation-scoped so a Cancel /
     *  Destroy / new tap during the dialog cancels the in-flight
     *  attempt. Only Restart VR sets the consent window and
     *  continues. */
    private void showRestartVrConsent(int generation) {
        if (generation != connectGeneration || !connectPending || isFinishing() || isDestroyed()) return;
        if (!resumed) { restartPromptPending = true; return; }
        restartPromptPending = false;
        if (restartVrDialog != null) {
            try { restartVrDialog.dismiss(); } catch (Exception ignored) { }
            restartVrDialog = null;
        }
        clearInlineError();
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.hub_restart_title)
                .setMessage(R.string.hub_restart_message)
                .setPositiveButton(R.string.hub_restart_confirm, (d, w) -> {
                    if (generation != connectGeneration || isFinishing() || isDestroyed()) return;
                    if (!resumed || !VrCapabilities.isHeadset(this) || !hasMicPermission()) {
                        // Mic permission could have been revoked
                        // while the dialog was up; refuse to start.
                        connectPending = false;
                        hostClient = null;
                        clearInlinePhase();
                        showInlineError(R.string.hub_error_mic_required);
                        return;
                    }
                    restartConsentUntil = android.os.SystemClock.elapsedRealtime() + RESTART_CONSENT_WINDOW_MS;
                    startPcvrConnection(generation);
                })
                .setNegativeButton(R.string.hub_restart_cancel, (d, w) -> {
                    if (generation != connectGeneration) return;
                    cancelHostConnection();
                })
                .setOnCancelListener(d -> {
                    if (generation != connectGeneration) return;
                    cancelHostConnection();
                })
                .show();
        restartVrDialog = dialog;
        trackDialog(dialog);
    }

    /**
     * Continue the connect lifecycle after the consent decision.
     * Issues the {@code POST /start_pcvr} request through the same
     * authenticated TLS pin and dispatches SteamVrActivity on
     * success. If the hub is paused when the success lands, the
     * dispatch is queued as a continuation tied to the current
     * generation; the next resume fires it exactly once, only if
     * the hub is still alive, still a headset, still has microphone
     * permission, and the restart-consent window has not silently
     * expired.
     *
     * <p>The incoming generation is consumed atomically by the first
     * accepted start, after the prerequisite and client checks. A
     * rapid double tap on the consent dialog's positive button can
     * reach this function twice with the same generation before the
     * worker finishes; consuming it here invalidates the consent
     * dialog's captured generation, so the second tap is rejected by
     * its own guard instead of queueing a second start request. Every
     * async callback and the queued continuation bind to the fresh
     * generation captured below.
     */
    private void startPcvrConnection(int generation) {
        if (generation != connectGeneration || !connectPending || launchPending) return;
        if (!VrCapabilities.isHeadset(this) || !hasMicPermission()) {
            cancelHostConnection();
            showInlineError(R.string.hub_error_mic_required);
            return;
        }
        final HostClient client = hostClient;
        if (client == null) {
            connectPending = false;
            clearInlinePhase();
            showInlineError(R.string.hub_error_generic);
            return;
        }
        final int startGeneration = ++connectGeneration;
        showInlinePhase(R.string.hub_phase_starting);
        clearInlineError();
        connectWorker.execute(() -> {
            try {
                HostPairing pairing = loadHostPairing();
                if (pairing == null) throw new HostClient.Failure("PAIRING", "Pair your PC again.");
                if (client.isCancelled()) throw new java.io.IOException("Cancelled");
                client.start(pairing, new PcvrOptions(getApplicationContext()).requestedCodec());
                runOnUiThread(() -> {
                    if (startGeneration != connectGeneration || isFinishing() || isDestroyed()) return;
                    if (!VrCapabilities.isHeadset(this) || !hasMicPermission()) {
                        // Permission loss or class change between the
                        // worker callback and the dispatch. Drop the
                        // attempt; the user sees the inline error.
                        connectPending = false;
                        hostClient = null;
                        clearInlinePhase();
                        showInlineError(R.string.hub_error_mic_required);
                        return;
                    }
                    if (usesNativeRuntime() && !restartConsentValid()) {
                        connectPending = false;
                        hostClient = null;
                        clearInlinePhase();
                        showInlineError(R.string.hub_error_consent_expired);
                        return;
                    }
                    if (!resumed) {
                        // Queue the dispatch for the next resume.
                        pendingContinuation = true;
                        pendingContinuationGeneration = startGeneration;
                        return;
                    }
                    dispatchSteamVr();
                });
            } catch (Exception e) {
                final String message = e instanceof HostClient.Failure ? e.getMessage()
                        : "Could not reach your PC or read its pairing. Check the companion, address and network, then retry.";
                runOnUiThread(() -> {
                    if (startGeneration != connectGeneration) return;
                    connectPending = false;
                    hostClient = null;
                    clearInlinePhase();
                    showInlineError(message);
                });
            }
        });
    }

    /** True if the consent window is still in the future. The window
     *  starts when Restart VR is confirmed or the cold probe
     *  succeeds and ends two minutes later. A silent expiry between
     *  confirmation and dispatch must never produce a launch. */
    private boolean restartConsentValid() {
        long until = restartConsentUntil;
        if (until <= 0L) return false;
        return android.os.SystemClock.elapsedRealtime() < until;
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
            .setNeutralButton("Manual VR", (d,w) -> startManualVr())
            .setNegativeButton("Back", (d,w) -> { if(resumed) showVrSetup(); }).show());
    }

    /**
     * Shared Manual VR entry for both reachable manual paths (Advanced
     * → Manual VR and Connection options → Open PCVR manually).
     *
     * <p>The setup flow no longer asks for the microphone up front, so
     * this entry must never silently return when the microphone is
     * missing: it captures {@link #MIC_TARGET_MANUAL_VR} and asks, and
     * the real grant callback routes straight back here through
     * {@link #continueAfterMicGrant}. Either way the user still has to
     * clear the explicit legacy restart consent before anything is
     * dispatched — the mic request alone is never consent.
     */
    private void startManualVr() {
        if (!resumed || isFinishing() || isDestroyed()) return;
        if (connectPending || launchPending || requestPending) return;
        if (!VrCapabilities.isHeadset(this)) return;
        if (!hasMicPermission()) {
            requestMicForSteamVr(MIC_TARGET_MANUAL_VR);
            return;
        }
        // The manual VR fallback keeps the legacy coldstart
        // confirmation: there is no probe, so the user explicitly opts
        // into a SteamVR restart.
        if (usesNativeRuntime()) confirmPcvrRestartLegacy(this::dispatchSteamVr);
        else dispatchSteamVr();
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
        if (!resumed || isFinishing() || isDestroyed() || connectPending || launchPending || requestPending) return;
        cancelVrSetupDiscovery();
        final int generation = ++discoveryGeneration;
        android.app.AlertDialog searching = new android.app.AlertDialog.Builder(this)
                .setTitle("Set up VR")
                .setMessage("Searching for your PC on the local network…")
                .setNegativeButton("Cancel", (d, w) -> cancelVrSetupDiscovery())
                .setOnCancelListener(d -> cancelVrSetupDiscovery())
                .show();
        setupDialog = searching;
        final com.vibertemis.quest.pcvr.VrSetupDiscovery discovery = createVrSetupDiscovery();
        runningDiscovery = discovery;
        final java.util.concurrent.atomic.AtomicReference<java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate>> discoveredRef =
                new java.util.concurrent.atomic.AtomicReference<>(
                        new java.util.ArrayList<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate>());
        final java.util.concurrent.atomic.AtomicReference<java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate>> dbHintsRef =
                new java.util.concurrent.atomic.AtomicReference<>(
                        new java.util.ArrayList<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate>());
        connectWorker.execute(() -> {
            java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate> discovered =
                    new java.util.ArrayList<>();
            try {
                discovered = discovery.browse();
            } catch (java.io.IOException ignored) {
            }
            discoveredRef.set(discovered);
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
                if (host.indexOf(':') >= 0) continue;
                int dbPort = VrSetupDiscovery.DEFAULT_VR_PORT;
                if (discoveredIps.contains(host)) continue;
                String displayName = (pc.name == null || pc.name.isEmpty()) ? host : pc.name;
                out.add(new com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate(
                        displayName, host, dbPort));
            }
        } catch (Throwable dbScanFailure) {
            Log.w(TAG, "saved Moonlight DB scan failed; continuing without hints", dbScanFailure);
        } finally {
            if (db != null) { try { db.close(); } catch (Exception ignored) { } }
        }
        return out;
    }

    private void cancelVrSetupDiscovery() {
        if (runningDiscovery != null) {
            try { runningDiscovery.cancel(); } catch (Exception ignored) { }
            runningDiscovery = null;
        }
        discoveryGeneration++;
    }

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
        if (dbHints != null) {
            for (com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate c : dbHints) {
                String key = c.address + ":" + c.port;
                if (seen.add(key)) merged.add(c);
            }
        }
        if (merged.isEmpty()) {
            android.app.AlertDialog empty = new android.app.AlertDialog.Builder(this)
                    .setTitle("No VR PC found")
                    .setMessage(R.string.hub_vr_setup_empty_message)
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

    private void showManualEntry() {
        if (!resumed || connectPending || launchPending || requestPending) return;
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
        if (connectPending || launchPending || !resumed || requestPending) return;
        connectPending=true;
        final int generation=++connectGeneration;
        final com.vibertemis.quest.pcvr.PairingSession bootstrap =
            createStandalonePairingClient();
        vrBootstrap=bootstrap;
        final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] tick = new Runnable[1];
        final long[] lastShownSecs = new long[] { -1L };
        connectDialog=trackDialog(new android.app.AlertDialog.Builder(this).setTitle("Connecting to your PC")
            .setMessage(getString(R.string.hub_vr_setup_contacting_host, host))
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
                try { createPairingStore().save(enrolled); }
                catch (Exception e) {
                    throw new com.vibertemis.quest.pcvr.StandalonePairingClient.SetupFailure(
                            getString(R.string.hub_vr_setup_save_failed));
                }
                if (generation != connectGeneration || isFinishing() || isDestroyed()) return;
                runOnUiThread(() -> {
                    if (tick[0] != null) ui.removeCallbacks(tick[0]);
                    if (generation!=connectGeneration || isFinishing() || isDestroyed()) return;
                    finishHostConnection();
                    if (!resumed) {
                        pairingNotice = "Paired. Select Connect to play.";
                        return;
                    }
                    showPairingNotice("Paired. Select Connect to play.");
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

    /**
     * Legacy cold-start confirmation kept for the manual VR fallback
     * paths (Advanced → Manual VR, Connection options → Open PCVR
     * manually). The main {@link #launchSteamVr} entry no longer
     * uses this dialog: the authenticated probe has already
     * established what this dialog used to ask.
     */
    private void confirmPcvrRestartLegacy(Runnable connect) {
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
                restartConsentUntil = android.os.SystemClock.elapsedRealtime() + RESTART_CONSENT_WINDOW_MS;
                connect.run();
            }).show());
    }

    private void showConnectionOptions() {
        trackDialog(new android.app.AlertDialog.Builder(this).setTitle("PCVR connection options")
            .setItems(new String[]{"Pair or change PC", "Open PCVR manually"}, (d,which) -> {
                if (which == 0) showVrSetup();
                else startManualVr();
            }).setNegativeButton("Cancel", null).show());
    }

    private void finishHostConnection() {
        connectPending = false;
        hostClient = null;
        vrBootstrap = null;
        refreshConnectionCard();
        if (connectDialog != null) { connectDialog.dismiss(); connectDialog = null; }
        if (restartVrDialog != null) {
            try { restartVrDialog.dismiss(); } catch (Exception ignored) { }
            restartVrDialog = null;
        }
    }

    private void cancelHostConnection() {
        ++connectGeneration;
        pendingContinuation = false;
        restartPromptPending = false;
        restartConsentUntil = 0;
        // An explicit Cancel abandons the action that was waiting on a
        // paused permission grant, so the queued continuation and the
        // target that identifies it are both dropped here.
        permissionContinuationPending = false;
        permissionContinuationTarget = MIC_TARGET_NONE;
        if (hostClient != null) hostClient.cancel();
        if (vrBootstrap != null) vrBootstrap.cancel();
        finishHostConnection();
        clearInlinePhase();
        clearInlineError();
    }

    /** Re-attempt a connect after an inline error. The retry path
     *  bumps the generation and re-runs {@link #launchSteamVr}; the
     *  probe runs again and the consent decision is re-evaluated
     *  from the current PC state. The retry button is hidden on the
     *  rare consent-expiry case where the user must explicitly
     *  confirm Restart VR again before continuing. */
    private void retryConnect() {
        if (launchPending || requestPending || connectPending) return;
        if (!VrCapabilities.isHeadset(this) || !hasMicPermission()) {
            showInlineError(R.string.hub_error_mic_required);
            return;
        }
        if (!hasPairedHost()) {
            showInlineError(R.string.hub_error_setup_first);
            return;
        }
        clearInlineError();
        launchSteamVr();
    }

    @Override protected void onDestroy() {
        cancelHostConnection();
        cancelVrSetupDiscovery();
        permissionContinuationPending = false;
        permissionContinuationTarget = MIC_TARGET_NONE;
        // No permission target may outlive the activity: a result that
        // lands after destroy must find nothing to continue.
        micPermissionTarget = MIC_TARGET_NONE;
        for (android.app.AlertDialog d : new java.util.ArrayList<>(transientDialogs)) {
            if (d != null && d.isShowing()) {
                try { d.dismiss(); } catch (Exception ignored) { }
            }
        }
        transientDialogs.clear();
        if (setupDialog != null) { try { setupDialog.dismiss(); } catch (Exception ignored) { } setupDialog = null; }
        if (connectDialog != null) { try { connectDialog.dismiss(); } catch (Exception ignored) { } connectDialog = null; }
        if (restartVrDialog != null) { try { restartVrDialog.dismiss(); } catch (Exception ignored) { } restartVrDialog = null; }
        connectWorker.shutdownNow();
        if (updateRepository != null && updateObserver != null) updateRepository.removeObserver(updateObserver);
        super.onDestroy();
    }

    private void dispatchSteamVr() {
        if (!resumed || isFinishing() || isDestroyed() || launchPending || !hasMicPermission()) return;
        if (!VrCapabilities.isHeadset(this)) {
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (usesNativeRuntime() && !restartConsentValid()) {
            // A silent expiry between confirmation and dispatch must
            // never produce a launch. Surface the inline error so the
            // user can confirm Restart VR again on the same attempt.
            connectPending = false;
            hostClient = null;
            clearInlinePhase();
            showInlineError(R.string.hub_error_consent_expired);
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
            connectPending = false;
            hostClient = null;
            clearInlinePhase();
            clearInlineError();
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

    private void showPairingNotice(String message) {
        refreshConnectionCard();
        clearInlineError();
        phaseView.setText(message);
        phaseView.setVisibility(View.VISIBLE);
    }

    private void refreshConnectionCard() {
        Button connect = findViewById(R.id.hub_btn_connect);
        if (connect == null) return;
        boolean headset = VrCapabilities.isHeadset(this);
        boolean paired = hasPairedHost();
        ((TextView) findViewById(R.id.hub_connect_note)).setText(headset
                ? getString(paired ? R.string.hub_card_note_paired : R.string.hub_card_note_unpaired)
                : getString(R.string.hub_card_note_phone));
        findViewById(R.id.hub_status).setVisibility(headset ? View.GONE : View.VISIBLE);
        Button setup = findViewById(R.id.hub_btn_setup);
        setup.setText(headset && paired ? getString(R.string.hub_btn_change_pc)
                : getString(R.string.hub_btn_setup));
        setup.setVisibility(headset && !paired ? View.GONE : View.VISIBLE);
        connect.setEnabled(!connectPending && !launchPending);
        connect.setText(connectPending ? getString(R.string.hub_connecting) : getString(headset && !paired
                ? R.string.hub_btn_setup_pc : R.string.hub_btn_connect));
        findViewById(R.id.hub_btn_cancel_connection).setVisibility(connectPending ? View.VISIBLE : View.GONE);
        TextView savedPc = findViewById(R.id.hub_card_pc_status);
        if (headset && paired) {
            try {
                HostPairing saved = loadHostPairing();
                savedPc.setText(saved == null ? getString(R.string.hub_card_unpaired)
                        : getString(R.string.hub_card_paired, saved.address));
            } catch (Exception ignored) { savedPc.setText(R.string.hub_card_unpaired); }
        } else savedPc.setText(headset ? getString(R.string.hub_card_unpaired) : getString(R.string.hub_card_pc_choose));
        // The paired caveat is only true when a PC is actually saved, so
        // it rides the same headset && paired condition as the saved-PC
        // line. An unpaired headset or a phone must not be told its PC
        // "will be checked", and the paired label is deliberately not
        // named here: the paired choice is the separate Change PC link.
        TextView pairedCaveat = findViewById(R.id.hub_card_pc_caveat);
        if (pairedCaveat != null) {
            pairedCaveat.setVisibility(headset && paired ? View.VISIBLE : View.GONE);
        }
    }

    /** Show the inline phase line in the connection card. Hidden at
     *  rest and shown only while a connect attempt is in flight. */
    private void showInlinePhase(int resId) {
        refreshConnectionCard();
        if (phaseView == null) return;
        phaseView.setText(resId);
        phaseView.setVisibility(View.VISIBLE);
    }

    private void clearInlinePhase() {
        refreshConnectionCard();
        if (phaseView == null) return;
        phaseView.setVisibility(View.GONE);
        phaseView.setText("");
    }

    /** Show the inline error block in the connection card. The retry
     *  chip is shown so the user can re-attempt the connect without
     *  re-doing the consent dialog. The cancel chip is shown only
     *  while a worker is in flight (so the user can stop a stuck
     *  attempt); an idle error (no in-flight worker) hides the
     *  cancel chip because there is nothing to cancel. */
    private void showInlineError(int resId) {
        showInlineError(getString(resId));
    }

    private void showInlineError(String message) {
        if (errorRow == null || errorView == null) return;
        errorView.setText(message);
        errorRow.setVisibility(View.VISIBLE);
        if (errorRetryBtn != null) errorRetryBtn.setVisibility(View.VISIBLE);
        if (errorCancelBtn != null) errorCancelBtn.setVisibility(connectPending ? View.VISIBLE : View.GONE);
    }

    private void clearInlineError() {
        if (errorRow == null) return;
        errorRow.setVisibility(View.GONE);
        if (errorView != null) errorView.setText("");
    }

    /**
     * Bind (or re-bind) the updates badge to the process-wide
     * {@link com.vibertemis.quest.update.UpdateRepository} that the
     * provider currently publishes.
     *
     * <p>The provider may hand back a different instance (or none at
     * all) from one call to the next — an initialization that failed
     * on the first call is retried by a later {@code get} — so the
     * hub re-binds on every resume instead of trusting the identity
     * it saw in {@code onCreate}:
     * <ul>
     *   <li><b>Same instance</b> — no-op. The observer attached
     *       earlier is still the right one, so repeated resumes never
     *       add a second observer and never re-render twice.</li>
     *   <li><b>Different instance</b> — the prior observer is
     *       detached before the new one is attached, so a replaced or
     *       shut-down repository can never call back into this
     *       activity.</li>
     *   <li><b>Null</b> — detach and hide the badge: "nothing is
     *       known" must not leave a stale line rendered from a
     *       previous instance. A null bind is never skipped, so the
     *       badge is also hidden on the very first call.</li>
     * </ul>
     *
     * <p>The observer re-checks liveness AND its own captured
     * instance identity inside the UI-thread hop. A snapshot
     * published on the check thread between pause and destroy — or
     * after the hub already re-bound to another repository — must
     * not render.
     */
    private void bindUpdateRepository() {
        com.vibertemis.quest.update.UpdateRepository repository =
                com.vibertemis.quest.update.UpdateRepositoryProvider.get(getApplicationContext());
        // Identity short-circuit for a live, unchanged repository. A
        // null result always falls through so the badge is hidden
        // rather than left over from a previous instance.
        if (repository != null && repository == updateRepository) return;
        if (updateObserver != null) {
            if (updateRepository != null) updateRepository.removeObserver(updateObserver);
            updateObserver = null;
        }
        updateRepository = repository;
        if (repository == null) {
            renderUpdatesBadge(null);
            return;
        }
        com.vibertemis.quest.update.UpdateRepository.Observer observer = snap -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (updateRepository != repository) return;
            renderUpdatesBadge(snap);
        });
        updateObserver = observer;
        repository.addObserver(observer);
        renderUpdatesBadge(repository.snapshot());
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
        // Use the already-bound repository: a second provider read
        // here could hand back an instance the hub is not observing.
        com.vibertemis.quest.update.UpdateRepository repository = updateRepository;
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
            badge.setText(R.string.hub_updates_badge_current);
            badge.setVisibility(View.VISIBLE);
            return;
        }
        badge.setVisibility(View.GONE);
    }
}