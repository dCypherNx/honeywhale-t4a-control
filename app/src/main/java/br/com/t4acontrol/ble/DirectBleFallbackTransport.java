package br.com.t4acontrol.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import br.com.t4acontrol.backend.T4AContracts;
import br.com.t4acontrol.backend.T4ATransport;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Experimental native-BLE transport boundary with an operational Tuya fallback.
 *
 * <p>The first connection attempt for a device performs a read-only Android GATT probe: connect,
 * discover services/characteristics/descriptors, write the topology to the application's raw log
 * and close the native GATT. No characteristic is read, written or subscribed. Whether the probe
 * succeeds or fails, the normal runtime session is then handed to the fallback transport.
 *
 * <p>This deliberately keeps commands and telemetry on the proven Tuya path while giving us the
 * exact native GATT surface of an already provisioned T4A. It can be removed from the composition
 * root without changing backend business logic.
 */
public final class DirectBleFallbackTransport implements T4ATransport {
  private static final long PROBE_TIMEOUT_MS = 4000L;

  private final Context context;
  private final T4ATransport fallback;
  private final Consumer<String> rawLog;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Object lock = new Object();

  private BluetoothGatt probeGatt;
  private T4AContracts.Device pendingDevice;
  private String probedDeviceId = "";
  private boolean probeFinished;

  public DirectBleFallbackTransport(
      Context context, T4ATransport fallback, Consumer<String> rawLog) {
    this.context = context.getApplicationContext();
    this.fallback = fallback;
    this.rawLog = rawLog == null ? ignored -> {} : rawLog;
  }

  @Override
  public void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener) {
    fallback.attach(device, listener);
  }

  @Override
  public void detach() {
    finishProbe(false, "detach");
    fallback.detach();
  }

  @Override
  public void connect(T4AContracts.Device device) {
    if (device == null || device.mac == null || device.mac.trim().isEmpty()) {
      raw("PROBE_SKIPPED reason=missing_mac fallback=true");
      fallback.connect(device);
      return;
    }

    synchronized (lock) {
      if (device.id.equals(probedDeviceId)) {
        fallback.connect(device);
        return;
      }
      if (probeGatt != null || pendingDevice != null) {
        raw("PROBE_SKIPPED reason=already_active fallback=true");
        fallback.connect(device);
        return;
      }
      pendingDevice = device;
      probeFinished = false;
    }

    raw(
        "PROBE_START deviceId="
            + device.id
            + " mac="
            + maskMac(device.mac)
            + " uuid="
            + device.uuid);

    try {
      BluetoothManager manager = context.getSystemService(BluetoothManager.class);
      BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
      if (adapter == null || !adapter.isEnabled()) {
        finishProbe(true, "bluetooth_unavailable");
        return;
      }

      BluetoothDevice bluetoothDevice = adapter.getRemoteDevice(device.mac);
      BluetoothGatt gatt =
          bluetoothDevice.connectGatt(context, false, probeCallback, BluetoothDevice.TRANSPORT_LE);
      synchronized (lock) {
        probeGatt = gatt;
      }
      handler.postDelayed(probeTimeout, PROBE_TIMEOUT_MS);
    } catch (IllegalArgumentException | SecurityException error) {
      raw("PROBE_ERROR stage=start error=" + error.getClass().getSimpleName());
      finishProbe(true, error.getClass().getSimpleName());
    }
  }

  @Override
  public boolean isConnected(String deviceId) {
    return fallback.isConnected(deviceId);
  }

  @Override
  public T4AContracts.Device cachedDevice(String deviceId) {
    return fallback.cachedDevice(deviceId);
  }

  @Override
  public void publish(
      String deviceId, Map<String, Object> dps, T4AContracts.ResultCallback callback) {
    fallback.publish(deviceId, dps, callback);
  }

  @Override
  public void readRssi(String mac, T4AContracts.RssiCallback callback) {
    fallback.readRssi(mac, callback);
  }

  @Override
  public void destroy() {
    finishProbe(false, "destroy");
    fallback.destroy();
  }

  private final Runnable probeTimeout = () -> finishProbe(true, "timeout");

  private final BluetoothGattCallback probeCallback =
      new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
          raw(
              "CONNECTION_STATE status="
                  + status
                  + " state="
                  + newState
                  + " mac="
                  + maskMac(gatt.getDevice().getAddress()));

          if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            boolean started;
            try {
              started = gatt.discoverServices();
            } catch (SecurityException error) {
              raw("PROBE_ERROR stage=discover error=SecurityException");
              finishProbe(true, "discover_security_exception");
              return;
            }
            raw("SERVICE_DISCOVERY_REQUESTED started=" + started);
            if (!started) {
              finishProbe(true, "discover_services_false");
            }
            return;
          }

          if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            finishProbe(true, "disconnected_status_" + status);
          }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
          if (status != BluetoothGatt.GATT_SUCCESS) {
            raw("PROBE_ERROR stage=services status=" + status);
            finishProbe(true, "service_discovery_status_" + status);
            return;
          }

          int serviceCount = 0;
          int characteristicCount = 0;
          int descriptorCount = 0;
          for (BluetoothGattService service : gatt.getServices()) {
            serviceCount++;
            raw(
                "SERVICE uuid="
                    + service.getUuid()
                    + " type="
                    + service.getType()
                    + " instanceId="
                    + service.getInstanceId());
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
              characteristicCount++;
              raw(
                  "CHAR service="
                      + service.getUuid()
                      + " uuid="
                      + characteristic.getUuid()
                      + " properties=0x"
                      + Integer.toHexString(characteristic.getProperties())
                      + " permissions=0x"
                      + Integer.toHexString(characteristic.getPermissions())
                      + " instanceId="
                      + characteristic.getInstanceId());
              for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
                descriptorCount++;
                raw(
                    "DESC characteristic="
                        + characteristic.getUuid()
                        + " uuid="
                        + descriptor.getUuid()
                        + " permissions=0x"
                        + Integer.toHexString(descriptor.getPermissions()));
              }
            }
          }

          raw(
              "TOPOLOGY_COMPLETE services="
                  + serviceCount
                  + " characteristics="
                  + characteristicCount
                  + " descriptors="
                  + descriptorCount);
          finishProbe(true, "topology_captured");
        }
      };

  /** Closes the probe and optionally continues the requested connection through Tuya. */
  private void finishProbe(boolean continueWithFallback, String reason) {
    final BluetoothGatt gatt;
    final T4AContracts.Device device;
    synchronized (lock) {
      if (probeFinished && probeGatt == null && pendingDevice == null) {
        return;
      }
      probeFinished = true;
      handler.removeCallbacks(probeTimeout);
      gatt = probeGatt;
      device = pendingDevice;
      probeGatt = null;
      pendingDevice = null;
      if (device != null) {
        probedDeviceId = device.id;
      }
    }

    if (gatt != null) {
      try {
        gatt.close();
      } catch (RuntimeException error) {
        raw("PROBE_ERROR stage=close error=" + error.getClass().getSimpleName());
      }
    }

    raw("PROBE_FINISH reason=" + reason + " fallback=" + continueWithFallback);
    if (continueWithFallback && device != null) {
      fallback.connect(device);
    }
  }

  private void raw(String message) {
    rawLog.accept("[BLE/DIRECT] " + message);
  }

  private static String maskMac(String mac) {
    if (mac == null || mac.length() < 5) {
      return "<unknown>";
    }
    return "**:**:**:**:" + mac.substring(mac.length() - 5);
  }
}
