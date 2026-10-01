package com.vibertemis.quest.pcvr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;

/** Real activity interactions: taps and dialogs must reach PcvrOptions on their own. */
@RunWith(RobolectricTestRunner.class)
public class PcvrSettingsInteractionTest {
  private static final int RADIO_AV1 = 701;
  private static final int RADIO_HEVC = 702;

  private Application app;
  private PcvrSettingsActivity activity;
  private PcvrOptions options;
  private ActivityController<PcvrSettingsActivity> controller;

  @Before
  public void setUp() {
    app = RuntimeEnvironment.getApplication();
    app.getSharedPreferences("vq_pcvr_options", Context.MODE_PRIVATE).edit().clear().commit();
    Shadows.shadowOf(app.getPackageManager()).setSystemFeature(PackageManager.FEATURE_VR_HEADTRACKING, true);
    options = new PcvrOptions(app);
    controller = Robolectric.buildActivity(PcvrSettingsActivity.class).create().start().resume().visible();
    activity = controller.get();
  }

  @After
  public void tearDown() {
    controller.destroy();
  }

  @Test
  public void codecAndTravelTapsPersistThroughOptions() {
    RadioButton av1 = activity.findViewById(RADIO_AV1);
    RadioButton hevc = activity.findViewById(RADIO_HEVC);
    Switch travel = activity.findViewById(PcvrSettingsActivity.ID_TRAVEL_SWITCH);
    Button home = activity.findViewById(PcvrSettingsActivity.ID_HOME_BITRATE);
    Button travelBitrate = activity.findViewById(PcvrSettingsActivity.ID_TRAVEL_BITRATE);
    assertNotNull(av1);
    assertNotNull(hevc);
    assertNotNull(travel);
    assertNotNull(home);
    assertNotNull(travelBitrate);

    av1.performClick();
    assertTrue(av1.isChecked());
    assertEquals("AV1", options.standardCodec());
    hevc.performClick();
    assertTrue(hevc.isChecked());
    assertEquals("Hevc", options.standardCodec());

    assertFalse(travel.isChecked());
    travel.performClick();
    assertTrue(travel.isChecked());
    assertTrue(options.travel());
    travel.performClick();
    assertFalse(travel.isChecked());
    assertFalse(options.travel());

    assertTrue(home.isEnabled());
    assertTrue(travelBitrate.isEnabled());
    assertTrue(home.getText().toString().contains("Home limit: " + options.homeMbps()));
    assertTrue(travelBitrate.getText().toString().contains("Travel limit: " + options.travelMbps()));
  }

  @Test
  public void homeBitrateDialogSavesHomeOnlyAndCancelKeepsValue() {
    Button home = activity.findViewById(PcvrSettingsActivity.ID_HOME_BITRATE);
    Button travelBitrate = activity.findViewById(PcvrSettingsActivity.ID_TRAVEL_BITRATE);

    assertTrue(home.performClick());
    AlertDialog saveDialog = ShadowAlertDialog.getLatestAlertDialog();
    assertNotNull(saveDialog);
    NumberPicker savePicker = findNumberPicker(saveDialog.getWindow().getDecorView());
    assertNotNull(savePicker);
    savePicker.setValue(200);
    assertTrue(saveDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
    assertEquals(200, options.homeMbps());
    assertEquals(30, options.travelMbps());
    assertTrue(home.getText().toString().contains("Home limit: 200"));
    assertTrue(travelBitrate.getText().toString().contains("Travel limit: 30"));

    assertTrue(home.performClick());
    AlertDialog cancelDialog = ShadowAlertDialog.getLatestAlertDialog();
    assertNotNull(cancelDialog);
    NumberPicker cancelPicker = findNumberPicker(cancelDialog.getWindow().getDecorView());
    assertNotNull(cancelPicker);
    cancelPicker.setValue(175);
    assertTrue(cancelDialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
    assertEquals(200, options.homeMbps());
  }

  private static NumberPicker findNumberPicker(View view) {
    if (view instanceof NumberPicker) return (NumberPicker) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int index = 0; index < group.getChildCount(); index++) {
        NumberPicker found = findNumberPicker(group.getChildAt(index));
        if (found != null) return found;
      }
    }
    return null;
  }
}
