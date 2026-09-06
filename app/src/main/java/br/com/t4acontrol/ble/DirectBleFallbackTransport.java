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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Experimental native-BLE probe with the operational Tuya transport kept as fallback. */
public final class DirectBleFallbackTransport implements T4ATransport {
  private static final long PROBE_TIMEOUT_MS = 6500L;
  private static final long NOTIFY_OBSERVATION_MS = 1500L;

  private static final UUID TUYA_SERVICE =
      UUID.fromString("0000fd50-0000-1000-8000-00805f9b34fb");
  private static final UUID TUYA_NOTIFY =
      UUID.fromString("00000002-0000-1001-8001-00805f9b07d0");
  private static final UUID TUYA_READ =
      UUID.fromString("00000003-0000-1001-8001-00805f9b07d0");
  private static final UUID CCCD =
      UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

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
            + device.uuid
            + " localKeyAvailable="
            + (device.localKey != null && !device.localKey.isEmpty()));

    try {
      BluetoothManager manager = context.getSystemService(BluetoothManager.class);
      BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
      if (adapter == null || !adapter.isEnabled()) {
        finishProbe(true, "bluetooth_unavailable");
        return;
      }

      String normalizedMac = normalizeMac(device.mac);
      raw("ADDRESS normalized=" + !normalizedMac.equals(device.mac) + " mac=" + maskMac(normalizedMac));
      BluetoothDevice bluetoothDevice = adapter.getRemoteDevice(normalizedMac);
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
  private final Runnable finishAfterObservation =
      () -> finishProbe(true, "notify_read_observation_complete");

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
            if (!started) finishProbe(true, "discover_services_false");
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

          logTopology(gatt);
          BluetoothGattService service = gatt.getService(TUYA_SERVICE);
          if (service == null) {
            raw("PROBE_ERROR stage=protocol reason=tuya_service_missing");
            finishProbe(true, "tuya_service_missing");
            return;
          }

          BluetoothGattCharacteristic notifyCharacteristic = service.getCharacteristic(TUYA_NOTIFY);
          BluetoothGattCharacteristic readCharacteristic = service.getCharacteristic(TUYA_READ);
          if (notifyCharacteristic == null || readCharacteristic == null) {
            raw("PROBE_ERROR stage=protocol reason=expected_characteristic_missing");
            finishProbe(true, "expected_characteristic_missing");
            return;
          }

          try {
            boolean localNotify = gatt.setCharacteristicNotification(notifyCharacteristic, true);
            raw("NOTIFY_LOCAL_ENABLED started=" + localNotify + " uuid=" + TUYA_NOTIFY);
            if (!localNotify) {
              finishProbe(true, "notify_local_enable_false");
              return;
            }

            BluetoothGattDescriptor cccd = notifyCharacteristic.getDescriptor(CCCD);
            if (cccd == null) {
              raw("PROBE_ERROR stage=notify reason=cccd_missing");
              finishProbe(true, "notify_cccd_missing");
              return;
            }

            int writeStatus = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            raw("NOTIFY_CCCD_WRITE_REQUEST status=" + writeStatus + " uuid=" + CCCD);
            if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
              finishProbe(true, "notify_cccd_write_request_" + writeStatus);
            }
          } catch (SecurityException error) {
            raw("PROBE_ERROR stage=notify error=SecurityException");
            finishProbe(true, "notify_security_exception");
          }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
          raw("NOTIFY_CCCD_WRITE_RESULT status=" + status + " uuid=" + descriptor.getUuid());
          if (status != BluetoothGatt.GATT_SUCCESS) {
            finishProbe(true, "notify_cccd_write_status_" + status);
            return;
          }

          BluetoothGattService service = gatt.getService(TUYA_SERVICE);
          BluetoothGattCharacteristic readCharacteristic =
              service == null ? null : service.getCharacteristic(TUYA_READ);
          if (readCharacteristic == null) {
            finishProbe(true, "read_characteristic_missing");
            return;
          }

          try {
            boolean started = gatt.readCharacteristic(readCharacteristic);
            raw("READ_REQUEST started=" + started + " uuid=" + TUYA_READ);
            if (!started) finishProbe(true, "read_request_false");
          } catch (SecurityException error) {
            raw("PROBE_ERROR stage=read error=SecurityException");
            finishProbe(true, "read_security_exception");
          }
        }

        @Override
        public void onCharacteristicRead(
            BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
          raw(
              "READ_RESULT status="
                  + status
                  + " uuid="
                  + characteristic.getUuid()
                  + " length="
                  + (value == null ? 0 : value.length)
                  + " hex="
                  + toHex(value));
          handler.removeCallbacks(finishAfterObservation);
          handler.postDelayed(finishAfterObservation, NOTIFY_OBSERVATION_MS);
        }

        @Override
        public void onCharacteristicChanged(
            BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
          raw(
              "NOTIFY_RX uuid="
                  + characteristic.getUuid()
                  + " length="
                  + (value == null ? 0 : value.length)
                  + " hex="
                  + toHex(value));
        }
      };

  private void logTopology(BluetoothGatt gatt) {
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
  }

  /** Closes the probe and optionally continues the requested connection through Tuya. */
  private void finishProbe(boolean continueWithFallback, String reason) {
    final BluetoothGatt gatt;
    final T4AContracts.Device device;
    synchronized (lock) {
      if (probeFinished && probeGatt == null && pendingDevice == null) return;
      probeFinished = true;
      handler.removeCallbacks(probeTimeout);
      handler.removeCallbacks(finishAfterObservation);
      gatt = probeGatt;
      device = pendingDevice;
      probeGatt = null;
      pendingDevice = null;
      if (device != null) probedDeviceId = device.id;
    }

    if (gatt != null) {
      try {
        gatt.close();
      } catch (RuntimeException error) {
        raw("PROBE_ERROR stage=close error=" + error.getClass().getSimpleName());
      }
    }

    raw("PROBE_FINISH reason=" + reason + " fallback=" + continueWithFallback);
    if (continueWithFallback && device != null) fallback.connect(device);
  }

  private void raw(String message) {
    rawLog.accept("[BLE/DIRECT] " + message);
  }

  private static String normalizeMac(String mac) {
    if (mac == null) return "";
    String value = mac.trim().toUpperCase(Locale.ROOT);
    if (BluetoothAdapter.checkBluetoothAddress(value)) return value;
    String compact = value.replace(":", "").replace("-", "");
    if (compact.length() != 12) return value;
    StringBuilder normalized = new StringBuilder(17);
    for (int index = 0; index < compact.length(); index += 2) {
      if (normalized.length() > 0) normalized.append(':');
      normalized.append(compact, index, index + 2);
    }
    return normalized.toString();
  }

  private static String maskMac(String mac) {
    if (mac == null || mac.length() < 5) return "<unknown>";
    return "**:**:**:**:" + mac.substring(mac.length() - 5);
  }

  private static String toHex(byte[] value) {
    if (value == null || value.length == 0) return "";
    StringBuilder hex = new StringBuilder(value.length * 2);
    for (byte item : value) hex.append(String.format(Locale.ROOT, "%02X", item & 0xFF));
    return hex.toString();
  }
}
