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
import android.util.Log;
import br.com.t4acontrol.backend.T4AContracts;
import br.com.t4acontrol.backend.T4ATransport;
import java.util.Map;

/**
 * Experimental native-BLE transport boundary with an operational Tuya fallback.
 *
 * <p>The first connection attempt for a device performs a read-only Android GATT probe: connect,
 * discover services/characteristics/descriptors, log the topology and close the native GATT. No
 * characteristic is read, written or subscribed. Whether the probe succeeds or fails, the normal
 * runtime session is then handed to the fallback transport.
 *
 * <p>This deliberately keeps commands and telemetry on the proven Tuya path while giving us the
 * exact native GATT surface of an already provisioned T4A. It can be removed from the composition
 * root without changing backend business logic.
 */
public final class DirectBleFallbackTransport implements T4ATransport {
  private static final String TAG = "T4A.DirectBLE";
  private static final long PROBE_TIMEOUT_MS = 4000L;

  private final Context context;
  private final T4ATransport fallback;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Object lock = new Object();

  private BluetoothGatt probeGatt;
  private T4AContracts.Device pendingDevice;
  private String probedDeviceId = "";
  private boolean probeFinished;

  public DirectBleFallbackTransport(Context context, T4ATransport fallback) {
    this.context = context.getApplicationContext();
    this.fallback = fallback;
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
      Log.w(TAG, "probe skipped: device MAC is unavailable; using Tuya fallback");
      fallback.connect(device);
      return;
    }

    synchronized (lock) {
      if (device.id.equals(probedDeviceId)) {
        fallback.connect(device);
        return;
      }
      if (probeGatt != null || pendingDevice != null) {
        Log.w(TAG, "probe already active; using Tuya fallback for concurrent connect");
        fallback.connect(device);
        return;
      }
      pendingDevice = device;
      probeFinished = false;
    }

    Log.i(
        TAG,
        "probe start deviceId=" + device.id + " mac=" + maskMac(device.mac) + " uuid=" + device.uuid);

    try {
      BluetoothManager manager = context.getSystemService(BluetoothManager.class);
      BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
      if (adapter == null || !adapter.isEnabled()) {
        finishProbe(true, "Bluetooth unavailable or disabled");
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
      Log.w(TAG, "probe could not start", error);
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
          Log.i(
              TAG,
              "connection state status=" + status + " state=" + newState + " mac="
                  + maskMac(gatt.getDevice().getAddress()));

          if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            boolean started;
            try {
              started = gatt.discoverServices();
            } catch (SecurityException error) {
              Log.w(TAG, "service discovery permission failure", error);
              finishProbe(true, "discover SecurityException");
              return;
            }
            Log.i(TAG, "service discovery requested=" + started);
            if (!started) {
              finishProbe(true, "discoverServices returned false");
            }
            return;
          }

          if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            finishProbe(true, "disconnected status=" + status);
          }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
          if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "service discovery failed status=" + status);
            finishProbe(true, "service discovery status=" + status);
            return;
          }

          int serviceCount = 0;
          int characteristicCount = 0;
          int descriptorCount = 0;
          for (BluetoothGattService service : gatt.getServices()) {
            serviceCount++;
            Log.i(
                TAG,
                "SERVICE uuid=" + service.getUuid() + " type=" + service.getType()
                    + " instanceId=" + service.getInstanceId());
            for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
              characteristicCount++;
              Log.i(
                  TAG,
                  "  CHAR uuid=" + characteristic.getUuid()
                      + " properties=0x" + Integer.toHexString(characteristic.getProperties())
                      + " permissions=0x" + Integer.toHexString(characteristic.getPermissions())
                      + " instanceId=" + characteristic.getInstanceId());
              for (BluetoothGattDescriptor descriptor : characteristic.getDescriptors()) {
                descriptorCount++;
                Log.i(
                    TAG,
                    "    DESC uuid=" + descriptor.getUuid()
                        + " permissions=0x" + Integer.toHexString(descriptor.getPermissions()));
              }
            }
          }

          Log.i(
              TAG,
              "probe topology complete services=" + serviceCount
                  + " characteristics=" + characteristicCount
                  + " descriptors=" + descriptorCount);
          finishProbe(true, "topology captured");
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
        Log.w(TAG, "error closing probe GATT", error);
      }
    }

    Log.i(TAG, "probe finish reason=" + reason + " fallback=" + continueWithFallback);
    if (continueWithFallback && device != null) {
      fallback.connect(device);
    }
  }

  private static String maskMac(String mac) {
    if (mac == null || mac.length() < 5) {
      return "<unknown>";
    }
    return "**:**:**:**:" + mac.substring(mac.length() - 5);
  }
}
