package br.com.t4acontrol.mqtt;

import br.com.t4acontrol.backend.mqtt.MqttConfiguration;
import br.com.t4acontrol.backend.mqtt.MqttConfigurationStore;

/** Application-layer adapter between the UI contract and backend configuration persistence. */
public final class DefaultMqttSettings implements MqttSettings {
  private final MqttConfigurationStore store;

  public DefaultMqttSettings(MqttConfigurationStore store) {
    this.store = store;
  }

  @Override
  public Snapshot load() {
    return snapshot(store.load());
  }

  @Override
  public SaveResult save(Draft draft) {
    if (draft == null) return SaveResult.failure(Error.STORAGE_ERROR);

    Integer port = integer(draft.port);
    if (port == null) return SaveResult.failure(Error.PORT_INVALID);
    Integer keepAlive = integer(draft.keepAliveSeconds);
    if (keepAlive == null) return SaveResult.failure(Error.KEEP_ALIVE_INVALID);
    MqttConfiguration.Transport transport = transport(draft.transport);
    if (transport == null) return SaveResult.failure(Error.TRANSPORT_INVALID);

    MqttConfiguration configuration =
        new MqttConfiguration(
            draft.enabled,
            draft.host,
            port,
            transport,
            draft.websocketPath,
            draft.username,
            draft.password,
            draft.clientId,
            draft.baseTopic,
            keepAlive,
            draft.discoveryEnabled);

    Error error = map(configuration.validate());
    if (error != Error.NONE) return SaveResult.failure(error);

    try {
      store.save(configuration);
      return SaveResult.success(snapshot(configuration));
    } catch (RuntimeException ignored) {
      return SaveResult.failure(Error.STORAGE_ERROR);
    }
  }

  private Snapshot snapshot(MqttConfiguration value) {
    return new Snapshot(
        value.enabled,
        value.host,
        String.valueOf(value.port),
        value.transport.name(),
        value.websocketPath,
        value.username,
        value.password,
        value.clientId,
        value.baseTopic,
        String.valueOf(value.keepAliveSeconds),
        value.discoveryEnabled);
  }

  private Error map(MqttConfiguration.ValidationError error) {
    return switch (error) {
      case HOST_REQUIRED -> Error.HOST_REQUIRED;
      case PORT_INVALID -> Error.PORT_INVALID;
      case TRANSPORT_INVALID -> Error.TRANSPORT_INVALID;
      case WEBSOCKET_PATH_INVALID -> Error.WEBSOCKET_PATH_INVALID;
      case CLIENT_ID_REQUIRED -> Error.CLIENT_ID_REQUIRED;
      case BASE_TOPIC_REQUIRED -> Error.BASE_TOPIC_REQUIRED;
      case KEEP_ALIVE_INVALID -> Error.KEEP_ALIVE_INVALID;
      default -> Error.NONE;
    };
  }

  private MqttConfiguration.Transport transport(String value) {
    try {
      return MqttConfiguration.Transport.valueOf(value == null ? "" : value.trim().toUpperCase());
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }

  private Integer integer(String value) {
    try {
      return Integer.parseInt(value == null ? "" : value.trim());
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}
