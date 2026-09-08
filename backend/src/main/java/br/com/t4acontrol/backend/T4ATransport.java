package br.com.t4acontrol.backend;

import java.util.Map;

/** Runtime session boundary for BLE connectivity, commands and telemetry. */
public interface T4ATransport {
  void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener);

  void detach();

  void connect(T4AContracts.Device device);

  /**
   * Explicit user-requested disconnect. Implementations should close the active BLE link while
   * preserving enough runtime context for a later {@link #connect(T4AContracts.Device)} call.
   *
   * <p>The default falls back to {@link #detach()} for transports that do not expose a distinct
   * physical disconnect primitive.
   */
  default void disconnect(T4AContracts.Device device) {
    detach();
  }

  boolean isConnected(String deviceId);

  T4AContracts.Device cachedDevice(String deviceId);

  void publish(
      String deviceId, Map<String, Object> dps, T4AContracts.ResultCallback callback);

  void readRssi(String mac, T4AContracts.RssiCallback callback);

  void destroy();
}
