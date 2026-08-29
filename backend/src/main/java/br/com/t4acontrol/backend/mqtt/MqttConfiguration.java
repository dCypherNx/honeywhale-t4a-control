package br.com.t4acontrol.backend.mqtt;

/** Immutable MQTT connection configuration independent from Android UI and MQTT client libraries. */
public final class MqttConfiguration {
  public static final int DEFAULT_PORT = 1883;
  public static final int DEFAULT_KEEP_ALIVE_SECONDS = 60;
  public static final String DEFAULT_WEBSOCKET_PATH = "/mqtt";

  public enum Transport {
    TCP("tcp", 1883, false),
    TLS("ssl", 8883, false),
    WS("ws", 80, true),
    WSS("wss", 443, true);

    public final String scheme;
    public final int defaultPort;
    public final boolean websocket;

    Transport(String scheme, int defaultPort, boolean websocket) {
      this.scheme = scheme;
      this.defaultPort = defaultPort;
      this.websocket = websocket;
    }

    public static Transport fromStored(String value, boolean legacyTls) {
      if (value != null && !value.isBlank()) {
        try {
          return Transport.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
          // Fall through to the legacy TLS flag for pre-transport configurations.
        }
      }
      return legacyTls ? TLS : TCP;
    }
  }

  public enum ValidationError {
    NONE,
    HOST_REQUIRED,
    PORT_INVALID,
    TRANSPORT_INVALID,
    WEBSOCKET_PATH_INVALID,
    CLIENT_ID_REQUIRED,
    BASE_TOPIC_REQUIRED,
    KEEP_ALIVE_INVALID
  }

  public final boolean enabled;
  public final String host;
  public final int port;
  public final Transport transport;
  public final String websocketPath;
  /** Compatibility projection for code/configuration created before explicit transports existed. */
  public final boolean tls;
  public final String username;
  public final String password;
  public final String clientId;
  public final String baseTopic;
  public final int keepAliveSeconds;
  public final boolean discoveryEnabled;

  public MqttConfiguration(
      boolean enabled,
      String host,
      int port,
      Transport transport,
      String websocketPath,
      String username,
      String password,
      String clientId,
      String baseTopic,
      int keepAliveSeconds,
      boolean discoveryEnabled) {
    this.enabled = enabled;
    this.host = clean(host);
    this.port = port;
    this.transport = transport == null ? Transport.TCP : transport;
    this.websocketPath = normalizeWebSocketPath(websocketPath);
    this.tls = this.transport == Transport.TLS || this.transport == Transport.WSS;
    this.username = clean(username);
    this.password = password == null ? "" : password;
    this.clientId = clean(clientId);
    this.baseTopic = normalizeTopic(baseTopic);
    this.keepAliveSeconds = keepAliveSeconds;
    this.discoveryEnabled = discoveryEnabled;
  }

  /** Constructor retained for callers created before Home Assistant discovery was configurable. */
  public MqttConfiguration(
      boolean enabled,
      String host,
      int port,
      Transport transport,
      String websocketPath,
      String username,
      String password,
      String clientId,
      String baseTopic,
      int keepAliveSeconds) {
    this(
        enabled,
        host,
        port,
        transport,
        websocketPath,
        username,
        password,
        clientId,
        baseTopic,
        keepAliveSeconds,
        false);
  }

  /** Legacy constructor retained so older callers migrate to TCP/TLS without a breaking change. */
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
    this(
        enabled,
        host,
        port,
        tls ? Transport.TLS : Transport.TCP,
        DEFAULT_WEBSOCKET_PATH,
        username,
        password,
        clientId,
        baseTopic,
        keepAliveSeconds,
        false);
  }

  public static MqttConfiguration defaults() {
    return new MqttConfiguration(
        false,
        "",
        DEFAULT_PORT,
        Transport.TCP,
        DEFAULT_WEBSOCKET_PATH,
        "",
        "",
        "t4a-control",
        "t4a",
        DEFAULT_KEEP_ALIVE_SECONDS,
        false);
  }

  public ValidationError validate() {
    if (transport == null) return ValidationError.TRANSPORT_INVALID;
    if (port < 1 || port > 65535) return ValidationError.PORT_INVALID;
    if (transport.websocket && !websocketPath.startsWith("/"))
      return ValidationError.WEBSOCKET_PATH_INVALID;
    if (keepAliveSeconds < 10 || keepAliveSeconds > 3600)
      return ValidationError.KEEP_ALIVE_INVALID;
    if (!enabled) return ValidationError.NONE;
    if (host.isEmpty()) return ValidationError.HOST_REQUIRED;
    if (clientId.isEmpty()) return ValidationError.CLIENT_ID_REQUIRED;
    if (baseTopic.isEmpty()) return ValidationError.BASE_TOPIC_REQUIRED;
    return ValidationError.NONE;
  }

  public String serverUri() {
    String path = transport.websocket ? websocketPath : "";
    return transport.scheme + "://" + host + ":" + port + path;
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

  private static String normalizeWebSocketPath(String value) {
    String path = clean(value);
    if (path.isEmpty()) return DEFAULT_WEBSOCKET_PATH;
    return path.startsWith("/") ? path : "/" + path;
  }
}
