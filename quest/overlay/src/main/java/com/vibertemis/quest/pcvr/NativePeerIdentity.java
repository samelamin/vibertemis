package com.vibertemis.quest.pcvr;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** The identity used by the pinned native ALVR Config, not a second device ID. */
public final class NativePeerIdentity {
    static final String PROTOCOL = "20-vibertemis-pyro.1";
    private NativePeerIdentity() {}

    // app_dirs2 2.5.5 tries XDG first. On Android Rust home_dir has no
    // passwd fallback: without nonempty HOME, it uses Context.getDataDir().
    static File configFile(File dataDir, String home, String xdgConfig) {
        File base = dataDir;
        if (home != null && !home.isEmpty()) {
            base = xdgConfig != null && new File(xdgConfig).isAbsolute()
                    ? new File(xdgConfig) : new File(home, ".config");
        }
        return new File(base, "ALVR Client/session.json");
    }

    public static String loadOrCreate(Context context) throws Exception {
        return loadOrCreate(configFile(context.getDataDir(), System.getenv("HOME"),
                System.getenv("XDG_CONFIG_HOME")));
    }

    static synchronized String loadOrCreate(File path) throws Exception {
        AtomicFile file = new AtomicFile(path);
        if (path.exists() || new File(path + ".bak").exists()) {
            byte[] bytes;
            try (java.io.InputStream in = file.openRead()) {
                bytes = HostClient.readBounded(in, 4096);
            }
            JSONObject config = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            String hostname = config.getString("hostname");
            if (!hostname.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,31}")
                    || !PROTOCOL.equals(config.getString("protocol_id"))) {
                throw new HostClient.Failure("HEADSET_IDENTITY", "The saved VR headset identity needs repair. Your PC pairing has been kept.");
            }
            return hostname;
        }
        File parent = path.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new java.io.IOException("Could not save headset identity");
        String hostname = UUID.randomUUID().toString().replace("-", "").substring(0, 24) + ".client";
        byte[] bytes = new JSONObject().put("hostname", hostname).put("protocol_id", PROTOCOL)
                .toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(bytes);
            file.finishWrite(out);
        } catch (Exception e) {
            if (out != null) file.failWrite(out);
            throw e;
        }
        return hostname;
    }
}
