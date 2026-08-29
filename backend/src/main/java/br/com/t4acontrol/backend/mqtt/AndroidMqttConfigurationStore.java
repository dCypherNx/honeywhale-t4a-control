package br.com.t4acontrol.backend.mqtt;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Android persistence adapter. MQTT password is encrypted with an app-private Android Keystore key. */
public final class AndroidMqttConfigurationStore implements MqttConfigurationStore {
  private static final String PREFS = "t4a_mqtt";
  private static final String KEY_ALIAS = "t4a_mqtt_password_v1";
  private static final byte[] AAD = "br.com.t4acontrol.mqtt.v1".getBytes(StandardCharsets.UTF_8);

  private static final String ENABLED = "enabled";
  private static final String HOST = "host";
  private static final String PORT = "port";
  private static final String TLS = "tls"; // Legacy key retained for migration.
  private static final String TRANSPORT = "transport";
  private static final String WEBSOCKET_PATH = "websocket_path";
  private static final String USERNAME = "username";
  private static final String PASSWORD = "password_ciphertext";
  private static final String CLIENT_ID = "client_id";
  private static final String BASE_TOPIC = "base_topic";
  private static final String KEEP_ALIVE = "keep_alive_seconds";
  private static final String DISCOVERY_ENABLED = "discovery_enabled";
  private static final String REVISION = "revision";

  private final SharedPreferences preferences;

  public AndroidMqttConfigurationStore(Context context) {
    preferences =
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  @Override
  public MqttConfiguration load() {
    MqttConfiguration defaults = MqttConfiguration.defaults();
    boolean legacyTls = preferences.getBoolean(TLS, defaults.tls);
    MqttConfiguration.Transport transport =
        MqttConfiguration.Transport.fromStored(preferences.getString(TRANSPORT, ""), legacyTls);
    return new MqttConfiguration(
        preferences.getBoolean(ENABLED, defaults.enabled),
        preferences.getString(HOST, defaults.host),
        preferences.getInt(PORT, defaults.port),
        transport,
        preferences.getString(WEBSOCKET_PATH, defaults.websocketPath),
        preferences.getString(USERNAME, defaults.username),
        decryptPassword(preferences.getString(PASSWORD, "")),
        preferences.getString(CLIENT_ID, defaults.clientId),
        preferences.getString(BASE_TOPIC, defaults.baseTopic),
        preferences.getInt(KEEP_ALIVE, defaults.keepAliveSeconds),
        preferences.getBoolean(DISCOVERY_ENABLED, defaults.discoveryEnabled));
  }

  @Override
  public void save(MqttConfiguration configuration) {
    if (configuration == null) throw new IllegalArgumentException("configuration == null");
    MqttConfiguration.ValidationError error = configuration.validate();
    if (error != MqttConfiguration.ValidationError.NONE)
      throw new IllegalArgumentException("Invalid MQTT configuration: " + error);

    long nextRevision = preferences.getLong(REVISION, 0L) + 1L;
    preferences
        .edit()
        .putBoolean(ENABLED, configuration.enabled)
        .putString(HOST, configuration.host)
        .putInt(PORT, configuration.port)
        .putBoolean(TLS, configuration.tls)
        .putString(TRANSPORT, configuration.transport.name())
        .putString(WEBSOCKET_PATH, configuration.websocketPath)
        .putString(USERNAME, configuration.username)
        .putString(PASSWORD, encryptPassword(configuration.password))
        .putString(CLIENT_ID, configuration.clientId)
        .putString(BASE_TOPIC, configuration.baseTopic)
        .putInt(KEEP_ALIVE, configuration.keepAliveSeconds)
        .putBoolean(DISCOVERY_ENABLED, configuration.discoveryEnabled)
        .putLong(REVISION, nextRevision)
        .apply();
  }

  @Override
  public void clear() {
    preferences.edit().clear().apply();
    try {
      KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
      keyStore.load(null);
      if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
    } catch (Exception error) {
      throw new IllegalStateException("Unable to clear MQTT encryption key", error);
    }
  }

  @Override
  public long revision() {
    return preferences.getLong(REVISION, 0L);
  }

  private String encryptPassword(String password) {
    if (password == null || password.isEmpty()) return "";
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, secretKey());
      cipher.updateAAD(AAD);
      byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
      byte[] iv = cipher.getIV();
      ByteBuffer payload = ByteBuffer.allocate(1 + iv.length + encrypted.length);
      payload.put((byte) iv.length);
      payload.put(iv);
      payload.put(encrypted);
      return Base64.encodeToString(payload.array(), Base64.NO_WRAP);
    } catch (Exception error) {
      throw new IllegalStateException("Unable to encrypt MQTT password", error);
    }
  }

  private String decryptPassword(String encoded) {
    if (encoded == null || encoded.isEmpty()) return "";
    try {
      ByteBuffer payload = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
      int ivLength = payload.get() & 0xFF;
      if (ivLength < 12 || ivLength > 16 || payload.remaining() <= ivLength) return "";
      byte[] iv = new byte[ivLength];
      payload.get(iv);
      byte[] encrypted = new byte[payload.remaining()];
      payload.get(encrypted);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
      cipher.updateAAD(AAD);
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (Exception ignored) {
      return "";
    }
  }

  private SecretKey secretKey() throws Exception {
    KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
    keyStore.load(null);
    if (keyStore.containsAlias(KEY_ALIAS))
      return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();

    KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
    return generator.generateKey();
  }
}
