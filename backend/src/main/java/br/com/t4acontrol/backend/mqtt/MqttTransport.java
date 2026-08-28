package br.com.t4acontrol.backend.mqtt;

/** Provider-neutral MQTT transport boundary. MQTT library types must not cross this interface. */
public interface MqttTransport {
  interface Listener {
    void onConnected();

    void onDisconnected(String reason);

    void onError(String operation, String reason);
  }

  void setListener(Listener listener);

  void connect(MqttConfiguration configuration, String willTopic, String willPayload);

  void disconnect();

  boolean isConnected();

  void publish(String topic, String payload, int qos, boolean retained);

  void close();
}
