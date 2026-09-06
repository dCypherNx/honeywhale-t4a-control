package br.com.t4acontrol.ble;

import br.com.t4acontrol.backend.T4AContracts;
import br.com.t4acontrol.backend.T4ATransport;
import java.util.Map;
import java.util.function.Consumer;

/** Temporary passive metadata probe used while identifying the exact Tuya BLE protocol variant. */
public final class BleProtocolMetadataProbeTransport implements T4ATransport {
  private final T4ATransport delegate;
  private final Consumer<String> rawLog;

  public BleProtocolMetadataProbeTransport(T4ATransport delegate, Consumer<String> rawLog) {
    this.delegate = delegate;
    this.rawLog = rawLog == null ? ignored -> {} : rawLog;
  }

  @Override public void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener) { delegate.attach(device, listener); }
  @Override public void detach() { delegate.detach(); }

  @Override public void connect(T4AContracts.Device device) {
    if (device == null) { delegate.connect(null); return; }
    rawLog.accept("[BLE/DIRECT] PROTOCOL_METADATA productId=" + display(device.productId)
        + " uuid=" + device.uuid
        + " localKeyAvailable=" + (device.localKey != null && !device.localKey.isEmpty())
        + " activeWriteProbe=false");
    rawLog.accept("[BLE/DIRECT] PROTOCOL_CAPABILITIES " + formatMetadata(device.protocolMetadata)
        + " secretsLogged=false");

    T4AContracts.Device passiveDevice = new T4AContracts.Device(
        device.id, device.name, device.mac, device.uuid, device.productId, "",
        device.protocolMetadata, device.dps, device.schema);
    delegate.connect(passiveDevice);
  }

  @Override public boolean isConnected(String deviceId) { return delegate.isConnected(deviceId); }
  @Override public T4AContracts.Device cachedDevice(String deviceId) { return delegate.cachedDevice(deviceId); }
  @Override public void publish(String deviceId, Map<String,Object> dps, T4AContracts.ResultCallback callback) { delegate.publish(deviceId, dps, callback); }
  @Override public void readRssi(String mac, T4AContracts.RssiCallback callback) { delegate.readRssi(mac, callback); }
  @Override public void destroy() { delegate.destroy(); }

  private static String display(String value) { return value == null || value.isBlank() ? "<unknown>" : value; }

  private static String formatMetadata(Map<String,String> metadata) {
    if (metadata == null || metadata.isEmpty()) return "available=false";
    StringBuilder out = new StringBuilder("available=true");
    for (Map.Entry<String,String> entry : metadata.entrySet()) {
      out.append(' ').append(entry.getKey()).append('=').append(display(entry.getValue()));
    }
    return out.toString();
  }
}
