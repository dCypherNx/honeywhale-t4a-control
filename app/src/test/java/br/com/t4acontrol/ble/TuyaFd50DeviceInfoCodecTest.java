package br.com.t4acontrol.ble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.Locale;
import org.junit.Test;

public class TuyaFd50DeviceInfoCodecTest {
  @Test
  public void buildsKnownFd50DeviceInfoFrame() throws Exception {
    byte[] iv = new byte[16];
    for (int index = 0; index < iv.length; index++) iv[index] = (byte) index;

    byte[] frame = TuyaFd50DeviceInfoCodec.buildDeviceInfoFrame("abcdef123456", 1, iv);

    assertEquals(36, frame.length);
    assertEquals(
        "00212004000102030405060708090A0B0C0D0E0FCD5F67BAF3058CEB949EC2BBA629F9CD",
        toHex(frame));
  }

  @Test
  public void derivesExpectedLoginKeyFromFirstSixLocalKeyCharacters() throws Exception {
    TuyaFd50DeviceInfoCodec.SessionMaterial material =
        TuyaFd50DeviceInfoCodec.sessionMaterial("abcdef123456");

    assertArrayEquals(new byte[] {'a', 'b', 'c', 'd', 'e', 'f'}, material.localKey6);
    assertEquals("E80B5017098950FC58AAD83C8C14978E", toHex(material.loginKey));
  }

  private static String toHex(byte[] value) {
    StringBuilder result = new StringBuilder(value.length * 2);
    for (byte item : value) result.append(String.format(Locale.ROOT, "%02X", item & 0xFF));
    return result.toString();
  }
}