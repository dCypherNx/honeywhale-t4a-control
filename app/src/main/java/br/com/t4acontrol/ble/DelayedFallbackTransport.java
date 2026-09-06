package br.com.t4acontrol.ble;

import android.os.Handler;
import android.os.Looper;
import br.com.t4acontrol.backend.T4AContracts;
import br.com.t4acontrol.backend.T4ATransport;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Experimental wrapper that deliberately delays Tuya runtime fallback so native BLE probes can
 * complete and remain isolated from provider traffic. Only connect() is delayed; all other runtime
 * operations stay delegated unchanged.
 */
public final class DelayedFallbackTransport implements T4ATransport {
  private static final long FALLBACK_DELAY_MS = 10_000L;

  private final T4ATransport delegate;
  private final Consumer<String> rawLog;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Runnable delayedConnect;
  private T4AContracts.Device pendingDevice;
  private boolean destroyed;

  public DelayedFallbackTransport(T4ATransport delegate, Consumer<String> rawLog) {
    this.delegate = delegate;
    this.rawLog = rawLog == null ? ignored -> {} : rawLog;
    this.delayedConnect =
        () -> {
          T4AContracts.Device device = pendingDevice;
          pendingDevice = null;
          if (destroyed || device == null) return;
          if (this.delegate.isConnected(device.id)) {
            this.rawLog.accept(
                "[BLE/DIRECT] FALLBACK_SKIPPED reason=already_connected provider=tuya deviceId="
                    + device.id);
            return;
          }
          this.rawLog.accept(
              "[BLE/DIRECT] FALLBACK_EXECUTE provider=tuya deviceId=" + device.id);
          this.delegate.connect(device);
        };
  }

  @Override
  public void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener) {
    delegate.attach(device, listener);
  }

  @Override
  public void detach() {
    cancelPending("detach");
    delegate.detach();
  }

  @Override
  public void connect(T4AContracts.Device device) {
    if (destroyed || device == null) {
      if (!destroyed) delegate.connect(device);
      return;
    }
    if (delegate.isConnected(device.id)) {
      cancelPending("already_connected");
      rawLog.accept(
          "[BLE/DIRECT] FALLBACK_SKIPPED reason=already_connected provider=tuya deviceId="
              + device.id);
      return;
    }
    pendingDevice = device;
    handler.removeCallbacks(delayedConnect);
    rawLog.accept(
        "[BLE/DIRECT] FALLBACK_SCHEDULED delayMs="
            + FALLBACK_DELAY_MS
            + " deviceId="
            + device.id
            + " provider=tuya");
    handler.postDelayed(delayedConnect, FALLBACK_DELAY_MS);
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
    destroyed = true;
    cancelPending("destroy");
    delegate.destroy();
  }

  private void cancelPending(String reason) {
    if (pendingDevice == null) return;
    handler.removeCallbacks(delayedConnect);
    pendingDevice = null;
    rawLog.accept("[BLE/DIRECT] FALLBACK_CANCELLED reason=" + reason);
  }
}
