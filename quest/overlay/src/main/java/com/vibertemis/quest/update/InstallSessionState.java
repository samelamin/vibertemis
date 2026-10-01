package com.vibertemis.quest.update;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInstaller.SessionInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-scoped coordinator for a Vibertemis self-update install session.
 *
 * <p>One caller at a time owns the flow: {@link #prepare(Context, Target)}
 * pins and verifies an immutable {@link Target}, and only a resumed
 * foreground owner on the main thread may {@link #commitWhenForeground}.
 * Nothing installs from metadata and nothing retries on its own: every
 * stage change comes from an explicit request or an Android status callback.
 *
 * <p>The confirmation Intent is held in memory only, so process death loses
 * it safely; {@link #restoreAfterRecreation} then reports
 * {@link Stage#UNCONFIRMED} instead of guessing.
 *
 * <p>State is mutated on the main thread only; the single IO thread
 * verifies and writes behind a volatile generation guard, so a cancelled or
 * superseded worker can neither create nor commit a session.
 */
final class InstallSessionState {

    /** Coarse install phases, in the order they can occur. */
    enum Stage {
        /** Verifying bytes and writing the OS session (worker thread). */
        PREPARING,
        /** Session written; waiting for the foreground owner. */
        PREPARED,
        /** {@code commit()} was called exactly once. */
        COMMITTED,
        /** Android handed back a confirmation Intent to start. */
        CONFIRMATION,
        /** Terminal failure with a short message and numeric status. */
        FAILED,
        /** Android has not told us whether the install happened. */
        UNCONFIRMED,
        /** Installed version reached the target with the same signer. */
        SUCCEEDED,
        /** Cancelled before commit. */
        CANCELLED
    }

    /** State sink; the owner must {@link #detach(Listener)} when done. */
    interface Listener {
        void onChanged(Stage stage);
    }

    /** Immutable description of the APK to install. */
    static final class Target {
        final String apkPath;
        final String packageName;
        final long versionCode;
        final long bytes;
        final String sha256;

        Target(String apkPath, String packageName, long versionCode, long bytes, String sha256) {
            this.apkPath = apkPath == null ? null : apkPath;
            this.packageName = packageName;
            this.versionCode = versionCode;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    /** Hard cap: 1 GiB. */
    static final long MAX_APK_BYTES = 1024L * 1024L * 1024L;
    /** Added in the next task; referenced by name until then. */
    private static final String RECEIVER = "com.vibertemis.quest.update.SessionInstallReceiver";
    private static final String ACTION_PREFIX = "com.vibertemis.quest.update.INSTALL_STATUS.";
    private static final String URI_PREFIX = "vibertemis-update://install/";
    private static final int MAX_PUBLISH_HOPS = 32;

    private static final InstallSessionState INSTANCE = new InstallSessionState();

    static InstallSessionState get() { return INSTANCE; }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "InstallSessionState");
        t.setDaemon(true);
        return t;
    });
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    /** Read by the IO worker, written on the main thread. */
    private volatile int generation;
    private volatile boolean cancelled;

    // Main-thread owned.
    private Stage stage;
    private String msg;
    private int code;
    private Target target;
    private Context appContext;
    private int sessionId = -1;
    private String nonce = "";
    private Uri matchUri;
    private String expectedAction;
    private Intent pendingConfirmation;
    private String signedFingerprint;
    private String gateReason;
    private boolean dispatching;
    private Stage queuedStage;
    private String queuedMessage;
    private int queuedCode;

    private InstallSessionState() { }

    // ------------------------------------------------------------------
    //  Observation
    // ------------------------------------------------------------------

    void attach(Listener l) {
        if (l == null) return;
        listeners.add(l);
        onMain(() -> {
            try { l.onChanged(stage); } catch (Throwable ignored) { }
        });
    }

    void detach(Listener l) { if (l != null) listeners.remove(l); }

    int listenerCountForTest() { return listeners.size(); }
    Stage stage() { return stage; }
    /** Idle is represented by a null stage, never by a placeholder. */
    boolean isIdle() { return stage == null; }
    String message() { return msg; }
    int statusCode() { return code; }
    int sessionId() { return sessionId; }
    String nonce() { return nonce; }
    Target target() { return target; }
    boolean hasPendingConfirmation() { return pendingConfirmation != null; }
    /** Signer captured for this attempt; persist it to survive a restart. */
    String signerProof() { return signedFingerprint; }
    /** Why the last foreground commit was refused, else null. */
    String gateReason() { return gateReason; }
    /** Signer of the installed package, for persisting before an update. */
    static String currentSignerFingerprint(Context app) {
        if (app == null) return null;
        try {
            PackageInfo info = app.getPackageManager()
                    .getPackageInfo(app.getPackageName(), infoFlags());
            return fingerprint(info);
        } catch (Exception e) {
            return null;
        }
    }

    /** Terminal stages never move again; a duplicate callback is inert. */
    static boolean isTerminal(Stage s) {
        return s == Stage.FAILED || s == Stage.SUCCEEDED
                || s == Stage.CANCELLED || s == Stage.UNCONFIRMED;
    }

    // ------------------------------------------------------------------
    //  Prepare
    // ------------------------------------------------------------------

    /**
     * Explicit request to verify {@code t} and stage a session. A malformed
     * request throws here; every later failure is published as
     * {@link Stage#FAILED} on the main thread.
     */
    void prepare(Context app, Target t) {
        if (app == null) throw new IllegalArgumentException("context is required");
        if (t == null || t.apkPath == null || t.packageName == null || t.versionCode <= 0
                || t.bytes <= 0 || t.bytes > MAX_APK_BYTES || !validDigest(t.sha256)) {
            throw new IllegalArgumentException("invalid update target");
        }
        final Context ctx = app.getApplicationContext();
        onMain(() -> startPrepare(ctx, t));
    }

    private void startPrepare(Context app, Target t) {
        if (stage == Stage.PREPARING || stage == Stage.PREPARED
                || stage == Stage.COMMITTED || stage == Stage.CONFIRMATION) {
            // An attempt is live: a second prepare must not abandon it.
            return;
        }
        final int gen = ++generation;
        cancelled = false;
        nonce = UUID.randomUUID().toString();
        target = t;
        appContext = app;
        sessionId = -1;
        matchUri = null;
        expectedAction = null;
        pendingConfirmation = null;
        signedFingerprint = null;
        gateReason = null;
        // Only definitively uncommitted sessions of this app are reclaimed.
        abandonOrphans(app);
        publish(Stage.PREPARING, "Verifying update", 0);
        io.execute(() -> prepareWorker(app, t, gen));
    }

    private void prepareWorker(Context app, Target t, int gen) {
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        int id = -1;
        try {
            if (stale(gen)) return;
            File apk = verifiedApk(app, t, gen);
            if (stale(gen)) return;
            String fingerprint = signerFingerprint(app, apk);
            if (stale(gen)) return;
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(app.getPackageName());
            params.setSize(t.bytes);
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(
                        PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
            }
            id = pi.createSession(params);
            try (PackageInstaller.Session session = pi.openSession(id);
                 OutputStream out = session.openWrite("base.apk", 0, t.bytes)) {
                copyExact(apk, out, t, gen);
                session.fsync(out);
            }
            final int done = id;
            final String sign = fingerprint;
            onMain(() -> {
                if (stale(gen)) { abandonQuietly(pi, done); return; }
                sessionId = done;
                signedFingerprint = sign;
                publish(Stage.PREPARED, "Ready to install", 0);
            });
        } catch (Cancelled c) {
            // The owner already moved on; drop only the session we made.
            abandonQuietly(pi, id);
        } catch (Exception e) {
            abandonQuietly(pi, id);
            final String failure = shortError(e);
            onMain(() -> {
                if (stale(gen)) return;
                publish(Stage.FAILED, failure, 0);
            });
        }
    }

    /**
     * Validate the target and the bytes on disk: canonical location under
     * {@code cacheDir/updates}, exact length, SHA-256, then package name,
     * version and signer identity against what is installed right now.
     * The source APK is only ever read.
     */
    @SuppressWarnings("deprecation")
    private File verifiedApk(Context app, Target t, int gen) throws IOException {
        if (!validDigest(t.sha256)) throw new IOException("Update digest is invalid");
        String self = app.getPackageName();
        if (!self.equals(t.packageName)) throw new IOException("Update is for another app");
        File root = new File(app.getCacheDir(), "updates").getCanonicalFile();
        File apk = new File(t.apkPath).getCanonicalFile();
        if (!apk.getPath().startsWith(root.getPath() + File.separator)) {
            throw new IOException("Update file is outside the update cache");
        }
        if (!apk.isFile() || apk.length() != t.bytes) {
            throw new IOException("Update file does not match the signed size");
        }
        if (!t.sha256.equalsIgnoreCase(sha256(apk, gen))) {
            throw new IOException("Update file digest does not match");
        }
        PackageManager pm = app.getPackageManager();
        PackageInfo installed;
        try {
            installed = pm.getPackageInfo(self, infoFlags());
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("Could not read the installed version");
        }
        if (t.versionCode <= version(installed)) {
            throw new IOException("Update is not newer than the installed app");
        }
        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getPath(), infoFlags());
        if (candidate == null || !self.equals(candidate.packageName)) {
            throw new IOException("Update package identity mismatch");
        }
        if (version(candidate) != t.versionCode) throw new IOException("Update version does not match");
        return apk;
    }

    /** Exact signer match between the staged APK and this installation. */
    @SuppressWarnings("deprecation")
    private String signerFingerprint(Context app, File apk) throws IOException {
        PackageManager pm = app.getPackageManager();
        PackageInfo installed;
        try {
            installed = pm.getPackageInfo(app.getPackageName(), infoFlags());
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("Could not read the installed signer");
        }
        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getPath(), infoFlags());
        if (installed == null || candidate == null) throw new IOException("Could not read APK identity");
        String self = fingerprint(installed);
        String other = fingerprint(candidate);
        if (self == null || other == null || !self.equals(other)) {
            throw new IOException("Update signer does not match this installation");
        }
        return self;
    }

    @SuppressWarnings("deprecation")
    private static String fingerprint(PackageInfo info) {
        if (info == null) return null;
        Signature[] signers;
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) return null;
            signers = info.signingInfo.getApkContentsSigners();
        } else {
            signers = info.signatures;
        }
        if (signers == null || signers.length != 1) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return UpdateManifest.hex(md.digest(signers[0].toByteArray()));
        } catch (Exception e) {
            return null;
        }
    }

    private static MessageDigest digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("Digest unavailable");
        }
    }

    private static boolean validDigest(String value) {
        return value != null && value.matches("[a-fA-F0-9]{64}");
    }

    /** Bounded, cancellable streaming hash of a file. */
    private String sha256(File f, int gen) throws IOException {
        MessageDigest md = digest();
        long total = 0;
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (stale(gen)) throw new Cancelled();
                total += n;
                if (total > MAX_APK_BYTES) throw new IOException("Update file is too large");
                md.update(buf, 0, n);
            }
        }
        return UpdateManifest.hex(md.digest());
    }

    /**
     * Copy exactly the announced size and digest the bytes that were
     * actually written, so a file swapped after verification cannot reach
     * the session.
     */
    private void copyExact(File src, OutputStream out, Target t, int gen) throws IOException {
        MessageDigest md = digest();
        byte[] buf = new byte[65536];
        long total = 0;
        try (InputStream in = new FileInputStream(src)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                if (stale(gen)) throw new Cancelled();
                if (total + n > t.bytes) throw new IOException("Update file changed while installing");
                md.update(buf, 0, n);
                out.write(buf, 0, n);
                total += n;
            }
        }
        if (total != t.bytes) throw new IOException("Update file is incomplete");
        if (!t.sha256.equalsIgnoreCase(UpdateManifest.hex(md.digest()))) {
            throw new IOException("Update file digest does not match");
        }
    }

    private boolean stale(int gen) { return cancelled || gen != generation; }

    private static final class Cancelled extends IOException {
        Cancelled() { super("cancelled"); }
    }

    // ------------------------------------------------------------------
    //  Commit (main-thread foreground owner only)
    // ------------------------------------------------------------------

    /** Commit when the given owner really is in the foreground. */
    boolean commitWhenForeground(Activity owner) {
        if (owner == null) return false;
        return commitWhenForeground(
                !owner.isFinishing() && !owner.isDestroyed() && owner.hasWindowFocus());
    }

    /**
     * Commit exactly once, from the resumed owner, after the permission and
     * VR-process gates are re-checked. Fails closed: called off the main
     * thread, not foreground, not {@link Stage#PREPARED}, or a closed gate
     * all return false without posting, without committing and without
     * republishing the stage. {@link #gateReason()} explains a closed gate.
     */
    boolean commitWhenForeground(boolean foreground) {
        if (!onMainThread()) return false;
        gateReason = null;
        if (!foreground) return false;
        if (stage != Stage.PREPARED || sessionId < 0) {
            gateReason = "No install session is ready";
            return false;
        }
        final Context app = appContext;
        if (app == null) return false;
        PackageManager pm = app.getPackageManager();
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            gateReason = "Allow installs from Vibertemis in Android settings, then try again";
            return false;
        }
        if (!vrIdle(app)) {
            gateReason = "Close your VR session before updating";
            return false;
        }
        final int id = sessionId;
        final String action = ACTION_PREFIX + app.getPackageName() + "." + id + "." + nonce;
        final Uri data = Uri.parse(URI_PREFIX + app.getPackageName() + "/" + nonce + "/" + id);
        PendingIntent callback;
        try {
            Intent explicit = new Intent(action)
                    .setPackage(app.getPackageName())
                    .setData(data)
                    .setClassName(app, RECEIVER);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            // Mutable on S+: Android fills in the status extras.
            callback = PendingIntent.getBroadcast(app, id, explicit, flags);
        } catch (Exception e) {
            abandonQuietly(pm.getPackageInstaller(), id);
            publish(Stage.FAILED, "Could not reach Android for the install result", 0);
            return false;
        }
        matchUri = data;
        expectedAction = action;
        try (PackageInstaller.Session session = pm.getPackageInstaller().openSession(id)) {
            publish(Stage.COMMITTED, "Waiting for Android to install", 0);
            session.commit(callback.getIntentSender());
            return true;
        } catch (Exception e) {
            Log.w("VibertemisUpdate", "PackageInstaller commit failed", e);
            matchUri = null;
            expectedAction = null;
            abandonQuietly(pm.getPackageInstaller(), id);
            publish(Stage.FAILED, "Android refused the install request", 0);
            return false;
        }
    }

    /**
     * Stop the flow with a clear error. Never pretends the update was
     * installed, and never replaces an already terminal stage.
     *
     * <p>A failure while we own the staging attempt invalidates the worker
     * generation and abandons only the session we made, so a slow worker can
     * neither publish {@link Stage#PREPARED} nor commit behind this terminal
     * stage. The verified cache copy is preserved for an explicit retry.
     */
    void fail(String message, int status) {
        final String m = message == null || message.trim().isEmpty()
                ? "Update could not be completed" : message;
        onMain(() -> {
            Stage s = stage;
            if (isTerminal(s)) return;
            if (s == Stage.PREPARING || s == Stage.PREPARED) {
                cancelled = true;
                generation++;
                if (s == Stage.PREPARED && sessionId >= 0 && appContext != null) {
                    abandonQuietly(appContext.getPackageManager().getPackageInstaller(), sessionId);
                    sessionId = -1;
                }
            }
            publish(Stage.FAILED, m, status);
        });
    }

    /**
     * Fail-closed VR gate: the streaming process list must be readable, and
     * neither {@code :pcvr} nor {@code :vr} may be running. Never blocks on
     * IO verification of package state.
     */
    static boolean vrIdle(Context app) {
        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        List<ActivityManager.RunningAppProcessInfo> procs;
        try {
            procs = am.getRunningAppProcesses();
        } catch (Throwable t) {
            return false;
        }
        if (procs == null) return false;
        String self = app.getPackageName();
        for (ActivityManager.RunningAppProcessInfo p : procs) {
            if (p == null || p.processName == null) continue;
            if ((self + ":pcvr").equals(p.processName)
                    || (self + ":vr").equals(p.processName)) return false;
        }
        return true;
    }

    /** Cancel while the attempt is still ours; never after commit. */
    boolean cancelBeforeCommit() {
        if (!onMainThread()) {
            main.post(this::cancelBeforeCommit);
            return false;
        }
        Stage s = stage;
        if (s != Stage.PREPARING && s != Stage.PREPARED) return false;
        cancelled = true;
        generation++;
        if (s == Stage.PREPARED && sessionId >= 0 && appContext != null) {
            abandonQuietly(appContext.getPackageManager().getPackageInstaller(), sessionId);
        }
        publish(Stage.CANCELLED, "Update cancelled", 0);
        return true;
    }

    /**
     * Reclaim only this app's own sessions that are provably uncommitted:
     * an active or sealed session may be an install this process already
     * committed, so it is left strictly alone.
     */
    private void abandonOrphans(Context app) {
        try {
            PackageInstaller pi = app.getPackageManager().getPackageInstaller();
            List<SessionInfo> sessions = pi.getMySessions();
            if (sessions == null) return;
            String self = app.getPackageName();
            for (SessionInfo s : sessions) {
                if (s == null) continue;
                int id = s.getSessionId();
                if (id == sessionId) continue;
                if (!self.equals(s.getAppPackageName())) continue;
                if (s.isActive() || s.isSealed()) continue;
                abandonQuietly(pi, id);
            }
        } catch (Throwable ignored) {
            // Nothing provably stale found; a new attempt still fails closed.
        }
    }

    private static void abandonQuietly(PackageInstaller pi, int id) {
        if (pi == null || id < 0) return;
        try {
            pi.abandonSession(id);
        } catch (Throwable ignored) {
            // Already gone, or not ours to abandon.
        }
    }

    private static boolean sessionExists(Context app, int id) {
        try {
            List<SessionInfo> sessions =
                    app.getPackageManager().getPackageInstaller().getMySessions();
            if (sessions == null) return false;
            for (SessionInfo s : sessions) {
                if (s != null && s.getSessionId() == id) return true;
            }
        } catch (Throwable ignored) {
            // Unknown; callers only ever report unknown, never success.
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Android status callbacks
    // ------------------------------------------------------------------

    /** Called by the receiver; the running instance handles it. */
    static void acceptStatus(Intent intent) { INSTANCE.handleStatus(intent); }

    @SuppressWarnings("deprecation")
    private void handleStatus(Intent intent) {
        if (intent == null) return;
        onMain(() -> {
            if (stage != Stage.COMMITTED && stage != Stage.CONFIRMATION) return;
            if (sessionId < 0 || matchUri == null || expectedAction == null) return;
            if (!expectedAction.equals(intent.getAction())) return;
            if (!matchUri.equals(intent.getData())) return;
            if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return;
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                if (stage != Stage.COMMITTED) return;
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) {
                    publish(Stage.FAILED, "Android sent an empty confirmation request", status);
                    return;
                }
                // Memory only: process death drops it, which is safe.
                pendingConfirmation = confirm;
                publish(Stage.CONFIRMATION, "Confirm the update in Android", 0);
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) {
                settleSuccess();
                return;
            }
            if (isFailureStatus(status)) {
                publish(Stage.FAILED, messageForStatus(status), status);
                return;
            }
            // Anything else is not a result we understand: fail visibly.
            publish(Stage.FAILED, "Android sent an unrecognized install result", status);
        });
    }

    private static boolean isFailureStatus(int status) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE:
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
            case PackageInstaller.STATUS_FAILURE_ABORTED:
            case PackageInstaller.STATUS_FAILURE_INVALID:
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_STORAGE:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return true;
            default:
                return false;
        }
    }

    /** Hand the confirmation Intent to the foreground owner, once. */
    Intent takeConfirmationIntent() {
        if (stage != Stage.CONFIRMATION) return null;
        Intent out = pendingConfirmation;
        pendingConfirmation = null;
        return out;
    }

    private void settleSuccess() {
        if (installedTargetReached(appContext)) {
            publish(Stage.SUCCEEDED, "Update installed", 0);
            return;
        }
        // Android said yes but nothing proves the app changed.
        publish(Stage.UNCONFIRMED, "Android reported success; check the installed version", 0);
    }

    /** The only proof helper: installed version at or above the target
     *  and the signer still equal to the captured fingerprint. */
    boolean installedTargetReached(Context app) {
        return app != null && target != null
                && installedTargetReached(app, target, signedFingerprint);
    }

    static boolean installedTargetReached(Context app, Target t, String expectedSigner) {
        if (app == null || t == null || t.packageName == null) return false;
        if (expectedSigner == null) return false;
        try {
            PackageInfo info = app.getPackageManager()
                    .getPackageInfo(t.packageName, infoFlags());
            if (info == null || version(info) < t.versionCode) return false;
            return expectedSigner.equals(fingerprint(info));
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Owner decision point after a confirmation or a process restart:
     * success only with installed-version plus captured-signer proof,
     * otherwise a bounded unknown.
     *
     * <p>A live {@link Stage#COMMITTED} or {@link Stage#CONFIRMATION} session
     * may also be resolved here, but only by the owner that committed it: the
     * pinned target must be the one we hold and the proof must use the signer
     * we captured. A failed, cancelled or already succeeded stage is never
     * overridden, and a newer target never displaces ours.
     */
    void reconcileAfterConfirmation(Context app, Target t, String expectedSigner) {
        if (app == null || t == null) return;
        final Context ctx = app.getApplicationContext();
        onMain(() -> {
            if (stage != null && stage != Stage.UNCONFIRMED
                    && stage != Stage.COMMITTED && stage != Stage.CONFIRMATION) return;
            if (stage == Stage.COMMITTED || stage == Stage.CONFIRMATION) {
                if (!sameTarget(target, t)) return;
                if (expectedSigner == null || !expectedSigner.equals(signedFingerprint)) return;
            }
            if (appContext == null) appContext = ctx;
            if (target == null) target = t;
            if (installedTargetReached(ctx, t, expectedSigner)) {
                publish(Stage.SUCCEEDED, "Update installed", 0);
            } else {
                publish(Stage.UNCONFIRMED,
                        "Android has not confirmed the update; check the installed version", 0);
            }
        });
    }

    /** Release identity of two pinned requests; the file copy may differ. */
    private static boolean sameTarget(Target a, Target b) {
        return a != null && b != null && a.packageName != null
                && a.packageName.equals(b.packageName)
                && a.versionCode == b.versionCode && a.bytes == b.bytes
                && a.sha256 != null && a.sha256.equals(b.sha256);
    }

    /**
     * After process recreation the committed session may still exist or may
     * be gone; either way the outcome is unknown, so say so. Never replays a
     * prepare and never claims an install.
     */
    void restoreAfterRecreation(Context app, int savedSessionId) {
        restoreAfterRecreation(app, savedSessionId, null, null);
    }

    /**
     * Overload for an owner that persisted the target and the captured
     * signer: the outcome is {@link Stage#SUCCEEDED} only when that proof
     * holds, otherwise {@link Stage#UNCONFIRMED} whether or not the session
     * still exists.
     */
    void restoreAfterRecreation(Context app, int savedSessionId, Target t,
            String expectedSigner) {
        if (app == null) return;
        final Context ctx = app.getApplicationContext();
        onMain(() -> {
            if (stage != null) return;
            appContext = ctx;
            if (t != null && target == null) target = t;
            if (installedTargetReached(ctx, t, expectedSigner)) {
                publish(Stage.SUCCEEDED, "Update installed", 0);
                return;
            }
            boolean present = savedSessionId > 0 && sessionExists(ctx, savedSessionId);
            publish(Stage.UNCONFIRMED, present
                    ? "An update was committed before Android restarted Vibertemis; "
                            + "its outcome is unknown"
                    : "Android restarted before this update finished; its outcome is unknown", 0);
        });
    }

    /** Plain message for a PackageInstaller failure status. */
    static String messageForStatus(int status) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "Not enough storage to install the update";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "This update is not compatible with this device";
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "Android rejected the update as invalid";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "Android reported a conflicting install";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "Android blocked the update";
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "Android aborted the update";
            default:
                return "Android could not install the update";
        }
    }

    // ------------------------------------------------------------------
    //  Plumbing
    // ------------------------------------------------------------------

    private static int infoFlags() {
        return Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static long version(PackageInfo info) {
        return info == null ? -1L
                : (Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode);
    }

    private static String shortError(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "Update could not be prepared" : m;
    }

    private boolean onMainThread() { return Looper.myLooper() == Looper.getMainLooper(); }

    private void onMain(Runnable r) {
        if (onMainThread()) r.run();
        else main.post(r);
    }

    /**
     * Main-thread only. Observer re-entry is queued instead of nested and
     * bounded by {@link #MAX_PUBLISH_HOPS}, so no observer can spin publish
     * or commit cycles.
     */
    private void publish(Stage next, String message, int status) {
        if (dispatching) {
            queuedStage = next;
            queuedMessage = message;
            queuedCode = status;
            return;
        }
        Stage s = next;
        String m = message;
        int c = status;
        for (int hop = 0; hop < MAX_PUBLISH_HOPS; hop++) {
            stage = s;
            msg = m;
            code = c;
            if (isTerminal(s)) pendingConfirmation = null;
            dispatching = true;
            try {
                for (Listener l : listeners) {
                    try { l.onChanged(s); } catch (Throwable ignored) { }
                }
            } finally {
                dispatching = false;
            }
            if (queuedStage == null) return;
            s = queuedStage;
            m = queuedMessage;
            c = queuedCode;
            queuedStage = null;
            queuedMessage = null;
            queuedCode = 0;
        }
    }

    /** Lowercase hex is expected from manifests; normalise defensively. */
    static String normalizeHex(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
