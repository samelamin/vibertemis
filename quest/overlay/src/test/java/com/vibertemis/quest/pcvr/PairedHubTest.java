package com.vibertemis.quest.pcvr;

import static org.junit.Assert.*;

import android.Manifest;
import android.content.pm.PackageManager;
import com.limelight.R;
import com.vibertemis.quest.hub.MainHubActivity;
import com.vibertemis.quest.hub.ShadowMoonBridge;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = ShadowMoonBridge.class)
public class PairedHubTest {
  static CountDownLatch entered, release;
  static AtomicInteger calls;

  public static class TestHub extends MainHubActivity {
    @Override protected String loadNativeHeadsetIdentity() { return "test.client"; }
    @Override
    protected boolean hasPairedHost() {
      return true;
    }

    @Override
    protected HostPairing loadHostPairing() throws Exception {
      return HostClientTest.pairing("host", 28540);
    }

    @Override
    protected HostClient createHostClient() {
      return new HostClient() {
        @Override
        public org.json.JSONObject request(HostPairing pairing, String method, String path, byte[] body) throws Exception {
          if (!"GET".equals(method) || !"/status".equals(path)) throw new AssertionError("Only a read-only preflight is allowed");
          return new org.json.JSONObject();
        }
        @Override
        public void start(HostPairing pairing, String codec) throws Exception {
          calls.incrementAndGet();
          entered.countDown();
          release.await(3, TimeUnit.SECONDS);
        }
      };
    }
  }

  @Before
  public void setup() {
    entered = new CountDownLatch(1);
    release = new CountDownLatch(1);
    calls = new AtomicInteger();
    android.content.Context app = RuntimeEnvironment.getApplication();
    Shadows.shadowOf(app.getPackageManager())
        .setSystemFeature(PackageManager.FEATURE_VR_HEADTRACKING, true);
    Shadows.shadowOf((android.app.Application) app)
        .grantPermissions(Manifest.permission.RECORD_AUDIO);
  }

  @After
  public void finish() {
    release.countDown();
  }

  @Test
  public void doubleTapOneRequestAndPauseDropsLateResult() throws Exception {
    ActivityController<TestHub> controller = Robolectric.buildActivity(TestHub.class).setup();
    TestHub hub = controller.get();
    hub.findViewById(R.id.hub_btn_connect).performClick();
    PcvrTestActions.confirmRestartIfShown();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    hub.findViewById(R.id.hub_btn_connect).performClick();
    PcvrTestActions.confirmRestartIfShown();
    hub.findViewById(R.id.hub_btn_screen).performClick();
    assertEquals(1, calls.get());
    controller.pause();
    release.countDown();
    Thread.sleep(100);
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertNull(Shadows.shadowOf(hub).getNextStartedActivity());
    controller.stop().destroy();
  }

  @Test
  public void revokedPermissionBeforeResponseNeverEntersVr() throws Exception {
    ActivityController<TestHub> controller = Robolectric.buildActivity(TestHub.class).setup();
    TestHub hub = controller.get();
    hub.findViewById(R.id.hub_btn_connect).performClick();
    PcvrTestActions.confirmRestartIfShown();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    Shadows.shadowOf(RuntimeEnvironment.getApplication())
        .denyPermissions(Manifest.permission.RECORD_AUDIO);
    release.countDown();
    Thread.sleep(100);
    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    assertNull(Shadows.shadowOf(hub).getNextStartedActivity());
    controller.pause().stop().destroy();
  }
}
