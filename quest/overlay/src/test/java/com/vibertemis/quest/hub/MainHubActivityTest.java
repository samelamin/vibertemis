package com.vibertemis.quest.hub;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.limelight.PcView;
import com.limelight.R;
import com.vibertemis.quest.pcvr.HostClient;
import com.vibertemis.quest.pcvr.HostClientTest;
import com.vibertemis.quest.pcvr.HostPairing;
import com.vibertemis.quest.pcvr.PcvrTestActions;
import com.vibertemis.quest.pcvr.PcvrTestActions.StartedIntentLog;
import com.vibertemis.quest.pcvr.VrSetupDiscovery;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowToast;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Main hub lifecycle tests. We inflate {@link MainHubActivity} via
 * Robolectric so the on-screen button handlers are bound, then drive
 * the user actions and assert on the intents Robolectric recorded.
 *
 * <p><b>Fixtures.</b> Two hub shapes are needed because the hub routes
 * differently once a PC is paired:
 * <ul>
 *   <li><b>Unpaired</b> ({@link FakeNsdHub} over the real
 *       {@link com.vibertemis.quest.pcvr.PairingStore}, so
 *       {@code hasPairing()} is false). The primary action reads "Set
 *       up PC" and opens the VR-setup picker. The setup flow
 *       deliberately discovers and pairs the PC <i>before</i> anything
 *       asks for the microphone, so these tests assert the picker
 *       surfaces and that <b>no</b> microphone request is dispatched
 *       until the user explicitly picks a VR action.</li>
 *   <li><b>Paired</b> ({@link PairedHub}, reusing the
 *       {@link ConnectJourneyTest} fake-host pattern): the real
 *       {@code onRequestPermissionsResult} seam, the real
 *       {@link #REQ_MIC_FOR_STEAMVR} request code, and the real
 *       {@code loadNativeHeadsetIdentity} seam, but a deterministic
 *       {@link FakeHost} whose {@code vrserver} answer each test
 *       chooses. Permission, lifecycle, duplication, denial and
 *       launch-guard behaviour is only reachable through this fixture,
 *       because Connect no longer asks for the microphone on the
 *       unpaired setup path. {@link NativePairedHub} is the same hub on
 *       the native runtime, where the restart-consent contract applies,
 *       {@link ProcessAwareHub} adds a controllable process
 *       snapshot, and {@link SwappablePairingHub} adds a pairing the
 *       test can replace while an attempt is in flight.</li>
 * </ul>
 *
 * <p><b>Harness rules.</b>
 * <ul>
 *   <li>The connect lifecycle runs on the hub's single-thread worker,
 *       so every asynchronous assertion waits on a real signal (a fake
 *       host counter, a surfaced dialog) instead of a fixed sleep.</li>
 *   <li>{@link StartedIntentLog} accumulates every recorded intent, so
 *       draining the destructive Robolectric started-activity queue
 *       before and after an asynchronous launch never loses an intent
 *       and never makes an early drain swallow a later one.</li>
 *   <li>Every test dismisses its dialogs and destroys its controller,
 *       so no modal dialog or background worker leaks into the next
 *       test.</li>
 * </ul>
 *
 * <p>The hub must:
 * <ul>
 *   <li>never launch PCVR when the device is not a headset,</li>
 *   <li>on a headset with the mic permission denied, surface the
 *       "PCVR not started" toast and never start {@link SteamVrActivity}
 *       or reach the host,</li>
 *   <li>on a headset with the mic permission granted, probe the paired
 *       host and start {@link SteamVrActivity} via the explicit
 *       {@code ComponentName} with the immersive VR categories and
 *       {@code FLAG_ACTIVITY_NEW_TASK},</li>
 *   <li>on a phone, route the primary Connect to {@link PcView}
 *       without asking for mic permission,</li>
 *   <li>on an unpaired headset, discover and pair before asking for the
 *       microphone,</li>
 *   <li>guard against in-flight permission requests and pending launches
 *       so rapid taps and duplicate callbacks cannot start two
 *       activities at once,</li>
 *   <li>never auto-launch from {@code onResume} after returning from
 *       Streaming settings, a permission dialog, or a recreation — but
 *       must continue exactly the action a paused grant belongs to,</li>
 *   <li>validate the permission result against the actual requested
 *       permission name, current headset status, and current grant
 *       state before launching PCVR,</li>
 *   <li>request the microphone for Manual VR when it is missing, and
 *       still require the explicit legacy restart consent afterwards,</li>
 *   <li>treat a WARM {@code vrserver=true} probe as a normal idempotent
 *       connection — one probe, one start, one dispatch, no prompt, and
 *       {@code vq_pcvr_allow_restart=false} with a zero deadline,</li>
 *   <li>keep the existing cold {@code vrserver=false} behaviour: open
 *       the 120-second consent window and start without a prompt,</li>
 *   <li>accept a native restart callback only when it echoes the
 *       pending launch nonce and the pairing pin the hub holds right
 *       now, and treat a missing, forged, stale, duplicate or
 *       host-changed callback as inert,</li>
 *   <li>never restart automatically after a native report: a
 *       {@code restart_required} surfaces the Restart / Cancel dialog
 *       only once the old {@code :pcvr} process is provably gone, and a
 *       Restart tap re-probes the SAME pairing with a NEW client
 *       before setting the consent window,</li>
 *   <li>keep no positive restart consent across a recreation, and</li>
 *   <li>refuse to dispatch while a previous immersive process of our own
 *       uid is still running, failing clearly instead of guessing;</li>
 *   <li>treat the process gate's answer as untrusted: a Cancel, a
 *       Destroy or a newer attempt must invalidate a poll that is already
 *       in flight, and a gate that finishes while the hub is paused must
 *       be parked and continued exactly once on the next resume, never
 *       dropped, never run early and never replayed;</li>
 *   <li>spend a Restart consent only on the host the accepted native
 *       callback named: a pairing replaced while the prompt is open or
 *       during the fresh probe must yield no probe, no start, no
 *       dispatch and no restart permission, while a host that merely
 *       changed address (same pin) is still restartable.</li>
 * </ul>
 *
 * <p><b>Process gate.</b> Robolectric does not model a running
 * {@code :pcvr} process, so the hub's exit gate reads a snapshot through
 * the {@code readPcvrProcesses} seam. The default fixture answers "no
 * process of ours is running" (the real situation before a launch);
 * {@link ProcessAwareHub} lets a test keep an old pid alive and assert
 * that nothing is dispatched until the process fact changes — which is
 * how the tests prove the gate waits on process identity rather than on
 * a debounce.
 */

@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class MainHubActivityTest {

    private static final int REQ_MIC_FOR_STEAMVR = MainHubActivity.REQ_MIC_FOR_STEAMVR_FOR_TEST;
    private static final String STATE_REQUEST_PENDING = "vq_hub_request_pending";

    private Context ctx;

    /** Fake host for the paired fixture, rebuilt per test. */
    static FakeHost host;
    static HostPairing pairing;

    /**
     * Deterministic stand-in for the authenticated host. It answers the
     * probe with a caller-chosen {@code vrserver} value (a cold PC is the
     * default: the probe itself opens the consent window and no modal
     * dialog appears) and counts probes and start requests so a test can
     * prove how many attempts reached the host.
     * {@link #release} can be held open to keep a start request in flight
     * while the test taps again, and {@link #probeRelease} does the same
     * for the probe itself so a test can change the pairing while a
     * request is genuinely on the wire.
     */
    static class FakeHost extends HostClient {
        final JSONObject status = new JSONObject();
        final AtomicInteger probes = new AtomicInteger();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger clients = new AtomicInteger();
        /** Counts probes that have been ENTERED, before the release
         *  latch. A test that has to change something while the request
         *  is genuinely in flight waits on this, not on {@link #probes},
         *  which only advances once the host has answered. */
        final AtomicInteger probeEntries = new AtomicInteger();
        volatile CountDownLatch release = new CountDownLatch(0);
        /** Held open to keep a probe in flight while the test changes
         *  the pairing underneath it. */
        volatile CountDownLatch probeRelease = new CountDownLatch(0);
        FakeHost() throws org.json.JSONException { status.put("vrserver", false); }
        /** Answer the next probe with a warm or a cold vrserver state. */
        void setVrserver(boolean warm) throws org.json.JSONException { status.put("vrserver", warm); }
        @Override public JSONObject request(HostPairing p, String method, String path, byte[] body)
                throws Exception {
            assertEquals("GET", method);
            assertEquals("/status", path);
            probeEntries.incrementAndGet();
            if (!probeRelease.await(5, TimeUnit.SECONDS)) {
                throw new java.io.IOException("fake probe timed out");
            }
            probes.incrementAndGet();
            return status;
        }
        @Override public void start(HostPairing p, String codec) throws Exception {
            starts.incrementAndGet();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new Exception("fake host start timed out");
            }
        }

        /**
         * One attempt's client: a NEW {@link HostClient} per
         * {@code createHostClient} call, exactly as production builds
         * one per attempt. The cancellation flag is inherited private
         * state, so the {@code cancel()} that abandons a superseded
         * attempt can never leave the NEXT attempt holding an
         * already-cancelled client.
         *
         * <p>Both calls are delegated to the shared {@link FakeHost},
         * so the counters and the release latches a test drives stay
         * the single source of truth for what really reached the wire.
         */
        static final class Attempt extends HostClient {
            private final FakeHost shared;
            Attempt(FakeHost shared) { this.shared = shared; }
            @Override public JSONObject request(
                    HostPairing p, String method, String path, byte[] body) throws Exception {
                return shared.request(p, method, path, body);
            }
            @Override public void start(HostPairing p, String codec) throws Exception {
                shared.start(p, codec);
            }
        }

        /** A fresh, not-yet-cancelled client for a single attempt. */
        Attempt newAttempt() { return new Attempt(this); }
    }

    @Before
    public void setUp() throws Exception {
        ctx = RuntimeEnvironment.getApplication();
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().clear().commit();
        FakeNsdHub.sharedFactory = null;
        host = new FakeHost();
        pairing = HostClientTest.pairing("host", 28540);
    }

    private static ShadowApplication app() { return org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()); }

    private void setHeadset(boolean headset) {
        ShadowPackageManager spm = Shadows.shadowOf(ctx.getPackageManager());
        spm.setSystemFeature(PackageManager.FEATURE_VR_HEADTRACKING, headset);
    }

    private void grantMic(boolean granted) {
        ShadowApplication app = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication());
        if (granted) {
            app.grantPermissions(Manifest.permission.RECORD_AUDIO);
        } else {
            app.denyPermissions(Manifest.permission.RECORD_AUDIO);
        }
    }

    /** Test hub that injects a deterministic fake NSD driver via
     *  the {@link VrSetupDiscovery.Factory} seam so the discovery
     *  completes immediately with the supplied services list
     *  (or empty when {@code services} is null/empty). Real NSD
     *  is a Robolectric no-op and would otherwise pin tests for
     *  the 8-second browse budget. It keeps the real
     *  {@code loadNativeHeadsetIdentity} seam so the native SNI path
     *  is exercised where the runtime asks for it.
     *
     *  <p>It also pins the process-liveness gate: the default snapshot is
     *  an empty list, i.e. no {@code :pcvr} process of ours is running,
     *  so the gate reports GONE on its first read and every dispatch
     *  behaves as it does on a real headset. Tests that care about a live
     *  old process use {@link ProcessAwareHub} instead. */
    public static class FakeNsdHub extends MainHubActivity {
        static volatile VrSetupDiscovery.Factory sharedFactory;
        @Override protected VrSetupDiscovery createVrSetupDiscovery() {
            VrSetupDiscovery.Factory f = sharedFactory;
            if (f != null) return new VrSetupDiscovery(f);
            return super.createVrSetupDiscovery();
        }
        @Override protected String loadNativeHeadsetIdentity() { return "test.client"; }
        @Override protected java.util.List<PcvrReturnGate.ProcRow> readPcvrProcesses() {
            return new java.util.ArrayList<>();
        }
    }

    /** Paired headset hub: the primary Connect runs the authenticated
     *  probe + start against the {@link FakeHost}. The native headset
     *  identity seam is preserved so a native runtime still gets its
     *  SNI name, and the fake NSD driver keeps any setup discovery in
     *  these tests deterministic. Every dispatch must additionally walk
     *  {@code createHostClient}, because a Restart tap must never reuse
     *  the client of the session that failed. What it hands out is a
     *  fresh {@link FakeHost.Attempt} every time, so a Cancel that
     *  abandons one attempt cannot strand the next one on a cancelled
     *  client. */
    public static class PairedHub extends FakeNsdHub {
        @Override protected boolean hasPairedHost() { return true; }
        @Override protected HostPairing loadHostPairing() { return pairing; }
        @Override protected HostClient createHostClient() {
            FakeHost shared = host;
            shared.clients.incrementAndGet();
            return shared.newAttempt();
        }
    }

    /** Paired hub on the native runtime build, where the restart-consent
     *  and {@code vq_pcvr_allow_restart} contract is mandatory. */
    public static class NativePairedHub extends PairedHub {
        @Override protected boolean usesNativeRuntime() { return true; }
    }

    /** Paired hub on the native runtime build, where the legacy
     *  explicit restart consent is mandatory before a manual start. */
    public static class ManualVrHub extends NativePairedHub {
    }

    /** Paired native hub whose process snapshot the test controls, so the
     *  exit gate can be observed rather than assumed.
     *
     *  <p>The first {@link #aliveReads} snapshots report a live
     *  {@code <package>:pcvr} process owned by our own uid running under
     *  {@link #livePid}; after that the hub reports whatever
     *  {@link #procRows} holds (an empty list by default). Setting
     *  {@link #procRows} to {@code null} makes the platform answer
     *  unknowable, which the gate must treat as a failure rather than as
     *  permission to launch. */
    public static class ProcessAwareHub extends NativePairedHub {
        volatile java.util.List<PcvrReturnGate.ProcRow> procRows = new java.util.ArrayList<>();
        final AtomicInteger processReads = new AtomicInteger();
        volatile int aliveReads = 0;
        volatile int livePid = 4242;
        volatile String liveProcessName = "";
        @Override protected java.util.List<PcvrReturnGate.ProcRow> readPcvrProcesses() {
            int read = processReads.incrementAndGet();
            if (read <= aliveReads && liveProcessName != null && !liveProcessName.isEmpty()) {
                return java.util.Collections.singletonList(new PcvrReturnGate.ProcRow(
                        android.os.Process.myUid(), liveProcessName, livePid));
            }
            return procRows;
        }
    }

    /**
     * Paired native hub whose pairing the test can replace while an
     * attempt is in flight, so a re-pair can be observed at a precise
     * moment — while the Restart prompt is open, or while the fresh
     * probe is in flight — instead of only at a convenient boundary.
     *
     * <p>The process snapshot is the default empty list, so the exit gate
     * resolves immediately and these tests isolate the pinning rules
     * from the process wait.
     */
    public static class SwappablePairingHub extends NativePairedHub {
        volatile HostPairing currentPairing;
        @Override protected HostPairing loadHostPairing() {
            HostPairing current = currentPairing;
            return current != null ? current : super.loadHostPairing();
        }
    }

    /** Driver that fires {@code onDiscoveryStopped} immediately
     *  with no services. {@link VrSetupDiscovery#browse()} exits on
     *  that callback (the polite shutdown path) so the picker /
     *  empty-state dialog renders deterministically. */
    static class EmptyDriver implements VrSetupDiscovery.BrowseDriver {
        final AtomicInteger startCalls = new AtomicInteger();
        final AtomicInteger stopCalls = new AtomicInteger();
        @Override public void start(NsdManager.DiscoveryListener listener) {
            startCalls.incrementAndGet();
            listener.onDiscoveryStopped(VrSetupDiscovery.SERVICE);
        }
        @Override public void stop(NsdManager.DiscoveryListener listener) {
            stopCalls.incrementAndGet();
        }
        @Override public void resolve(NsdServiceInfo info, NsdManager.ResolveListener rl) {
            // no services to resolve
        }
    }

    /** Inject the empty NSD driver for the next
     *  {@link #startHubWithFakeNsd()} / {@link #startManualHub()}
     *  call. Must be cleared in the {@code finally} of the test. */
    private static void useEmptyNsd() {
        FakeNsdHub.sharedFactory = () -> new EmptyDriver();
    }

    private ActivityController<MainHubActivity> startHub() {
        return Robolectric.buildActivity(MainHubActivity.class)
                .create().start().resume();
    }

    /** Variant of {@link #startHub()} that uses {@link FakeNsdHub}. */
    private ActivityController<FakeNsdHub> startHubWithFakeNsd() {
        useEmptyNsd();
        return Robolectric.buildActivity(FakeNsdHub.class)
                .create().start().resume();
    }

    /** Paired-headset fixture. The fake NSD driver is installed so any
     *  setup flow reached from these tests is deterministic. */
    private ActivityController<PairedHub> startPairedHub() {
        useEmptyNsd();
        return Robolectric.buildActivity(PairedHub.class)
                .create().start().resume();
    }

    /** Paired headset on the native runtime, where the restart-consent
     *  contract (and {@code vq_pcvr_allow_restart}) applies. */
    private ActivityController<NativePairedHub> startNativePairedHub() {
        useEmptyNsd();
        return Robolectric.buildActivity(NativePairedHub.class)
                .create().start().resume();
    }

    /** Paired headset on the native runtime with a controllable process
     *  snapshot, for the exit-gate tests. */
    private ActivityController<ProcessAwareHub> startProcessAwareHub() {
        useEmptyNsd();
        ActivityController<ProcessAwareHub> c =
                Robolectric.buildActivity(ProcessAwareHub.class).create().start().resume();
        c.get().liveProcessName = c.get().getPackageName() + SteamVrActivity.PROCESS_SUFFIX;
        return c;
    }

    /** Paired headset on the native runtime, where the legacy explicit
     *  restart consent is mandatory. */
    private ActivityController<ManualVrHub> startManualHub() {
        useEmptyNsd();
        return Robolectric.buildActivity(ManualVrHub.class)
                .create().start().resume();
    }

    /** Paired native hub whose pairing the test controls, for the
     *  "the host changed under this consent" tests. */
    private ActivityController<SwappablePairingHub> startSwappablePairingHub() {
        useEmptyNsd();
        ActivityController<SwappablePairingHub> c =
                Robolectric.buildActivity(SwappablePairingHub.class).create().start().resume();
        c.get().currentPairing = pairing;
        return c;
    }

    /** A public pin that is NOT the paired host's: what a re-pair to a
     *  different PC leaves behind. */
    private static final String OTHER_PIN =
            "3333333333333333333333333333333333333333333333333333333333333333";

    /**
     * Build a pairing that names a different host. The production parser
     * only ever accepts a pin that matches its own certificate, and a
     * re-pair really does produce a different certificate, so the test
     * constructs the value through the same package-private shape the
     * factory fills in. Address and pin are independent: the same helper
     * also produces a host that merely MOVED, which keeps its pin.
     */
    private static HostPairing pairingFor(String address, String pin) throws Exception {
        java.lang.reflect.Constructor<HostPairing> ctor = HostPairing.class
                .getDeclaredConstructor(String.class, String.class, String.class,
                        String.class, String.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(address, pin,
                "0000000000000000000000000000000000000000000000000000000000000009",
                "", "", "", "");
    }

    /** Read the private restart-consent deadline and overwrite it, so a
     *  test can make a consent window expire while the hub is paused. */
    private static void setRestartConsentUntil(MainHubActivity hub, long value) {
        try {
            java.lang.reflect.Field f =
                    MainHubActivity.class.getDeclaredField("restartConsentUntil");
            f.setAccessible(true);
            f.setLong(hub, value);
        } catch (Exception e) {
            throw new AssertionError("cannot write restartConsentUntil", e);
        }
    }

    /**
     * Bounded UI wait. Drives the main looper until {@code condition}
     * is true or {@code maxMillis} elapses, so an assertion can wait
     * on the hub's connect worker (and its UI callback) instead of a
     * fixed sleep. The condition is evaluated once more after a final
     * looper pass.
     */
    private static boolean await(BooleanSupplier condition, long maxMillis) {
        long deadline = System.currentTimeMillis() + maxMillis;
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            if (condition.getAsBoolean()) return true;
            try { Thread.sleep(5L); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return condition.getAsBoolean();
            }
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return condition.getAsBoolean();
    }

    /** Bounded UI drain used only where the assertion is about work
     *  that must NOT have been queued, so there is no positive
     *  completion signal to await. */
    private static void settle(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            try { Thread.sleep(5L); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    /** Dismiss the live dialog and tear the activity down so neither a
     *  modal dialog nor the connect worker survives the test. */
    private static void close(ActivityController<?> controller) {
        PcvrTestActions.dismissLatestDialog();
        try { controller.pause().stop().destroy(); } catch (Throwable ignored) { }
    }

    /** Read the private microphone-permission target so a test can
     *  prove which action a request belongs to and that the target is
     *  cleared once the request is consumed, rejected, cancelled or
     *  destroyed. Zero means "no request this instance started". */
    private static int micTarget(MainHubActivity hub) {
        try {
            java.lang.reflect.Field f =
                    MainHubActivity.class.getDeclaredField("micPermissionTarget");
            f.setAccessible(true);
            return f.getInt(hub);
        } catch (Exception e) {
            throw new AssertionError("cannot read micPermissionTarget", e);
        }
    }

    /** Read the private restart-consent deadline so a test can prove
     *  that a positive consent is (a) opened for a cold start or an
     *  explicit Restart tap and (b) never granted or retained silently,
     *  including across a recreation. */
    private static long restartConsentUntil(MainHubActivity hub) {
        try {
            java.lang.reflect.Field f =
                    MainHubActivity.class.getDeclaredField("restartConsentUntil");
            f.setAccessible(true);
            return f.getLong(hub);
        } catch (Exception e) {
            throw new AssertionError("cannot read restartConsentUntil", e);
        }
    }

    /** The launch-nonce expectation the hub is currently holding, so a
     *  test can build a callback that the hub will accept (and one it
     *  will reject). */
    private static String pendingNonce(MainHubActivity hub) {
        try {
            java.lang.reflect.Field f = MainHubActivity.class.getDeclaredField("returnGate");
            f.setAccessible(true);
            return ((PcvrReturnGate) f.get(hub)).pendingNonce();
        } catch (Exception e) {
            throw new AssertionError("cannot read the return gate", e);
        }
    }

    /**
     * Whether the hub currently believes a process-liveness gate is
     * polling. Read only to pin the ONE invariant a user cannot see
     * directly: while a gate is waiting, a superseded gate's completion
     * must not clear this flag, or a second attempt could be started
     * against a process that is still shutting down. Everything else
     * these tests assert is observed through intents, probes and
     * dialogs.
     */
    private static boolean exitGateRunning(MainHubActivity hub) {
        try {
            java.lang.reflect.Field f =
                    MainHubActivity.class.getDeclaredField("pcvrExitGateRunning");
            f.setAccessible(true);
            return f.getBoolean(hub);
        } catch (Exception e) {
            throw new AssertionError("cannot read pcvrExitGateRunning", e);
        }
    }

    private static String text(View v) { return ((TextView) v).getText().toString(); }

    private static boolean isSteamVrIntent(Intent i) {
        if (i == null || i.getComponent() == null) return false;
        return "com.vibertemis.quest.hub.SteamVrActivity"
                .equals(i.getComponent().getClassName());
    }

    /** The one immersive dispatch the hub recorded, or {@code null}. */
    private static Intent steamVrIntent(StartedIntentLog log) {
        return log.all().stream().filter(MainHubActivityTest::isSteamVrIntent)
                .findFirst().orElse(null);
    }

    /** Rebuild the return Intent a {@link SteamVrActivity} would send for
     *  the dispatch it actually received: same action and flags, the
     *  nonce and public pin echoed back, plus the reporting pid. */
    private static Intent nativeReturn(Intent launch, String issue, int oldPid) {
        Intent back = new Intent();
        back.setAction(MainHubActivity.ACTION_PCVR_RETURN);
        back.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        back.putExtra(MainHubActivity.EXTRA_PCVR_ISSUE, issue);
        back.putExtra(MainHubActivity.EXTRA_PCVR_LAUNCH_NONCE,
                launch == null ? null : launch.getStringExtra(MainHubActivity.EXTRA_PCVR_LAUNCH_NONCE));
        back.putExtra(MainHubActivity.EXTRA_PCVR_HOST_PIN,
                launch == null ? null : launch.getStringExtra(MainHubActivity.EXTRA_PCVR_HOST_PIN));
        back.putExtra(MainHubActivity.EXTRA_PCVR_OLD_PID, oldPid);
        return back;
    }

    /** Deliver a native return Intent the way the system would after a
     *  {@code CLEAR_TOP|SINGLE_TOP} return. Called directly (rather than
     *  through a controller) so the delivery is identical whether or not
     *  the hub happens to be resumed. */
    private static void deliverNativeReturn(MainHubActivity hub, Intent back) {
        hub.onNewIntent(back);
    }

    /** The Restart VR consent dialog, once it is really showing. */
    private static AlertDialog awaitRestartDialog(MainHubActivity hub, long maxMillis) {
        String title = hub.getString(R.string.hub_restart_title);
        if (!PcvrTestActions.awaitDialogTitle(title, maxMillis)) return null;
        return ShadowAlertDialog.getLatestAlertDialog();
    }

    /** The body text of a showing AlertDialog. */
    private static String dialogMessage(AlertDialog dialog) {
        TextView message = dialog.findViewById(android.R.id.message);
        return message == null ? "" : message.getText().toString();
    }

    /** Every value the given Intent carries, so a test can prove no
     *  credential ever rides along with the launch identity. */
    private static java.util.List<Object> intentValues(Intent i) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (i == null || i.getExtras() == null) return out;
        for (String key : i.getExtras().keySet()) {
            Object v = i.getExtras().get(key);
            if (v != null) out.add(v);
        }
        return out;
    }

    /** Bounded UI wait that drives the launch journey through
     *  the picker / Advanced / restart confirmation. The hub first
     *  surfaces a transient "Set up VR" (Searching) dialog while
     *  the discovery worker is on the single-thread executor; the
     *  fake NSD driver fires {@code onDiscoveryStopped}
     *  synchronously inside start() so the picker / empty-state
     *  dialog lands on the same looper pass. We must wait for the
     *  picker / empty-state title specifically — NOT the transient
     *  "Searching…" title — otherwise {@link
     *  PcvrTestActions#confirmRestartIfShown} runs against the
     *  wrong dialog and is a silent no-op. */
    private static boolean stepLaunchJourney(long maxMillis) {
        return com.vibertemis.quest.pcvr.PcvrTestActions.awaitDialogTitle(
                "No VR PC found", maxMillis)
                || com.vibertemis.quest.pcvr.PcvrTestActions.awaitDialogTitle(
                        "Choose your PC for VR", maxMillis);
    }

    /**
     * On a phone (no headtracking), tapping the primary Connect must
     * route to the flat PcView, NOT to SteamVrActivity. The hub must
     * also hide the explicit flat-screen override because it would be
     * redundant with the primary Connect on a non-headset.
     */
    @Test
    public void phone_tapConnect_routesToPcView_notPcvr() {
        setHeadset(false);
        ActivityController<MainHubActivity> c = startHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // The explicit flat override is hidden on phones — the
            // primary Connect already routes to flat.
            View screen = c.get().findViewById(R.id.hub_btn_screen);
            assertEquals("Flat override must be hidden on phones",
                    View.GONE, screen.getVisibility());

            c.get().findViewById(R.id.hub_btn_connect).performClick();
            com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
            log.drain(app());

            assertEquals("Phone must not launch SteamVrActivity", 0, log.countSteamVr());
            assertEquals("Phone Connect must launch PcView",
                    1, log.countComponent(PcView.class.getName()));
        } finally {
            close(c);
        }
    }

    /**
     * Headset, paired, mic denied. The click dispatches the microphone
     * request through the real permission seam; we assert exactly that
     * one request was dispatched, deliver a denied result through the
     * real {@code onRequestPermissionsResult} callback, then assert no
     * SteamVrActivity launch, no host start request, the explicit
     * "PCVR not started" toast, and that the permission target was
     * cleared so nothing can be continued by a later result.
     */
    @Test
    public void headset_micDenied_doesNotLaunchPcvr_andShowsDeniedToast() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals("Paired Connect without mic must dispatch one permission request",
                    1, log.countPermissionRequests());
            assertEquals("Permission pending must not launch SteamVrActivity",
                    0, log.countSteamVr());
            assertEquals("Permission pending must not reach the host",
                    0, host.starts.get());
            assertTrue("the request must remember the action it belongs to",
                    micTarget(c.get()) != 0);

            // Deliver the denial through the real permission callback path.
            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_DENIED});
            settle(250);
            log.drain(app());

            assertEquals("Mic denied must not launch SteamVrActivity",
                    0, log.countSteamVr());
            assertEquals("Mic denied must not start the host",
                    0, host.starts.get());
            assertEquals("a denied result must clear the permission target",
                    0, micTarget(c.get()));
            assertEquals(1, ShadowToast.shownToastCount());
            CharSequence msg = ShadowToast.getTextOfLatestToast();
            assertNotNull(msg);
            assertTrue("Denied toast must say PCVR not started: " + msg,
                    msg.toString().toLowerCase().contains("pcvr not started"));
            assertNotNull("Denial must offer the mic recovery dialog",
                    ShadowAlertDialog.getLatestAlertDialog());
        } finally {
            close(c);
        }
    }

    /**
     * Headset, mic granted at click time, but UNPAIRED. The primary
     * action is "Set up PC", so the hub opens the VR-setup picker /
     * empty state and the test drives it through Advanced → Manual
     * VR → Connect restart confirmation. SteamVrActivity launches with
     * the explicit ComponentName, the immersive VR categories and
     * FLAG_ACTIVITY_NEW_TASK. No microphone request may be dispatched:
     * the permission was already granted.
     */
    @Test
    public void headset_micGranted_launchesExplicitSteamVrActivity() {
        setHeadset(true);
        grantMic(true);
        ActivityController<FakeNsdHub> c = startHubWithFakeNsd();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // The explicit flat override is visible on a headset because
            // it differs from the primary Connect target there.
            View screen = c.get().findViewById(R.id.hub_btn_screen);
            assertEquals("Flat override must be visible on headsets",
                    View.VISIBLE, screen.getVisibility());
            assertEquals("an unpaired headset reads Set up PC on the primary action",
                    c.get().getString(R.string.hub_btn_setup_pc),
                    text(c.get().findViewById(R.id.hub_btn_connect)));

            c.get().findViewById(R.id.hub_btn_connect).performClick();

            // Bounded UI wait for the picker / empty-state dialog to
            // surface, then drive the journey to the restart-consent
            // confirmation. Production dialog order is preserved exactly:
            // picker empty-state → Advanced VR pairing → Connect to PCVR.
            assertTrue("picker must surface for unpaired Connect",
                    stepLaunchJourney(4000L));
            com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
            assertTrue("the manual VR journey must dispatch SteamVrActivity",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            Intent pcvr = log.all().stream().filter(MainHubActivityTest::isSteamVrIntent)
                    .findFirst().orElse(null);
            assertNotNull("Headset + granted mic must launch SteamVrActivity",
                    pcvr);
            assertEquals("com.vibertemis.quest.hub.SteamVrActivity",
                    pcvr.getComponent().getClassName());
            assertTrue(pcvr.hasCategory("com.oculus.intent.category.VR"));
            assertTrue(pcvr.hasCategory("org.khronos.openxr.intent.category.IMMERSIVE_HMD"));
            assertTrue((pcvr.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
            assertEquals("Manual VR must not ask for an already granted mic",
                    0, log.countPermissionRequests());
        } finally {
            close(c);
        }
    }

    /**
     * Explicit flat override on a headset: tapping Screen gaming
     * launches the flat PcView, NOT SteamVrActivity. The flat path
     * must not require mic permission because it does not start
     * SteamVR.
     */
    @Test
    public void headset_tapFlatOverride_launchesPcView() {
        setHeadset(true);
        grantMic(false);
        ActivityController<MainHubActivity> c = startHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_screen).performClick();
            com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
            log.drain(app());

            assertEquals("Explicit flat override on a headset must NOT launch PCVR",
                    0, log.countSteamVr());
            assertEquals("Explicit flat override on a headset must launch PcView",
                    1, log.countComponent(PcView.class.getName()));
        } finally {
            close(c);
        }
    }

    /**
     * The unpaired primary action discovers and pairs BEFORE anything
     * asks for the microphone. The setup flow itself must therefore
     * dispatch no permission request, must not launch PCVR, and must
     * not touch the host; the microphone is only requested once the
     * user explicitly picks a VR action out of that flow (Manual VR).
     */
    @Test
    public void unpairedHeadset_setUpPc_pairsBeforeAnyMicRequest() {
        setHeadset(true);
        grantMic(false);
        ActivityController<FakeNsdHub> c = startHubWithFakeNsd();
        StartedIntentLog log = new StartedIntentLog();
        try {
            assertEquals("an unpaired headset reads Set up PC on the primary action",
                    c.get().getString(R.string.hub_btn_setup_pc),
                    text(c.get().findViewById(R.id.hub_btn_connect)));

            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("Set up PC must surface the VR-setup picker",
                    stepLaunchJourney(4000L));
            log.drain(app());
            assertEquals("Set up PC must not request the microphone",
                    0, log.countPermissionRequests());
            assertEquals("Set up PC must not launch SteamVrActivity",
                    0, log.countSteamVr());
            assertEquals("Set up PC must not probe a host before pairing",
                    0, host.probes.get());

            // The setup surfaces stay live, so the flow is reachable and
            // the explicit VR action below really is an explicit choice.
            assertTrue("the empty setup state must offer Advanced",
                    PcvrTestActions.stepAdvancedIfPicker());
            assertTrue("Advanced must offer Manual VR",
                    PcvrTestActions.stepManualVr());
            log.drain(app());
            assertEquals("an explicit Manual VR must request the microphone once",
                    1, log.countPermissionRequests());
            assertEquals("nothing may launch before the mic is granted",
                    0, log.countSteamVr());
        } finally {
            close(c);
        }
    }

    /**
     * Rapid repeated taps on Connect with mic already granted must not
     * start two attempts. The taps land while the first attempt is
     * still in flight on the host worker (held open by the fake host's
     * latch), so the assertion measures the guard itself: exactly one
     * authenticated probe, one host start request and one activity
     * dispatch.
     */
    @Test
    public void rapidTapConnect_doesNotLaunchTwice() {
        setHeadset(true);
        grantMic(true);
        host.release = new CountDownLatch(1);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            View connect = c.get().findViewById(R.id.hub_btn_connect);
            connect.performClick();
            assertTrue("the first tap must reach the host start request",
                    await(() -> host.starts.get() == 1, 4000L));

            // Rapid taps land while the same attempt is still in flight.
            connect.performClick();
            connect.performClick();
            connect.performClick();
            settle(200);
            log.drain(app());
            assertEquals("no PCVR launch may happen before the in-flight start returns",
                    0, log.countSteamVr());
            assertEquals("rapid taps must not queue a second host start",
                    1, host.starts.get());

            host.release.countDown();
            assertTrue("the accepted attempt must launch PCVR exactly once",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));
            settle(300);
            log.drain(app());

            assertEquals("Rapid taps must yield exactly one PCVR launch",
                    1, log.countSteamVr());
            assertEquals("Rapid taps must yield exactly one host start request",
                    1, host.starts.get());
            assertEquals("Rapid taps must yield exactly one authenticated probe",
                    1, host.probes.get());
        } finally {
            close(c);
        }
    }

    /**
     * Rapid taps when the mic is not yet granted must still only
     * dispatch one permission request — the request guard blocks the
     * following taps until the result returns, and nothing may reach
     * the host meanwhile.
     */
    @Test
    public void rapidTapConnect_micNotGranted_dispatchesOneRequest() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            View connect = c.get().findViewById(R.id.hub_btn_connect);
            connect.performClick();
            connect.performClick();
            connect.performClick();
            settle(250);
            log.drain(app());

            assertEquals("Rapid taps must dispatch exactly one permission request",
                    1, log.countPermissionRequests());
            assertEquals("Permission pending must not launch SteamVrActivity",
                    0, log.countSteamVr());
            assertEquals("Permission pending must not reach the host",
                    0, host.probes.get());
            assertTrue("the in-flight request must remember its action",
                    micTarget(c.get()) != 0);
        } finally {
            close(c);
        }
    }

    /**
     * A permission result for a different permission name must be
     * ignored, even if the grant is positive, and it must consume the
     * pending request: a later, correctly named result belongs to no
     * target and must not start PCVR.
     */
    @Test
    public void mismatchedPermissionResult_doesNotLaunch() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // Dispatch a Connect tap so a permission request is in flight.
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            // Deliver a result whose permission name is not RECORD_AUDIO.
            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.CAMERA},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(250);
            log.drain(app());
            assertEquals("Mismatched permission must not launch PCVR",
                    0, log.countSteamVr());
            assertEquals("Mismatched permission must not reach the host",
                    0, host.probes.get());
            assertEquals("a mismatched result must clear the permission target",
                    0, micTarget(c.get()));

            // The request is consumed, so even a correctly named grant
            // cannot continue the dropped action.
            grantMic(true);
            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(250);
            log.drain(app());
            assertEquals("A result with no captured target must not launch PCVR",
                    0, log.countSteamVr());
            assertEquals("A result with no captured target must not reach the host",
                    0, host.probes.get());

            // A fresh explicit tap works normally again.
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("a fresh tap after a dropped request must launch PCVR",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));
            assertEquals(1, host.starts.get());
        } finally {
            close(c);
        }
    }

    /**
     * Empty grant results array (the system killed the activity before
     * delivering) must surface the recovery dialog, never launch PCVR,
     * and clear the captured action.
     */
    @Test
    public void emptyGrantResults_doesNotLaunch_showsRecovery() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{});
            settle(250);
            log.drain(app());

            assertEquals("Empty grant results must not launch PCVR",
                    0, log.countSteamVr());
            assertEquals("Empty grant results must not reach the host",
                    0, host.probes.get());
            assertEquals("An empty result must clear the permission target",
                    0, micTarget(c.get()));
            assertNotNull("Empty grant results must surface the recovery dialog",
                    ShadowAlertDialog.getLatestAlertDialog());
        } finally {
            close(c);
        }
    }

    /**
     * Duplicate permission results for the same request code must be
     * ignored. The first result consumes the in-flight request AND its
     * captured action, so the duplicate delivery is a no-op: exactly
     * one probe, one host start and one activity dispatch.
     */
    @Test
    public void duplicatePermissionResult_doesNotLaunchTwice() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            // First result — granted, drives one probe and one launch.
            grantMic(true);
            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            assertTrue("the granted result must launch PCVR exactly once",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            // Second delivery of the same grant — must be a no-op.
            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(300);
            log.drain(app());

            assertEquals("Duplicate permission results must yield one launch",
                    1, log.countSteamVr());
            assertEquals("Duplicate permission results must yield one host start",
                    1, host.starts.get());
            assertEquals("Duplicate permission results must yield one probe",
                    1, host.probes.get());
            assertEquals("The consumed result must leave no captured target",
                    0, micTarget(c.get()));
        } finally {
            close(c);
        }
    }

    /**
     * Granting phone-style mic (CAMERA, not RECORD_AUDIO) for the
     * request code path that goes through the Connect button must not
     * launch PCVR. The hub keys the result on the permission NAME, not
     * the request code alone.
     */
    @Test
    public void grantCameraInsteadOfMic_doesNotLaunch() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.CAMERA},
                    new int[]{PackageManager.PERMISSION_DENIED,
                            PackageManager.PERMISSION_GRANTED});
            settle(250);
            log.drain(app());

            assertEquals("Recording denied must not launch PCVR even if camera granted",
                    0, log.countSteamVr());
            assertEquals("Recording denied must not reach the host",
                    0, host.probes.get());
            assertEquals("A denied microphone must clear the permission target",
                    0, micTarget(c.get()));
        } finally {
            close(c);
        }
    }

    /**
     * Returning from Streaming settings must NOT auto-launch anything.
     * Only an explicit user tap can dispatch a target activity. The hub
     * never auto-launches from {@code onResume}.
     */
    @Test
    public void returningFromSettings_doesNotAutoLaunch() {
        setHeadset(true);
        grantMic(true);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // Tap settings -> simulate the user going there and coming back.
            c.get().findViewById(R.id.hub_btn_settings).performClick();
            com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
            log.drain(app());
            assertEquals("Settings tap must launch the Vibertemis settings screen",
                    1, log.countComponent(QuestSettingsActivity.class.getName()));

            // Simulate returning from the settings screen.
            c.pause();
            c.resume();
            settle(250);
            log.drain(app());

            assertEquals("Returning from settings must not auto-launch any activity",
                    1, log.size());
            assertEquals("Returning from settings must not reach the host",
                    0, host.probes.get());
        } finally {
            close(c);
        }
    }

    /**
     * The request guard survives activity recreation (configuration
     * change). When the activity is recreated mid-permission-dialog,
     * the saved state restores {@code requestPending} so a second
     * dispatch of the same request code is suppressed. The captured
     * ACTION is deliberately not restored: a stray grant that lands on
     * the recreated hub must not replay the intent of the destroyed
     * instance. Only a fresh explicit tap may launch.
     */
    @Test
    public void savedState_restoresRequestGuardAcrossRecreation() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        ActivityController<PairedHub> reborn = null;
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals("the request must be dispatched before recreation",
                    1, log.countPermissionRequests());

            // Save the state mid-request.
            Bundle state = new Bundle();
            c.get().onSaveInstanceState(state);
            assertTrue("Request flag must be persisted",
                    state.getBoolean(STATE_REQUEST_PENDING));
            close(c);
            c = null;

            reborn = Robolectric.buildActivity(PairedHub.class)
                    .create(state).start().resume();

            // The guard is restored, so a tap cannot dispatch a second
            // request while the system dialog is still up.
            reborn.get().findViewById(R.id.hub_btn_connect).performClick();
            settle(250);
            log.drain(app());
            assertEquals("the restored request guard must suppress a second request",
                    1, log.countPermissionRequests());
            assertEquals("The recreated hub must not inherit a captured action",
                    0, micTarget(reborn.get()));

            // A stray grant on the recreated hub has no target to
            // continue, so it must never launch PCVR — even though the
            // microphone really is granted at this point.
            grantMic(true);
            reborn.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(300);
            log.drain(app());
            assertEquals("A stale result after recreation must not launch PCVR",
                    0, log.countSteamVr());
            assertEquals("A stale result after recreation must not reach the host",
                    0, host.probes.get());

            // A fresh explicit tap on the recreated hub works end to end.
            reborn.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("a fresh tap on the recreated hub must launch PCVR",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));
            settle(300);
            log.drain(app());
            assertEquals("New tap on recreated hub with mic granted must launch once",
                    1, log.countSteamVr());
            assertEquals("New tap on recreated hub must reach the host once",
                    1, host.starts.get());
        } finally {
            if (reborn != null) close(reborn);
            if (c != null) close(c);
        }
    }

    /**
     * Permission lifecycle race regression. The dialog MAY pause the
     * hub on some platform versions before {@code launchPending} is
     * set; we simulate that ordering by calling pause() BEFORE the
     * grant callback. The permission-return onResume must therefore
     * NOT release the guard (no actual launched-activity pause ever
     * happened), and a follow-up Screen or PCVR tap during the
     * permission round-trip must be a no-op. After an actual leave-
     * and-return pause/resume cycle, the hub must accept a fresh tap.
     *
     * <p>We observe the started intents (no mirrored boolean asserts)
     * so a regression in either the guard OR the onResume ordering
     * surfaces as the wrong intent count.
     */
    @Test
    public void permissionDialogDoesNotClearLaunchGuard() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // Tap Connect — dispatches a permission request and sets
            // requestPending. No SteamVrActivity launch yet (mic denied).
            View connect = c.get().findViewById(R.id.hub_btn_connect);
            connect.performClick();
            log.drain(app());
            assertEquals("PCVR tap before grant must dispatch one permission request",
                    1, log.countPermissionRequests());
            assertEquals("PCVR tap before grant must not launch",
                    0, log.countSteamVr());

            // Simulate the dialog pausing the hub BEFORE the grant
            // callback returns. Some platform versions do pause the
            // hosting activity here; launchLeftHub is still false
            // (launchPending was never set) so onResume must keep the
            // guard set.
            c.pause();
            c.resume();
            connect.performClick();
            settle(250);
            log.drain(app());
            assertEquals("the permission round-trip must survive a pause/resume",
                    1, log.countPermissionRequests());
            assertEquals("the permission pause must not launch PCVR",
                    0, log.countSteamVr());

            // Grant mic AND deliver the result through the real callback.
            // The paired hub then probes the host and dispatches once.
            grantMic(true);
            c.get().onRequestPermissionsResult(
                    REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            assertTrue("the grant callback must reach the host start",
                    await(() -> host.starts.get() == 1, 4000L));
            assertTrue("Grant callback must launch PCVR exactly once",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            // Second tap during the (still set) launch guard window must
            // NOT launch anything — the hub never observed a real
            // launched-activity pause for the FIRST tap, so launchLeftHub
            // is still false and launchPending is still true.
            connect.performClick();
            settle(250);
            log.drain(app());
            assertEquals("Second PCVR tap while guard is still set must not launch",
                    1, log.countSteamVr());
            assertEquals("Second PCVR tap must not reach the host again",
                    1, host.starts.get());

            // Other buttons are also guarded during the in-flight launch.
            c.get().findViewById(R.id.hub_btn_screen).performClick();
            settle(250);
            log.drain(app());
            assertEquals("Screen tap during PCVR launch must be blocked",
                    0, log.countComponent(PcView.class.getName()));

            // Now simulate the user actually leaving and returning from
            // the launched PCVR activity. pause() with launchPending=true
            // sets launchLeftHub=true; resume() then clears launchPending.
            c.pause();
            c.resume();

            // A fresh tap on the explicit flat override now launches the
            // flat PcView (the paired Connect still routes to
            // SteamVrActivity, not Screen gaming), and the return must
            // not replay the PCVR launch.
            c.get().findViewById(R.id.hub_btn_screen).performClick();
            settle(250);
            log.drain(app());
            assertEquals("Screen tap after actual leave-and-return must launch",
                    1, log.countComponent(PcView.class.getName()));
            assertEquals("returning from PCVR must not relaunch PCVR",
                    1, log.countSteamVr());
        } finally {
            close(c);
        }
    }

    /**
     * A grant that arrives while the hub is paused must be queued and
     * continued exactly once on the next resume, into the action that
     * asked for it. While the round-trip is pending nothing may launch
     * and no setup / restart prompt may appear, and a later resume must
     * not replay the queued continuation.
     */
    @Test
    public void permissionGrantWhilePausedContinuesOnceOnResume() {
        setHeadset(true);
        grantMic(false);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            log.drain(app());
            assertEquals("paired Connect without mic must request the mic once",
                    1, log.countPermissionRequests());

            // No VR-setup picker and no restart prompt may appear while
            // the permission round-trip is still pending.
            assertFalse("no VR-setup picker may appear while the mic is requested",
                    PcvrTestActions.awaitDialogTitle("No VR PC found", 300L));
            assertFalse("no restart consent may appear before the mic is granted",
                    PcvrTestActions.awaitDialogTitle("Restart VR", 100L));

            c.pause();
            grantMic(true);
            c.get().onRequestPermissionsResult(REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(250);
            log.drain(app());
            assertEquals("a grant while paused must not launch before resume",
                    0, log.countSteamVr());
            assertEquals("a grant while paused must not reach the host yet",
                    0, host.probes.get());

            c.resume();
            assertTrue("the resumed hub must continue the connect",
                    await(() -> host.starts.get() == 1, 4000L));
            assertTrue("the resumed hub must launch PCVR exactly once",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            // A later resume must not replay the queued continuation.
            c.pause();
            c.resume();
            settle(250);
            log.drain(app());
            assertEquals("a later resume must not relaunch PCVR",
                    1, log.countSteamVr());
            assertEquals("a later resume must not queue a second host start",
                    1, host.starts.get());
            assertEquals("a later resume must not probe the host again",
                    1, host.probes.get());
        } finally {
            close(c);
        }
    }

    /**
     * Manual VR with no microphone permission must request it instead
     * of silently returning, and the grant must still leave the
     * explicit legacy restart consent between the user and VR.
     */
    @Test
    public void manualVr_withoutMic_requestsPermissionThenConsentThenLaunch() {
        setHeadset(true);
        grantMic(false);
        ActivityController<ManualVrHub> c = startManualHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            assertTrue("the setup flow must surface the picker",
                    stepLaunchJourneyViaSetupLink(c));
            assertTrue("Advanced must be reachable", PcvrTestActions.stepAdvancedIfPicker());
            assertTrue("Manual VR must be reachable", PcvrTestActions.stepManualVr());
            log.drain(app());

            assertEquals("Manual VR without mic must request the mic once",
                    1, log.countPermissionRequests());
            assertEquals("Manual VR must not fabricate consent",
                    0, log.countSteamVr());
            assertEquals("Manual VR must not reach the host before the grant",
                    0, host.probes.get());
            assertTrue("the mic request must be tagged as the manual VR action",
                    micTarget(c.get()) != 0);
            assertFalse("Manual VR must not skip the legacy consent",
                    PcvrTestActions.awaitDialogTitle("Connect to PCVR?", 200L));

            grantMic(true);
            c.get().onRequestPermissionsResult(REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            assertTrue("the granted mic must surface the legacy restart consent",
                    PcvrTestActions.awaitDialogTitle("Connect to PCVR?", 4000L));
            log.drain(app());
            assertEquals("the mic grant alone must not launch PCVR",
                    0, log.countSteamVr());

            AlertDialog consent = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("the legacy restart consent must be showing", consent);
            Button connect = consent.getButton(AlertDialog.BUTTON_POSITIVE);
            assertEquals("Connect", connect.getText().toString());
            connect.performClick();
            assertTrue("the confirmed manual start must launch PCVR exactly once",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));
            settle(250);
            log.drain(app());
            assertEquals("Manual VR must launch PCVR exactly once",
                    1, log.countSteamVr());
            assertEquals("Manual VR must consume the permission target",
                    0, micTarget(c.get()));
        } finally {
            close(c);
        }
    }

    /**
     * A denied microphone on the Manual VR path must surface the
     * "PCVR not started" toast, never launch PCVR, and clear the
     * captured action so nothing is continued later.
     */
    @Test
    public void manualVr_micDenied_doesNotLaunch_andClearsTarget() {
        setHeadset(true);
        grantMic(false);
        ActivityController<ManualVrHub> c = startManualHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            assertTrue("the setup flow must surface the picker",
                    stepLaunchJourneyViaSetupLink(c));
            assertTrue(PcvrTestActions.stepAdvancedIfPicker());
            assertTrue(PcvrTestActions.stepManualVr());
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            c.get().onRequestPermissionsResult(REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_DENIED});
            settle(300);
            log.drain(app());

            assertEquals("a denied mic must not launch PCVR", 0, log.countSteamVr());
            assertEquals("a denied mic must not reach the host", 0, host.probes.get());
            assertEquals("a denied mic must clear the captured action",
                    0, micTarget(c.get()));
            assertEquals(1, ShadowToast.shownToastCount());
            CharSequence msg = ShadowToast.getTextOfLatestToast();
            assertNotNull(msg);
            assertTrue("Denied toast must say PCVR not started: " + msg,
                    msg.toString().toLowerCase().contains("pcvr not started"));
            assertFalse("a denied mic must not surface the restart consent",
                    PcvrTestActions.awaitDialogTitle("Connect to PCVR?", 200L));
        } finally {
            close(c);
        }
    }

    /**
     * An empty grant result on the Manual VR path is treated as a
     * cancelled round-trip: recovery is offered, nothing launches, and
     * the captured action is dropped.
     */
    @Test
    public void manualVr_emptyGrantResult_showsRecovery_andNeverLaunches() {
        setHeadset(true);
        grantMic(false);
        ActivityController<ManualVrHub> c = startManualHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            assertTrue("the setup flow must surface the picker",
                    stepLaunchJourneyViaSetupLink(c));
            assertTrue(PcvrTestActions.stepAdvancedIfPicker());
            assertTrue(PcvrTestActions.stepManualVr());
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            c.get().onRequestPermissionsResult(REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{});
            settle(300);
            log.drain(app());

            assertEquals("a cancelled mic round-trip must not launch PCVR",
                    0, log.countSteamVr());
            assertEquals("a cancelled mic round-trip must not reach the host",
                    0, host.probes.get());
            assertEquals("a cancelled mic round-trip must clear the captured action",
                    0, micTarget(c.get()));
            assertNotNull("a cancelled mic round-trip must offer recovery",
                    ShadowAlertDialog.getLatestAlertDialog());
        } finally {
            close(c);
        }
    }

    /**
     * A Manual VR grant that lands while the hub is paused is queued
     * and continued once on resume — into the legacy restart consent,
     * not straight into a launch.
     */
    @Test
    public void manualVr_grantWhilePaused_consentsOnceOnResume() {
        setHeadset(true);
        grantMic(false);
        ActivityController<ManualVrHub> c = startManualHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            assertTrue("the setup flow must surface the picker",
                    stepLaunchJourneyViaSetupLink(c));
            assertTrue(PcvrTestActions.stepAdvancedIfPicker());
            assertTrue(PcvrTestActions.stepManualVr());
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            c.pause();
            grantMic(true);
            c.get().onRequestPermissionsResult(REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(300);
            log.drain(app());
            assertEquals("a paused manual grant must not launch before resume",
                    0, log.countSteamVr());
            assertFalse("a paused manual grant must not surface consent while paused",
                    PcvrTestActions.awaitDialogTitle("Connect to PCVR?", 200L));

            c.resume();
            assertTrue("the resumed hub must surface the legacy consent",
                    PcvrTestActions.awaitDialogTitle("Connect to PCVR?", 4000L));
            log.drain(app());
            assertEquals("the resumed manual grant must still require consent",
                    0, log.countSteamVr());
            AlertDialog consent = ShadowAlertDialog.getLatestAlertDialog();
            consent.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue("the confirmed manual start must launch PCVR once",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            // A later resume must not replay the queued continuation or
            // re-open the consent prompt.
            c.pause();
            c.resume();
            settle(300);
            log.drain(app());
            assertEquals("a later resume must not relaunch manual VR",
                    1, log.countSteamVr());
            assertEquals("a later resume must not probe the host",
                    0, host.probes.get());
        } finally {
            close(c);
        }
    }

    /**
     * An explicit Cancel abandons a Manual VR permission continuation
     * that was queued while the hub was paused: the queued action is
     * dropped, so the resume surfaces neither the consent prompt nor a
     * launch.
     */
    @Test
    public void manualVr_pausedGrantCancelled_neverConsentsOrLaunches() {
        setHeadset(true);
        grantMic(false);
        ActivityController<ManualVrHub> c = startManualHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            assertTrue("the setup flow must surface the picker",
                    stepLaunchJourneyViaSetupLink(c));
            assertTrue(PcvrTestActions.stepAdvancedIfPicker());
            assertTrue(PcvrTestActions.stepManualVr());
            log.drain(app());
            assertEquals(1, log.countPermissionRequests());

            c.pause();
            grantMic(true);
            c.get().onRequestPermissionsResult(REQ_MIC_FOR_STEAMVR,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    new int[]{PackageManager.PERMISSION_GRANTED});
            settle(250);

            // The user cancels the queued action before returning.
            c.get().findViewById(R.id.hub_btn_cancel_connection).performClick();
            c.resume();
            settle(300);
            log.drain(app());

            assertFalse("a cancelled continuation must not surface the consent",
                    PcvrTestActions.awaitDialogTitle("Connect to PCVR?", 200L));
            assertEquals("a cancelled continuation must not launch PCVR",
                    0, log.countSteamVr());
            assertEquals("a cancelled continuation must not reach the host",
                    0, host.probes.get());
        } finally {
            close(c);
        }
    }

    /**
     * Setup is also guarded by launchPending. A tap on Setup during
     * an in-flight launch must be a no-op. Once the user actually
     * returns from Setup, the guard releases.
     */
    @Test
    public void setupAndSettingsGuardedDuringInflightLaunch() {
        setHeadset(true);
        grantMic(true);
        ActivityController<PairedHub> c = startPairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // Tap Connect — one probe, one start, one launch; the launch
            // sets launchPending.
            View connect = c.get().findViewById(R.id.hub_btn_connect);
            connect.performClick();
            assertTrue("the accepted attempt must launch PCVR",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            // Setup tap during in-flight launch must NOT launch SetupActivity.
            c.get().findViewById(R.id.hub_btn_setup).performClick();
            settle(250);
            log.drain(app());
            assertEquals("Setup during PCVR launch must be blocked", 0,
                    log.countComponent("com.vibertemis.quest.hub.SetupActivity"));
            assertFalse("Setup during PCVR launch must not open the setup flow",
                    PcvrTestActions.awaitDialogTitle("Set up VR", 200L));

            // Settings tap during in-flight launch must NOT launch
            // the settings screen.
            c.get().findViewById(R.id.hub_btn_settings).performClick();
            settle(250);
            log.drain(app());
            assertEquals("Settings during PCVR launch must be blocked", 0,
                    log.countComponent(QuestSettingsActivity.class.getName()));

            // After actual leave-and-return, Setup opens the VR-setup
            // flow. The hub must surface a visible dialog, not
            // auto-launch anything.
            c.pause();
            c.resume();
            c.get().findViewById(R.id.hub_btn_setup).performClick();
            // The picker / searching dialog must surface because the hub
            // runs a discovery pass on its worker; the fake NSD driver
            // makes it resolve to the empty state immediately.
            assertTrue("Setup must surface a visible dialog after return",
                    PcvrTestActions.awaitDialogTitle("Set up VR", 2000L)
                    || PcvrTestActions.awaitDialogTitle("No VR PC found", 2000L)
                    || PcvrTestActions.awaitDialogTitle("Choose your PC for VR", 2000L));
        } finally {
            close(c);
        }
    }

    /**
     * Phone Connect must never request mic permission. The flat
     * PcView path does not need RECORD_AUDIO; asking for it would
     * surface an unrelated permission dialog.
     */
    @Test
    public void phone_connect_doesNotRequestMicPermission() {
        setHeadset(false);
        grantMic(false);
        ActivityController<MainHubActivity> c = startHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            com.vibertemis.quest.pcvr.PcvrTestActions.confirmRestartIfShown();
            log.drain(app());

            assertEquals("Phone Connect must not dispatch a permission request",
                    0, log.countPermissionRequests());
            assertEquals("Phone Connect must not dispatch any component-less intent",
                    0, log.countComponentlessIntents());
            assertEquals("Phone Connect must launch PcView",
                    1, log.countComponent(PcView.class.getName()));
        } finally {
            close(c);
        }
    }

    /**
     * Tap the paired hub's "Change PC" link and wait for the VR-setup
     * picker / empty state. The Manual VR fixtures need the Advanced
     * entry point, which only the setup flow surfaces.
     */
    private boolean stepLaunchJourneyViaSetupLink(
            ActivityController<? extends MainHubActivity> controller) {
        controller.get().findViewById(R.id.hub_btn_setup).performClick();
        return stepLaunchJourney(4000L);
    }

    /* ------------------------------------------------------------
     * Warm-server journey: no prompt, no restart permission.
     * ------------------------------------------------------------ */

    /**
     * A WARM {@code vrserver=true} probe is an ordinary idempotent
     * connection, not a reason to interrupt the user. Exactly one
     * authenticated probe, one host start and one dispatch, no dialog,
     * and the launch carries {@code vq_pcvr_allow_restart=false} with a
     * ZERO deadline — so the native side cannot restart a healthy VR
     * session it was not asked to touch.
     */
    @Test
    public void warmServer_connectsWithoutPromptAndWithoutAllowRestart() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<NativePairedHub> c = startNativePairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("a warm host must still dispatch PCVR",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            assertFalse("a warm probe must NOT prompt for a restart",
                    PcvrTestActions.awaitDialogTitle(
                            c.get().getString(R.string.hub_restart_title), 300L));
            assertEquals("exactly one authenticated probe", 1, host.probes.get());
            assertEquals("exactly one host start request", 1, host.starts.get());
            assertEquals("exactly one client", 1, host.clients.get());

            Intent launch = steamVrIntent(log);
            assertNotNull("the launch intent must be recorded", launch);
            assertFalse("a warm connection must not grant restart permission",
                    launch.getBooleanExtra("vq_pcvr_allow_restart", true));
            assertEquals("a warm connection must carry a zero restart deadline",
                    0L, launch.getLongExtra("vq_pcvr_restart_until_ms", -1L));
            assertFalse("no restart consent may be open for a warm connection",
                    restartConsentUntil(c.get()) > 0L);

            // The correlation identity travels with the launch.
            String nonce = launch.getStringExtra(MainHubActivity.EXTRA_PCVR_LAUNCH_NONCE);
            assertNotNull("the launch must carry a nonce", nonce);
            assertTrue("the nonce must be a real value", nonce.length() > 16);
            assertEquals("the public pairing pin must travel with the launch",
                    pairing.pin, launch.getStringExtra(MainHubActivity.EXTRA_PCVR_HOST_PIN));

            // ... and no credential may ever ride in an Intent.
            for (Object value : intentValues(launch)) {
                assertFalse("no Intent extra may carry the pairing token: " + value,
                        pairing.token.equals(String.valueOf(value)));
            }
        } finally {
            close(c);
        }
    }

    /**
     * The cold behaviour is unchanged: a {@code vrserver=false} probe
     * opens the 120-second consent window and starts without any dialog,
     * and that live consent is what authorises
     * {@code vq_pcvr_allow_restart} on the native runtime.
     */
    @Test
    public void coldServer_opensConsentWindowAndGrantsAllowRestart() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(false);
        ActivityController<NativePairedHub> c = startNativePairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("a cold host must dispatch PCVR",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            assertFalse("a cold start must not prompt either",
                    PcvrTestActions.awaitDialogTitle(
                            c.get().getString(R.string.hub_restart_title), 300L));
            assertTrue("the cold probe must open the consent window",
                    restartConsentUntil(c.get()) > android.os.SystemClock.elapsedRealtime());
            Intent launch = steamVrIntent(log);
            assertNotNull(launch);
            assertTrue("a consented cold start may restart SteamVR on the PC",
                    launch.getBooleanExtra("vq_pcvr_allow_restart", false));
            assertTrue("a consented start carries its deadline",
                    launch.getLongExtra("vq_pcvr_restart_until_ms", 0L) > 0L);
        } finally {
            close(c);
        }
    }

    /* ------------------------------------------------------------
     * Native mismatch -> Restart / Cancel -> fresh authenticated re-probe.
     * ------------------------------------------------------------ */

    /**
     * The Restart VR dialog is reserved for an ACTUAL native mismatch.
     * After a warm connect, the return Intent that {@link SteamVrActivity}
     * would send makes the dialog appear — but nothing restarts by
     * itself: no second probe, no second dispatch until the user presses
     * Restart VR. That tap then rebuilds the attempt from scratch with a
     * NEW client and a fresh authenticated probe against the SAME
     * pairing, and only then opens the consent window and dispatches with
     * restart permission. The dialog never repeats on its own.
     */
    @Test
    public void nativeRestartRequired_promptsThenReprobesWithNewClient() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<NativePairedHub> c = startNativePairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue(await(() -> {
                log.drain(app());
                return log.countSteamVr() == 1;
            }, 4000L));
            Intent first = steamVrIntent(log);
            assertNotNull(first);

            // Simulate the immersive activity coming back to the hub.
            c.pause();
            c.resume();
            deliverNativeReturn(c.get(), nativeReturn(first,
                    PcvrReturnGate.ISSUE_RESTART_REQUIRED, 4242));

            AlertDialog prompt = awaitRestartDialog(c.get(), 4000L);
            assertNotNull("a real native mismatch must surface the Restart prompt", prompt);
            assertEquals("the prompt must give the SETTINGS reason for a restart",
                    c.get().getString(R.string.hub_restart_message), dialogMessage(prompt));
            settle(300);
            log.drain(app());
            assertEquals("a valid callback must never auto-connect", 1, log.countSteamVr());
            assertEquals("a valid callback must not re-probe on its own", 1, host.probes.get());

            prompt.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue("Restart VR must re-probe the same pairing",
                    await(() -> host.probes.get() == 2, 4000L));
            assertTrue("the confirmed restart must dispatch again",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 2;
                    }, 4000L));
            settle(300);
            log.drain(app());

            assertEquals("the re-probe must use a NEW client, never the stale one",
                    2, host.clients.get());
            assertEquals("one dispatch per accepted attempt", 2, log.countSteamVr());
            assertEquals("the retry must not loop the restart dialog",
                    false, PcvrTestActions.awaitDialogTitle(
                            c.get().getString(R.string.hub_restart_title), 300L));

            Intent second = null;
            for (Intent i : log.all()) {
                if (isSteamVrIntent(i)) second = i;
            }
            assertNotNull(second);
            assertTrue("the confirmed restart may restart SteamVR on the PC",
                    second.getBooleanExtra("vq_pcvr_allow_restart", false));
            assertTrue("the confirmed restart carries its deadline",
                    second.getLongExtra("vq_pcvr_restart_until_ms", 0L) > 0L);
            assertFalse("a re-dispatch must mint a fresh nonce",
                    first.getStringExtra(MainHubActivity.EXTRA_PCVR_LAUNCH_NONCE)
                            .equals(second.getStringExtra(MainHubActivity.EXTRA_PCVR_LAUNCH_NONCE)));
        } finally {
            close(c);
        }
    }

    /**
     * A callback is only acted on when it echoes the pending nonce AND
     * the pin the hub holds right now. A forged nonce, a forged pin and a
     * duplicate delivery of the genuine callback are all inert: no
     * prompt, no probe, no dispatch — and none of them may consume the
     * expectation, which is why the genuine callback still works
     * afterwards.
     */
    @Test
    public void nativeRestartRequired_rejectsForgedAndDuplicateCallbacks() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<NativePairedHub> c = startNativePairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue(await(() -> {
                log.drain(app());
                return log.countSteamVr() == 1;
            }, 4000L));
            Intent launch = steamVrIntent(log);
            c.pause();
            c.resume();

            // Forged nonce.
            Intent forgedNonce = nativeReturn(launch, PcvrReturnGate.ISSUE_RESTART_REQUIRED, 4242);
            forgedNonce.putExtra(MainHubActivity.EXTRA_PCVR_LAUNCH_NONCE,
                    "00000000-0000-4000-8000-000000000000");
            deliverNativeReturn(c.get(), forgedNonce);

            // Forged host identity: a different PC.
            Intent forgedPin = nativeReturn(launch, PcvrReturnGate.ISSUE_RESTART_REQUIRED, 4242);
            forgedPin.putExtra(MainHubActivity.EXTRA_PCVR_HOST_PIN,
                    "0000000000000000000000000000000000000000000000000000000000000001");
            deliverNativeReturn(c.get(), forgedPin);

            // Unknown issue value from native.
            Intent unknownIssue = nativeReturn(launch, "restart_maybe", 4242);
            deliverNativeReturn(c.get(), unknownIssue);

            settle(300);
            log.drain(app());
            assertFalse("a forged / unknown callback must never prompt",
                    PcvrTestActions.awaitDialogTitle(
                            c.get().getString(R.string.hub_restart_title), 300L));
            assertEquals("a forged / unknown callback must not re-probe", 1, host.probes.get());
            assertEquals("a forged / unknown callback must not dispatch", 1, log.countSteamVr());
            assertNotNull("the expectation must survive a rejected callback",
                    pendingNonce(c.get()));

            // The genuine callback is still accepted exactly once.
            deliverNativeReturn(c.get(), nativeReturn(launch,
                    PcvrReturnGate.ISSUE_RESTART_REQUIRED, 4242));
            AlertDialog prompt = awaitRestartDialog(c.get(), 4000L);
            assertNotNull("the genuine callback must still prompt", prompt);

            // A duplicate delivery of the same callback is inert.
            deliverNativeReturn(c.get(), nativeReturn(launch,
                    PcvrReturnGate.ISSUE_RESTART_REQUIRED, 4242));
            settle(300);
            log.drain(app());
            assertEquals("a duplicate callback must not dispatch", 1, log.countSteamVr());
            assertEquals("a duplicate callback must not re-probe", 1, host.probes.get());
            assertEquals("a spent callback must leave no expectation behind",
                    "", pendingNonce(c.get()));
        } finally {
            close(c);
        }
    }

    /**
     * A callback that lands while the hub is paused (or behind a
     * recreation) survives, but ONLY as a pending issue: the recreated
     * hub still shows the prompt, and it holds NO restart consent, so a
     * recreation can never turn into a silent restart.
     */
    @Test
    public void nativeRestartRequired_survivesRecreationWithoutConsent() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<NativePairedHub> c = startNativePairedHub();
        StartedIntentLog log = new StartedIntentLog();
        ActivityController<NativePairedHub> reborn = null;
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue(await(() -> {
                log.drain(app());
                return log.countSteamVr() == 1;
            }, 4000L));
            Intent launch = steamVrIntent(log);
            assertNotNull(launch);

            // The callback lands while the hub is paused: it must be
            // stored, not shown and not acted on.
            c.pause();
            deliverNativeReturn(c.get(), nativeReturn(launch,
                    PcvrReturnGate.ISSUE_RESTART_REQUIRED, 4242));
            settle(250);
            assertFalse("a paused hub must not surface the prompt",
                    PcvrTestActions.awaitDialogTitle(
                            c.get().getString(R.string.hub_restart_title), 200L));

            Bundle state = new Bundle();
            c.get().onSaveInstanceState(state);
            close(c);
            c = null;

            reborn = Robolectric.buildActivity(NativePairedHub.class)
                    .create(state).start().resume();

            assertEquals("a recreation must not restore positive restart consent",
                    0L, restartConsentUntil(reborn.get()));
            AlertDialog prompt = awaitRestartDialog(reborn.get(), 4000L);
            assertNotNull("the restored hub must still surface the prompt once", prompt);

            log.drain(app());
            assertEquals("a restored callback must never auto-dispatch", 1, log.countSteamVr());
            assertEquals("a restored callback must not re-probe on its own", 1, host.probes.get());

            // Cancel drops it: no probe, no dispatch.
            prompt.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            settle(300);
            log.drain(app());
            assertEquals("Cancel after a mismatch must not probe", 1, host.probes.get());
            assertEquals("Cancel after a mismatch must not dispatch", 1, log.countSteamVr());
            assertEquals("Cancel must clear the restart consent", 0L, restartConsentUntil(reborn.get()));
        } finally {
            if (reborn != null) close(reborn);
            if (c != null) close(c);
        }
    }

    /**
     * The prompt waits on a PROCESS fact, not on a delay. With an old
     * {@code :pcvr} pid of our own uid still reported alive, the hub
     * must neither prompt nor dispatch — no matter how much wall-clock
     * time passes — and it must surface the moment the process is
     * genuinely gone.
     */
    @Test
    public void nativeRestartRequired_waitsForRealProcessExitBeforePrompting() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<ProcessAwareHub> c = startProcessAwareHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue(await(() -> {
                log.drain(app());
                return log.countSteamVr() == 1;
            }, 4000L));
            Intent launch = steamVrIntent(log);
            c.pause();
            c.resume();

            // The old process is still alive.
            c.get().aliveReads = Integer.MAX_VALUE;
            int readsBefore = c.get().processReads.get();
            deliverNativeReturn(c.get(), nativeReturn(launch,
                    PcvrReturnGate.ISSUE_RESTART_REQUIRED, 4242));
            settle(400);
            log.drain(app());

            assertFalse("a live old :pcvr process must block the prompt",
                    PcvrTestActions.awaitDialogTitle(
                            c.get().getString(R.string.hub_restart_title), 200L));
            assertEquals("a live old :pcvr process must block the dispatch", 1, log.countSteamVr());
            assertTrue("the gate must actually poll the process table",
                    c.get().processReads.get() > readsBefore);

            // The process is gone for real: now the prompt may appear.
            c.get().aliveReads = 0;
            AlertDialog prompt = awaitRestartDialog(c.get(), 6000L);
            assertNotNull("the prompt must appear once the process is gone", prompt);
            log.drain(app());
            assertEquals("the prompt alone must not dispatch", 1, log.countSteamVr());
            assertEquals("the prompt alone must not re-probe", 1, host.probes.get());
        } finally {
            close(c);
        }
    }

    /**
     * The same gate guards the dispatch itself: an unknown process state
     * (the platform cannot answer) is a clear failure, never an
     * automatic launch.
     */
    @Test
    public void unknownProcessState_failsClearlyInsteadOfLaunching() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<ProcessAwareHub> c = startProcessAwareHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // "The platform could not answer" is represented by a null
            // snapshot, which must fail closed.
            c.get().procRows = null;
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            final View errorRow = c.get().findViewById(R.id.hub_card_error_row);
            assertTrue("the failure must be reported in the connection card",
                    await(() -> errorRow.getVisibility() == View.VISIBLE, 4000L));
            log.drain(app());

            assertEquals("an unknown process state must never launch PCVR", 0, log.countSteamVr());
            String message = text(c.get().findViewById(R.id.hub_card_error));
            assertTrue("the failure must be explained: " + message,
                    message.toLowerCase().contains("confirm"));
        } finally {
            close(c);
        }
    }

    /**
     * A microphone revoked while the mismatch prompt is up blocks the
     * restart: no probe, no dispatch, no consent.
     */
    @Test
    public void nativeRestartRequired_micRevokedWhilePrompted_blocksRestart() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<NativePairedHub> c = startNativePairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue(await(() -> {
                log.drain(app());
                return log.countSteamVr() == 1;
            }, 4000L));
            Intent launch = steamVrIntent(log);
            c.pause();
            c.resume();
            deliverNativeReturn(c.get(), nativeReturn(launch,
                    PcvrReturnGate.ISSUE_RESTART_REQUIRED, 4242));
            AlertDialog prompt = awaitRestartDialog(c.get(), 4000L);
            assertNotNull(prompt);

            grantMic(false);
            prompt.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle(400);
            log.drain(app());

            assertEquals("a revoked microphone must block the restart probe",
                    1, host.probes.get());
            assertEquals("a revoked microphone must block the dispatch", 1, log.countSteamVr());
            assertEquals("a revoked microphone must leave no restart consent",
                    0L, restartConsentUntil(c.get()));
        } finally {
            close(c);
        }
    }

    /**
     * {@code restart_failed} is a concise Retry / Cancel error: the hub
     * never restarts anything on its own, and Retry is a fresh connect
     * (fresh probe, no inherited consent) rather than a replay.
     */
    @Test
    public void nativeRestartFailed_showsRetryCancelAndNeverRestartsItself() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<NativePairedHub> c = startNativePairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue(await(() -> {
                log.drain(app());
                return log.countSteamVr() == 1;
            }, 4000L));
            Intent launch = steamVrIntent(log);
            c.pause();
            c.resume();
            deliverNativeReturn(c.get(), nativeReturn(launch,
                    PcvrReturnGate.ISSUE_RESTART_FAILED, 4242));

            assertTrue("a failed native restart must surface a Retry / Cancel error",
                    PcvrTestActions.awaitDialogTitle("VR restart failed", 4000L));
            AlertDialog failed = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(failed);
            assertNotNull("Retry must be offered", failed.getButton(AlertDialog.BUTTON_POSITIVE));
            assertNotNull("Cancel must be offered", failed.getButton(AlertDialog.BUTTON_NEGATIVE));
            settle(300);
            log.drain(app());
            assertEquals("a failed restart must never dispatch by itself", 1, log.countSteamVr());
            assertEquals("a failed restart must never probe by itself", 1, host.probes.get());
            assertEquals("a failed restart must leave no consent", 0L, restartConsentUntil(c.get()));
            assertFalse("a failed restart must not offer the Restart VR consent",
                    PcvrTestActions.awaitDialogTitle(
                            c.get().getString(R.string.hub_restart_title), 200L));

            // Retry is a fresh attempt, not a replay of the old one.
            failed.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue("Retry must re-probe the host",
                    await(() -> host.probes.get() == 2, 4000L));
            assertTrue("Retry must dispatch again",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 2;
                    }, 4000L));
            Intent retry = null;
            for (Intent i : log.all()) {
                if (isSteamVrIntent(i)) retry = i;
            }
            assertNotNull(retry);
            assertFalse("a retry must not inherit a restart permission it was never given",
                    retry.getBooleanExtra("vq_pcvr_allow_restart", true));
        } finally {
            close(c);
        }
    }

    /**
     * A fresh connect supersedes the previous session's callback
     * expectation: the new dispatch mints a new nonce, and the old
     * callback is then stale and inert.
     */
    @Test
    public void newConnect_clearsTheOldCallbackExpectation() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<NativePairedHub> c = startNativePairedHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue(await(() -> {
                log.drain(app());
                return log.countSteamVr() == 1;
            }, 4000L));
            Intent first = steamVrIntent(log);
            assertNotNull(first);

            // Leave and come back from the immersive activity, then
            // reconnect: that is a new dispatch with a new nonce.
            c.pause();
            c.resume();
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue(await(() -> {
                log.drain(app());
                return log.countSteamVr() == 2;
            }, 4000L));
            Intent second = null;
            for (Intent i : log.all()) {
                if (isSteamVrIntent(i)) second = i;
            }
            assertNotNull(second);
            assertFalse("each dispatch must mint its own nonce",
                    first.getStringExtra(MainHubActivity.EXTRA_PCVR_LAUNCH_NONCE)
                            .equals(second.getStringExtra(MainHubActivity.EXTRA_PCVR_LAUNCH_NONCE)));

            // The first session's callback is now stale.
            deliverNativeReturn(c.get(), nativeReturn(first,
                    PcvrReturnGate.ISSUE_RESTART_REQUIRED, 4242));
            settle(400);
            log.drain(app());
            assertFalse("a stale callback must never prompt",
                    PcvrTestActions.awaitDialogTitle(
                            c.get().getString(R.string.hub_restart_title), 300L));
            assertEquals("a stale callback must not dispatch", 2, log.countSteamVr());
            assertEquals("a stale callback must not re-probe", 2, host.probes.get());
        } finally {
            close(c);
        }
    }

    /* ------------------------------------------------------------
     * The process gate is asynchronous, so its answer is treated as
     * untrusted: a Cancel, a Destroy or a newer attempt must be able to
     * invalidate a poll that is already in flight, and a gate that
     * finishes while the hub is paused must be parked rather than
     * dropped or run early.
     * ------------------------------------------------------------ */

    /**
     * A Cancel that lands while the gate is still polling the old
     * {@code :pcvr} process must abandon the attempt for good. The
     * process genuinely disappearing afterwards is exactly the moment the
     * old code would have called {@code outcome.accept} and launched VR
     * (or surfaced the prompt) on an attempt the user had already
     * abandoned — so the process fact is made to arrive AFTER the Cancel
     * here, not before.
     */
    @Test
    public void gateCompletionAfterCancel_neverLaunchesPromptsOrReprobes() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<ProcessAwareHub> c = startProcessAwareHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            // The old :pcvr process is still there, so the first
            // dispatch parks itself in the exit gate.
            c.get().aliveReads = Integer.MAX_VALUE;
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("the attempt must reach the process gate",
                    await(() -> c.get().processReads.get() > 0, 4000L));
            settle(200);
            log.drain(app());
            assertEquals("a live old process must block the dispatch",
                    0, log.countSteamVr());

            // The user gives up while the gate is still waiting.
            c.get().findViewById(R.id.hub_btn_cancel_connection).performClick();
            assertFalse("a Cancel must release the gate guard",
                    exitGateRunning(c.get()));

            // Now the process really goes away — after the Cancel.
            c.get().aliveReads = 0;
            settle(600);
            log.drain(app());

            assertEquals("a gate that completes after a Cancel must not launch PCVR",
                    0, log.countSteamVr());
            assertFalse("a gate that completes after a Cancel must not prompt",
                    PcvrTestActions.awaitDialogTitle(
                            c.get().getString(R.string.hub_restart_title), 300L));
            assertEquals("a gate that completes after a Cancel must not re-probe",
                    1, host.probes.get());
            assertEquals("a gate that completes after a Cancel must not start the host again",
                    1, host.starts.get());
            assertEquals("a cancelled attempt must leave no restart consent",
                    0L, restartConsentUntil(c.get()));
        } finally {
            close(c);
        }
    }

    /**
     * A newer attempt supersedes an older gate with the same protection.
     *
     * <p>The old process is kept alive across both attempts and the
     * fresh probe is held open, so the ordering is exact: gate A is
     * polling, Cancel abandons it, gate A's completion is delivered and
     * dropped, and only then does gate B open. Gate A's answer must never
     * be attributed to gate B — no extra probe, no extra start, no
     * dispatch — and gate B must still be holding the guard, resolve on
     * its own when the process really goes, and dispatch exactly once.
     */
    @Test
    public void supersededGate_neverActsForTheNewAttempt() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<ProcessAwareHub> c = startProcessAwareHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().aliveReads = Integer.MAX_VALUE;
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("the first attempt must reach the process gate",
                    await(() -> c.get().processReads.get() > 0, 4000L));
            assertTrue("a running gate must hold the guard",
                    exitGateRunning(c.get()));

            c.get().findViewById(R.id.hub_btn_cancel_connection).performClick();
            assertFalse("a Cancel must release the gate guard",
                    exitGateRunning(c.get()));

            // Hold the fresh probe so gate B cannot open until the
            // superseded gate's completion has already been delivered.
            host.probeRelease = new CountDownLatch(1);
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("a fresh attempt must reach the host again",
                    await(() -> host.probeEntries.get() == 2, 4000L));
            settle(400);
            log.drain(app());
            assertEquals("the superseded gate must not launch for the new attempt",
                    0, log.countSteamVr());
            assertEquals("the superseded gate must not start the host again",
                    1, host.starts.get());

            // Release the probe: the new attempt opens its own gate.
            host.probeRelease.countDown();
            assertTrue("the new attempt must open its own gate",
                    await(() -> exitGateRunning(c.get()), 4000L));
            log.drain(app());
            assertEquals("a live old process must still block the new attempt",
                    0, log.countSteamVr());

            // The process really goes away: the surviving gate resolves
            // and the new attempt dispatches exactly once.
            c.get().aliveReads = c.get().processReads.get();
            assertTrue("the surviving gate must dispatch exactly once",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 6000L));
            settle(400);
            log.drain(app());
            assertEquals("the new attempt must dispatch exactly once",
                    1, log.countSteamVr());
            assertEquals("the superseded gate must not have re-probed",
                    2, host.probes.get());
            assertEquals("the superseded gate must not have started the host twice",
                    2, host.starts.get());
        } finally {
            close(c);
        }
    }

    /**
     * A gate that proves the old process gone WHILE THE HUB IS PAUSED
     * (the user took the headset off mid-teardown) is parked, not
     * dropped. Running it immediately would return from the dispatch
     * without any resume continuation and strand the attempt; dropping it
     * would lose the launch. So the next resume must run it exactly once,
     * and a resume after that must not replay it.
     */
    @Test
    public void gateCompletionWhilePaused_continuesExactlyOnceOnResume() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<ProcessAwareHub> c = startProcessAwareHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().aliveReads = Integer.MAX_VALUE;
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("the attempt must reach the process gate",
                    await(() -> c.get().processReads.get() > 0, 4000L));

            // The user takes the headset off BEFORE the process is gone.
            c.pause();
            c.get().aliveReads = c.get().processReads.get();
            settle(600);
            log.drain(app());
            assertEquals("a paused hub must not dispatch from the gate",
                    0, log.countSteamVr());

            c.resume();
            assertTrue("the parked gate outcome must dispatch on resume",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 1;
                    }, 4000L));

            // A later resume must not replay the parked outcome.
            c.pause();
            c.resume();
            settle(400);
            log.drain(app());
            assertEquals("a later resume must not replay the parked gate outcome",
                    1, log.countSteamVr());
            assertEquals("a parked gate outcome must not re-probe",
                    1, host.probes.get());
        } finally {
            close(c);
        }
    }

    /**
     * The same pause window, but the user cancels instead of returning:
     * the parked outcome belongs to an abandoned attempt, so the resume
     * must produce nothing at all — no dispatch, no prompt, no re-probe.
     */
    @Test
    public void gateCompletionWhilePaused_thenCancelled_neverContinues() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<ProcessAwareHub> c = startProcessAwareHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().aliveReads = Integer.MAX_VALUE;
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("the attempt must reach the process gate",
                    await(() -> c.get().processReads.get() > 0, 4000L));

            c.pause();
            c.get().aliveReads = c.get().processReads.get();
            settle(600);

            // Cancel while the outcome is parked.
            c.get().findViewById(R.id.hub_btn_cancel_connection).performClick();
            c.resume();
            settle(400);
            log.drain(app());

            assertEquals("a cancelled parked outcome must not launch PCVR",
                    0, log.countSteamVr());
            assertFalse("a cancelled parked outcome must not prompt",
                    PcvrTestActions.awaitDialogTitle(
                            c.get().getString(R.string.hub_restart_title), 300L));
            assertEquals("a cancelled parked outcome must not re-probe",
                    1, host.probes.get());
        } finally {
            close(c);
        }
    }

    /**
     * A consent window that expires while the hub is paused is not
     * renewed by the resume. The parked outcome is dropped with the
     * inline error the user has to react to, and no VR launch happens on
     * the strength of a consent that is no longer live. Uses a COLD host
     * because that is the attempt that actually holds a positive consent.
     */
    @Test
    public void gateContinuation_pastConsentExpiry_neverLaunches() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(false);
        ActivityController<ProcessAwareHub> c = startProcessAwareHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().aliveReads = Integer.MAX_VALUE;
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue("the cold attempt must reach the process gate",
                    await(() -> c.get().processReads.get() > 0, 4000L));
            assertTrue("the cold probe must have opened a consent window",
                    restartConsentUntil(c.get()) > android.os.SystemClock.elapsedRealtime());

            c.pause();
            c.get().aliveReads = c.get().processReads.get();
            settle(600);

            // The consent lapses while the outcome is parked.
            setRestartConsentUntil(c.get(),
                    android.os.SystemClock.elapsedRealtime() - 1L);

            c.resume();
            settle(500);
            log.drain(app());

            assertEquals("an expired consent must never produce a launch",
                    0, log.countSteamVr());
            assertEquals("an expired consent must not re-probe",
                    1, host.probes.get());
            assertEquals("an expired consent must be cleared, not renewed",
                    0L, restartConsentUntil(c.get()));
            String message = text(c.get().findViewById(R.id.hub_card_error));
            assertTrue("the expiry must be reported in the card: " + message,
                    message.toLowerCase().contains("too long"));
        } finally {
            close(c);
        }
    }

    /* ------------------------------------------------------------
     * A restart consent is spent on one named host.
     * ------------------------------------------------------------ */

    /**
     * The Restart VR prompt is about a specific PC. If the pairing is
     * replaced while the prompt is open — the user ran Setup in another
     * surface, or the store entry was rewritten — the confirmed restart
     * must not be spent on the newly paired machine: no fresh probe, no
     * {@code POST /start_pcvr}, no dispatch, and no restart permission.
     */
    @Test
    public void pairingChangedWhileRestartDialogOpen_neverStartsOrRestarts() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<SwappablePairingHub> c = startSwappablePairingHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue(await(() -> {
                log.drain(app());
                return log.countSteamVr() == 1;
            }, 4000L));
            Intent launch = steamVrIntent(log);
            assertNotNull(launch);
            c.pause();
            c.resume();
            deliverNativeReturn(c.get(), nativeReturn(launch,
                    PcvrReturnGate.ISSUE_RESTART_REQUIRED, 4242));
            AlertDialog prompt = awaitRestartDialog(c.get(), 4000L);
            assertNotNull("a real native mismatch must surface the Restart prompt", prompt);

            // The paired host changes while the prompt is up.
            c.get().currentPairing = pairingFor("otherhost:28540", OTHER_PIN);
            prompt.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            settle(500);
            log.drain(app());

            assertEquals("a changed pairing must not re-probe", 1, host.probes.get());
            assertEquals("a changed pairing must not start the host", 1, host.starts.get());
            assertEquals("a changed pairing must not launch PCVR", 1, log.countSteamVr());
            assertEquals("a changed pairing must never grant restart consent",
                    0L, restartConsentUntil(c.get()));
            assertTrue("the user must be told the pairing changed",
                    text(c.get().findViewById(R.id.hub_card_error))
                            .toLowerCase().contains("pairing changed"));
        } finally {
            close(c);
        }
    }

    /**
     * The same rule, checked at the only other moment it can break: the
     * pairing is replaced while the fresh probe behind the Restart tap is
     * genuinely in flight. The probe already authenticated the old host,
     * so the check has to happen after it returns, not just before it
     * was issued.
     */
    @Test
    public void pairingChangedDuringFreshProbe_neverStartsOrRestarts() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<SwappablePairingHub> c = startSwappablePairingHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue(await(() -> {
                log.drain(app());
                return log.countSteamVr() == 1;
            }, 4000L));
            Intent launch = steamVrIntent(log);
            assertNotNull(launch);
            c.pause();
            c.resume();
            deliverNativeReturn(c.get(), nativeReturn(launch,
                    PcvrReturnGate.ISSUE_RESTART_REQUIRED, 4242));
            AlertDialog prompt = awaitRestartDialog(c.get(), 4000L);
            assertNotNull(prompt);

            // Hold the fresh probe open, then re-pair underneath it.
            host.probeRelease = new CountDownLatch(1);
            prompt.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue("the fresh probe must really be in flight",
                    await(() -> host.probeEntries.get() == 2, 4000L));
            c.get().currentPairing = pairingFor("otherhost:28540", OTHER_PIN);
            host.probeRelease.countDown();
            settle(600);
            log.drain(app());

            assertEquals("a pairing changed mid-probe must not start the host",
                    1, host.starts.get());
            assertEquals("a pairing changed mid-probe must not launch PCVR",
                    1, log.countSteamVr());
            assertEquals("a pairing changed mid-probe must never grant restart consent",
                    0L, restartConsentUntil(c.get()));
            assertTrue("the user must be told the pairing changed",
                    text(c.get().findViewById(R.id.hub_card_error))
                            .toLowerCase().contains("pairing changed"));
        } finally {
            close(c);
        }
    }

    /**
     * Address rediscovery is NOT a pairing change. A host that moved
     * keeps its certificate, so the pin still matches and the confirmed
     * restart must go ahead end to end: re-probe, start, dispatch, and
     * this time a real restart permission. This is the guard that the two
     * tests above could have implemented far too strictly.
     */
    @Test
    public void samePinAtNewAddress_stillReProbesAndRestarts() throws Exception {
        setHeadset(true);
        grantMic(true);
        host.setVrserver(true);
        ActivityController<SwappablePairingHub> c = startSwappablePairingHub();
        StartedIntentLog log = new StartedIntentLog();
        try {
            c.get().findViewById(R.id.hub_btn_connect).performClick();
            assertTrue(await(() -> {
                log.drain(app());
                return log.countSteamVr() == 1;
            }, 4000L));
            Intent launch = steamVrIntent(log);
            assertNotNull(launch);
            c.pause();
            c.resume();
            deliverNativeReturn(c.get(), nativeReturn(launch,
                    PcvrReturnGate.ISSUE_RESTART_REQUIRED, 4242));
            AlertDialog prompt = awaitRestartDialog(c.get(), 4000L);
            assertNotNull(prompt);

            // Same certificate, different address.
            c.get().currentPairing = pairingFor("moved-host:28541", pairing.pin);
            prompt.getButton(AlertDialog.BUTTON_POSITIVE).performClick();

            assertTrue("a host that only moved must still be re-probed",
                    await(() -> host.probes.get() == 2, 4000L));
            assertTrue("a host that only moved must still dispatch",
                    await(() -> {
                        log.drain(app());
                        return log.countSteamVr() == 2;
                    }, 4000L));
            Intent second = null;
            for (Intent i : log.all()) {
                if (isSteamVrIntent(i)) second = i;
            }
            assertNotNull(second);
            assertTrue("the confirmed restart may restart SteamVR on the PC",
                    second.getBooleanExtra("vq_pcvr_allow_restart", false));
            assertEquals("the re-dispatch must still be pinned to the same host",
                    pairing.pin, second.getStringExtra(MainHubActivity.EXTRA_PCVR_HOST_PIN));
        } finally {
            close(c);
        }
    }
}
