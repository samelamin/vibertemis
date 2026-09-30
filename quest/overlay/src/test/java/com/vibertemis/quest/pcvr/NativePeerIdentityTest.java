package com.vibertemis.quest.pcvr;

import static org.junit.Assert.*;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class NativePeerIdentityTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void matchesPinnedNativePaths() {
        File data = new File("/data/user/0/app");
        assertEquals(new File(data, "ALVR Client/session.json"), NativePeerIdentity.configFile(data, null, "/ignored"));
        assertEquals(new File(data, "ALVR Client/session.json"), NativePeerIdentity.configFile(data, "", null));
        assertEquals(new File("/home/app/.config/ALVR Client/session.json"), NativePeerIdentity.configFile(data, "/home/app", "relative"));
        assertEquals(new File("/config/ALVR Client/session.json"), NativePeerIdentity.configFile(data, "/home/app", "/config"));
    }

    @Test public void createsNativeSchemaOnceAndPreservesExistingIdentity() throws Exception {
        File file = new File(temp.getRoot(), "ALVR Client/session.json");
        String id = NativePeerIdentity.loadOrCreate(file);
        byte[] before = Files.readAllBytes(file.toPath());
        JSONObject nativeConfig = new JSONObject(new String(before, StandardCharsets.UTF_8));
        assertEquals(id, nativeConfig.getString("hostname"));
        assertEquals("20-vibertemis-pyro.1", nativeConfig.getString("protocol_id"));
        assertTrue(id.length() <= 32);
        assertEquals(id, NativePeerIdentity.loadOrCreate(file));
        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
        String legacy = "{\"hostname\":\"1234.client\",\"protocol_id\":\"20-vibertemis-pyro.1\"}";
        Files.write(file.toPath(), legacy.getBytes(StandardCharsets.UTF_8));
        assertEquals("1234.client", NativePeerIdentity.loadOrCreate(file));
        assertEquals(legacy, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    @Test public void corruptOrWrongProtocolDoesNotResetIdentity() throws Exception {
        File file = temp.newFile();
        for (String value : new String[]{"{", "{\"hostname\":\"quest.client\",\"protocol_id\":\"20\"}", "{\"hostname\":\"../bad\",\"protocol_id\":\"20-vibertemis-pyro.1\"}"}) {
            Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
            assertThrows(Exception.class, () -> NativePeerIdentity.loadOrCreate(file));
            assertEquals(value, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
    }
}
