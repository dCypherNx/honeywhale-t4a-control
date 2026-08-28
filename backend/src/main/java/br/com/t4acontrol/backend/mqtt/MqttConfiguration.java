package br.com.t4acontrol.backend.mqtt;

/** Immutable MQTT connection configuration independent from Android UI and MQTT client libraries. */
public final class MqttConfiguration {
  public static final int DEFAULT_PORT = 1883;
  public static final int DEFAULT_KEEP_ALIVE_SECONDS = 60;

  public enum ValidationError {
    NONE,
    HOST_REQUIRED,
    PORT_INVALID,
    CLIENT_ID_REQUIRED,
    BASE_TOPIC_REQUIRED,
    KEEP_ALIVE_INVALID
  }

  public final boolean enabled;
  public final String host;
  public final int port;
  public final boolean tls;
  public final String username;
  public final String password;
  public final String clientId;
  public final String baseTopic;
  public final int keepAliveSeconds;

  public MqttConfiguration(
      boolean enabled,
      String host,
      int port,
      boolean tls,
      String username,
      String password,
      String clientId,
      String baseTopic,
      int keepAliveSeconds) {
    this.enabled = enabled;
    this.host = clean(host);
    this.port = port;
    this.tls = tls;
    this.username = clean(username);
    this.password = password == null ? "" : password;
    this.clientId = clean(clientId);
    this.baseTopic = normalizeTopic(baseTopic);
    this.keepAliveSeconds = keepAliveSeconds;
  }

  public static MqttConfiguration defaults() {
    return new MqttConfiguration(
        false,
        "",
        DEFAULT_PORT,
        false,
        "",
        "",
        "t4a-control",
        "t4a",
        DEFAULT_KEEP_ALIVE_SECONDS);
  }

  public ValidationError validate() {
    if (port < 1 || port > 65535) return ValidationError.PORT_INVALID;
    if (keepAliveSeconds < 10 || keepAliveSeconds > 3600)
      return ValidationError.KEEP_ALIVE_INVALID;
    if (!enabled) return ValidationError.NONE;
    if (host.isEmpty()) return ValidationError.HOST_REQUIRED;
    if (clientId.isEmpty()) return ValidationError.CLIENT_ID_REQUIRED;
    if (baseTopic.isEmpty()) return ValidationError.BASE_TOPIC_REQUIRED;
    return ValidationError.NONE;
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }

  private static String normalizeTopic(String value) {
    String topic = clean(value);
    while (topic.startsWith("/")) topic = topic.substring(1);
    while (topic.endsWith("/")) topic = topic.substring(0, topic.length() - 1);
    return topic;
  }
}
