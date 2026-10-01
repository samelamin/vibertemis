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
 *             the PC. That is a <b>normal, idempotent</b> connection:
 *             we issue the start request and dispatch with
 *             {@code vq_pcvr_allow_restart=false} and a zero restart
 *             deadline. No prompt — a warm server is not a
 *             discrepancy, and restarting a perfectly good session
 *             would be the surprising behaviour.</li>
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
 * <p><b>Restart consent is only for real mismatches.</b> The
 * <b>Restart VR / Cancel</b> dialog is no longer produced by a warm
 * {@code vrserver=true} probe. It appears only when the native runtime
 * itself reports that the running SteamVR session does not match the
 * settings this dispatch asked for — the authentic
 * {@link SteamVrActivity#onPcvrConnectionIssue(String) onPcvrConnectionIssue}
 * callback, and only after the old immersive process is provably gone.
 * Until then the warm path is a plain connection with no restart
 * permission and no deadline.
 *
 * <p><b>The native return contract.</b> Every immersive dispatch mints
 * a random one-shot nonce and passes it with the public pairing
 * certificate pin (never a credential) in the launch Intent.
 * {@link SteamVrActivity} echoes both back, plus its own pid, in an
 * explicit hub Intent ({@code CLEAR_TOP|SINGLE_TOP}) when native
 * reports {@code restart_required} or {@code restart_failed}. The hub
 * consumes that callback exactly once and only after validating the
 * nonce against its pending expectation and the pin against the
 * pairing it holds <i>now</i>; a missing, forged, stale, duplicate or
 * host-changed callback is inert. Nothing is dispatched automatically:
 * a valid {@code restart_required} surfaces the existing Restart VR /
 * Cancel dialog, and a valid {@code restart_failed} surfaces a concise
 * Retry / Cancel error. A positive restart consent is never persisted
 * across recreation, so a recreated hub can still recognise the
 * callback but can never act on a forgotten "yes".
 *
 * <p><b>Real process gate, no human-timing guesswork.</b> Before any
 * new immersive dispatch — including the re-probe behind a Restart
 * tap — the hub proves through {@link ActivityManager#getRunningAppProcesses()}
 * that no process of its own UID with the exact
 * {@code <package>:pcvr} name and the old pid is still running, polling
 * a bounded budget. This never relies on how fast the user reacts or on
 * an arbitrary debounce: if the process state cannot be established the
 * gate fails clearly and no launch happens. The gate's answer is
 * delivered asynchronously and is treated as untrusted: it carries a
 * gate identity and the {@code connectGeneration} it was started for,
 * and both must still match before anything is launched, prompted or
 * re-probed. A Cancel, a Destroy or a newer attempt invalidates that
 * identity, so a poll already in flight cannot resurrect an abandoned
 * attempt, and a superseded completion never clears the running flag of
 * a gate that replaced it.
 *
 * <p><b>A gate that finishes while the hub is paused is parked, not
 * dropped.</b> Dropping it would strand the attempt, and running it
 * immediately would return without any resume continuation. So the
 * outcome is queued together with its gate identity and generation and
 * executed on the next resume, exactly once, and only if the hub is
 * still alive, still a headset, still holds the microphone, the
 * generation has not moved, and any restart consent the attempt
 * required is still inside its window. Cancel and Destroy clear the
 * parked outcome, and it is never persisted: a recreated hub cancels
 * rather than replaying a forgotten positive consent.
 *
 * <p><b>A restart consent is spent on one named host.</b> The public
 * pairing pin that the validated native callback carried is retained
 * from the moment the callback is accepted until the Restart tap has
 * finished. The live pairing is compared against it before the gate,
 * after it, after the fresh probe, and immediately before the dispatch;
 * the start request is issued against the very pairing the probe
 * authenticated rather than a reloaded one. A host that merely changed
 * address keeps the pin and is rediscovery, not a re-pair; a changed pin
 * cancels the attempt with a "pairing changed" notice, so a restart is
 * never granted on a machine the user never agreed to. The pin the
 * dispatch carries comes from the current attempt only — a background
 * worker's cached pin is published solely on the UI thread, inside that
 * attempt's generation check.
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
 * still has microphone permission, and any restart consent this attempt
 * required has not silently expired. A warm, normal connection carries
 * no restart consent at all and is therefore never blocked by consent
 * expiry; a cold or explicitly consented attempt that expires is
 * dropped with an inline error so the user must reconnect and reconfirm
 * — consent is never silently renewed. The continuation is bound to the
 * current {@code connectGeneration}; a Cancel / Destroy / new tap
 * bumps the generation and any queued continuation is dropped.
 *
 * <p>The hub never auto-launches from {@code onResume} out of nothing.
 * The only things a resume may continue are the ones an explicit user
 * action started and that were waiting: a paused microphone grant, a
 * connect attempt whose start already returned, and a process-gate
 * outcome that settled while the hub was paused. Each is consumed
 * exactly once and re-checks that it still belongs to this attempt. The
 * hub guards every tap against in-flight permission requests and pending
 * launches so a rapid double-tap or an asynchronous permission result
 * for a different request cannot start two activities at once.
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

    /* ---- Native return contract (Java <-> JNI boundary) ---- */

    /** Action of the explicit Intent {@link SteamVrActivity} sends back
     *  to this hub when the native runtime reports a real connection
     *  issue. Deliberately explicit so no unrelated Intent can be
     *  mistaken for a native report. */
    static final String ACTION_PCVR_RETURN = "com.vibertemis.quest.hub.action.PCVR_RETURN";

    /** One of {@link PcvrReturnGate#ISSUE_RESTART_REQUIRED} /
     *  {@link PcvrReturnGate#ISSUE_RESTART_FAILED}. */
    static final String EXTRA_PCVR_ISSUE = "vq_pcvr_issue";

    /** Random one-shot nonce minted per immersive dispatch. It is a
     *  correlation id, not a credential. */
    static final String EXTRA_PCVR_LAUNCH_NONCE = "vq_pcvr_launch_nonce";

    /** Public pairing certificate pin (SHA-256 of the host
     *  certificate). Public identity, deliberately never the pairing
     *  auth token: no credential is ever placed in an Intent. */
    static final String EXTRA_PCVR_HOST_PIN = "vq_pcvr_host_pin";

    /** Pid of the {@code :pcvr} process that is reporting, so the hub
     *  can prove it is gone before any new immersive dispatch. */
    static final String EXTRA_PCVR_OLD_PID = "vq_pcvr_old_pid";

    /* ---- Saved instance state ---- */

    private static final String STATE_PCVR_LAUNCH_NONCE = "vq_hub_pcvr_nonce";
    private static final String STATE_PCVR_HOST_PIN = "vq_hub_pcvr_pin";
    private static final String STATE_PCVR_ISSUE = "vq_hub_pcvr_issue";
    private static final String STATE_PCVR_OLD_PID = "vq_hub_pcvr_old_pid";
    private static final String STATE_PCVR_ACCEPTED_PIN = "vq_hub_pcvr_accepted_pin";

    /** Bounded budget for proving the old {@code :pcvr} process is
     *  gone. This is a process-identity wait, not a UX delay: the poll
     *  returns the moment the kernel stops reporting that pid, and
     *  exhausts only when the process is genuinely still there. */
    static final long PCVR_EXIT_GATE_MS = 10000L;
    /** Poll interval of the exit gate. */
    private static final long PCVR_EXIT_POLL_MS = 100L;

    /** No microphone permission request is in flight. */
    private static final int MIC_TARGET_NONE = 0;
    /** The primary Connect action on a paired host: the grant
     *  continues through the authenticated probe and the start. */
    private static final int MIC_TARGET_CONNECT = 1;
    /** The manual VR fallback (Advanced → Manual VR, Connection
     *  options → Open PCVR manually): the grant continues through the
     *  explicit legacy restart consent, never straight to a launch. */
    private static final int MIC_TARGET_MANUAL_VR = 2;

    /** Probe path: a warm server is a normal idempotent connection, a
     *  cold server opens the 120s restart-consent window itself. */
    private static final int PROBE_MODE_CONNECT = 0;
    /** Probe path taken behind an explicit Restart tap: the user has
     *  already said yes, so the window is opened whichever way the
     *  fresh probe resolves. */
    private static final int PROBE_MODE_AFTER_RESTART_CONSENT = 1;

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
    /**
     * True only while THIS attempt actually needs positive restart
     * consent (a cold PC, an explicit Restart tap, the manual VR
     * legacy consent). A warm {@code vrserver=true} probe is a normal
     * connection: it needs no consent, so it dispatches with
     * {@code vq_pcvr_allow_restart=false} and a zero deadline and is
     * never blocked by consent expiry.
     *
     * <p>Deliberately not persisted: a positive restart consent must
     * not survive a recreation.
     */
    private boolean restartConsentRequired;
    /** A {@link PcvrReturnGate} owns the one-shot launch nonce and the
     *  public pin it must come back with. */
    private final PcvrReturnGate returnGate = new PcvrReturnGate();
    /** A validated native issue waiting to be surfaced. Survives
     *  recreation through {@link #STATE_PCVR_ISSUE} so a hub that was
     *  destroyed behind the system dialog can still show the prompt
     *  exactly once. */
    private String pendingPcvrIssue;
    /**
     * The public pairing certificate pin that the accepted native
     * callback was validated against, retained from the moment the
     * callback is accepted until the Restart tap has finished (or been
     * abandoned).
     *
     * <p>It exists so a positive restart consent is spent on the host
     * the user actually said yes to. The consent is captured here, NOT
     * re-derived from {@link #pendingHostPin} (which belongs to a
     * connect worker's generation and may be a different attempt) and
     * never from a background generation that has since been cancelled.
     * Public identity, never a credential.
     */
    private String pendingIssueHostPin;
    /** Pid reported by the native callback, awaited by the exit gate.
     *  {@code <= 0} means "unknown", which fails closed. */
    private int pendingOldPcvrPid;
    /** True while the real process-liveness gate is polling the old
     *  {@code :pcvr} pid. Blocks taps and dispatch so nothing races the
     *  teardown. */
    private boolean pcvrExitGateRunning;
    /**
     * Monotonic identity of the exit gate. Every gate bumps it, and
     * {@link #cancelHostConnection()} bumps it too, so a completion
     * that was already in flight can tell that it has been superseded.
     * A stale completion MUST leave {@link #pcvrExitGateRunning} alone:
     * the flag may by then belong to a newer gate.
     */
    private int pcvrExitGateId;
    /**
     * The poll task of the gate that is currently running, so a Cancel /
     * Destroy can interrupt the wait instead of only ignoring it.
     */
    private java.util.concurrent.Future<?> pcvrExitGateFuture;
    /**
     * A gate outcome that proved a process fact while the hub was
     * paused, parked until the next resume. It is bound to the gate
     * identity and the generation it was produced for, so a Cancel /
     * Destroy / new attempt drops it instead of replaying a stale
     * launch or a stale prompt. Deliberately not persisted: a recreated
     * hub cancels rather than re-runs a forgotten positive consent.
     */
    private Runnable pendingGateContinuation;
    private int pendingGateContinuationId = -1;
    private int pendingGateContinuationGeneration = -1;
    /** Public pairing certificate pin the current attempt authenticated,
     *  so the dispatch does not have to touch the keystore on the UI
     *  thread. Never a credential.
     *
     *  <p>Published on the UI thread only, and only from inside the
     *  {@code connectGeneration} check of the probe callback, so a
     *  superseded worker can never hand its pin to the attempt that
     *  replaced it. Attempt-local: {@code null} again once the attempt
     *  ends, and a path that skipped the probe (manual VR) re-reads the
     *  store instead. */
    private String pendingHostPin;
    private boolean resumed;
    private boolean restartPromptPending;
    private volatile int connectGeneration;
    private com.vibertemis.quest.update.UpdateRepository updateRepository;
    /** Version whose banner the user dismissed with Later (process lifetime). */
    private String dismissedUpdateVersion;
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
    /** The Restart VR / Cancel consent. It is shown ONLY for a real
     *  native mismatch report ({@code restart_required}) after the old
     *  immersive process is provably gone — a warm probe never prompts
     *  on its own. The user must press Restart VR before we re-probe,
     *  open the 120s consent window and start. */
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
            // Only the correlation identity and the validated issue
            // cross a recreation. A positive restart consent does NOT:
            // a recreated hub must be able to recognise the callback
            // but is never allowed to act on a forgotten "yes".
            returnGate.restorePending(
                    savedInstanceState.getString(STATE_PCVR_LAUNCH_NONCE),
                    savedInstanceState.getString(STATE_PCVR_HOST_PIN));
            pendingPcvrIssue = PcvrReturnGate.normalizeIssue(
                    savedInstanceState.getString(STATE_PCVR_ISSUE));
            pendingOldPcvrPid = savedInstanceState.getInt(STATE_PCVR_OLD_PID, 0);
            // The public pin the accepted callback was validated against
            // is correlation identity, not consent, so it may cross a
            // recreation. It is useless on its own: the Restart tap still
            // has to re-compare it against the live pairing, and no
            // positive consent is restored with it.
            pendingIssueHostPin = PcvrReturnGate.isPublicPin(
                    savedInstanceState.getString(STATE_PCVR_ACCEPTED_PIN))
                    ? savedInstanceState.getString(STATE_PCVR_ACCEPTED_PIN) : null;
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
            // Phones get a single "Play" card: no Big Screen card, no VR
            // art, and the mint primary action instead of the VR styling.
            findViewById(R.id.hub_card_screen).setVisibility(View.GONE);
            findViewById(R.id.hub_art_vr_frame).setVisibility(View.GONE);
            findViewById(R.id.hub_subtitle_note).setVisibility(View.GONE);
            ((TextView) findViewById(R.id.hub_card_title)).setText(R.string.hub_card_title_phone);
            connectBtn.setBackgroundResource(R.drawable.hub_btn_primary_bg);
            connectBtn.setTextColor(getColor(R.color.hub_panel_accent_on));
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
        findViewById(R.id.hub_btn_updates).setOnClickListener(v -> openUpdates());
        findViewById(R.id.hub_update_banner_action).setOnClickListener(v -> openUpdates());
        findViewById(R.id.hub_update_banner_later).setOnClickListener(v -> {
            com.vibertemis.quest.update.UpdateRepository repository = updateRepository;
            com.vibertemis.quest.update.UpdateRepository.Snapshot snap =
                    repository == null ? null : repository.snapshot();
            dismissedUpdateVersion = bannerVersion(snap);
            renderUpdatesBadge(snap);
        });
        renderVersionChip();
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
        // A hub that was recreated with the native return Intent as its
        // own Intent must still process the callback exactly like an
        // onNewIntent delivery. Validation is view-free and idempotent
        // (the nonce is one-shot), and the prompt itself waits for the
        // first resume.
        handlePcvrReturn(getIntent());
    }

    /**
     * Primary Connect entry. The hub auto-detects device class and
     * routes to the authenticated PCVR start (headset) or to the
     * existing flat {@link PcView} start (everything else). Mic
     * permission is only required on a headset — phone Connect never
     * asks for it because the flat PcView path does not need it.
     */
    private void onConnectTapped() {
        if (launchPending || requestPending || connectPending || pcvrExitGateRunning) {
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
        // Persist ONLY the native-return correlation identity and a
        // validated-but-not-yet-surfaced issue. The restart-consent
        // window is intentionally absent: positive restart consent must
        // be given again after a recreation.
        if (returnGate.awaitingCallback()) {
            outState.putString(STATE_PCVR_LAUNCH_NONCE, returnGate.pendingNonce());
            outState.putString(STATE_PCVR_HOST_PIN, returnGate.pendingPin());
        }
        outState.putString(STATE_PCVR_ISSUE, pendingPcvrIssue == null ? "" : pendingPcvrIssue);
        outState.putInt(STATE_PCVR_OLD_PID, pendingOldPcvrPid);
        outState.putString(STATE_PCVR_ACCEPTED_PIN,
                pendingIssueHostPin == null ? "" : pendingIssueHostPin);
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
        // A process-liveness gate that finished while the hub was paused
        // parked its continuation instead of dropping it. Run it first,
        // and exactly once, so the attempt that was waiting on the old
        // process continues on this resume rather than stranding
        // connectPending. The continuation re-checks its own gate
        // identity, generation, device class, microphone permission and
        // any consent it needed; a Cancel / Destroy / new attempt while
        // paused means there is nothing here to run.
        drainPendingGateContinuation();
        // A validated native issue that arrived while the hub was paused
        // (or behind a recreation) is surfaced here, and only here: the
        // prompt itself waits until the hub is resumed, and for the old
        // immersive process to be provably gone.
        advancePendingPcvrIssue();
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
        // headset, the microphone permission has been revoked, or a
        // restart consent this attempt required has silently expired.
        // A valid continuation dispatches SteamVrActivity exactly once
        // through the real process gate.
        if (pendingContinuation
                && pendingContinuationGeneration == connectGeneration
                && !isFinishing() && !isDestroyed()
                && VrCapabilities.isHeadset(this)
                && hasMicPermission()
                && consentSatisfied()) {
            pendingContinuation = false;
            dispatchSteamVr();
        } else if (pendingContinuation) {
            // Drop the queued continuation: a Cancel / Destroy /
            // permission loss / consent expiry happened while paused.
            // An expired consent surfaces an error so the user has to
            // reconnect and reconfirm; it is never renewed silently.
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
            prefs.rememberMode(HubPrefs.MODE_SCREEN);
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
     *             already running, so this is an ordinary idempotent
     *             connection: issue the start request and dispatch with
     *             {@code vq_pcvr_allow_restart=false} and a zero
     *             restart deadline. <b>No prompt.</b> A warm server is
     *             not a discrepancy. If the running session does not
     *             match the settings this dispatch asked for, the
     *             native runtime reports that itself through
     *             {@link SteamVrActivity#onPcvrConnectionIssue(String)}
     *             and only then is the user asked about a restart.</li>
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
     *       a headset, still has microphone permission, and any
     *       restart consent this attempt required has not silently
     *       expired.</li>
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
        if (launchPending || connectPending || pcvrExitGateRunning
                || !VrCapabilities.isHeadset(this) || !hasMicPermission()) return;
        if (!hasPairedHost()) { showVrSetup(); return; }
        connectPending = true;
        final int generation = ++connectGeneration;
        pendingContinuation = false;
        // A fresh attempt supersedes whatever the previous session left
        // behind: no callback expectation, no un-surfaced native issue,
        // no accepted host pin, no parked gate continuation, and no
        // restart consent (the probe below re-decides all of them).
        returnGate.clearPending();
        pendingPcvrIssue = null;
        pendingOldPcvrPid = 0;
        pendingIssueHostPin = null;
        dropPendingGateContinuation();
        restartConsentRequired = false;
        restartConsentUntil = 0;
        // Drop the queued continuation: a fresh tap supersedes any
        // pending dispatch from an earlier in-flight attempt.
        final HostClient client = createHostClient();
        hostClient = client;
        showInlinePhase(R.string.hub_phase_probing);
        clearInlineError();
        probeAndContinue(generation, client, PROBE_MODE_CONNECT, null);
    }

    /**
     * Run the authenticated {@code GET /status} probe on the connect
     * worker and hand the verified {@code vrserver} verdict to the
     * connect state machine on the UI thread.
     *
     * <p>The pairing is re-loaded here on every pass, so an explicit
     * Restart tap re-reads the stored identity and re-checks its pin
     * instead of trusting anything captured earlier. On an unreachable
     * saved address the bounded LAN rediscovery helper runs against the
     * paired TLS pin and the probe is retried once the hint verifies;
     * because the pin travels with the pairing, a host that merely
     * changed address keeps its identity and needs no new consent.
     *
     * <p>When {@code requiredPin} is set, the attempt is pinned to one
     * host: the pin is compared before the probe is issued, again after
     * it returns, and again before the start request and the dispatch.
     * A host that merely moved keeps the pin and passes; a re-pair
     * changes it and the attempt is cancelled with a "pairing changed"
     * notice instead of granting a restart against a machine the user
     * never consented to. The pairing the probe authenticated is handed
     * forward to the start request, so the start can never be issued
     * against a different store entry than the one the probe verified.
     *
     * @param mode {@link #PROBE_MODE_CONNECT} for a plain tap, or
     *             {@link #PROBE_MODE_AFTER_RESTART_CONSENT} behind an
     *             explicit Restart tap where consent is already given
     * @param requiredPin public pin this attempt must stay pinned to, or
     *                    {@code null} for a plain connect
     */
    private void probeAndContinue(final int generation, final HostClient client, final int mode,
                                  final String requiredPin) {
        connectWorker.execute(() -> {
            try {
                HostPairing pairing = loadHostPairing();
                if (pairing == null) throw new HostClient.Failure("PAIRING", "Pair your PC again.");
                if (requiredPin != null && !requiredPin.equals(pairing.pin)) {
                    // Before the probe: the host moved to a different
                    // identity while the consent was being collected.
                    runOnUiThread(() -> {
                        if (generation != connectGeneration || isFinishing() || isDestroyed()) return;
                        cancelForPairingChange();
                    });
                    return;
                }
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
                    // Address rediscovery is not a pairing change: the
                    // candidate is only accepted because it presents the
                    // pinned certificate, so the accepted pin is unchanged.
                    createPairingStore().updateAddress(pairing, candidate);
                }
                if (client.isCancelled()) throw new java.io.IOException("Cancelled");
                if (usesNativeRuntime()) client.setHeadsetHostname(loadNativeHeadsetIdentity());
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
                final boolean vrserver = status.getBoolean("vrserver");
                if (client.isCancelled()) throw new java.io.IOException("Cancelled");
                // The pin this attempt is pinned to, and the pairing the
                // probe just authenticated. Both are captured here and
                // carried forward instead of being re-read mid-attempt.
                final String probedPin = pairing.pin;
                runOnUiThread(() -> {
                    if (generation != connectGeneration || isFinishing() || isDestroyed()) return;
                    // After the probe: the pairing may have been replaced
                    // while the request was in flight.
                    if (requiredPin != null && !pairedPinMatches(requiredPin)) {
                        cancelForPairingChange();
                        return;
                    }
                    // Publish the public pin to the UI only here, on the
                    // UI thread, inside the generation check — so a
                    // superseded worker can never hand its pin to the
                    // attempt that replaced it.
                    pendingHostPin = probedPin;
                    if (mode == PROBE_MODE_AFTER_RESTART_CONSENT) {
                        // The user already confirmed a restart for THIS
                        // attempt, so the window opens whichever way the
                        // fresh probe resolved — a host that came back
                        // cold simply starts normally. No second prompt:
                        // repeating the dialog without a fresh native
                        // mismatch would be a loop, not a decision.
                        restartConsentRequired = true;
                        restartConsentUntil =
                                android.os.SystemClock.elapsedRealtime() + RESTART_CONSENT_WINDOW_MS;
                        startPcvrConnection(generation, requiredPin, pairing);
                        return;
                    }
                    if (!vrserver) {
                        // Cold server. The probe already proved the PC
                        // is reachable and SteamVR is not running, so a
                        // cold start cannot disrupt a live VR session.
                        // Set the consent window and continue — no modal
                        // dialog.
                        restartConsentRequired = true;
                        restartConsentUntil =
                                android.os.SystemClock.elapsedRealtime() + RESTART_CONSENT_WINDOW_MS;
                        startPcvrConnection(generation, requiredPin, pairing);
                    } else {
                        // Warm server: a normal, idempotent connection.
                        // No restart consent at all, so the dispatch
                        // carries vq_pcvr_allow_restart=false with a
                        // zero deadline and the user is never prompted.
                        restartConsentRequired = false;
                        restartConsentUntil = 0;
                        startPcvrConnection(generation, requiredPin, pairing);
                    }
                });
            } catch (Exception e) {
                final String message = e instanceof HostClient.Failure ? e.getMessage()
                        : "Could not reach your PC. Check the companion, address and network, then retry.";
                runOnUiThread(() -> {
                    if (generation != connectGeneration) return;
                    connectPending = false;
                    hostClient = null;
                    pendingHostPin = null;
                    clearInlinePhase();
                    showInlineError(message);
                });
            }
        });
    }

    /**
     * Restart tap behind a real native mismatch, pinned to the host the
     * user was actually asked about.
     *
     * <p>{@code acceptedPin} is the public pin the validated callback
     * carried and the hub verified at accept time. It is retained
     * through the dialog and this whole sequence, and the live pairing is
     * compared against it three times: before the exit gate, after it,
     * and again after the fresh probe (see
     * {@link #probeAndContinue}). A host that merely changed address
     * keeps the pin and continues; a re-pair changes it, and the attempt
     * is cancelled with a "pairing changed" notice so a restart is never
     * granted on a machine the user never consented to.
     *
     * <p>The attempt is otherwise rebuilt from scratch: a <b>new</b>
     * {@link HostClient} (the stale one belongs to the session that
     * failed), a fresh authenticated probe against the pairing as it is
     * stored right now, and then the existing 120-second consent + start
     * flow. Nothing here can skip a network round-trip, and the consent
     * window opens only after that probe succeeded.
     */
    private void startAfterRestartConsent(final String acceptedPin) {
        if (!resumed || isFinishing() || isDestroyed()
                || launchPending || connectPending || pcvrExitGateRunning) return;
        if (!PcvrReturnGate.isPublicPin(acceptedPin)) {
            // No nameable host means no restart to consent to.
            cancelHostConnection();
            showInlineError(R.string.hub_error_pairing_changed);
            return;
        }
        if (!VrCapabilities.isHeadset(this) || !hasMicPermission()) {
            cancelHostConnection();
            showInlineError(R.string.hub_error_mic_required);
            return;
        }
        if (!hasPairedHost()) {
            cancelHostConnection();
            showInlineError(R.string.hub_error_setup_first);
            return;
        }
        // Before the gate: the paired host must still be the host this
        // consent was given for.
        if (!pairedPinMatches(acceptedPin)) {
            cancelForPairingChange();
            return;
        }
        // The old immersive process must still be gone at the moment we
        // act, not merely when the prompt appeared.
        final int gatePid = pendingOldPcvrPid;
        runPcvrExitGate(gatePid, liveness -> {
            if (liveness != PcvrReturnGate.LIVENESS_GONE) {
                handlePcvrExitGateFailure(liveness);
                return;
            }
            // After the gate, before the probe: re-check the pin, so a
            // re-pair that happened while the prompt was open (or while
            // the gate polled) cannot turn into a fresh probe.
            if (!pairedPinMatches(acceptedPin)) {
                cancelForPairingChange();
                return;
            }
            pendingOldPcvrPid = 0;
            connectPending = true;
            final int generation = ++connectGeneration;
            pendingContinuation = false;
            pendingPcvrIssue = null;
            // The consumed callback can never be replayed, and the
            // accepted pin is now carried by the attempt itself.
            returnGate.clearPending();
            pendingIssueHostPin = acceptedPin;
            restartConsentRequired = false;
            restartConsentUntil = 0;
            final HostClient client = createHostClient();
            hostClient = client;
            showInlinePhase(R.string.hub_phase_probing);
            clearInlineError();
            probeAndContinue(generation, client, PROBE_MODE_AFTER_RESTART_CONSENT, acceptedPin);
        });
    }

    /**
     * True only when the pairing the hub holds <i>right now</i> is the
     * exact public identity this attempt was pinned to. Addresses are
     * deliberately not compared: a host that moved keeps its
     * certificate, and rediscovery of that host is allowed. An
     * unreadable store counts as "changed", because the hub can no
     * longer prove which host it is talking to.
     */
    private boolean pairedPinMatches(String expectedPin) {
        if (!PcvrReturnGate.isPublicPin(expectedPin)) return false;
        try {
            HostPairing pairing = loadHostPairing();
            return pairing != null && expectedPin.equals(pairing.pin);
        } catch (Exception e) {
            Log.w(TAG, "Could not read the pairing to compare it: " + e.getMessage());
            return false;
        }
    }

    /**
     * Abandon an attempt whose host no longer matches the one the user
     * consented to. Nothing is dispatched, no restart permission is
     * granted, and the user is told plainly that the pairing changed so
     * a fresh tap (and a fresh decision) is what comes next.
     */
    private void cancelForPairingChange() {
        cancelHostConnection();
        showInlineError(R.string.hub_error_pairing_changed);
        Log.w(TAG, "PCVR attempt cancelled: the paired host changed under it");
    }

    /** Fail-closed handler for an unknown probe state. Draws an
     *  inline error into the connection card and surfaces an inline
     *  Retry chip. The user can re-attempt the connect without
     *  re-doing the consent dialog. */
    private void handleProbeUnknown(int generation, String reason) {
        if (generation != connectGeneration || isFinishing() || isDestroyed()) return;
        connectPending = false;
        hostClient = null;
        pendingHostPin = null;
        pendingIssueHostPin = null;
        // An unknown state cannot carry a restart consent forward.
        restartConsentRequired = false;
        restartConsentUntil = 0;
        clearInlinePhase();
        showInlineError(getString(R.string.hub_error_state_unknown));
    }

    /**
     * Surface the Restart VR / Cancel consent for a <b>real</b> native
     * mismatch report. A warm {@code vrserver=true} probe never reaches
     * this dialog; only {@link SteamVrActivity#onPcvrConnectionIssue}
     * reporting {@code restart_required}, after the old immersive
     * process is provably gone, does.
     *
     * <p>The dialog is generation-scoped so a Cancel / Destroy / new tap
     * during the dialog cancels the in-flight attempt. Only Restart VR
     * continues, and it continues through
     * {@link #startAfterRestartConsent(String)} — a fresh authenticated
     * re-probe with a new client, never the stale one, pinned to the host
     * the accepted callback named.
     */
    private void showRestartVrConsent(int generation) {
        if (generation != connectGeneration || !connectPending || isFinishing() || isDestroyed()) return;
        if (!resumed) { restartPromptPending = true; return; }
        // The host this consent is about, captured from the validated
        // native callback. It is the ONLY source for the restart attempt:
        // re-deriving it from the connect worker's cached pin would let a
        // background generation decide which machine gets restarted.
        final String acceptedPin = pendingIssueHostPin;
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
                        pendingHostPin = null;
                        clearInlinePhase();
                        showInlineError(R.string.hub_error_mic_required);
                        return;
                    }
                    // Close out the prompt's own attempt before the
                    // re-probe: the new attempt owns connectPending from
                    // here on.
                    finishHostConnection();
                    startAfterRestartConsent(acceptedPin);
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
     * permission, and any restart consent this attempt required has
     * not silently expired. An attempt that required no consent (a
     * warm, normal connection) is never blocked by consent state.
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
     *
     * <p>{@code authenticated} is the very {@link HostPairing} the probe
     * verified, handed forward instead of reloading the store mid
     * attempt: reloading could pick up a different entry, so the start
     * would be signed for a host the probe never authenticated. When a
     * pairing has to be read instead, its pin is compared against
     * {@code requiredPin} first.
     */
    private void startPcvrConnection(int generation, final String requiredPin,
                                     final HostPairing authenticated) {
        if (generation != connectGeneration || !connectPending || launchPending || pcvrExitGateRunning) return;
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
                HostPairing pairing = authenticated != null ? authenticated : loadHostPairing();
                if (pairing == null) throw new HostClient.Failure("PAIRING", "Pair your PC again.");
                if (requiredPin != null && !requiredPin.equals(pairing.pin)) {
                    runOnUiThread(() -> {
                        if (startGeneration != connectGeneration || isFinishing() || isDestroyed()) return;
                        cancelForPairingChange();
                    });
                    return;
                }
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
                        pendingHostPin = null;
                        clearInlinePhase();
                        showInlineError(R.string.hub_error_mic_required);
                        return;
                    }
                    if (requiredPin != null && !pairedPinMatches(requiredPin)) {
                        // Last gate before anything is dispatched: the
                        // host must still be the one the user said yes to.
                        cancelForPairingChange();
                        return;
                    }
                    if (usesNativeRuntime() && !consentSatisfied()) {
                        // A positive consent this attempt required
                        // expired while the host was starting. Fail
                        // closed with an error: the user must reconnect
                        // and reconfirm. Consent is never renewed here.
                        connectPending = false;
                        hostClient = null;
                        pendingHostPin = null;
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
                    pendingHostPin = null;
                    clearInlinePhase();
                    showInlineError(message);
                });
            }
        });
    }

    /**
     * The restart-consent gate for one attempt.
     *
     * <ul>
     *   <li>An attempt that required NO positive consent — a warm
     *       {@code vrserver=true} probe, i.e. a normal idempotent
     *       connection — is always allowed through, and dispatches with
     *       {@code vq_pcvr_allow_restart=false} plus a zero deadline.
     *       Consent state can neither block nor fake it.</li>
     *   <li>An attempt that DID require consent (cold PC, explicit
     *       Restart tap, manual VR legacy consent) must still be inside
     *       its 120-second window. A silent expiry between the decision
     *       and the dispatch must never produce a launch; the caller
     *       surfaces an error so the user reconnects and reconfirms.</li>
     * </ul>
     */
    private boolean consentSatisfied() {
        if (!restartConsentRequired) return true;
        return restartConsentValid();
    }

    /** True if the consent window is still in the future. The window
     *  starts when Restart VR is confirmed (or the cold probe passes)
     *  and ends two minutes later. Only consulted for attempts that
     *  actually required consent — see {@link #consentSatisfied()}. */
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
        if (connectPending || launchPending || requestPending || pcvrExitGateRunning) return;
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
        // Pairing with a PC changes which host the hub speaks to, so any
        // callback expectation from the previous pairing is dropped: a
        // return from the old machine must never drive a restart here.
        returnGate.clearPending();
        pendingPcvrIssue = null;
        pendingOldPcvrPid = 0;
        pendingIssueHostPin = null;
        pendingHostPin = null;
        dropPendingGateContinuation();
        restartConsentRequired = false;
        restartConsentUntil = 0;
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
                restartConsentRequired = usesNativeRuntime();
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
        // Invalidate the running process gate before anything else. The
        // bump means a poll already in flight sees a superseded gate and
        // runs nothing, and the running flag is cleared HERE so the
        // stale completion cannot clear a newer gate's flag instead. The
        // queued outcome is dropped, and the poll is interrupted so the
        // wait ends immediately rather than at its budget.
        ++pcvrExitGateId;
        pcvrExitGateRunning = false;
        java.util.concurrent.Future<?> gatePoll = pcvrExitGateFuture;
        pcvrExitGateFuture = null;
        dropPendingGateContinuation();
        // No restart consent survives a cancel, and no callback
        // expectation, un-surfaced native issue or accepted host pin does
        // either: whatever the abandoned session was going to say is no
        // longer ours.
        restartConsentRequired = false;
        restartConsentUntil = 0;
        returnGate.clearPending();
        pendingPcvrIssue = null;
        pendingOldPcvrPid = 0;
        pendingIssueHostPin = null;
        pendingHostPin = null;
        // An explicit Cancel abandons the action that was waiting on a
        // paused permission grant, so the queued continuation and the
        // target that identifies it are both dropped here.
        permissionContinuationPending = false;
        permissionContinuationTarget = MIC_TARGET_NONE;
        if (gatePoll != null) {
            try { gatePoll.cancel(true); } catch (Exception ignored) { }
        }
        if (hostClient != null) hostClient.cancel();
        if (vrBootstrap != null) vrBootstrap.cancel();
        finishHostConnection();
        clearInlinePhase();
        clearInlineError();
    }

    /** Re-attempt a connect after an inline error. The retry path
     *  bumps the generation and re-runs {@link #launchSteamVr}; the
     *  probe runs again and the consent decision is re-evaluated
     *  from the current PC state. Nothing is carried over: a retry is
     *  a fresh tap, not a resumed consent, so it can neither re-open
     *  the Restart prompt without a fresh native mismatch nor inherit
     *  a deadline from the attempt that failed. */
    private void retryConnect() {
        if (launchPending || requestPending || connectPending || pcvrExitGateRunning) return;
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
        // Hard cancel: this also invalidates the exit gate and drops any
        // parked gate outcome, so nothing a late worker reports can
        // dispatch or prompt on a destroyed hub.
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

    /**
     * Dispatch the immersive PCVR activity.
     *
     * <p>Before anything is dispatched the hub proves, through the real
     * process table, that no process of its own UID with the exact
     * {@code <package>:pcvr} name and the last known pcvr pid is still
     * running. That wait is bounded by {@link #PCVR_EXIT_GATE_MS} and
     * ends the moment the process is gone; it is never a guess about
     * how long a person takes to read a dialog. When the old pid cannot
     * be proven gone, nothing launches and the failure is explicit.
     */
    private void dispatchSteamVr() {
        if (!resumed || isFinishing() || isDestroyed() || launchPending
                || !hasMicPermission() || pcvrExitGateRunning) return;
        if (!VrCapabilities.isHeadset(this)) {
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (pendingIssueHostPin != null && !pairedPinMatches(pendingIssueHostPin)) {
            // This attempt carries an accepted restart consent for one
            // specific host; if the pairing moved underneath it, there is
            // nothing left to dispatch.
            cancelForPairingChange();
            return;
        }
        if (usesNativeRuntime() && !consentSatisfied()) {
            // A silent expiry between confirmation and dispatch must
            // never produce a launch. Surface the inline error so the
            // user can confirm Restart VR again on the same attempt.
            connectPending = false;
            hostClient = null;
            pendingHostPin = null;
            clearInlinePhase();
            showInlineError(R.string.hub_error_consent_expired);
            return;
        }
        final int gatePid = pendingOldPcvrPid;
        runPcvrExitGate(gatePid, liveness -> {
            if (liveness != PcvrReturnGate.LIVENESS_GONE) {
                handlePcvrExitGateFailure(liveness);
                return;
            }
            pendingOldPcvrPid = 0;
            dispatchSteamVrIntent();
        });
    }

    /**
     * The actual immersive dispatch. Only reached once
     * {@link #dispatchSteamVr()} established that the old pcvr process
     * is gone.
     */
    private void dispatchSteamVrIntent() {
        if (!resumed || isFinishing() || isDestroyed() || launchPending
                || !hasMicPermission() || pcvrExitGateRunning) return;
        if (!VrCapabilities.isHeadset(this)) {
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (pendingIssueHostPin != null && !pairedPinMatches(pendingIssueHostPin)) {
            // Immediately before the dispatch, the host is still the one
            // the user was asked about.
            cancelForPairingChange();
            return;
        }
        if (usesNativeRuntime() && !consentSatisfied()) {
            connectPending = false;
            hostClient = null;
            pendingHostPin = null;
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
            // Restart permission is passed ONLY when this attempt holds a
            // live positive consent. A warm, normal connection therefore
            // carries allow_restart=false and a zero deadline, so the
            // native side can never restart a healthy VR session.
            boolean allowRestart = usesNativeRuntime() && restartConsentRequired
                    && android.os.SystemClock.elapsedRealtime() < restartConsentUntil;
            i.putExtra("vq_pcvr_codec", options.requestedCodec());
            i.putExtra("vq_pcvr_fallback", options.standardCodec());
            i.putExtra("vq_pcvr_travel", options.travel());
            i.putExtra("vq_pcvr_bitrate_mbps", options.bitrateMbps());
            i.putExtra("vq_pcvr_allow_restart", allowRestart);
            i.putExtra("vq_pcvr_restart_until_ms", allowRestart ? restartConsentUntil : 0L);
            // Correlation identity for the native return contract: a
            // random one-shot nonce plus the PUBLIC pairing certificate
            // pin. No credential is ever placed in an Intent.
            i.putExtra(EXTRA_PCVR_LAUNCH_NONCE, returnGate.beginDispatch(currentPairedPin()));
            i.putExtra(EXTRA_PCVR_HOST_PIN, returnGate.pendingPin());
            connectPending = false;
            hostClient = null;
            pendingHostPin = null;
            // The attempt is spent: the host it was pinned to is not
            // carried into whatever the user does next.
            pendingIssueHostPin = null;
            clearInlinePhase();
            clearInlineError();
            launchPending = true;
            startActivity(i);
            prefs.rememberMode(HubPrefs.MODE_VR);
        } catch (ActivityNotFoundException e) {
            launchPending = false;
            // Nothing can come back through the return contract now.
            returnGate.clearPending();
            Log.w(TAG, "SteamVrActivity not available: " + e.getMessage());
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            launchPending = false;
            returnGate.clearPending();
            Log.w(TAG, "SteamVrActivity dispatch security exception: "
                    + e.getMessage());
            Toast.makeText(this, R.string.hub_steamvr_unsupported,
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The pairing certificate pin the hub holds right now. The probe
     * caches the public pin for the attempt it authenticated, so the
     * common path never touches the keystore on the UI thread; a dispatch
     * that skipped the probe (manual VR) re-reads it here. Never a token.
     */
    private String currentPairedPin() {
        String cached = pendingHostPin;
        if (cached != null) return cached;
        try {
            HostPairing pairing = loadHostPairing();
            return pairing == null ? null : pairing.pin;
        } catch (Exception e) {
            Log.w(TAG, "Could not read the pairing pin: " + e.getMessage());
            return null;
        }
    }

    /**
     * Prove the old immersive process is gone before anything new is
     * dispatched.
     *
     * <p>The poll runs on the connect worker (never the UI thread) and
     * asks {@link ActivityManager#getRunningAppProcesses()} for a
     * snapshot this hub turns into own-UID rows. {@link
     * PcvrReturnGate#liveness} then decides:
     * <ul>
     *   <li>{@link PcvrReturnGate#LIVENESS_GONE} — the process really
     *       is gone; the continuation runs.</li>
     *   <li>{@link PcvrReturnGate#LIVENESS_ALIVE} — still there; keep
     *       polling until the budget runs out.</li>
     *   <li>{@link PcvrReturnGate#LIVENESS_UNKNOWN} — the platform
     *       could not answer (or the name is live under a different
     *       pid). Stop immediately and fail clearly: an unknown state
     *       must never become an automatic launch.</li>
     * </ul>
     *
     * <p><b>The completion is guarded, not trusted.</b> A gate is
     * identified by {@link #pcvrExitGateId} and by the
     * {@code connectGeneration} that was current when it started, and
     * both are re-checked on the UI thread before {@code outcome} runs:
     * a Cancel / Destroy / new attempt bumps the identity, so a poll
     * that was already in flight can neither launch VR nor prompt
     * anything for an attempt the user has abandoned. A completion that
     * has been superseded also never clears
     * {@link #pcvrExitGateRunning}, because by then the flag may
     * belong to a newer gate.
     */
    private void runPcvrExitGate(final int targetPid,
                                 final java.util.function.IntConsumer outcome) {
        final int ownUid = android.os.Process.myUid();
        final String expected = getPackageName() + SteamVrActivity.PROCESS_SUFFIX;
        final int gateId = ++pcvrExitGateId;
        final int gateGeneration = connectGeneration;
        pcvrExitGateRunning = true;
        java.util.concurrent.Future<?> poll = null;
        try {
            poll = connectWorker.submit((Runnable) () -> {
                int liveness = PcvrReturnGate.LIVENESS_UNKNOWN;
                long deadline = android.os.SystemClock.elapsedRealtime() + PCVR_EXIT_GATE_MS;
                while (true) {
                    liveness = PcvrReturnGate.liveness(readPcvrProcesses(), ownUid, expected, targetPid);
                    if (liveness != PcvrReturnGate.LIVENESS_ALIVE) break;
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) break;
                    try {
                        Thread.sleep(PCVR_EXIT_POLL_MS);
                    } catch (InterruptedException e) {
                        // A Cancel / Destroy interrupted the wait: the
                        // answer no longer matters, and the completion
                        // below sees a superseded gate and drops it.
                        Thread.currentThread().interrupt();
                        liveness = PcvrReturnGate.LIVENESS_UNKNOWN;
                        break;
                    }
                }
                final int result = liveness;
                runOnUiThread(() -> {
                    if (gateId != pcvrExitGateId) {
                        // Superseded (Cancel, Destroy, or a newer gate).
                        // Return without touching the running flag: it
                        // may already belong to that newer gate.
                        return;
                    }
                    pcvrExitGateRunning = false;
                    pcvrExitGateFuture = null;
                    if (gateGeneration != connectGeneration
                            || isFinishing() || isDestroyed()) return;
                    // The process fact is settled. If the hub is paused,
                    // park the continuation for the next resume instead
                    // of dropping it (a dropped launch would strand
                    // connectPending) and instead of running it now (an
                    // unresumed dispatch would return without a resume
                    // continuation).
                    pendingGateContinuation = () -> outcome.accept(result);
                    pendingGateContinuationId = gateId;
                    pendingGateContinuationGeneration = gateGeneration;
                    drainPendingGateContinuation();
                });
            });
            pcvrExitGateFuture = poll;
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // The worker is gone (the hub was torn down). Fail closed: no
            // continuation runs, so nothing is dispatched or prompted.
            pcvrExitGateRunning = false;
            pcvrExitGateFuture = null;
            Log.w(TAG, "PCVR exit gate could not start: " + rejected.getMessage());
        }
    }

    /**
     * Run a gate outcome that landed while the hub was paused — once,
     * and only after it has re-proved that the continuation still
     * belongs to this attempt.
     *
     * <p>Every check here is the paused-window equivalent of a guard the
     * synchronous path already makes: the gate identity (a Cancel /
     * Destroy / newer gate bumps it), the {@code connectGeneration}, the
     * activity still being alive and resumed, the device still being a
     * headset, the microphone permission still held, and any restart
     * consent this attempt required still inside its window. A consent
     * that expired while the hub was paused is dropped with an inline
     * error rather than renewed.
     *
     * <p>The continuation is consumed before it runs, so a second resume
     * can never replay it.
     */
    private void drainPendingGateContinuation() {
        if (pendingGateContinuation == null) return;
        if (!resumed || isFinishing() || isDestroyed()) return;
        if (pendingGateContinuationId != pcvrExitGateId
                || pendingGateContinuationGeneration != connectGeneration) {
            // The attempt this outcome belonged to is gone.
            dropPendingGateContinuation();
            return;
        }
        if (!VrCapabilities.isHeadset(this) || !hasMicPermission()) {
            dropPendingGateContinuation();
            cancelHostConnection();
            showInlineError(R.string.hub_error_mic_required);
            return;
        }
        if (usesNativeRuntime() && !consentSatisfied()) {
            dropPendingGateContinuation();
            cancelHostConnection();
            showInlineError(R.string.hub_error_consent_expired);
            return;
        }
        final Runnable continuation = pendingGateContinuation;
        dropPendingGateContinuation();
        continuation.run();
    }

    /** Forget a parked gate outcome. Nothing to run, nothing to replay. */
    private void dropPendingGateContinuation() {
        pendingGateContinuation = null;
        pendingGateContinuationId = -1;
        pendingGateContinuationGeneration = -1;
    }

    /**
     * Fail closed when the old immersive process cannot be proven gone.
     * The user is told plainly, and nothing is launched: an unknown
     * process state must never turn into an automatic VR start.
     */
    private void handlePcvrExitGateFailure(int liveness) {
        connectPending = false;
        hostClient = null;
        pendingHostPin = null;
        // The attempt is over, so the host it was pinned to is no longer
        // this hub's business.
        pendingIssueHostPin = null;
        clearInlinePhase();
        showInlineError(liveness == PcvrReturnGate.LIVENESS_ALIVE
                ? "The previous VR session on this headset is still closing. Leave it, then tap Connect again."
                : "Could not confirm the previous VR session closed. Leave this headset's VR app, then tap Connect again.");
        Log.w(TAG, "PCVR exit gate did not prove the old process gone (liveness="
                + liveness + ")");
    }

    /* ------------------------------------------------------------
     * Native return contract (Java side of the JNI boundary).
     * ------------------------------------------------------------ */

    /**
     * A {@link SteamVrActivity} that reported a native issue comes back
     * with {@code CLEAR_TOP|SINGLE_TOP}, so this hub may be either
     * brought forward ({@code onNewIntent}) or recreated
     * ({@code onCreate}, which also calls {@link #handlePcvrReturn}).
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handlePcvrReturn(intent);
    }

    /**
     * Validate and consume one native return callback.
     *
     * <p>Validation happens BEFORE anything is consumed or shown, and it
     * is entirely view-free so it is safe to run from {@code onCreate}:
     * <ul>
     *   <li>the action must be {@link #ACTION_PCVR_RETURN};</li>
     *   <li>the issue must be one of the two known native values;</li>
     *   <li>the echoed nonce must match the pending expectation exactly
     *       — a missing, forged, stale or already-spent nonce is
     *       rejected;</li>
     *   <li>the echoed pin must match the pin the dispatch carried AND
     *       the pairing the hub holds right now, so a callback produced
     *       against a since-re-paired PC cannot drive anything here.</li>
     * </ul>
     * A rejected callback is inert: no prompt, no error, no launch.
     */
    private void handlePcvrReturn(Intent intent) {
        if (intent == null || isFinishing() || isDestroyed()) return;
        if (!ACTION_PCVR_RETURN.equals(intent.getAction())) return;
        String livePin;
        try {
            HostPairing pairing = loadHostPairing();
            livePin = pairing == null ? null : pairing.pin;
        } catch (Exception e) {
            // An unreadable pairing store is a changed host as far as
            // this callback is concerned.
            livePin = null;
        }
        int verdict = returnGate.accept(
                intent.getStringExtra(EXTRA_PCVR_ISSUE),
                intent.getStringExtra(EXTRA_PCVR_LAUNCH_NONCE),
                intent.getStringExtra(EXTRA_PCVR_HOST_PIN),
                livePin);
        if (verdict == PcvrReturnGate.ACCEPT_RESTART_REQUIRED) {
            acceptPcvrIssue(PcvrReturnGate.ISSUE_RESTART_REQUIRED,
                    intent.getIntExtra(EXTRA_PCVR_OLD_PID, 0), livePin);
            return;
        }
        if (verdict == PcvrReturnGate.ACCEPT_RESTART_FAILED) {
            acceptPcvrIssue(PcvrReturnGate.ISSUE_RESTART_FAILED,
                    intent.getIntExtra(EXTRA_PCVR_OLD_PID, 0), null);
            return;
        }
        Log.i(TAG, "Ignoring PCVR return callback (verdict=" + verdict + ")");
    }

    /**
     * A validated native issue. Nothing is started here: the attempt
     * that produced the callback is abandoned, every trace of positive
     * restart consent is dropped, and the issue waits for
     * {@link #advancePendingPcvrIssue()} — which only runs while the hub
     * is resumed.
     *
     * @param acceptedPin the public pin this callback was validated
     *                    against, retained so the Restart tap is spent on
     *                    that exact host. Only a real mismatch carries
     *                    one, and {@code null} is treated as "no host to
     *                    restart" when the user confirms.
     */
    private void acceptPcvrIssue(String issue, int oldPid, String acceptedPin) {
        if (hostClient != null) {
            try { hostClient.cancel(); } catch (Exception ignored) { }
        }
        hostClient = null;
        pendingHostPin = null;
        connectPending = false;
        pendingContinuation = false;
        // A callback never leaves a restart consent standing.
        restartConsentRequired = false;
        restartConsentUntil = 0;
        pendingPcvrIssue = issue;
        pendingOldPcvrPid = oldPid;
        pendingIssueHostPin = PcvrReturnGate.isPublicPin(acceptedPin) ? acceptedPin : null;
        advancePendingPcvrIssue();
    }

    /**
     * Surface a validated native issue, once, on a resumed hub.
     *
     * <ul>
     *   <li>{@code restart_failed} — a concise Retry / Cancel error. The
     *       native side already tried and failed, so the hub never
     *       restarts anything on its own.</li>
     *   <li>{@code restart_required} — the old Restart VR / Cancel
     *       dialog, but only after the real process gate proves the
     *       reporting {@code :pcvr} process is gone. A pid we cannot
     *       trust fails closed with an explicit message instead of a
     *       prompt, and a host we cannot name fails closed too: the
     *       accepted public pin is what the prompt's Restart tap is
     *       later spent on.</li>
     * </ul>
     */
    private void advancePendingPcvrIssue() {
        final String issue = pendingPcvrIssue;
        if (issue == null) return;
        if (!resumed || isFinishing() || isDestroyed() || pcvrExitGateRunning) return;
        if (pendingGateContinuation != null) {
            // A gate already proved a process fact for this issue and is
            // waiting for the next resume. Starting a second gate here
            // would race that parked continuation.
            return;
        }
        if (PcvrReturnGate.ISSUE_RESTART_FAILED.equals(issue)) {
            pendingPcvrIssue = null;
            showPcvrRestartFailed();
            return;
        }
        if (pendingOldPcvrPid <= 0) {
            // Without the reporting pid there is nothing to prove, and a
            // prompt the hub cannot verify would be a guess.
            pendingPcvrIssue = null;
            clearInlinePhase();
            showInlineError("SteamVR needs a restart, but this headset could not confirm its VR session closed. Leave this headset's VR app, then tap Connect again.");
            Log.w(TAG, "PCVR restart_required without a usable pid; refusing to prompt");
            return;
        }
        // No prompt and no dispatch until the gate answers; the wait is a
        // process fact, not a UX beat, so no phase line is faked here.
        runPcvrExitGate(pendingOldPcvrPid, liveness -> {
            if (liveness != PcvrReturnGate.LIVENESS_GONE) {
                pendingPcvrIssue = null;
                handlePcvrExitGateFailure(liveness);
                return;
            }
            pendingOldPcvrPid = 0;
            pendingPcvrIssue = null;
            // The prompt runs as a fresh attempt so its generation guard
            // covers a Cancel / new tap exactly like any other dialog.
            connectPending = true;
            ++connectGeneration;
            showRestartVrConsent(connectGeneration);
        });
    }

    /** Concise Retry / Cancel error for a native restart that failed.
     *  Consent is already cleared by {@link #acceptPcvrIssue}: Retry is
     *  a fresh connect (fresh probe, fresh consent), never a replay of
     *  the old one. */
    private void showPcvrRestartFailed() {
        if (isFinishing() || isDestroyed()) return;
        clearInlinePhase();
        clearInlineError();
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("VR restart failed")
                .setMessage("SteamVR could not be restarted on your PC. Check the PC, then retry.")
                .setPositiveButton("Retry", (d, w) -> retryConnect())
                .setNegativeButton("Cancel", (d, w) -> cancelHostConnection());
        if (restartVrDialog != null) {
            try { restartVrDialog.dismiss(); } catch (Exception ignored) { }
        }
        android.app.AlertDialog dialog = b.show();
        restartVrDialog = dialog;
        trackDialog(dialog);
    }

    /**
     * Process snapshot the exit gate reads, reduced to own-UID rows.
     * Production always uses
     * {@link ActivityManager#getRunningAppProcesses()}; {@code null}
     * means "the platform could not answer", which the gate treats as
     * unknown (fail closed). Tests override this to drive the gate
     * deterministically.
     */
    protected java.util.List<PcvrReturnGate.ProcRow> readPcvrProcesses() {
        java.util.List<PcvrReturnGate.ProcRow> rows = new java.util.ArrayList<>();
        try {
            ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (manager == null) return null;
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes == null) return null;
            for (ActivityManager.RunningAppProcessInfo p : processes) {
                if (p == null) continue;
                rows.add(new PcvrReturnGate.ProcRow(p.uid, p.processName, p.pid));
            }
            return rows;
        } catch (Throwable t) {
            return null;
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
        renderLastMode(headset);
        Button setup = findViewById(R.id.hub_btn_setup);
        setup.setText(headset && paired ? getString(R.string.hub_btn_change_pc)
                : getString(R.string.hub_btn_setup));
        setup.setVisibility(headset && !paired ? View.GONE : View.VISIBLE);
        connect.setEnabled(!connectPending && !launchPending);
        connect.setText(connectPending ? getString(R.string.hub_connecting) : getString(!headset
                ? R.string.hub_btn_play : !paired ? R.string.hub_btn_setup_pc : R.string.hub_btn_connect));
        findViewById(R.id.hub_btn_cancel_connection).setVisibility(connectPending ? View.VISIBLE : View.GONE);
        TextView savedPc = findViewById(R.id.hub_card_pc_status);
        if (headset && paired) {
            try {
                HostPairing saved = loadHostPairing();
                savedPc.setText(saved == null ? getString(R.string.hub_card_unpaired)
                        : getString(R.string.hub_card_paired, saved.address));
            } catch (Exception ignored) { savedPc.setText(R.string.hub_card_unpaired); }
        } else savedPc.setText(headset ? getString(R.string.hub_card_unpaired) : getString(R.string.hub_card_pc_choose));
        savedPc.setCompoundDrawablesRelativeWithIntrinsicBounds(headset && paired
                ? R.drawable.hub_dot_ok : R.drawable.hub_dot_wait, 0, 0, 0);
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
    private void openUpdates() {
        if (launchPending || requestPending || connectPending) return;
        try {
            launchPending = true;
            startActivity(new Intent(this, com.vibertemis.quest.update.UpdatesActivity.class)
                    .putExtra(com.vibertemis.quest.update.UpdatesActivity.EXTRA_REQUEST_UPDATE, true));
        } catch (ActivityNotFoundException | SecurityException e) {
            launchPending = false;
            Toast.makeText(this, "Updates screen unavailable", Toast.LENGTH_LONG).show();
        }
    }

    /** Installed versionName in the header chip; hidden if unreadable. */
    private void renderVersionChip() {
        TextView chip = findViewById(R.id.hub_version);
        if (chip == null) return;
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (name == null || name.isEmpty()) { chip.setVisibility(View.GONE); return; }
            int suffix = name.indexOf('-');
            chip.setText(suffix > 0 ? name.substring(0, suffix) : name);
            chip.setVisibility(View.VISIBLE);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            chip.setVisibility(View.GONE);
        }
    }

    /** The version the banner would advertise for this snapshot, or null. */
    private static String bannerVersion(com.vibertemis.quest.update.UpdateRepository.Snapshot snap) {
        if (snap == null || snap.checking) return null;
        if (snap.hasDownloaded()) return snap.downloaded.version;
        if (snap.hasAvailable()) return snap.available.version;
        return null;
    }

    /**
     * Banner across the top of the hub whenever the shared snapshot has
     * an available or already-downloaded update, so a new version is
     * never only a footnote. "Later" hides it for that version until the
     * process restarts; a newer version shows it again.
     */
    private void renderUpdateBanner(com.vibertemis.quest.update.UpdateRepository.Snapshot snap) {
        View banner = findViewById(R.id.hub_update_banner);
        if (banner == null) return;
        String version = bannerVersion(snap);
        if (version == null || version.equals(dismissedUpdateVersion)) {
            banner.setVisibility(View.GONE);
            return;
        }
        boolean ready = snap.hasDownloaded();
        ((TextView) findViewById(R.id.hub_update_banner_title)).setText(getString(ready
                ? R.string.hub_update_banner_ready : R.string.hub_update_banner_available, version));
        ((TextView) findViewById(R.id.hub_update_banner_body)).setText(ready
                ? R.string.hub_update_banner_body_ready : R.string.hub_update_banner_body_available);
        banner.setVisibility(View.VISIBLE);
    }

    /** "Last used" tag and accent outline on the mode the user last started. */
    private void renderLastMode(boolean headset) {
        String mode = headset ? prefs.getLastMode() : null;
        boolean screen = HubPrefs.MODE_SCREEN.equals(mode);
        boolean vr = HubPrefs.MODE_VR.equals(mode);
        findViewById(R.id.hub_tag_screen).setVisibility(screen ? View.VISIBLE : View.GONE);
        findViewById(R.id.hub_tag_vr).setVisibility(vr ? View.VISIBLE : View.GONE);
        findViewById(R.id.hub_card_screen).setBackgroundResource(screen
                ? R.drawable.hub_card_screen_bg : R.drawable.hub_card_bg);
        findViewById(R.id.hub_card_connection).setBackgroundResource(vr
                ? R.drawable.hub_card_vr_bg : R.drawable.hub_card_bg);
    }

    private void renderUpdatesBadge(com.vibertemis.quest.update.UpdateRepository.Snapshot snap) {
        renderUpdateBanner(snap);
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