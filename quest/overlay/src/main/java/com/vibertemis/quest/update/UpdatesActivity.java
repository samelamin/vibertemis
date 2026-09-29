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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.*;

/** Idle 2D update flow; verified APK still requires the OS installation prompt. */
public final class UpdatesActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;
    private Button check, download, install, cancel;
    private UpdateTransport transport;
    private UpdateManifest available;
    private File apk;
    private boolean busy;
    private static final int SOURCE_PERMISSION=801, INSTALL=802;
    private File cache() { return new File(getCacheDir(),"updates"); }
    private String trustKey() throws IOException {
        try (InputStream in=getResources().openRawResource(R.raw.quest_update_key);
             ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[2048];int n;while((n=in.read(b))!=-1) out.write(b,0,n);
            return new String(out.toByteArray(),StandardCharsets.US_ASCII);
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
        check=button(layout,"Check for updates");download=button(layout,"Download update");install=button(layout,"Install update");cancel=button(layout,"Cancel download");
        button(layout,"Back").setOnClickListener(v->finish());
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
        check.setOnClickListener(v->checkUpdates());download.setOnClickListener(v->downloadUpdate());install.setOnClickListener(v->installUpdate());
        cancel.setOnClickListener(v->{if(transport!=null)transport.cancel();});
        status.setText("Installed version: "+installedVersion());render();
        // Restore only signed, locally verified downloads. No network/VR launch.
        runWork(()->{
            File manifest=new File(cache(),"quest-update.json"), sig=new File(cache(),"quest-update.json.sig"), file=new File(cache(),"update.apk");
            if (!manifest.isFile() || !sig.isFile() || !file.isFile()) return;
            if (manifest.length()>65536 || sig.length()!=384) throw new IOException("Cached update metadata invalid");
            UpdateManifest m=UpdateManifest.verify(Files.readAllBytes(manifest.toPath()),Files.readAllBytes(sig.toPath()),trustKey());
            verifyApk(file,m);
            ui(()->{available=m;apk=file;status.setText("Verified update "+m.version+" is ready to install.");});
        });
    }
    private Button button(LinearLayout layout,String text) {
        Button b=new Button(this);b.setText(text);b.setMinHeight((int)(52*getResources().getDisplayMetrics().density));
        layout.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;
    }
    private void render() {
        check.setEnabled(!busy);download.setEnabled(!busy && available!=null && apk==null);
        install.setEnabled(!busy && apk!=null);cancel.setVisibility(busy?View.VISIBLE:View.GONE);
    }
    private void ui(Runnable action) { runOnUiThread(()->{if(!isFinishing()&&!isDestroyed()){action.run();render();}}); }
    private interface Work { void run() throws Exception; }
    private void runWork(Work action) {
        if(busy)return;busy=true;render();
        worker.execute(()->{
            try { action.run(); }
            catch(Exception e) { ui(()->status.setText("Update unavailable: "+e.getMessage()+". Your installed app is unchanged.")); }
            finally { ui(()->{busy=false;}); }
        });
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
    private void checkUpdates() {
        status.setText("Checking signed Quest preview releases...");
        runWork(()->{
            ensureIdle();transport=new UpdateTransport(trustKey());
            UpdateManifest m=transport.checkForUpdate(installedVersion());
            ui(()->{available=m;apk=null;status.setText(m==null?"No newer compatible signed Quest preview is available.":"Update "+m.version+" available ("+(m.bytes/1048576)+" MB).");});
        });
    }
    private void downloadUpdate() {
        final UpdateManifest selected=available;if(selected==null)return;
        status.setText("Downloading and verifying "+selected.version+"...");
        runWork(()->{
            ensureIdle();
            // Reuse the exact signed bytes from this check, not mutable server metadata.
            byte[] manifest=transport.manifestBytes,signature=transport.signatureBytes;
            transport = new UpdateTransport(trustKey());
            transport.manifestBytes = manifest; transport.signatureBytes = signature;
            File result=transport.download(selected,cache());verifyApk(result,selected);
            Files.write(new File(cache(),"quest-update.json").toPath(),manifest);
            Files.write(new File(cache(),"quest-update.json.sig").toPath(),signature);
            ui(()->{apk=result;status.setText("Verified update ready. Choose Install update to open the Android confirmation.");});
        });
    }
    @SuppressWarnings("deprecation") private void verifyApk(File file,UpdateManifest m) throws Exception {
        UpdateTransport.verifyFile(file,m);
        PackageInfo own=ownPackage();
        if(m.sequence<=4 || m.versionCode<=version(own) || !m.packageName.equals(getPackageName()))throw new IOException("APK package/version mismatch or downgrade");
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
        final File file=apk;final UpdateManifest m=available;if(file==null||m==null)return;
        runWork(()->{
            ensureIdle();verifyApk(file,m);
            ui(()->{
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
            });
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==SOURCE_PERMISSION) {
            if(getPackageManager().canRequestPackageInstalls()) installUpdate();
            else status.setText("Install permission was not granted. Download kept; retry when ready.");
        } else if(request==INSTALL) status.setText(result==RESULT_OK?"Installation completed.":"Installation cancelled or unsuccessful. Verified download kept for retry.");
    }
    @Override protected void onDestroy() {
        if(transport!=null)transport.cancel();worker.shutdownNow();super.onDestroy();
    }
}
