package br.com.t4acontrol.mqtt;

import br.com.t4acontrol.backend.mqtt.MqttConfiguration;
import br.com.t4acontrol.backend.mqtt.MqttTransport;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

/** Eclipse Paho adapter. No Paho type escapes the provider-neutral MqttTransport contract. */
public final class PahoMqttTransport implements MqttTransport {
  private volatile Listener listener;
  private volatile MqttAsyncClient client;
  private volatile boolean connecting;

  @Override
  public void setListener(Listener listener) {
    this.listener = listener;
  }

  @Override
  public synchronized void connect(
      MqttConfiguration configuration, String willTopic, String willPayload) {
    if (configuration == null || connecting || isConnected()) return;

    String uri = serverUri(configuration);
    try {
      closeClient();
      MqttAsyncClient next =
          new MqttAsyncClient(uri, configuration.clientId, new MemoryPersistence());
      next.setCallback(
          new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
              connecting = false;
              Listener current = listener;
              if (current != null) current.onConnected();
            }

            @Override
            public void connectionLost(Throwable cause) {
              connecting = false;
              Listener current = listener;
              if (current != null) current.onDisconnected(describe(cause));
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
              // Telemetry transport is intentionally publish-only.
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
              // QoS acknowledgements do not need to cross the transport boundary.
            }
          });

      MqttConnectOptions options = new MqttConnectOptions();
      options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
      // Reconnect cadence belongs to MqttTelemetryCoordinator, not the provider adapter.
      options.setAutomaticReconnect(false);
      options.setCleanSession(true);
      options.setConnectionTimeout(10);
      options.setKeepAliveInterval(configuration.keepAliveSeconds);
      options.setWill(
          willTopic,
          willPayload.getBytes(StandardCharsets.UTF_8),
          1,
          true);
      if (!configuration.username.isEmpty()) {
        options.setUserName(configuration.username);
        options.setPassword(configuration.password.toCharArray());
      }

      client = next;
      connecting = true;
      next.connect(
          options,
          null,
          new org.eclipse.paho.client.mqttv3.IMqttActionListener() {
            @Override
            public void onSuccess(org.eclipse.paho.client.mqttv3.IMqttToken asyncActionToken) {
              // connectComplete is the canonical connected callback.
            }

            @Override
            public void onFailure(
                org.eclipse.paho.client.mqttv3.IMqttToken asyncActionToken, Throwable exception) {
              connecting = false;
              Listener current = listener;
              if (current != null) current.onError("conexão", describe(exception));
            }
          });
    } catch (MqttException | RuntimeException error) {
      connecting = false;
      Listener current = listener;
      if (current != null) current.onError("conexão", describe(error));
      closeClient();
    }
  }

  @Override
  public synchronized void disconnect() {
    MqttAsyncClient current = client;
    connecting = false;
    if (current == null) return;
    try {
      if (current.isConnected()) current.disconnectForcibly(1000L, 1000L);
    } catch (MqttException ignored) {
      // Best effort. LWT covers unexpected connection loss.
    } finally {
      closeClient();
    }
  }

  @Override
  public boolean isConnected() {
    MqttAsyncClient current = client;
    return current != null && current.isConnected();
  }

  @Override
  public boolean isConnecting() {
    return connecting;
  }

  @Override
  public void publish(String topic, String payload, int qos, boolean retained) {
    MqttAsyncClient current = client;
    if (current == null || !current.isConnected()) return;
    try {
      current.publish(topic, payload.getBytes(StandardCharsets.UTF_8), qos, retained);
    } catch (MqttException error) {
      Listener currentListener = listener;
      if (currentListener != null) currentListener.onError("publicação", describe(error));
    }
  }

  @Override
  public synchronized void close() {
    connecting = false;
    closeClient();
  }

  private void closeClient() {
    MqttAsyncClient current = client;
    client = null;
    if (current == null) return;
    try {
      current.close();
    } catch (MqttException ignored) {
      // Nothing else owns this client instance.
    }
  }

  private static String serverUri(MqttConfiguration configuration) {
    String scheme = configuration.tls ? "ssl" : "tcp";
    return scheme + "://" + configuration.host + ":" + configuration.port;
  }

  private static String describe(Throwable error) {
    if (error == null) return "";
    StringBuilder out = new StringBuilder(error.getClass().getSimpleName());
    if (error instanceof MqttException mqtt)
      out.append(" reasonCode=").append(mqtt.getReasonCode());
    String message = error.getMessage();
    if (message != null && !message.isBlank() && !message.equals(error.getClass().getSimpleName()))
      out.append(" message=").append(message);
    Throwable cause = error.getCause();
    if (cause != null && cause != error) {
      out.append(" cause=").append(cause.getClass().getSimpleName());
      String causeMessage = cause.getMessage();
      if (causeMessage != null && !causeMessage.isBlank())
        out.append("(").append(causeMessage).append(")");
    }
    return out.toString();
  }
}
