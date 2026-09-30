package com.vibertemis.quest.pcvr;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.widget.Button;
import com.limelight.R;
import com.vibertemis.quest.hub.MainHubActivity;
import com.vibertemis.quest.hub.ShadowMoonBridge;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class NativeConsentTest {
  /** Native-runtime hub that simulates an unpaired headset so
   *  Connect opens the Setup VR picker / empty-state flow. */
  public static class UnpairedNativeHub extends MainHubActivity {
    @Override
    protected boolean usesNativeRuntime() {
      return true;
    }
    @Override
    protected boolean hasPairedHost() {
      return false;
    }
    /** Use a fake NSD driver that fires onDiscoveryStopped
     *  immediately so the picker / empty-state dialog renders
     *  deterministically without waiting the 8 s real budget. */
    @Override
    protected VrSetupDiscovery createVrSetupDiscovery() {
      return new VrSetupDiscovery(new VrSetupDiscovery.Factory() {
        @Override public VrSetupDiscovery.BrowseDriver create() {
          return new VrSetupDiscovery.BrowseDriver() {
            @Override public void start(NsdManager.DiscoveryListener listener) {
              listener.onDiscoveryStopped(VrSetupDiscovery.SERVICE);
            }
            @Override public void stop(NsdManager.DiscoveryListener listener) {}
            @Override public void resolve(NsdServiceInfo info, NsdManager.ResolveListener rl) {}
          };
        }
      });
    }
    @Override protected String loadNativeHeadsetIdentity() { return "test.client"; }
  }

  @Before
  public void setup() {
    Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
        .setSystemFeature(PackageManager.FEATURE_VR_HEADTRACKING, true);
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(Manifest.permission.RECORD_AUDIO);
  }

  /** Connect → picker / empty-state → Advanced → Manual VR →
   *  restart confirmation → SteamVrActivity. The intent must
   *  carry the travel / bitrate / restart-consent extras the
   *  Hub computes from PcvrOptions. */
  @Test
  public void connectCarriesExpiringConsentAndTravelBudget() {
    PcvrOptions options = new PcvrOptions(RuntimeEnvironment.getApplication());
    options.travel(true);
    options.bitrate(true, 25);
    try (var controller = Robolectric.buildActivity(UnpairedNativeHub.class).setup()) {
      var hub = controller.get();
      hub.findViewById(R.id.hub_btn_connect).performClick();
      // No launched activity yet — discovery is in flight on the
      // single-thread executor.
      assertNull(Shadows.shadowOf(hub).getNextStartedActivity());
      // Bounded wait for the empty-state dialog, then drive the
      // picker → Advanced → Manual VR → Connect path. The Advanced
      // branch invokes dispatchSteamVr directly (no HTTP), so the
      // SteamVrActivity intent fires on the next UI tick.
      assertTrue("empty-state dialog must surface",
          PcvrTestActions.awaitDialogTitle("No VR PC found", 4000L));
      PcvrTestActions.confirmRestartIfShown();
      Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
      Intent intent = Shadows.shadowOf(hub).getNextStartedActivity();
      assertNotNull(intent);
      assertTrue(intent.getBooleanExtra("vq_pcvr_allow_restart", false));
      assertEquals(25, intent.getIntExtra("vq_pcvr_bitrate_mbps", 0));
      assertTrue(
          intent.getLongExtra("vq_pcvr_restart_until_ms", 0)
              > android.os.SystemClock.elapsedRealtime());
    }
  }

  /** Leaving the hub while the restart-consent dialog is showing
   *  bumps the connectGeneration; clicking Connect on the
   *  paused activity must be a no-op so the user cannot start
   *  VR while the headset is off their face. The picker-driven
   *  journey passes through the consent dialog exactly the same
   *  way the OLD direct-launch path did, so the cancel-on-pause
   *  guard still applies. */
  @Test
  public void leavingHubInvalidatesPendingConfirmation() {
    try (var controller = Robolectric.buildActivity(UnpairedNativeHub.class).setup()) {
      var hub = controller.get();
      hub.findViewById(R.id.hub_btn_connect).performClick();
      // Drive to the "Connect to PCVR?" dialog. The picker is a
      // no-op once the dialog has moved past the picker stage.
      assertTrue("empty-state dialog must surface",
          PcvrTestActions.awaitDialogTitle("No VR PC found", 4000L));
      PcvrTestActions.stepAdvancedIfPicker();
      PcvrTestActions.stepManualVr();
      assertTrue("restart-consent dialog must surface",
          PcvrTestActions.awaitDialogTitle("Connect to PCVR?", 4000L));
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      controller.pause();
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
      assertNull(Shadows.shadowOf(hub).getNextStartedActivity());
    }
  }

  /** Unpaired Connect must surface the VR-setup picker / empty
   *  state — NOT a native launch. The legacy "Screen gaming"
   *  detour is intentionally NOT reinstated: VR pairing is a
   *  separate workflow from screen streaming. */
  @Test
  public void unpairedQuestOpensVrSetupInsteadOfUnreachableNativeLobby() {
    try (var controller = Robolectric.buildActivity(UnpairedNativeHub.class).setup()) {
      var hub = controller.get();
      hub.findViewById(R.id.hub_btn_connect).performClick();
      // No native launch ever — only the picker / empty-state
      // dialog must be on screen.
      assertNull(Shadows.shadowOf(hub).getNextStartedActivity());
      assertTrue(PcvrTestActions.awaitDialogTitle("No VR PC found", 4000L));
      AlertDialog setup = ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull("Setup dialog must surface for unpaired Connect", setup);
      CharSequence title = Shadows.shadowOf(setup).getTitle();
      assertNotNull("Setup dialog must have a title", title);
      assertTrue("Setup dialog must be the empty-state title: " + title,
          title.toString().contains("No VR PC found"));
      // Screen gaming is NOT an option here — VR pairing is a
      // separate workflow from screen streaming.
      Button retry = setup.getButton(AlertDialog.BUTTON_POSITIVE);
      if (retry != null) {
        assertNotEquals("Screen gaming must not appear in the empty state: " + retry.getText(),
            "Screen gaming", retry.getText().toString());
      }
      // Still no native launch.
      assertNull(Shadows.shadowOf(hub).getNextStartedActivity());
    }
  }

  @Test
  public void lastCodecRequiresDecoderReportNotPreference() {
    var context = RuntimeEnvironment.getApplication();
    assertNull(PcvrHistory.lastDecoded(context));
    PcvrHistory.decoded(context, "unknown");
    assertNull(PcvrHistory.lastDecoded(context));
    PcvrHistory.decoded(context, "AV1");
    assertEquals("AV1", PcvrHistory.lastDecoded(context));
    PcvrOptions options = new PcvrOptions(context);
    options.standardCodec("Hevc");
    assertEquals("AV1", PcvrHistory.lastDecoded(context));
  }
}
