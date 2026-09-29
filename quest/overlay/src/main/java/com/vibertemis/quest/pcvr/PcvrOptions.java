package com.vibertemis.quest.pcvr;

import android.content.Context;
import android.content.SharedPreferences;

/** PCVR preferences never overwrite Moonlight's Screen gaming settings. */
public final class PcvrOptions {
  public static final boolean PYROWAVE_BUILD =
      true; // Requires the matching custom native library set (build-time verified).
  private final SharedPreferences prefs;

  public PcvrOptions(Context context) {
    prefs = context.getSharedPreferences("vq_pcvr_options", Context.MODE_PRIVATE);
  }

  public String standardCodec() {
    String value = prefs.getString("standard", "auto");
    return value.equals("AV1") || value.equals("Hevc") ? value : "auto";
  }

  public boolean travel() {
    return prefs.getBoolean("travel", false);
  }

  public boolean pyro() {
    return prefs.getBoolean("pyrowave", false);
  }

  public String requestedCodec() {
    return PYROWAVE_BUILD && pyro() && !travel() ? "PyroWave" : standardCodec();
  }

  public void standardCodec(String value) {
    if (!value.equals("AV1") && !value.equals("Hevc") && !value.equals("auto"))
      throw new IllegalArgumentException();
    prefs.edit().putString("standard", value).apply();
  }

  public void travel(boolean value) {
    prefs.edit().putBoolean("travel", value).apply();
  }

  public void pyro(boolean value) {
    prefs.edit().putBoolean("pyrowave", value).apply();
  }

  public int homeMbps() {
    return Math.max(5, Math.min(200, prefs.getInt("home_mbps", 200)));
  }

  public int travelMbps() {
    return Math.max(5, Math.min(45, prefs.getInt("travel_mbps", 30)));
  }

  public int bitrateMbps() {
    return travel() ? travelMbps() : homeMbps();
  }

  public void bitrate(boolean travel, int value) {
    if (value < 5 || value > (travel ? 45 : 200)) throw new IllegalArgumentException();
    prefs.edit().putInt(travel ? "travel_mbps" : "home_mbps", value).apply();
  }
}
