package br.com.t4acontrol.backend.mqtt;

import static org.junit.Assert.assertEquals;

import android.os.Looper;
import br.com.t4acontrol.backend.T4AState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class MqttTelemetryCoordinatorTest {
  private FakeTransport transport;
  private MqttTelemetryCoordinator coordinator;

  @Before public void setUp() {
    transport = new FakeTransport();
    coordinator = new MqttTelemetryCoordinator(new FakeStore(enabledConfiguration()), transport, new NoopListener());
  }

  @After public void tearDown() {
    if (coordinator != null) coordinator.stop();
  }

  @Test public void unchangedTelemetryIsNotRepublishedByMaintenance() {
    coordinator.start();
    coordinator.onState(state(true));
    idleMainLooper();
    int published = transport.telemetryPublishes();

    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(35));

    assertEquals(published, transport.telemetryPublishes());
  }

  @Test public void changedTelemetryStillPublishesImmediately() {
    coordinator.start();
    coordinator.onState(state(true));
    idleMainLooper();
    int before = transport.telemetryPublishes();

    coordinator.onState(state(false));
    idleMainLooper();

    assertEquals(before + 1, transport.telemetryPublishes());
  }

  private static MqttConfiguration enabledConfiguration() {
    return new MqttConfiguration(
        true,
        "broker.local",
        1883,
        MqttConfiguration.Transport.TCP,
        "/mqtt",
        "",
        "",
        "t4a-test",
        "t4a/test",
        60,
        false);
  }

  private static T4AState state(boolean connected) {
    return new T4AState(
        true,
        "test@example.com",
        1L,
        "Casa",
        T4AState.Pairing.PAIRED,
        connected,
        "T4A Test",
        "AA:BB:CC:DD:EE:FF",
        -50,
        Collections.emptyMap(),
        Collections.emptyMap(),
        Collections.emptySet(),
        false,
        "medium",
        null,
        null,
        null,
        1,
        null,
        null,
        "");
  }

  private static void idleMainLooper() {
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private static final class FakeStore implements MqttConfigurationStore {
    private MqttConfiguration configuration;
    private long revision = 1L;

    FakeStore(MqttConfiguration configuration) {
      this.configuration = configuration;
    }

    @Override public MqttConfiguration load() { return configuration; }
    @Override public void save(MqttConfiguration value) { configuration = value; revision++; }
    @Override public void clear() { configuration = MqttConfiguration.defaults(); revision++; }
    @Override public long revision() { return revision; }
  }

  private static final class FakeTransport implements MqttTransport {
    private final List<Publication> publications = new ArrayList<>();
    private Listener listener;
    private boolean connected;
    private boolean connecting;

    @Override public void setListener(Listener listener) { this.listener = listener; }

    @Override public void connect(MqttConfiguration configuration, String willTopic, String willPayload) {
      connecting = true;
      connected = true;
      connecting = false;
      if (listener != null) listener.onConnected();
    }

    @Override public void disconnect() { connected = false; connecting = false; }
    @Override public boolean isConnected() { return connected; }
    @Override public boolean isConnecting() { return connecting; }

    @Override public void publish(String topic, String payload, int qos, boolean retained) {
      publications.add(new Publication(topic, payload));
    }

    @Override public void close() {}

    int telemetryPublishes() {
      int count = 0;
      for (Publication publication : publications) {
        if (publication.topic.endsWith("/telemetry")) count++;
      }
      return count;
    }
  }

  private static final class Publication {
    final String topic;
    final String payload;

    Publication(String topic, String payload) {
      this.topic = topic;
      this.payload = payload;
    }
  }

  private static final class NoopListener implements MqttTelemetryCoordinator.Listener {
    @Override public void onMqttEvent(String event) {}
    @Override public void onMqttRawLog(String entry) {}
  }
}
