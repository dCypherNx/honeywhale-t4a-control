package br.com.t4acontrol.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class T4ABackendTest {
  private Context context;
  private SharedPreferences preferences;
  private FakeProvisioner provisioner;
  private FakeTransport transport;
  private RecordingListener listener;
  private T4ABackend backend;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    preferences = context.getSharedPreferences("t4a_backend", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
    provisioner = new FakeProvisioner();
    transport = new FakeTransport();
    listener = new RecordingListener();
  }

  @After public void tearDown() {
    if (backend != null) backend.destroy();
    preferences.edit().clear().commit();
  }

  @Test public void startWithoutRestoredAccountStaysUnauthenticated() {
    provisioner.account = null;
    backend = new T4ABackend(context, provisioner, transport, listener);

    backend.start();

    assertFalse(listener.latest().authenticated);
    assertEquals("Entre para acessar o T4A", listener.latest().message);
    assertFalse(transport.attached);
  }

  @Test public void restoredSessionAttachesAndConnectsThroughNeutralPorts() {
    startConnected(Collections.emptyMap());

    T4AState state = listener.latest();
    assertTrue(state.authenticated);
    assertEquals(T4AState.Pairing.PAIRED, state.pairing);
    assertTrue(state.connected);
    assertEquals("device-1", transport.attachedDevice.id);
    assertTrue(transport.connectCalls >= 1);
  }

  @Test public void discoveryAndPairingStayBehindProvisionerPort() {
    provisioner.account = "account";
    provisioner.home = new T4AContracts.Home(1L, "Casa", Collections.emptyList());
    backend = new T4ABackend(context, provisioner, transport, listener);
    backend.start();
    idleMainLooper();

    assertEquals(T4AState.Pairing.UNPAIRED, listener.latest().pairing);
    backend.scan();
    assertEquals(T4AState.Pairing.SCANNING, listener.latest().pairing);
    assertNotNull(provisioner.discoveryListener);

    T4AContracts.DiscoveredDevice discovered = new T4AContracts.DiscoveredDevice(
        "discovery-1", "AA:BB:CC:DD:EE:FF", "uuid", "product", 1, false, -55);
    provisioner.discoveryListener.onDevice(discovered);
    idleMainLooper();
    assertEquals(T4AState.Pairing.READY, listener.latest().pairing);

    provisioner.pairResult = device(Collections.emptyMap());
    backend.pair();
    idleMainLooper();

    assertEquals(T4AState.Pairing.PAIRED, listener.latest().pairing);
    assertTrue(transport.attached);
    assertEquals("device-1", transport.attachedDevice.id);
  }

  @Test public void reconnectsWhenTransportReportsDisconnected() {
    startConnected(Collections.emptyMap());
    int before = transport.connectCalls;
    transport.connected = false;

    backend.setForeground(true);
    idleMainLooper();

    assertTrue(transport.connectCalls > before);
    assertTrue(listener.latest().connected);
  }

  @Test public void initialCacheCannotEstablishSpeedBatteryOrLock() {
    Map<String, Object> initial = new HashMap<>();
    initial.put("1", false);
    initial.put("2", 321);
    initial.put("3", 100);
    initial.put("8", true);
    startConnected(initial);

    T4AState state = listener.latest();
    assertFalse(state.dps.containsKey("1"));
    assertFalse(state.dps.containsKey("2"));
    assertFalse(state.dps.containsKey("3"));
    assertEquals(true, state.dps.get("8"));

    transport.emitDps(Map.of("1", false, "2", 123, "3", 77));
    idleMainLooper();

    state = listener.latest();
    assertEquals(false, state.dps.get("1"));
    assertEquals(123, state.dps.get("2"));
    assertEquals(77, state.dps.get("3"));
    assertEquals(Integer.valueOf(77), state.batteryObservedMin);
    assertEquals(Integer.valueOf(77), state.batteryObservedMax);
  }

  @Test public void pendingDpIsClearedOnlyByLiveRx() {
    startConnected(Collections.emptyMap());

    backend.publish("8", true);
    assertTrue(listener.latest().pendingDps.contains("8"));
    assertEquals(true, transport.lastPublished.get("8"));

    transport.emitDps(Map.of("8", true));
    idleMainLooper();

    assertFalse(listener.latest().pendingDps.contains("8"));
    assertEquals(true, listener.latest().dps.get("8"));
  }

  @Test public void staleLockRxIsIgnoredUntilRequestedStateIsConfirmed() {
    startConnected(Collections.emptyMap());

    backend.publish("1", false);
    assertEquals(false, transport.lastPublished.get("1"));

    transport.emitDps(Map.of("1", true));
    idleMainLooper();
    assertEquals(false, listener.latest().dps.get("1"));

    transport.emitDps(Map.of("1", false));
    idleMainLooper();
    assertEquals(false, listener.latest().dps.get("1"));
    assertTrue(listener.events.stream().anyMatch(value -> value.contains("Trava confirmada")));
  }

  @Test public void autoLockPublishesAbsoluteLockCommandAtConfiguredThreshold() {
    startConnected(Collections.emptyMap());
    transport.emitDps(Map.of("1", true));
    idleMainLooper();
    transport.rssi = -70;

    backend.setAutoLockEnabled(true);
    backend.setForeground(true);
    idleMainLooper();

    assertEquals(false, transport.lastPublished.get("1"));
    assertTrue(listener.events.stream().anyMatch(value -> value.contains("bloqueando")));
  }

  @Test public void batteryObservationTracksLiveMinMaxAndDetectsRechargeCandidate() {
    startConnected(Collections.emptyMap());

    transport.emitDps(Map.of("3", 80));
    idleMainLooper();
    transport.emitDps(Map.of("3", 60));
    idleMainLooper();

    assertEquals(Integer.valueOf(60), listener.latest().batteryObservedMin);
    assertEquals(Integer.valueOf(80), listener.latest().batteryObservedMax);

    forceRechargeGapFrom(60);
    transport.emitDps(Map.of("3", 80));
    idleMainLooper();

    assertEquals(Integer.valueOf(80), listener.latest().pendingBatteryRechargePercent);
    assertNotNull(listener.latest().pendingBatteryRechargeDetectedAt);

    backend.resolveBatteryRecharge(true);
    assertNull(listener.latest().pendingBatteryRechargePercent);
    assertEquals(Integer.valueOf(80), listener.latest().batteryObservedMin);
    assertEquals(Integer.valueOf(80), listener.latest().batteryObservedMax);
  }

  @Test public void rejectedRechargeCandidatePreservesCurrentCycle() {
    startConnected(Collections.emptyMap());
    transport.emitDps(Map.of("3", 80));
    idleMainLooper();
    transport.emitDps(Map.of("3", 60));
    idleMainLooper();

    forceRechargeGapFrom(60);
    transport.emitDps(Map.of("3", 80));
    idleMainLooper();
    assertNotNull(listener.latest().pendingBatteryRechargePercent);

    backend.resolveBatteryRecharge(false);

    assertNull(listener.latest().pendingBatteryRechargePercent);
    assertEquals(Integer.valueOf(60), listener.latest().batteryObservedMin);
    assertEquals(Integer.valueOf(80), listener.latest().batteryObservedMax);
  }

  private void forceRechargeGapFrom(int percent) {
    preferences.edit()
        .putInt("battery_last_live_percent", percent)
        .putLong("battery_last_live_at", System.currentTimeMillis() - 2L * 60L * 60L * 1000L)
        .commit();
  }

  private void startConnected(Map<String, Object> initialDps) {
    provisioner.account = "account";
    T4AContracts.Device device = device(initialDps);
    provisioner.home = new T4AContracts.Home(1L, "Casa", List.of(device));
    transport.cached = device;
    backend = new T4ABackend(context, provisioner, transport, listener);
    backend.start();
    idleMainLooper();
  }

  private static T4AContracts.Device device(Map<String, Object> dps) {
    return new T4AContracts.Device(
        "device-1", "T4A Test", "AA:BB:CC:DD:EE:FF", "uuid", dps, Collections.emptyMap());
  }

  private static void idleMainLooper() {
    Shadows.shadowOf(Looper.getMainLooper()).idle();
  }

  private static final class RecordingListener implements T4ABackend.Listener {
    final List<T4AState> states = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    final List<String> raw = new ArrayList<>();

    @Override public void onState(T4AState state) { states.add(state); }
    @Override public void onEvent(String event) { events.add(event); }
    @Override public void onRawLog(String entry) { raw.add(entry); }

    T4AState latest() {
      assertFalse("Backend emitted no state", states.isEmpty());
      return states.get(states.size() - 1);
    }
  }

  private static final class FakeProvisioner implements T4AProvisioner {
    String account;
    T4AContracts.Home home;
    T4AContracts.DiscoveryListener discoveryListener;
    T4AContracts.Device pairResult;

    @Override public String currentAccount() { return account; }
    @Override public void login(String countryCode, String email, String password, T4AContracts.Callback<String> callback) { callback.onSuccess(email); }
    @Override public void loadPrimaryHome(T4AContracts.Callback<T4AContracts.Home> callback) { callback.onSuccess(home); }
    @Override public void startDiscovery(long timeoutMs, T4AContracts.DiscoveryListener listener) { discoveryListener = listener; }
    @Override public void stopDiscovery() {}
    @Override public void pair(long homeId, T4AContracts.DiscoveredDevice device, T4AContracts.Callback<T4AContracts.Device> callback) {
      if (pairResult == null) callback.onError("missing", "No pair result configured");
      else callback.onSuccess(pairResult);
    }
    @Override public void remove(String deviceId, T4AContracts.ResultCallback callback) { callback.onSuccess(); }
    @Override public void destroy() {}
  }

  private static final class FakeTransport implements T4ATransport {
    boolean attached;
    boolean connected;
    int connectCalls;
    int rssi = -60;
    T4AContracts.Device attachedDevice;
    T4AContracts.Device cached;
    T4AContracts.DeviceListener listener;
    Map<String, Object> lastPublished = Collections.emptyMap();

    @Override public void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener) {
      attached = true;
      attachedDevice = device;
      this.listener = listener;
    }
    @Override public void detach() { attached = false; }
    @Override public void connect(T4AContracts.Device device) {
      connectCalls++;
      connected = true;
      if (listener != null) listener.onConnectionChanged(device.id, true);
    }
    @Override public boolean isConnected(String deviceId) { return connected; }
    @Override public T4AContracts.Device cachedDevice(String deviceId) { return cached; }
    @Override public void publish(String deviceId, Map<String, Object> dps, T4AContracts.ResultCallback callback) {
      lastPublished = new HashMap<>(dps);
      callback.onSuccess();
    }
    @Override public void readRssi(String mac, T4AContracts.RssiCallback callback) { callback.onResult(true, rssi); }
    @Override public void destroy() {}

    void emitDps(Map<String, Object> dps) {
      assertNotNull(listener);
      listener.onDpUpdate(attachedDevice.id, dps);
    }
  }
}
