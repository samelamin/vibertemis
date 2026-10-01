package com.vibertemis.quest.update;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;

/** Public HTTPS only; bounded redirects, bodies, deadlines, and cancellation. */
final class UpdateTransport {
    /**
     * Opens the connection for an address that already passed the HTTPS
     * allowlist. Production goes through the JDK's ordinary
     * {@link URL#openConnection()}; tests inject an offline fake.
     */
    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws Exception;
    }

    private static final ConnectionFactory ORDINARY_CONNECTIONS = new ConnectionFactory() {
        @Override public HttpURLConnection open(URL url) throws Exception {
            return (HttpURLConnection) url.openConnection();
        }
    };

    private static final String RELEASES_URL =
            "https://api.github.com/repos/samelamin/vibertemis/releases?per_page=100";
    private static final String TAG_PREFIX = "quest-preview-v";
    private static final String NUMERIC_VERSION = "[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}";
    private static final String CHANNEL_SUFFIX = "-quest-preview.";
    private static final String MANIFEST_ASSET = "quest-update.json";
    private static final String SIGNATURE_ASSET = "quest-update.json.sig";

    volatile boolean cancelled;
    private volatile HttpURLConnection active;
    private long deadline;
    final String trustedKey;
    private final ConnectionFactory connections;
    /**
     * The release version this build already carries, as four numbers,
     * or null when the build's version name does not name a release
     * version at all. Only used to drop candidates that cannot be newer
     * than what is installed: the last component is a release ordinal,
     * never an Android versionCode.
     */
    private final int[] installedVersion;
    byte[] manifestBytes, signatureBytes;

    UpdateTransport(String key) {
        this(key, com.limelight.BuildConfig.VERSION_NAME, ORDINARY_CONNECTIONS);
    }

    /** Injected version name and connection factory, for tests. */
    UpdateTransport(String key, String currentVersionName, ConnectionFactory connections) {
        trustedKey = key;
        this.connections = connections;
        installedVersion = releaseVersion(currentVersionName);
    }
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
            HttpURLConnection c = connections.open(url); active = c;
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "VibertemisQuest/0.1.0.10");
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

    /**
     * Newest first. Ordering is numeric per component, so
     * {@code quest-preview-v0.1.0.10} sorts above
     * {@code quest-preview-v0.1.0.9} even though it sorts below it as
     * text.
     */
    private static final Comparator<Candidate> NEWEST_FIRST = new Comparator<Candidate>() {
        @Override public int compare(Candidate a, Candidate b) { return compareVersions(b.version, a.version); }
    };

    private static int compareVersions(int[] a, int[] b) {
        for (int i = 0; i < 4; i++) { if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1; }
        return 0;
    }

    /** A published release that could still carry a signed manifest. */
    private static final class Candidate {
        final String tag; final int[] version;
        Candidate(String tag, int[] version) { this.tag = tag; this.version = version; }
    }

    /**
     * Every published Quest release that has both signed-metadata
     * assets. A malformed entry is skipped rather than failing the
     * whole check: a release the updater could not read must not hide
     * the ones it can.
     */
    private static List<Candidate> candidates(JSONArray releases) {
        List<Candidate> found = new ArrayList<>();
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.optJSONObject(i);
            if (r == null || r.optBoolean("draft")) continue;
            String tag = r.optString("tag_name", null);
            int[] version = tagVersion(tag);
            if (version == null) continue;
            JSONArray assets = r.optJSONArray("assets");
            if (assets == null) continue;
            boolean hasManifest = false, hasSignature = false;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject a = assets.optJSONObject(j);
                if (a == null) continue;
                String name = a.optString("name", null);
                if (MANIFEST_ASSET.equals(name)) hasManifest = true;
                else if (SIGNATURE_ASSET.equals(name)) hasSignature = true;
            }
            if (hasManifest && hasSignature) found.add(new Candidate(tag, version));
        }
        return found;
    }

    /** The four numbers a Quest tag names, or null for anything else. */
    private static int[] tagVersion(String tag) {
        if (tag == null || !tag.startsWith(TAG_PREFIX)) return null;
        return numericVersion(tag.substring(TAG_PREFIX.length()));
    }

    /**
     * The release version a build's version name refers to, or null
     * when the name is not a known release version. Accepts both the
     * four-component normal version and the
     * {@code 0.1.0-quest-preview.10} channel suffix form.
     */
    static int[] releaseVersion(String versionName) {
        if (versionName == null) return null;
        String name = versionName.trim();
        int marker = name.indexOf(CHANNEL_SUFFIX);
        if (marker >= 0) name = name.substring(0, marker) + "." + name.substring(marker + CHANNEL_SUFFIX.length());
        return numericVersion(name);
    }

    private static int[] numericVersion(String value) {
        if (value == null || !value.matches(NUMERIC_VERSION)) return null;
        String[] parts = value.split("\\.");
        int[] version = new int[4];
        for (int i = 0; i < 4; i++) version[i] = Integer.parseInt(parts[i]);
        return version;
    }

    /**
     * Fetches the newest signed release that applies to this build and
     * returns it, or null when there is none.
     *
     * <p>Candidates are ordered newest first and the first verified
     * candidate that applies wins: once an applicable verified manifest
     * is found, no older candidate is visited, so a broken old release
     * can no longer consume the deadline or invalidate a good new
     * candidate. A newest candidate that
     * fails verification, or whose tag disagrees with its signed
     * version, still fails the check: that is a real integrity
     * problem, not a reason to fall back to older history.
     */
    UpdateManifest checkForUpdate(long currentVersion) throws Exception {
        deadline = System.nanoTime() + 45_000_000_000L;
        // Stale bytes from a previous run must never be readable as
        // this run's result, whether this run succeeds, finds nothing,
        // or throws.
        manifestBytes = null;
        signatureBytes = null;
        JSONArray releases = new JSONArray(new String(read(RELEASES_URL, 2*1024*1024), StandardCharsets.UTF_8));
        List<Candidate> ordered = candidates(releases);
        Collections.sort(ordered, NEWEST_FIRST);
        for (Candidate candidate : ordered) {
            if (installedVersion != null && compareVersions(candidate.version, installedVersion) <= 0) continue;
            byte[] body = read(UpdateManifest.PREFIX+candidate.tag+"/"+MANIFEST_ASSET, 65536);
            byte[] sig = read(UpdateManifest.PREFIX+candidate.tag+"/"+SIGNATURE_ASSET, 384);
            UpdateManifest m = UpdateManifest.verify(body, sig, trustedKey);
            if (!candidate.tag.equals(TAG_PREFIX + m.version)) throw new IOException("Signed release tag mismatch");
            if (m.sequence <= UpdateRepository.MIN_PUBLISHED_SEQUENCE || m.versionCode <= currentVersion) continue;
            manifestBytes = body; signatureBytes = sig;
            return m;
        }
        return null;
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
