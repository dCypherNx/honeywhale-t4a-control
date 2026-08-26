package br.com.t4acontrol.backend;

import java.util.Map;

/** Runtime session boundary for BLE connectivity, commands and telemetry. */
public interface T4ATransport {
  void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener);

  void detach();

  void connect(T4AContracts.Device device);

  boolean isConnected(String deviceId);

  T4AContracts.Device cachedDevice(String deviceId);

  void publish(
      String deviceId, Map<String, Object> dps, T4AContracts.ResultCallback callback);

  void readRssi(String mac, T4AContracts.RssiCallback callback);

  void destroy();
}
