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
    if (dialog != null && dialog.isShowing() && dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null
        && "Connect".contentEquals(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText())) {
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
  }
}
