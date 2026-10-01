package com.vibertemis.quest.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide holder for the {@link UpdateRepository}.
 *
 * <p>The provider exposes a single idempotent {@link #get(Context)}
 * that returns the shared repository, initializing it on first call
 * from the application context: the embedded PEM
 * ({@code R.raw.quest_update_key}), the {@link
 * UpdateRepositoryBindings.DiskCache} rooted at the application's
 * no-backup cache directory, the actual installed
 * {@link PackageInfo#getLongVersionCode()}, and a production
 * {@link UpdateRepositoryBindings.TransportCheckSource} bound to the
 * shared executor. Initialization runs once per process; concurrent
 * first-callers coalesce onto the same initialization so two
 * activities cannot each build a separate repository.
 *
 * <p>The activity / hub only consult {@link #get(Context)}; they
 * never install directly. Tests continue to inject a fully-bound
 * {@link UpdateRepository} through {@link #installForTest} so they
 * can drive the lifecycle without touching the embedded PEM or the
 * network transport, but cold-start tests use {@link #get(Context)}
 * against a Robolectric {@link Context} that resolves
 * {@code R.raw.quest_update_key} from {@code src/main/res/raw/}.
 *
 * <p>If initialization fails (missing key resource, PackageManager
 * rejection, I/O error), the failure is recorded and surfaced via
 * {@link #initializationErrorForTest()}; the next {@link #get}
 * retries. The shared executor is shut down on every failed
 * install so a leaked thread never survives across a transient
 * fault.
 *
 * <p>Activity destruction never cancels the shared check — the
 * executor and observers survive Activity recreation.
 */
public final class UpdateRepositoryProvider {
    private static final AtomicReference<UpdateRepository> INSTANCE = new AtomicReference<>();
    private static volatile ExecutorService EXECUTOR;
    private static volatile String INITIALIZATION_ERROR;
    private static volatile long CURRENT_VERSION_CODE;
    private static volatile Context APPLICATION_CONTEXT;

    /** Pluggable factory for the metadata check source so tests can
     *  drive the cold-start path with a fake remote source without
     *  touching the GitHub transport. The default builds a real
     *  {@link UpdateRepositoryBindings.TransportCheckSource}. */
    public interface CheckSourceFactory {
        UpdateRepository.CheckSource create(Context context, String trustedKey);
    }

    private static volatile CheckSourceFactory CHECK_SOURCE_FACTORY =
            (context, trustedKey) -> new UpdateRepositoryBindings.TransportCheckSource(trustedKey);

    /** Turn off the persistent test failure; do it in test teardown so
     *  one test cannot leak into the next. */
    public static void clearPersistentInitFailureForTest() {
        FAIL_INITS_UNTIL_TOGGLED_FOR_TEST = false;
    }

    /** Test-only: when {@code true}, the next {@link #get(Context)}
     *  call throws on the cold-start path so the activity can
     *  exercise its initial-failure recovery. The flag auto-clears
     *  after one use so a follow-up retry can succeed. */
    private static volatile boolean FAIL_NEXT_INIT_FOR_TEST;

    /** Test-only: while {@code true}, every initialisation fails until
     *  the flag is turned off again. Unlike the one-shot toggle above
     *  this models a cold start that stays broken, which is what a
     *  screen has to keep offering a Retry for; the one-shot flag
     *  self-heals on the next {@code get} and would otherwise be
     *  masked by that automatic recovery. */
    private static volatile boolean FAIL_INITS_UNTIL_TOGGLED_FOR_TEST;

    public static void failNextInitForTest(boolean fail) {
        FAIL_NEXT_INIT_FOR_TEST = fail;
    }

    public static void failInitsUntilToggledForTest(boolean fail) {
        FAIL_INITS_UNTIL_TOGGLED_FOR_TEST = fail;
    }

    private UpdateRepositoryProvider() { }

    /** Test seam: replace the {@link CheckSourceFactory} used during
     *  cold-start initialization. The default builds a real
     *  {@link UpdateRepositoryBindings.TransportCheckSource}. */
    public static synchronized void installCheckSourceFactoryForTest(CheckSourceFactory factory) {
        CHECK_SOURCE_FACTORY = factory != null ? factory
                : (context, trustedKey) -> new UpdateRepositoryBindings.TransportCheckSource(trustedKey);
    }

    /** Idempotent: returns the shared repository, initializing it
     *  on first call. Safe to call from any thread. */
    public static UpdateRepository get(Context appContext) {
        if (appContext == null) {
            throw new IllegalArgumentException("Application context is required");
        }
        UpdateRepository existing = INSTANCE.get();
        if (existing != null) return existing;
        synchronized (UpdateRepositoryProvider.class) {
            existing = INSTANCE.get();
            if (existing != null) return existing;
            return initializeFromContext(appContext.getApplicationContext());
        }
    }

    private static UpdateRepository initializeFromContext(Context appContext) {
        ExecutorService exec = null;
        UpdateRepository repo = null;
        try {
            if (FAIL_NEXT_INIT_FOR_TEST) {
                FAIL_NEXT_INIT_FOR_TEST = false;
                throw new IOException("forced init failure (test toggle)");
            }
            if (FAIL_INITS_UNTIL_TOGGLED_FOR_TEST) {
                throw new IOException("persistent init failure (test toggle)");
            }
            long versionCode = readInstalledVersionCode(appContext);
            CURRENT_VERSION_CODE = versionCode;
            APPLICATION_CONTEXT = appContext;
            String pem = UpdateRepositoryBindings.loadTrustedKey(appContext);
            UpdateRepositoryBindings.DiskCache cache =
                    new UpdateRepositoryBindings.DiskCache(appContext.getCacheDir());
            UpdateRepository.CheckSource source =
                    CHECK_SOURCE_FACTORY.create(appContext, pem);
            exec = UpdateRepository.newDefaultExecutor();
            repo = new UpdateRepository(cache, UpdateRepository.Clock.SYSTEM,
                    versionCode, () -> pem);
            repo.bindExecutor(exec, source);
            EXECUTOR = exec;
            INSTANCE.set(repo);
            INITIALIZATION_ERROR = null;
            return repo;
        } catch (Throwable t) {
            // Cleanup: shutdown any partially-bound executor and
            // forget the repository so the next get() retries.
            INITIALIZATION_ERROR = t.getClass().getSimpleName() + ": "
                    + (t.getMessage() == null ? "(no detail)" : t.getMessage());
            if (repo != null) repo.shutdown();
            if (exec != null) {
                exec.shutdownNow();
            }
            EXECUTOR = null;
            INSTANCE.set(null);
            return null;
        }
    }

    /** Read the installed versionCode via PackageManager. Returns 0
     *  on failure rather than {@link Long#MAX_VALUE} so the caller
     *  sees a real failure (no newer release signalled) instead of a
     *  fake "you are very old" success. */
    private static long readInstalledVersionCode(Context appContext) {
        try {
            PackageInfo info = appContext.getPackageManager().getPackageInfo(
                    appContext.getPackageName(),
                    Build.VERSION.SDK_INT >= 28
                            ? android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                            : android.content.pm.PackageManager.GET_SIGNATURES);
            if (info == null) throw new IOException("PackageManager returned null for " + appContext.getPackageName());
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Throwable t) {
            throw new RuntimeException("Could not read installed versionCode: "
                    + t.getMessage(), t);
        }
    }

    /** The most recent initialization error, or {@code null} if the
     *  provider has successfully installed or has never been
     *  queried. Visible for tests; the activity uses it to render a
     *  meaningful "Update check unavailable: …" status when the
     *  cold-start install failed. */
    public static String initializationErrorForTest() {
        return INITIALIZATION_ERROR;
    }

    /** The installed version code that was used to bind the shared
     *  repository. Visible for tests; the activity prefers this
     *  over a re-query through the PackageManager so the two views
     *  agree. */
    public static long currentVersionCodeForTest() {
        return CURRENT_VERSION_CODE;
    }

    /** The application context the shared repository was bound
     *  against. Visible for tests. */
    public static Context applicationContextForTest() {
        return APPLICATION_CONTEXT;
    }

    /** For tests / process shutdown. */
    public static synchronized void reset() {
        UpdateRepository r = INSTANCE.getAndSet(null);
        ExecutorService e = EXECUTOR;
        EXECUTOR = null;
        INITIALIZATION_ERROR = null;
        CURRENT_VERSION_CODE = 0L;
        APPLICATION_CONTEXT = null;
        if (r != null) r.shutdown();
        if (e != null) e.shutdownNow();
    }

    /** Test-only install path that bypasses the production
     *  resource load. The lifecycle tests supply their own
     *  already-bound repository (with a fake source) so they
     *  can assert the open / pause / resume / close path without
     *  touching the real {@code quest_update_key} PEM or the
     *  network transport. Package-private so callers outside the
     *  update package cannot reach it; the lifecycle test lives in
     *  the same package. */
    static synchronized void installForTest(UpdateRepository repo) {
        UpdateRepository prior = INSTANCE.getAndSet(repo);
        INITIALIZATION_ERROR = null;
        if (prior != null && prior != repo) prior.shutdown();
    }
}