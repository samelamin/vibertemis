package com.vibertemis.quest.update;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * App-scoped shared state for Quest preview updates.
 *
 * <p>The launch hub and {@link UpdatesActivity} consult this repository
 * rather than driving their own ad-hoc checks. The repository owns:
 * <ul>
 *   <li>the exact signed manifest / signature bytes from the most
 *       successful metadata check (the {@code available} slot),</li>
 *   <li>the verified cached APK and its bound manifest / signature
 *       bytes (the {@code downloaded} slot),</li>
 *   <li>throttle timestamps (last success / last failure),</li>
 *   <li>a coalesced in-flight check handle so concurrent triggers
 *       never duplicate work.</li>
 * </ul>
 *
 * <p>The two slots are independent. A successful background check
 * that finds a newer release replaces only the {@code available}
 * slot; the {@code downloaded} slot stays sticky so the user can
 * still install the previously verified release. Both slots are
 * bound to their exact signed bytes — the APK's digest must match
 * the manifest it was downloaded for, never a different manifest.
 *
 * <p>The repository does NOT cancel an in-flight check when an
 * observer unregisters or when an Activity is destroyed. The shared
 * executor survives Activity recreation; only an explicit
 * {@link #shutdown()} from the application lifecycle tears the
 * executor down.
 *
 * <p>Pure Java (no Android imports) so the state machine can be
 * unit-tested without Robolectric.
 */
public final class UpdateRepository {

    /**
     * Successful throttle: 30 minutes between metadata checks. The hub
     * checks on every resume past this window, so a release published
     * while the headset is on the shelf shows up the next time the app
     * is opened instead of up to six hours later. One small GitHub API
     * call per window stays well inside the unauthenticated rate limit.
     */
    public static final long SUCCESS_INTERVAL_MS = 30L * 60L * 1000L;
    /** Failure throttle: 15 minutes between retries after an error. */
    public static final long FAILURE_INTERVAL_MS = 15L * 60L * 1000L;

    /**
     * Current minimum published sequence (matches the upstream
     * SignedRelease.CurrentSequence). Releases at or below this
     * floor are considered "no newer release". Older installed
     * builds need one manual sideload to gain the updater.
     */
    public static final long MIN_PUBLISHED_SEQUENCE = 6L;

    /** Defensive cap on the manifest body (matches UpdateManifest). */
    public static final int MAX_MANIFEST_BYTES = 65536;
    /** Defensive cap on the signature length (matches UpdateManifest). */
    public static final int MAX_SIGNATURE_BYTES = 384;
    /** Stream-copy buffer for the APK: 64 KiB. */
    public static final int COPY_BUFFER_BYTES = 65536;

    /** Strongly-typed immutable snapshot of the repository state. */
    public static final class Snapshot {
        public final UpdateManifest available;
        public final byte[] availableManifestBytes;
        public final byte[] availableSignatureBytes;
        public final UpdateManifest downloaded;
        public final byte[] downloadedManifestBytes;
        public final byte[] downloadedSignatureBytes;
        public final File downloadedApk;
        public final long lastSuccessAtMs;
        public final long lastFailureAtMs;
        public final String lastError;
        public final boolean checking;

        Snapshot(UpdateManifest available, byte[] aBytes, byte[] aSig,
                 UpdateManifest downloaded, byte[] dBytes, byte[] dSig, File apk,
                 long lastSuccessAtMs, long lastFailureAtMs, String lastError, boolean checking) {
            this.available = available;
            this.availableManifestBytes = cloneBytes(aBytes);
            this.availableSignatureBytes = cloneBytes(aSig);
            this.downloaded = downloaded;
            this.downloadedManifestBytes = cloneBytes(dBytes);
            this.downloadedSignatureBytes = cloneBytes(dSig);
            this.downloadedApk = apk;
            this.lastSuccessAtMs = lastSuccessAtMs;
            this.lastFailureAtMs = lastFailureAtMs;
            this.lastError = lastError;
            this.checking = checking;
        }

        public boolean hasAvailable() { return available != null; }
        public boolean hasDownloaded() {
            if (downloaded == null || downloadedApk == null) return false;
            return downloadedApk.isFile();
        }
        /**
         * True when an installed-applicable available release is
         * newer (by versionCode, then sequence) than the currently
         * downloaded one. Used by the hub badge to surface "newer
         * available" alongside the ready-to-install candidate.
         */
        public boolean hasNewerAvailable() {
            if (!hasAvailable()) return false;
            if (!hasDownloaded()) return true;
            if (available.versionCode != downloaded.versionCode)
                return available.versionCode > downloaded.versionCode;
            return available.sequence > downloaded.sequence;
        }
        /**
         * Returns the manifest the user is most likely to act on:
         * the downloaded one if it is ready to install, otherwise
         * the available one (newer metadata check result).
         */
        public UpdateManifest primaryCandidate() {
            if (hasDownloaded()) return downloaded;
            if (hasAvailable()) return available;
            return null;
        }

        private static byte[] cloneBytes(byte[] in) { return in == null ? null : in.clone(); }
    }

    public interface Observer {
        void onUpdate(Snapshot snapshot);
    }

    /** Source of wall-clock time; injectable for tests. */
    public interface Clock {
        long now();
        Clock SYSTEM = new Clock() { @Override public long now() { return System.currentTimeMillis(); } };
    }

    /** Pluggable networking surface so tests can run without HTTP. */
    public interface CheckSource {
        Result check(long currentVersionCode) throws Exception;
        final class Result {
            public final UpdateManifest manifest;
            public final byte[] manifestBytes;
            public final byte[] signatureBytes;
            public Result(UpdateManifest m, byte[] mBytes, byte[] sigBytes) {
                this.manifest = m; this.manifestBytes = mBytes; this.signatureBytes = sigBytes;
            }
            public static Result none() { return new Result(null, null, null); }
        }
    }

    /** Persistent storage for the available and downloaded slots. */
    public interface Cache {
        /** Returns null when no available slot exists or the file
         *  exceeds {@link #MAX_MANIFEST_BYTES} (cap enforced before
         *  buffering). */
        byte[] readAvailableManifestBytes() throws IOException;
        byte[] readAvailableSignatureBytes() throws IOException;
        /** Persist the available slot's signed bytes atomically. */
        void persistAvailable(byte[] manifestBytes, byte[] signatureBytes) throws IOException;

        File downloadedRoot();
        File downloadedManifestFile(String version);
        File downloadedSignatureFile(String version);
        File downloadedApkFile(String version);
        byte[] readDownloadedManifestBytes(String version) throws IOException;
        byte[] readDownloadedSignatureBytes(String version) throws IOException;

        /**
         * Stream-copy the verified APK from {@code sourceFile} into
         * the per-version download directory. The implementation
         * MUST fsync the temp file, atomically rename it into place,
         * and return the SHA-256 digest of the persisted APK bytes
         * so the caller can verify it against the manifest's signed
         * digest. The implementation MUST clean up the temp file on
         * failure and MUST leave the per-version directory untouched
         * on failure.
         *
         * <p>This method does NOT write the manifest / signature
         * files. The caller writes those only after the digest
         * matches, so a corrupt APK cannot leave a half-written
         * manifest lying around.
         *
         * @param version directory name (validated by the cache)
         * @param sourceFile already-fully-downloaded file path
         * @return lowercase hex SHA-256 digest of the persisted APK
         */
        String persistDownloadedApk(String version, File sourceFile) throws IOException;

        /** Persist the manifest + signature bytes for a verified
         *  download. Called AFTER {@link #persistDownloadedApk} has
         *  succeeded and the caller has confirmed the digest matches
         *  the signed metadata. */
        void persistDownloadedMetadata(String version, byte[] manifestBytes, byte[] signatureBytes) throws IOException;

        /** Delete a downloaded version's directory. */
        void deleteDownloaded(String version) throws IOException;
        /** List downloaded version directories (best-effort). */
        List<String> enumerateDownloadedVersions();
    }

    /** Provider of the trust key. */
    public interface TrustKeySource {
        String pem();
    }

    private final Cache cache;
    private final Clock clock;
    private final long currentVersionCode;
    private final TrustKeySource trustKey;
    private final Set<Observer> observers = new CopyOnWriteArraySet<>();
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(empty(false));
    private final AtomicReference<InFlight> inFlight = new AtomicReference<>();
    private volatile ExecutorService executor;
    private volatile CheckSource source;

    public UpdateRepository(Cache cache, Clock clock, long currentVersionCode, TrustKeySource trustKey) {
        this.cache = cache;
        this.clock = clock;
        this.currentVersionCode = currentVersionCode;
        this.trustKey = trustKey;
    }

    /**
     * Bind the executor and the metadata check source, then hydrate
     * the available and downloaded slots from disk on a background
     * thread. Hydration never blocks the UI; observers are notified
     * asynchronously when hydration completes.
     */
    public void bindExecutor(ExecutorService executor, CheckSource source) {
        this.executor = executor;
        this.source = source;
        executor.execute(this::hydrateFromCache);
    }

    /** Test-only convenience: a fresh single-thread executor. */
    public static ExecutorService newDefaultExecutor() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "UpdateRepository");
            t.setDaemon(true);
            return t;
        });
    }

    public Snapshot snapshot() { return snapshot.get(); }

    public void addObserver(Observer o) {
        if (o == null) return;
        observers.add(o);
        // Replay current state so the new observer can render immediately.
        Snapshot s = snapshot.get();
        try { o.onUpdate(s); } catch (Throwable ignored) { /* tolerant */ }
    }

    public void removeObserver(Observer o) { observers.remove(o); }

    public boolean shouldRunByThrottle(boolean force) {
        if (force) return true;
        Snapshot s = snapshot.get();
        long now = clock.now();
        // Failure beats success on a tie. The IN-MEMORY snapshot
        // (timestamps are not persisted to disk — the activity
        // history resets across process restarts and the throttle
        // re-arms on the next open) records the same timestamp for
        // both fields when a check finishes
        // (recordAvailable / recordNoNewerAvailable advance
        // success; recordFailure advances failure). When the latest
        // outcome was a failure, lastFailureAtMs equals or exceeds
        // lastSuccessAtMs AND lastError is non-null. We pick the
        // failure branch on a tie so a forced retry is not blocked
        // for the rest of the success window.
        boolean failureIsLatestOrTie =
                s.lastFailureAtMs >= s.lastSuccessAtMs && s.lastError != null;
        if (failureIsLatestOrTie) {
            return (now - s.lastFailureAtMs) >= FAILURE_INTERVAL_MS;
        }
        if (s.lastSuccessAtMs > 0) {
            return (now - s.lastSuccessAtMs) >= SUCCESS_INTERVAL_MS;
        }
        if (s.lastFailureAtMs > 0) {
            return (now - s.lastFailureAtMs) >= FAILURE_INTERVAL_MS;
        }
        return true;
    }

    /**
     * Request a metadata-only check. The throttle is consulted first
     * unless {@code force} is true. Concurrent callers are coalesced
     * onto the in-flight check.
     */
    public InFlight requestCheck(boolean force) {
        ensureBound();
        InFlight start = new InFlight(force);
        InFlight prior = inFlight.get();
        if (prior != null) {
            prior.bumpForce(force);
            return prior;
        }
        if (inFlight.compareAndSet(null, start)) {
            executor.execute(() -> runInFlight(start));
            return start;
        }
        InFlight racer = inFlight.get();
        if (racer != null) return racer;
        return requestCheck(force);
    }

    /**
     * Record a successful verification of the bytes for {@code m}
     * as the {@code available} slot. The {@code downloaded} slot
     * is preserved verbatim.
     */
    public synchronized void recordAvailable(UpdateManifest m, byte[] manifestBytes, byte[] signatureBytes) {
        if (m == null || manifestBytes == null || signatureBytes == null) {
            throw new IllegalArgumentException("Available record must carry manifest bytes");
        }
        long now = clock.now();
        Snapshot prev = snapshot.get();
        Snapshot next = new Snapshot(m, manifestBytes, signatureBytes,
                prev.downloaded, prev.downloadedManifestBytes, prev.downloadedSignatureBytes, prev.downloadedApk,
                now, prev.lastFailureAtMs, null, false);
        snapshot.set(next);
        notifyObservers();
    }

    /** No-newer-available: success, NOT failure. */
    public synchronized void recordNoNewerAvailable() {
        long now = clock.now();
        Snapshot prev = snapshot.get();
        Snapshot next = new Snapshot(prev.available, prev.availableManifestBytes, prev.availableSignatureBytes,
                prev.downloaded, prev.downloadedManifestBytes, prev.downloadedSignatureBytes, prev.downloadedApk,
                now, prev.lastFailureAtMs, null, false);
        snapshot.set(next);
        notifyObservers();
    }

    /** Record a failure (15-min throttle). */
    public synchronized void recordFailure(String message) {
        long now = clock.now();
        Snapshot prev = snapshot.get();
        Snapshot next = new Snapshot(prev.available, prev.availableManifestBytes, prev.availableSignatureBytes,
                prev.downloaded, prev.downloadedManifestBytes, prev.downloadedSignatureBytes, prev.downloadedApk,
                prev.lastSuccessAtMs, now, message, false);
        snapshot.set(next);
        notifyObservers();
    }

    /**
     * Record a verified download. The implementation streams
     * {@code sourceFile} into the per-version download directory
     * (atomic fsync + rename), hashes it during the copy, and only
     * persists the manifest + signature bytes once the digest
     * matches {@code m.sha256}.
     *
     * <p>If the digest does not match (or the stream-copy fails for
     * any reason), the per-version directory is evicted and the
     * snapshot's downloaded slot is left untouched, so a previously
     * verified download continues to be ready to install.
     *
     * <p>The available slot is preserved.
     *
     * @return the immutable per-version copy the download was bound to.
     *         That path, not the caller's source file, is what the
     *         installer must be handed: a shared staging file can be
     *         overwritten by another attempt while Android holds the
     *         bytes.
     */
    public synchronized File recordDownloaded(UpdateManifest m, byte[] manifestBytes, byte[] signatureBytes, File sourceFile) {
        if (m == null || manifestBytes == null || signatureBytes == null || sourceFile == null) {
            throw new IllegalArgumentException("Downloaded record must carry manifest bytes and a source file");
        }
        if (!sourceFile.isFile()) {
            throw new IllegalArgumentException("Downloaded source file is missing");
        }
        if (sourceFile.length() != m.bytes) {
            // Length mismatch means the source is wrong; do NOT
            // overwrite the per-version slot. The previously verified
            // download remains ready to install.
            throw new IllegalArgumentException("Downloaded source length does not match signed size");
        }
        String actualSha;
        try {
            actualSha = cache.persistDownloadedApk(m.version, sourceFile);
        } catch (IOException ioe) {
            throw new RuntimeException("Failed to persist downloaded APK: " + ioe.getMessage(), ioe);
        }
        if (!actualSha.equalsIgnoreCase(m.sha256)) {
            // Persisted file's digest does not match the signed
            // manifest. Evict the half-written directory; do not
            // touch the previously-verified download (if any).
            try { cache.deleteDownloaded(m.version); } catch (IOException ignored) { }
            throw new IllegalArgumentException("Downloaded APK digest does not match signed metadata");
        }
        try {
            cache.persistDownloadedMetadata(m.version, manifestBytes, signatureBytes);
        } catch (IOException ioe) {
            // Manifest/sig write failed after the APK was placed.
            // Evict to keep the cache consistent; surface the failure
            // so the UI can prompt a retry.
            try { cache.deleteDownloaded(m.version); } catch (IOException ignored) { }
            throw new RuntimeException("Failed to persist downloaded metadata: " + ioe.getMessage(), ioe);
        }
        long now = clock.now();
        Snapshot prev = snapshot.get();
        File apkFile = cache.downloadedApkFile(m.version);
        Snapshot next = new Snapshot(prev.available, prev.availableManifestBytes, prev.availableSignatureBytes,
                m, manifestBytes, signatureBytes, apkFile,
                now, prev.lastFailureAtMs, null, false);
        snapshot.set(next);
        notifyObservers();
        return apkFile;
    }

    /**
     * Forget the downloaded slot. Used after a confirmed install.
     */
    public synchronized void clearDownloadedAfterInstall(String version) {
        if (version == null) return;
        Snapshot prev = snapshot.get();
        try { cache.deleteDownloaded(version); } catch (IOException ignored) { /* best effort */ }
        if (prev.downloaded != null && version.equals(prev.downloaded.version)) {
            Snapshot next = new Snapshot(prev.available, prev.availableManifestBytes, prev.availableSignatureBytes,
                    null, null, null, null,
                    prev.lastSuccessAtMs, prev.lastFailureAtMs, prev.lastError, prev.checking);
            snapshot.set(next);
            notifyObservers();
        }
    }

    private void ensureBound() {
        if (executor == null) {
            throw new IllegalStateException("UpdateRepository.bindExecutor must be called before use");
        }
        if (source == null) {
            throw new IllegalStateException("UpdateRepository.bindExecutor must set a CheckSource");
        }
    }

    /**
     * Hydrate available + downloaded slots from disk. Runs on the
     * shared executor (background, not UI). Each slot is verified
     * independently; a slot with corrupt metadata or a mismatched
     * APK digest is rejected and (for downloaded) its directory is
     * removed so the next check produces a clean re-download.
     */
    void hydrateFromCache() {
        Snapshot prev = snapshot.get();
        UpdateManifest available = null;
        byte[] aBytes = null, aSig = null;
        UpdateManifest downloaded = null;
        byte[] dBytes = null, dSig = null;
        File dApk = null;

        try {
            aBytes = cache.readAvailableManifestBytes();
            aSig = cache.readAvailableSignatureBytes();
            if (aBytes != null && aSig != null
                && aBytes.length > 0 && aBytes.length <= MAX_MANIFEST_BYTES
                && aSig.length == MAX_SIGNATURE_BYTES) {
                UpdateManifest m = UpdateManifest.verify(aBytes, aSig, trustKey.pem());
                if (m.sequence > MIN_PUBLISHED_SEQUENCE && m.versionCode > currentVersionCode) {
                    available = m;
                }
            }
        } catch (Exception ignored) {
            available = null; aBytes = null; aSig = null;
        }

        try {
            List<String> versions = cache.enumerateDownloadedVersions();
            Collections.sort(versions);
            for (int i = versions.size() - 1; i >= 0; i--) {
                String version = versions.get(i);
                byte[] mBytes = cache.readDownloadedManifestBytes(version);
                byte[] sBytes = cache.readDownloadedSignatureBytes(version);
                if (mBytes == null || sBytes == null) continue;
                if (mBytes.length <= 0 || mBytes.length > MAX_MANIFEST_BYTES) continue;
                if (sBytes.length != MAX_SIGNATURE_BYTES) continue;
                UpdateManifest m;
                try { m = UpdateManifest.verify(mBytes, sBytes, trustKey.pem()); }
                catch (Exception e) { cache.deleteDownloaded(version); continue; }
                if (m.sequence <= MIN_PUBLISHED_SEQUENCE || m.versionCode <= currentVersionCode) {
                    cache.deleteDownloaded(version);
                    continue;
                }
                File apk = cache.downloadedApkFile(version);
                if (apk == null || !apk.isFile() || apk.length() != m.bytes) {
                    cache.deleteDownloaded(version);
                    continue;
                }
                // Hash-verify the actual APK against the bound
                // manifest's signed digest before declaring the
                // download verified. This is the second-line check
                // that prevents stale-cache hydration from falsely
                // advertising an unverified download.
                try {
                    String hex = sha256HexFile(apk);
                    if (!hex.equalsIgnoreCase(m.sha256)) {
                        cache.deleteDownloaded(version);
                        continue;
                    }
                } catch (Exception e) {
                    cache.deleteDownloaded(version);
                    continue;
                }
                if (downloaded != null && (m.versionCode < downloaded.versionCode
                    || (m.versionCode == downloaded.versionCode && m.sequence <= downloaded.sequence))) continue;
                downloaded = m;
                dBytes = mBytes;
                dSig = sBytes;
                dApk = apk;
            }
        } catch (Exception ignored) {
            downloaded = null; dBytes = null; dSig = null; dApk = null;
        }

        Snapshot next = new Snapshot(available, aBytes, aSig,
                downloaded, dBytes, dSig, dApk,
                prev.lastSuccessAtMs, prev.lastFailureAtMs, prev.lastError, false);
        snapshot.set(next);
        notifyObservers();
    }

    private static String sha256HexFile(File f) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[COPY_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        return UpdateManifest.hex(md.digest());
    }

    private void notifyObservers() {
        Snapshot s = snapshot.get();
        for (Observer o : observers) {
            try { o.onUpdate(s); } catch (Throwable ignored) { /* tolerant */ }
        }
    }

    public void shutdown() {
        ExecutorService e = executor;
        executor = null;
        if (e == null) return;
        e.shutdownNow();
        try { e.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
    }

    /** Handle returned to callers so they can observe completion. */
    public final class InFlight {
        private volatile boolean force;
        private volatile boolean completed;
        private volatile Snapshot result;
        private volatile Throwable failure;
        InFlight(boolean force) { this.force = force; }
        void bumpForce(boolean f) { if (f) force = true; }
        public boolean isForce() { return force; }
        public synchronized boolean isCompleted() { return completed; }
        public synchronized Snapshot result() { return result; }
        public synchronized Throwable failure() { return failure; }
        synchronized void complete(Snapshot s) { this.completed = true; this.result = s; this.failure = null; notifyAll(); }
        synchronized void completeWithFailure(Throwable t) { this.completed = true; this.failure = t; this.result = snapshot.get(); notifyAll(); }
        public synchronized void await(long timeoutMs) throws InterruptedException {
            if (completed) return;
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!completed) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return;
                wait(remaining);
            }
        }
    }

    void runInFlight(InFlight job) {
        boolean forced = job.isForce();
        snapshot.set(withChecking(snapshot.get(), true));
        notifyObservers();
        try {
            if (!shouldRunByThrottle(forced)) {
                InFlight done = inFlight.getAndSet(null);
                if (done != null) done.complete(snapshot.get());
                return;
            }
            CheckSource s = this.source;
            if (s == null) {
                throw new IllegalStateException("no CheckSource bound");
            }
            CheckSource.Result result = s.check(currentVersionCode);
            if (result == null || result.manifest == null) {
                recordNoNewerAvailable();
            } else {
                if (result.manifest.sequence <= MIN_PUBLISHED_SEQUENCE
                    || result.manifest.versionCode <= currentVersionCode) {
                    recordNoNewerAvailable();
                } else {
                    cache.persistAvailable(result.manifestBytes, result.signatureBytes);
                    recordAvailable(result.manifest, result.manifestBytes, result.signatureBytes);
                }
            }
            InFlight done = inFlight.getAndSet(null);
            if (done != null) done.complete(snapshot.get());
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            recordFailure(msg);
            InFlight done = inFlight.getAndSet(null);
            if (done != null) done.completeWithFailure(t);
        } finally {
            snapshot.set(withChecking(snapshot.get(), false));
            notifyObservers();
        }
    }

    private Snapshot withChecking(Snapshot s, boolean checking) {
        return new Snapshot(s.available, s.availableManifestBytes, s.availableSignatureBytes,
                s.downloaded, s.downloadedManifestBytes, s.downloadedSignatureBytes, s.downloadedApk,
                s.lastSuccessAtMs, s.lastFailureAtMs, s.lastError, checking);
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 MB";
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    public static final List<Observer> NO_OBSERVERS = Collections.emptyList();

    private static Snapshot empty(boolean checking) {
        return new Snapshot(null, null, null, null, null, null, null, 0L, 0L, null, checking);
    }

    /** Visible for tests so they can inject a hydration result. */
    void hydrateForTest() { hydrateFromCache(); }

    /** Visible for tests so a lifecycle test can assert no
     *  observer leaks across an activity's destroy path. */
    int observerCountForTest() { return observers.size(); }
}
