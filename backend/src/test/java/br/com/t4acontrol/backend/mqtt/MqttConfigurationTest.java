package br.com.t4acontrol.backend.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MqttConfigurationTest {
  @Test public void buildsUrisForAllTransports() {
    assertEquals("tcp://broker.local:1883", config(MqttConfiguration.Transport.TCP, 1883, "").serverUri());
    assertEquals("ssl://broker.local:8883", config(MqttConfiguration.Transport.TLS, 8883, "").serverUri());
    assertEquals("ws://broker.local:80/mqtt", config(MqttConfiguration.Transport.WS, 80, "mqtt").serverUri());
    assertEquals("wss://broker.local:443/custom", config(MqttConfiguration.Transport.WSS, 443, "/custom").serverUri());
  }

  @Test public void validatesEnabledConfiguration() {
    assertEquals(MqttConfiguration.ValidationError.NONE, config(MqttConfiguration.Transport.TCP, 1883, "").validate());
    assertEquals(MqttConfiguration.ValidationError.HOST_REQUIRED,
        new MqttConfiguration(true, "", 1883, MqttConfiguration.Transport.TCP, "/mqtt", "", "", "client", "t4a", 60, true).validate());
    assertEquals(MqttConfiguration.ValidationError.PORT_INVALID,
        new MqttConfiguration(true, "host", 0, MqttConfiguration.Transport.TCP, "/mqtt", "", "", "client", "t4a", 60, true).validate());
    assertEquals(MqttConfiguration.ValidationError.CLIENT_ID_REQUIRED,
        new MqttConfiguration(true, "host", 1883, MqttConfiguration.Transport.TCP, "/mqtt", "", "", "", "t4a", 60, true).validate());
    assertEquals(MqttConfiguration.ValidationError.BASE_TOPIC_REQUIRED,
        new MqttConfiguration(true, "host", 1883, MqttConfiguration.Transport.TCP, "/mqtt", "", "", "client", "/", 60, true).validate());
    assertEquals(MqttConfiguration.ValidationError.KEEP_ALIVE_INVALID,
        new MqttConfiguration(true, "host", 1883, MqttConfiguration.Transport.TCP, "/mqtt", "", "", "client", "t4a", 5, true).validate());
  }

  @Test public void disabledConfigurationStillRequiresStructuralValidity() {
    MqttConfiguration disabled = new MqttConfiguration(false, "", 1883, MqttConfiguration.Transport.TCP, "/mqtt", "", "", "", "", 60, false);
    assertEquals(MqttConfiguration.ValidationError.NONE, disabled.validate());
  }

  @Test public void normalizesTopicPathAndTlsProjection() {
    MqttConfiguration ws = new MqttConfiguration(true, " broker ", 80, MqttConfiguration.Transport.WS, "mqtt", "", "", "client", "/t4a/", 60, true);
    assertEquals("broker", ws.host);
    assertEquals("/mqtt", ws.websocketPath);
    assertEquals("t4a", ws.baseTopic);
    assertFalse(ws.tls);

    MqttConfiguration wss = config(MqttConfiguration.Transport.WSS, 443, "/mqtt");
    assertTrue(wss.tls);
  }

  @Test public void restoresTransportFromStoredValueOrLegacyTls() {
    assertEquals(MqttConfiguration.Transport.WSS, MqttConfiguration.Transport.fromStored("wss", false));
    assertEquals(MqttConfiguration.Transport.TLS, MqttConfiguration.Transport.fromStored("unknown", true));
    assertEquals(MqttConfiguration.Transport.TCP, MqttConfiguration.Transport.fromStored(null, false));
  }

  private static MqttConfiguration config(MqttConfiguration.Transport transport, int port, String path) {
    return new MqttConfiguration(true, "broker.local", port, transport, path, "", "", "client", "t4a", 60, true);
  }
}
