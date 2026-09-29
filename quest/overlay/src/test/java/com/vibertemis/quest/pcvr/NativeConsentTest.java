package com.vibertemis.quest.pcvr;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import com.limelight.R;
import com.vibertemis.quest.hub.MainHubActivity;
import com.vibertemis.quest.hub.ShadowMoonBridge;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class NativeConsentTest {
  public static class ManualNativeHub extends MainHubActivity {
    @Override
    protected boolean usesNativeRuntime() {
      return true;
    }

    @Override
    protected boolean hasPairedHost() {
      return false;
    }
  }

  @Before
  public void setup() {
    Shadows.shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
        .setSystemFeature(PackageManager.FEATURE_VR_HEADTRACKING, true);
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .grantPermissions(Manifest.permission.RECORD_AUDIO);
  }

  @Test
  public void connectCarriesExpiringConsentAndTravelBudget() {
    PcvrOptions options = new PcvrOptions(RuntimeEnvironment.getApplication());
    options.travel(true);
    options.bitrate(true, 25);
    try (var controller = Robolectric.buildActivity(ManualNativeHub.class).setup()) {
      var hub = controller.get();
      hub.findViewById(R.id.hub_btn_connect).performClick();
      assertNull(Shadows.shadowOf(hub).getNextStartedActivity());
      ShadowAlertDialog.getLatestAlertDialog()
          .getButton(AlertDialog.BUTTON_POSITIVE)
          .performClick();
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

  @Test
  public void leavingHubInvalidatesPendingConfirmation() {
    try (var controller = Robolectric.buildActivity(ManualNativeHub.class).setup()) {
      var hub = controller.get();
      hub.findViewById(R.id.hub_btn_connect).performClick();
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      controller.pause();
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
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
