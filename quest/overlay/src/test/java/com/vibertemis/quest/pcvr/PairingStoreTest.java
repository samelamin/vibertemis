package com.vibertemis.quest.pcvr;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class PairingStoreTest {
  @Test
  public void ciphertextRoundTripAndTamperRejection() throws Exception {
    android.content.Context context = RuntimeEnvironment.getApplication();
    SecretKey key = KeyGenerator.getInstance("AES").generateKey();
    PairingStore store = new PairingStore(context, create -> key);
    HostPairing pairing = HostClientTest.pairing("host", 28540);
    store.save(pairing);
    assertEquals(pairing.token, store.load().token);
    assertEquals(pairing.pin, store.load().pin);
    File file = new File(context.getNoBackupFilesDir(), "pcvr-pairing.enc");
    byte[] bytes = Files.readAllBytes(file.toPath());
    assertFalse(new String(bytes, StandardCharsets.ISO_8859_1).contains(pairing.token));
    bytes[bytes.length - 1] ^= 1;
    Files.write(file.toPath(), bytes);
    try {
      store.load();
      fail("tampered ciphertext accepted");
    } catch (javax.crypto.AEADBadTagException expected) {
    }
    file.delete();
  }

  @Test
  public void failedSaveKeepsPreviousPairing() throws Exception {
    android.content.Context context = RuntimeEnvironment.getApplication();
    SecretKey key = KeyGenerator.getInstance("AES").generateKey();
    PairingStore original = new PairingStore(context, create -> key);
    HostPairing pairing = HostClientTest.pairing("host", 28540);
    original.save(pairing);
    PairingStore broken =
        new PairingStore(
            context,
            create -> {
              throw new java.security.KeyStoreException("unavailable");
            });
    try {
      broken.save(HostClientTest.pairing("other", 28540));
      fail();
    } catch (java.security.KeyStoreException expected) {
    }
    assertEquals(pairing.pin, original.load().pin);
    new File(context.getNoBackupFilesDir(), "pcvr-pairing.enc").delete();
  }
}
