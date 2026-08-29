package br.com.t4acontrol.mqtt;

/** UI-facing MQTT settings contract. Android widgets never depend on persistence or MQTT libraries. */
public interface MqttSettings {
  enum Error {
    NONE,
    HOST_REQUIRED,
    PORT_INVALID,
    TRANSPORT_INVALID,
    WEBSOCKET_PATH_INVALID,
    CLIENT_ID_REQUIRED,
    BASE_TOPIC_REQUIRED,
    KEEP_ALIVE_INVALID,
    STORAGE_ERROR
  }

  final class Snapshot {
    public final boolean enabled;
    public final String host;
    public final String port;
    public final String transport;
    public final String websocketPath;
    public final String username;
    public final String password;
    public final String clientId;
    public final String baseTopic;
    public final String keepAliveSeconds;

    public Snapshot(
        boolean enabled,
        String host,
        String port,
        String transport,
        String websocketPath,
        String username,
        String password,
        String clientId,
        String baseTopic,
        String keepAliveSeconds) {
      this.enabled = enabled;
      this.host = host;
      this.port = port;
      this.transport = transport;
      this.websocketPath = websocketPath;
      this.username = username;
      this.password = password;
      this.clientId = clientId;
      this.baseTopic = baseTopic;
      this.keepAliveSeconds = keepAliveSeconds;
    }
  }

  final class Draft {
    public final boolean enabled;
    public final String host;
    public final String port;
    public final String transport;
    public final String websocketPath;
    public final String username;
    public final String password;
    public final String clientId;
    public final String baseTopic;
    public final String keepAliveSeconds;

    public Draft(
        boolean enabled,
        String host,
        String port,
        String transport,
        String websocketPath,
        String username,
        String password,
        String clientId,
        String baseTopic,
        String keepAliveSeconds) {
      this.enabled = enabled;
      this.host = host;
      this.port = port;
      this.transport = transport;
      this.websocketPath = websocketPath;
      this.username = username;
      this.password = password;
      this.clientId = clientId;
      this.baseTopic = baseTopic;
      this.keepAliveSeconds = keepAliveSeconds;
    }
  }

  final class SaveResult {
    public final boolean saved;
    public final Error error;
    public final Snapshot snapshot;

    private SaveResult(boolean saved, Error error, Snapshot snapshot) {
      this.saved = saved;
      this.error = error;
      this.snapshot = snapshot;
    }

    public static SaveResult success(Snapshot snapshot) {
      return new SaveResult(true, Error.NONE, snapshot);
    }

    public static SaveResult failure(Error error) {
      return new SaveResult(false, error, null);
    }
  }

  Snapshot load();

  SaveResult save(Draft draft);
}
