package br.com.t4acontrol.ble;

import br.com.t4acontrol.backend.T4AContracts;
import br.com.t4acontrol.backend.T4ATransport;
import java.util.Map;
import java.util.function.Consumer;

/** Debug transport decorator that emits the ThingClips protocol map at the first real connect. */
public final class SdkIntrospectionTransport implements T4ATransport {
  private final T4ATransport delegate;
  private final Consumer<String> rawLog;

  public SdkIntrospectionTransport(T4ATransport delegate, Consumer<String> rawLog) {
    this.delegate = delegate;
    this.rawLog = rawLog == null ? ignored -> {} : rawLog;
  }

  @Override public void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener) {
    delegate.attach(device, listener);
  }

  @Override public void detach() { delegate.detach(); }

  @Override public void connect(T4AContracts.Device device) {
    ThingBleProtocolIntrospector.inspect(rawLog);
    delegate.connect(device);
  }

  @Override public boolean isConnected(String deviceId) { return delegate.isConnected(deviceId); }

  @Override public T4AContracts.Device cachedDevice(String deviceId) {
    return delegate.cachedDevice(deviceId);
  }

  @Override public void publish(String deviceId, Map<String, Object> dps,
      T4AContracts.ResultCallback callback) {
    delegate.publish(deviceId, dps, callback);
  }

  @Override public void readRssi(String mac, T4AContracts.RssiCallback callback) {
    delegate.readRssi(mac, callback);
  }

  @Override public void destroy() { delegate.destroy(); }
}
