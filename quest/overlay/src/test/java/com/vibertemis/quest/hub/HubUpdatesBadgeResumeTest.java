package com.vibertemis.quest.hub;

import android.content.pm.PackageManager;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.limelight.R;
import com.vibertemis.quest.update.UpdateRepository;
import com.vibertemis.quest.update.UpdateRepositoryBindings;
import com.vibertemis.quest.update.UpdateRepositoryProvider;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Updates-badge recovery tests for {@link MainHubActivity}.
 *
 * <p>{@link UpdateRepositoryProvider} can hold no instance when the
 * hub is created (a failed initialization) and publish one on a later
 * read. The badge has to recover on the next resume instead of
 * staying hidden until the activity is recreated. Three transitions
 * are pinned here: null -&gt; installed (bind, observe, render the
 * auto-check result), installed -&gt; installed (extra resumes add no
 * observer and no check), and installed -&gt; null (detach and hide).
 *
 * <p>The repository is published through the provider's own
 * package-private {@code installForTest} seam and its observer count
 * is read through {@code observerCountForTest}, so the assertions
 * observe real observer registration. The provider is never modified.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class HubUpdatesBadgeResumeTest {

    /** Outer wall-clock cap so a stuck executor can never pin the
     *  test thread. */
    private static final long AWAIT_TIMEOUT_MS = 4000L;

    /** Frozen wall clock: a successful check pins
     *  {@code lastSuccessAtMs} here, so the throttle keeps rejecting
     *  the extra resume triggers for the full 6-hour window. */
    private static final long FIXED_NOW_MS = 1_700_000_000_000L;

    private File tmpRoot;
    private ExecutorService exec;
    private UpdateRepository repo;
    private final AtomicInteger sourceCalls = new AtomicInteger();

    @Before public void setup() throws Exception {
        UpdateRepositoryProvider.reset();
        // Pin the provider to a FAILED initialization before the hub is
        // created: a null read must model the real "init did not
        // produce an instance" state, never a lazy re-init that would
        // publish a real transport-backed repository.
        failProviderInit(true);
        sourceCalls.set(0);
        // Headset mode, no paired PC: the hub never dispatches
        // anything, so the badge is the only moving part.
        Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .setSystemFeature(PackageManager.FEATURE_VR_HEADTRACKING, true);
        tmpRoot = new File(System.getProperty("java.io.tmpdir"),
                "vq-hub-badge-" + UUID.randomUUID().toString());
        assertTrue("tempdir", tmpRoot.mkdirs());
        // The trust key is never exercised: the cache is empty and the
        // fake source reports no newer release, so no manifest bytes
        // are ever verified.
        repo = new UpdateRepository(new UpdateRepositoryBindings.DiskCache(tmpRoot),
                () -> FIXED_NOW_MS, 6L, () -> "");
        exec = UpdateRepository.newDefaultExecutor();
        repo.bindExecutor(exec, new UpdateRepository.CheckSource() {
            @Override public Result check(long currentVersionCode) {
                sourceCalls.incrementAndGet();
                return Result.none();
            }
        });
    }

    @After public void teardown() throws Exception {
        failProviderInit(false);
        UpdateRepositoryProvider.reset();
        if (exec != null) {
            exec.shutdownNow();
            exec.awaitTermination(2, TimeUnit.SECONDS);
        }
        deleteRecursive(tmpRoot);
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] files = f.listFiles();
            if (files != null) for (File c : files) deleteRecursive(c);
        }
        if (!f.delete()) throw new RuntimeException("delete failed " + f);
    }

    /**
     * A hub that never pairs, so no network, native identity, or
     * discovery runs; the badge is all that is under test.
     */
    public static class BadgeHub extends MainHubActivity {
        @Override protected boolean hasPairedHost() { return false; }
        @Override protected String loadNativeHeadsetIdentity() { return "test.client"; }
    }

    private ActivityController<BadgeHub> openHub() {
        return Robolectric.buildActivity(BadgeHub.class).setup();
    }

    /** Drain the looper for a bounded window, for assertions about
     *  work that must NOT have been queued. */
    private void settle() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300L);
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return;
            Thread.sleep(5);
        }
        fail("condition did not become true within " + AWAIT_TIMEOUT_MS + "ms");
    }

    private static TextView badge(MainHubActivity hub) {
        TextView b = hub.findViewById(R.id.hub_updates_badge);
        if (b == null) throw new AssertionError("hub_updates_badge missing from the layout");
        return b;
    }

    private static UpdateRepository boundRepository(MainHubActivity hub) {
        try {
            Field f = MainHubActivity.class.getDeclaredField("updateRepository");
            f.setAccessible(true);
            return (UpdateRepository) f.get(hub);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    /** Real observer count, reflected past its package-private
     *  modifier so the assertion reads the production registration. */
    private static int observerCount(UpdateRepository r) {
        try {
            Method m = UpdateRepository.class.getDeclaredMethod("observerCountForTest");
            m.setAccessible(true);
            return (Integer) m.invoke(r);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    /** Publish the repository the way a successful provider
     *  initialization would. Reflected because {@code installForTest}
     *  is package-private. An injected instance wins the provider's
     *  get fast path even while initialization is forced to fail, so
     *  this still works with the flag set. */
    private static void publish(UpdateRepository r) {
        try {
            Method m = UpdateRepositoryProvider.class.getDeclaredMethod(
                    "installForTest", UpdateRepository.class);
            m.setAccessible(true);
            m.invoke(null, r);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    /** Force the provider's persistent initialization to fail so a
     *  null read really means "no instance". The updater branch owns
     *  {@code failInitsUntilToggledForTest(boolean)}; before it lands,
     *  {@code get} simply returns null, so the missing hook is the
     *  pass case. Any other reflection error fails the test. */
    private static void failProviderInit(boolean fail) {
        try {
            Method m = UpdateRepositoryProvider.class.getDeclaredMethod(
                    "failInitsUntilToggledForTest", boolean.class);
            m.setAccessible(true);
            m.invoke(null, fail);
        } catch (NoSuchMethodException hookNotLandedYet) {
            // Pre-integration provider: nothing to toggle.
        } catch (Exception e) { throw new AssertionError(e); }
    }

    /**
     * A hub created while initialization is failing must bind the
     * repository on the next resume, observe it once, and render the
     * auto-check that resume triggers.
     */
    @Test public void nullOnCreateRecoversBadgeOnResume() throws Exception {
        try (ActivityController<BadgeHub> ctl = openHub()) {
            BadgeHub hub = ctl.get();
            assertNull("initialization is failing, so no repository may be bound",
                    boundRepository(hub));
            assertEquals("nothing to observe", 0, observerCount(repo));
            assertEquals("badge stays hidden while nothing is known",
                    View.GONE, badge(hub).getVisibility());

            // A repository appears even though init is still forced to
            // fail: the injected instance wins the get fast path.
            publish(repo);
            ctl.pause().resume();

            assertSame("resume must bind the repository that appeared",
                    repo, boundRepository(hub));
            assertEquals("resume attaches exactly one observer", 1, observerCount(repo));

            // The rebound badge also receives updates: the auto-check
            // for the just-bound repository runs once and its success
            // reaches the observer.
            await(() -> sourceCalls.get() == 1);
            await(() -> repo.snapshot().lastSuccessAtMs > 0L);
            settle();
            assertEquals("recovery triggers exactly one check", 1, sourceCalls.get());
            assertEquals("badge must show the recovered check result",
                    View.VISIBLE, badge(hub).getVisibility());
            assertTrue("badge must report the successful check: " + badge(hub).getText(),
                    badge(hub).getText().toString().toLowerCase().contains("up to date"));
        }
    }

    /**
     * A provider that stops publishing an instance must detach the
     * observer and hide the badge instead of leaving a stale line that
     * no longer reflects any check.
     */
    @Test public void repositoryGoingNullDetachesAndHidesBadge() throws Exception {
        publish(repo);
        try (ActivityController<BadgeHub> ctl = openHub()) {
            BadgeHub hub = ctl.get();
            await(() -> sourceCalls.get() == 1);
            await(() -> repo.snapshot().lastSuccessAtMs > 0L);
            settle();
            assertEquals("onCreate binds the published repository", 1, observerCount(repo));
            assertEquals("badge is visible after the first successful check",
                    View.VISIBLE, badge(hub).getVisibility());

            // The provider drops the instance; init is still forced to
            // fail, so the next get stays null.
            UpdateRepositoryProvider.reset();
            ctl.pause().resume();

            assertNull("a null provider result must unbind", boundRepository(hub));
            assertEquals("the detached repository keeps no observer", 0, observerCount(repo));
            assertEquals("badge hides when there is nothing known",
                    View.GONE, badge(hub).getVisibility());

            // A late publication from the detached repository must not
            // resurrect the line.
            repo.recordFailure("late");
            settle();
            assertEquals("a detached repository must not re-render the badge",
                    View.GONE, badge(hub).getVisibility());
        }
    }

    /**
     * Repeated resumes against an unchanged repository must add no
     * observer and no check: the re-bind is identity-compared and the
     * repository throttle still collapses the extra triggers.
     */
    @Test public void repeatedResumesDoNotDuplicateObserverOrChecks() throws Exception {
        publish(repo);
        try (ActivityController<BadgeHub> ctl = openHub()) {
            BadgeHub hub = ctl.get();
            await(() -> sourceCalls.get() == 1);
            await(() -> repo.snapshot().lastSuccessAtMs > 0L);
            settle();
            assertEquals("onCreate attaches exactly one observer", 1, observerCount(repo));
            assertEquals("exactly one check so far", 1, sourceCalls.get());

            for (int i = 0; i < 3; i++) ctl.pause().resume();
            settle();

            assertSame("the bound repository must not change", repo, boundRepository(hub));
            assertEquals("three extra resumes must not add observers", 1, observerCount(repo));
            assertEquals("the throttle must swallow the extra resume triggers",
                    1, sourceCalls.get());
            assertEquals("badge stays visible on the last known result",
                    View.VISIBLE, badge(hub).getVisibility());
            assertTrue("badge must still report the successful check: " + badge(hub).getText(),
                    badge(hub).getText().toString().toLowerCase().contains("up to date"));
        }
    }
}
