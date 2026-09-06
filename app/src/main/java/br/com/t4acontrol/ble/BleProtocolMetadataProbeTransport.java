package br.com.t4acontrol.ble;

import android.os.SystemClock;
import br.com.t4acontrol.backend.T4AContracts;
import br.com.t4acontrol.backend.T4ATransport;
import java.util.Map;
import java.util.function.Consumer;

/** Selects the current non-actuating native BLE bootstrap experiment. */
public final class BleProtocolMetadataProbeTransport implements T4ATransport {
  private static final long DIRECT_PROBE_SUPPRESSION_MS = 12_000L;

  private final T4ATransport delegate;
  private final Consumer<String> rawLog;
  private String probingDeviceId = "";
  private long suppressReconnectUntilMs;

  public BleProtocolMetadataProbeTransport(T4ATransport delegate, Consumer<String> rawLog) {
    this.delegate = delegate;
    this.rawLog = rawLog == null ? ignored -> {} : rawLog;
  }

  @Override public void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener) { delegate.attach(device, listener); }

  @Override public void detach() {
    probingDeviceId = "";
    suppressReconnectUntilMs = 0L;
    delegate.detach();
  }

  @Override public void connect(T4AContracts.Device device) {
    if (device == null) { delegate.connect(null); return; }

    long now = SystemClock.elapsedRealtime();
    if (device.id.equals(probingDeviceId) && now < suppressReconnectUntilMs) {
      rawLog.accept("[BLE/DIRECT] PROTOCOL_RECONNECT_SUPPRESSED reason=direct_probe_active remainingMs="
          + (suppressReconnectUntilMs - now)
          + " fallbackDeferred=true");
      return;
    }

    probingDeviceId = device.id;
    suppressReconnectUntilMs = now + DIRECT_PROBE_SUPPRESSION_MS;

    boolean securityKeyAvailable = device.securityKey != null && !device.securityKey.isEmpty();
    boolean rawSecurityKeyCandidate = securityKeyAvailable && device.securityKey.getBytes(java.nio.charset.StandardCharsets.UTF_8).length == 16;
    rawLog.accept("[BLE/DIRECT] PROTOCOL_METADATA productId=" + display(device.productId)
        + " uuid=" + device.uuid
        + " localKeyAvailable=" + (device.localKey != null && !device.localKey.isEmpty())
        + " securityKeyAvailable=" + securityKeyAvailable
        + " bootstrapKey=" + (rawSecurityKeyCandidate ? "secKey_raw16" : "none")
        + " activeWriteProbe=" + rawSecurityKeyCandidate
        + " classicFd50Bootstrap=disproved");
    rawLog.accept("[BLE/DIRECT] PROTOCOL_CAPABILITIES " + formatMetadata(device.protocolMetadata)
        + " secretsLogged=false");

    // f147 disproved classic first-six/MD5 derivation with secKey. The next isolated experiment
    // keeps the same non-actuating DEVICE_INFO envelope but uses the 16-byte secKey directly as
    // AES material. No PAIR or DPS is emitted.
    String bootstrapKey = rawSecurityKeyCandidate ? device.securityKey : "";
    T4AContracts.Device probeDevice = new T4AContracts.Device(
        device.id, device.name, device.mac, device.uuid, device.productId, bootstrapKey,
        device.securityKey, device.protocolMetadata, device.dps, device.schema);
    delegate.connect(probeDevice);
  }

  @Override public boolean isConnected(String deviceId) { return delegate.isConnected(deviceId); }
  @Override public T4AContracts.Device cachedDevice(String deviceId) { return delegate.cachedDevice(deviceId); }
  @Override public void publish(String deviceId, Map<String,Object> dps, T4AContracts.ResultCallback callback) { delegate.publish(deviceId, dps, callback); }
  @Override public void readRssi(String mac, T4AContracts.RssiCallback callback) { delegate.readRssi(mac, callback); }

  @Override public void destroy() {
    probingDeviceId = "";
    suppressReconnectUntilMs = 0L;
    delegate.destroy();
  }

  private static String display(String value) { return value == null || value.isBlank() ? "<unknown>" : value; }
  private static String formatMetadata(Map<String,String> metadata) {
    if (metadata == null || metadata.isEmpty()) return "available=false";
    StringBuilder out = new StringBuilder("available=true");
    for (Map.Entry<String,String> entry : metadata.entrySet()) out.append(' ').append(entry.getKey()).append('=').append(display(entry.getValue()));
    return out.toString();
  }
}
