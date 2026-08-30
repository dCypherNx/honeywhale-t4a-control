package br.com.t4acontrol.session;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import java.util.Collections;
import java.util.Locale;

/**
 * Experimental Android-native RSSI sampler.
 *
 * <p>The scanner is constrained at the platform level with an exact MAC-address ScanFilter and
 * defensively rejects any callback whose address does not match the configured T4A MAC. It is
 * diagnostic only and never participates in the automatic-lock decision.
 */
final class AndroidBleRssiMonitor implements AutoCloseable {
  interface Listener {
    void onRssi(String mac, Integer rssi, String status);
  }

  private static final long SCAN_WINDOW_MS = 1500L;
  private static final long SAMPLE_INTERVAL_MS = 5000L;

  private final Context context;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Listener listener;

  private String targetMac = "";
  private boolean active;
  private BluetoothLeScanner scanner;
  private ScanCallback callback;
  private Runnable timeout;
  private Integer lastRssi;
  private String lastStatus = "";

  AndroidBleRssiMonitor(Context context, Listener listener) {
    this.context = context.getApplicationContext();
    this.listener = listener;
  }

  void setTarget(String mac, boolean enabled) {
    String normalized = normalize(mac);
    boolean valid = !normalized.isEmpty() && BluetoothAdapter.checkBluetoothAddress(normalized);
    boolean nextActive = enabled && valid;
    if (targetMac.equals(normalized) && active == nextActive) return;

    stopScan();
    handler.removeCallbacks(sample);
    targetMac = normalized;
    active = nextActive;
    lastRssi = null;
    lastStatus = "";

    if (active) handler.post(sample);
  }

  private final Runnable sample =
      new Runnable() {
        @Override
        public void run() {
          scanOnce();
        }
      };

  private void scanOnce() {
    if (!active || targetMac.isEmpty()) return;
    if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
        != PackageManager.PERMISSION_GRANTED) {
      reportUnavailable("permission");
      scheduleNext();
      return;
    }

    BluetoothManager manager = context.getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
    if (scanner == null) {
      reportUnavailable("scanner");
      scheduleNext();
      return;
    }

    final String expectedMac = targetMac;
    ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(expectedMac).build();
    ScanSettings settings =
        new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

    callback =
        new ScanCallback() {
          @Override
          public void onScanResult(int callbackType, ScanResult result) {
            if (!active || result == null || result.getDevice() == null) return;
            String address = normalize(result.getDevice().getAddress());
            if (!expectedMac.equals(address)) return;
            int value = result.getRssi();
            finishScan();
            reportRssi(value);
            scheduleNext();
          }

          @Override
          public void onScanFailed(int errorCode) {
            finishScan();
            reportUnavailable("scan_error_" + errorCode);
            scheduleNext();
          }
        };

    try {
      scanner.startScan(Collections.singletonList(filter), settings, callback);
    } catch (RuntimeException | SecurityException error) {
      finishScan();
      reportUnavailable(error.getClass().getSimpleName());
      scheduleNext();
      return;
    }

    timeout =
        () -> {
          if (!active || !expectedMac.equals(targetMac)) return;
          finishScan();
          reportUnavailable("timeout");
          scheduleNext();
        };
    handler.postDelayed(timeout, SCAN_WINDOW_MS);
  }

  private void reportRssi(int value) {
    if (lastRssi != null && lastRssi == value && "ok".equals(lastStatus)) return;
    lastRssi = value;
    lastStatus = "ok";
    listener.onRssi(targetMac, value, "ok");
  }

  private void reportUnavailable(String reason) {
    String status = "unavailable:" + reason;
    if (lastRssi == null && status.equals(lastStatus)) return;
    lastRssi = null;
    lastStatus = status;
    listener.onRssi(targetMac, null, status);
  }

  private void scheduleNext() {
    handler.removeCallbacks(sample);
    if (active) handler.postDelayed(sample, SAMPLE_INTERVAL_MS);
  }

  private void finishScan() {
    if (timeout != null) {
      handler.removeCallbacks(timeout);
      timeout = null;
    }
    if (scanner != null && callback != null) {
      try {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
            == PackageManager.PERMISSION_GRANTED) {
          scanner.stopScan(callback);
        }
      } catch (RuntimeException | SecurityException ignored) {
        // Diagnostic reader must never disturb the primary SDK session.
      }
    }
    callback = null;
    scanner = null;
  }

  private void stopScan() {
    finishScan();
  }

  private static String normalize(String mac) {
    return mac == null ? "" : mac.trim().toUpperCase(Locale.ROOT);
  }

  @Override
  public void close() {
    active = false;
    handler.removeCallbacksAndMessages(null);
    stopScan();
  }
}
