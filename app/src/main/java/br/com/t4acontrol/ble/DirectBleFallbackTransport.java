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
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import br.com.t4acontrol.backend.T4AContracts;
import br.com.t4acontrol.backend.T4ATransport;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Experimental native-BLE probe with the operational Tuya transport kept as fallback. */
public final class DirectBleFallbackTransport implements T4ATransport {
  private static final long PROBE_TIMEOUT_MS = 11000L;
  private static final long ADVERTISEMENT_OBSERVATION_MS = 2500L;
  private static final long MTU_CALLBACK_WAIT_MS = 700L;
  private static final long HANDSHAKE_OBSERVATION_MS = 2200L;
  private static final int REQUESTED_MTU = 247;
  private static final int TUYA_COMPANY_ID = 0x07D0;
  private static final int MAX_ADVERTISEMENT_CANDIDATES = 5;

  private static final UUID TUYA_SERVICE =
      UUID.fromString("0000fd50-0000-1000-8000-00805f9b34fb");
  private static final UUID TUYA_WRITE =
      UUID.fromString("00000001-0000-1001-8001-00805f9b07d0");
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
  private BluetoothLeScanner probeScanner;
  private T4AContracts.Device pendingDevice;
  private String pendingNormalizedMac = "";
  private String probedDeviceId = "";
  private boolean probeFinished;
  private boolean gattStarted;
  private boolean serviceDiscoveryRequested;
  private int advertisementCandidateCount;

  private TuyaFd50DeviceInfoCodec.SessionMaterial handshakeMaterial;
  private final ByteArrayOutputStream notifyBuffer = new ByteArrayOutputStream();
  private int notifyExpectedPacket;
  private int notifyExpectedLength = -1;
  private int notifyProtocolMarker = -1;

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
      pendingNormalizedMac = normalizeMac(device.mac);
      probeFinished = false;
      gattStarted = false;
      serviceDiscoveryRequested = false;
      advertisementCandidateCount = 0;
      handshakeMaterial = null;
      resetNotificationAssembler();
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

      String normalizedMac = pendingNormalizedMac;
      raw("ADDRESS normalized=" + !normalizedMac.equals(device.mac) + " mac=" + maskMac(normalizedMac));
      if (!BluetoothAdapter.checkBluetoothAddress(normalizedMac)) {
        raw("PROBE_ERROR stage=address reason=invalid_mac");
        finishProbe(true, "invalid_mac");
        return;
      }

      handler.postDelayed(probeTimeout, PROBE_TIMEOUT_MS);
      startAdvertisementProbe(adapter);
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

  private void startAdvertisementProbe(BluetoothAdapter adapter) {
    BluetoothLeScanner scanner;
    try {
      scanner = adapter.getBluetoothLeScanner();
    } catch (SecurityException error) {
      raw("ADV_SCAN_ERROR stage=get_scanner error=SecurityException");
      startGattProbe(adapter);
      return;
    }
    if (scanner == null) {
      raw("ADV_SCAN_SKIPPED reason=scanner_unavailable");
      startGattProbe(adapter);
      return;
    }

    synchronized (lock) {
      if (probeFinished) return;
      probeScanner = scanner;
    }

    try {
      scanner.startScan(advertisementCallback);
      raw("ADV_SCAN_START windowMs=" + ADVERTISEMENT_OBSERVATION_MS + " target=fd50_or_known_mac");
      handler.postDelayed(finishAdvertisementObservation, ADVERTISEMENT_OBSERVATION_MS);
    } catch (SecurityException | IllegalStateException error) {
      raw("ADV_SCAN_ERROR stage=start error=" + error.getClass().getSimpleName());
      synchronized (lock) {
        probeScanner = null;
      }
      startGattProbe(adapter);
    }
  }

  private final ScanCallback advertisementCallback =
      new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
          if (result == null || result.getDevice() == null) return;

          String address;
          try {
            address = result.getDevice().getAddress();
          } catch (SecurityException error) {
            raw("ADV_SCAN_ERROR stage=result_address error=SecurityException");
            return;
          }

          ScanRecord record = result.getScanRecord();
          byte[] fd50 = record == null ? null : record.getServiceData(new ParcelUuid(TUYA_SERVICE));
          byte[] manufacturer =
              record == null ? null : record.getManufacturerSpecificData(TUYA_COMPANY_ID);
          boolean serviceUuidPresent = hasServiceUuid(record, TUYA_SERVICE);
          boolean knownAddress =
              address != null && address.equalsIgnoreCase(pendingNormalizedMac);
          boolean tuyaCandidate = serviceUuidPresent || fd50 != null || manufacturer != null;
          if (!knownAddress && !tuyaCandidate) return;

          synchronized (lock) {
            if (probeFinished || advertisementCandidateCount >= MAX_ADVERTISEMENT_CANDIDATES) return;
            advertisementCandidateCount++;
          }

          raw(
              "ADV_CANDIDATE index="
                  + advertisementCandidateCount
                  + " mac="
                  + maskMac(address)
                  + " knownAddress="
                  + knownAddress
                  + " rssi="
                  + result.getRssi()
                  + " connectable="
                  + result.isConnectable()
                  + " fd50Uuid="
                  + serviceUuidPresent);
          raw(
              "ADV_TUYA fd50Length="
                  + lengthOf(fd50)
                  + " fd50="
                  + toHex(fd50)
                  + " mfg07D0Length="
                  + lengthOf(manufacturer)
                  + " mfg07D0="
                  + toHex(manufacturer));
        }

        @Override
        public void onScanFailed(int errorCode) {
          raw("ADV_SCAN_ERROR stage=callback errorCode=" + errorCode);
          finishAdvertisementScanAndConnect("scan_failed_" + errorCode);
        }
      };

  private final Runnable finishAdvertisementObservation =
      () -> finishAdvertisementScanAndConnect("window_complete");

  private void finishAdvertisementScanAndConnect(String reason) {
    handler.removeCallbacks(finishAdvertisementObservation);
    stopAdvertisementScan();
    raw("ADV_SCAN_FINISH reason=" + reason + " candidates=" + advertisementCandidateCount);

    BluetoothManager manager = context.getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    if (adapter == null || !adapter.isEnabled()) {
      finishProbe(true, "bluetooth_unavailable_after_scan");
      return;
    }
    startGattProbe(adapter);
  }

  private void startGattProbe(BluetoothAdapter adapter) {
    final String normalizedMac;
    synchronized (lock) {
      if (probeFinished || gattStarted) return;
      gattStarted = true;
      normalizedMac = pendingNormalizedMac;
    }

    try {
      BluetoothDevice bluetoothDevice = adapter.getRemoteDevice(normalizedMac);
      BluetoothGatt gatt =
          bluetoothDevice.connectGatt(context, false, probeCallback, BluetoothDevice.TRANSPORT_LE);
      synchronized (lock) {
        probeGatt = gatt;
      }
      raw("GATT_CONNECT_REQUEST mac=" + maskMac(normalizedMac));
    } catch (IllegalArgumentException | SecurityException error) {
      raw("PROBE_ERROR stage=gatt_start error=" + error.getClass().getSimpleName());
      finishProbe(true, "gatt_start_" + error.getClass().getSimpleName());
    }
  }

  private final Runnable probeTimeout = () -> finishProbe(true, "timeout");
  private final Runnable mtuWaitExpired =
      () -> {
        raw("MTU_WAIT_EXPIRED proceedingWithServiceDiscovery=true");
        BluetoothGatt gatt;
        synchronized (lock) {
          gatt = probeGatt;
        }
        if (gatt != null) requestServiceDiscovery(gatt);
      };
  private final Runnable finishAfterHandshakeObservation =
      () -> finishProbe(true, "device_info_observation_complete");

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
              started = gatt.requestMtu(REQUESTED_MTU);
            } catch (SecurityException error) {
              raw("PROBE_ERROR stage=mtu error=SecurityException");
              requestServiceDiscovery(gatt);
              return;
            }
            raw("MTU_REQUEST requested=" + REQUESTED_MTU + " started=" + started);
            if (started) {
              handler.removeCallbacks(mtuWaitExpired);
              handler.postDelayed(mtuWaitExpired, MTU_CALLBACK_WAIT_MS);
            } else {
              requestServiceDiscovery(gatt);
            }
            return;
          }

          if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            finishProbe(true, "disconnected_status_" + status);
          }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
          handler.removeCallbacks(mtuWaitExpired);
          raw("MTU_RESULT status=" + status + " mtu=" + mtu);
          requestServiceDiscovery(gatt);
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
          BluetoothGattCharacteristic writeCharacteristic = service.getCharacteristic(TUYA_WRITE);
          if (notifyCharacteristic == null || readCharacteristic == null || writeCharacteristic == null) {
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
                  + lengthOf(value)
                  + " hex="
                  + toHex(value));
          if (status != BluetoothGatt.GATT_SUCCESS) {
            finishProbe(true, "read_status_" + status);
            return;
          }
          sendDeviceInfoProbe(gatt);
        }

        @Override
        public void onCharacteristicChanged(
            BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
          raw(
              "NOTIFY_RX uuid="
                  + characteristic.getUuid()
                  + " length="
                  + lengthOf(value)
                  + " hex="
                  + toHex(value));
          if (TUYA_NOTIFY.equals(characteristic.getUuid())) {
            handleTuyaNotification(value);
          }
        }
      };

  private void sendDeviceInfoProbe(BluetoothGatt gatt) {
    T4AContracts.Device device;
    synchronized (lock) {
      if (probeFinished) return;
      device = pendingDevice;
    }
    if (device == null || device.localKey == null || device.localKey.length() < 6) {
      raw("DEVICE_INFO_SKIPPED reason=local_key_unavailable");
      finishProbe(true, "device_info_local_key_unavailable");
      return;
    }

    BluetoothGattService service = gatt.getService(TUYA_SERVICE);
    BluetoothGattCharacteristic writeCharacteristic =
        service == null ? null : service.getCharacteristic(TUYA_WRITE);
    if (writeCharacteristic == null) {
      finishProbe(true, "device_info_write_characteristic_missing");
      return;
    }

    final byte[] frame;
    try {
      handshakeMaterial = TuyaFd50DeviceInfoCodec.sessionMaterial(device.localKey);
      frame = TuyaFd50DeviceInfoCodec.buildDeviceInfoFrame(device.localKey, 1);
    } catch (GeneralSecurityException error) {
      raw("DEVICE_INFO_ERROR stage=build error=" + error.getClass().getSimpleName());
      finishProbe(true, "device_info_build_error");
      return;
    }

    raw(
        "DEVICE_INFO_FRAME length="
            + frame.length
            + " packet=0 encryptedLength="
            + ((frame[1] & 0xFF))
            + " marker=0x"
            + String.format(Locale.ROOT, "%02X", frame[2] & 0xFF)
            + " security=0x04 seq=1 payload=00F3 secretsLogged=false");

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        int writeStatus =
            gatt.writeCharacteristic(
                writeCharacteristic,
                frame,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        raw("DEVICE_INFO_WRITE_REQUEST status=" + writeStatus + " writeType=no_response");
        if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
          finishProbe(true, "device_info_write_request_" + writeStatus);
          return;
        }
      } else {
        writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        writeCharacteristic.setValue(frame);
        boolean started = gatt.writeCharacteristic(writeCharacteristic);
        raw("DEVICE_INFO_WRITE_REQUEST started=" + started + " writeType=no_response");
        if (!started) {
          finishProbe(true, "device_info_write_request_false");
          return;
        }
      }
    } catch (SecurityException error) {
      raw("DEVICE_INFO_ERROR stage=write error=SecurityException");
      finishProbe(true, "device_info_write_security_exception");
      return;
    }

    handler.removeCallbacks(finishAfterHandshakeObservation);
    handler.postDelayed(finishAfterHandshakeObservation, HANDSHAKE_OBSERVATION_MS);
  }

  private void handleTuyaNotification(byte[] value) {
    if (value == null || value.length == 0) return;

    try {
      VarInt packet = readVarInt(value, 0);
      int packetNumber = packet.value;
      int pos = packet.nextOffset;

      if (packetNumber == 0) {
        VarInt totalLength = readVarInt(value, pos);
        pos = totalLength.nextOffset;
        if (pos >= value.length) throw new IllegalArgumentException("missing protocol marker");
        notifyBuffer.reset();
        notifyExpectedPacket = 0;
        notifyExpectedLength = totalLength.value;
        notifyProtocolMarker = value[pos++] & 0xFF;
      }

      if (packetNumber != notifyExpectedPacket) {
        raw(
            "DEVICE_INFO_RX_REASSEMBLY_RESET expectedPacket="
                + notifyExpectedPacket
                + " receivedPacket="
                + packetNumber);
        resetNotificationAssembler();
        return;
      }

      notifyBuffer.write(value, pos, value.length - pos);
      notifyExpectedPacket++;
      raw(
          "DEVICE_INFO_RX_FRAGMENT packet="
              + packetNumber
              + " accumulated="
              + notifyBuffer.size()
              + " expected="
              + notifyExpectedLength
              + " marker=0x"
              + String.format(Locale.ROOT, "%02X", notifyProtocolMarker));

      if (notifyExpectedLength < 0 || notifyBuffer.size() < notifyExpectedLength) return;
      if (notifyBuffer.size() > notifyExpectedLength) {
        raw("DEVICE_INFO_RX_ERROR reason=length_overflow");
        resetNotificationAssembler();
        return;
      }

      byte[] encrypted = notifyBuffer.toByteArray();
      TuyaFd50DeviceInfoCodec.SessionMaterial material = handshakeMaterial;
      if (material == null) {
        raw("DEVICE_INFO_RX_ERROR reason=session_material_missing");
        resetNotificationAssembler();
        return;
      }

      TuyaFd50DeviceInfoCodec.ParsedDeviceInfo parsed =
          TuyaFd50DeviceInfoCodec.parseDeviceInfoResponse(
              (byte) notifyProtocolMarker, encrypted, material);
      raw(
          "DEVICE_INFO_RESPONSE parsed=true marker=0x"
              + String.format(Locale.ROOT, "%02X", parsed.protocolMarker)
              + " security=0x"
              + String.format(Locale.ROOT, "%02X", parsed.securityFlag)
              + " seq="
              + parsed.sequence
              + " responseTo="
              + parsed.responseTo
              + " payloadLength="
              + parsed.payloadLength
              + " deviceVersion="
              + parsed.deviceVersion
              + " protocolVersion="
              + parsed.protocolVersion
              + " hardwareVersion="
              + parsed.hardwareVersion
              + " flags=0x"
              + Integer.toHexString(parsed.flags)
              + " bound="
              + parsed.bound
              + " sessionKeyDerived="
              + (parsed.sessionKey.length == 16)
              + " authKeyAvailable="
              + (parsed.authKey.length == 32)
              + " secretsLogged=false");
      resetNotificationAssembler();
      handler.removeCallbacks(finishAfterHandshakeObservation);
      handler.postDelayed(finishAfterHandshakeObservation, 250L);
    } catch (GeneralSecurityException | IllegalArgumentException error) {
      raw("DEVICE_INFO_RX_ERROR error=" + error.getClass().getSimpleName());
      resetNotificationAssembler();
    }
  }

  private void requestServiceDiscovery(BluetoothGatt gatt) {
    synchronized (lock) {
      if (probeFinished || serviceDiscoveryRequested) return;
      serviceDiscoveryRequested = true;
    }

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
  }

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

  private void stopAdvertisementScan() {
    final BluetoothLeScanner scanner;
    synchronized (lock) {
      scanner = probeScanner;
      probeScanner = null;
    }
    if (scanner == null) return;
    try {
      scanner.stopScan(advertisementCallback);
    } catch (SecurityException | IllegalStateException error) {
      raw("ADV_SCAN_ERROR stage=stop error=" + error.getClass().getSimpleName());
    }
  }

  /** Closes the probe and optionally continues the requested connection through Tuya. */
  private void finishProbe(boolean continueWithFallback, String reason) {
    final BluetoothGatt gatt;
    final T4AContracts.Device device;
    synchronized (lock) {
      if (probeFinished && probeGatt == null && pendingDevice == null) return;
      probeFinished = true;
      handler.removeCallbacks(probeTimeout);
      handler.removeCallbacks(finishAdvertisementObservation);
      handler.removeCallbacks(mtuWaitExpired);
      handler.removeCallbacks(finishAfterHandshakeObservation);
      gatt = probeGatt;
      device = pendingDevice;
      probeGatt = null;
      pendingDevice = null;
      pendingNormalizedMac = "";
      handshakeMaterial = null;
      resetNotificationAssembler();
      if (device != null) probedDeviceId = device.id;
    }

    stopAdvertisementScan();
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

  private void resetNotificationAssembler() {
    notifyBuffer.reset();
    notifyExpectedPacket = 0;
    notifyExpectedLength = -1;
    notifyProtocolMarker = -1;
  }

  private void raw(String message) {
    rawLog.accept("[BLE/DIRECT] " + message);
  }

  private static boolean hasServiceUuid(ScanRecord record, UUID uuid) {
    if (record == null) return false;
    List<ParcelUuid> serviceUuids = record.getServiceUuids();
    if (serviceUuids == null) return false;
    ParcelUuid expected = new ParcelUuid(uuid);
    return serviceUuids.contains(expected);
  }

  private static VarInt readVarInt(byte[] data, int start) {
    int result = 0;
    int shift = 0;
    int offset = start;
    while (offset < data.length && shift < 28) {
      int current = data[offset++] & 0xFF;
      result |= (current & 0x7F) << shift;
      if ((current & 0x80) == 0) return new VarInt(result, offset);
      shift += 7;
    }
    throw new IllegalArgumentException("invalid Tuya varint");
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

  private static int lengthOf(byte[] value) {
    return value == null ? 0 : value.length;
  }

  private static String toHex(byte[] value) {
    if (value == null || value.length == 0) return "";
    StringBuilder hex = new StringBuilder(value.length * 2);
    for (byte item : value) hex.append(String.format(Locale.ROOT, "%02X", item & 0xFF));
    return hex.toString();
  }

  private static final class VarInt {
    final int value;
    final int nextOffset;

    VarInt(int value, int nextOffset) {
      this.value = value;
      this.nextOffset = nextOffset;
    }
  }
}