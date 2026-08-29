package br.com.t4acontrol.backend.mqtt;

import android.os.Handler;
import android.os.Looper;
import br.com.t4acontrol.backend.T4AState;
import com.alibaba.fastjson.JSON;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Owns MQTT connection policy and maps T4A state into outbound telemetry. */
public final class MqttTelemetryCoordinator implements MqttTransport.Listener {
  public interface Listener {
    void onMqttEvent(String event);

    void onMqttRawLog(String entry);
  }

  private static final long MAINTENANCE_MS = 5000L;
  private static final long HEARTBEAT_MS = 30000L;

  private final MqttConfigurationStore configurationStore;
  private final MqttTransport transport;
  private final Listener listener;
  private final Handler handler = new Handler(Looper.getMainLooper());

  private boolean running;
  private long configurationRevision = Long.MIN_VALUE;
  private MqttConfiguration configuration = MqttConfiguration.defaults();
  private T4AState lastState;
  private String lastTelemetrySignature = "";
  private long lastTelemetryPublishAt;
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
    refreshConfiguration(true);
    ensureConnection();
    handler.post(maintenance);
  }

  public void stop() {
    if (!running) return;
    running = false;
    handler.removeCallbacks(maintenance);
    publishOfflineIfPossible(configuration);
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

  @Override
  public void onConnected() {
    if (!running || !configuration.enabled) return;
    event("MQTT conectado a " + endpoint(configuration));
    raw("STATUS online");
    transport.publish(statusTopic(configuration), "online", 1, true);
    lastTelemetrySignature = "";
    lastDiscoveryPayload = "";
    publishDiscovery(true);
    publishTelemetry(true);
  }

  @Override
  public void onDisconnected(String reason) {
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

    if (transport.isConnected()
        && previous.enabled
        && (discoveryDisabled || discoveryBrokerChanged)) {
      clearDiscoveryIfPossible();
    }

    if (transport.isConnected() && previous.enabled && (reconnect || !next.enabled)) {
      publishOfflineIfPossible(previous);
      transport.disconnect();
    }

    configuration = next;
    configurationRevision = revision;
    lastTelemetrySignature = "";
    lastDiscoveryPayload = "";

    if (!configuration.enabled) {
      if (wasEnabled) event("MQTT desativado");
      return;
    }

    if (!wasEnabled) event("MQTT ativado");
    if (transport.isConnected()) publishDiscovery(true);
  }

  private void ensureConnection() {
    if (!running
        || !configuration.enabled
        || transport.isConnected()
        || transport.isConnecting()) return;
    raw("CONNECT " + endpoint(configuration));
    transport.connect(configuration, statusTopic(configuration), "offline");
  }

  private void publishDiscovery(boolean force) {
    if (!running
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
    if (!transport.isConnected() || lastDiscoveryTopic.isEmpty()) return;
    raw("DISCOVERY CLEAR " + lastDiscoveryTopic);
    transport.publish(lastDiscoveryTopic, "", 1, true);
    lastDiscoveryTopic = "";
    lastDiscoveryPayload = "";
    event("Home Assistant discovery removido");
  }

  private void publishTelemetry(boolean force) {
    if (!running
        || !configuration.enabled
        || !transport.isConnected()
        || lastState == null) return;

    Map<String, Object> telemetry = telemetry(lastState);
    String signature = JSON.toJSONString(telemetry);
    long now = System.currentTimeMillis();
    if (!force
        && signature.equals(lastTelemetrySignature)
        && now - lastTelemetryPublishAt < HEARTBEAT_MS) return;

    telemetry.put("timestamp_ms", now);
    String payload = JSON.toJSONString(telemetry);
    raw("TX " + telemetryTopic(configuration) + " " + payload);
    transport.publish(telemetryTopic(configuration), payload, 1, true);
    lastTelemetrySignature = signature;
    lastTelemetryPublishAt = now;
  }

  private Map<String, Object> telemetry(T4AState state) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("connected", state.connected);
    out.put("device_name", state.deviceName);
    out.put("mac", state.mac);
    out.put("rssi_dbm", state.rssi);
    out.put("battery_percent", integer(state.dps.get("3")));
    out.put("speed_kmh", scaled(state.dps.get("2"), 10.0));
    out.put("odometer_km", scaled(state.dps.get("12"), 10.0));
    out.put("trip_km", scaled(state.dps.get("5"), 10.0));
    out.put("ride_time_s", integer(state.dps.get("6")));
    out.put("locked", !truth(state.dps.get("1")));
    out.put("headlight", truth(state.dps.get("8")));
    out.put("cruise", truth(state.dps.get("13")));
    out.put("mode", string(state.dps.get("14")));
    out.put("start_mode", string(state.dps.get("16")));
    out.put("unit_setting", string(state.dps.get("11")));
    return out;
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

  private static int integer(Object value) {
    try {
      return value == null ? 0 : Integer.parseInt(value.toString());
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private static double scaled(Object value, double divisor) {
    try {
      return value == null ? 0.0 : Double.parseDouble(value.toString()) / divisor;
    } catch (NumberFormatException ignored) {
      return 0.0;
    }
  }

  private static boolean truth(Object value) {
    return value != null
        && (Boolean.TRUE.equals(value)
            || "true".equalsIgnoreCase(value.toString())
            || "1".equals(value.toString()));
  }

  private static String string(Object value) {
    return value == null ? "" : value.toString();
  }
}
