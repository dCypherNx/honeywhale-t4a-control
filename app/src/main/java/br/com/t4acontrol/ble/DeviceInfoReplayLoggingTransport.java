package br.com.t4acontrol.ble;

import br.com.t4acontrol.backend.T4AContracts;
import br.com.t4acontrol.backend.T4ATransport;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Experimental-only transport decorator that emits a replayable encrypted DEVICE_INFO packet.
 *
 * <p>The log deliberately contains only final BLE packet bytes. Credential material and derived
 * keys are never logged. A fixed IV makes captures reproducible while remaining valid for the
 * protocol because the IV is carried in the encrypted envelope itself.
 */
public final class DeviceInfoReplayLoggingTransport implements T4ATransport {
  private static final int REPLAY_MTU = 247;
  private static final byte[] REPLAY_IV = new byte[] {
      0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
      0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F
  };

  private final T4ATransport delegate;
  private final Consumer<String> rawLog;

  public DeviceInfoReplayLoggingTransport(T4ATransport delegate, Consumer<String> rawLog) {
    this.delegate = delegate;
    this.rawLog = rawLog == null ? ignored -> {} : rawLog;
  }

  @Override
  public void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener) {
    delegate.attach(device, listener);
  }

  @Override
  public void detach() {
    delegate.detach();
  }

  @Override
  public void connect(T4AContracts.Device device) {
    logDeviceInfoReplay(device);
    delegate.connect(device);
  }

  @Override
  public boolean isConnected(String deviceId) {
    return delegate.isConnected(deviceId);
  }

  @Override
  public T4AContracts.Device cachedDevice(String deviceId) {
    return delegate.cachedDevice(deviceId);
  }

  @Override
  public void publish(
      String deviceId, Map<String, Object> dps, T4AContracts.ResultCallback callback) {
    delegate.publish(deviceId, dps, callback);
  }

  @Override
  public void readRssi(String mac, T4AContracts.RssiCallback callback) {
    delegate.readRssi(mac, callback);
  }

  @Override
  public void destroy() {
    delegate.destroy();
  }

  private void logDeviceInfoReplay(T4AContracts.Device device) {
    if (device == null
        || device.localKey == null
        || device.localKey.isEmpty()
        || device.securityKey == null
        || device.securityKey.length() != 16) {
      rawLog.accept("ESP32_REPLAY DEVICE_INFO unavailable=missing_credentials");
      return;
    }
    try {
      byte[] key14 = TuyaBle47Codec.deriveKey14(device.localKey, device.securityKey);
      byte[] envelope = TuyaBle47Codec.encode(
          1,
          0,
          TuyaBle47Codec.DEVICE_INFO,
          TuyaBle47Codec.deviceInfoPayload(REPLAY_MTU),
          14,
          key14,
          REPLAY_IV);
      List<byte[]> packets = TuyaBle47Codec.fragment(envelope, REPLAY_MTU);
      rawLog.accept(
          "ESP32_REPLAY DEVICE_INFO packets=" + packets.size()
              + " mtu=" + REPLAY_MTU
              + " selector=14 writeType=NO_RESPONSE");
      for (int index = 0; index < packets.size(); index++) {
        byte[] packet = packets.get(index);
        rawLog.accept(
            "ESP32_REPLAY DEVICE_INFO packet=" + index
                + " len=" + packet.length
                + " hex=" + hex(packet));
      }
    } catch (GeneralSecurityException | RuntimeException error) {
      rawLog.accept(
          "ESP32_REPLAY DEVICE_INFO unavailable=" + error.getClass().getSimpleName());
    }
  }

  private static String hex(byte[] data) {
    StringBuilder out = new StringBuilder(data.length * 2);
    for (byte item : data) {
      out.append(String.format(Locale.ROOT, "%02X", item & 0xFF));
    }
    return out.toString();
  }
}
