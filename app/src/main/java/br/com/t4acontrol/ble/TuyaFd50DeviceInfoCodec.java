package br.com.t4acontrol.ble;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal Tuya BLE FD50 codec used only by the native diagnostic probe.
 *
 * <p>It intentionally implements only the non-actuating DEVICE_INFO exchange. It does not build
 * PAIR or datapoint commands.
 */
final class TuyaFd50DeviceInfoCodec {
  static final int DEVICE_INFO_CODE = 0x0000;
  static final byte SECURITY_LOGIN_KEY = 0x04;
  static final byte FD50_PROTOCOL_MARKER = 0x20;
  static final byte[] FD50_DEVICE_INFO_PAYLOAD = new byte[] {0x00, (byte) 0xF3};

  private static final SecureRandom RANDOM = new SecureRandom();

  private TuyaFd50DeviceInfoCodec() {}

  static SessionMaterial sessionMaterial(String localKey) throws GeneralSecurityException {
    if (localKey == null || localKey.length() < 6) {
      throw new GeneralSecurityException("localKey must contain at least six characters");
    }
    byte[] localKey6 = localKey.substring(0, 6).getBytes(StandardCharsets.UTF_8);
    byte[] loginKey = md5(localKey6);
    return new SessionMaterial(localKey6, loginKey);
  }

  static byte[] buildDeviceInfoFrame(String localKey, int sequence)
      throws GeneralSecurityException {
    byte[] iv = new byte[16];
    RANDOM.nextBytes(iv);
    return buildDeviceInfoFrame(localKey, sequence, iv);
  }

  static byte[] buildDeviceInfoFrame(String localKey, int sequence, byte[] iv)
      throws GeneralSecurityException {
    if (iv == null || iv.length != 16) {
      throw new GeneralSecurityException("IV must contain exactly 16 bytes");
    }

    SessionMaterial material = sessionMaterial(localKey);
    byte[] body = buildPlainBody(sequence, 0, DEVICE_INFO_CODE, FD50_DEVICE_INFO_PAYLOAD);
    byte[] encryptedBody = aesCbcEncrypt(material.loginKey, iv, body);

    ByteArrayOutputStream encrypted = new ByteArrayOutputStream(1 + iv.length + encryptedBody.length);
    encrypted.write(SECURITY_LOGIN_KEY);
    encrypted.write(iv, 0, iv.length);
    encrypted.write(encryptedBody, 0, encryptedBody.length);
    byte[] encryptedBytes = encrypted.toByteArray();

    ByteArrayOutputStream frame = new ByteArrayOutputStream(encryptedBytes.length + 4);
    writeVarInt(frame, 0); // packet number
    writeVarInt(frame, encryptedBytes.length);
    frame.write(FD50_PROTOCOL_MARKER);
    frame.write(encryptedBytes, 0, encryptedBytes.length);
    return frame.toByteArray();
  }

  static ParsedDeviceInfo parseDeviceInfoResponse(
      byte protocolMarker, byte[] encrypted, SessionMaterial material)
      throws GeneralSecurityException {
    if (encrypted == null || encrypted.length < 33) {
      throw new GeneralSecurityException("encrypted Tuya response is too short");
    }

    int securityFlag = encrypted[0] & 0xFF;
    if (securityFlag != (SECURITY_LOGIN_KEY & 0xFF)) {
      throw new GeneralSecurityException("unexpected security flag " + securityFlag);
    }

    byte[] iv = Arrays.copyOfRange(encrypted, 1, 17);
    byte[] ciphertext = Arrays.copyOfRange(encrypted, 17, encrypted.length);
    if ((ciphertext.length % 16) != 0) {
      throw new GeneralSecurityException("ciphertext is not block aligned");
    }

    byte[] plain = aesCbcDecrypt(material.loginKey, iv, ciphertext);
    if (plain.length < 14) {
      throw new GeneralSecurityException("decrypted response is too short");
    }

    ByteBuffer header = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN);
    long sequence = Integer.toUnsignedLong(header.getInt());
    long responseTo = Integer.toUnsignedLong(header.getInt());
    int code = Short.toUnsignedInt(header.getShort());
    int payloadLength = Short.toUnsignedInt(header.getShort());
    int payloadEnd = 12 + payloadLength;
    if (payloadEnd + 2 > plain.length) {
      throw new GeneralSecurityException("payload length exceeds decrypted response");
    }

    byte[] payload = Arrays.copyOfRange(plain, 12, payloadEnd);
    int expectedCrc = ((plain[payloadEnd] & 0xFF) << 8) | (plain[payloadEnd + 1] & 0xFF);
    int actualCrc = crc16(Arrays.copyOfRange(plain, 0, payloadEnd));
    if (expectedCrc != actualCrc) {
      throw new GeneralSecurityException("Tuya CRC mismatch");
    }
    if (code != DEVICE_INFO_CODE) {
      throw new GeneralSecurityException("unexpected response code " + code);
    }
    if (payload.length < 46) {
      throw new GeneralSecurityException("DEVICE_INFO response payload is too short");
    }

    byte[] srand = Arrays.copyOfRange(payload, 6, 12);
    byte[] sessionKeyInput = new byte[material.localKey6.length + srand.length];
    System.arraycopy(material.localKey6, 0, sessionKeyInput, 0, material.localKey6.length);
    System.arraycopy(srand, 0, sessionKeyInput, material.localKey6.length, srand.length);
    byte[] sessionKey = md5(sessionKeyInput);

    return new ParsedDeviceInfo(
        protocolMarker & 0xFF,
        securityFlag,
        sequence,
        responseTo,
        code,
        payloadLength,
        (payload[0] & 0xFF) + "." + (payload[1] & 0xFF),
        (payload[2] & 0xFF) + "." + (payload[3] & 0xFF),
        payload[4] & 0xFF,
        payload[5] != 0,
        (payload[12] & 0xFF) + "." + (payload[13] & 0xFF),
        sessionKey,
        Arrays.copyOfRange(payload, 14, 46));
  }

  private static byte[] buildPlainBody(int sequence, int responseTo, int code, byte[] payload) {
    int rawLength = 12 + payload.length + 2;
    int paddedLength = ((rawLength + 15) / 16) * 16;
    ByteBuffer buffer = ByteBuffer.allocate(paddedLength).order(ByteOrder.BIG_ENDIAN);
    buffer.putInt(sequence);
    buffer.putInt(responseTo);
    buffer.putShort((short) code);
    buffer.putShort((short) payload.length);
    buffer.put(payload);

    int crcPosition = buffer.position();
    byte[] crcInput = Arrays.copyOfRange(buffer.array(), 0, crcPosition);
    buffer.putShort((short) crc16(crcInput));
    return buffer.array();
  }

  static int crc16(byte[] data) {
    int crc = 0xFFFF;
    for (byte item : data) {
      crc ^= item & 0xFF;
      for (int bit = 0; bit < 8; bit++) {
        int low = crc & 1;
        crc >>>= 1;
        if (low != 0) crc ^= 0xA001;
      }
    }
    return crc & 0xFFFF;
  }

  private static byte[] aesCbcEncrypt(byte[] key, byte[] iv, byte[] plain)
      throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    return cipher.doFinal(plain);
  }

  private static byte[] aesCbcDecrypt(byte[] key, byte[] iv, byte[] encrypted)
      throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
    return cipher.doFinal(encrypted);
  }

  private static byte[] md5(byte[] data) throws GeneralSecurityException {
    try {
      return MessageDigest.getInstance("MD5").digest(data);
    } catch (NoSuchAlgorithmException error) {
      throw new GeneralSecurityException("MD5 unavailable", error);
    }
  }

  private static void writeVarInt(ByteArrayOutputStream output, int value) {
    int remaining = value;
    while (true) {
      int current = remaining & 0x7F;
      remaining >>>= 7;
      if (remaining != 0) current |= 0x80;
      output.write(current);
      if (remaining == 0) return;
    }
  }

  static final class SessionMaterial {
    final byte[] localKey6;
    final byte[] loginKey;

    SessionMaterial(byte[] localKey6, byte[] loginKey) {
      this.localKey6 = Arrays.copyOf(localKey6, localKey6.length);
      this.loginKey = Arrays.copyOf(loginKey, loginKey.length);
    }
  }

  static final class ParsedDeviceInfo {
    final int protocolMarker;
    final int securityFlag;
    final long sequence;
    final long responseTo;
    final int code;
    final int payloadLength;
    final String deviceVersion;
    final String protocolVersion;
    final int flags;
    final boolean bound;
    final String hardwareVersion;
    final byte[] sessionKey;
    final byte[] authKey;

    ParsedDeviceInfo(
        int protocolMarker,
        int securityFlag,
        long sequence,
        long responseTo,
        int code,
        int payloadLength,
        String deviceVersion,
        String protocolVersion,
        int flags,
        boolean bound,
        String hardwareVersion,
        byte[] sessionKey,
        byte[] authKey) {
      this.protocolMarker = protocolMarker;
      this.securityFlag = securityFlag;
      this.sequence = sequence;
      this.responseTo = responseTo;
      this.code = code;
      this.payloadLength = payloadLength;
      this.deviceVersion = deviceVersion;
      this.protocolVersion = protocolVersion;
      this.flags = flags;
      this.bound = bound;
      this.hardwareVersion = hardwareVersion;
      this.sessionKey = Arrays.copyOf(sessionKey, sessionKey.length);
      this.authKey = Arrays.copyOf(authKey, authKey.length);
    }
  }
}