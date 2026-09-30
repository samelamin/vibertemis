package com.vibertemis.quest.update;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * Production-side wiring for {@link UpdateRepository}: load the
 * embedded PEM from {@code R.raw.quest_update_key}, expose the
 * cache directory under the application's no-backup storage, and
 * drive the {@link UpdateTransport} for actual checks.
 *
 * <p>Cache layout under {@code <cacheDir>/updates/}:
 * <pre>
 *   available/
 *     manifest.json       latest verified metadata body
 *     manifest.sig        384-byte detached signature
 *   downloaded/
 *     &lt;version&gt;/         per-release verified download
 *       manifest.json
 *       manifest.sig
 *       update.apk
 * </pre>
 * The two slots are independent: a successful metadata check that
 * finds a newer release replaces only the {@code available} files;
 * the {@code downloaded} tree continues to be ready to install until
 * the user explicitly downloads the newer release or installs the
 * old one.
 */
public final class UpdateRepositoryBindings {

    private UpdateRepositoryBindings() { }

    /** Read the embedded PEM from the application resources. */
    public static String loadTrustedKey(Context context) throws IOException {
        try (InputStream in = context.getResources().openRawResource(
                com.limelight.R.raw.quest_update_key);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[2048];
            int n;
            while ((n = in.read(b)) != -1) out.write(b, 0, n);
            return new String(out.toByteArray(), StandardCharsets.US_ASCII);
        }
    }

    /** Disk-backed {@link UpdateRepository.Cache}. Read methods enforce
     *  the size cap before buffering so a corrupt cache cannot exhaust
     *  memory. The APK is never read whole into memory: persistDownloadedApk
     *  streams from the source file with a bounded 64 KiB buffer. */
    public static final class DiskCache implements UpdateRepository.Cache {
        private static final Pattern VERSION_DIR = Pattern.compile("[0-9]{1,5}(\\.[0-9]{1,5}){3}");
        private final File updatesDir;
        private final File availableDir;
        private final File downloadedDir;

        public DiskCache(File baseCacheDir) {
            this.updatesDir = new File(baseCacheDir, "updates");
            this.availableDir = new File(updatesDir, "available");
            this.downloadedDir = new File(updatesDir, "downloaded");
            ensureDir(updatesDir);
            ensureDir(availableDir);
            ensureDir(downloadedDir);
        }

        private File availableManifest() { return new File(availableDir, "manifest.json"); }
        private File availableSignature() { return new File(availableDir, "manifest.sig"); }

        @Override public byte[] readAvailableManifestBytes() throws IOException {
            return readCapped(availableManifest(), UpdateRepository.MAX_MANIFEST_BYTES);
        }
        @Override public byte[] readAvailableSignatureBytes() throws IOException {
            return readCapped(availableSignature(), UpdateRepository.MAX_SIGNATURE_BYTES);
        }

        @Override public void persistAvailable(byte[] manifestBytes, byte[] signatureBytes) throws IOException {
            File mf = availableManifest();
            File sf = availableSignature();
            File mfTmp = new File(mf.getParentFile(), mf.getName() + ".tmp");
            File sfTmp = new File(sf.getParentFile(), sf.getName() + ".tmp");
            try {
                writeAtomically(mf, mfTmp, manifestBytes, UpdateRepository.MAX_MANIFEST_BYTES);
                writeAtomically(sf, sfTmp, signatureBytes, UpdateRepository.MAX_SIGNATURE_BYTES);
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                mfTmp.delete();
                //noinspection ResultOfMethodCallIgnored
                sfTmp.delete();
                throw e;
            }
        }

        @Override public File downloadedRoot() { return downloadedDir; }

        @Override public File downloadedManifestFile(String version) {
            return new File(versionDir(version), "manifest.json");
        }
        @Override public File downloadedSignatureFile(String version) {
            return new File(versionDir(version), "manifest.sig");
        }
        @Override public File downloadedApkFile(String version) {
            return new File(versionDir(version), "update.apk");
        }

        @Override public byte[] readDownloadedManifestBytes(String version) throws IOException {
            return readCapped(downloadedManifestFile(version), UpdateRepository.MAX_MANIFEST_BYTES);
        }
        @Override public byte[] readDownloadedSignatureBytes(String version) throws IOException {
            return readCapped(downloadedSignatureFile(version), UpdateRepository.MAX_SIGNATURE_BYTES);
        }

        @Override
        public String persistDownloadedApk(String version, File sourceFile) throws IOException {
            if (sourceFile == null) throw new IOException("sourceFile is null");
            if (!sourceFile.isFile()) throw new IOException("sourceFile is not a regular file: " + sourceFile);
            File dir = versionDir(version);
            ensureDir(dir);
            File apk = downloadedApkFile(version);
            File apkTmp = new File(dir, "update.apk.tmp");
            String actualSha;
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                try (FileInputStream in = new FileInputStream(sourceFile);
                     FileOutputStream out = new FileOutputStream(apkTmp)) {
                    byte[] buf = new byte[UpdateRepository.COPY_BUFFER_BYTES];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        md.update(buf, 0, n);
                        out.write(buf, 0, n);
                    }
                    out.getFD().sync();
                }
                actualSha = UpdateManifest.hex(md.digest());
                try {
                    Files.move(apkTmp.toPath(), apk.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    try (FileOutputStream out = new FileOutputStream(apk)) {
                        Files.copy(apkTmp.toPath(), out);
                        out.getFD().sync();
                    }
                    //noinspection ResultOfMethodCallIgnored
                    apkTmp.delete();
                }
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                apkTmp.delete();
                throw e;
            } catch (NoSuchAlgorithmException e) {
                //noinspection ResultOfMethodCallIgnored
                apkTmp.delete();
                throw new IOException("SHA-256 unavailable", e);
            }
            return actualSha;
        }

        @Override
        public void persistDownloadedMetadata(String version, byte[] manifestBytes, byte[] signatureBytes) throws IOException {
            File dir = versionDir(version);
            ensureDir(dir);
            File mf = downloadedManifestFile(version);
            File sf = downloadedSignatureFile(version);
            File mfTmp = new File(dir, "manifest.json.tmp");
            File sfTmp = new File(dir, "manifest.sig.tmp");
            try {
                writeAtomically(mf, mfTmp, manifestBytes, UpdateRepository.MAX_MANIFEST_BYTES);
                writeAtomically(sf, sfTmp, signatureBytes, UpdateRepository.MAX_SIGNATURE_BYTES);
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                mfTmp.delete();
                //noinspection ResultOfMethodCallIgnored
                sfTmp.delete();
                throw e;
            }
        }

        @Override public void deleteDownloaded(String version) throws IOException {
            File dir = versionDir(version);
            if (!dir.exists()) return;
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            dir.delete();
        }

        @Override public List<String> enumerateDownloadedVersions() {
            File[] entries = downloadedDir.listFiles();
            if (entries == null) return Collections.emptyList();
            List<String> versions = new ArrayList<>(entries.length);
            for (File e : entries) {
                if (!e.isDirectory()) continue;
                String name = e.getName();
                if (!VERSION_DIR.matcher(name).matches()) continue;
                if (!new File(e, "manifest.json").isFile()) continue;
                if (!new File(e, "manifest.sig").isFile()) continue;
                if (!new File(e, "update.apk").isFile()) continue;
                versions.add(name);
            }
            return versions;
        }

        private File versionDir(String version) {
            if (version == null || !VERSION_DIR.matcher(version).matches()) {
                throw new IllegalArgumentException("Invalid version directory name: " + version);
            }
            return new File(downloadedDir, version);
        }

        private static void ensureDir(File d) {
            if (!d.isDirectory()) //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
        }

        /** Read a file while enforcing the cap. Returns null when the
         *  file does not exist or exceeds the cap (without
         *  buffering it). */
        private static byte[] readCapped(File f, int max) throws IOException {
            if (!f.isFile()) return null;
            if (f.length() > max) return null;
            try (FileInputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream((int) f.length());
                byte[] buf = new byte[4096];
                int n;
                int total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > max) return null;
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        }

        private static void writeAtomically(File target, File tmp, byte[] bytes, int max) throws IOException {
            if (bytes.length > max) throw new IOException("File exceeds cache cap");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
                out.getFD().sync();
            }
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                try (FileOutputStream out = new FileOutputStream(target)) {
                    out.write(Files.readAllBytes(tmp.toPath()));
                    out.getFD().sync();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /** Source that uses the existing {@link UpdateTransport} to drive
     *  a metadata-only check. */
    public static final class TransportCheckSource implements UpdateRepository.CheckSource {
        private final String trustedKey;
        public TransportCheckSource(String trustedKey) { this.trustedKey = trustedKey; }
        @Override public Result check(long currentVersionCode) throws Exception {
            UpdateTransport transport = new UpdateTransport(trustedKey);
            UpdateManifest m = transport.checkForUpdate(currentVersionCode);
            if (m == null) return Result.none();
            return new Result(m, transport.manifestBytes, transport.signatureBytes);
        }
    }

    /** Build a production repository bound to a shared executor and
     *  the supplied cache + key. */
    public static UpdateRepository create(Context appContext,
                                          ExecutorService executor,
                                          long currentVersionCode) throws IOException {
        String pem = loadTrustedKey(appContext);
        DiskCache cache = new DiskCache(appContext.getCacheDir());
        TransportCheckSource source = new TransportCheckSource(pem);
        UpdateRepository repo = new UpdateRepository(cache, UpdateRepository.Clock.SYSTEM,
                currentVersionCode, () -> pem);
        repo.bindExecutor(executor, source);
        return repo;
    }
}