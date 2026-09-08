package br.com.t4acontrol.ble;

import br.com.t4acontrol.backend.T4AContracts;
import br.com.t4acontrol.backend.T4ATransport;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Neutral null-object used when the production runtime must remain native-BLE-only.
 *
 * <p>This transport never establishes a session. It exists only to satisfy the native transport's
 * fallback boundary without reintroducing a provider runtime. Provisioning remains a separate
 * concern behind {@code T4AProvisioner}.
 */
public final class UnavailableRuntimeTransport implements T4ATransport {
  private final Consumer<String> rawLog;
  private T4AContracts.Device device;
  private T4AContracts.DeviceListener listener;

  public UnavailableRuntimeTransport(Consumer<String> rawLog) {
    this.rawLog = rawLog == null ? ignored -> {} : rawLog;
  }

  @Override
  public void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener) {
    this.device = device;
    this.listener = listener;
  }

  @Override
  public void detach() {
    device = null;
    listener = null;
  }

  @Override
  public void connect(T4AContracts.Device device) {
    rawLog.accept("[BLE/NATIVE] RUNTIME_FALLBACK_DISABLED provider=none");
    if (listener != null && device != null) listener.onConnectionChanged(device.id, false);
  }

  @Override
  public void disconnect(T4AContracts.Device device) {
    // Already disconnected by definition.
  }

  @Override
  public boolean isConnected(String deviceId) {
    return false;
  }

  @Override
  public T4AContracts.Device cachedDevice(String deviceId) {
    return device != null && device.id.equals(deviceId) ? device : null;
  }

  @Override
  public void publish(
      String deviceId, Map<String, Object> dps, T4AContracts.ResultCallback callback) {
    if (callback != null) {
      callback.onError("NATIVE_NOT_CONNECTED", "Sessão BLE nativa indisponível");
    }
  }

  @Override
  public void readRssi(String mac, T4AContracts.RssiCallback callback) {
    if (callback != null) callback.onResult(false, 0);
  }

  @Override
  public void destroy() {
    detach();
  }
}
