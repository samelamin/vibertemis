package com.vibertemis.quest.hub;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-instance controller that owns the cached "current streaming
 * settings" snapshot for one hub owner (the {@link MainHubActivity}
 * instance) and notifies per-instance observers when an upstream
 * preference key changes.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>construct once with the activity context (captured as
 *       application context to avoid leaking the activity),</li>
 *   <li>{@link #addObserver} once in {@code onCreate} — observers are
 *       attached for the lifetime of the host fragment; they are NOT
 *       reattached on every register/unregister,</li>
 *   <li>{@link #register()} in {@code onResume()} to subscribe the
 *       upstream SharedPreferences listener; cached snapshot
 *       refreshes and observers fire only when an upstream preference
 *       key changes (e.g. a preset Apply, a manual Streaming
 *       settings edit),</li>
 *   <li>{@link #unregister()} in {@code onPause()} to unsubscribe the
 *       SharedPreferences listener. Observer references are RETAINED
 *       so the next {@code onResume} / {@link #register()} reattaches
 *       them automatically — without this the live UI would not
 *       refresh after the first pause/resume cycle,</li>
 *   <li>{@link #dispose()} from a permanent teardown path
 *       (e.g. {@code onDestroyView}) to drop observer references too.</li>
 *   <li>{@link #refresh()} explicitly before reading the snapshot, so
 *       the caller does not have to depend on the listener having
 *       fired (e.g. after a process-level reset).</li>
 * </ul>
 *
 * <p>Observer callback fires once per relevant upstream key change
 * after the cached snapshot has been refreshed. Observers are
 * expected to update their UI (top status preference, cached
 * ListPreference / SeekBarPreference widgets) without performing any
 * recursive write through {@code PreferenceManager.edit()}.
 *
 * <p>There is no static / global state. Two activities holding two
 * controllers see two snapshots and two observer sets.
 */
public final class SettingsController {

    /**
     * Observer fired once per upstream preference change for the
     * four relevant keys. Implementations MUST NOT call
     * {@link ProfileApplier#applyProfile} or otherwise write through
     * {@link PreferenceManager}; the callback is notification-only
     * so the upstream key set stays consistent.
     */
    public interface Observer {
        void onUpstreamPreferenceChanged(String key,
                                         ProfileApplier.CurrentSettings snapshot);
    }

    private final Context appContext;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener;
    private final CopyOnWriteArrayList<Observer> observers =
            new CopyOnWriteArrayList<>();
    private ProfileApplier.CurrentSettings cached;
    private boolean registered;

    public SettingsController(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                if (!isRelevant(key)) return;
                cached = ProfileApplier.currentSettings(appContext);
                for (Observer o : observers) {
                    o.onUpstreamPreferenceChanged(key, cached);
                }
            }
        };
        cached = ProfileApplier.currentSettings(appContext);
    }

    /**
     * Add an observer. The reference is held until {@link
     * #removeObserver} or {@link #unregister} runs; remove observers
     * in {@code onPause} so the controller does not pin destroyed
     * fragments.
     */
    public void addObserver(Observer o) {
        if (o != null) observers.addIfAbsent(o);
    }

    public void removeObserver(Observer o) {
        if (o != null) observers.remove(o);
    }

    /** Subscribe to upstream preference changes. Idempotent. */
    public void register() {
        if (registered) return;
        PreferenceManager.getDefaultSharedPreferences(appContext)
                .registerOnSharedPreferenceChangeListener(listener);
        registered = true;
    }

    /**
     * Unsubscribe the upstream SharedPreferences listener. Observer
     * references are retained so a follow-up {@link #register()}
     * reattaches them to the same controller instance — the
     * controller is owned by the host fragment, not by a static
     * reference, so dropping the SP listener is enough to break the
     * GC chain. Use {@link #dispose()} when the host is permanently
     * gone.
     */
    public void unregister() {
        if (registered) {
            PreferenceManager.getDefaultSharedPreferences(appContext)
                    .unregisterOnSharedPreferenceChangeListener(listener);
            registered = false;
        }
    }

    /**
     * Unsubscribe AND drop observer references. Idempotent. Call
     * from a permanent teardown path (e.g. {@code onDestroyView}).
     */
    public void dispose() {
        unregister();
        observers.clear();
    }

    /** Force a re-read of the cached snapshot from upstream prefs. */
    public void refresh() {
        cached = ProfileApplier.currentSettings(appContext);
    }

    /** Snapshot of the current streaming settings. Refreshes if empty. */
    public ProfileApplier.CurrentSettings current() {
        if (cached == null) {
            cached = ProfileApplier.currentSettings(appContext);
        }
        return cached;
    }

    public boolean isRegistered() {
        return registered;
    }

    /** Test hook — number of currently-attached observers. */
    public int observerCount() {
        return observers.size();
    }

    private static boolean isRelevant(String key) {
        if (key == null) return false;
        return ProfileApplier.K_RES.equals(key)
                || ProfileApplier.K_FPS.equals(key)
                || ProfileApplier.K_BITRATE_KBPS.equals(key)
                || ProfileApplier.K_VIDEO_FORMAT.equals(key);
    }
}