package com.vibertemis.quest.pcvr;
import android.app.AlertDialog;
import android.os.Looper;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlertDialog;

/** Explicit user confirmation used by existing launch/permission regression scenarios. */
public final class PcvrTestActions {
  private PcvrTestActions() {}
  public static void confirmRestartIfShown() {
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    // Existing native-launch scenarios now explicitly choose the manual path
    // instead of treating missing host pairing as implicit manual consent.
    if (dialog != null && dialog.isShowing() && dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null
        && "Manual VR".contentEquals(dialog.getButton(AlertDialog.BUTTON_NEUTRAL).getText())) {
      dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      dialog = ShadowAlertDialog.getLatestAlertDialog();
    }
    if (dialog != null && dialog.isShowing() && dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null
        && "Connect".contentEquals(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText())) {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
  }
}
