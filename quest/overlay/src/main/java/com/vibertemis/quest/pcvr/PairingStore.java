package com.vibertemis.quest.pcvr;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keys stay in AndroidKeyStore; only authenticated ciphertext enters no-backup storage. */
public final class PairingStore {
  private static final Object LOCK = new Object();
  private static final String ALIAS = "vq_pcvr_pairing_v1";
  private final AtomicFile file;

  public interface KeySource {
    SecretKey get(boolean create) throws Exception;
  }

  private final KeySource keys;

  public PairingStore(Context context, KeySource source) {
    file = new AtomicFile(new File(context.getNoBackupFilesDir(), "pcvr-pairing.enc"));
    keys = source;
  }

  public PairingStore(Context context) {
    this(context, null);
  }

  public boolean hasPairing() {
    synchronized (LOCK) {
      return file.getBaseFile().exists();
    }
  }

  private SecretKey key(boolean create) throws Exception {
    if (keys != null) return keys.get(create);
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
    if (!create) throw new IllegalStateException("Pairing key unavailable. Pair your PC again.");
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
    return generator.generateKey();
  }

  public HostPairing load() throws Exception {
    synchronized (LOCK) {
      if (!file.getBaseFile().exists()) return null;
      if (file.getBaseFile().length() > HostPairing.MAX_BYTES + 64)
        throw new IllegalStateException("Pairing is damaged. Pair again.");
      byte[] data = file.readFully();
      if (data.length < 30 || data[0] != 1)
        throw new IllegalStateException("Pairing is damaged. Pair again.");
      byte[] iv = new byte[12];
      System.arraycopy(data, 1, iv, 0, 12);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
      cipher.updateAAD(ALIAS.getBytes(StandardCharsets.US_ASCII));
      return HostPairing.parse(
          new String(cipher.doFinal(data, 13, data.length - 13), StandardCharsets.UTF_8));
    }
  }

  public void save(HostPairing pairing) throws Exception {
    synchronized (LOCK) {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(true));
      cipher.updateAAD(ALIAS.getBytes(StandardCharsets.US_ASCII));
      byte[] encrypted = cipher.doFinal(pairing.serialize().getBytes(StandardCharsets.UTF_8));
      byte[] iv = cipher.getIV();
      if (iv.length != 12) throw new IllegalStateException("Unsupported encryption IV.");
      byte[] data =
          ByteBuffer.allocate(13 + encrypted.length).put((byte) 1).put(iv).put(encrypted).array();
      FileOutputStream out = null;
      try {
        out = file.startWrite();
        out.write(data);
        file.finishWrite(out);
      } catch (Exception e) {
        if (out != null) file.failWrite(out);
        throw e;
      }
    }
  }

  public void updateAddress(HostPairing previous, HostPairing verified) throws Exception {
    synchronized (LOCK) {
      HostPairing current = load();
      if (current == null || !current.pin.equals(previous.pin) || !current.token.equals(previous.token))
        throw new IllegalStateException("Pairing changed during discovery. Retry.");
      if (!verified.pin.equals(previous.pin) || !verified.token.equals(previous.token))
        throw new IllegalArgumentException("Discovery cannot change paired identity");
      save(verified);
    }
  }

  public void forget() throws Exception {
    synchronized (LOCK) {
      file.delete();
      KeyStore keys = KeyStore.getInstance("AndroidKeyStore");
      keys.load(null);
      keys.deleteEntry(ALIAS);
    }
  }
}
