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
    private UpdateTransport transport;
    private UpdateRepository.Snapshot current;
    private UpdateRepository repository;
    private boolean busy;
    private boolean observerRegistered;
    private boolean inflightWaiterAlive;
    private static final int SOURCE_PERMISSION=801, INSTALL=802;
    private File cacheRoot() { return new File(getCacheDir(),"updates"); }

    private String trustKey() throws IOException {
        try (InputStream in=getResources().openRawResource(R.raw.quest_update_key);
             ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1) out.write(b,0,n);
            return new String(out.toByteArray(),"US-ASCII");
        }
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
        check.setOnClickListener(v->triggerCheck(true));
        download.setOnClickListener(v->downloadUpdate());
        install.setOnClickListener(v->installUpdate());
        cancel.setOnClickListener(v->{if(transport!=null)transport.cancel();});
        status.setText("Installed version: "+installedVersion());
        repository = UpdateRepositoryProvider.get(getApplicationContext());
        if (repository != null) {
            repository.addObserver(this::onRepositoryUpdate);
            observerRegistered = true;
            onRepositoryUpdate(repository.snapshot());
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
        runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) render(); });
    }

    private void render() {
        UpdateRepository.Snapshot s = current;
        boolean canDownload = !busy && s != null && s.hasAvailable() && !sameReleaseDownloaded(s);
        boolean canInstall = !busy && s != null && s.hasDownloaded();
        check.setEnabled(!busy);
        download.setEnabled(canDownload);
        install.setEnabled(canInstall);
        cancel.setVisibility(busy?View.VISIBLE:View.GONE);
        if (busy) {
            status.setText("Checking signed Quest preview releases...");
            return;
        }
        if (s == null) {
            status.setText("Installed version: " + installedVersion() + ". Open to check for updates.");
            return;
        }
        if (s.lastError != null && !s.hasAvailable() && !s.hasDownloaded()) {
            status.setText("Update check unavailable: " + s.lastError + ". Your installed app is unchanged.");
            return;
        }
        if (s.hasDownloaded()) {
            UpdateManifest d = s.downloaded;
            if (s.hasNewerAvailable()) {
                status.setText("Update " + d.version + " ready to install. Update " + s.available.version +
                        " is also available — tap Check now to refresh, then Download to fetch it.");
            } else {
                status.setText("Verified update " + d.version + " is ready to install.");
            }
            return;
        }
        if (s.hasAvailable()) {
            status.setText("Update " + s.available.version + " available (" + UpdateRepository.formatBytes(s.available.bytes) + ").");
            return;
        }
        status.setText("Installed version: " + installedVersion() + ". No newer compatible signed Quest preview is available.");
    }

    private static boolean sameReleaseDownloaded(UpdateRepository.Snapshot s) {
        return s.hasDownloaded() && s.hasAvailable()
                && s.available.version.equals(s.downloaded.version)
                && s.available.versionCode == s.downloaded.versionCode
                && s.available.sequence == s.downloaded.sequence;
    }

    private void ensureIdle() throws IOException {
        ActivityManager manager=(ActivityManager)getSystemService(ACTIVITY_SERVICE);
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

    private void triggerCheck(boolean force) {
        if (busy) return;
        if (repository == null) {
            status.setText("Update check unavailable in this session.");
            return;
        }
        busy = true;
        render();
        final UpdateRepository.InFlight inflight = repository.requestCheck(force);
        inflightWaiterAlive = true;
        Thread waiter = new Thread(() -> {
            try { inflight.await(60_000L); } catch (InterruptedException ignored) { return; }
            finally { inflightWaiterAlive = false; }
            runOnUiThread(() -> { busy = false; render(); });
        }, "UpdatesActivityCheckWaiter");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void downloadUpdate() {
        UpdateRepository.Snapshot snap = current == null ? repository == null ? null : repository.snapshot() : current;
        if (snap == null || !snap.hasAvailable()) return;
        final UpdateManifest selected = snap.available;
        final byte[] manifest = snap.availableManifestBytes;
        final byte[] signature = snap.availableSignatureBytes;
        busy = true;
        render();
        Thread worker = new Thread(() -> {
            try {
                ensureIdle();
                transport = new UpdateTransport(trustKey());
                transport.manifestBytes = manifest;
                transport.signatureBytes = signature;
                // transport.download writes the verified APK bytes to
                // <cacheRoot>/update.apk. We then verify digest +
                // signer on this file and pass the FILE PATH to the
                // repository, which streams it into the per-version
                // download directory with a bounded buffer (no whole
                // APK in memory).
                File result = transport.download(selected, cacheRoot());
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
                runOnUiThread(() -> status.setText("Verified update ready. Choose Install update to open the Android confirmation."));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                runOnUiThread(() -> status.setText("Download failed: " + msg + ". Your installed app is unchanged."));
            } finally {
                runOnUiThread(() -> { busy = false; render(); });
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
        busy = true;
        render();
        Thread worker = new Thread(() -> {
            try {
                ensureIdle();verifyApk(file,m);
                runOnUiThread(() -> {
                    try {
                        ensureIdle();
                        if(!getPackageManager().canRequestPackageInstalls()) {
                            new AlertDialog.Builder(this).setTitle("Allow app updates")
                                .setMessage("Android requires permission for Vibertemis to open its update installer. Enable Allow from this source, then return here.")
                                .setPositiveButton("Open settings",(d,w)->{
                                    try {startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),SOURCE_PERMISSION);}
                                    catch(ActivityNotFoundException e){status.setText("Open Android settings and allow installs from Vibertemis, then retry.");}
                                }).setNegativeButton("Cancel",null).show();return;
                        }
                        Uri uri=FileProvider.getUriForFile(this,getPackageName()+".updates",file);
                        Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT,true);
                        startActivityForResult(intent,INSTALL);
                        status.setText("Confirm installation in the Android window. Cancelling keeps this version.");
                    } catch(Exception e){status.setText("Could not open installer: "+e.getMessage());}
                    finally { busy = false; render(); }
                });
            } catch (Exception e) {
                final String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                runOnUiThread(() -> { busy = false; status.setText("Install unavailable: "+msg+". Your installed app is unchanged."); render(); });
            }
        }, "UpdatesActivityInstall");
        worker.setDaemon(true);
        worker.start();
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==SOURCE_PERMISSION) {
            if(getPackageManager().canRequestPackageInstalls()) installUpdate();
            else status.setText("Install permission was not granted. Download kept; retry when ready.");
        } else if(request==INSTALL) {
            if (result == RESULT_OK) {
                UpdateRepository.Snapshot s = current;
                if (s != null && s.hasDownloaded()) {
                    repository.clearDownloadedAfterInstall(s.downloaded.version);
                }
                status.setText("Installation completed.");
            } else {
                status.setText("Installation cancelled or unsuccessful. Verified download kept for retry.");
            }
        }
    }

    @Override protected void onDestroy() {
        if (observerRegistered && repository != null) repository.removeObserver(this::onRepositoryUpdate);
        if(transport!=null)transport.cancel();
        super.onDestroy();
    }
}