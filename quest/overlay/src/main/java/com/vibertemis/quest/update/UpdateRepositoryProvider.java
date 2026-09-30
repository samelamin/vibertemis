package com.vibertemis.quest.update;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide holder for the {@link UpdateRepository}.
 *
 * <p>The application context installs a singleton instance during
 * {@code onCreate}. The launch hub and {@link UpdatesActivity} read
 * it through {@link #get(Context)}; neither owns the lifecycle, so
 * Activity destruction never cancels a shared in-flight check.
 */
public final class UpdateRepositoryProvider {
    private static final AtomicReference<UpdateRepository> INSTANCE = new AtomicReference<>();
    private static volatile ExecutorService EXECUTOR;

    private UpdateRepositoryProvider() { }

    /** Install the process-wide repository. Idempotent: a second
     *  install replaces the prior instance only if the prior is null
     *  (effectively only one install per process). */
    public static synchronized void install(Context appContext, long currentVersionCode) throws Exception {
        if (INSTANCE.get() != null) return;
        ExecutorService exec = UpdateRepository.newDefaultExecutor();
        UpdateRepository repo = UpdateRepositoryBindings.create(appContext, exec, currentVersionCode);
        EXECUTOR = exec;
        INSTANCE.set(repo);
    }

    public static UpdateRepository get(Context appContext) { return INSTANCE.get(); }

    /** For tests / process shutdown. */
    public static synchronized void reset() {
        UpdateRepository r = INSTANCE.getAndSet(null);
        ExecutorService e = EXECUTOR;
        EXECUTOR = null;
        if (r != null) r.shutdown();
        if (e != null) {
            e.shutdownNow();
        }
    }
}
