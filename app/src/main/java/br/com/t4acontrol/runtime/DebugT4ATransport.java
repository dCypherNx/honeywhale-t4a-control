package br.com.t4acontrol.runtime;

import android.os.SystemClock;
import br.com.t4acontrol.backend.T4ABackend;
import br.com.t4acontrol.backend.T4AContracts;
import br.com.t4acontrol.backend.T4ATransport;
import java.util.Map;

/** Debug-only transport decorator for tracing provider RSSI reads without changing behavior. */
public final class DebugT4ATransport implements T4ATransport {
  private final T4ATransport delegate;
  private final T4ABackend.Listener listener;

  public DebugT4ATransport(T4ATransport delegate, T4ABackend.Listener listener) {
    this.delegate = delegate;
    this.listener = listener;
  }

  @Override
  public void attach(T4AContracts.Device device, T4AContracts.DeviceListener deviceListener) {
    delegate.attach(device, deviceListener);
  }

  @Override
  public void detach() {
    delegate.detach();
  }

  @Override
  public void connect(T4AContracts.Device device) {
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
    long startedAt = SystemClock.elapsedRealtime();
    raw("[SDK] RSSI REQUEST mac=" + mac + " connectedTransport=true");
    try {
      delegate.readRssi(
          mac,
          (success, value) -> {
            long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
            raw(
                "[SDK] RSSI RESULT mac="
                    + mac
                    + " success="
                    + success
                    + " value="
                    + value
                    + " elapsedMs="
                    + elapsedMs);
            callback.onResult(success, value);
          });
    } catch (RuntimeException error) {
      long elapsedMs = SystemClock.elapsedRealtime() - startedAt;
      raw(
          "[SDK] RSSI ERROR mac="
              + mac
              + " type="
              + error.getClass().getSimpleName()
              + " elapsedMs="
              + elapsedMs);
      throw error;
    }
  }

  @Override
  public void destroy() {
    delegate.destroy();
  }

  private void raw(String entry) {
    if (listener != null) listener.onRawLog(entry);
  }
}
