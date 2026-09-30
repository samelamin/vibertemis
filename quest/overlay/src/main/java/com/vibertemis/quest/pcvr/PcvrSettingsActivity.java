package com.vibertemis.quest.pcvr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.*;
import com.vibertemis.quest.hub.VrCapabilities;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small native panel: pairing first, codec choices second; advanced host tuning stays in ALVR. */
public final class PcvrSettingsActivity extends Activity {
  public static final String EXTRA_MANUAL_PAIRING = "manual_pairing";
  private static final int IMPORT = 604;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private TextView status;
  private PairingStore store;
  private PcvrOptions options;
  private LinearLayout body;
  private volatile boolean destroyed;
  private AlertDialog pairingDialog;
  private Button forgetButton;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    if (!VrCapabilities.isHeadset(this)) {
      finish();
      return;
    }
    store = new PairingStore(this);
    options = new PcvrOptions(this);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    Button back = button("Back", () -> finish());
    root.addView(back);
    ScrollView scroll = new ScrollView(this);
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    int padding = dp(20);
    body.setPadding(padding, padding, padding, padding);
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    setContentView(root);
    label("PCVR connection", 24);
    label(
        "In Windows VR Host Manager, choose Setup VR, then Pair headset. Here, return home and choose Setup VR. Compare the code and approve once on the PC. Vibeshine stays unchanged. Connect starts SteamVR for this headset.",
        16);
    status = label("Checking pairing…", 16);
    LinearLayout manualPairing = new LinearLayout(this);
    manualPairing.setOrientation(LinearLayout.VERTICAL);
    boolean showManual = getIntent().getBooleanExtra(EXTRA_MANUAL_PAIRING, false);
    manualPairing.setVisibility(showManual ? android.view.View.VISIBLE : android.view.View.GONE);
    Button advanced = button(showManual ? "Hide manual pairing" : "Advanced: manual pairing", () -> {});
    advanced.setOnClickListener(v -> {
      boolean visible = manualPairing.getVisibility() != android.view.View.VISIBLE;
      manualPairing.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
      advanced.setText(visible ? "Hide manual pairing" : "Advanced: manual pairing");
    });
    body.addView(advanced);
    body.addView(manualPairing);
    manualPairing.addView(
        button(
            "Import pairing file",
            () -> {
              Intent intent =
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .setType("*/*")
                      .addCategory(Intent.CATEGORY_OPENABLE);
              try {
                startActivityForResult(intent, IMPORT);
              } catch (Exception e) {
                message("File picker unavailable. Use Paste pairing instead.");
              }
            }));
    manualPairing.addView(button("Paste pairing", this::paste));
    forgetButton =
        button(
            "Forget paired PC",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("Forget this PC?")
                    .setMessage("Automatic startup will stop until you pair again.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton(
                        "Forget",
                        (d, w) ->
                            worker.execute(
                                () -> {
                                  try {
                                    store.forget();
                                    refreshPairing();
                                  } catch (Exception e) {
                                    runOnUiThread(
                                        () -> {
                                          if (!destroyed)
                                            message("Could not clear secure pairing. Try again.");
                                        });
                                  }
                                }))
                    .show());
    manualPairing.addView(forgetButton);
    String lastCodec = PcvrHistory.lastDecoded(this);
    if (lastCodec != null) label("Last decoded codec: " + lastCodec, 16);
    label("Streaming mode", 20);
    Switch travel = new Switch(this);
    travel.setText("Travel — use standard codec");
    travel.setMinHeight(dp(56));
    travel.setChecked(options.travel());
    body.addView(travel);
    travel.setOnCheckedChangeListener((v, checked) -> options.travel(checked));
    label(
        "Travel preserves your Home codec choices. Remote access needs a reachable PC address or an"
            + " existing VPN; internet speed alone does not determine latency.",
        16);
    Switch pyro = new Switch(this);
    pyro.setText("PyroWave (experimental)");
    pyro.setMinHeight(dp(56));
    pyro.setChecked(options.pyro());
    pyro.setEnabled(PcvrOptions.PYROWAVE_BUILD);
    body.addView(pyro);
    label(
        PcvrOptions.PYROWAVE_BUILD
            ? "Compatibility checked when connecting. Travel uses your standard codec."
            : "Requires matching experimental host and headset builds. Standard streaming remains"
                + " available.",
        16);
    pyro.setOnCheckedChangeListener((v, checked) -> options.pyro(checked));
    label("Standard codec", 20);
    RadioGroup codecs = new RadioGroup(this);
    String[] titles = {"Auto — keep host preference", "AV1", "HEVC"};
    String[] values = {"auto", "AV1", "Hevc"};
    for (int n = 0; n < values.length; n++) {
      RadioButton choice = new RadioButton(this);
      choice.setId(700 + n);
      choice.setText(titles[n]);
      choice.setMinHeight(dp(56));
      codecs.addView(choice);
      if (values[n].equals(options.standardCodec())) choice.setChecked(true);
    }
    body.addView(codecs);
    codecs.setOnCheckedChangeListener(
        (group, id) -> {
          int index = id - 700;
          if (index >= 0 && index < values.length) options.standardCodec(values[index]);
        });
    label(
        "Changes apply to your next PCVR connection. Disconnect first to change an active stream."
            + " The host may require a SteamVR restart. Requested codec is not confirmation of the"
            + " codec in use.",
        16);
    label("Quality and latency", 20);
    bitrateButton(false);
    bitrateButton(true);
    label(
        "Bitrate adapts below these limits. Home allows up to 200 Mbps; Travel starts at 30 Mbps to"
            + " leave room on a 50 Mbps connection. Your PC upload speed and network delay still"
            + " matter.",
        16);
    label(
        "Use the ALVR Dashboard for resolution, refresh rate and fixed foveation. Quest 3 has no"
            + " eye-tracking hardware.",
        16);
    worker.execute(this::refreshPairing);
  }

  private void bitrateButton(boolean travel) {
    Button button = button("", () -> {});
    Runnable refresh =
        () ->
            button.setText(
                (travel ? "Travel limit: " : "Home limit: ")
                    + (travel ? options.travelMbps() : options.homeMbps())
                    + " Mbps");
    refresh.run();
    button.setEnabled(PcvrOptions.PYROWAVE_BUILD);
    button.setOnClickListener(
        v -> {
          NumberPicker picker = new NumberPicker(this);
          picker.setMinValue(5);
          picker.setMaxValue(travel ? 45 : 200);
          picker.setValue(travel ? options.travelMbps() : options.homeMbps());
          picker.setWrapSelectorWheel(false);
          new AlertDialog.Builder(this)
              .setTitle(travel ? "Travel bitrate limit" : "Home bitrate limit")
              .setView(picker)
              .setNegativeButton("Cancel", null)
              .setPositiveButton(
                  "Save",
                  (d, w) -> {
                    picker.clearFocus();
                    options.bitrate(travel, picker.getValue());
                    refresh.run();
                  })
              .show();
        });
    body.addView(button);
  }

  private void refreshPairing() {
    String text;
    try {
      HostPairing pairing = store.load();
      text =
          pairing == null
              ? "VR not paired yet. Return to the home screen and choose Setup VR."
              : "Paired PC: " + pairing.address;
    } catch (Exception e) {
      text = "Pairing unavailable. Return to Setup VR to pair again, or use Advanced for a manual pairing file.";
    }
    final String value = text;
    runOnUiThread(
        () -> {
          if (!destroyed) {
            status.setText(value);
            forgetButton.setEnabled(store.hasPairing());
          }
        });
  }

  private void paste() {
    EditText input = new EditText(this);
    input.setHint("Paste pairing JSON");
    input.setMinLines(4);
    input.setInputType(
        InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Pair your PC")
            .setMessage("Paste the pairing export created by your Windows companion.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Pair", null)
            .create();
    pairingDialog = dialog;
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String text = input.getText().toString();
                      dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                      worker.execute(
                          () -> {
                            try {
                              HostPairing pairing = HostPairing.parse(text);
                              store.save(pairing);
                              runOnUiThread(
                                  () -> {
                                    if (!destroyed) {
                                      input.setText("");
                                      dialog.dismiss();
                                    }
                                  });
                              refreshPairing();
                            } catch (Exception e) {
                              runOnUiThread(
                                  () -> {
                                    if (!destroyed) {
                                      input.setError(
                                          "Invalid pairing or secure storage unavailable. Check the"
                                              + " export and both device clocks.");
                                      dialog
                                          .getButton(AlertDialog.BUTTON_POSITIVE)
                                          .setEnabled(true);
                                    }
                                  });
                            }
                          });
                    }));
    dialog.show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != IMPORT || result != RESULT_OK || data == null || data.getData() == null) return;
    android.net.Uri uri = data.getData();
    worker.execute(
        () -> {
          try {
            byte[] bytes =
                HostClient.readBounded(
                    getContentResolver().openInputStream(uri), HostPairing.MAX_BYTES);
            HostPairing pairing = HostPairing.parse(new String(bytes, StandardCharsets.UTF_8));
            store.save(pairing);
            refreshPairing();
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (!destroyed)
                    message(
                        "Could not import pairing. Choose the file exported by your Windows"
                            + " companion.");
                });
          }
        });
  }

  private TextView label(String text, int size) {
    TextView view = new TextView(this);
    view.setText(text);
    view.setTextSize(size);
    view.setPadding(0, dp(12), 0, dp(8));
    body.addView(view, new LinearLayout.LayoutParams(-1, -2));
    return view;
  }

  private Button button(String text, Runnable action) {
    Button button = new Button(this);
    button.setText(text);
    button.setMinHeight(dp(56));
    button.setOnClickListener(v -> action.run());
    return button;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private void message(String text) {
    new AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    if (pairingDialog != null) {
      pairingDialog.dismiss();
      pairingDialog = null;
    }
    worker.shutdownNow();
    super.onDestroy();
  }
}
