package com.vibertemis.quest.update;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import androidx.core.content.FileProvider;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSigningInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared Robolectric fixture for the updater tests.
 *
 * <p>Robolectric cannot produce a real APK, so the archive-identity
 * and signer checks in {@link UpdatesActivity} are driven through
 * {@link ShadowPackageManager}. Two facts have to line up or the
 * production check correctly refuses:
 * <ul>
 *   <li>the app's own {@link PackageInfo} must report exactly one
 *       signer, and</li>
 *   <li>the archive {@link PackageInfo} returned for the APK path
 *       must report the same single signer plus the matching package
 *       name and versionCode.</li>
 * </ul>
 * {@link SigningInfo} values are built with Robolectric's own
 * {@link ShadowSigningInfo} shadow, so the fixture does not reach into
 * framework internals. This is test scaffolding only: the production
 * path is untouched.
 */
final class UpdateTestFixture {

    private UpdateTestFixture() { }

    static final Signature SIGNER = new Signature(new byte[]{9, 8, 7, 6, 5});

    /** SHA-256 of {@link #SIGNER} in the manifest's lowercase hex form. */
    static final String SIGNER_SHA256 = signerSha256();

    private static String signerSha256() {
        return hex(sha256(SIGNER.toByteArray()));
    }

    /** The APK package name for the nonRoot debug variant. */
    static String packageName(Context context) { return context.getPackageName(); }

    /** The versionCode Robolectric reports for the installed app. */
    static long installedVersionCode(Context context) throws Exception {
        return getPackageInfo(context).getLongVersionCode();
    }

    private static PackageInfo getPackageInfo(Context context) throws Exception {
        return context.getPackageManager().getPackageInfo(context.getPackageName(),
                Build.VERSION.SDK_INT >= 28
                        ? android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                        : android.content.pm.PackageManager.GET_SIGNATURES);
    }

    /**
     * Make the installed app report a single signer and install
     * permission as requested. Robolectric reports
     * {@code canRequestPackageInstalls == false} by default.
     *
     * <p>The manifest's own {@link PackageInfo} is mutated and put back
     * rather than replaced: a freshly built one carries no providers or
     * {@code applicationInfo}, which would silently drop the update
     * FileProvider the activity hands APKs to.
     */
    static void installSigningIdentity(Context context, long versionCode, boolean canRequestInstalls) {
        ShadowPackageManager spm = Shadows.shadowOf(context.getPackageManager());
        spm.setCanRequestPackageInstalls(canRequestInstalls);
        PackageInfo own = manifestPackageInfo(context);
        own.versionCode = (int) versionCode;
        own.versionName = versionCode + ".0.0.0";
        own.signatures = new Signature[]{SIGNER};
        if (Build.VERSION.SDK_INT >= 28) own.signingInfo = signingInfoForTest(SIGNER);
        spm.installPackage(own);
    }

    /**
     * The installed package record as the manifest produced it, including
     * its declared providers. Falls back to a bare record only when the
     * package is genuinely unknown to the PackageManager.
     */
    private static PackageInfo manifestPackageInfo(Context context) {
        int flags = PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA
                | (Build.VERSION.SDK_INT >= 28
                        ? PackageManager.GET_SIGNING_CERTIFICATES
                        : PackageManager.GET_SIGNATURES);
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), flags);
            if (info != null) return info;
        } catch (Exception ignored) {
            // Falls through to the bare record below.
        }
        PackageInfo bare = new PackageInfo();
        bare.packageName = context.getPackageName();
        return bare;
    }

    /**
     * Build a {@link SigningInfo} reporting exactly {@code sig} as the
     * APK contents signer, using the {@link ShadowSigningInfo} shadow
     * Robolectric ships for this purpose. Test scaffolding only: the
     * production path reads real {@code SigningInfo} objects.
     */
    static SigningInfo signingInfoForTest(Signature sig) {
        SigningInfo info = Shadow.newInstanceOf(SigningInfo.class);
        Shadows.shadowOf(info).setSignatures(new Signature[]{sig});
        return info;
    }

    /**
     * Teach Robolectric that {@code apkPath} is a package archive with
     * the given versionCode, signed by {@link #SIGNER}.
     */
    static void publishArchiveInfo(Context context, File apkPath, long versionCode) {
        publishArchiveInfo(context, apkPath, versionCode, SIGNER);
    }

    /** Same, but with an explicit signer so the production
     *  signer-equality check can be driven both ways. */
    static void publishArchiveInfo(Context context, File apkPath, long versionCode,
                                   Signature signer) {
        PackageInfo archive = new PackageInfo();
        archive.packageName = context.getPackageName();
        archive.versionCode = (int) versionCode;
        archive.versionName = versionCode + ".0.0.0";
        archive.signatures = new Signature[]{signer};
        if (Build.VERSION.SDK_INT >= 28) archive.signingInfo = signingInfoForTest(signer);
        publish(context, apkPath, archive);
    }

    /** Remove a previously published archive so the path reads as
     *  unparseable, which is what a corrupt download looks like. */
    static void clearArchiveInfo(Context context, File apkPath) {
        publish(context, apkPath, null);
    }

    /**
     * Production reads the canonical path, which is not the absolute one
     * where the cache sits under a symlink (macOS /var -> /private/var), so
     * the archive is published under both.
     */
    private static void publish(Context context, File apkPath, PackageInfo archive) {
        ShadowPackageManager spm = Shadows.shadowOf(context.getPackageManager());
        spm.setPackageArchiveInfo(apkPath.getAbsolutePath(), archive);
        try {
            spm.setPackageArchiveInfo(apkPath.getCanonicalPath(), archive);
        } catch (java.io.IOException ignored) {
            // The absolute path above is all there is
        }
    }

    /**
     * The path {@link UpdateRepositoryBindings.DiskCache} uses for a
     * release's verified copy. The activity verifies and hands over
     * this copy, not the shared download staging file, so a test that
     * stages a download has to publish the identity for it too.
     */
    private static final Map<Activity, List<ShadowActivity.IntentForResult>>
            STARTED_INTENTS_BY_ACTIVITY = new java.util.WeakHashMap<>();

    static File downloadedApkFile(Context context, String version) {
        return new File(context.getCacheDir(), "updates/downloaded/" + version + "/update.apk");
    }

    /** Publish the archive identity for a release's verified copy. */
    static void publishDownloadedArchiveInfo(Context context, String version, long versionCode) {
        publishDownloadedArchiveInfo(context, version, versionCode, SIGNER);
    }

    /** Same, with an explicit signer so the signer check can be driven. */
    static void publishDownloadedArchiveInfo(Context context, String version, long versionCode,
                                             Signature signer) {
        publishArchiveInfo(context, downloadedApkFile(context, version), versionCode, signer);
    }

    /**
     * Teach Robolectric that {@code apkPath} is a package archive whose
     * identity parses but which reports no signing information at all.
     * A package that cannot name its signer cannot be shown to come
     * from this installation, so the production check must refuse it
     * rather than read a signer out of nothing.
     */
    static void publishArchiveInfoWithoutSigningInfo(Context context, File apkPath, long versionCode) {
        PackageInfo archive = new PackageInfo();
        archive.packageName = context.getPackageName();
        archive.versionCode = (int) versionCode;
        archive.versionName = versionCode + ".0.0.0";
        publish(context, apkPath, archive);
    }

    /** The same, for a release's verified copy. */
    static void publishDownloadedArchiveInfoWithoutSigningInfo(Context context, String version,
                                                               long versionCode) {
        publishArchiveInfoWithoutSigningInfo(context,
                downloadedApkFile(context, version), versionCode);
    }

    static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(3072);
        return gen.generateKeyPair();
    }

    static String pemFor(KeyPair keyPair) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    /**
     * Every intent the activity has launched for a result, oldest first.
     *
     * <p>Robolectric's queue can only be drained, so the history is
     * accumulated here. Reading it twice must not look like the second
     * launch never happened, which is exactly what a draining helper
     * does to an assertion that counts launches.
     */
    static List<ShadowActivity.IntentForResult> launchedIntents(Activity activity) {
        List<ShadowActivity.IntentForResult> history = STARTED_INTENTS_BY_ACTIVITY
                .computeIfAbsent(activity, k -> new ArrayList<>());
        ShadowActivity.IntentForResult next;
        while ((next = Shadows.shadowOf(activity).getNextStartedActivityForResult()) != null) {
            history.add(next);
        }
        return history;
    }

    /** Forget an activity's recorded intents. */
    static void forgetLaunchedIntents(Activity activity) {
        STARTED_INTENTS_BY_ACTIVITY.remove(activity);
    }

    /**
     * Drop androidx's process-wide cache of resolved FileProvider path
     * strategies.
     *
     * <p>{@code FileProvider} memoises the strategy it builds for an
     * authority, and that strategy is rooted at the cache directory of
     * whichever context resolved it first. Robolectric hands every test
     * method a fresh cache directory while the JVM and the static cache
     * live on, so without this the first test in a class resolves the
     * real content URI and every later one fails with "Failed to find
     * configured root" for a path that is plainly inside the declared
     * {@code updates/} cache path.
     *
     * <p>Clearing the memo forces {@code FileProvider} to re-resolve
     * against the current context, which is exactly the behaviour
     * production gets on a cold start. Nothing here relaxes the
     * production check: the provider still refuses any file outside the
     * configured root.
     */
    static void resetFileProviderStrategyCache() {
        try {
            for (java.lang.reflect.Field field : FileProvider.class.getDeclaredFields()) {
                if ("sCache".equals(field.getName())
                        && Map.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Map<Object, Object> cache = (Map<Object, Object>) field.get(null);
                    cache.clear();
                    return;
                }
            }
        } catch (Exception ignored) {
            // Without the reset the first test in a class resolves real
            // URIs and later ones fail; that is a test-isolation defect,
            // never a reason to skip the reset.
        }
    }

    /** A unique scratch file inside the shared temp dir. */
    static File scratchFile(String suffix) {
        File dir = new File(System.getProperty("java.io.tmpdir"), "vq-updater-fixture");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, UUID.randomUUID() + suffix);
    }

    static File writeBytes(File target, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(bytes);
        }
        return target;
    }

    static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String hex(byte[] in) {
        return UpdateManifest.hex(in);
    }

    static Context context() { return RuntimeEnvironment.getApplication(); }
}
