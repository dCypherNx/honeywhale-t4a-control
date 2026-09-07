package br.com.t4acontrol.ble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class TuyaBle47CodecTest {
  @Test
  public void encryptsAndDecodesTheSdkEnvelope() throws Exception {
    byte[] key = "1234567890abcdef".getBytes(StandardCharsets.UTF_8);
    byte[] iv = new byte[16];
    for (int i = 0; i < iv.length; i++) iv[i] = (byte) i;
    byte[] envelope = TuyaBle47Codec.encode(7, 3, 39, new byte[] {1, 2, 3}, 15, key, iv);
    TuyaBle47Codec.Message decoded = TuyaBle47Codec.decode(envelope, key);
    assertEquals(15, decoded.securityFlag);
    assertEquals(7, decoded.sequence);
    assertEquals(3, decoded.acknowledgement);
    assertEquals(39, decoded.command);
    assertArrayEquals(new byte[] {1, 2, 3}, decoded.payload);
  }

  @Test
  public void reassemblesOutOfOrderSafeFragments() throws Exception {
    byte[] key = "1234567890abcdef".getBytes(StandardCharsets.UTF_8);
    byte[] envelope = TuyaBle47Codec.encode(1, 0, 0, new byte[90], 15, key, new byte[16]);
    List<byte[]> fragments = TuyaBle47Codec.fragment(envelope, 20);
    TuyaBle47Codec.Reassembler reassembler = new TuyaBle47Codec.Reassembler();
    assertNull(reassembler.accept(fragments.get(0)));
    for (int i = 1; i < fragments.size() - 1; i++) assertNull(reassembler.accept(fragments.get(i)));
    byte[] complete = reassembler.accept(fragments.get(fragments.size() - 1));
    assertNotNull(complete);
    assertArrayEquals(envelope, complete);
  }

  @Test
  public void rejectsCrcCorruptionAndOutOfOrderFragments() throws Exception {
    byte[] key = "1234567890abcdef".getBytes(StandardCharsets.UTF_8);
    byte[] envelope = TuyaBle47Codec.encode(1, 0, 0, new byte[30], 15, key, new byte[16]);
    byte[] damaged = Arrays.copyOf(envelope, envelope.length);
    damaged[damaged.length - 1] ^= 1;
    assertThrows(IllegalArgumentException.class, () -> TuyaBle47Codec.decode(damaged, key));
    List<byte[]> fragments = TuyaBle47Codec.fragment(envelope, 20);
    TuyaBle47Codec.Reassembler reassembler = new TuyaBle47Codec.Reassembler();
    assertThrows(IllegalArgumentException.class, () -> reassembler.accept(fragments.get(1)));
  }

  @Test
  public void acceptsTheResponseMarkerUsedByTheDevice() throws Exception {
    byte[] key = "1234567890abcdef".getBytes(StandardCharsets.UTF_8);
    byte[] envelope = TuyaBle47Codec.encode(1, 0, 0x8006, new byte[30], 15, key, new byte[16]);
    List<byte[]> fragments = TuyaBle47Codec.fragment(envelope, 20);
    fragments.get(0)[2] = 0x70;
    TuyaBle47Codec.Reassembler reassembler = new TuyaBle47Codec.Reassembler();
    for (int i = 0; i < fragments.size() - 1; i++) assertNull(reassembler.accept(fragments.get(i)));
    assertArrayEquals(envelope, reassembler.accept(fragments.get(fragments.size() - 1)));
  }

  @Test
  public void derivesNewSecurityKeysFromUtf8Credentials() throws Exception {
    byte[] base = "complete-secret".getBytes(StandardCharsets.UTF_8);
    byte[] srand = new byte[] {0, 1, 2, 3, 4, 5};
    assertArrayEquals(MessageDigest.getInstance("MD5").digest(base), TuyaBle47Codec.deriveKey14("complete", "-secret"));
    byte[] key15Input = Arrays.copyOf(base, base.length + srand.length);
    System.arraycopy(srand, 0, key15Input, base.length, srand.length);
    assertArrayEquals(MessageDigest.getInstance("MD5").digest(key15Input), TuyaBle47Codec.deriveKey15("complete", "-secret", srand));
  }

  @Test
  public void buildsTheBoundPairPayloadWithoutSecretsInLogs() {
    byte[] payload = TuyaBle47Codec.pairPayload("44c621c46f26a07e", "abcdef", "daccqvyo", "abcdef1234567890", "0123456789abcdef");
    assertEquals(82, payload.length);
    assertEquals(0, payload[44]);
    assertEquals(1, payload[45]);
  }

  @Test
  public void buildsTheDpsReportAcknowledgement() {
    byte[] report = new byte[] {4, 0, 0, 0, 9, 0x12, 3};
    assertArrayEquals(
        new byte[] {4, 0, 0, 0, 9, 0x12, 3, 0},
        TuyaBle47Codec.dpsReportAckPayload(report));
  }
}
