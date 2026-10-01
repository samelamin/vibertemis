package com.vibertemis.quest.update;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.limelight.R;
import java.io.*;
import java.security.MessageDigest;
import java.util.List;

/**
 * Idle 2D update flow with a single update action.
 *
 * <p>One primary button carries a release all the way to the Android
 * installer: the tap pins an immutable {@link Attempt} (release,
 * versionCode, APK digest, signed metadata bytes), downloads the APK
 * when the verified cache is not already an exact match, verifies it,
 * and hands it to a private {@link SessionInstallActivity}, which owns
 * the Android install session from there. There is no second in-app
 * Install tap and Android's own permission / install consent prompts are
 * untouched.
 *
 * <p>When nothing is advertised yet, the same single tap owns the
 * metadata check instead of stopping at a "nothing to do" screen: it
 * forces or joins one check, and whatever that exact check produced is
 * what gets pinned and installed, still without a second tap. A check
 * that finds nothing renders the ordinary up-to-date notice, and a
 * check that fails is an ordinary Retryable failure.
 *
 * <p>State ownership:
 * <ul>
 *   <li>The UI thread is the sole mutator of {@link Stage}, the pinned
 *       attempt, the hand-off record and the terminal-error fields.
 *       Workers only read them (they are safely published) and report
 *       back through {@link #publishToUi}.</li>
 *   <li>A worker that finishes after its attempt was cancelled or
 *       superseded reports failure without touching the repository, so
 *       a late transport can neither overwrite nor purge the cache.</li>
 *   <li>Downloading mutates one shared staging file, so the
 *       file-mutating part of a download is serialized process-wide
 *       across Activity instances, and the bytes are bound to a
 *       release-specific copy before that boundary is released.</li>
 *   <li>Only that release-bound copy is ever verified or handed to
 *       Android, so a later attempt cannot disturb an update that is
 *       already pinned.</li>
 * </ul>
 *
 * <p>Trust is unchanged: the pinned digest gates every byte we keep,
 * the archive must match this package / versionCode, the signer must
 * equal the installed signer, and the release-bound copy is handed over
 * as a file path that the session re-verifies from this app's own update
 * cache and commits through a {@code PackageInstaller} session. A cached
 * APK that fails an integrity check is purged so the next attempt
 * downloads fresh bytes; a verified cache survives Cancel, a denial, and
 * any failure that is not evidence of corruption (a live VR session, for
 * instance).
 */
public final class UpdatesActivity extends Activity {

    /**
     * Intent flag the launch hub sets when the user asks for an update
     * rather than merely for this screen. It is consumed exactly once,
     * so neither a configuration change nor a later resume can replay
     * an explicit request that has already been acted on (or that the
     * user cancelled).
     */
    public static final String EXTRA_REQUEST_UPDATE = "user_request_update";

    /**
     * Label of the branch where one tap checks and, when the check
     * finds a release, installs it without a second tap. The existing
     * "Check" string resource still names the metadata-only
     * background check, and resources are read-only here, so the
     * combined action names itself inline instead.
     */
    private static final String UPDATE_LABEL = "Update";

    /** How long an explicit tap waits for the check it owns before
     *  calling the wait a failure the user can Retry. */
    private static final long EXPLICIT_CHECK_TIMEOUT_MS = 120_000L;

    /**
     * Request codes must fit the lower 16 bits Android accepts, and
     * must stay clear of the range fragments reserve, so each launch
     * takes the next code in the process.
     */
    private static final int FIRST_REQUEST_CODE = 0x5100;
    private static final int LAST_REQUEST_CODE = 0xFFFF;
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_REQUEST_CODE =
            new java.util.concurrent.atomic.AtomicInteger(FIRST_REQUEST_CODE);

    /** Allocate a fresh request code, or fail closed when the 16-bit
     *  space is exhausted rather than reissuing a live code. */
    private static int allocateRequestCode() {
        int code = NEXT_REQUEST_CODE.getAndIncrement();
        if (code > LAST_REQUEST_CODE) {
            NEXT_REQUEST_CODE.set(FIRST_REQUEST_CODE);
            return -1;
        }
        return code;
    }

    /** The action the single primary button is bound to. The label and
     *  the click dispatch are both derived from this enum, so they
     *  cannot diverge. */
    enum PrimaryAction {
        /** An operation is running, VR is live, or the update service
         *  is unavailable: the button is disabled. */
        NONE,
        /** Nothing is advertised and nothing is cached: the one tap
         *  owns a forced metadata check and installs whatever that
         *  check finds, so the user never needs a second tap. */
        CHECK,
        /** A newer release is advertised or its verified APK is cached:
         *  one tap downloads (if needed), verifies, and opens the
         *  installer. */
        UPDATE,
        /** The last attempt failed: re-run the operation that failed
         *  rather than checking again first. */
        RETRY,
        /** Android owns the installer and has not told us whether the
         *  install completed. Re-run the same pinned release. */
        RETRY_UNCONFIRMED
    }

    /** The stage of the running attempt. Owned by the UI thread;
     *  {@link #VERIFYING} covers "verifying" and "verified, hand-off
     *  pending" so a completion that lands while paused can be resumed
     *  from the very stage it left. {@link #INSTALLER_UNCONFIRMED} is a
     *  resting state, not work in flight: Android owns the installer and
     *  nothing here can say whether it succeeded. */
    private enum Stage {
        IDLE, CHECKING, DOWNLOADING, VERIFYING, INSTALLER, AWAITING_SYSTEM, INSTALLER_UNCONFIRMED
    }

    /**
     * An immutable, pinned update target. Everything that decides what
     * gets installed is captured at click time so a concurrent
     * metadata refresh cannot change what this attempt installs.
     *
     * <p>Immutable fields are published safely by the final-field
     * guarantee, so a worker may read a pinned attempt it was handed
     * without further synchronisation. Cancellation lives on the
     * attempt itself rather than in a per-Activity flag, so a newer
     * attempt can never clear the cancellation of an older one.
     */
    static final class Attempt {
        final UpdateManifest manifest;
        final byte[] manifestBytes;
        final byte[] signatureBytes;
        /** The release-bound copy of the APK: verified here and handed
         *  to Android. It is never the shared download staging file. */
        final File cachedApk;
        /** Monotonic id; a newer attempt (or a cancel) supersedes it. */
        final int generation;
        private final java.util.concurrent.atomic.AtomicBoolean cancelled =
                new java.util.concurrent.atomic.AtomicBoolean();

        Attempt(UpdateManifest manifest, byte[] manifestBytes, byte[] signatureBytes,
                File cachedApk, int generation) {
            this.manifest = manifest;
            this.manifestBytes = manifestBytes == null ? null : manifestBytes.clone();
            this.signatureBytes = signatureBytes == null ? null : signatureBytes.clone();
            this.cachedApk = cachedApk;
            this.generation = generation;
        }

        String version() { return manifest.version; }

        /** The APK only counts when it is the pinned release. */
        boolean hasCachedApk() { return cachedApk != null && cachedApk.isFile(); }

        boolean isCancelled() { return cancelled.get(); }

        void cancel() { cancelled.set(true); }

        Attempt withApk(File verified) {
            return new Attempt(manifest, manifestBytes, signatureBytes, verified, generation);
        }

        boolean sameTargetAs(Attempt other) {
            return other != null
                    && generation == other.generation
                    && manifest.versionCode == other.manifest.versionCode
                    && manifest.sha256.equals(other.manifest.sha256);
        }
    }

    /**
     * One explicit Update tap that owns a metadata check: the exact
     * flight it requested or joined, and the generation that owns the
     * tap.
     *
     * <p>Ownership is the only authority to pin a target out of a
     * completed check. A tap is a new object, so a second tap, a Cancel
     * and a Destroy all retire the first one simply by ceasing to be
     * {@link UpdatesActivity#ownedCheck}: the flight itself is never
     * cancelled (it is shared app state), it just stops being able to
     * install anything.
     */
    private static final class ExplicitCheck {
        final UpdateRepository.InFlight flight;
        final int generation;

        ExplicitCheck(UpdateRepository.InFlight flight, int generation) {
            this.flight = flight;
            this.generation = generation;
        }
    }

    /**
     * The single outstanding hand-off to the OS. It records the exact
     * request code the launch used, so a result is only ever accepted
     * for the launch it answers: a result that arrives for an earlier
     * request cannot be confused with the live one, and therefore
     * cannot authorise, or invalidate, whatever is running now.
     *
     * <p>A result code is only ever recorded after that exact match, and
     * its presence is what separates "Android told us this did not
     * happen" from "Android told us nothing at all".
     *
     * <p>While only the install-permission dialog is up nothing has been
     * launched, so there is no request code to match and a permission
     * result cannot be accepted at all.
     */
    private static final class SystemHandoff {
        /** Waiting on the install-permission dialog; nothing launched. */
        static final int AWAITING_PERMISSION = 0;
        /** Waiting on the "allow from this source" Settings screen. */
        static final int AWAITING_SETTINGS = 1;
        /** The APK is with the OS installer. */
        static final int AWAITING_INSTALLER = 2;
        /** No request is in flight. */
        static final int NO_REQUEST = 0;

        final int phase;
        final int requestCode;
        final int generation;
        final Attempt attempt;
        /** Set only by a result carrying this record's own request code. */
        boolean resultReceived;
        int resultCode;
        /** What the install session reported about its own outcome, when it
         *  reported anything usable; a mapped install status, or null. */
        String resultNotice;
        int resultStatus = -1;

        SystemHandoff(int phase, int requestCode, Attempt attempt) {
            this.phase = phase;
            this.requestCode = requestCode;
            this.generation = attempt == null ? -1 : attempt.generation;
            this.attempt = attempt;
        }

        /** True while this record still describes the live attempt. */
        boolean describes(Attempt live) {
            return live != null
                    && attempt != null
                    && generation == live.generation
                    && attempt.sameTargetAs(live);
        }

        /** Record a result that matched this exact launch. The code alone
         *  never decides anything: a result code is read here, only ever
         *  as the reason a version still has not advanced. */
        void recordResult(int code, Intent data) {
            this.resultReceived = true;
            this.resultCode = code;
            this.resultNotice = SessionInstallActivity.reasonFor(data);
            this.resultStatus = SessionInstallActivity.statusFor(data);
        }
    }

    private TextView status;
    private TextView installedVersionView;
    private Button primary;
    private Button cancel;
    private Button back;

    private volatile UpdateTransport transport;
    private volatile UpdateRepository.Snapshot current;
    private UpdateRepository repository;

    /** UI-owned; read by workers through the volatile publication. */
    private volatile Stage stage = Stage.IDLE;
    /** UI-owned; read by workers through the volatile publication. */
    private volatile Attempt attempt;
    private int generationCounter;
    private String actionError;
    private String lastFailedKind;
    private String resultNotice;
    private boolean observerRegistered;
    private volatile boolean destroyed;
    private volatile boolean resumed;

    /** True from the moment the APK is handed to the OS installer until
     *  the activity comes back. */
    private volatile boolean handedToSystem;
    /** A permission answer is outstanding: the dialog is up or the
     *  Settings screen owns the screen. */
    private volatile boolean awaitingPermission;
    /** The one outstanding hand-off, or null. UI-owned. */
    private SystemHandoff handoff;
    /** The install-permission dialog, while it is on screen. */
    private AlertDialog permissionDialog;
    /** A verification that completed while paused queues one
     *  continuation; this holds it until onResume. UI-owned. */
    private Attempt queuedContinuation;
    private boolean continuationQueued;
    /** True when the queued continuation has not run yet and must
     *  still pin-and-install, rather than hand the already-verified
     *  bytes to the installer. */
    private boolean continuationIsUpdate;
    /** The check an explicit tap owns, or null. While it is set the
     *  flow is a tap's, not a background check's, so Cancel stays
     *  available before any attempt exists. */
    private ExplicitCheck ownedCheck;
    /** A launch that asked for an update; taken on the first resume
     *  and never replayed. */
    private boolean pendingExplicitUpdate;

    /** The action the primary button is currently bound to. */
    private volatile PrimaryAction boundPrimaryAction = PrimaryAction.NONE;

    private final UpdateRepository.Observer observer = this::onRepositoryUpdate;

    /**
     * The file-mutating part of a download is process-wide state (one
     * shared staging APK), so it is serialized across Activity
     * instances: a destroyed or cancelled instance can neither
     * interleave writes with nor be overwritten by a newer attempt.
     */
    private static final Object DOWNLOAD_BOUNDARY = new Object();

    private File cacheRoot() { return new File(getCacheDir(), "updates"); }

    /** Narrow injectable seam for {@link UpdateTransport} so the
     *  production code path can be swapped for a fake in tests. */
    public interface TransportFactory {
        UpdateTransport create(String trustedKey);
    }

    private static volatile TransportFactory transportFactory = new TransportFactory() {
        @Override public UpdateTransport create(String trustedKey) {
            return new UpdateTransport(trustedKey);
        }
    };

    static void installTransportFactoryForTest(TransportFactory f) {
        if (f != null) transportFactory = f;
    }

    private static volatile java.util.function.Function<UpdatesActivity, String> trustKeyProvider =
            new java.util.function.Function<UpdatesActivity, String>() {
                @Override public String apply(UpdatesActivity activity) {
                    try (InputStream in = activity.getResources().openRawResource(R.raw.quest_update_key);
                         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] b = new byte[2048];
                        int n;
                        while ((n = in.read(b)) != -1) out.write(b, 0, n);
                        return new String(out.toByteArray(), "US-ASCII");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            };

    static void installTrustKeyProviderForTest(java.util.function.Function<UpdatesActivity, String> provider) {
        trustKeyProvider = provider;
    }

    private String trustKey() throws IOException {
        return trustKeyProvider.apply(this);
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        float density = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);
        int sp = Math.round(8 * density);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(getResources().getColor(R.color.upd_panel_bg));
        scroll.setFillViewport(true);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(pad, pad, pad, pad);
        card.setBackgroundColor(getResources().getColor(R.color.upd_panel_card));

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(pad, pad, pad, pad);
        scroll.addView(card, cardParams);

        TextView heading = new TextView(this);
        heading.setText(R.string.upd_title);
        heading.setTextSize(26);
        heading.setTextColor(getResources().getColor(R.color.upd_panel_fg));
        heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
        card.addView(heading, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, 0));

        TextView description = new TextView(this);
        description.setText(R.string.upd_intro);
        description.setTextSize(15);
        description.setTextColor(getResources().getColor(R.color.upd_panel_subtle));
        description.setLineSpacing(0f, 1.2f);
        card.addView(description, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, sp, 0, 0, sp));

        installedVersionView = new TextView(this);
        installedVersionView.setTextSize(15);
        installedVersionView.setTextColor(getResources().getColor(R.color.upd_panel_subtle));
        card.addView(installedVersionView, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, sp, 0, 0, sp));

        status = new TextView(this);
        status.setTextSize(17);
        status.setTextColor(getResources().getColor(R.color.upd_panel_fg));
        status.setLineSpacing(0f, 1.3f);
        card.addView(status, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, Math.round(16 * density)));

        primary = createPrimaryButton(card, density);
        cancel = createSecondaryButton(card, getString(R.string.upd_action_cancel), density);
        back = createSecondaryButton(card, getString(R.string.upd_action_back), density);

        cancel.setOnClickListener(v -> onCancelClicked());
        primary.setOnClickListener(v -> {
            // The retry branch must know which operation failed, so
            // both the bound action and its failure kind are captured
            // before the terminal state is cleared.
            PrimaryAction action = boundPrimaryAction;
            String failedKind = lastFailedKind;
            clearTerminalState();
            dispatchPrimaryAction(action, failedKind);
        });
        back.setOnClickListener(v -> finish());

        setContentView(scroll);

        pendingExplicitUpdate = consumeUpdateRequest(saved);

        refreshInstalledVersionView();
        acquireRepository();
    }

    /**
     * Read the explicit-update request and clear it from both the
     * intent and the restored bundle. Removing it from the intent is
     * what stops a configuration change — which keeps the same intent —
     * from replaying a tap that has already been acted on; clearing the
     * bundle covers a restore that does not carry the intent. Opening
     * the screen without the flag leaves the metadata-only automatic
     * check exactly as it was.
     */
    private boolean consumeUpdateRequest(Bundle saved) {
        Intent i = getIntent();
        boolean fromIntent = i != null && i.getBooleanExtra(EXTRA_REQUEST_UPDATE, false);
        boolean fromState = saved != null && saved.getBoolean(EXTRA_REQUEST_UPDATE, false);
        if (i != null) i.removeExtra(EXTRA_REQUEST_UPDATE);
        if (saved != null) saved.remove(EXTRA_REQUEST_UPDATE);
        return fromIntent || fromState;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int t, int l, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = t;
        p.leftMargin = l;
        p.rightMargin = r;
        p.bottomMargin = b;
        return p;
    }

    private Button createPrimaryButton(LinearLayout card, float density) {
        Button b = new Button(this);
        b.setMinHeight(Math.round(72 * density));
        b.setMinimumHeight(Math.round(72 * density));
        b.setTextSize(18);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setTextColor(getResources().getColor(R.color.upd_panel_on_primary));
        b.setBackgroundResource(R.drawable.upd_btn_primary);
        b.setPadding(Math.round(20 * density), Math.round(12 * density),
                Math.round(20 * density), Math.round(12 * density));
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        card.addView(b, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Math.round(8 * density), 0, 0, Math.round(12 * density)));
        return b;
    }

    private Button createSecondaryButton(LinearLayout card, String text, float density) {
        Button b = new Button(this);
        b.setText(text);
        b.setMinHeight(Math.round(56 * density));
        b.setMinimumHeight(Math.round(56 * density));
        b.setTextSize(16);
        b.setTextColor(getResources().getColor(R.color.upd_panel_fg));
        b.setBackgroundResource(R.drawable.upd_btn_secondary);
        b.setPadding(Math.round(20 * density), Math.round(8 * density),
                Math.round(20 * density), Math.round(8 * density));
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        card.addView(b, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, Math.round(8 * density)));
        return b;
    }

    private void acquireRepository() {
        repository = UpdateRepositoryProvider.get(getApplicationContext());
        if (repository == null) {
            // Cold-start install failed: surface it as the initial
            // state so a Retry tap can recover without recreation.
            actionError = firstErrorOr("Update service unavailable");
            lastFailedKind = "check";
            render();
            return;
        }
        repository.addObserver(observer);
        observerRegistered = true;
        current = repository.snapshot();
        // Any "service unavailable" notice is stale now that a
        // repository exists; let the real state render.
        actionError = null;
        lastFailedKind = null;
        render();
        if (repository.shouldRunByThrottle(false)) triggerBackgroundCheck();
    }

    private static String firstErrorOr(String fallback) {
        String err = UpdateRepositoryProvider.initializationErrorForTest();
        return err != null ? err : fallback;
    }

    private void onRepositoryUpdate(UpdateRepository.Snapshot s) {
        current = s;
        if (destroyed) return;
        runOnUiThread(() -> {
            if (destroyed || isFinishing() || isDestroyed()) return;
            render();
        });
    }

    /**
     * The release the primary action would act on right now.
     *
     * <p>Newest signed release wins, ranked by versionCode and then by
     * sequence, so a genuinely newer {@code available} release outranks
     * an older cached one. The cached APK is attached only when the
     * cached release IS that target: either the exact same release
     * (versionCode, digest and package all equal), or the newer cached
     * release that outranks the advertised one and therefore became
     * the target itself, so the download is skipped only for the
     * precise release the bytes were verified against. A cached
     * release at or below the installed versionCode is stale and is
     * never offered.
     */
    Attempt offeredAttempt() {
        UpdateRepository.Snapshot snap = current == null
                ? (repository == null ? null : repository.snapshot()) : current;
        return offeredAttempt(snap);
    }

    /**
     * The release this exact snapshot would have the primary action
     * act on.
     *
     * <p>An explicit tap that owns a check pins out of the snapshot
     * that check completed with, not out of the mutable current one:
     * a check published in the meantime must not retarget the tap that
     * is already committed to installing something.
     */
    Attempt offeredAttempt(UpdateRepository.Snapshot snap) {
        if (snap == null) return null;
        long installed = installedVersion();

        UpdateManifest available = snap.hasAvailable() && appliesTo(snap.available, installed)
                ? snap.available : null;
        UpdateManifest downloaded = snap.hasDownloaded() && appliesTo(snap.downloaded, installed)
                ? snap.downloaded : null;

        if (available == null && downloaded == null) return null;
        boolean preferDownloaded = downloaded != null
                && (available == null || isNewer(downloaded, available));

        UpdateManifest target = preferDownloaded ? downloaded : available;
        byte[] manifestBytes = preferDownloaded
                ? snap.downloadedManifestBytes : snap.availableManifestBytes;
        byte[] signatureBytes = preferDownloaded
                ? snap.downloadedSignatureBytes : snap.availableSignatureBytes;

        // The cache serves the target when the cached release IS that
        // target: an exact identity match, or the newer cached release
        // that outranks the advertised one and therefore became the
        // target itself. An older cache never stands in for the target,
        // and neither does one for a release that is not offered.
        boolean cacheMatches = downloaded != null
                && (preferDownloaded || (available != null
                        && downloaded.versionCode == available.versionCode
                        && downloaded.packageName.equals(available.packageName)
                        && downloaded.sha256.equals(available.sha256)));
        File cached = cacheMatches ? snap.downloadedApk : null;
        return new Attempt(target, manifestBytes, signatureBytes, cached, 0);
    }

    private static boolean appliesTo(UpdateManifest m, long installedVersion) {
        return m != null && m.versionCode > installedVersion;
    }

    private static boolean isNewer(UpdateManifest candidate, UpdateManifest current0) {
        if (candidate.versionCode != current0.versionCode)
            return candidate.versionCode > current0.versionCode;
        return candidate.sequence > current0.sequence;
    }

    Attempt offeredAttemptForTest() { return offeredAttempt(); }

    /** Compute the action the primary button is bound to. The same
     *  enum drives both the visible label and the click dispatch. */
    PrimaryAction computePrimaryAction() {
        if (repository == null) {
            return actionError != null ? PrimaryAction.RETRY : PrimaryAction.NONE;
        }
        // The installer's outcome is unknown, not failed: the user
        // decides whether to ask Android again. Nothing else is offered
        // while this rests, so metadata published meanwhile cannot pull
        // the tap away from the pinned release.
        if (stage == Stage.INSTALLER_UNCONFIRMED) return PrimaryAction.RETRY_UNCONFIRMED;
        if (stage == Stage.CHECKING && ownedCheck == null) {
            // A background check is running and no tap owns it, so the
            // one button stays live: a tap either installs what is
            // already offered or joins the very flight in the air and
            // installs what that flight finds. It is the same single
            // action, so the label does not change.
            return offeredAttempt() != null ? PrimaryAction.UPDATE : PrimaryAction.CHECK;
        }
        if (stage != Stage.IDLE) return PrimaryAction.NONE;
        if (isLiveVrRunning()) return PrimaryAction.NONE;
        if (actionError != null) return PrimaryAction.RETRY;
        return offeredAttempt() != null ? PrimaryAction.UPDATE : PrimaryAction.CHECK;
    }

    private void render() {
        PrimaryAction action = computePrimaryAction();
        boundPrimaryAction = action;
        Attempt pinned = attempt;
        String updateLabel = pinned != null
                ? getString(R.string.upd_action_update, pinned.version())
                : offeredAttemptLabel();

        String label;
        boolean enabled;
        if (action == PrimaryAction.NONE) {
            // Disabled. Keep naming the pinned release while a stage
            // runs so the label matches the work in flight.
            label = pinned != null ? updateLabel : UPDATE_LABEL;
            enabled = false;
        } else if (action == PrimaryAction.RETRY) {
            label = getString(R.string.upd_action_retry);
            enabled = true;
        } else if (action == PrimaryAction.RETRY_UNCONFIRMED) {
            // The status right above names the release whose outcome is
            // unknown, so the button only has to say Retry: one word,
            // and no ambiguity about what re-running it means.
            label = getString(R.string.upd_action_retry_unconfirmed);
            enabled = true;
        } else if (action == PrimaryAction.UPDATE) {
            label = updateLabel;
            enabled = true;
        } else {
            label = UPDATE_LABEL;
            enabled = true;
        }
        primary.setText(label);
        primary.setEnabled(enabled);

        cancel.setVisibility(isCancelAvailable() ? View.VISIBLE : View.GONE);
        cancel.setEnabled(isCancelAvailable());
        back.setVisibility(View.VISIBLE);
        back.setEnabled(true);

        refreshInstalledVersionView();
        renderStatus();
    }

    private String offeredAttemptLabel() {
        Attempt offered = offeredAttempt();
        return offered == null ? UPDATE_LABEL
                : getString(R.string.upd_action_update, offered.version());
    }

    private void refreshInstalledVersionView() {
        if (installedVersionView == null) return;
        long installed = installedVersion();
        installedVersionView.setText(installed < 0
                ? "Installed version: unavailable"
                : "Installed version: " + installed);
    }

    private void renderStatus() {
        boolean liveVr = isLiveVrRunning();
        Attempt pinned = attempt;
        if (stage == Stage.INSTALLER_UNCONFIRMED && pinned != null) {
            // Never claim the install was cancelled or that the app is
            // unchanged: Android owns the installer and simply has not
            // reported back.
            status.setText(getString(R.string.upd_installer_unconfirmed,
                    pinned.version()));
            return;
        }
        if (resultNotice != null) {
            status.setText(resultNotice + vrSuffix(liveVr, pinned != null));
            return;
        }
        if (stage != Stage.IDLE) {
            status.setText(stageText(pinned, liveVr));
            return;
        }
        if (actionError != null) {
            String kind = lastFailedKind != null ? lastFailedKind : "check";
            String target = pinned != null ? " (" + pinned.version() + ")" : "";
            status.setText(getString(R.string.upd_error_retry_update,
                    kind.equals("check") ? "Update check" : "Update" + target, actionError)
                    + vrSuffix(liveVr, false));
            return;
        }
        UpdateRepository.Snapshot s = current;
        if (s != null && s.checking) {
            status.setText("Checking for updates…");
            return;
        }
        Attempt offered = offeredAttempt();
        if (s == null) {
            status.setText("Not checked yet.");
            return;
        }
        if (s.lastError != null && offered == null) {
            status.setText("Update check unavailable: " + s.lastError + ". Your installed app is unchanged.");
            return;
        }
        if (offered == null) {
            if (liveVr) {
                status.setText("Waiting for VR to close before checking.");
                return;
            }
            status.setText(s.lastSuccessAtMs > 0 ? "App is up to date." : "Not checked yet.");
            return;
        }
        String verb = offered.hasCachedApk() ? "ready to install" : "available";
        String size = offered.hasCachedApk() ? ""
                : " (" + UpdateRepository.formatBytes(offered.manifest.bytes) + ")";
        status.setText("Update " + offered.version() + " " + verb + size + "."
                + vrSuffix(liveVr, true));
    }

    private String stageText(Attempt pinned, boolean liveVr) {
        String version = pinned == null ? "" : pinned.version();
        switch (stage) {
            case DOWNLOADING:
                return getString(R.string.upd_busy_downloading, version) + vrSuffix(liveVr, true);
            case VERIFYING:
                return getString(R.string.upd_busy_verifying, version) + vrSuffix(liveVr, true);
            case INSTALLER:
                return getString(R.string.upd_busy_installer, version) + vrSuffix(liveVr, true);
            case AWAITING_SYSTEM:
                return getString(R.string.upd_busy_awaiting, version);
            case INSTALLER_UNCONFIRMED:
                return getString(R.string.upd_installer_unconfirmed, version);
            case CHECKING:
                return "Checking for updates…";
        case IDLE:
        default:
            return "Not checked yet.";
        }
    }

    private static String vrSuffix(boolean liveVr, boolean actionable) {
        return liveVr && actionable ? " Close your VR session to continue." : "";
    }

    // ------------------------------------------------------------------
    //  Single-action dispatch
    // ------------------------------------------------------------------

    private void dispatchPrimaryAction(PrimaryAction action, String failedKind) {
        switch (action) {
            case CHECK:
                // One tap: force the check and install what it finds.
                requestUpdate();
                break;
            case UPDATE:
                startUpdate();
                break;
            case RETRY_UNCONFIRMED:
                retryUnconfirmedInstall();
                break;
            case RETRY:
                // Retry re-runs the operation that failed. A failed
                // update retries the pinned update itself; a failed
                // (or unavailable) check re-runs the check AND goes on
                // to install whatever that check finds, so a successful
                // retry never costs the user a second tap.
                if (!"check".equals(failedKind) && offeredAttempt() != null) {
                    startUpdate();
                } else {
                    requestUpdate();
                }
                break;
            case NONE:
            default:
                break;
        }
    }

    private void clearTerminalState() {
        actionError = null;
        lastFailedKind = null;
        resultNotice = null;
    }

    /**
     * Retry when Android's installer never reported an outcome. The same
     * pinned release and the same verified bytes are re-offered, never a
     * newer one a background check may have published meanwhile. The
     * previous launch identity is retired before any work starts, so a
     * late result for it cannot settle this attempt, and the re-dispatch
     * needs a result of its own: nothing installs on a resume.
     */
    private void retryUnconfirmedInstall() {
        Attempt pinned = attempt;
        if (pinned == null || stage != Stage.INSTALLER_UNCONFIRMED) return;
        if (installedVersionReached(pinned)) {
            settleConfirmedInstall(pinned);
            return;
        }
        handoff = null;
        handedToSystem = false;
        stage = Stage.VERIFYING;
        render();
        launchInstallWorker(pinned);
    }

    /** The installed version reached the pinned release: report success
     *  and forget the verified copy. */
    private void settleConfirmedInstall(Attempt pinned) {
        if (repository != null) repository.clearDownloadedAfterInstall(pinned.version());
        handoff = null;
        handedToSystem = false;
        awaitingPermission = false;
        attempt = null;
        stage = Stage.IDLE;
        resultNotice = "Update " + pinned.version() + " installed.";
        render();
    }

    /**
     * Invalidate the running attempt: it can no longer authorise an
     * installer launch, its queued continuation is dropped, the
     * hand-off record is retired and any permission dialog is
     * dismissed. The attempt is cancelled as well, so a worker that is
     * still running reports failure instead of touching the cache.
     */
    private void invalidateAttempt() {
        Attempt pinned = attempt;
        attempt = null;
        if (pinned != null) pinned.cancel();
        // A check a tap owned loses that ownership here: the flight is
        // shared app state and keeps running, but it can no longer pin
        // a target or install anything on this screen's behalf.
        ownedCheck = null;
        queuedContinuation = null;
        continuationQueued = false;
        continuationIsUpdate = false;
        handoff = null;
        awaitingPermission = false;
        dismissPermissionDialog();
        stage = Stage.IDLE;
    }

    private void dismissPermissionDialog() {
        AlertDialog dialog = permissionDialog;
        permissionDialog = null;
        if (dialog == null) return;
        try {
            dialog.setOnCancelListener(null);
            dialog.setOnDismissListener(null);
            if (dialog.isShowing()) dialog.dismiss();
        } catch (Exception ignored) {
            // A dialog that is already gone is exactly what we want.
        }
    }

    private boolean attemptIsActive(Attempt candidate) {
        return candidate != null
                && attempt != null
                && attempt.sameTargetAs(candidate)
                && !candidate.isCancelled()
                && !destroyed;
    }

    // ------------------------------------------------------------------
    //  Cancel
    // ------------------------------------------------------------------

    /**
     * Cancel stays available for the whole pre-handoff window: while
     * the check a tap owns is still in the air (there is no attempt to
     * cancel yet, but the tap is still owed an install), while
     * downloading, while verifying, and while the hand-off itself is
     * being prepared (including the source-permission dialog). Once
     * the OS owns the screen the attempt is no longer ours to cancel,
     * so the button retires.
     */
    boolean isCancelAvailable() {
        if (ownedCheck != null) return true;
        if (stage == Stage.IDLE || stage == Stage.AWAITING_SYSTEM
                || stage == Stage.INSTALLER_UNCONFIRMED) return false;
        return attempt != null;
    }

    /** True when the real installed version has reached the launch's
     *  target, which is the only evidence of a completed install. */
    private boolean installedVersionReached(Attempt pinned) {
        return pinned != null
                && installedVersion() >= pinned.manifest.versionCode;
    }

    private void onCancelClicked() {
        Attempt pinned = attempt;
        // Invalidate first: the pinned generation loses its authority
        // to authorise an installer launch, so a transport that keeps
        // writing (an uncooperative one) can no longer promote itself.
        invalidateAttempt();
        handedToSystem = false;
        UpdateTransport t = transport;
        if (t != null) t.cancel();
        // The verified cache is deliberately preserved so a later
        // Update does not re-download bytes we already proved good.
        resultNotice = pinned == null
                ? "Update cancelled."
                : "Update " + pinned.version() + " cancelled. The verified download was kept.";
        render();
    }

    // ------------------------------------------------------------------
    //  Check
    // ------------------------------------------------------------------

    /**
     * The background automatic check. It is metadata-only by
     * construction: throttled, skipped while VR is live, and never
     * carrying a target, so it can never install anything. A user's own
     * tap goes through {@link #requestUpdate()} instead, which forces
     * the check and owns its result.
     */
    private void triggerBackgroundCheck() {
        if (stage != Stage.IDLE) return;
        if (repository == null) {
            // The provider may have failed during cold-start; a
            // subsequent get() can succeed, so re-acquire before
            // giving up on the check.
            acquireRepository();
            if (repository == null) {
                actionError = firstErrorOr("Update service unavailable");
                lastFailedKind = "check";
                render();
                return;
            }
        }
        if (isLiveVrRunning()) {
            render();
            return;
        }
        // A metadata check carries no target and never installs, so it
        // gets its own stage rather than an Attempt.
        stage = Stage.CHECKING;
        render();

        final UpdateRepository.InFlight inflight = repository.requestCheck(false);
        if (inflight == null) {
            stage = Stage.IDLE;
            render();
            return;
        }
        Thread waiter = new Thread(() -> {
            try { inflight.await(60_000L); } catch (InterruptedException ignored) { return; }
            if (destroyed) return;
            publishToUi(() -> {
                // A metadata check never installs and never retargets
                // a pinned update: it only ends its own stage, and
                // never one a tap has taken over by joining this very
                // flight.
                if (stage == Stage.CHECKING && ownedCheck == null) stage = Stage.IDLE;
                render();
            });
        }, "UpdatesActivityCheckWaiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    // ------------------------------------------------------------------
    //  One-click update: check, then install what the check found
    // ------------------------------------------------------------------

    /**
     * The single explicit Update tap, with nothing offered to install
     * yet. The tap owns a metadata check: it requests one, or joins the
     * one already in the air, and the completion is what pins the
     * target and starts the install — so the user is never asked for a
     * second tap.
     *
     * <p>The tap is a generation like any other attempt: it supersedes
     * an earlier tap that was still waiting, and Cancel or Destroy
     * retires it, which is why ownership lives in a fresh
     * {@link ExplicitCheck} object rather than in a flag.
     */
    private void requestUpdate() {
        if (stage != Stage.IDLE && stage != Stage.CHECKING) return;
        if (repository == null) {
            // The provider may have failed during cold-start; a
            // subsequent get() can succeed, so re-acquire before
            // giving up on the check.
            acquireRepository();
            if (repository == null) {
                actionError = firstErrorOr("Update service unavailable");
                lastFailedKind = "check";
                render();
                return;
            }
        }
        clearTerminalState();

        UpdateRepository.InFlight flight;
        try {
            // requestCheck coalesces, so a background check already in
            // the air is joined rather than duplicated (and is bumped
            // to forced, so the throttle cannot silently skip it).
            flight = repository.requestCheck(true);
        } catch (RuntimeException e) {
            actionError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            lastFailedKind = "check";
            render();
            return;
        }
        if (flight == null) {
            render();
            return;
        }
        final ExplicitCheck req = new ExplicitCheck(flight, ++generationCounter);
        ownedCheck = req;
        stage = Stage.CHECKING;
        render();

        Thread waiter = new Thread(() -> {
            try { req.flight.await(EXPLICIT_CHECK_TIMEOUT_MS); }
            catch (InterruptedException ignored) { return; }
            if (destroyed) return;
            publishToUi(() -> onExplicitCheckComplete(req));
        }, "UpdatesActivityUpdateCheck");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * The check a tap owned has finished. Ownership is re-checked here,
     * because the tap may have been cancelled, superseded or destroyed
     * while the flight was in the air, and a retired tap must install
     * nothing.
     *
     * <p>Completion — not the snapshot's {@code checking} flag — is the
     * authority on what happened: the repository completes the flight
     * and only afterwards clears {@code checking}, so a completed
     * snapshot legitimately still says it is checking. Reading that
     * flag would report "checking" for a check that is over.
     */
    private void onExplicitCheckComplete(ExplicitCheck req) {
        if (req == null || ownedCheck != req || destroyed) return;
        ownedCheck = null;

        UpdateRepository.InFlight flight = req.flight;
        if (!flight.isCompleted()) {
            // Timed out rather than failed, but the tap it owed an
            // install for cannot be delivered, so it ends as a failed
            // check the user can Retry.
            stage = Stage.IDLE;
            actionError = "Update check did not finish in time";
            lastFailedKind = "check";
            render();
            return;
        }
        Throwable failure = flight.failure();
        if (failure != null) {
            stage = Stage.IDLE;
            actionError = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            lastFailedKind = "check";
            render();
            return;
        }
        // Pin out of THIS flight's snapshot. The repository keeps
        // running and may already have published something newer, but
        // the target this tap owns is the one its own check produced.
        Attempt offered = offeredAttempt(flight.result());
        if (offered == null) {
            // Nothing newer: the ordinary up-to-date notice, rendered
            // from the repository exactly as it always was.
            stage = Stage.IDLE;
            render();
            return;
        }
        startUpdate(offered);
    }

    // ------------------------------------------------------------------
    //  Update (download -> verify -> installer, automatically)
    // ------------------------------------------------------------------

    private void startUpdate() {
        if (stage != Stage.IDLE && stage != Stage.CHECKING) return;
        Attempt offered = offeredAttempt();
        if (offered == null) {
            // Nothing is offered yet (first run). The same tap owns a
            // check and installs what that check finds, rather than
            // ending at a "nothing to do" screen that would need a
            // second tap.
            requestUpdate();
            return;
        }
        startUpdate(offered);
    }

    /**
     * Pin {@code proposed} to a fresh generation and run the existing
     * download, verify and installer flow on it.
     *
     * <p>While paused the target is pinned but the work is not started:
     * the resume continues this exact attempt, so a completion that
     * lands off-screen can neither be lost nor retargeted by whatever
     * the next check publishes.
     */
    private void startUpdate(Attempt proposed) {
        if (proposed == null || destroyed) return;
        if (stage != Stage.IDLE && stage != Stage.CHECKING) return;
        clearTerminalState();
        Attempt pinned = new Attempt(proposed.manifest, proposed.manifestBytes,
                proposed.signatureBytes, proposed.cachedApk, ++generationCounter);
        attempt = pinned;
        handedToSystem = false;
        awaitingPermission = false;
        handoff = null;
        stage = pinned.hasCachedApk() ? Stage.VERIFYING : Stage.DOWNLOADING;
        if (!resumed) {
            queuedContinuation = pinned;
            continuationQueued = true;
            continuationIsUpdate = true;
            render();
            return;
        }
        render();

        if (pinned.hasCachedApk()) {
            // Verified bytes are already on disk; skip straight to
            // verification and the installer hand-off.
            launchInstallWorker(pinned);
        } else {
            launchDownloadWorker(pinned);
        }
    }

    /** Continue a pinned attempt that was waiting for a resume. The
     *  cached-bytes path still goes through verification: a queued
     *  install continuation has not verified anything yet. */
    private void resumePinnedUpdate(Attempt pinned) {
        if (!attemptIsActive(pinned)) return;
        if (pinned.hasCachedApk()) launchInstallWorker(pinned);
        else launchDownloadWorker(pinned);
    }

    /**
     * Download, bind and hand back the pinned release. The shared
     * staging file is mutated under the process-wide download
     * boundary, and the bytes are bound to their immutable per-version
     * copy while that boundary is still held, so the installer is
     * later fed a file nothing else can overwrite.
     */
    private void launchDownloadWorker(final Attempt pinned) {
        Thread worker = new Thread(() -> {
            UpdateTransport local = null;
            File staged = null;
            File bound = null;
            boolean cancelled = false;
            boolean busy = false;
            String busyMessage = null;
            String failure = null;
            try {
                local = transportFactory.create(trustKey());
                local.manifestBytes = pinned.manifestBytes;
                local.signatureBytes = pinned.signatureBytes;
                transport = local;
                if (stale(pinned)) { cancelled = true; return; }
                synchronized (DOWNLOAD_BOUNDARY) {
                    // Re-check under the boundary: the wait for it can
                    // be long enough for this attempt to be cancelled
                    // or superseded.
                    if (stale(pinned)) { cancelled = true; return; }
                    // Busy is not a failure of the file: report it so
                    // the cached bytes of any earlier attempt survive.
                    ensureIdle();
                    staged = local.download(pinned.manifest, cacheRoot());
                    if (stale(pinned)) { cancelled = true; return; }
                    if (repository != null) {
                        bound = repository.recordDownloaded(pinned.manifest,
                                pinned.manifestBytes, pinned.signatureBytes, staged);
                    }
                }
            } catch (BusySignal busySignal) {
                if (!stale(pinned)) { busy = true; busyMessage = busySignal.getMessage(); }
            } catch (Exception e) {
                if (stale(pinned)) { cancelled = true; return; }
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                if (transport == local) transport = null;
                if (local != null && (cancelled || failure != null || busy)) local.cancel();
            }
            if (busy) {
                final String message = busyMessage;
                publishToUi(() -> onBusy(pinned, message));
                return;
            }
            final boolean downloadCompleted = staged != null;
            final File immutable = bound;
            final String error = failure;
            final boolean wasCancelled = cancelled;
            publishToUi(() -> {
                if (downloadCompleted) onDownloadComplete(pinned, immutable);
                else onDownloadFailed(pinned, wasCancelled, error);
            });
        }, "UpdatesActivityDownload");
        worker.setDaemon(true);
        worker.start();
    }

    /** True once the pinned attempt may no longer act: destroyed,
     *  cancelled, or superseded by a newer generation. */
    private boolean stale(Attempt pinned) {
        return destroyed || pinned.isCancelled() || !attemptIsActive(pinned);
    }

    private void onDownloadComplete(Attempt pinned, File immutable) {
        if (!attemptIsActive(pinned)) return;
        if (immutable == null) {
            // Without the immutable binding there is nothing safe to
            // verify or hand over. The shared staging file is never a
            // fallback: another attempt may already own it.
            invalidateAttempt();
            actionError = "Could not store the verified download for " + pinned.version();
            lastFailedKind = "update";
            render();
            return;
        }
        // Verify and hand over the copy bound to this release. The
        // shared staging file is only a transfer buffer: once the
        // download boundary is released another Activity instance may
        // overwrite it, and verifying that file would fail spuriously
        // and purge a perfectly good download.
        Attempt verified = pinned.withApk(immutable);
        attempt = verified;
        stage = Stage.VERIFYING;
        render();
        launchInstallWorker(verified);
    }

    private void onDownloadFailed(Attempt pinned, boolean cancelled, String error) {
        if (!attemptIsActive(pinned)) return;
        invalidateAttempt();
        if (!cancelled) {
            actionError = error;
            lastFailedKind = "update";
            resultNotice = null;
        }
        render();
    }

    /**
     * Verify the pinned bytes. Busy and integrity are distinct
     * outcomes: only a genuine integrity failure may purge the cache.
     */
    private void launchInstallWorker(final Attempt pinned) {
        Thread worker = new Thread(() -> {
            try {
                ensureIdle();
            } catch (BusySignal busy) {
                publishToUi(() -> onBusy(pinned, busy.getMessage()));
                return;
            }
            try {
                verifyApk(pinned.cachedApk, pinned.manifest);
            } catch (Exception apkError) {
                final String msg = apkError.getMessage() == null
                        ? apkError.getClass().getSimpleName() : apkError.getMessage();
                publishToUi(() -> onVerificationFailed(pinned, msg));
                return;
            }
            publishToUi(() -> onVerified(pinned));
        }, "UpdatesActivityVerify");
        worker.setDaemon(true);
        worker.start();
    }

    private void onVerificationFailed(Attempt pinned, String message) {
        if (!attemptIsActive(pinned)) return;
        // The cached bytes did not verify: purge them so the next
        // Update downloads fresh ones, then report honestly.
        if (repository != null) repository.clearDownloadedAfterInstall(pinned.version());
        invalidateAttempt();
        actionError = message;
        lastFailedKind = "update";
        render();
    }

    /**
     * A failure that is not evidence of corruption (a live VR session,
     * an unwritable cache). The attempt ends so the single button can
     * be tapped again, but the verified cache is left intact and Retry
     * re-runs the update.
     */
    private void onBusy(Attempt pinned, String message) {
        if (!attemptIsActive(pinned)) return;
        invalidateAttempt();
        actionError = message;
        lastFailedKind = "update";
        render();
    }

    /**
     * The pinned bytes are verified. Stay in VERIFYING (the hand-off
     * is still ours) and let the resume deliver the installer: while
     * paused the continuation is queued exactly once, for this exact
     * attempt, so a completed download can never be stranded by the
     * stage the resume looks for.
     */
    private void onVerified(Attempt pinned) {
        if (!attemptIsActive(pinned)) return;
        stage = Stage.VERIFYING;
        render();
        if (!resumed) {
            queuedContinuation = pinned;
            continuationQueued = true;
            continuationIsUpdate = false;
            return;
        }
        dispatchInstaller(pinned);
    }

    /**
     * Ask Android to install the pinned APK. This is the whole point
     * of the single action: the user taps Update once and ends up in
     * the OS installer without a second in-app tap.
     */
    private void dispatchInstaller(Attempt pinned) {
        if (!attemptIsActive(pinned)) return;
        try {
            ensureIdle();
            if (!getPackageManager().canRequestPackageInstalls()) {
                showPermissionDialog(pinned);
                return;
            }
            handOffToInstaller(pinned);
        } catch (BusySignal busy) {
            onBusy(pinned, busy.getMessage());
        } catch (Exception e) {
            handedToSystem = false;
            invalidateAttempt();
            actionError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            lastFailedKind = "update";
            render();
        }
    }

    /**
     * The APK is verified and Android has not been asked yet, so the
     * attempt is still ours and still cancellable. Every dismissal
     * route (the buttons, Back and an outside tap) is scoped to this
     * exact generation, so a dialog left over from an earlier attempt
     * can never invalidate the attempt that is live now.
     */
    private void showPermissionDialog(final Attempt pinned) {
        final int generation = pinned.generation;
        stage = Stage.INSTALLER;
        awaitingPermission = true;
        handoff = new SystemHandoff(SystemHandoff.AWAITING_PERMISSION,
                SystemHandoff.NO_REQUEST, pinned);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Allow app updates")
                .setMessage("Android needs permission for Vibertemis to open its installer. "
                        + "Turn on Allow from this source for Vibertemis, then come back here. "
                        + "The verified download is kept either way.")
                .setPositiveButton("Open settings", (d, w) -> {
                    if (!dialogBelongsTo(pinned, generation)) return;
                    openInstallPermissionSettings(pinned, generation);
                })
                .setNegativeButton("Not now", (d, w) -> {
                    if (!dialogBelongsTo(pinned, generation)) return;
                    stopAttemptWithNotice(pinned,
                            "Update paused. The verified download was kept.");
                })
                .setOnCancelListener(d -> {
                    // Back or an outside tap: without this the stage
                    // would stay stuck on the hand-off forever.
                    if (!dialogBelongsTo(pinned, generation)) return;
                    stopAttemptWithNotice(pinned,
                            "Update paused. The verified download was kept.");
                })
                .create();
        permissionDialog = dialog;
        dialog.show();
        render();
    }

    /** True while this dialog still belongs to the live attempt. */
    private boolean dialogBelongsTo(Attempt pinned, int generation) {
        return attemptIsActive(pinned) && pinned.generation == generation;
    }

    private void stopAttemptWithNotice(Attempt pinned, String notice) {
        handedToSystem = false;
        invalidateAttempt();
        resultNotice = notice;
        render();
    }

    /** Open the OS screen where the install permission is granted. The
     *  result, when one arrives, is matched against the request code
     *  this launch uses and reconciled on resume. */
    private void openInstallPermissionSettings(Attempt pinned, int generation) {
        dismissPermissionDialog();
        if (!dialogBelongsTo(pinned, generation)) {
            render();
            return;
        }
        int requestCode = allocateRequestCode();
        if (requestCode < 0) {
            stopAttemptWithNotice(pinned, "Update paused. The verified download was kept.");
            return;
        }
        try {
            ensureIdle();
            handedToSystem = false;
            awaitingPermission = true;
            handoff = new SystemHandoff(SystemHandoff.AWAITING_SETTINGS, requestCode, pinned);
            stage = Stage.INSTALLER;
            startActivityForResult(
                    new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())),
                    requestCode);
            render();
        } catch (BusySignal busy) {
            onBusy(pinned, busy.getMessage());
        } catch (ActivityNotFoundException e) {
            stopAttemptWithNotice(pinned, "Open Android settings and allow installs from "
                    + "Vibertemis, then tap Update again.");
        }
    }

    /** Hand the release-bound verified APK to the install session. The
     *  file stays in this app's own update cache: the session re-verifies
     *  exactly this release and commits it to Android from there, so
     *  nothing that arrives later can change what gets installed. */
    private void handOffToInstaller(Attempt pinned) throws IOException {
        File apk = pinned.cachedApk;
        if (apk == null || !apk.isFile()) throw new IOException("Verified update file is missing");
        int requestCode = allocateRequestCode();
        if (requestCode < 0) {
            // Fail closed: launching without a trackable request would
            // make the result impossible to attribute.
            throw new IOException("No request code available for the installer hand-off");
        }
        Intent intent = SessionInstallActivity.newIntent(this, apk.getAbsolutePath(),
                pinned.manifest.packageName, pinned.manifest.versionCode,
                pinned.manifest.bytes, pinned.manifest.sha256);
        handedToSystem = true;
        awaitingPermission = false;
        handoff = new SystemHandoff(SystemHandoff.AWAITING_INSTALLER, requestCode, pinned);
        stage = Stage.AWAITING_SYSTEM;
        startActivityForResult(intent, requestCode);
        render();
    }

    private void publishToUi(Runnable r) {
        if (destroyed) return;
        runOnUiThread(() -> {
            if (destroyed || isFinishing() || isDestroyed()) return;
            r.run();
        });
    }

    @SuppressWarnings("deprecation") private void verifyApk(File file, UpdateManifest m) throws Exception {
        if (file == null || !file.isFile()) throw new IOException("Verified update file is missing");
        UpdateTransport.verifyFile(file, m);
        PackageInfo own = ownPackage();
        long installed = installedVersion();
        if (installed < 0) throw new IOException("Could not read installed version");
        if (m.sequence <= UpdateRepository.MIN_PUBLISHED_SEQUENCE
                || m.versionCode <= installed
                || !m.packageName.equals(getPackageName())) {
            throw new IOException("APK package/version mismatch or downgrade");
        }
        PackageInfo candidate = getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),
                Build.VERSION.SDK_INT >= 28
                        ? PackageManager.GET_SIGNING_CERTIFICATES
                        : PackageManager.GET_SIGNATURES);
        if (candidate == null || !candidate.packageName.equals(getPackageName())
                || version(candidate) != m.versionCode) {
            throw new IOException("Downloaded APK identity mismatch");
        }
        if (Build.VERSION.SDK_INT >= 28
                && (own.signingInfo == null || candidate.signingInfo == null)) {
            // Fail closed: a package that reports no signing information
            // cannot be shown to come from this installation, and the
            // signer must never be read out of nothing.
            throw new IOException("APK signing information is missing");
        }
        android.content.pm.Signature[] oldSigners = Build.VERSION.SDK_INT >= 28
                ? own.signingInfo.getApkContentsSigners() : own.signatures;
        android.content.pm.Signature[] newSigners = Build.VERSION.SDK_INT >= 28
                ? candidate.signingInfo.getApkContentsSigners() : candidate.signatures;
        if (oldSigners == null || newSigners == null
                || oldSigners.length != 1 || newSigners.length != 1) {
            throw new IOException("Unsupported APK signer set");
        }
        String oldHash = UpdateManifest.hex(MessageDigest.getInstance("SHA-256").digest(oldSigners[0].toByteArray()));
        String newHash = UpdateManifest.hex(MessageDigest.getInstance("SHA-256").digest(newSigners[0].toByteArray()));
        if (!oldHash.equals(newHash) || !newHash.equals(m.signer)) {
            throw new IOException("Downloaded APK signer does not match this installation");
        }
    }

    private boolean isLiveVrRunning() {
        android.app.ActivityManager manager =
                (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (manager == null) return false;
        java.util.List<android.app.ActivityManager.RunningAppProcessInfo> processes =
                manager.getRunningAppProcesses();
        if (processes == null) return false;
        String pcvr = getPackageName() + ":pcvr";
        for (android.app.ActivityManager.RunningAppProcessInfo p : processes) {
            if (pcvr.equals(p.processName)) return true;
        }
        return false;
    }

    /**
     * Busy, not broken. A live VR session blocks the hand-off without
     * saying anything about the integrity of the pinned bytes, so it
     * must never purge a verified cache.
     */
    private static final class BusySignal extends IOException {
        BusySignal(String message) { super(message); }
    }

    private void ensureIdle() throws BusySignal {
        if (isLiveVrRunning()) throw new BusySignal("Close your VR session before updating");
    }

    @SuppressWarnings("deprecation") private PackageInfo ownPackage() throws Exception {
        return getPackageManager().getPackageInfo(getPackageName(),
                Build.VERSION.SDK_INT >= 28
                        ? PackageManager.GET_SIGNING_CERTIFICATES
                        : PackageManager.GET_SIGNATURES);
    }

    @SuppressWarnings("deprecation") private long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    /** Returns the actually-installed versionCode. {@code -1L} on
     *  PackageManager failure (rendered as "Installed version:
     *  unavailable" — never {@link Long#MAX_VALUE}). */
    private long installedVersion() {
        try {
            return version(ownPackage());
        } catch (Exception e) {
            return -1L;
        }
    }

    // ------------------------------------------------------------------
    //  Lifecycle
    // ------------------------------------------------------------------

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        SystemHandoff pending = handoff;
        if (pending != null) {
            // Returning from the OS. A result that arrived while we were
            // away settles now; a Settings return settles on the real
            // permission even with no callback at all. Each is consumed
            // as it is settled, so repeated resumes cannot act twice.
            if (pending.phase == SystemHandoff.AWAITING_SETTINGS) {
                reconcilePermissionReturn(pending);
            } else if (pending.phase == SystemHandoff.AWAITING_INSTALLER) {
                // Every real return from the installer is reconciled,
                // including the return with no callback at all: only
                // reconciliation can tell the three cases apart. An
                // unreported outcome is unknown, so it must rest in
                // INSTALLER_UNCONFIRMED rather than keep pretending the
                // OS installer still owns the hand-off, and gating that
                // call on a result that may never arrive would make the
                // unknown case unreachable.
                reconcileInstallerReturn(pending);
            }
            render();
            return;
        }
        if (stage == Stage.INSTALLER_UNCONFIRMED) {
            // Still unknown: give the user the choice rather than
            // deciding for them, and check for a late arrival.
            render();
            return;
        }
        // A verification that completed while paused continues exactly
        // once, from the very stage it left, and only for the still
        // active attempt. A pin made by a tap that owns a check
        // continues too, and from its own stage: it starts the work,
        // it does not hand unverified bytes to the installer.
        if (continuationQueued && queuedContinuation != null) {
            Attempt queued = queuedContinuation;
            boolean startsUpdate = continuationIsUpdate;
            queuedContinuation = null;
            continuationQueued = false;
            continuationIsUpdate = false;
            if (attemptIsActive(queued)) {
                if (startsUpdate) resumePinnedUpdate(queued);
                else if (stage == Stage.VERIFYING) dispatchInstaller(queued);
            }
        }
        if (pendingExplicitUpdate) {
            // The launch asked for an update. Take that request now,
            // once: the flag was consumed at create, so nothing can
            // replay it, and a request whose check was cancelled
            // inherits no authority to install.
            pendingExplicitUpdate = false;
            if (repository == null) acquireRepository();
            requestUpdate();
            render();
            return;
        }
        if (stage == Stage.IDLE) {
            if (repository == null) acquireRepository();
            if (repository != null) triggerBackgroundCheck();
            render();
        }
    }

    /**
     * Coming back from the install-permission screen. The OS answer
     * is the live permission state, so a return with no callback and a
     * return with a denial are handled the same way: the verified
     * download is kept and the installer resumes only for a grant on
     * the still-active attempt.
     */
    private void reconcilePermissionReturn(SystemHandoff pending) {
        handoff = null;
        awaitingPermission = false;
        Attempt pinned = attempt;
        if (!pending.describes(pinned)) {
            // A stale answer for an attempt that is gone: nothing of
            // the live state may be touched.
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            invalidateAttempt();
            resultNotice = "Install permission was not granted. The verified download was kept.";
            return;
        }
        stage = Stage.VERIFYING;
        dispatchInstaller(pinned);
    }

    /**
     * Reconcile the return from the OS installer.
     *
     * <p>Only the real installed version proves anything. When Android
     * sent a matching result and the version still has not advanced, the
     * install did not complete and the verified download is kept for an
     * ordinary retry. When Android sent no result at all, the outcome is
     * genuinely unknown — a multiwindow installer may simply still be up
     * — so the attempt rests in {@link Stage#INSTALLER_UNCONFIRMED} with
     * its request identity intact, so a late result can still settle it.
     */
    private void reconcileInstallerReturn(SystemHandoff pending) {
        handedToSystem = false;
        awaitingPermission = false;
        Attempt pinned = attempt;
        if (!pending.describes(pinned)) {
            handoff = null;
            return;
        }
        if (installedVersionReached(pinned)) {
            settleConfirmedInstall(pinned);
            return;
        }
        if (pending.resultReceived) {
            handoff = null;
            invalidateAttempt();
            resultNotice = failedNotice(pending, pinned);
            return;
        }
        // No answer from Android: unknown, not failed.
        stage = Stage.INSTALLER_UNCONFIRMED;
        render();
    }

    /**
     * Why an attempt that came back from the install session did not
     * install. The session's own short reason is used when it reported
     * one, so a refusal Android actually explained is not flattened into
     * a generic sentence; a result that said nothing keeps the generic
     * wording. {@code RESULT_OK} never reaches here on its own: the
     * installed version has already been checked and has not advanced.
     *
     * <p>Only a reported install status is named as a failure. Anything
     * else is shown as what the session could actually say, so an outcome
     * it could not prove is not turned into a claim it never made.
     */
    private String failedNotice(SystemHandoff pending, Attempt pinned) {
        String reason = pending.resultNotice;
        if (reason == null || reason.isEmpty()) {
            return "Update " + pinned.version() + " was not installed. The verified download "
                    + "was kept — tap Update to try again.";
        }
        String lead = pending.resultStatus > 0
                ? "Update " + pinned.version() + " failed: "
                : "Update " + pinned.version() + " — ";
        return lead + reason + ". The verified download was kept — tap Update to try again.";
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
    }

    /**
     * A result is only ever a fact about the launch that produced it.
     * The live hand-off records that launch's request code, so a result
     * carrying any other code — a superseded attempt's, or a plain
     * duplicate — is dropped. Nothing is accepted while only the
     * permission dialog is up, because nothing has been launched yet.
     *
     * <p>A matching result is recorded against that hand-off and nothing
     * more happens here: while paused the settlement belongs to
     * {@link #onResume}, so a result can never launch a hand-off and
     * then be mistaken for its return. While already resumed it settles
     * immediately, so it never waits for a resume that may not come.
     */
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        SystemHandoff pending = handoff;
        if (pending == null) return;
        if (pending.requestCode == SystemHandoff.NO_REQUEST) return;
        if (request != pending.requestCode) return;
        if (!pending.describes(attempt)) return;
        pending.recordResult(result, data);
        if (!resumed) return;
        settleHandoff(pending);
    }

    /** Settle a hand-off whose result has arrived and whose activity is
     *  in the foreground. */
    private void settleHandoff(SystemHandoff pending) {
        if (pending.phase == SystemHandoff.AWAITING_SETTINGS) {
            dismissPermissionDialog();
            reconcilePermissionReturn(pending);
        } else if (pending.phase == SystemHandoff.AWAITING_INSTALLER) {
            reconcileInstallerReturn(pending);
        } else {
            return;
        }
        render();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        resumed = false;
        invalidateAttempt();
        handedToSystem = false;
        if (observerRegistered && repository != null) repository.removeObserver(observer);
        UpdateTransport t = transport;
        if (t != null) t.cancel();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    //  Test accessors
    // ------------------------------------------------------------------

    String windowStatusText() { return status == null ? null : status.getText().toString(); }

    String currentActionErrorForTest() { return actionError; }
    String currentLastFailedKindForTest() { return lastFailedKind; }
    String currentResultNoticeForTest() { return resultNotice; }

    /** The action the primary button is currently bound to. */
    PrimaryAction primaryActionForTest() { return boundPrimaryAction; }

    /** The visible primary label. */
    String primaryLabelForTest() { return primary == null ? null : primary.getText().toString(); }

    /** The version the running attempt is pinned to, or null. */
    String pinnedVersionForTest() { return attempt == null ? null : attempt.version(); }

    String runningStageForTest() { return stage.name(); }

    /** True while Android owns the installer and has not reported an
     *  outcome. */
    boolean installerUnconfirmedForTest() { return stage == Stage.INSTALLER_UNCONFIRMED; }

    /** The single primary button. Tests click this one view. */
    Button primaryButtonForTest() { return primary; }

    Button cancelButtonForTest() { return cancel; }

    boolean isPrimaryEnabledForTest() { return primary != null && primary.isEnabled(); }

    int cancelVisibilityForTest() { return cancel == null ? View.GONE : cancel.getVisibility(); }
    boolean isCancelVisibleForTest() { return cancel != null && cancel.getVisibility() == View.VISIBLE; }
    boolean isBackVisibleForTest() { return back != null && back.getVisibility() == View.VISIBLE; }

    /** True once the OS installer has taken the APK. */
    boolean handedToSystemForTest() { return handedToSystem; }

    boolean awaitingPermissionForTest() { return awaitingPermission; }

    /** True while the OS installer owns the screen. */
    boolean awaitingSystemForTest() { return stage == Stage.AWAITING_SYSTEM; }

    UpdateTransport publishedTransportForTest() { return transport; }
}
