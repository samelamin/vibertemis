package com.vibertemis.quest.pcvr;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Small atomic record shared by the hub and isolated VR process. Never a claim of a live stream.
 */
public final class PcvrHistory {
  private PcvrHistory() {}

  public static void decoded(Context context, String codec) {
    if (!valid(codec)) return;
    AtomicFile file = file(context);
    FileOutputStream output = null;
    try {
      output = file.startWrite();
      output.write(codec.getBytes(StandardCharsets.US_ASCII));
      file.finishWrite(output);
    } catch (Exception e) {
      if (output != null) file.failWrite(output);
    }
  }

  public static String lastDecoded(Context context) {
    try {
      String value =
          new String(
              HostClient.readBounded(file(context).openRead(), 32), StandardCharsets.US_ASCII);
      return valid(value) ? value : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static AtomicFile file(Context context) {
    return new AtomicFile(new File(context.getNoBackupFilesDir(), "pcvr-last-decoded"));
  }

  private static boolean valid(String codec) {
    return "PyroWave".equals(codec)
        || "AV1".equals(codec)
        || "HEVC".equals(codec)
        || "H.264".equals(codec);
  }
}
