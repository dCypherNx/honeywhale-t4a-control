package br.com.t4acontrol.backend.mqtt;

import br.com.t4acontrol.backend.T4AState;
import com.alibaba.fastjson.JSON;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Builds Home Assistant MQTT Device Discovery messages without depending on Android or Paho. */
public final class HomeAssistantDiscovery {
  private static final String DISCOVERY_PREFIX = "homeassistant";
  private static final String ORIGIN_NAME = "T4A Control";
  private static final String SUPPORT_URL =
      "https://github.com/dCypherNx/honeywhale-t4a-control";

  private HomeAssistantDiscovery() {}

  public static String topic(T4AState state) {
    String deviceId = deviceId(state);
    return deviceId.isEmpty() ? "" : DISCOVERY_PREFIX + "/device/" + deviceId + "/config";
  }

  public static String payload(MqttConfiguration configuration, T4AState state) {
    String deviceId = deviceId(state);
    if (deviceId.isEmpty()) return "";

    Map<String, Object> root = new LinkedHashMap<>();

    Map<String, Object> device = new LinkedHashMap<>();
    device.put("identifiers", Collections.singletonList(deviceId));
    device.put("name", deviceName(state));
    device.put("manufacturer", "Honeywhale");
    device.put("model", "T4A");
    if (state.mac != null && !state.mac.isBlank()) device.put("serial_number", state.mac);
    root.put("device", device);

    Map<String, Object> origin = new LinkedHashMap<>();
    origin.put("name", ORIGIN_NAME);
    origin.put("support_url", SUPPORT_URL);
    root.put("origin", origin);

    root.put("state_topic", configuration.baseTopic + "/telemetry");
    root.put("availability_topic", configuration.baseTopic + "/status");
    root.put("payload_available", "online");
    root.put("payload_not_available", "offline");
    root.put("qos", 1);
    root.put("components", components(configuration, deviceId));

    return JSON.toJSONString(root);
  }

  private static Map<String, Object> components(
      MqttConfiguration configuration, String deviceId) {
    Map<String, Object> out = new LinkedHashMap<>();

    out.put(
        "battery",
        sensor(
            "Bateria",
            deviceId + "_battery",
            "{{ value_json.battery_percent }}",
            "battery",
            "%",
            "measurement"));
    out.put(
        "speed",
        sensor(
            "Velocidade",
            deviceId + "_speed",
            "{{ value_json.speed_kmh }}",
            "speed",
            "km/h",
            "measurement"));
    out.put(
        "odometer",
        sensor(
            "Odômetro total",
            deviceId + "_odometer",
            "{{ value_json.odometer_km }}",
            "distance",
            "km",
            "total_increasing"));
    out.put(
        "trip",
        sensor(
            "Odômetro parcial",
            deviceId + "_trip",
            "{{ value_json.trip_km }}",
            "distance",
            "km",
            "measurement"));
    out.put(
        "ride_time",
        sensor(
            "Tempo de viagem",
            deviceId + "_ride_time",
            "{{ value_json.ride_time_s }}",
            "duration",
            "s",
            "measurement"));
    Map<String, Object> rssi =
        sensor(
            "RSSI",
            deviceId + "_rssi",
            "{{ value_json.rssi_dbm }}",
            "signal_strength",
            "dBm",
            "measurement");
    rssi.put("entity_category", "diagnostic");
    out.put("rssi", rssi);

    out.put(
        "connected",
        binarySensor(
            "Conexão Bluetooth",
            deviceId + "_connected",
            "{{ 'ON' if value_json.connected else 'OFF' }}",
            "connectivity"));
    out.put(
        "locked",
        binarySensor(
            "Bloqueio",
            deviceId + "_locked",
            "{{ 'OFF' if value_json.locked else 'ON' }}",
            "lock"));
    out.put(
        "headlight",
        binarySensor(
            "Farol",
            deviceId + "_headlight",
            "{{ 'ON' if value_json.headlight else 'OFF' }}",
            "light"));
    out.put(
        "cruise",
        binarySensor(
            "Cruzeiro",
            deviceId + "_cruise",
            "{{ 'ON' if value_json.cruise else 'OFF' }}",
            null));

    out.put(
        "mode",
        sensor(
            "Modo de pilotagem",
            deviceId + "_mode",
            "{{ value_json.mode }}",
            null,
            null,
            null));
    out.put(
        "start_mode",
        sensor(
            "Modo de partida",
            deviceId + "_start_mode",
            "{{ value_json.start_mode }}",
            null,
            null,
            null));
    Map<String, Object> unit =
        sensor(
            "Unidade",
            deviceId + "_unit",
            "{{ value_json.unit_setting }}",
            null,
            null,
            null);
    unit.put("entity_category", "diagnostic");
    out.put("unit", unit);

    Map<String, Object> location = new LinkedHashMap<>();
    location.put("platform", "device_tracker");
    location.put("name", "Localização");
    location.put("unique_id", deviceId + "_location");
    location.put("source_type", "gps");
    location.put("state_topic", configuration.baseTopic + "/location");
    location.put("json_attributes_topic", configuration.baseTopic + "/location");
    location.put("value_template", "{{ 'None' }}");
    location.put("payload_reset", "None");
    out.put("location", location);

    return out;
  }

  private static Map<String, Object> sensor(
      String name,
      String uniqueId,
      String valueTemplate,
      String deviceClass,
      String unit,
      String stateClass) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("platform", "sensor");
    out.put("name", name);
    out.put("unique_id", uniqueId);
    out.put("value_template", valueTemplate);
    if (deviceClass != null) out.put("device_class", deviceClass);
    if (unit != null) out.put("unit_of_measurement", unit);
    if (stateClass != null) out.put("state_class", stateClass);
    return out;
  }

  private static Map<String, Object> binarySensor(
      String name, String uniqueId, String valueTemplate, String deviceClass) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("platform", "binary_sensor");
    out.put("name", name);
    out.put("unique_id", uniqueId);
    out.put("value_template", valueTemplate);
    out.put("payload_on", "ON");
    out.put("payload_off", "OFF");
    if (deviceClass != null) out.put("device_class", deviceClass);
    return out;
  }

  private static String deviceId(T4AState state) {
    if (state == null || state.mac == null) return "";
    String normalized = state.mac.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    return normalized.isEmpty() ? "" : "t4a_" + normalized;
  }

  private static String deviceName(T4AState state) {
    if (state == null || state.deviceName == null || state.deviceName.isBlank()) return "T4A";
    return state.deviceName;
  }
}
