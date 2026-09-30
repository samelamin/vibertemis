package com.vibertemis.quest.update;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import androidx.core.content.FileProvider;
import com.limelight.R;
import java.io.*;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.*;

/**
 * Idle 2D update flow; verified APK still requires the OS installation
 * prompt.
 *
 * <p>State is consumed from the app-scoped {@link UpdateRepository}.
 * The activity does NOT require an explicit Check button click — the
 * shared repository already carries whatever the most recent metadata
 * check produced. A "Check now" button remains so the user can force
 * a refresh through the throttle; "Download update" appears once
 * verified available metadata is present and no downloaded slot is
 * already ready for the same release; "Install update" appears once a
 * verified APK is on disk.
 *
 * <p>Activity lifecycle does not cancel the shared check. The
 * repository owns its executor; the activity only registers /
 * unregisters an observer.
 */
public final class UpdatesActivity extends Activity {
    private TextView status;
    private Button check, download, install, cancel;
    /** Volatile because {@link #onRepositoryUpdate} writes it on the
     *  repository's executor and the UI thread reads it from
     *  {@link #render()} / the click handlers. Also because the
     *  download worker thread publishes the transport here, and the
     *  UI thread reads it from {@link #onDestroy()} to cancel any
     *  in-flight network call. */
    private volatile UpdateTransport transport;
    /** Per-download cancel flag, volatile across UI/worker threads.
     *  Reset at the start of each download. */
    private volatile boolean downloadCancelled;
    /** Volatile for the same reason as {@link #transport}: the
     *  repository observer writes it on its executor, the UI thread
     *  reads it from render() and the action click handlers. */
    private volatile UpdateRepository.Snapshot current;
    private UpdateRepository repository;
    /** True while the shared repository is fetching metadata. Render
     *  uses this to surface the in-flight label. It does NOT cover
     *  download / install: those use {@link #actionKind} so the
     *  download / install progress and errors are never overwritten
     *  by a generic "Checking…" label. */
    private boolean metadataChecking;
    /** Active local operation: "check", "download", "install",
     *  null when idle. Cleared when the operation TERMINATES
     *  (success OR failure) so the buttons re-enable; the
     *  completion-side error (if any) is preserved in
     *  {@link #actionError} until the user starts a new operation.
     *  This is the "active action busy" state and is intentionally
     *  separate from a "completed error operation" — the latter is
     *  expressed by {@code actionKind == null} AND
     *  {@code actionError != null}. */
    private String actionKind;
    /** Error from the most recently TERMINATED operation. Preserved
     *  across renders and across metadata check completions until
     *  the user starts a new operation. */
    private String actionError;
    /** Captures which operation produced {@link #actionError} so the
     *  "Tap X to retry" hint in the terminal-error state stays
     *  accurate even after {@link #actionKind} has been cleared. */
    private String lastFailedKind;
    /** Non-terminal result notice (permission denied, install
     *  cancelled, settings unavailable, ActivityNotFoundException).
     *  Preserved across renders and across onResume auto-checks so
     *  the next background metadata check never erases a
     *  still-visible result. Cleared by
     *  {@link #userInitiatedNewOperation}. Render shows it above any
     *  snapshot copy so the user always sees the outcome of their
     *  last install attempt. */
    private String resultNotice;
    private String actionInProgressText;
    private boolean observerRegistered;
    private boolean inflightWaiterAlive;
    private volatile boolean destroyed;
    private final UpdateRepository.Observer observer = this::onRepositoryUpdate;
    private static final int SOURCE_PERMISSION=801, INSTALL=802;
    private File cacheRoot() { return new File(getCacheDir(),"updates"); }

    /** Narrow injectable seam for {@link UpdateTransport} so the
     *  production code path (a real network transport) can be
     *  swapped for a fake in tests without editing UpdateTransport
     *  itself. The default factory constructs the package-private
     *  {@link UpdateTransport} directly; tests override via
     *  {@link #installTransportFactoryForTest}. */
    public interface TransportFactory {
        UpdateTransport create(String trustedKey);
    }

    private static volatile TransportFactory transportFactory = new TransportFactory() {
        @Override public UpdateTransport create(String trustedKey) {
            return new UpdateTransport(trustedKey);
        }
    };

    /** Replace the static {@link TransportFactory} seam. Tests pass a
     *  fake that records creation / download calls so the destroy-
     *  before-publication path can be asserted without hitting the
     *  real network. */
    static void installTransportFactoryForTest(TransportFactory f) {
        transportFactory = f;
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

    /** Replace the {@link #trustKeyProvider} for tests that exercise
     *  the download worker. The default loads the embedded PEM from
     *  {@code R.raw.quest_update_key}, which is unavailable under
     *  Robolectric unless the test adds the raw resource. */
    static void installTrustKeyProviderForTest(java.util.function.Function<UpdatesActivity, String> provider) {
        trustKeyProvider = provider;
    }

    private String trustKey() throws IOException {
        return trustKeyProvider.apply(this);
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout=new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(24*getResources().getDisplayMetrics().density);layout.setPadding(pad,pad,pad,pad);
        TextView heading=new TextView(this);heading.setText("App updates");heading.setTextSize(26);layout.addView(heading);
        TextView description=new TextView(this);description.setText("Quest preview updates are checked before installation. Close your game first. Android will ask you to confirm installation. Update your Windows host separately from its manager.");
        description.setTextSize(17);layout.addView(description);
        status=new TextView(this);status.setTextSize(17);layout.addView(status);
        check=button(layout,"Check now");download=button(layout,"Download update");install=button(layout,"Install update");cancel=button(layout,"Cancel download");
        button(layout,"Back").setOnClickListener(v->finish());
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        check.setOnClickListener(v->{ userInitiatedNewOperation(); triggerCheck(true); });
        download.setOnClickListener(v->{ userInitiatedNewOperation(); downloadUpdate(); });
        install.setOnClickListener(v->{ userInitiatedNewOperation(); installUpdate(); });
        cancel.setOnClickListener(v->{downloadCancelled=true;if(transport!=null)transport.cancel();});
        status.setText("Installed version: "+installedVersion());
        repository = UpdateRepositoryProvider.get(getApplicationContext());
        if (repository != null) {
            repository.addObserver(observer);
            observerRegistered = true;
            UpdateRepository.Snapshot s = repository.snapshot();
            current = s;
            render();
            // Auto-trigger a metadata check on open. The repository
            // coalesces concurrent triggers onto the in-flight handle
            // and the same gate the hub uses on onResume, so opening
            // the updates screen never starts a second parallel
            // request. The Check now button stays available for a
            // forced refresh.
            if (repository.shouldRunByThrottle(false)) {
                triggerCheck(false);
            }
        } else {
            render();
        }
    }

    private Button button(LinearLayout layout,String text) {
        Button b=new Button(this);b.setText(text);b.setMinHeight((int)(52*getResources().getDisplayMetrics().density));
        layout.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;
    }

    private void onRepositoryUpdate(UpdateRepository.Snapshot s) {
        current = s;
        // Marshalling back to the UI thread is required because the
        // snapshot mutator runs on the repository's executor.
        if (destroyed) return;
        runOnUiThread(() -> {
            if (destroyed || isFinishing() || isDestroyed()) return;
            render();
        });
    }

    private void render() {
        UpdateRepository.Snapshot s = current;
        boolean busy = actionKind != null;
        boolean liveVr = isLiveVrRunning();
        // Live VR blocks download / install (the worker calls
        // ensureIdle() which throws), but it does NOT block the
        // actionKind != null check. We disable download / install
        // buttons in live VR so the user gets immediate feedback
        // instead of an "ensureIdle" failure surfaced as a download
        // error. The action error from a prior download is still
        // preserved until the user retries.
        boolean canDownload = !busy && !liveVr && s != null && s.hasAvailable() && !sameReleaseDownloaded(s);
        boolean canInstall = !busy && !liveVr && s != null && s.hasDownloaded();
        check.setEnabled(!busy);
        download.setEnabled(canDownload);
        install.setEnabled(canInstall);
        // The cancel button is reserved for the DOWNLOAD action.
        // Metadata check and OS install confirmation are not
        // cancellable from this screen: "check" is short and
        // controlled by the throttle, and "install" is already in
        // the system installer dialog.
        cancel.setVisibility(("download".equals(actionKind)) ? View.VISIBLE : View.GONE);
        // Operation-specific labels win over generic repository
        // metadata so a download or install never gets overwritten
        // by "Checking…". The error label MUST be shown even when
        // actionKind is null: that is the "completed error
        // operation" state — the operation terminated, buttons
        // re-enabled, but the user has not yet started a new
        // operation. We preserve the message across background
        // metadata checks so an auto-refresh never erases a
        // still-visible failure. The error label is always keyed
        // by lastFailedKind — that field is the only reliable
        // record of WHICH operation produced the visible message
        // once actionKind has been cleared. When a new action
        // starts (e.g. an auto-check) the previous failure still
        // wins on screen until the user starts a real new
        // operation (Download / Install / a user-pressed Check now).
        // resultNotice wins over every other branch so a still-visible
        // install / permission / settings outcome is never erased by
        // the next background metadata check, "Checking…" copy, or a
        // "Tap X to retry" overlap. The notice survives across
        // onResume auto-checks; it is cleared only by
        // userInitiatedNewOperation. VR guidance is appended when
        // the install / retry action is currently blocked by live VR.
        if (resultNotice != null) {
            status.setText(resultNotice + (liveVr ? " Close your VR session before updating." : ""));
            return;
        }
        if (actionError != null) {
            String failedKind = lastFailedKind != null ? lastFailedKind : actionKind;
            String base;
            if (actionKind == null) {
                base = actionKindLabel(failedKind) + " failed: " + actionError + ". Tap " + actionKindLabel(failedKind) + " to retry.";
            } else {
                base = actionKindLabel(failedKind) + " failed: " + actionError + ". Tap " + actionKindLabel(failedKind) + " to retry. " + actionKindLabel(actionKind) + " in progress…";
            }
            status.setText(base + vrSuffixFor(liveVr, failedKind));
            return;
        }
        if (actionKind != null) {
            status.setText(actionInProgressText != null
                    ? actionInProgressText
                    : actionKindLabel(actionKind) + " in progress…");
            return;
        }
        if (metadataChecking || (s != null && s.checking)) {
            // The shared repository sets snapshot.checking while its
            // executor actually runs the metadata fetch. The label
            // is reserved for ACTUAL metadata checks; it never
            // covers download / install (those use actionKind above).
            status.setText("Checking signed Quest preview releases...");
            return;
        }
        if (s == null) {
            status.setText("Installed version: " + installedVersion() + ". Not checked yet.");
            return;
        }
        if (s.lastError != null && !s.hasAvailable() && !s.hasDownloaded()) {
            status.setText("Update check unavailable: " + s.lastError + ". Your installed app is unchanged.");
            return;
        }
        if (s.hasDownloaded()) {
            UpdateManifest d = s.downloaded;
            if (liveVr) {
                status.setText("Verified update " + d.version + " is ready to install. Close your VR session to continue.");
                return;
            }
            if (s.hasNewerAvailable()) {
                status.setText("Update " + d.version + " ready to install. Newer update " + s.available.version + " is also available — tap Download to fetch it.");
            } else {
                status.setText("Verified update " + d.version + " is ready to install.");
            }
            return;
        }
        if (s.hasAvailable()) {
            if (liveVr) {
                status.setText("Update " + s.available.version + " available (" + UpdateRepository.formatBytes(s.available.bytes) + "). Close your VR session to download.");
                return;
            }
            status.setText("Update " + s.available.version + " available (" + UpdateRepository.formatBytes(s.available.bytes) + ").");
            return;
        }
        if (liveVr) {
            status.setText("Installed version: " + installedVersion() + ". Waiting for VR to close before checking.");
            return;
        }
        if (s.lastSuccessAtMs > 0) {
            status.setText("Installed version: " + installedVersion() + ". App is up to date.");
            return;
        }
        // No successful check has run yet, no available metadata, no
        // downloaded slot. The screen just opened; the shared
        // repository will run the check imminently. We never claim
        // "no newer available" before the first successful check.
        status.setText("Installed version: " + installedVersion() + ". Not checked yet.");
    }

    private static String actionKindLabel(String kind) {
        if ("download".equals(kind)) return "Download";
        if ("install".equals(kind)) return "Install";
        if ("check".equals(kind)) return "Update check";
        return "Update";
    }

    /** VR guidance suffix appended to an error / result message when
     *  the corresponding button is blocked by live VR. The buttons
     *  check live VR independently of actionKind; the hint must
     *  match the action the user is being asked to retry. */
    private static String vrSuffixFor(boolean liveVr, String kind) {
        if (!liveVr) return "";
        if ("download".equals(kind)) return " Close your VR session to download.";
        if ("install".equals(kind)) return " Close your VR session to install.";
        return "";
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

    private static boolean sameReleaseDownloaded(UpdateRepository.Snapshot s) {
        return s.hasDownloaded() && s.hasAvailable()
                && s.available.version.equals(s.downloaded.version)
                && s.available.versionCode == s.downloaded.versionCode
                && s.available.sequence == s.downloaded.sequence;
    }

    private void ensureIdle() throws IOException {
        ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
        if (manager == null) throw new IOException("Could not query running processes to verify VR is closed");
        List<ActivityManager.RunningAppProcessInfo> processes=manager.getRunningAppProcesses();
        if(processes==null)throw new IOException("Could not confirm VR is closed");
        for(ActivityManager.RunningAppProcessInfo p:processes)
            if((getPackageName()+":pcvr").equals(p.processName))throw new IOException("Close your VR session before updating");
    }

    @SuppressWarnings("deprecation") private PackageInfo ownPackage() throws Exception {
        return getPackageManager().getPackageInfo(getPackageName(),Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
    }
    @SuppressWarnings("deprecation") private long version(PackageInfo info) { return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode; }
    private long installedVersion() { try{return version(ownPackage());}catch(Exception e){return Long.MAX_VALUE;} }

    /** Called from every user-facing button click (Check now,
     *  Download, Install). Starting a NEW user operation supersedes
     *  any prior terminal-error message so the new operation's
     *  progress label is visible immediately. Auto-check paths
     *  (onCreate / onResume) deliberately do NOT call this: an
     *  auto-triggered metadata check must not erase a still-visible
     *  failure from a download / install the user has not yet
     *  retried. */
    private void userInitiatedNewOperation() {
        actionError = null;
        lastFailedKind = null;
        resultNotice = null;
    }

    private void triggerCheck(boolean force) {
        if (actionKind != null) return;
        if (repository == null) {
            status.setText("Update check unavailable in this session.");
            return;
        }
        // Live VR session suppresses the check. The shared
        // repository will pick it up on the next idle onResume.
        if (!force && isLiveVrRunning()) {
            render();
            return;
        }
        // A background metadata check must NEVER erase the
        // visible failure from a previous download / install. The
        // user has not started a new operation; the auto-check on
        // open / onResume is an internal coalesced throttle tick.
        // Clearing actionError here would let the next repository
        // snapshot notification overwrite the "Download failed: …"
        // label with "Checking…" or "App is up to date.". Only the
        // user pressing Download / Install / Check now clears the
        // error (because that IS a new operation).
        actionKind = "check";
        actionInProgressText = "Checking for updates…";
        render();
        final UpdateRepository.InFlight inflight = repository.requestCheck(force);
        if (inflight == null) {
            actionKind = null;
            render();
            return;
        }
        inflightWaiterAlive = true;
        Thread waiter = new Thread(() -> {
            try { inflight.await(60_000L); } catch (InterruptedException ignored) { return; }
            finally { inflightWaiterAlive = false; }
            if (destroyed) return;
            runOnUiThread(() -> {
                if (destroyed || isFinishing() || isDestroyed()) return;
                if (actionKind != null && "check".equals(actionKind)) {
                    // The check itself succeeded (or failed via the
                    // repository, which would have updated the
                    // snapshot's lastError / lastSuccessAtMs). Clear
                    // the check actionKind so the buttons re-enable.
                    // We do NOT clear actionError here: a prior
                    // terminal download / install failure must
                    // remain visible until the user starts a new
                    // operation. The repository's snapshot
                    // notification will re-render, and render()
                    // shows actionError above any snapshot copy.
                    actionKind = null;
                    actionInProgressText = null;
                }
                render();
            });
        }, "UpdatesActivityCheckWaiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void downloadUpdate() {
        UpdateRepository.Snapshot snap = current == null ? repository == null ? null : repository.snapshot() : current;
        if (snap == null || !snap.hasAvailable()) return;
        if (actionKind != null) return;
        final UpdateManifest selected = snap.available;
        final byte[] manifest = snap.availableManifestBytes;
        final byte[] signature = snap.availableSignatureBytes;
        actionKind = "download";
        downloadCancelled = false;
        actionInProgressText = "Downloading update " + selected.version + "…";
        render();
        Thread worker = new Thread(() -> {
            UpdateTransport local = null;
            boolean cancelled = false;
            String terminalError = null;
            try {
                ensureIdle();
                // Build the transport LOCALLY first. We publish to
                // the activity field only after we know destroy()
                // was not already called. This is the "destroy
                // before assignment must prevent subsequent
                // network" guarantee: if the activity is destroyed
                // between the user pressing Download and this
                // thread running, onDestroy's transport.cancel()
                // sees a null field, so the network call must NOT
                // happen. We then publish (volatile write) and
                // re-check destroyed AFTER the publish — at that
                // point onDestroy (UI thread) will see our
                // published transport via the volatile read and
                // call cancel() on it; the download's first
                // check() iteration sees cancelled=true and throws.
                local = transportFactory.create(trustKey());
                local.manifestBytes = manifest;
                local.signatureBytes = signature;
                if (destroyed) {
                    // Destroy already fired before we could
                    // publish; do not publish, do not start the
                    // network. The activity is gone.
                    local.cancel();
                    return;
                }
                if (downloadCancelled) {
                    // Cancel raced with the create step but before
                    // publish — transport never became reachable
                    // from onDestroy / cancel-click.
                    local.cancel();
                    cancelled = true;
                    return;
                }
                // Atomic publish (volatile write).
                transport = local;
                if (destroyed) {
                    // Race: onDestroy fired between our destroyed
                    // check and our publish. Either:
                    //   - onDestroy read transport BEFORE our
                    //     publish (saw null, no cancel). We must
                    //     cancel ourselves here.
                    //   - onDestroy will read transport AFTER our
                    //     publish (volatile read) and cancel()
                    //     itself. Redundant but safe.
                    local.cancel();
                    throw new InterruptedIOException("Destroyed before download could start");
                }
                if (downloadCancelled) {
                    // Cancel raced with publish.
                    local.cancel();
                    cancelled = true;
                    return;
                }
                // transport.download writes the verified APK bytes to
                // <cacheRoot>/update.apk. We then verify digest +
                // signer on this file and pass the FILE PATH to the
                // repository, which streams it into the per-version
                // download directory with a bounded buffer (no whole
                // APK in memory).
                File result = local.download(selected, cacheRoot());
                verifyApk(result, selected);
                if (repository != null) {
                    // recordDownloaded:
                    //   - stream-copies the APK to per-version dir
                    //     with a 64 KiB buffer,
                    //   - verifies the digest during the copy,
                    //   - evicts the per-version dir on mismatch,
                    //   - persists manifest + signature only on
                    //     success,
                    //   - publishes the snapshot's downloaded slot
                    //     only when all of the above succeeded.
                    repository.recordDownloaded(selected, manifest, signature, result);
                }
                // Success — terminalError stays null; the unified
                // finally callback will clear actionKind below.
            } catch (Exception e) {
                if (destroyed) return;
                if (downloadCancelled) {
                    cancelled = true;
                    return;
                }
                terminalError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                // ALWAYS drop the transport reference before any
                // terminal UI callback runs, so a subsequent
                // download's worker cannot observe a stale
                // transport. The single runOnUiThread below is the
                // ONLY place that releases actionKind for this
                // worker — success / error / cancel are unified
                // here so the UI state machine transitions exactly
                // once per worker exit.
                transport = null;
                if (destroyed) return;
                final boolean finalCancelled = cancelled;
                final String finalTerminalError = terminalError;
                runOnUiThread(() -> {
                    if (destroyed || isFinishing() || isDestroyed()) return;
                    if (finalCancelled) {
                        if ("download".equals(actionKind)) {
                            actionKind = null;
                            actionInProgressText = null;
                        }
                    } else if (finalTerminalError != null) {
                        if ("download".equals(actionKind)) {
                            // Terminal failure: clear actionKind
                            // so buttons re-enable, but preserve
                            // actionError AND remember which
                            // operation failed so the "Tap X to
                            // retry" hint stays accurate.
                            actionError = finalTerminalError;
                            lastFailedKind = "download";
                            actionKind = null;
                            actionInProgressText = null;
                        }
                    } else {
                        // Terminal success: clear actionKind so
                        // buttons re-enable. actionError was
                        // already null from the click above.
                        actionKind = null;
                        actionInProgressText = null;
                    }
                    render();
                });
            }
        }, "UpdatesActivityDownload");
        worker.setDaemon(true);
        worker.start();
    }

    @SuppressWarnings("deprecation") private void verifyApk(File file,UpdateManifest m) throws Exception {
        UpdateTransport.verifyFile(file,m);
        PackageInfo own=ownPackage();
        if(m.sequence<= UpdateRepository.MIN_PUBLISHED_SEQUENCE || m.versionCode<=version(own) || !m.packageName.equals(getPackageName()))throw new IOException("APK package/version mismatch or downgrade");
        PackageInfo candidate=getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
        if(candidate==null || !candidate.packageName.equals(getPackageName()) || version(candidate)!=m.versionCode)throw new IOException("Downloaded APK identity mismatch");
        android.content.pm.Signature[] oldSigners=Build.VERSION.SDK_INT>=28?own.signingInfo.getApkContentsSigners():own.signatures;
        android.content.pm.Signature[] newSigners=Build.VERSION.SDK_INT>=28?candidate.signingInfo.getApkContentsSigners():candidate.signatures;
        if(oldSigners==null || newSigners==null || oldSigners.length!=1 || newSigners.length!=1)throw new IOException("Unsupported APK signer set");
        String oldHash=UpdateManifest.hex(MessageDigest.getInstance("SHA-256").digest(oldSigners[0].toByteArray()));
        String newHash=UpdateManifest.hex(MessageDigest.getInstance("SHA-256").digest(newSigners[0].toByteArray()));
        if(!oldHash.equals(newHash) || !newHash.equals(m.signer))throw new IOException("Downloaded APK signer does not match this installation");
    }

    private void installUpdate() {
        UpdateRepository.Snapshot snap = current == null ? repository == null ? null : repository.snapshot() : current;
        final File file = snap == null ? null : snap.downloadedApk;
        final UpdateManifest m = snap == null ? null : snap.downloaded;
        if(file==null||m==null)return;
        if (actionKind != null) return;
        actionKind = "install";
        actionInProgressText = "Opening installer for " + m.version + "…";
        render();
        Thread worker = new Thread(() -> {
            try {
                ensureIdle();
                try {
                    verifyApk(file, m);
                } catch (Exception apkError) {
                    // ONLY on verifyApk failure do we evict the
                    // downloaded slot. The on-disk APK no longer
                    // matches the manifest / signer / version, so
                    // the user CANNOT install this file. We evict
                    // it via the existing exact-version API so the
                    // available metadata is preserved verbatim and
                    // Download is re-enabled for a fresh fetch.
                    // Live VR (ensureIdle throws), source-permission
                    // denial, and system-installer errors do NOT
                    // take this branch; the downloaded slot is
                    // preserved for retry.
                    final String msg = apkError.getMessage() == null ? apkError.getClass().getSimpleName() : apkError.getMessage();
                    if (destroyed) return;
                    runOnUiThread(() -> {
                        if (destroyed || isFinishing() || isDestroyed()) return;
                        if (repository != null) {
                            repository.clearDownloadedAfterInstall(m.version);
                        }
                        // The retry action here is DOWNLOAD, not
                        // INSTALL — Install is now disabled because
                        // the downloaded slot is gone. Set a
                        // resultNotice with the prescribed text so
                        // the render never falls through to a
                        // "Tap Install to retry" hint. The
                        // underlying reason is preserved on
                        // actionError for diagnostics / tests.
                        resultNotice = "Downloaded update failed verification. Tap Download update to download again.";
                        actionError = msg;
                        lastFailedKind = null;
                        actionKind = null;
                        actionInProgressText = null;
                        render();
                    });
                    return;
                }
                if (destroyed) return;
                runOnUiThread(() -> {
                    if (destroyed || isFinishing() || isDestroyed()) return;
                    try {
                        ensureIdle();
                        if(!getPackageManager().canRequestPackageInstalls()) {
                            actionKind = null;
                            actionError = null;
                            actionInProgressText = null;
                            new AlertDialog.Builder(this).setTitle("Allow app updates")
                                .setMessage("Android requires permission for Vibertemis to open its update installer. Enable Allow from this source, then return here.")
                                .setPositiveButton("Open settings",(d,w)->{
                                    try {startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),SOURCE_PERMISSION);}
                                    catch(ActivityNotFoundException e){
                                        // Settings app is missing
                                        // entirely — there is no
                                        // "denied permission" state to
                                        // return to. Surface as a
                                        // result notice so it survives
                                        // the next auto-check render.
                                        resultNotice = "Open Android settings and allow installs from Vibertemis, then retry.";
                                        render();
                                    }
                                }).setNegativeButton("Cancel",null).show();
                            render();
                            return;
                        }
                        Uri uri=FileProvider.getUriForFile(this,getPackageName()+".updates",file);
                        Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true);
                        startActivityForResult(intent,INSTALL);
                        // Keep actionKind so the in-progress text
                        // remains visible until the system installer
                        // returns.
                    } catch(Exception e){
                        actionError = e.getMessage();
                        lastFailedKind = "install";
                        actionKind = null;
                        actionInProgressText = null;
                        render();
                    }
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (destroyed) return;
                runOnUiThread(() -> {
                    if (destroyed || isFinishing() || isDestroyed()) return;
                    if ("install".equals(actionKind)) {
                        // Terminal failure: clear actionKind so
                        // buttons re-enable, preserve actionError so
                        // the user can see why install did not start.
                        actionError = msg;
                        lastFailedKind = "install";
                        actionKind = null;
                        actionInProgressText = null;
                    }
                    render();
                });
            }
        }, "UpdatesActivityInstall");
        worker.setDaemon(true);
        worker.start();
    }

    @Override protected void onResume() {
        super.onResume();
        // Resume-driven idle auto-trigger: the shared repository
        // already coalesces concurrent triggers onto the in-flight
        // handle, so re-entering the screen after a pause (e.g.
        // closing the source-permission dialog or the system
        // installer) does NOT start a second parallel check.
        // Live VR still suppresses the trigger via triggerCheck's
        // gate; the same throttle and the live-VR deferral that
        // protect onCreate protect this path. We never trigger
        // while a local action (download / install) is running.
        if (actionKind == null) {
            triggerCheck(false);
            render();
        }
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==SOURCE_PERMISSION) {
            if(getPackageManager().canRequestPackageInstalls()) installUpdate();
            else {
                // Persist as resultNotice so the next onResume
                // auto-check render does not erase the "permission
                // was not granted" outcome.
                resultNotice = "Install permission was not granted. Download kept; retry when ready.";
                render();
            }
        } else if(request==INSTALL) {
            actionKind = null;
            actionError = null;
            actionInProgressText = null;
            if (result == RESULT_OK) {
                UpdateRepository.Snapshot s = current;
                if (s != null && s.hasDownloaded()) {
                    repository.clearDownloadedAfterInstall(s.downloaded.version);
                }
                resultNotice = "Installation completed.";
            } else {
                // Persist as resultNotice so the cancelled / failed
                // installer outcome survives the next onResume
                // auto-check render.
                resultNotice = "Installation cancelled or unsuccessful. Verified download kept for retry.";
            }
            render();
        }
    }

    @Override protected void onDestroy() {
        // Mark destroyed FIRST so any late executor callbacks short
        // circuit before they try to touch the UI or the repository
        // observer set. The retained observer field is the exact
        // same instance addObserver saw, so removeObserver matches.
        destroyed = true;
        // Set the per-download cancel flag BEFORE cancelling the
        // transport so a worker that reads the flag after
        // transport.cancel() still sees a cancelled state — without
        // this, a worker racing the onDestroy path could observe
        // destroyed but not downloadCancelled and take a branch
        // reserved for an in-flight cancel click.
        downloadCancelled = true;
        if (observerRegistered && repository != null) repository.removeObserver(observer);
        if(transport!=null)transport.cancel();
        super.onDestroy();
    }

    /** Visible for tests: returns the most recent text rendered on
     *  the inline status line. Package-private so lifecycle tests
     *  can assert the visibility ladder without spelunking the
     *  private TextView field. */
    String windowStatusText() { return status == null ? null : status.getText().toString(); }

    /** Visible for tests: package-private read-only views of the
     *  action state machine. Tests assert that download / install
     *  failures correctly transition to the terminal-error state
     *  (actionKind == null, actionError != null) so the buttons
     *  re-enable while the message stays visible. */
    String currentActionKindForTest() { return actionKind; }
    String currentActionErrorForTest() { return actionError; }
    String currentLastFailedKindForTest() { return lastFailedKind; }
    String currentResultNoticeForTest() { return resultNotice; }
    boolean isDownloadButtonEnabledForTest() { return download != null && download.isEnabled(); }
    boolean isInstallButtonEnabledForTest() { return install != null && install.isEnabled(); }
    boolean isCheckButtonEnabledForTest() { return check != null && check.isEnabled(); }
    int cancelVisibilityForTest() { return cancel == null ? View.GONE : cancel.getVisibility(); }
    /** Visible for tests: returns the volatile transport field
     *  without exposing the field itself. Lets a destroy-before-
     *  publication test assert that the activity never publishes
     *  a transport after destroy. */
    UpdateTransport publishedTransportForTest() { return transport; }
}
