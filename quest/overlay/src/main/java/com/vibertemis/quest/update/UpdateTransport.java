package com.vibertemis.quest.update;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/** Public HTTPS only; bounded redirects, bodies, deadlines, and cancellation. */
final class UpdateTransport {
    volatile boolean cancelled;
    private volatile HttpURLConnection active;
    private long deadline;
    final String trustedKey;
    byte[] manifestBytes, signatureBytes;
    UpdateTransport(String key) { trustedKey = key; }
    void cancel() { cancelled = true; HttpURLConnection c = active; if (c != null) c.disconnect(); }
    private void check() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new InterruptedIOException("Update cancelled or timed out");
    }
    private HttpURLConnection open(String address) throws Exception {
        URL url = new URL(address);
        for (int i=0; i<5; i++) {
            check(); String host = url.getHost();
            if (!url.getProtocol().equals("https") || (url.getPort() != -1 && url.getPort() != 443)
                    || url.getUserInfo() != null || url.getRef() != null
                    || !(host.equals("github.com") || host.equals("api.github.com") || host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com")))
                throw new IOException("Untrusted update redirect");
            HttpURLConnection c = (HttpURLConnection) url.openConnection(); active = c;
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "VibertemisQuest/0.1.0.8");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String next = c.getHeaderField("Location"); c.disconnect();
                if (next == null) throw new IOException("Invalid update redirect");
                url = new URL(url, next); continue;
            }
            if (code == 403 || code == 429) { c.disconnect(); throw new IOException("Update checks are temporarily rate limited. Try again later"); }
            if (code != 200) { c.disconnect(); throw new IOException("Update server returned HTTP " + code); }
            return c;
        }
        throw new IOException("Too many update redirects");
    }
    private byte[] read(String url, int max) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                check(); if (out.size()+count > max) throw new IOException("Update metadata too large");
                out.write(buffer,0,count);
            }
            return out.toByteArray();
        } finally { c.disconnect(); }
    }
    UpdateManifest checkForUpdate(long currentVersion) throws Exception {
        deadline = System.nanoTime() + 45_000_000_000L;
        JSONArray releases = new JSONArray(new String(read("https://api.github.com/repos/samelamin/vibertemis/releases?per_page=100", 2*1024*1024), StandardCharsets.UTF_8));
        UpdateManifest newest = null;
        for (int i=0; i<releases.length(); i++) {
            JSONObject r = releases.getJSONObject(i); String tag = r.getString("tag_name");
            if (r.optBoolean("draft") || !tag.matches("quest-preview-v[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}")) continue;
            JSONArray assets = r.getJSONArray("assets"); boolean hasManifest=false, hasSignature=false;
            for (int j=0;j<assets.length();j++) {
                String name = assets.getJSONObject(j).getString("name");
                hasManifest |= name.equals("quest-update.json"); hasSignature |= name.equals("quest-update.json.sig");
            }
            if (!hasManifest || !hasSignature) continue;
            byte[] body=read(UpdateManifest.PREFIX+tag+"/quest-update.json",65536);
            byte[] sig=read(UpdateManifest.PREFIX+tag+"/quest-update.json.sig",384);
            UpdateManifest m=UpdateManifest.verify(body,sig,trustedKey);
            if (!tag.equals("quest-preview-v"+m.version)) throw new IOException("Signed release tag mismatch");
            if (m.sequence > 6 && m.versionCode > currentVersion && (newest == null || m.sequence > newest.sequence)) {
                newest=m; manifestBytes=body; signatureBytes=sig;
            }
        }
        return newest;
    }
    File download(UpdateManifest m, File directory) throws Exception {
        deadline=System.nanoTime()+600_000_000_000L;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Update cache unavailable");
        File target=new File(directory,"update.apk");
        if (target.isFile()) { try { verifyFile(target,m); return target; } catch (IOException ignored) { } }
        File part=File.createTempFile("download-", ".part", directory);
        HttpURLConnection c=null;
        try {
            c=open(m.url);
            try (InputStream in=c.getInputStream(); FileOutputStream out=new FileOutputStream(part)) {
                byte[] buffer=new byte[65536]; long total=0; int count;
                while ((count=in.read(buffer))!=-1) {
                    check(); total+=count; if (total>m.bytes) throw new IOException("APK exceeds signed size");
                    out.write(buffer,0,count);
                }
                if (total!=m.bytes) throw new IOException("Incomplete APK download");
                out.getFD().sync();
            }
            verifyFile(part,m);
            Files.move(part.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally { if (c!=null) c.disconnect(); part.delete(); }
    }
    static void verifyFile(File file, UpdateManifest m) throws Exception {
        if (file.length()!=m.bytes) throw new IOException("APK size mismatch");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try (InputStream in=new FileInputStream(file)) {
            byte[] b=new byte[65536]; int n; while((n=in.read(b))!=-1) hash.update(b,0,n);
        }
        if (!UpdateManifest.hex(hash.digest()).equals(m.sha256)) throw new IOException("APK integrity check failed");
    }
}
