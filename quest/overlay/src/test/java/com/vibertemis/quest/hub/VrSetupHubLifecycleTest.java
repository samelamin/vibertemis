package com.vibertemis.quest.hub;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;

import com.limelight.R;
import com.vibertemis.quest.pcvr.HostClientTest;
import com.vibertemis.quest.pcvr.HostPairing;
import com.vibertemis.quest.pcvr.PairingSession;
import com.vibertemis.quest.pcvr.StandalonePairingClient;
import com.vibertemis.quest.pcvr.VrSetupDiscovery;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSystemClock;

import static org.junit.Assert.*;

/**
 * Hub lifecycle tests for the new VR-setup discovery flow. Each
 * test installs a {@link com.vibertemis.quest.pcvr.VrSetupDiscovery.Factory}
 * via a {@link com.vibertemis.quest.hub.MainHubActivity} subclass so
 * the real activity drives a fake NSD without touching the system
 * service.
 *
 * <p>Robolectric's {@code SystemClock.elapsedRealtime()} is frozen in
 * paused mode, which would block both the {@code browse()} budget and
 * any test-side deadline that polls the same clock. These tests
 * therefore advance the Robolectric clock explicitly via
 * {@link ShadowSystemClock#advanceBy(long, TimeUnit)} so the discovery
 * budget and the test wait terminate deterministically. Real wall-clock
 * time is bounded by an outer {@link #AWAIT_TIMEOUT_MS} guard so a
 * hung worker can never pin the test thread indefinitely.
 */
@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
@org.robolectric.annotation.SQLiteMode(org.robolectric.annotation.SQLiteMode.Mode.LEGACY)
public class VrSetupHubLifecycleTest {

    /**
     * Outer wall-clock cap (real time). Even if the Robolectric clock
     * is somehow stuck, the test exits after this many milliseconds.
     */
    private static final long AWAIT_TIMEOUT_MS = 4000L;

    static class FakeDriver implements VrSetupDiscovery.BrowseDriver {
        final List<NsdServiceInfo> services = new ArrayList<>();
        final AtomicInteger startCalls = new AtomicInteger();
        final AtomicInteger stopCalls = new AtomicInteger();
        CountDownLatch startGate;
        CountDownLatch resolveGate;
        /**
         * When true, {@link #start(NsdManager.DiscoveryListener)}
         * emits {@code onServiceFound} for every service and then
         * fires {@code onDiscoveryStopped}, which causes the
         * production discovery loop to drain and exit cleanly without
         * advancing the Robolectric clock.
         */
        boolean selfClose;
        /** Captured listener so the test can invoke onDiscoveryStopped
         *  from the outside if needed. */
        final AtomicReference<NsdManager.DiscoveryListener> captured =
                new AtomicReference<>();
        FakeDriver() { this(null, null); }
        FakeDriver(CountDownLatch startGate, CountDownLatch resolveGate) {
            this.startGate = startGate;
            this.resolveGate = resolveGate;
        }
        @Override public void start(NsdManager.DiscoveryListener listener) {
            captured.set(listener);
            startCalls.incrementAndGet();
            if (startGate != null) {
                // Real NSD.start() returns immediately and dispatches
                // callbacks asynchronously. This fake simulates that
                // by waiting on a gate the test controls, so the test
                // can exercise the cancel path while the activity's
                // worker thread is still inside driver.start().
                try { startGate.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { return; }
            }
            for (NsdServiceInfo s : services) listener.onServiceFound(s);
            // Production callers fire onDiscoveryStopped when their
            // background teardown completes; the real NsdManager fires
            // it as soon as the framework tears the discovery down.
            // Without it the discovery loop would have to wait for the
            // full 8-second budget to elapse before returning. A fake
            // driver that fires results without firing
            // onDiscoveryStopped is misbehaving for the test.
            if (selfClose) {
                listener.onDiscoveryStopped(VrSetupDiscovery.SERVICE);
            }
        }
        @Override public void stop(NsdManager.DiscoveryListener listener) { stopCalls.incrementAndGet(); }
        @Override public void resolve(NsdServiceInfo info, NsdManager.ResolveListener rl) {
            if (resolveGate != null) {
                try { resolveGate.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { return; }
            }
            rl.onServiceResolved(info);
        }
    }

    public static class FakeHub extends MainHubActivity {
        static volatile VrSetupDiscovery.Factory sharedFactory;
        static volatile PairingSession sharedSession;
        static volatile javax.crypto.SecretKey sharedKey;
        static volatile com.vibertemis.quest.pcvr.PairingStore.KeySource sharedKeys;
        @Override protected VrSetupDiscovery createVrSetupDiscovery() {
            VrSetupDiscovery.Factory f = sharedFactory;
            if (f != null) return new VrSetupDiscovery(f);
            return super.createVrSetupDiscovery();
        }
        @Override protected PairingSession createStandalonePairingClient() {
            PairingSession s = sharedSession;
            if (s != null) return s;
            return super.createStandalonePairingClient();
        }
        @Override protected com.vibertemis.quest.pcvr.PairingStore createPairingStore() {
            if (sharedKeys != null) return new com.vibertemis.quest.pcvr.PairingStore(getApplicationContext(), sharedKeys);
            javax.crypto.SecretKey k = sharedKey;
            if (k != null) {
                return new com.vibertemis.quest.pcvr.PairingStore(getApplicationContext(),
                        create -> k);
            }
            return super.createPairingStore();
        }
        @Override protected String loadNativeHeadsetIdentity() { return "test.client"; }
    }

    /** Deterministic pairing session for the enrollment lifecycle
     *  tests. The session blocks in {@link #enroll} until the test
     *  calls {@link #deliverCode} (which fires
     *  {@link StandalonePairingClient.Progress#comparing}) and then
     *  {@link #completeSuccess}/{@link #completeFailure} to release
     *  the enrollment. {@link #cancel} sets the cancelled flag so
     *  in-flight waits return with a Cancelled IOException. */
    static class FakePairingSession implements PairingSession {
        final CountDownLatch enrolled = new CountDownLatch(1);
        final CountDownLatch codeDelivered = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicReference<String> code = new AtomicReference<>();
        final AtomicReference<String> host = new AtomicReference<>();
        final AtomicInteger portRef = new AtomicInteger();
        final AtomicLong deadlineMs = new AtomicLong();
        final java.util.concurrent.atomic.AtomicBoolean canceled =
                new java.util.concurrent.atomic.AtomicBoolean();
        HostPairing result;
        Exception failure;
        FakePairingSession() {}
        @Override
        public HostPairing enroll(String host, int port,
                                  StandalonePairingClient.Progress progress) throws Exception {
            this.host.set(host);
            this.portRef.set(port);
            deadlineMs.set(SystemClock.elapsedRealtime() + 60000L);
            enrolled.countDown();
            try { codeDelivered.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { throw new java.io.IOException("Cancelled"); }
            if (canceled.get()) throw new java.io.IOException("Cancelled");
            String c = code.get();
            if (c != null) progress.comparing(c);
            try { completed.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { throw new java.io.IOException("Cancelled"); }
            if (canceled.get()) throw new java.io.IOException("Cancelled");
            if (failure != null) throw failure;
            return result;
        }
        @Override public void cancel() {
            canceled.set(true);
            codeDelivered.countDown();
            completed.countDown();
        }
        @Override public long remainingDeadlineMs() {
            long end = deadlineMs.get();
            if (end == 0L) return 0L;
            long r = end - SystemClock.elapsedRealtime();
            return r > 0L ? r : 0L;
        }
        void deliverCode(String c) { code.set(c); codeDelivered.countDown(); }
        void completeSuccess(HostPairing p) { result = p; completed.countDown(); }
        void completeFailure(Exception e) { failure = e; completed.countDown(); }
    }

    private Context ctx;

    @Before public void setup() {
        ctx = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(ctx.getPackageManager())
                .setSystemFeature(PackageManager.FEATURE_VR_HEADTRACKING, true);
        ShadowApplication.getInstance().grantPermissions(Manifest.permission.RECORD_AUDIO);
    }

    @After public void teardown() {
        // Ensure any lingering gates do not block subsequent tests.
        FakeHub.sharedFactory = null;
        FakeHub.sharedSession = null;
        FakeHub.sharedKey = null;
        FakeHub.sharedKeys = null;
    }

    private static NsdServiceInfo service(String name, InetAddress host, int port) throws Exception {
        NsdServiceInfo s = new NsdServiceInfo();
        s.setServiceName(name);
        s.setHost(host);
        s.setPort(port);
        s.setAttribute("protocol", VrSetupDiscovery.PROTOCOL);
        return s;
    }

    /**
     * Advance the Robolectric clock in 50 ms steps and drain the main
     * looper until either the condition is true or the real
     * wall-clock deadline elapses. The outer guard guarantees the
     * test thread can never be pinned by a stuck background worker.
     *
     * <p>Each iteration also yields the OS scheduler
     * ({@code Thread.sleep}) so a real worker thread (the
     * {@code connectWorker} single-thread executor) gets a chance
     * to run. {@link ShadowSystemClock#advanceBy} advances the
     * frozen {@code SystemClock.elapsedRealtime()} clock so any
     * deadline computed against it expires in lockstep.
     */
    private static void awaitCondition(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            ShadowSystemClock.advanceBy(50L, TimeUnit.MILLISECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(20L);
        }
        // One last chance: condition may now be satisfied.
        if (!cond.getAsBoolean()) {
            throw new AssertionError("awaitCondition timed out after " + AWAIT_TIMEOUT_MS + "ms");
        }
    }

    /** Setup with no saved Moonlight PCs and an empty discovery
     *  must show the "No VR PC found" empty state with Retry /
     *  Enter address / Advanced buttons. Screen-gaming is NOT
     *  offered here: that is a separate workflow. */
    @Test public void emptyStateShowsRetryEnterAddressAdvanced() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.selfClose = true;
        FakeHub.sharedFactory = () -> driver;
        ActivityController<FakeHub> ctl = Robolectric.buildActivity(FakeHub.class);
        try {
            ctl = ctl.setup();
            ctl.get().findViewById(R.id.hub_btn_setup).performClick();
            // Drive the discovery through the search; the worker is on
            // a single-thread executor. selfClose ensures the discovery
            // returns promptly without advancing the 8 s budget.
            awaitCondition(() -> {
                AlertDialog dlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
                if (dlg == null || !dlg.isShowing()) return false;
                CharSequence title = Shadows.shadowOf(dlg).getTitle();
                return title != null && title.toString().contains("No VR PC found");
            });
            AlertDialog dlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("empty state must be shown", dlg);
            CharSequence title = Shadows.shadowOf(dlg).getTitle();
            assertNotNull("title must exist", title);
            assertTrue("title must be No VR PC found: " + title,
                    title.toString().contains("No VR PC found"));
            // Screen gaming must NOT be offered here.
            Button retry = null, enter = null, advanced = null;
            Button[] buttons = new Button[] {
                    (android.widget.Button) findButton(dlg, android.R.id.button1),
                    (android.widget.Button) findButton(dlg, android.R.id.button2),
                    (android.widget.Button) findButton(dlg, android.R.id.button3) };
            for (Button b : buttons) {
                if (b == null) continue;
                String t = b.getText().toString();
                if ("Retry".equals(t)) retry = b;
                else if (t.contains("Enter address")) enter = b;
                else if ("Advanced".equals(t)) advanced = b;
                assertNotEquals("Screen gaming must not appear in the empty state: " + t,
                        "Screen gaming", t);
            }
            assertNotNull("Retry button missing", retry);
            assertNotNull("Enter address button missing", enter);
            assertNotNull("Advanced button missing", advanced);
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    private static View findButton(AlertDialog dlg, int id) {
        android.view.Window w = dlg.getWindow();
        if (w == null) return null;
        return findById(w.getDecorView(), id);
    }

    private static View findById(View root, int id) {
        if (root == null) return null;
        if (root.getId() == id) return root;
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup vg = (android.view.ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View c = findById(vg.getChildAt(i), id);
                if (c != null) return c;
            }
        }
        return null;
    }

    /** Setup with a discovered PC must show the candidate picker,
     *  not the empty state. The picker offers Enter address /
     *  Retry / Advanced buttons. */
    @Test public void discoveredCandidateReachesPicker() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.selfClose = true;
        driver.services.add(service("My Gaming PC", InetAddress.getByName("192.168.1.42"), 28540));
        FakeHub.sharedFactory = () -> driver;
        ActivityController<FakeHub> ctl = Robolectric.buildActivity(FakeHub.class);
        try {
            ctl = ctl.setup();
            ctl.get().findViewById(R.id.hub_btn_setup).performClick();
            AlertDialog picker = awaitDialog("Choose your PC for VR");
            assertNotNull("picker must be shown", picker);
            assertTrue("picker is showing", picker.isShowing());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /** Pause during a long discovery must cancel the running
     *  browse within the cancel poll window, NOT block the worker
     *  for the full 8-second budget. */
    @Test public void pauseDuringDiscoveryCancelsPromptly() throws Exception {
        final CountDownLatch startGate = new CountDownLatch(1);
        FakeDriver driver = new FakeDriver(startGate, null);
        FakeHub.sharedFactory = () -> driver;
        ActivityController<FakeHub> ctl = Robolectric.buildActivity(FakeHub.class);
        try {
            ctl = ctl.setup();
            ctl.get().findViewById(R.id.hub_btn_setup).performClick();
            // Let the worker enter the gated start() callback before
            // we cancel. Advance the clock briefly and yield to the
            // executor thread so it reaches start().
            ShadowSystemClock.advanceBy(50L, TimeUnit.MILLISECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(100L);
            assertEquals("worker must be inside driver.start() (one start call)",
                    1, driver.startCalls.get());
            // Pause flips the cancel flag on the discovery; the
            // worker is still blocked in driver.start() because the
            // gate is held. Release the gate so start() returns; the
            // browse loop will then see cancelled=true and exit,
            // triggering driver.stop().
            ctl.pause();
            startGate.countDown();
            // Bounded wait for the cancel cleanup to call
            // driver.stop(). A 2 s ceiling matches the production
            // cancel-poll window plus a margin for thread scheduling.
            long deadline = System.currentTimeMillis() + 2000L;
            while (System.currentTimeMillis() < deadline
                    && driver.stopCalls.get() < 1) {
                ShadowSystemClock.advanceBy(50L, TimeUnit.MILLISECONDS);
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                Thread.sleep(20L);
            }
            assertTrue("discovery must have been stopped on pause: stopCalls="
                    + driver.stopCalls.get(), driver.stopCalls.get() >= 1);
        } finally {
            startGate.countDown();
            ctl.stop().destroy();
        }
    }

    private AlertDialog awaitDialog(String titleSubstring) throws InterruptedException {
        final AlertDialog[] captured = new AlertDialog[1];
        awaitCondition(() -> {
            AlertDialog dlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            if (dlg != null && dlg.isShowing()) {
                CharSequence title = Shadows.shadowOf(dlg).getTitle();
                if (title != null && title.toString().contains(titleSubstring)) {
                    captured[0] = dlg;
                    return true;
                }
            }
            return false;
        });
        return captured[0];
    }

    private AlertDialog awaitDialogMessage(String messageSubstring) throws InterruptedException {
        final AlertDialog[] captured = new AlertDialog[1];
        awaitCondition(() -> {
            AlertDialog dlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            if (dlg != null && dlg.isShowing()) {
                android.widget.TextView msgView =
                        (android.widget.TextView) dlg.findViewById(android.R.id.message);
                if (msgView != null) {
                    CharSequence msg = msgView.getText();
                    if (msg != null && msg.toString().contains(messageSubstring)) {
                        captured[0] = dlg;
                        return true;
                    }
                }
            }
            return false;
        });
        return captured[0];
    }

    /** Selecting a discovered candidate starts enrollment. The
     *  "Connecting to your PC" dialog appears first; after the
     *  comparison-code callback fires, the dialog title flips to
     *  "Approve matching code on Windows" and the code appears in
     *  the message body. */
    @Test public void selectionShowsApprovalDialogWithComparisonCode() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.selfClose = true;
        // Private IPv4 — sanitize() rejects 127.0.0.1 (loopback) so
        // the picker would otherwise be empty. 192.168.1.50 is in the
        // RFC1918 range and is accepted.
        driver.services.add(service("My Gaming PC", InetAddress.getByName("192.168.1.50"), 28540));
        FakePairingSession session = new FakePairingSession();
        FakeHub.sharedFactory = () -> driver;
        FakeHub.sharedSession = session;
        ActivityController<FakeHub> ctl = Robolectric.buildActivity(FakeHub.class);
        try {
            ctl = ctl.setup();
            FakeHub hub = ctl.get();
            hub.findViewById(R.id.hub_btn_setup).performClick();
            // Wait for the picker (the result of discovery).
            AlertDialog picker = awaitDialog("Choose your PC for VR");
            assertNotNull("picker must appear", picker);
            assertTrue("picker is showing", picker.isShowing());
            // Drive the picker via its ListView so the click target
            // is exercised the same way a user does it. The picker
            // is set up via AlertDialog.Builder.setItems() which
            // builds a ListView internally; we click its first row.
            android.widget.ListView pickerList = picker.getListView();
            assertNotNull("picker must expose a list view", pickerList);
            pickerList.performItemClick(
                    pickerList.getChildAt(0), 0, pickerList.getItemIdAtPosition(0));
            // The picker click handler dismisses the picker dialog
            // and calls enrollVrHost on the connector.
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue("enrollment must be invoked",
                    session.enrolled.await(2, TimeUnit.SECONDS));
            AlertDialog connecting = awaitDialog("Connecting to your PC");
            assertNotNull("Connecting dialog must appear", connecting);
            // Deliver the comparison code; the dialog title and
            // body update on the next UI tick.
            session.deliverCode("1234-ABCD-5678-EFGH");
            awaitCondition(() -> {
                AlertDialog dlg = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
                if (dlg == null || !dlg.isShowing()) return false;
                CharSequence title = Shadows.shadowOf(dlg).getTitle();
                return title != null
                        && title.toString().contains("Approve matching code on Windows");
            });
            // The code text appears in the body verbatim.
            awaitDialogMessage("1234-ABCD-5678-EFGH");
            session.completeFailure(new java.io.IOException("Cancelled"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /** Pause during enrollment must NOT cancel the bootstrap. The
     *  comparison-code callback has already fired (or is in flight);
     *  the headset may be off the user's face while they approve
     *  on the PC, so the enrollment must survive the pause. The
     *  dialog also stays in place so the user can read the code
     *  when they put the headset back on. */
    @Test public void pauseDuringEnrollmentDoesNotCancelBootstrap() throws Exception {
        FakeHub.sharedKey = javax.crypto.KeyGenerator.getInstance("AES").generateKey();
        FakeDriver driver = new FakeDriver();
        driver.selfClose = true;
        // Private IPv4 — sanitize() rejects 127.0.0.1 (loopback).
        driver.services.add(service("VR-PC", InetAddress.getByName("192.168.1.50"), 28540));
        FakePairingSession session = new FakePairingSession();
        FakeHub.sharedFactory = () -> driver;
        FakeHub.sharedSession = session;
        ActivityController<FakeHub> ctl = Robolectric.buildActivity(FakeHub.class);
        try {
            ctl = ctl.setup();
            FakeHub hub = ctl.get();
            hub.findViewById(R.id.hub_btn_setup).performClick();
            awaitDialog("Choose your PC for VR");
            hub.enrollVrHost("192.168.1.50", 28540);
            assertTrue(session.enrolled.await(2, TimeUnit.SECONDS));
            session.deliverCode("1234-ABCD-5678-EFGH");
            awaitDialogMessage("1234-ABCD-5678-EFGH");
            // Pause the hub. The bootstrap must NOT be cancelled.
            ctl.pause();
            assertFalse("bootstrap must survive pause", session.canceled.get());
            // Resume and let the enrollment complete successfully.
            ctl.resume();
            HostPairing expected = HostClientTest.pairing("host", 28540);
            session.completeSuccess(expected);
            // Completion appears inline without a second Done click.
            awaitCondition(() -> ((android.widget.TextView) hub.findViewById(R.id.hub_card_phase))
                    .getText().toString().contains("Paired."));
            assertNull(Shadows.shadowOf(hub).getNextStartedActivity());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    /** Successful enrollment persists the pairing to disk. The next
     *  onResume (returning from a launched activity, or after
     *  foreground/background) MUST surface the deferred
     *  pairing notice inline so the user is told their PC is paired
     *  even if the success was delivered while the hub was not
     *  resumed. The PairingStore is the production seam, so the
     *  test saves via the real {@link com.vibertemis.quest.pcvr.PairingStore}
     *  surface area the hub uses. */
    @Test public void successSavesPairingAndShowsNoticeOnResume() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.selfClose = true;
        // Private IPv4 — sanitize() rejects 127.0.0.1 (loopback).
        driver.services.add(service("VR-PC", InetAddress.getByName("192.168.1.50"), 28540));
        FakePairingSession session = new FakePairingSession();
        javax.crypto.SecretKey fakeKey =
                javax.crypto.KeyGenerator.getInstance("AES").generateKey();
        FakeHub.sharedFactory = () -> driver;
        FakeHub.sharedSession = session;
        FakeHub.sharedKey = fakeKey;
        ActivityController<FakeHub> ctl = Robolectric.buildActivity(FakeHub.class);
        try {
            ctl = ctl.setup();
            FakeHub hub = ctl.get();
            hub.findViewById(R.id.hub_btn_setup).performClick();
            awaitDialog("Choose your PC for VR");
            hub.enrollVrHost("192.168.1.50", 28540);
            assertTrue(session.enrolled.await(2, TimeUnit.SECONDS));
            session.deliverCode("1234-ABCD-5678-EFGH");
            awaitDialogMessage("1234-ABCD-5678-EFGH");
            // Pause FIRST so the success path takes the
            // pairingNotice deferred branch. We use the real
            // StandalonePairingClient-style HostPairing JSON so
            // PairingStore.save is satisfied.
            AlertDialog approval = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            ctl.pause();
            HostPairing saved = HostClientTest.pairing("host", 28540);
            session.completeSuccess(saved);
            // The save runs on the worker; give it a tick.
            awaitCondition(() -> new com.vibertemis.quest.pcvr.PairingStore(
                    RuntimeEnvironment.getApplication(), create -> fakeKey).hasPairing() && !approval.isShowing());
            // Resume shows the saved result inline, without launching VR.
            ctl.resume();
            awaitCondition(() -> ((android.widget.TextView) hub.findViewById(R.id.hub_card_phase))
                    .getText().toString().contains("Paired."));
            assertNull(Shadows.shadowOf(hub).getNextStartedActivity());
            assertTrue("PairingStore must persist the saved pairing",
                    new com.vibertemis.quest.pcvr.PairingStore(
                            RuntimeEnvironment.getApplication(), create -> fakeKey).hasPairing());
        } finally {
            // Clean up any persisted ciphertext so other tests
            // don't inherit it.
            try {
                new java.io.File(RuntimeEnvironment.getApplication().getNoBackupFilesDir(),
                        "pcvr-pairing.enc").delete();
            } catch (Exception ignored) {}
            ctl.pause().stop().destroy();
        }
    }

    /** Destroying the hub while a late enrollment callback is in
     *  flight must NOT show a new dialog. The bootstrap's
     *  cancellation fires through the connectGeneration guard so
     *  a post-destroy UI thread runnable is a no-op. */
    @Test public void destroyDuringEnrollmentShowsNoLateDialog() throws Exception {
        FakeDriver driver = new FakeDriver();
        driver.selfClose = true;
        // Private IPv4 — sanitize() rejects 127.0.0.1 (loopback).
        driver.services.add(service("VR-PC", InetAddress.getByName("192.168.1.50"), 28540));
        FakePairingSession session = new FakePairingSession();
        FakeHub.sharedFactory = () -> driver;
        FakeHub.sharedSession = session;
        ActivityController<FakeHub> ctl = Robolectric.buildActivity(FakeHub.class);
        ctl = ctl.setup();
        FakeHub hub = ctl.get();
        hub.findViewById(R.id.hub_btn_setup).performClick();
        awaitDialog("Choose your PC for VR");
        hub.enrollVrHost("192.168.1.50", 28540);
        assertTrue(session.enrolled.await(2, TimeUnit.SECONDS));
        session.deliverCode("1234-ABCD-5678-EFGH");
        awaitDialogMessage("1234-ABCD-5678-EFGH");
        // Snapshot the latest dialog so we can assert a NEW one
        // never appears after destroy.
        AlertDialog beforeDestroy =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        ctl.pause();
        ctl.stop();
        ctl.destroy();
        // Now release the enrollment. The worker thread tries to
        // runOnUiThread and post the success dialog — but the
        // generation guard + isDestroyed() guard must short-circuit
        // so no new dialog is shown.
        HostPairing saved = HostClientTest.pairing("host", 28540);
        session.completeSuccess(saved);
        // Drain the main looper so a late runnable would have had
        // a chance to fire.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        // The latest dialog is still the pre-destroy one.
        AlertDialog afterDestroy =
                org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertSame("destroy must prevent a new dialog from the late callback",
                beforeDestroy, afterDestroy);
    }

    /** The saved Moonlight DB hints must NEVER reuse the stored
     *  GameStream port as a VR port. The hint uses the default
     *  VR port (28540) so the picker only shows the standard
     *  port; a discovered PC on the same IP would advertise the
     *  custom port and win the merge. The hint's IP is taken
     *  from {@code AddressTuple.address} verbatim — the
     *  implementation MUST NOT split the address on a colon
     *  because the address is HOST ONLY in the Moonlight
     *  schema. */
    @Test public void dbHintsUseVrDefaultNotGameStreamPort() throws Exception {
        insertSavedComputer("test-uuid-1", "Saved PC",
                "192.168.1.50", 47989);
        ActivityController<FakeHub> ctl = Robolectric.buildActivity(FakeHub.class);
        try {
            ctl = ctl.setup();
            FakeHub hub = ctl.get();
            // Contract: null `discovered` means "no discovery yet,
            // skip the dedup gate"; pass emptyList() to mean
            // "discovery ran with no candidates". The DB scan must
            // still execute and the port assertion is NOT weakened.
            java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate> hints =
                    hub.loadSavedMoonlightHints(
                            java.util.Collections.<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate>emptyList());
            assertEquals(1, hints.size());
            com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate hint = hints.get(0);
            assertEquals("host must be address verbatim", "192.168.1.50", hint.address);
            assertEquals("port must be DEFAULT_VR_PORT (never reuse GameStream)",
                    VrSetupDiscovery.DEFAULT_VR_PORT, hint.port);
        } finally {
            deleteSavedComputer("test-uuid-1");
            ctl.pause().stop().destroy();
        }
    }

    /** IPv6 hints from the saved Moonlight DB must be rejected
     *  because the VR discovery path only accepts IPv4. An IPv6
     *  hint in the picker would never match an IPv4 discovered
     *  service and would never reach a VR-capable listener on
     *  the same host. AddressTuple strips brackets from IPv6
     *  literals, so the colon count is the giveaway. */
    @Test public void dbHintsRejectIpv6() throws Exception {
        insertSavedComputer("test-uuid-v6", "IPv6 PC",
                "fe80::1", 47989);
        ActivityController<FakeHub> ctl = Robolectric.buildActivity(FakeHub.class);
        try {
            ctl = ctl.setup();
            FakeHub hub = ctl.get();
            java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate> hints =
                    hub.loadSavedMoonlightHints(
                            java.util.Collections.<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate>emptyList());
            assertTrue("IPv6 hint must be rejected: " + hints, hints.isEmpty());
        } finally {
            deleteSavedComputer("test-uuid-v6");
            ctl.pause().stop().destroy();
        }
    }

    /** A DB hint whose host matches a discovered PC is dropped
     *  entirely so the discovered custom port wins the merge.
     *  The DB row's GameStream port must NOT appear in the
     *  picker at all (it would be misleading to show 47989 as a
     *  VR port next to the discovered 30000 on the same IP). */
    @Test public void dbHintsSkipIpsWithDiscoveredMatch() throws Exception {
        insertSavedComputer("test-uuid-skip", "Same IP PC",
                "192.168.1.60", 47989);
        ActivityController<FakeHub> ctl = Robolectric.buildActivity(FakeHub.class);
        try {
            ctl = ctl.setup();
            FakeHub hub = ctl.get();
            java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate> discovered =
                    new java.util.ArrayList<>();
            discovered.add(new com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate(
                    "PC", "192.168.1.60", 30000));
            java.util.List<com.vibertemis.quest.pcvr.VrSetupDiscovery.Candidate> hints =
                    hub.loadSavedMoonlightHints(discovered);
            assertTrue("DB hint for discovered IP must be skipped: " + hints,
                    hints.isEmpty());
        } finally {
            deleteSavedComputer("test-uuid-skip");
            ctl.pause().stop().destroy();
        }
    }

    private static void insertSavedComputer(String uuid, String name,
                                            String host, int port) throws Exception {
        com.limelight.computers.ComputerDatabaseManager db =
                new com.limelight.computers.ComputerDatabaseManager(
                        RuntimeEnvironment.getApplication());
        try {
            com.limelight.nvstream.http.ComputerDetails pc =
                    new com.limelight.nvstream.http.ComputerDetails();
            pc.uuid = uuid;
            pc.name = name;
            pc.localAddress = new com.limelight.nvstream.http.ComputerDetails.AddressTuple(
                    host, port);
            db.updateComputer(pc);
        } finally { db.close(); }
    }

    private static void deleteSavedComputer(String uuid) {
        try {
            android.database.sqlite.SQLiteDatabase sql =
                    RuntimeEnvironment.getApplication().openOrCreateDatabase(
                            "computers4.db", 0, null);
            sql.delete("Computers", "UUID=?", new String[]{uuid});
            sql.close();
        } catch (Exception ignored) { /* best effort cleanup */ }
    }
    @Test public void cancelDuringApprovedCredentialSaveDoesNotBlockUiOrShowLateNotice() throws Exception {
        javax.crypto.SecretKey key = javax.crypto.KeyGenerator.getInstance("AES").generateKey();
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean offUi = new java.util.concurrent.atomic.AtomicBoolean();
        FakeHub.sharedKeys = create -> {
            offUi.set(Looper.myLooper() != Looper.getMainLooper());
            saving.countDown();
            if (!release.await(3, TimeUnit.SECONDS)) throw new java.io.IOException("test save gate timed out");
            return key;
        };
        FakePairingSession session = new FakePairingSession();
        FakeHub.sharedSession = session;
        ActivityController<FakeHub> controller = Robolectric.buildActivity(FakeHub.class).setup();
        try {
            controller.get().enrollVrHost("192.168.1.50", 28540);
            assertTrue(session.enrolled.await(2, TimeUnit.SECONDS));
            session.deliverCode("1111-2222-3333-4444");
            awaitDialogMessage("1111-2222-3333-4444");
            AlertDialog approval = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            session.completeSuccess(HostClientTest.pairing("host", 28540));
            assertTrue(saving.await(2, TimeUnit.SECONDS));
            assertTrue("credential encryption must run off UI", offUi.get());
            approval.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            release.countDown();
            com.vibertemis.quest.pcvr.PairingStore store = new com.vibertemis.quest.pcvr.PairingStore(ctx, create -> key);
            awaitCondition(store::hasPairing);
            assertNotNull(store.load());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertSame(approval, org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());
            assertFalse(approval.isShowing());
            assertNull(Shadows.shadowOf(controller.get()).getNextStartedActivity());
        } finally {
            release.countDown();
            controller.pause().stop().destroy();
        }
    }
}
