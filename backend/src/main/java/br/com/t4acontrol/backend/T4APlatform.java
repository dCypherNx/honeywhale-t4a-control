package br.com.t4acontrol.backend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform boundary used by the T4A domain backend.
 *
 * <p>The contract deliberately contains no ThingClips/Tuya types. A platform implementation may
 * use the Tuya SDK, a native BLE protocol, or a test fake without leaking that choice into the
 * state machine or UI.
 */
public interface T4APlatform {
  interface Callback<T> {
    void onSuccess(T value);

    void onError(String code, String message);
  }

  interface ResultCallback {
    void onSuccess();

    void onError(String code, String message);
  }

  interface DiscoveryListener {
    void onDevice(DiscoveredDevice device);

    void onError(String code, String message);
  }

  interface DeviceListener {
    void onDpUpdate(String deviceId, Map<String, Object> dps);

    void onRemoved(String deviceId);

    void onConnectionChanged(String deviceId, boolean connected);

    void onDeviceInfoChanged(String deviceId);
  }

  interface RssiCallback {
    void onResult(boolean success, int rssi);
  }

  final class DpSchema {
    public final String code;
    public final String mode;
    public final String type;

    public DpSchema(String code, String mode, String type) {
      this.code = code;
      this.mode = mode;
      this.type = type;
    }
  }

  final class Device {
    public final String id;
    public final String name;
    public final String mac;
    public final String uuid;
    public final Map<String, Object> dps;
    public final Map<String, DpSchema> schema;

    public Device(
        String id,
        String name,
        String mac,
        String uuid,
        Map<String, Object> dps,
        Map<String, DpSchema> schema) {
      this.id = value(id);
      this.name = value(name);
      this.mac = value(mac);
      this.uuid = value(uuid);
      this.dps = immutableMap(dps);
      this.schema = immutableMap(schema);
    }
  }

  final class Home {
    public final long id;
    public final List<Device> devices;

    public Home(long id, List<Device> devices) {
      this.id = id;
      this.devices =
          Collections.unmodifiableList(
              devices == null ? Collections.emptyList() : new ArrayList<>(devices));
    }
  }

  final class DiscoveredDevice {
    /** Opaque identifier meaningful only to the active platform implementation. */
    public final String discoveryId;
    public final String address;
    public final String uuid;
    public final String productId;
    public final int deviceType;
    public final boolean bound;
    public final int rssi;

    public DiscoveredDevice(
        String discoveryId,
        String address,
        String uuid,
        String productId,
        int deviceType,
        boolean bound,
        int rssi) {
      this.discoveryId = value(discoveryId);
      this.address = value(address);
      this.uuid = value(uuid);
      this.productId = value(productId);
      this.deviceType = deviceType;
      this.bound = bound;
      this.rssi = rssi;
    }
  }

  /** Returns the restored account identifier, or {@code null} when no session exists. */
  String currentAccount();

  void login(String countryCode, String email, String password, Callback<String> callback);

  /** Loads the primary home used by this single-device application. An id of zero means no home. */
  void loadPrimaryHome(Callback<Home> callback);

  void startDiscovery(long timeoutMs, DiscoveryListener listener);

  void stopDiscovery();

  /** Performs all platform-specific token acquisition and device activation. */
  void pair(long homeId, DiscoveredDevice device, Callback<Device> callback);

  void attach(Device device, DeviceListener listener);

  void detach();

  void connect(Device device);

  boolean isConnected(String deviceId);

  Device cachedDevice(String deviceId);

  void publish(String deviceId, Map<String, Object> dps, ResultCallback callback);

  void readRssi(String mac, RssiCallback callback);

  void remove(String deviceId, ResultCallback callback);

  void destroy();

  static String value(String value) {
    return value == null ? "" : value;
  }

  static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
    return Collections.unmodifiableMap(
        values == null ? Collections.emptyMap() : new HashMap<>(values));
  }
}
