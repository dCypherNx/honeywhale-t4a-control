package br.com.t4acontrol.backend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Vendor-neutral values and callbacks shared by provisioning and runtime transport. */
public final class T4AContracts {
  private T4AContracts() {}

  public interface Callback<T> { void onSuccess(T value); void onError(String code, String message); }
  public interface ResultCallback { void onSuccess(); void onError(String code, String message); }
  public interface DiscoveryListener { void onDevice(DiscoveredDevice device); void onError(String code, String message); }
  public interface DeviceListener {
    void onDpUpdate(String deviceId, Map<String, Object> dps);
    void onRemoved(String deviceId);
    void onConnectionChanged(String deviceId, boolean connected);
    void onDeviceInfoChanged(String deviceId);
  }
  public interface RssiCallback { void onResult(boolean success, int rssi); }

  public static final class DpSchema {
    public final String code; public final String mode; public final String type; public final String property;
    public DpSchema(String code, String mode, String type) {
      this(code, mode, type, "");
    }
    public DpSchema(String code, String mode, String type, String property) {
      this.code = value(code); this.mode = value(mode); this.type = value(type); this.property = value(property);
    }
  }

  public static final class Device {
    public final String id;
    public final String name;
    public final String mac;
    public final String uuid;
    public final String productId;
    /** Provider-neutral local communication key material, if exposed by the active provisioner. */
    public final String localKey;
    /** Provider-neutral BLE security/session bootstrap key, if exposed by the active provisioner. */
    public final String securityKey;
    /** Non-secret protocol/capability metadata useful when selecting a native transport codec. */
    public final Map<String, String> protocolMetadata;
    public final Map<String, Object> dps;
    public final Map<String, DpSchema> schema;

    public Device(String id, String name, String mac, String uuid,
        Map<String, Object> dps, Map<String, DpSchema> schema) {
      this(id, name, mac, uuid, "", "", "", Collections.emptyMap(), dps, schema);
    }

    public Device(String id, String name, String mac, String uuid, String localKey,
        Map<String, Object> dps, Map<String, DpSchema> schema) {
      this(id, name, mac, uuid, "", localKey, "", Collections.emptyMap(), dps, schema);
    }

    public Device(String id, String name, String mac, String uuid, String productId,
        String localKey, Map<String, Object> dps, Map<String, DpSchema> schema) {
      this(id, name, mac, uuid, productId, localKey, "", Collections.emptyMap(), dps, schema);
    }

    public Device(String id, String name, String mac, String uuid, String productId,
        String localKey, Map<String, String> protocolMetadata,
        Map<String, Object> dps, Map<String, DpSchema> schema) {
      this(id, name, mac, uuid, productId, localKey, "", protocolMetadata, dps, schema);
    }

    public Device(String id, String name, String mac, String uuid, String productId,
        String localKey, String securityKey, Map<String, String> protocolMetadata,
        Map<String, Object> dps, Map<String, DpSchema> schema) {
      this.id = value(id);
      this.name = value(name);
      this.mac = value(mac);
      this.uuid = value(uuid);
      this.productId = value(productId);
      this.localKey = value(localKey);
      this.securityKey = value(securityKey);
      this.protocolMetadata = immutableMap(protocolMetadata);
      this.dps = immutableMap(dps);
      this.schema = immutableMap(schema);
    }
  }

  public static final class Home {
    public final long id; public final String name; public final List<Device> devices;
    public Home(long id, String name, List<Device> devices) {
      this.id = id; this.name = value(name);
      this.devices = Collections.unmodifiableList(devices == null ? Collections.emptyList() : new ArrayList<>(devices));
    }
  }

  public static final class DiscoveredDevice {
    public final String discoveryId; public final String address; public final String uuid;
    public final String productId; public final int deviceType; public final boolean bound; public final int rssi;
    public DiscoveredDevice(String discoveryId, String address, String uuid, String productId,
        int deviceType, boolean bound, int rssi) {
      this.discoveryId = value(discoveryId); this.address = value(address); this.uuid = value(uuid);
      this.productId = value(productId); this.deviceType = deviceType; this.bound = bound; this.rssi = rssi;
    }
  }

  static String value(String value) { return value == null ? "" : value; }

  private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
    return Collections.unmodifiableMap(values == null ? Collections.emptyMap() : new HashMap<>(values));
  }
}
