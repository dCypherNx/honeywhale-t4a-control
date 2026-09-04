package br.com.t4acontrol.backend.mqtt;

import android.os.Handler;
import android.os.Looper;
import br.com.t4acontrol.backend.T4AState;
import br.com.t4acontrol.backend.location.LocationSnapshot;
import com.alibaba.fastjson.JSON;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Owns MQTT connection policy and maps T4A state/location into outbound telemetry. */
public final class MqttTelemetryCoordinator implements MqttTransport.Listener {
  public interface Listener {
    void onMqttEvent(String event);

    void onMqttRawLog(String entry);
  }

  private static final long MAINTENANCE_MS = 5000L;

  private final MqttConfigurationStore configurationStore;
  private final MqttTransport transport;
  private final Listener listener;
  private final Handler handler = new Handler(Looper.getMainLooper());

  private boolean running;
  private boolean mqttReady;
  private long configurationRevision = Long.MIN_VALUE;
  private MqttConfiguration configuration = MqttConfiguration.defaults();
  private T4AState lastState;
  private LocationSnapshot lastLocation;
  private String lastTelemetrySignature = "";
  private String lastLocationSignature = "";
  private String lastDiscoveryTopic = "";
  private String lastDiscoveryPayload = "";

  private final Runnable maintenance =
      new Runnable() {
        @Override
        public void run() {
          if (!running) return;
          refreshConfiguration(false);
          ensureConnection();
          publishDiscovery(false);
          publishLocation(false);
          publishTelemetry(false);
          handler.postDelayed(this, MAINTENANCE_MS);
        }
      };

  public MqttTelemetryCoordinator(
      MqttConfigurationStore configurationStore,
      MqttTransport transport,
      Listener listener) {
    this.configurationStore = Objects.requireNonNull(configurationStore);
    this.transport = Objects.requireNonNull(transport);
    this.listener = Objects.requireNonNull(listener);
    this.transport.setListener(this);
  }

  public void start() {
    if (running) return;
    running = true;
    mqttReady = false;
    refreshConfiguration(true);
    ensureConnection();
    handler.post(maintenance);
  }

  public void stop() {
    if (!running) return;
    running = false;
    handler.removeCallbacks(maintenance);
    publishOfflineIfPossible(configuration);
    mqttReady = false;
    transport.disconnect();
    transport.close();
  }

  public void onState(T4AState state) {
    lastState = state;
    if (!running) return;
    refreshConfiguration(false);
    ensureConnection();
    publishDiscovery(false);
    publishTelemetry(false);
  }

  public void onLocation(LocationSnapshot snapshot) {
    if (snapshot == null) return;
    lastLocation = snapshot;
    if (!running) return;
    refreshConfiguration(false);
    ensureConnection();
    publishLocation(false);
    publishTelemetry(false);
  }

  @Override
  public void onConnected() {
    if (!running || !configuration.enabled) return;
    mqttReady = true;
    event("MQTT conectado a " + endpoint(configuration));
    raw("STATUS online");
    transport.publish(statusTopic(configuration), "online", 1, true);
    lastTelemetrySignature = "";
    lastLocationSignature = "";
    lastDiscoveryPayload = "";
    publishDiscovery(false);
    publishLocation(true);
    publishTelemetry(true);
  }

  @Override
  public void onDisconnected(String reason) {
    mqttReady = false;
    if (!running || !configuration.enabled) return;
    event("MQTT desconectado" + suffix(reason));
  }

  @Override
  public void onError(String operation, String reason) {
    if (!running) return;
    event("MQTT " + operation + " falhou" + suffix(reason));
  }

  private void refreshConfiguration(boolean force) {
    long revision = configurationStore.revision();
    if (!force && revision == configurationRevision) return;

    MqttConfiguration previous = configuration;
    MqttConfiguration next = configurationStore.load();
    MqttConfiguration.ValidationError error = next.validate();
    if (error != MqttConfiguration.ValidationError.NONE) {
      event("Configuração MQTT inválida: " + error);
      next = MqttConfiguration.defaults();
    }

    boolean reconnect = connectionSettingsChanged(previous, next);
    boolean wasEnabled = previous.enabled;
    boolean discoveryDisabled = previous.discoveryEnabled && !next.discoveryEnabled;
    boolean discoveryBrokerChanged =
        previous.discoveryEnabled && brokerEndpointChanged(previous, next);

    if (mqttReady
        && transport.isConnected()
        && previous.enabled
        && (discoveryDisabled || discoveryBrokerChanged)) {
      clearDiscoveryIfPossible();
    }

    if (transport.isConnected() && previous.enabled && (reconnect || !next.enabled)) {
      publishOfflineIfPossible(previous);
      mqttReady = false;
      transport.disconnect();
    }

    configuration = next;
    configurationRevision = revision;
    lastTelemetrySignature = "";
    lastLocationSignature = "";
    lastDiscoveryPayload = "";

    if (!configuration.enabled) {
      if (wasEnabled) event("MQTT desativado");
      return;
    }

    if (!wasEnabled) event("MQTT ativado");
    if (mqttReady && transport.isConnected()) publishDiscovery(false);
  }

  private void ensureConnection() {
    if (!running
        || !configuration.enabled
        || transport.isConnected()
        || transport.isConnecting()) return;
    mqttReady = false;
    raw("CONNECT " + endpoint(configuration));
    transport.connect(configuration, statusTopic(configuration), "offline");
  }

  private void publishDiscovery(boolean force) {
    if (!running
        || !mqttReady
        || !configuration.enabled
        || !configuration.discoveryEnabled
        || !transport.isConnected()
        || lastState == null) return;

    String topic = HomeAssistantDiscovery.topic(lastState);
    String payload = HomeAssistantDiscovery.payload(configuration, lastState);
    if (topic.isEmpty() || payload.isEmpty()) return;
    if (!force && topic.equals(lastDiscoveryTopic) && payload.equals(lastDiscoveryPayload)) return;

    if (!lastDiscoveryTopic.isEmpty() && !lastDiscoveryTopic.equals(topic)) {
      raw("DISCOVERY CLEAR " + lastDiscoveryTopic);
      transport.publish(lastDiscoveryTopic, "", 1, true);
    }

    raw("DISCOVERY TX " + topic + " " + payload);
    transport.publish(topic, payload, 1, true);
    lastDiscoveryTopic = topic;
    lastDiscoveryPayload = payload;
    event("Home Assistant discovery publicado");
  }

  private void clearDiscoveryIfPossible() {
    if (!mqttReady || !transport.isConnected() || lastDiscoveryTopic.isEmpty()) return;
    raw("DISCOVERY CLEAR " + lastDiscoveryTopic);
    transport.publish(lastDiscoveryTopic, "", 1, true);
    lastDiscoveryTopic = "";
    lastDiscoveryPayload = "";
    event("Home Assistant discovery removido");
  }

  private void publishLocation(boolean force) {
    if (!running
        || !mqttReady
        || !configuration.enabled
        || !transport.isConnected()
        || lastLocation == null) return;

    Map<String, Object> location = location(lastLocation);
    String payload = JSON.toJSONString(location);
    if (!force && payload.equals(lastLocationSignature)) return;

    raw("TX " + locationTopic(configuration) + " " + payload);
    transport.publish(locationTopic(configuration), payload, 1, true);
    lastLocationSignature = payload;
  }

  private void publishTelemetry(boolean force) {
    if (!running
        || !mqttReady
        || !configuration.enabled
        || !transport.isConnected()
        || lastState == null) return;

    Map<String, Object> telemetry = telemetry(lastState, lastLocation);
    String signature = JSON.toJSONString(telemetry);
    if (!force && signature.equals(lastTelemetrySignature)) return;

    long now = System.currentTimeMillis();
    telemetry.put("timestamp_ms", now);
    String payload = JSON.toJSONString(telemetry);
    raw("TX " + telemetryTopic(configuration) + " " + payload);
    transport.publish(telemetryTopic(configuration), payload, 1, true);
    lastTelemetrySignature = signature;
  }

  private Map<String, Object> telemetry(T4AState state, LocationSnapshot location) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("connected", state.connected);
    if (state.deviceName != null && !state.deviceName.isBlank()) out.put("device_name", state.deviceName);
    if (state.mac != null && !state.mac.isBlank()) out.put("mac", state.mac);
    if (state.rssi != 0) out.put("rssi", state.rssi);
    String rssiRange = rssiRange(state.rssi);
    if (!rssiRange.isEmpty()) out.put("rssi_range", rssiRange);

    putIntegerIfPresent(out, "battery_percent", state.dps, "3");
    if (state.batteryObservedMin != null) out.put("battery_observed_min", state.batteryObservedMin);
    if (state.batteryObservedMax != null) out.put("battery_observed_max", state.batteryObservedMax);
    if (state.batteryCycleStartedAt != null) out.put("battery_cycle_started_at", state.batteryCycleStartedAt);
    putScaledIfPresent(out, "speed_kmh", state.dps, "2", 10.0);
    putScaledIfPresent(out, "odometer_km", state.dps, "12", 10.0);
    putScaledIfPresent(out, "trip_km", state.dps, "5", 10.0);
    putIntegerIfPresent(out, "ride_time_s", state.dps, "6");

    if (state.dps.containsKey("1")) out.put("locked", !truth(state.dps.get("1")));
    if (state.dps.containsKey("8")) out.put("headlight", truth(state.dps.get("8")));
    if (state.dps.containsKey("13")) out.put("cruise", truth(state.dps.get("13")));
    putStringIfPresent(out, "mode", state.dps, "14");
    putStringIfPresent(out, "start_mode", state.dps, "16");
    putStringIfPresent(out, "unit_setting", state.dps, "11");

    if (location != null) appendLocation(out, location);
    return out;
  }

  private static String rssiRange(int rssi) {
    if (rssi == 0) return "";
    if (rssi >= -40) return "short";
    if (rssi >= -60) return "medium";
    if (rssi > -90) return "long";
    return "out_of_range";
  }

  private Map<String, Object> location(LocationSnapshot snapshot) {
    Map<String, Object> out = new LinkedHashMap<>();
    appendLocation(out, snapshot);
    return out;
  }

  private static void appendLocation(Map<String, Object> out, LocationSnapshot snapshot) {
    out.put("latitude", snapshot.latitude);
    out.put("longitude", snapshot.longitude);
    out.put("gps_accuracy", snapshot.accuracyMeters);
    out.put("location_timestamp_ms", snapshot.timestampMs);
    if (snapshot.gpsSpeedKmh != null) out.put("gps_speed_kmh", snapshot.gpsSpeedKmh);
    if (snapshot.bearingDegrees != null) out.put("bearing_deg", snapshot.bearingDegrees);
    if (snapshot.altitudeMeters != null) out.put("altitude_m", snapshot.altitudeMeters);
  }

  private void publishOfflineIfPossible(MqttConfiguration value) {
    if (!value.enabled || !transport.isConnected()) return;
    try {
      raw("STATUS offline");
      transport.publish(statusTopic(value), "offline", 1, true);
    } catch (RuntimeException ignored) {
      // LWT covers abnormal disconnects. A best-effort explicit offline state is enough here.
    }
  }

  private static String statusTopic(MqttConfiguration value) {
    return value.baseTopic + "/status";
  }

  private static String telemetryTopic(MqttConfiguration value) {
    return value.baseTopic + "/telemetry";
  }

  private static String locationTopic(MqttConfiguration value) {
    return value.baseTopic + "/location";
  }

  private void event(String value) {
    listener.onMqttEvent(value);
  }

  private void raw(String value) {
    listener.onMqttRawLog(value);
  }

  private static boolean connectionSettingsChanged(
      MqttConfiguration before, MqttConfiguration after) {
    return before.enabled != after.enabled
        || before.port != after.port
        || before.transport != after.transport
        || before.keepAliveSeconds != after.keepAliveSeconds
        || !before.websocketPath.equals(after.websocketPath)
        || !before.host.equals(after.host)
        || !before.username.equals(after.username)
        || !before.password.equals(after.password)
        || !before.clientId.equals(after.clientId)
        || !before.baseTopic.equals(after.baseTopic);
  }

  private static boolean brokerEndpointChanged(
      MqttConfiguration before, MqttConfiguration after) {
    return before.port != after.port
        || before.transport != after.transport
        || !before.websocketPath.equals(after.websocketPath)
        || !before.host.equals(after.host);
  }

  private static String endpoint(MqttConfiguration value) {
    return value.serverUri();
  }

  private static String suffix(String value) {
    return value == null || value.isBlank() ? "" : ": " + value;
  }

  private static void putIntegerIfPresent(
      Map<String, Object> out, String outputKey, Map<String, Object> dps, String dpId) {
    if (!dps.containsKey(dpId)) return;
    Integer value = integer(dps.get(dpId));
    if (value != null) out.put(outputKey, value);
  }

  private static void putScaledIfPresent(
      Map<String, Object> out,
      String outputKey,
      Map<String, Object> dps,
      String dpId,
      double divisor) {
    if (!dps.containsKey(dpId)) return;
    Double value = scaled(dps.get(dpId), divisor);
    if (value != null) out.put(outputKey, value);
  }

  private static void putStringIfPresent(
      Map<String, Object> out, String outputKey, Map<String, Object> dps, String dpId) {
    if (!dps.containsKey(dpId)) return;
    Object value = dps.get(dpId);
    if (value == null) return;
    String text = value.toString();
    if (!text.isBlank()) out.put(outputKey, text);
  }

  private static Integer integer(Object value) {
    try {
      return value == null ? null : Integer.parseInt(value.toString());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static Double scaled(Object value, double divisor) {
    try {
      return value == null ? null : Double.parseDouble(value.toString()) / divisor;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static boolean truth(Object value) {
    return value != null
        && (Boolean.TRUE.equals(value)
            || "true".equalsIgnoreCase(value.toString())
            || "1".equals(value.toString()));
  }
}
