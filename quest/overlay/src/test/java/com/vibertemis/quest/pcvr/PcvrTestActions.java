package com.vibertemis.quest.pcvr;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Looper;
import android.widget.Button;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowApplication;

/**
 * Drives the unpaired-headset launch journey through the new
 * Setup VR picker/empty state and the explicit Advanced → Manual
 * VR → Connect restart confirmation. Production code NEVER skips
 * these dialogs; the harness must follow the same path the user
 * follows.
 *
 * <p>The journey traverses up to three dialogs in sequence:
 * <ol>
 *   <li>Picker / "No VR PC found" (BUTTON_NEGATIVE = "Advanced")
 *       — the VR-setup picker surface appears whenever the user
 *       has no paired host, regardless of whether the discovery
 *       returned any candidates.</li>
 *   <li>Advanced VR pairing (BUTTON_NEUTRAL = "Manual VR") —
 *       the user opts out of file-based pairing and explicitly
 *       chooses to start VR anyway. If the microphone has not been
 *       granted the hub asks for it here instead of silently doing
 *       nothing; the grant continuation comes back to the same entry
 *       and the legacy consent prompt is still required, so the three
 *       stages below may be separated by a permission request.</li>
 *   <li>"Connect to PCVR?" (BUTTON_POSITIVE = "Connect") — the
 *       restart-consent prompt that the native runtime shows
 *       because SteamVR may restart.</li>
 * </ol>
 *
 * <p>Each stage checks the latest alert dialog and idles the main
 * looper between clicks so the next dialog has a chance to surface.
 * Stages whose dialog is not currently shown are skipped without
 * effect — tests that are not in the launch journey stay
 * unchanged. Production flow is preserved exactly: no dialog is
 * dismissed that the production code wouldn't dismiss, no consent
 * is bypassed, and the Screen-gaming detour is NOT reinstated.
 */
public final class PcvrTestActions {
  private PcvrTestActions() {}

  /** Wait up to {@code maxMillis} for the latest dialog to
   *  satisfy {@code predicate}, draining the main looper while
   *  waiting. Returns true on success, false on timeout. */
  public static boolean awaitDialog(
          java.util.function.Predicate<AlertDialog> predicate,
          long maxMillis) {
      long deadline = System.currentTimeMillis() + maxMillis;
      while (System.currentTimeMillis() < deadline) {
          AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
          if (d != null && d.isShowing() && predicate.test(d)) return true;
          Shadows.shadowOf(Looper.getMainLooper()).idle();
          try { Thread.sleep(20L); } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return false;
          }
      }
      AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
      return d != null && d.isShowing() && predicate.test(d);
  }

  /** Wait until the latest alert dialog's title contains
   *  {@code titleSubstring}, up to {@code maxMillis}. */
  public static boolean awaitDialogTitle(String titleSubstring, long maxMillis) {
      return awaitDialog(d -> {
          CharSequence t = Shadows.shadowOf(d).getTitle();
          return t != null && t.toString().contains(titleSubstring);
      }, maxMillis);
  }

  /** Stage 1: tap "Advanced" in the picker / empty state if
   *  visible. The picker dialog has BUTTON_NEGATIVE = "Advanced".
   *  Does nothing if the dialog isn't the picker. */
  public static boolean stepAdvancedIfPicker() {
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      if (dialog == null || !dialog.isShowing()) return false;
      CharSequence title = Shadows.shadowOf(dialog).getTitle();
      if (title == null) return false;
      String t = title.toString();
      boolean isPicker =
              t.contains("No VR PC found") || t.contains("Choose your PC for VR");
      if (!isPicker) return false;
      Button advanced = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
      if (advanced != null && "Advanced".contentEquals(advanced.getText())) {
          advanced.performClick();
          Shadows.shadowOf(Looper.getMainLooper()).idle();
          return true;
      }
      return false;
  }

  /** Stage 2: tap "Manual VR" in the Advanced VR pairing dialog
   *  if visible. */
  public static boolean stepManualVr() {
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      if (dialog == null || !dialog.isShowing()) return false;
      Button manual = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
      if (manual == null) return false;
      if (!"Manual VR".contentEquals(manual.getText())) return false;
      manual.performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      return true;
  }

  /** Stage 3: tap "Connect" in the restart-consent prompt if
   *  visible. */
  public static boolean stepConnect() {
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      if (dialog == null || !dialog.isShowing()) return false;
      Button connect = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
      if (connect == null) return false;
      if (!"Connect".contentEquals(connect.getText())) return false;
      connect.performClick();
      Shadows.shadowOf(Looper.getMainLooper()).idle();
      return true;
  }

  /**
   * Drive the launch journey forward by one stage at a time.
   * Each call walks exactly one dialog, leaving the next call
   * to handle the dialog that surfaces as a result. Tests that
   * need bounded UI waits between stages should call this in a
   * loop combined with {@link #awaitDialog}.
   */
  public static void confirmRestartIfShown() {
      stepAdvancedIfPicker();
      stepManualVr();
      stepConnect();
  }

  /** Dismiss whatever dialog is currently showing without firing any
   *  button or cancel callback. Tests call this before pausing and
   *  destroying an activity so a modal dialog never survives the test
   *  and leak into the next one's latest-dialog state. */
  public static void dismissLatestDialog() {
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      if (dialog == null) return;
      try { if (dialog.isShowing()) dialog.dismiss(); }
      catch (Exception ignored) { }
  }

  /** Action Robolectric records when the activity calls
   *  {@code requestPermissions}. A recorded permission request has no
   *  component, so tests can also spot it by that shape. */
  public static final String ACTION_REQUEST_PERMISSIONS =
          "android.content.pm.action.REQUEST_PERMISSIONS";

  /** Explicit {@code ComponentName} the hub dispatches for a VR
   *  launch. */
  public static final String STEAMVR_ACTIVITY =
          "com.vibertemis.quest.hub.SteamVrActivity";

  /**
   * Accumulating log of every started intent Robolectric recorded for
   * one test.
   *
   * <p>{@code getNextStartedActivity()} is destructive — each call
   * consumes one entry — so a test that drains the same queue twice
   * (for example before and after an asynchronous host callback)
   * silently loses the intents an earlier drain already consumed and
   * then asserts on an incomplete history. This log keeps every
   * drained intent, so {@link #drain} may be called as often as the
   * test needs while the counts keep describing the full session.
   */
  public static final class StartedIntentLog {
      private final java.util.List<Intent> intents = new java.util.ArrayList<>();

      private void drainAll(java.util.function.Supplier<Intent> next) {
          Intent i;
          while ((i = next.get()) != null) intents.add(i);
      }

      /** Drain the intents recorded against the application shadow. */
      public void drain(ShadowApplication app) {
          if (app == null) return;
          drainAll(app::getNextStartedActivity);
      }

      /** Drain the intents recorded against one activity shadow.
       *
       *  <p>Robolectric records the system permission-request intent
       *  and ordinary {@code startActivity} calls through the
       *  application shadow in some versions and through the activity
       *  shadow in others. Draining both views is always safe: when
       *  they share a single slot the second drain finds nothing. */
      public void drain(android.app.Activity activity) {
          if (activity == null) return;
          drainAll(() -> Shadows.shadowOf(activity).getNextStartedActivity());
      }

      /** Every intent recorded so far, in the order it was dispatched. */
      public java.util.List<Intent> all() {
          return new java.util.ArrayList<>(intents);
      }

      public int size() { return intents.size(); }

      /** Intents recorded for an explicit component class name. */
      public int countComponent(String className) {
          int n = 0;
          for (Intent i : intents) {
              if (i != null && i.getComponent() != null
                      && className.equals(i.getComponent().getClassName())) n++;
          }
          return n;
      }

      /** Intents recorded for an action string. */
      public int countAction(String action) {
          int n = 0;
          for (Intent i : intents) if (i != null && action.equals(i.getAction())) n++;
          return n;
      }

      /** Microphone permission requests the hub actually dispatched. */
      public int countPermissionRequests() {
          return countAction(ACTION_REQUEST_PERMISSIONS);
      }

      /** Permission requests Robolectric recorded without a component
       *  (the shape {@code requestPermissions} produces). */
      public int countComponentlessIntents() {
          int n = 0;
          for (Intent i : intents) if (i != null && i.getComponent() == null) n++;
          return n;
      }

      /** SteamVrActivity launches recorded so far. */
      public int countSteamVr() {
          return countComponent(STEAMVR_ACTIVITY);
      }
  }
}
