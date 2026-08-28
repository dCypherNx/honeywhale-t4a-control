package br.com.t4acontrol.backend.mqtt;

/** Persistence boundary for MQTT configuration. UI code must not depend on its implementation. */
public interface MqttConfigurationStore {
  MqttConfiguration load();

  void save(MqttConfiguration configuration);

  void clear();
}
