package br.com.t4acontrol.backend.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import br.com.t4acontrol.backend.T4AState;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class HomeAssistantDiscoveryTest {
  @Test public void normalizesMacIntoStableDiscoveryTopic() {
    assertEquals("homeassistant/device/t4a_aabbccddeeff/config", HomeAssistantDiscovery.topic(state("aa-bb-cc-dd-ee-ff", "Meu T4A")));
    assertEquals("", HomeAssistantDiscovery.topic(state("", "Meu T4A")));
  }

  @Test public void payloadUsesStableDeviceIdentityAndConfiguredTopics() {
    String payload = HomeAssistantDiscovery.payload(configuration(), state("aa-bb-cc-dd-ee-ff", "Meu T4A"));
    JSONObject root = JSON.parseObject(payload);
    JSONObject device = root.getJSONObject("device");

    assertEquals("Meu T4A", device.getString("name"));
    assertEquals("AA:BB:CC:DD:EE:FF", device.getString("serial_number"));
    assertTrue(device.getJSONArray("identifiers").contains("t4a_aabbccddeeff"));
    assertEquals("t4a/telemetry", root.getString("state_topic"));
    assertEquals("t4a/status", root.getString("availability_topic"));

    JSONObject components = root.getJSONObject("components");
    assertTrue(components.containsKey("battery"));
    assertTrue(components.containsKey("speed"));
    assertTrue(components.containsKey("odometer"));
    assertTrue(components.containsKey("trip"));
    assertTrue(components.containsKey("ride_time"));
    assertTrue(components.containsKey("rssi_range"));
    assertTrue(components.containsKey("connected"));
    assertTrue(components.containsKey("locked"));
    assertTrue(components.containsKey("headlight"));
    assertTrue(components.containsKey("cruise"));
    assertTrue(components.containsKey("mode"));
    assertTrue(components.containsKey("start_mode"));
    assertTrue(components.containsKey("unit"));
    assertTrue(components.containsKey("location"));
    assertEquals("t4a/location", components.getJSONObject("location").getString("state_topic"));
  }

  @Test public void missingMacProducesNoDiscoveryPayload() {
    assertEquals("", HomeAssistantDiscovery.payload(configuration(), state("", "T4A")));
  }

  @Test public void payloadDoesNotInventSerialForInvalidMac() {
    JSONObject root = JSON.parseObject(HomeAssistantDiscovery.payload(configuration(), state("device-xyz", "T4A")));
    JSONObject device = root.getJSONObject("device");
    assertEquals("DEVICE-XYZ", device.getString("serial_number"));
    assertFalse(device.getString("serial_number").contains(":"));
  }

  private static MqttConfiguration configuration() {
    return new MqttConfiguration(true, "broker", 1883, MqttConfiguration.Transport.TCP, "/mqtt", "", "", "client", "t4a", 60, true);
  }

  private static T4AState state(String mac, String name) {
    Map<String, Object> dps = new HashMap<>();
    return new T4AState(
        true, "account", 1L, "home", T4AState.Pairing.PAIRED, true, name, mac, -60,
        dps, Collections.emptyMap(), Collections.emptySet(), false, "medium",
        null, null, null, 1, null, null, "");
  }
}
