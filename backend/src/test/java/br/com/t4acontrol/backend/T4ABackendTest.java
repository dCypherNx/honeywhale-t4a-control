package br.com.t4acontrol.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import java.lang.reflect.Field;
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
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class T4ABackendTest {
  private FakeStateStore stateStore;
  private FakeProvisioner provisioner;
  private FakeTransport transport;
  private RecordingListener listener;
  private T4ABackend backend;

  @Before public void setUp() {
    stateStore = new FakeStateStore();
    provisioner = new FakeProvisioner();
    transport = new FakeTransport();
    listener = new RecordingListener();
  }

  @After public void tearDown() {
    if (backend != null) backend.destroy();
  }

  @Test public void startWithoutRestoredAccountStaysUnauthenticated() {
    provisioner.account = null;
    backend = new T4ABackend(stateStore, provisioner, transport, listener);

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
    backend = new T4ABackend(stateStore, provisioner, transport, listener);
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
    assertFalse(state.dps.containsKey("1"));
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
    assertEquals(Boolean.FALSE, stateStore.rememberedLock);
    assertTrue(listener.events.stream().anyMatch(value -> value.contains("Trava confirmada")));
  }

  @Test public void unsolicitedLockRxNeverChangesRememberedState() {
    stateStore.rememberedLock = false;
    startConnected(Collections.emptyMap());

    transport.emitDps(Map.of("1", true));
    idleMainLooper();

    assertEquals(false, listener.latest().dps.get("1"));
    assertEquals(Boolean.FALSE, stateStore.rememberedLock);
    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("LOCK RX ignored unsolicited")));
  }

  @Test public void autoLockPublishesAbsoluteLockCommandAtConfiguredThreshold() {
    stateStore.rememberedLock = true;
    startConnected(Collections.emptyMap());
    transport.rssi = -70;

    backend.setAutoLockEnabled(true);
    pollRssiNow();

    assertEquals(false, transport.lastPublished.get("1"));
    assertTrue(listener.events.stream().anyMatch(value -> value.contains("bloqueando")));
  }

  @Test public void shortAutoUnlocksInsideDisplayedBandAtMinusThirtyEight() {
    stateStore.rememberedLock = false;
    stateStore.autoLockDistance = "short";
    startConnected(Collections.emptyMap());
    backend.setAutoLockEnabled(true);
    int before = transport.publishCalls;
    transport.rssi = -38;

    pollRssiNow();

    assertEquals(before + 1, transport.publishCalls);
    assertEquals(true, transport.lastPublished.get("1"));
    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("AUTO_LOCK action=unlock rssi=-38 threshold=-39")));
  }

  @Test public void longAutoLockRequiresThreeFreshSamplesAtMinusNinety() {
    stateStore.rememberedLock = true;
    stateStore.autoLockDistance = "long";
    startConnected(Collections.emptyMap());
    backend.setAutoLockEnabled(true);
    int before = transport.publishCalls;
    transport.rssi = -90;

    pollRssiNow();
    assertEquals(before, transport.publishCalls);
    pollRssiNow();
    assertEquals(before, transport.publishCalls);
    pollRssiNow();

    assertEquals(before + 1, transport.publishCalls);
    assertEquals(false, transport.lastPublished.get("1"));
    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("longGuard sample=3/3")));
  }

  @Test public void disabledAutoLockNeverPublishesUnlockAcrossReconnectAndNearRssi() {
    stateStore.rememberedLock = false;
    startConnected(Collections.emptyMap());
    backend.setAutoLockEnabled(false);
    int before = transport.publishCalls;

    transport.connected = false;
    transport.rssi = -20;
    backend.setForeground(true);
    idleMainLooper();
    backend.setForeground(true);
    idleMainLooper();

    assertEquals(before, transport.publishCalls);
    assertEquals(false, listener.latest().dps.get("1"));
    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("DISCONNECTED->CONNECTED autoLock=false")));
    assertFalse(listener.raw.stream().anyMatch(value -> value.contains("AUTO_LOCK action=unlock")));
  }

  @Test public void firstLockRxAfterReconnectIsLoggedAndIgnoredWithoutIssuingCommand() {
    stateStore.rememberedLock = false;
    startConnected(Collections.emptyMap());
    backend.setAutoLockEnabled(false);
    int before = transport.publishCalls;

    transport.connected = false;
    backend.setForeground(true);
    idleMainLooper();
    transport.emitDps(Map.of("1", true));
    idleMainLooper();

    assertEquals(before, transport.publishCalls);
    assertEquals(false, listener.latest().dps.get("1"));
    assertEquals(Boolean.FALSE, stateStore.rememberedLock);
    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("firstLockRx=unlocked autoLock=false")));
    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("LOCK RX ignored unsolicited")));
  }

  @Test public void recentSpuriousBatteryHundredDoesNotReplaceLiveSubHundredValue() {
    startConnected(Collections.emptyMap());

    transport.emitDps(Map.of("3", 76));
    idleMainLooper();
    transport.emitDps(Map.of("3", 100));
    idleMainLooper();

    assertEquals(76, listener.latest().dps.get("3"));
    assertEquals(Integer.valueOf(76), listener.latest().batteryObservedMin);
    assertEquals(Integer.valueOf(76), listener.latest().batteryObservedMax);
    assertEquals(Integer.valueOf(76), stateStore.batteryLastLivePercent);
    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("BATTERY RX 100 ignored")));
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
    stateStore.batteryLastLivePercent = percent;
    stateStore.batteryLastLiveAt = System.currentTimeMillis() - 2L * 60L * 60L * 1000L;
  }

  private void startConnected(Map<String, Object> initialDps) {
    provisioner.account = "account";
    T4AContracts.Device device = device(initialDps);
    provisioner.home = new T4AContracts.Home(1L, "Casa", List.of(device));
    transport.cached = device;
    backend = new T4ABackend(stateStore, provisioner, transport, listener);
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

  private void pollRssiNow() {
    try {
      Field lastRssiRead = T4ABackend.class.getDeclaredField("lastRssiRead");
      lastRssiRead.setAccessible(true);
      lastRssiRead.setLong(backend, 0L);
    } catch (ReflectiveOperationException error) {
      throw new AssertionError(error);
    }
    backend.setForeground(true);
    idleMainLooper();
  }

  private static final class FakeStateStore implements T4AStateStore {
    Boolean rememberedLock;
    boolean autoLockEnabled;
    String autoLockDistance;
    String bleAddress = "";
    Integer batteryObservedMin;
    Integer batteryObservedMax;
    Long batteryCycleStartedAt;
    Integer batteryLastLivePercent;
    Long batteryLastLiveAt;
    Integer batteryRechargeMinGapHours;

    @Override public Boolean rememberedLock() { return rememberedLock; }
    @Override public void setRememberedLock(boolean value) { rememberedLock = value; }
    @Override public boolean autoLockEnabled() { return autoLockEnabled; }
    @Override public void setAutoLockEnabled(boolean enabled) { autoLockEnabled = enabled; }
    @Override public String autoLockDistance() { return autoLockDistance; }
    @Override public void setAutoLockDistance(String distance) { autoLockDistance = distance; }
    @Override public String bleAddress() { return bleAddress; }
    @Override public void setBleAddress(String address) { bleAddress = address == null ? "" : address; }
    @Override public void clearBleAddress() { bleAddress = ""; }
    @Override public Integer batteryObservedMin() { return batteryObservedMin; }
    @Override public Integer batteryObservedMax() { return batteryObservedMax; }
    @Override public Long batteryCycleStartedAt() { return batteryCycleStartedAt; }
    @Override public void setBatteryObservation(int min, int max, long startedAt) {
      batteryObservedMin = min;
      batteryObservedMax = max;
      batteryCycleStartedAt = startedAt;
    }
    @Override public Integer batteryLastLivePercent() { return batteryLastLivePercent; }
    @Override public Long batteryLastLiveAt() { return batteryLastLiveAt; }
    @Override public void setBatteryLastLive(int percent, long observedAt) {
      batteryLastLivePercent = percent;
      batteryLastLiveAt = observedAt;
    }
    @Override public Integer batteryRechargeMinGapHours() { return batteryRechargeMinGapHours; }
    @Override public void setBatteryRechargeMinGapHours(int hours) { batteryRechargeMinGapHours = hours; }
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
    int publishCalls;
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
      publishCalls++;
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
