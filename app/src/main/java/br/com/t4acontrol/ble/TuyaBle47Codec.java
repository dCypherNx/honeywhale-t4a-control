package br.com.t4acontrol.ble;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure Java codec for the Tuya BLE 4.x envelope used by the paired T4A.
 *
 * <p>The implementation intentionally contains no ThingClips classes.  The SDK's protocol 4.7
 * name refers to the negotiated device protocol; its GATT envelope still uses the historical
 * type marker {@code 0x20}.  This class is also used by JVM tests, so all bounds are explicit.
 */
public final class TuyaBle47Codec {
  public static final int WIRE_TYPE = 2;
  public static final int DEVICE_INFO = 0;
  public static final int PAIR = 1;
  public static final int QUERY_DPS = 3;
  public static final int PUBLISH_DPS = 39;
  public static final int MAX_ENVELOPE = 8192;
  private static final SecureRandom RANDOM = new SecureRandom();

  private TuyaBle47Codec() {}

  public static byte[] encode(
      int sequence,
      int acknowledgement,
      int command,
      byte[] payload,
      int securityFlag,
      byte[] key,
      byte[] iv)
      throws GeneralSecurityException {
    byte[] plain = plain(sequence, acknowledgement, command, payload);
    if (securityFlag == 0) return prepend((byte) 0, plain);
    requireKey(key);
    byte[] actualIv = iv == null ? randomIv() : copyExact(iv, 16, "IV");
    byte[] padded = pad16(plain);
    byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, key, actualIv, padded);
    ByteArrayOutputStream result = new ByteArrayOutputStream(1 + 16 + encrypted.length);
    result.write(securityFlag & 0xFF);
    result.write(actualIv, 0, actualIv.length);
    result.write(encrypted, 0, encrypted.length);
    return result.toByteArray();
  }

  public static Message decode(byte[] envelope, byte[] key) throws GeneralSecurityException {
    if (envelope == null || envelope.length < 1 || envelope.length > MAX_ENVELOPE) {
      throw new IllegalArgumentException("invalid envelope length");
    }
    int securityFlag = envelope[0] & 0xFF;
    byte[] plain;
    if (securityFlag == 0) {
      plain = Arrays.copyOfRange(envelope, 1, envelope.length);
    } else {
      requireKey(key);
      if (envelope.length < 17 || ((envelope.length - 17) & 15) != 0) {
        throw new IllegalArgumentException("encrypted envelope is not block aligned");
      }
      byte[] iv = Arrays.copyOfRange(envelope, 1, 17);
      byte[] encrypted = Arrays.copyOfRange(envelope, 17, envelope.length);
      plain = crypt(Cipher.DECRYPT_MODE, key, iv, encrypted);
    }
    if (plain.length < 14) throw new IllegalArgumentException("frame is too short");
    ByteBuffer header = ByteBuffer.wrap(plain).order(ByteOrder.BIG_ENDIAN);
    int sequence = header.getInt();
    int acknowledgement = header.getInt();
    int command = Short.toUnsignedInt(header.getShort());
    int payloadLength = Short.toUnsignedInt(header.getShort());
    int end = 12 + payloadLength;
    if (end + 2 > plain.length) throw new IllegalArgumentException("payload exceeds frame");
    int expected = Short.toUnsignedInt(headerAt(plain, end));
    int actual = crc16(plain, 0, end);
    if (expected != actual) throw new IllegalArgumentException("CRC mismatch");
    return new Message(
        securityFlag, sequence, acknowledgement, command, Arrays.copyOfRange(plain, 12, end));
  }

  public static List<byte[]> fragment(byte[] envelope, int packetSize) {
    if (envelope == null || envelope.length == 0 || envelope.length > MAX_ENVELOPE) {
      throw new IllegalArgumentException("invalid envelope");
    }
    if (packetSize < 20) packetSize = 20;
    List<byte[]> packets = new ArrayList<>();
    int offset = 0;
    int packet = 0;
    do {
      ByteArrayOutputStream prefix = new ByteArrayOutputStream();
      writeVarInt(prefix, packet++);
      if (offset == 0) {
        writeVarInt(prefix, envelope.length);
        prefix.write(WIRE_TYPE << 4);
      }
      int amount = Math.min(envelope.length - offset, packetSize - prefix.size());
      if (amount <= 0) throw new IllegalArgumentException("packet size too small");
      prefix.write(envelope, offset, amount);
      packets.add(prefix.toByteArray());
      offset += amount;
    } while (offset < envelope.length);
    return packets;
  }

  public static byte[] deviceInfoPayload(int mtu) {
    if (mtu < 20 || mtu > 65535) throw new IllegalArgumentException("invalid MTU");
    return new byte[] {(byte) (mtu >>> 8), (byte) mtu};
  }

  /** Builds the acknowledgement body required by a protocol 4.7 DPS report. */
  public static byte[] dpsReportAckPayload(byte[] reportPayload) {
    if (reportPayload == null || reportPayload.length < 7) {
      throw new IllegalArgumentException("short DPS report");
    }
    ByteBuffer input = ByteBuffer.wrap(reportPayload).order(ByteOrder.BIG_ENDIAN);
    int version = input.get() & 0xFF;
    int sequence = input.getInt();
    int bType = input.get() & 0xFF;
    int flag = input.get() & 0xFF;
    return ByteBuffer.allocate(8)
        .order(ByteOrder.BIG_ENDIAN)
        .put((byte) version)
        .putInt(sequence)
        .put((byte) bType)
        .put((byte) flag)
        .put((byte) 0)
        .array();
  }

  public static byte[] pairPayload(
      String uuid, String loginKey, String deviceId, String loginKeyComplete, String secretKey) {
    byte[] uuidBytes = uuidBytes(uuid);
    byte[] login = utf8(loginKey);
    byte[] devId = utf8(deviceId);
    byte[] complete = utf8(loginKeyComplete);
    byte[] secret = utf8(secretKey);
    if (login.length != 6 || complete.length != 16 || secret.length != 16) {
      throw new IllegalArgumentException("T4A credentials have unexpected lengths");
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream(16 + 6 + 22 + 1 + 1 + 16 + 16 + 4);
    out.write(uuidBytes, 0, uuidBytes.length);
    out.write(login, 0, login.length);
    out.write(devId, 0, Math.min(devId.length, 22));
    for (int i = devId.length; i < 22; i++) out.write(0);
    out.write(0); // no beacon key
    out.write(1); // reconnect
    out.write(complete, 0, complete.length);
    out.write(secret, 0, secret.length);
    out.write(new byte[4], 0, 4); // verify key is only supplied by cloud during activation
    return out.toByteArray();
  }

  public static byte[] deriveKey5(String loginKey, byte[] srand) {
    return md5(concat(utf8(loginKey), copyExact(srand, 6, "srand")));
  }

  public static byte[] deriveKey14(String loginKeyComplete, String secretKey) {
    return md5(utf8(required(loginKeyComplete) + required(secretKey)));
  }

  public static byte[] deriveKey15(String loginKeyComplete, String secretKey, byte[] srand) {
    return md5(concat(utf8(required(loginKeyComplete) + required(secretKey)), copyExact(srand, 6, "srand")));
  }

  public static DeviceInfo parseDeviceInfo(byte[] payload) {
    if (payload == null || payload.length < 46) throw new IllegalArgumentException("short device info");
    int protocolMajor = payload[2] & 0xFF;
    int protocolMinor = payload[3] & 0xFF;
    int flags = payload[4] & 0xFF;
    boolean bound = (payload[5] & 0xFF) == 1;
    byte[] srand = Arrays.copyOfRange(payload, 6, 12);
    byte[] authKey = Arrays.copyOfRange(payload, 14, 46);
    return new DeviceInfo(
        (payload[0] & 0xFF) + "." + (payload[1] & 0xFF),
        protocolMajor + "." + protocolMinor,
        protocolMajor,
        protocolMinor,
        flags,
        bound,
        (flags & 2) != 0,
        (flags & 8) != 0,
        (flags & 16) != 0,
        srand,
        authKey);
  }

  public static int crc16(byte[] data, int offset, int length) {
    if (data == null || offset < 0 || length < 0 || offset + length > data.length) {
      throw new IllegalArgumentException("invalid CRC range");
    }
    int crc = 0xFFFF;
    for (int i = offset; i < offset + length; i++) {
      crc ^= data[i] & 0xFF;
      for (int bit = 0; bit < 8; bit++) {
        crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
      }
    }
    return crc & 0xFFFF;
  }

  private static byte[] plain(int sequence, int acknowledgement, int command, byte[] payload) {
    byte[] actual = payload == null ? new byte[0] : payload;
    if (actual.length > 0xFFFF) throw new IllegalArgumentException("payload too large");
    ByteBuffer body = ByteBuffer.allocate(14 + actual.length).order(ByteOrder.BIG_ENDIAN);
    body.putInt(sequence).putInt(acknowledgement).putShort((short) command).putShort((short) actual.length);
    body.put(actual);
    body.putShort((short) crc16(body.array(), 0, 12 + actual.length));
    return body.array();
  }

  private static short headerAt(byte[] value, int offset) {
    return ByteBuffer.wrap(value, offset, 2).order(ByteOrder.BIG_ENDIAN).getShort();
  }

  private static byte[] crypt(int mode, byte[] key, byte[] iv, byte[] value) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
    cipher.init(mode, new SecretKeySpec(copyExact(key, 16, "key"), "AES"), new IvParameterSpec(iv));
    return cipher.doFinal(value);
  }

  private static byte[] pad16(byte[] value) {
    int length = ((value.length + 15) / 16) * 16;
    return Arrays.copyOf(value, length);
  }

  private static byte[] prepend(byte value, byte[] rest) {
    byte[] result = new byte[rest.length + 1];
    result[0] = value;
    System.arraycopy(rest, 0, result, 1, rest.length);
    return result;
  }

  private static void writeVarInt(ByteArrayOutputStream out, int value) {
    do {
      int part = value & 0x7F;
      value >>>= 7;
      if (value != 0) part |= 0x80;
      out.write(part);
    } while (value != 0);
  }

  private static byte[] uuidBytes(String uuid) {
    byte[] value = utf8(uuid);
    if (value.length == 20 && valueAsHex(value)) {
      byte[] decoded = new byte[10];
      for (int i = 0; i < decoded.length; i++) decoded[i] = (byte) Integer.parseInt(new String(value, i * 2, 2, StandardCharsets.UTF_8), 16);
      value = decoded;
    }
    if (value.length > 16) value = Arrays.copyOf(value, 16);
    byte[] result = Arrays.copyOf(value, 16);
    Arrays.fill(result, value.length, 16, (byte) 0xFF);
    return result;
  }

  private static boolean valueAsHex(byte[] value) {
    for (byte item : value) if (Character.digit((char) item, 16) < 0) return false;
    return true;
  }

  private static byte[] utf8(String value) { return required(value).getBytes(StandardCharsets.UTF_8); }
  private static String required(String value) { if (value == null || value.isEmpty()) throw new IllegalArgumentException("missing credential"); return value; }
  private static byte[] concat(byte[] first, byte[] second) { byte[] result = Arrays.copyOf(first, first.length + second.length); System.arraycopy(second, 0, result, first.length, second.length); return result; }
  private static byte[] md5(byte[] value) { try { return MessageDigest.getInstance("MD5").digest(value); } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); } }
  private static byte[] randomIv() { byte[] iv = new byte[16]; RANDOM.nextBytes(iv); return iv; }
  private static void requireKey(byte[] key) { copyExact(key, 16, "key"); }
  private static byte[] copyExact(byte[] value, int length, String name) { if (value == null || value.length != length) throw new IllegalArgumentException(name + " must have length " + length); return Arrays.copyOf(value, value.length); }

  public static final class Message {
    public final int securityFlag, sequence, acknowledgement, command;
    public final byte[] payload;
    private Message(int flag, int sequence, int acknowledgement, int command, byte[] payload) {
      this.securityFlag = flag; this.sequence = sequence; this.acknowledgement = acknowledgement; this.command = command; this.payload = payload;
    }
  }

  public static final class DeviceInfo {
    public final String deviceVersion, protocolVersion; public final int protocolMajor, protocolMinor, flags; public final boolean bound, v4NeedAuth, v4NeedServerAuth, needBeaconKey; public final byte[] srand, authKey;
    private DeviceInfo(String dv, String pv, int major, int minor, int flags, boolean bound, boolean auth, boolean server, boolean beacon, byte[] srand, byte[] authKey) { this.deviceVersion=dv; this.protocolVersion=pv; this.protocolMajor=major; this.protocolMinor=minor; this.flags=flags; this.bound=bound; this.v4NeedAuth=auth; this.v4NeedServerAuth=server; this.needBeaconKey=beacon; this.srand=Arrays.copyOf(srand,srand.length); this.authKey=Arrays.copyOf(authKey,authKey.length); }
  }

  public static final class Reassembler {
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private int expectedPacket, expectedLength = -1;
    public byte[] accept(byte[] packet) {
      if (packet == null || packet.length == 0) throw new IllegalArgumentException("empty BLE fragment");
      VarInt number = readVarInt(packet, 0); int offset = number.next;
      if (number.value == 0) { VarInt length = readVarInt(packet, offset); offset = length.next; if (offset >= packet.length) throw new IllegalArgumentException("missing BLE type"); offset++; buffer.reset(); expectedPacket=0; expectedLength=length.value; if (expectedLength <= 0 || expectedLength > MAX_ENVELOPE) throw new IllegalArgumentException("invalid envelope length"); }
      if (number.value != expectedPacket || offset > packet.length) { reset(); throw new IllegalArgumentException("out of order BLE fragment"); }
      buffer.write(packet, offset, packet.length-offset); expectedPacket++;
      if (buffer.size() > expectedLength) { reset(); throw new IllegalArgumentException("fragment overflow"); }
      if (buffer.size() == expectedLength) { byte[] result=buffer.toByteArray(); reset(); return result; }
      return null;
    }
    public void reset() { buffer.reset(); expectedPacket=0; expectedLength=-1; }
    private static final class VarInt { final int value,next; VarInt(int value,int next){this.value=value;this.next=next;} }
    private static VarInt readVarInt(byte[] value,int start){int result=0,shift=0,offset=start;while(offset<value.length&&shift<28){int part=value[offset++]&0xFF;result|=(part&0x7F)<<shift;if((part&0x80)==0)return new VarInt(result,offset);shift+=7;}throw new IllegalArgumentException("invalid BLE varint");}
  }
}
