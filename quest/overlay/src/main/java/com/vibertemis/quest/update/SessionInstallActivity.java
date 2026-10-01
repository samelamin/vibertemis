package com.vibertemis.quest.update;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.limelight.R;

/**
 * The private 2D bridge from a verified update to the Android installer.
 * {@link UpdatesActivity} hands over the release-bound verified copy in an
 * explicit Intent carrying the pinned target, and there is no second Install
 * or Continue tap. Rotation keeps the process-scoped session, while process
 * death reports {@code UNCONFIRMED} instead of preparing or committing again.
 */
public final class SessionInstallActivity extends Activity {

    private static final String ACTION = "com.vibertemis.quest.update.action.SESSION_INSTALL";
    private static final String EXTRA_APK = "com.vibertemis.quest.update.extra.APK_PATH";
    private static final String EXTRA_PACKAGE = "com.vibertemis.quest.update.extra.PACKAGE";
    private static final String EXTRA_VERSION = "com.vibertemis.quest.update.extra.VERSION_CODE";
    private static final String EXTRA_BYTES = "com.vibertemis.quest.update.extra.BYTES";
    private static final String EXTRA_SHA256 = "com.vibertemis.quest.update.extra.SHA256";
    private static final String EXTRA_MESSAGE = "com.vibertemis.quest.update.extra.MESSAGE";
    private static final String EXTRA_STATUS = "com.vibertemis.quest.update.extra.STATUS";
    private static final String KEY_INITIALIZED = "initialized";
    private static final String KEY_SESSION_ID = "sessionId";
    private static final String KEY_LAUNCHED = "confirmationLaunched";
    private static final int CONFIRM_REQUEST = 0x5A11;
    /** How long a returning consent dialog may still deliver its status. */
    private static final long SETTLE_MS = 2000L;
    /** How long the Android install may keep running after our own callback
     *  returns, before the attempt resolves as a known unknown. */
    private static final long INSTALL_RESULT_TIMEOUT_MS = 120000L;
    private static final String UNKNOWN = "Android has not confirmed this update; its outcome is unknown";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final InstallSessionState.Listener listener = this::onStageChanged;
    private TextView status;
    private Button cancel;
    private volatile boolean destroyed, resumed;
    private boolean attached, initialized, settled, confirmationLaunched;
    private int sessionId = -1;
    private String pendingNotice;
    private InstallSessionState.Target wanted;
    private Runnable pendingSettle, pendingContinuation;
    /** True while the armed settle is the wait that runs before Android's own
     *  consent screen, which the CONFIRMATION stage cancels. */
    private boolean preConfirmationWait;

    /** The one explicit hand-off {@link UpdatesActivity} uses: the pinned
     *  release travels with the Intent, so nothing here reads the cache. */
    static Intent newIntent(Context context, String apkPath, String packageName,
            long versionCode, long bytes, String sha256) {
        return new Intent(context, SessionInstallActivity.class).setAction(ACTION)
                .putExtra(EXTRA_APK, apkPath).putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_VERSION, versionCode).putExtra(EXTRA_BYTES, bytes)
                .putExtra(EXTRA_SHA256, sha256);
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        buildUi();
        InstallSessionState state = InstallSessionState.get();
        wanted = readTarget(getIntent());
        if (wanted == null) { finish(false, "This update request is not valid", 0); return; }
        boolean recreate = saved != null && saved.getBoolean(KEY_INITIALIZED);
        if (recreate) {
            sessionId = saved.getInt(KEY_SESSION_ID, -1);
            // A dialog launched before the rotation is still owned by Android
            // and its result comes back to this recreated instance.
            confirmationLaunched = saved.getBoolean(KEY_LAUNCHED);
            if (state.stage() == null) {
                // Our process-scoped state is gone while Android may or may not
                // still hold the session: unknown, never a reason to try again.
                pendingNotice = UNKNOWN;
                if (attach(state)) state.restoreAfterRecreation(getApplicationContext(), sessionId);
                armSettle(SETTLE_MS);
            } else {
                // A live or settled stage is this request's own continuation;
                // watch it and never replay prepare.
                if (!attach(state)) { finish(false, "Another update is already being installed", 0); return; }
                render(state);
                // Committed but the dialog has not been shown yet: keep a
                // bounded wait running so this attempt cannot hang.
                if (state.stage() == InstallSessionState.Stage.COMMITTED
                        && !confirmationLaunched) armPreConfirmationWait();
            }
            // Even the reported-unknown path stays initialized, so a further
            // recreation resumes this attempt instead of preparing again.
            initialized = true;
            return;
        }
        if (isActive(state)) {
            // Another attempt is live: watch it if it is this release, and
            // never stage a second copy behind it.
            if (!attach(state)) { finish(false, "Another update is already being installed", 0); return; }
            initialized = true;
            render(state);
            return;
        }
        // Nothing is running: start this request before listening, so a
        // terminal callback from an earlier attempt cannot settle this one.
        state.prepare(getApplicationContext(), wanted);
        if (!attach(state)) { finish(false, "Another update is already being installed", 0); return; }
        initialized = true;
        render(state);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean(KEY_INITIALIZED, initialized);
        out.putInt(KEY_SESSION_ID, InstallSessionState.get().sessionId());
        out.putBoolean(KEY_LAUNCHED, confirmationLaunched);
    }

    /** The pinned request, checked before any work starts. */
    private InstallSessionState.Target readTarget(Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return null;
        String apkPath = intent.getStringExtra(EXTRA_APK);
        String packageName = intent.getStringExtra(EXTRA_PACKAGE);
        String sha256 = InstallSessionState.normalizeHex(intent.getStringExtra(EXTRA_SHA256));
        long versionCode = intent.getLongExtra(EXTRA_VERSION, -1L), bytes = intent.getLongExtra(EXTRA_BYTES, -1L);
        if (apkPath == null || apkPath.isEmpty() || !getPackageName().equals(packageName)
                || versionCode <= 0 || bytes <= 0 || bytes > InstallSessionState.MAX_APK_BYTES
                || sha256 == null || !sha256.matches("[a-f0-9]{64}")) return null;
        return new InstallSessionState.Target(apkPath, packageName, versionCode, bytes, sha256);
    }

    /** A stage that still belongs to a running attempt. */
    private static boolean isActive(InstallSessionState state) {
        InstallSessionState.Stage stage = state.stage();
        return stage != null && !InstallSessionState.isTerminal(stage);
    }

    /** An attempt in flight for another release is not ours to watch. */
    private boolean attach(InstallSessionState state) {
        if (isActive(state) && !sameRelease(state.target(), wanted)) return false;
        if (!attached) { state.attach(listener); attached = true; }
        return true;
    }

    /** Release identity, not the file copy: the coordinator re-hashes it. */
    private static boolean sameRelease(InstallSessionState.Target pinned,
            InstallSessionState.Target wanted) {
        return pinned != null && wanted != null && pinned.packageName != null
                && pinned.packageName.equals(wanted.packageName)
                && pinned.versionCode == wanted.versionCode && pinned.bytes == wanted.bytes
                && pinned.sha256 != null && pinned.sha256.equals(wanted.sha256);
    }

    private boolean ownsRequest(InstallSessionState state) {
        return state.stage() == null || sameRelease(state.target(), wanted);
    }

    private void onStageChanged(InstallSessionState.Stage stage) {
        if (destroyed || status == null) return;
        InstallSessionState state = InstallSessionState.get();
        render(state);
        // A terminal status is the outcome itself, so it settles at once and
        // may land while this Activity is paused: finishing then is safe, and
        // nothing here can start another install.
        if (InstallSessionState.isTerminal(stage)) armSettle(0L);
        else if (stage == InstallSessionState.Stage.CONFIRMATION) {
            // The user now decides inside Android's dialog, on no clock of ours.
            cancelPreConfirmationWait();
            launchConfirmation(state);
        }
        // Android may still be installing while the OS callback is outstanding,
        // so a commit already starts a bounded wait instead of waiting forever.
        else if (stage == InstallSessionState.Stage.COMMITTED) armPreConfirmationWait();
        // Verification usually finishes after the first resume and focus, and
        // no further event would arrive: the ready stage must drive the commit.
        else if (stage == InstallSessionState.Stage.PREPARED) postContinuation();
    }

    /** The only work this Activity starts, and never from a worker. */
    private void runContinuation() {
        if (destroyed || settled || !resumed || isFinishing() || isDestroyed()) return;
        InstallSessionState state = InstallSessionState.get();
        if (!ownsRequest(state)) return;
        InstallSessionState.Stage stage = state.stage();
        if (stage == InstallSessionState.Stage.CONFIRMATION) { launchConfirmation(state); return; }
        if (stage != InstallSessionState.Stage.PREPARED || state.sessionId() < 0) return;
        // No focus yet is not a refusal: another focus event will arrive.
        if (!hasWindowFocus()) return;
        // The install permission and the VR gate are the coordinator's own
        // checks; a closed gate changes nothing until the user acts.
        if (state.commitWhenForeground(this)) return;
        String gate = state.gateReason();
        // Without a gate reason the commit simply did not happen yet, so the
        // stage message would be a stale "Ready to install" here.
        if (gate == null || gate.isEmpty()) return;
        finish(false, plain(gate), 0);
    }

    /** Start Android's own consent screen for result, exactly once. */
    private void launchConfirmation(InstallSessionState state) {
        if (destroyed || settled || !resumed || isFinishing() || isDestroyed()
                || confirmationLaunched || !hasWindowFocus()) return;
        Intent pending = state.takeConfirmationIntent();
        if (pending == null) return;
        confirmationLaunched = true;
        try {
            startActivityForResult(pending, CONFIRM_REQUEST); render(state);
        } catch (ActivityNotFoundException e) {
            finish(false, UNKNOWN, 0);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CONFIRM_REQUEST || destroyed || settled) return;
        // The result code is not an outcome in either direction: the APK may
        // still be installing, so wait out a bounded, generous window for the
        // install status to arrive instead of giving up after a moment. A
        // terminal status in that window resolves at once, and nothing here
        // ever claims an install from RESULT_OK.
        armSettle(INSTALL_RESULT_TIMEOUT_MS);
    }

    /** The wait that covers Android still working with no dialog of ours up. */
    private void armPreConfirmationWait() { armSettle(INSTALL_RESULT_TIMEOUT_MS, true); }

    /** One settle at a time, so a later stage cannot push the deadline out. */
    private void armSettle(long delayMs) { armSettle(delayMs, false); }

    private void armSettle(long delayMs, boolean preConfirmation) {
        if (destroyed || settled) return;
        if (pendingSettle != null) {
            // Only a terminal outcome may take a waiting slot: everything else
            // leaves the running deadline alone, so no event can extend it.
            if (delayMs != 0L) return;
            main.removeCallbacks(pendingSettle);
            pendingSettle = null;
        }
        pendingSettle = this::deliverOutcome;
        preConfirmationWait = preConfirmation;
        main.postDelayed(pendingSettle, delayMs);
    }

    /** Android's consent screen sets its own pace, so drop the deadline that
     *  was only covering the wait for it to appear. */
    private void cancelPreConfirmationWait() {
        if (pendingSettle == null || !preConfirmationWait) return;
        main.removeCallbacks(pendingSettle);
        pendingSettle = null;
        preConfirmationWait = false;
    }

    private void deliverOutcome() {
        pendingSettle = null; preConfirmationWait = false;
        if (destroyed || settled) return;
        InstallSessionState state = InstallSessionState.get();
        if (!ownsRequest(state)) { finish(false, UNKNOWN, 0); return; }
        // The installed version is the only proof; SUCCEEDED means it was seen.
        boolean proven = state.stage() == InstallSessionState.Stage.SUCCEEDED
                || state.installedTargetReached(getApplicationContext());
        InstallSessionState.Stage stage = state.stage();
        if (!proven && (stage == InstallSessionState.Stage.COMMITTED
                || stage == InstallSessionState.Stage.CONFIRMATION)) {
            // An unproven confirmation must not stay active: that would block
            // every later request. Resolve it to a known unknown, which the
            // owner may then retry explicitly. The Android session keeps
            // running and a late success stays provable by version.
            pendingNotice = UNKNOWN;
            state.reconcileAfterConfirmation(getApplicationContext(), wanted, state.signerProof());
            proven = state.stage() == InstallSessionState.Stage.SUCCEEDED;
        }
        finish(proven, state.stage() == null ? null : state.message(), state.statusCode());
    }

    /** One terminal result, ever, with a bounded plain reason. */
    private void finish(boolean proven, String message, int status) {
        if (settled || isFinishing()) return;
        settled = true;
        String text = plain(message == null || message.isEmpty() ? pendingNotice : message);
        if (text.isEmpty()) text = proven ? "Update installed" : "The update could not be installed";
        setResult(proven ? RESULT_OK : RESULT_CANCELED,
                new Intent().putExtra(EXTRA_MESSAGE, text).putExtra(EXTRA_STATUS, status));
        finish();
    }

    /** Back and pre-commit Cancel: drop this attempt while it is ours. */
    private void onBackOrCancel() {
        if (settled || isFinishing()) return;
        InstallSessionState state = InstallSessionState.get();
        if (ownsRequest(state) && state.cancelBeforeCommit()) return;
        if (ownsRequest(state) && (state.stage() == InstallSessionState.Stage.COMMITTED
                || state.stage() == InstallSessionState.Stage.CONFIRMATION)) {
            // Back after commit: the Android session keeps running and is never
            // abandoned, but this attempt stops being active, so the reason is
            // a known unknown unless the version now proves the install.
            pendingNotice = UNKNOWN;
            state.reconcileAfterConfirmation(getApplicationContext(), wanted, state.signerProof());
            boolean proven = state.stage() == InstallSessionState.Stage.SUCCEEDED;
            finish(proven, state.stage() == null ? null : state.message(), 0);
            return;
        }
        pendingNotice = UNKNOWN;
        finish(false, null, 0);
    }

    @Override public void onBackPressed() { onBackOrCancel(); }

    @Override protected void onDestroy() {
        destroyed = true; resumed = false;
        InstallSessionState state = InstallSessionState.get();
        if (attached) { state.detach(listener); attached = false; }
        if (pendingSettle != null) { main.removeCallbacks(pendingSettle); pendingSettle = null; preConfirmationWait = false; }
        if (pendingContinuation != null) { main.removeCallbacks(pendingContinuation); pendingContinuation = null; }
        // A configuration change keeps the session; leaving drops only what
        // Android was never asked to install.
        if (isFinishing() && !isChangingConfigurations() && ownsRequest(state)) {
            state.cancelBeforeCommit();
        }
        super.onDestroy();
    }

    @Override protected void onResume() { super.onResume(); resumed = true; postContinuation(); }

    /** Background can never claim the foreground, not even briefly. */
    @Override protected void onPause() { resumed = false; super.onPause(); }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) postContinuation();
    }

    /** At most one queued continuation, so nothing runs twice. */
    private void postContinuation() {
        if (pendingContinuation != null) return;
        pendingContinuation = () -> { pendingContinuation = null; runContinuation(); };
        main.post(pendingContinuation);
    }

    private void render(InstallSessionState state) {
        if (status == null) return;
        InstallSessionState.Stage stage = state.stage();
        String message = stage == null ? null : state.message();
        status.setText(plain(message == null || message.isEmpty() ? "Preparing the update" : message));
        // Cancel is ours only before commit.
        boolean ours = (stage == null || !InstallSessionState.isTerminal(stage))
                && stage != InstallSessionState.Stage.COMMITTED
                && stage != InstallSessionState.Stage.CONFIRMATION;
        cancel.setVisibility(ours ? View.VISIBLE : View.GONE); cancel.setEnabled(ours);
    }

    private void buildUi() {
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        card.setBackgroundColor(getResources().getColor(R.color.upd_panel_card));
        status = new TextView(this);
        status.setTextSize(20);
        status.setTextColor(getResources().getColor(R.color.upd_panel_fg));
        card.addView(status, row(Math.round(16 * density)));
        cancel = new Button(this);
        cancel.setText(R.string.upd_action_cancel);
        cancel.setAllCaps(false);
        cancel.setStateListAnimator(null);
        cancel.setMinHeight(Math.round(56 * density));
        cancel.setTextSize(16);
        cancel.setTextColor(getResources().getColor(R.color.upd_panel_fg));
        cancel.setBackgroundResource(R.drawable.upd_btn_secondary);
        card.addView(cancel, row(Math.round(16 * density)));
        cancel.setOnClickListener(v -> onBackOrCancel());
        setContentView(card);
        render(InstallSessionState.get());
    }

    private LinearLayout.LayoutParams row(int topMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = topMargin; return p;
    }

    /** What a finished session reported: its own reason, else the mapped
     *  line for the install status behind it, else null. */
    static String reasonFor(Intent data) {
        if (data == null) return null;
        String notice = plain(data.getStringExtra(EXTRA_MESSAGE));
        if (!notice.isEmpty()) return notice;
        int status = statusFor(data);
        return status > 0 ? InstallSessionState.messageForStatus(status) : null;
    }

    /** The install status behind that reason, or -1 when there is none. */
    static int statusFor(Intent data) { return data == null ? -1 : data.getIntExtra(EXTRA_STATUS, -1); }

    /** One bounded line of plain text: no control characters, no length. */
    private static String plain(String raw) {
        if (raw == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length() && out.length() < 160; i++) out.append(raw.charAt(i) < 0x20 ? ' ' : raw.charAt(i));
        return out.toString().trim();
    }
}
