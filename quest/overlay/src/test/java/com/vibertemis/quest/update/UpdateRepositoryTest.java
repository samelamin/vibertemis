package com.vibertemis.quest.update;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/**
 * Unit + integration tests for {@link UpdateRepository}.
 *
 * <p>The integration tests exercise the real DiskCache exactly the way
 * {@link UpdatesActivity} does: the test calls
 * {@link UpdateRepository#recordDownloaded} with a downloaded File,
 * which the repository then streams into the per-version cache
 * directory. We then construct a fresh repository against the same
 * cache (simulating a process restart) and verify hydration restores
 * the downloaded slot from disk without any hidden pre-population.
 *
 * <p>Unit tests cover throttle windows, in-flight coalescing, the
 * "no-newer available" success path, the available-vs-downloaded
 * slot independence, defensive cloning of snapshot byte arrays,
 * and the size caps enforced before any file is buffered.
 */
@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class UpdateRepositoryTest {
    private KeyPair key;
    private String pem;
    private File tmpRoot;

    @Before public void setup() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        key = generator.generateKeyPair();
        pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(key.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
        tmpRoot = new File(System.getProperty("java.io.tmpdir"), "vq-update-repo-" + UUID.randomUUID().toString());
        if (!tmpRoot.mkdirs()) throw new IOException("Could not create temp dir " + tmpRoot);
    }

    @After public void teardown() throws IOException {
        UpdateRepositoryProvider.reset();
        deleteRecursive(tmpRoot);
    }

    private static void deleteRecursive(File f) throws IOException {
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursive(c);
        }
        if (!f.delete()) throw new IOException("Could not delete " + f);
    }

    private static byte[] sha256(byte[] bytes) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(bytes);
    }

    private byte[] buildManifestBody(String version, long sequence, long versionCode, long apkBytes, String apkSha) throws Exception {
        JSONObject apk = new JSONObject()
                .put("filename", "vibertemis-quest-preview-" + version + ".apk")
                .put("url", UpdateManifest.PREFIX + "quest-preview-v" + version + "/vibertemis-quest-preview-" + version + ".apk")
                .put("bytes", apkBytes)
                .put("sha256", apkSha)
                .put("package", "com.vibertemis.quest.preview.debug")
                .put("version_code", versionCode)
                .put("signer_sha256", "0".repeat(64));
        return new JSONObject()
                .put("schema", 1)
                .put("channel", "quest-preview")
                .put("sequence", sequence)
                .put("version", version)
                .put("native_protocol", UpdateManifest.PROTOCOL)
                .put("assets", new JSONObject().put("android", apk))
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    private byte[] sign(byte[] body) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key.getPrivate());
        s.update(body);
        return s.sign();
    }

    private UpdateManifest manifest(String version, long sequence, long versionCode, long apkBytes, String apkSha) throws Exception {
        byte[] body = buildManifestBody(version, sequence, versionCode, apkBytes, apkSha);
        return UpdateManifest.verify(body, sign(body), pem);
    }

    /** Build the bytes the test would write to disk for a downloaded APK. */
    private byte[] apkBytesFor(String version, long apkBytes, byte[] seed) throws Exception {
        byte[] out = new byte[(int) apkBytes];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (seed[i % seed.length] ^ (i & 0xFF));
        }
        // Make sure the manifest's signed digest will be accurate
        String sha = UpdateManifest.hex(sha256(out));
        // Round-trip the manifest so the caller has the right digest
        // to put in the manifest body before calling recordDownloaded.
        // The test caller reads sha and substitutes it; we leave that
        // to the per-test driver.
        return out;
    }

    private UpdateRepositoryBindings.DiskCache freshCache() {
        return new UpdateRepositoryBindings.DiskCache(tmpRoot);
    }

    /** Throttle: a fresh repo with no history should run a check. */
    @Test public void freshRepositoryRunsByThrottle() {
        UpdateRepository repo = new UpdateRepository(freshCache(), () -> 0L, 6L, () -> pem);
        assertTrue(repo.shouldRunByThrottle(false));
        assertTrue(repo.shouldRunByThrottle(true));
    }

    @Test public void successThrottleBlocksForSixHours() throws Exception {
        FakeClock clock = new FakeClock();
        UpdateRepository repo = new UpdateRepository(freshCache(), clock, 6L, () -> pem);
        byte[] apkBytes = new byte[]{1, 2, 3, 4};
        String apkSha = UpdateManifest.hex(sha256(apkBytes));
        UpdateManifest m = manifest("0.1.0.7", 7, 7, apkBytes.length, apkSha);
        byte[] body = buildManifestBody("0.1.0.7", 7, 7, apkBytes.length, apkSha);
        byte[] sig = sign(body);
        repo.recordAvailable(m, body, sig);
        assertFalse(repo.shouldRunByThrottle(false));
        assertTrue(repo.shouldRunByThrottle(true));
        clock.now += UpdateRepository.SUCCESS_INTERVAL_MS + 1;
        assertTrue(repo.shouldRunByThrottle(false));
    }

    @Test public void failureThrottleBlocksForFifteenMinutes() {
        FakeClock clock = new FakeClock();
        UpdateRepository repo = new UpdateRepository(freshCache(), clock, 6L, () -> pem);
        repo.recordFailure("network unreachable");
        assertFalse(repo.shouldRunByThrottle(false));
        clock.now += UpdateRepository.FAILURE_INTERVAL_MS + 1;
        assertTrue(repo.shouldRunByThrottle(false));
    }

    @Test public void noNewerAvailableIsSuccess() throws Exception {
        FakeClock clock = new FakeClock();
        UpdateRepository repo = new UpdateRepository(freshCache(), clock, 6L, () -> pem);
        ExecutorService exec = UpdateRepository.newDefaultExecutor();
        try {
            repo.bindExecutor(exec, currentCode -> UpdateRepository.CheckSource.Result.none());
            UpdateRepository.InFlight f = repo.requestCheck(false);
            f.await(2000);
            UpdateRepository.Snapshot s = repo.snapshot();
            assertTrue("no-newer-available must advance success timestamp", s.lastSuccessAtMs > 0);
            assertNull(s.available);
            assertNull(s.lastError);
            assertFalse(repo.shouldRunByThrottle(false));
            assertEquals(0, s.lastFailureAtMs);
        } finally {
            repo.shutdown();
            exec.shutdownNow();
        }
    }

    @Test public void availableCheckPreservesDownloaded() throws Exception {
        FakeClock clock = new FakeClock();
        UpdateRepositoryBindings.DiskCache cache = freshCache();
        UpdateRepository repo = new UpdateRepository(cache, clock, 6L, () -> pem);
        // Real production handoff: write a temp APK file, then call
        // recordDownloaded with the file path. The repository
        // streams it into the per-version cache directory.
        String version = "0.1.0.7";
        byte[] apkBytes = new byte[]{1, 2, 3, 4};
        String apkSha = UpdateManifest.hex(sha256(apkBytes));
        UpdateManifest m = manifest(version, 7, 7, apkBytes.length, apkSha);
        byte[] body = buildManifestBody(version, 7, 7, apkBytes.length, apkSha);
        byte[] sig = sign(body);
        File sourceFile = new File(tmpRoot, "transport-output.apk");
        try (FileOutputStream out = new FileOutputStream(sourceFile)) { out.write(apkBytes); }
        repo.recordDownloaded(m, body, sig, sourceFile);
        UpdateRepository.Snapshot before = repo.snapshot();
        assertTrue("downloaded slot must be ready", before.hasDownloaded());

        UpdateManifest m8 = manifest("0.1.0.8", 8, 8, apkBytes.length, apkSha);
        byte[] m8body = buildManifestBody("0.1.0.8", 8, 8, apkBytes.length, apkSha);
        byte[] m8sig = sign(m8body);
        repo.recordAvailable(m8, m8body, m8sig);

        UpdateRepository.Snapshot after = repo.snapshot();
        assertTrue(after.hasDownloaded());
        assertEquals("0.1.0.7", after.downloaded.version);
        assertTrue(after.hasAvailable());
        assertEquals("0.1.0.8", after.available.version);
        assertEquals("0.1.0.7", after.primaryCandidate().version);
        assertTrue(after.hasNewerAvailable());
    }

    @Test public void recordDownloadedRejectsMismatchedDigest() throws Exception {
        UpdateRepository repo = new UpdateRepository(freshCache(), () -> 0L, 6L, () -> pem);
        // Manifest claims the APK has digest X, but the source file
        // has digest Y. recordDownloaded must evict the directory
        // and throw, leaving the previously downloaded slot (if any)
        // untouched.
        byte[] declared = new byte[]{1, 2, 3, 4};
        String declaredSha = UpdateManifest.hex(sha256(declared));
        UpdateManifest m = manifest("0.1.0.7", 7, 7, declared.length, declaredSha);
        byte[] body = buildManifestBody("0.1.0.7", 7, 7, declared.length, declaredSha);
        byte[] sig = sign(body);
        byte[] actual = new byte[]{9, 9, 9, 9};
        File source = new File(tmpRoot, "mismatch.apk");
        try (FileOutputStream out = new FileOutputStream(source)) { out.write(actual); }
        try {
            repo.recordDownloaded(m, body, sig, source);
            fail("Digest mismatch should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        File versionDir = new File(new File(tmpRoot, "updates"), "downloaded/0.1.0.7");
        assertFalse("half-written per-version dir must be evicted", versionDir.isDirectory());
    }

    @Test public void recordDownloadedRejectsMismatchedLength() throws Exception {
        UpdateRepository repo = new UpdateRepository(freshCache(), () -> 0L, 6L, () -> pem);
        byte[] apkBytes = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        String apkSha = UpdateManifest.hex(sha256(apkBytes));
        UpdateManifest m = manifest("0.1.0.7", 7, 7, apkBytes.length, apkSha);
        byte[] body = buildManifestBody("0.1.0.7", 7, 7, apkBytes.length, apkSha);
        byte[] sig = sign(body);
        File source = new File(tmpRoot, "wrong-length.apk");
        try (FileOutputStream out = new FileOutputStream(source)) { out.write(new byte[]{1, 2, 3}); }
        try {
            repo.recordDownloaded(m, body, sig, source);
            fail("Length mismatch should throw IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test public void concurrentRequestsCoalesce() throws Exception {
        FakeClock clock = new FakeClock();
        UpdateRepository repo = new UpdateRepository(freshCache(), clock, 6L, () -> pem);
        AtomicInteger invocations = new AtomicInteger();
        UpdateRepository.CheckSource source = code -> {
            invocations.incrementAndGet();
            try { Thread.sleep(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return UpdateRepository.CheckSource.Result.none();
        };
        ExecutorService exec = UpdateRepository.newDefaultExecutor();
        try {
            repo.bindExecutor(exec, source);
            UpdateRepository.InFlight a = repo.requestCheck(false);
            UpdateRepository.InFlight b = repo.requestCheck(false);
            UpdateRepository.InFlight c = repo.requestCheck(true);
            assertSame("all concurrent callers must share the same InFlight handle", a, b);
            assertSame(a, c);
            a.await(2000);
            assertEquals(1, invocations.get());
        } finally {
            repo.shutdown();
            exec.shutdownNow();
        }
    }

    @Test public void snapshotBytesAreDefensivelyCloned() throws Exception {
        FakeClock clock = new FakeClock();
        UpdateRepository repo = new UpdateRepository(freshCache(), clock, 6L, () -> pem);
        byte[] apkBytes = new byte[]{1, 2, 3, 4};
        String apkSha = UpdateManifest.hex(sha256(apkBytes));
        UpdateManifest m = manifest("0.1.0.7", 7, 7, apkBytes.length, apkSha);
        byte[] body = buildManifestBody("0.1.0.7", 7, 7, apkBytes.length, apkSha);
        byte[] sig = sign(body);
        repo.recordAvailable(m, body, sig);
        UpdateRepository.Snapshot s = repo.snapshot();
        body[0] ^= 1;
        sig[0] ^= 1;
        assertNotEquals("mutating the source bytes must NOT leak into the snapshot",
                body[0], s.availableManifestBytes[0]);
        assertNotEquals(sig[0], s.availableSignatureBytes[0]);
    }

    @Test public void hydrateRejectsStaleApk() throws Exception {
        FakeClock clock = new FakeClock();
        UpdateRepositoryBindings.DiskCache cache = freshCache();
        // Persist a downloaded release with mismatched APK bytes
        // using the same production handoff path. The cache will
        // write the APK, but the manifest claims a different digest.
        String version = "0.1.0.7";
        byte[] declared = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        String declaredSha = UpdateManifest.hex(sha256(declared));
        byte[] body = buildManifestBody(version, 7, 7, declared.length, declaredSha);
        byte[] sig = sign(body);
        File source = new File(tmpRoot, "stale-source.apk");
        try (FileOutputStream out = new FileOutputStream(source)) { out.write(new byte[]{9,9,9,9,9,9,9,9}); }
        try {
            cache.persistDownloadedApk(version, source);
            cache.persistDownloadedMetadata(version, body, sig);
        } catch (IOException expected) {
            // persistDownloadedApk succeeded (it only returns the
            // hash); persistDownloadedMetadata must succeed too.
            fail("Setup should not throw: " + expected.getMessage());
        }
        UpdateRepository repo = new UpdateRepository(cache, clock, 6L, () -> pem);
        ExecutorService exec = UpdateRepository.newDefaultExecutor();
        try {
            repo.bindExecutor(exec, code -> UpdateRepository.CheckSource.Result.none());
            Thread.sleep(200);
            UpdateRepository.Snapshot s = repo.snapshot();
            assertFalse("stale APK must not be advertised as downloaded", s.hasDownloaded());
            File dir = new File(new File(tmpRoot, "updates"), "downloaded/" + version);
            assertFalse("stale directory must be removed", dir.isDirectory());
        } finally {
            repo.shutdown();
            exec.shutdownNow();
        }
    }

    @Test public void hydrateKeepsValidApk() throws Exception {
        FakeClock clock = new FakeClock();
        UpdateRepositoryBindings.DiskCache cache = freshCache();
        String version = "0.1.0.7";
        byte[] apkBytes = new byte[]{1, 2, 3, 4};
        String apkSha = UpdateManifest.hex(sha256(apkBytes));
        byte[] body = buildManifestBody(version, 7, 7, apkBytes.length, apkSha);
        byte[] sig = sign(body);
        File source = new File(tmpRoot, "good-source.apk");
        try (FileOutputStream out = new FileOutputStream(source)) { out.write(apkBytes); }
        cache.persistDownloadedApk(version, source);
        cache.persistDownloadedMetadata(version, body, sig);
        UpdateRepository repo = new UpdateRepository(cache, clock, 6L, () -> pem);
        ExecutorService exec = UpdateRepository.newDefaultExecutor();
        try {
            repo.bindExecutor(exec, code -> UpdateRepository.CheckSource.Result.none());
            Thread.sleep(200);
            UpdateRepository.Snapshot s = repo.snapshot();
            assertTrue("valid cached APK must surface as downloaded", s.hasDownloaded());
            assertEquals(version, s.downloaded.version);
        } finally {
            repo.shutdown();
            exec.shutdownNow();
        }
    }

    @Test public void diskCacheSizeCapRejectsOversize() throws Exception {
        UpdateRepositoryBindings.DiskCache cache = new UpdateRepositoryBindings.DiskCache(tmpRoot);
        File dir = new File(tmpRoot, "updates/available");
        if (!dir.isDirectory()) assertTrue(dir.mkdirs());
        File big = new File(dir, "manifest.json");
        try (FileOutputStream out = new FileOutputStream(big)) {
            byte[] buf = new byte[UpdateRepository.MAX_MANIFEST_BYTES + 1];
            out.write(buf);
        }
        assertNull("oversize manifest must return null", cache.readAvailableManifestBytes());
    }

    @Test public void diskCachePersistRoundtrip() throws Exception {
        UpdateRepositoryBindings.DiskCache cache = new UpdateRepositoryBindings.DiskCache(tmpRoot);
        byte[] body = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
        byte[] sig = new byte[384];
        new java.security.SecureRandom().nextBytes(sig);
        cache.persistAvailable(body, sig);
        assertArrayEquals(body, cache.readAvailableManifestBytes());
        assertArrayEquals(sig, cache.readAvailableSignatureBytes());
    }

    @Test public void clearDownloadedAfterInstallPreservesAvailable() throws Exception {
        FakeClock clock = new FakeClock();
        UpdateRepository repo = new UpdateRepository(freshCache(), clock, 6L, () -> pem);
        byte[] apkBytes = new byte[]{1, 2, 3, 4};
        String apkSha = UpdateManifest.hex(sha256(apkBytes));
        UpdateManifest m7 = manifest("0.1.0.7", 7, 7, apkBytes.length, apkSha);
        byte[] m7body = buildManifestBody("0.1.0.7", 7, 7, apkBytes.length, apkSha);
        byte[] m7sig = sign(m7body);
        File source = new File(tmpRoot, "d7.apk");
        try (FileOutputStream out = new FileOutputStream(source)) { out.write(apkBytes); }
        repo.recordDownloaded(m7, m7body, m7sig, source);
        UpdateManifest m8 = manifest("0.1.0.8", 8, 8, apkBytes.length, apkSha);
        byte[] m8body = buildManifestBody("0.1.0.8", 8, 8, apkBytes.length, apkSha);
        byte[] m8sig = sign(m8body);
        repo.recordAvailable(m8, m8body, m8sig);
        repo.clearDownloadedAfterInstall("0.1.0.7");
        UpdateRepository.Snapshot s = repo.snapshot();
        assertFalse(s.hasDownloaded());
        assertTrue(s.hasAvailable());
        assertEquals("0.1.0.8", s.available.version);
    }

    @Test public void sourceThrowingIsFailure() throws Exception {
        FakeClock clock = new FakeClock();
        UpdateRepository repo = new UpdateRepository(freshCache(), clock, 6L, () -> pem);
        ExecutorService exec = UpdateRepository.newDefaultExecutor();
        try {
            repo.bindExecutor(exec, code -> { throw new IOException("network unreachable"); });
            UpdateRepository.InFlight f = repo.requestCheck(false);
            f.await(2000);
            UpdateRepository.Snapshot s = repo.snapshot();
            assertTrue(s.lastFailureAtMs > 0);
            assertEquals("network unreachable", s.lastError);
            assertFalse(repo.shouldRunByThrottle(false));
        } finally {
            repo.shutdown();
            exec.shutdownNow();
        }
    }

    @Test public void diskCacheDeleteRemovesDirectory() throws Exception {
        UpdateRepositoryBindings.DiskCache cache = new UpdateRepositoryBindings.DiskCache(tmpRoot);
        File source = new File(tmpRoot, "del.apk");
        try (FileOutputStream out = new FileOutputStream(source)) { out.write(new byte[]{1, 2}); }
        cache.persistDownloadedApk("0.1.0.7", source);
        File dir = new File(new File(tmpRoot, "updates"), "downloaded/0.1.0.7");
        assertTrue(dir.isDirectory());
        cache.deleteDownloaded("0.1.0.7");
        assertFalse(dir.isDirectory());
    }

    @Test public void diskCacheEnumerateFiltersIncomplete() throws Exception {
        UpdateRepositoryBindings.DiskCache cache = new UpdateRepositoryBindings.DiskCache(tmpRoot);
        // First release: complete (APK + manifest + signature).
        File source = new File(tmpRoot, "enum.apk");
        try (FileOutputStream out = new FileOutputStream(source)) { out.write(new byte[]{9, 9}); }
        cache.persistDownloadedApk("0.1.0.7", source);
        cache.persistDownloadedMetadata("0.1.0.7", new byte[]{1, 2, 3, 4}, new byte[384]);
        // Second release: only manifest, missing APK and signature.
        File incompleteDir = new File(new File(tmpRoot, "updates"), "downloaded/0.1.0.8");
        if (!incompleteDir.mkdirs()) throw new IOException("mkdirs failed");
        java.nio.file.Files.write(new File(incompleteDir, "manifest.json").toPath(), new byte[]{1, 2, 3, 4});
        List<String> versions = cache.enumerateDownloadedVersions();
        assertEquals(Collections.singletonList("0.1.0.7"), versions);
    }

    @Test public void removingObserverDoesNotCancelCheck() throws Exception {
        FakeClock clock = new FakeClock();
        UpdateRepository repo = new UpdateRepository(freshCache(), clock, 6L, () -> pem);
        AtomicInteger invocations = new AtomicInteger();
        ExecutorService exec = UpdateRepository.newDefaultExecutor();
        try {
            repo.bindExecutor(exec, code -> {
                invocations.incrementAndGet();
                try { Thread.sleep(150); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return UpdateRepository.CheckSource.Result.none();
            });
            repo.addObserver(s -> {});
            repo.removeObserver(s -> {});
            UpdateRepository.InFlight f = repo.requestCheck(false);
            f.await(2000);
            assertEquals(1, invocations.get());
        } finally {
            repo.shutdown();
            exec.shutdownNow();
        }
    }

    /** Real DiskCache end-to-end: writes a 1 MiB APK file from a
     *  source file, then verifies the digest matches what
     *  persistDownloadedApk returned, and the on-disk bytes equal
     *  the source bytes. */
    @Test public void diskCachePersistDownloadedApkStreamsLargeFile() throws Exception {
        UpdateRepositoryBindings.DiskCache cache = new UpdateRepositoryBindings.DiskCache(tmpRoot);
        // 1 MiB of pseudo-random bytes; deterministic.
        byte[] data = new byte[1024 * 1024];
        for (int i = 0; i < data.length; i++) data[i] = (byte)((i * 31 + 7) & 0xFF);
        File source = new File(tmpRoot, "big-source.bin");
        try (FileOutputStream out = new FileOutputStream(source)) { out.write(data); }
        String version = "0.1.0.7";
        String actualSha = cache.persistDownloadedApk(version, source);
        String expectedSha = UpdateManifest.hex(sha256(data));
        assertEquals("streamed digest must match", expectedSha, actualSha);
        File persisted = new File(new File(new File(tmpRoot, "updates"), "downloaded"), version + "/update.apk");
        assertTrue(persisted.isFile());
        assertEquals(data.length, persisted.length());
        // Read back via a SHA stream to confirm on-disk content matches.
        byte[] readBack = new byte[(int) persisted.length()];
        try (FileInputStream in = new FileInputStream(persisted)) {
            int total = 0;
            while (total < readBack.length) {
                int n = in.read(readBack, total, readBack.length - total);
                if (n < 0) break;
                total += n;
            }
            assertEquals(readBack.length, total);
        }
        assertArrayEquals(data, readBack);
    }

    /** Real end-to-end: a fresh repository, a transport-style
     *  downloaded file written to disk, then recordDownloaded +
     *  a fresh repository binding against the same cache (process
     *  restart). The downloaded slot must be restored by hydration
     *  alone, without any hidden pre-population. */
    @Test public void endToEndDownloadThenHydrateAfterRestart() throws Exception {
        // First "process": write a transport-style file, call
        // recordDownloaded, then shut down the executor.
        UpdateRepositoryBindings.DiskCache cache = freshCache();
        byte[] apkBytes = new byte[]{5, 6, 7, 8};
        String apkSha = UpdateManifest.hex(sha256(apkBytes));
        UpdateManifest m = manifest("0.1.0.7", 7, 7, apkBytes.length, apkSha);
        byte[] body = buildManifestBody("0.1.0.7", 7, 7, apkBytes.length, apkSha);
        byte[] sig = sign(body);
        File transportOutput = new File(tmpRoot, "transport-output.apk");
        try (FileOutputStream out = new FileOutputStream(transportOutput)) { out.write(apkBytes); }

        FakeClock clock = new FakeClock();
        UpdateRepository first = new UpdateRepository(cache, clock, 6L, () -> pem);
        ExecutorService exec1 = UpdateRepository.newDefaultExecutor();
        try {
            first.bindExecutor(exec1, code -> UpdateRepository.CheckSource.Result.none());
            // Give hydration a moment to run (it sees no per-version
            // dirs yet, so nothing changes here).
            Thread.sleep(50);
            first.recordDownloaded(m, body, sig, transportOutput);
            UpdateRepository.Snapshot s = first.snapshot();
            assertTrue("first process: downloaded must be ready", s.hasDownloaded());
            // The on-disk file at the snapshotted path must exist
            // and have the expected content.
            File expectedApk = cache.downloadedApkFile("0.1.0.7");
            assertEquals(expectedApk, s.downloadedApk);
            assertTrue(expectedApk.isFile());
            byte[] roundTrip = new byte[(int) expectedApk.length()];
            try (FileInputStream in = new FileInputStream(expectedApk)) {
                int total = 0;
                while (total < roundTrip.length) {
                    int n = in.read(roundTrip, total, roundTrip.length - total);
                    if (n < 0) break;
                    total += n;
                }
            }
            assertArrayEquals(apkBytes, roundTrip);
        } finally {
            first.shutdown();
            exec1.shutdownNow();
        }

        // Second "process": fresh repository, same cache. Hydration
        // alone must restore the downloaded slot.
        UpdateRepository second = new UpdateRepository(cache, clock, 6L, () -> pem);
        ExecutorService exec2 = UpdateRepository.newDefaultExecutor();
        try {
            second.bindExecutor(exec2, code -> UpdateRepository.CheckSource.Result.none());
            // Poll for hydration to complete.
            UpdateRepository.Snapshot s2 = null;
            for (int i = 0; i < 50; i++) {
                s2 = second.snapshot();
                if (s2.hasDownloaded()) break;
                Thread.sleep(50);
            }
            assertNotNull(s2);
            assertTrue("second process: hydration must restore downloaded slot", s2.hasDownloaded());
            assertEquals("0.1.0.7", s2.downloaded.version);
            assertEquals(7, s2.downloaded.versionCode);
        } finally {
            second.shutdown();
            exec2.shutdownNow();
        }
    }

    /** The persistDownloadedApk path must be safe against a
     *  tampered source: when the source digest does not match the
     *  manifest's signed digest, the per-version dir must be
     *  evicted by recordDownloaded's caller (the repository), not
     *  left half-written. */
    @Test public void digestMismatchEvictsDownloadDir() throws Exception {
        UpdateRepositoryBindings.DiskCache cache = new UpdateRepositoryBindings.DiskCache(tmpRoot);
        UpdateRepository repo = new UpdateRepository(cache, () -> 0L, 6L, () -> pem);
        byte[] declared = new byte[]{1, 2, 3, 4};
        String declaredSha = UpdateManifest.hex(sha256(declared));
        UpdateManifest m = manifest("0.1.0.7", 7, 7, declared.length, declaredSha);
        byte[] body = buildManifestBody("0.1.0.7", 7, 7, declared.length, declaredSha);
        byte[] sig = sign(body);
        // Source bytes are NOT what the manifest claims.
        File source = new File(tmpRoot, "tampered.apk");
        try (FileOutputStream out = new FileOutputStream(source)) { out.write(new byte[]{7, 7, 7, 7}); }
        try {
            repo.recordDownloaded(m, body, sig, source);
            fail("digest mismatch must throw");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        File dir = new File(new File(tmpRoot, "updates"), "downloaded/0.1.0.7");
        assertFalse("digest mismatch must leave no half-written per-version dir", dir.isDirectory());
    }

    public static class FakeClock implements UpdateRepository.Clock {
        public long now;
        public FakeClock() { this.now = 1_000_000L; }
        public FakeClock(long initial) { this.now = initial; }
        @Override public long now() { return now; }
    }
}