package br.com.t4acontrol.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class T4ALifecycleLoggingTest {
  @Test public void pairLogsRequestedAndSuccess() {
    FakeProvisioner provisioner = new FakeProvisioner();
    provisioner.account = "account";
    provisioner.home = new T4AContracts.Home(1L, "Casa", Collections.emptyList());
    provisioner.pairResult = device();
    FakeTransport transport = new FakeTransport();
    RecordingListener listener = new RecordingListener();
    T4ABackend backend = backend(provisioner, transport, listener);

    backend.start();
    backend.scan();
    provisioner.discoveryListener.onDevice(discovered());
    backend.pair();

    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("[APP] PAIR requested")));
    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("address=AA:BB:CC:DD:EE:FF")));
    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("[APP] PAIR success deviceId=device-1")));
    assertEquals(T4AState.Pairing.PAIRED, listener.latest().pairing);
    backend.destroy();
  }

  @Test public void pairLogsError() {
    FakeProvisioner provisioner = new FakeProvisioner();
    provisioner.account = "account";
    provisioner.home = new T4AContracts.Home(1L, "Casa", Collections.emptyList());
    provisioner.pairError = true;
    FakeTransport transport = new FakeTransport();
    RecordingListener listener = new RecordingListener();
    T4ABackend backend = backend(provisioner, transport, listener);

    backend.start();
    backend.scan();
    provisioner.discoveryListener.onDevice(discovered());
    backend.pair();

    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("[APP] PAIR error code=PAIR_FAILED")));
    assertEquals(T4AState.Pairing.READY, listener.latest().pairing);
    backend.destroy();
  }

  @Test public void unpairLogsRequestedAndSuccess() {
    FakeProvisioner provisioner = pairedProvisioner();
    FakeTransport transport = new FakeTransport();
    RecordingListener listener = new RecordingListener();
    T4ABackend backend = backend(provisioner, transport, listener);

    backend.start();
    backend.unpair();

    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("[APP] UNPAIR requested deviceId=device-1 connected=true")));
    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("[APP] UNPAIR success deviceId=device-1")));
    assertEquals(T4AState.Pairing.UNPAIRED, listener.latest().pairing);
    backend.destroy();
  }

  @Test public void unpairLogsErrorAndPreservesPairing() {
    FakeProvisioner provisioner = pairedProvisioner();
    provisioner.removeError = true;
    FakeTransport transport = new FakeTransport();
    RecordingListener listener = new RecordingListener();
    T4ABackend backend = backend(provisioner, transport, listener);

    backend.start();
    backend.unpair();

    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("[APP] UNPAIR requested deviceId=device-1 connected=true")));
    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("[APP] UNPAIR error deviceId=device-1 code=REMOVE_FAILED")));
    assertEquals(T4AState.Pairing.PAIRED, listener.latest().pairing);
    backend.destroy();
  }

  private static T4ABackend backend(
      FakeProvisioner provisioner, FakeTransport transport, RecordingListener listener) {
    return new T4ABackend(
        new FakeStateStore(), provisioner, transport, listener, () -> 1_700_000_000_000L,
        new ImmediateScheduler());
  }

  private static FakeProvisioner pairedProvisioner() {
    FakeProvisioner provisioner = new FakeProvisioner();
    provisioner.account = "account";
    provisioner.home = new T4AContracts.Home(1L, "Casa", List.of(device()));
    return provisioner;
  }

  private static T4AContracts.Device device() {
    return new T4AContracts.Device(
        "device-1", "T4A Test", "AA:BB:CC:DD:EE:FF", "uuid", Collections.emptyMap(),
        Collections.emptyMap());
  }

  private static T4AContracts.DiscoveredDevice discovered() {
    return new T4AContracts.DiscoveredDevice(
        "discovery-1", "AA:BB:CC:DD:EE:FF", "uuid", "product", 1, false, -55);
  }

  private static final class RecordingListener implements T4ABackend.Listener {
    final List<T4AState> states = new ArrayList<>();
    final List<String> raw = new ArrayList<>();
    @Override public void onState(T4AState state) { states.add(state); }
    @Override public void onEvent(String event) {}
    @Override public void onRawLog(String entry) { raw.add(entry); }
    T4AState latest() { return states.get(states.size() - 1); }
  }

  private static final class ImmediateScheduler implements Scheduler {
    @Override public void post(Runnable task) { task.run(); }
    @Override public void postDelayed(Runnable task, long delayMillis) {}
    @Override public void cancel(Runnable task) {}
    @Override public void cancelAll() {}
  }

  private static final class FakeProvisioner implements T4AProvisioner {
    String account;
    T4AContracts.Home home;
    T4AContracts.DiscoveryListener discoveryListener;
    T4AContracts.Device pairResult;
    boolean pairError;
    boolean removeError;
    @Override public String currentAccount() { return account; }
    @Override public void login(String countryCode, String email, String password, T4AContracts.Callback<String> callback) { callback.onSuccess(email); }
    @Override public void loadPrimaryHome(T4AContracts.Callback<T4AContracts.Home> callback) { callback.onSuccess(home); }
    @Override public void startDiscovery(long timeoutMs, T4AContracts.DiscoveryListener listener) { discoveryListener = listener; }
    @Override public void stopDiscovery() {}
    @Override public void pair(long homeId, T4AContracts.DiscoveredDevice device, T4AContracts.Callback<T4AContracts.Device> callback) {
      if (pairError) callback.onError("PAIR_FAILED", "pair failed");
      else callback.onSuccess(pairResult);
    }
    @Override public void remove(String deviceId, T4AContracts.ResultCallback callback) {
      if (removeError) callback.onError("REMOVE_FAILED", "remove failed");
      else callback.onSuccess();
    }
    @Override public void destroy() {}
  }

  private static final class FakeTransport implements T4ATransport {
    boolean connected;
    T4AContracts.Device cached;
    T4AContracts.DeviceListener listener;
    @Override public void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener) {
      this.listener = listener;
      cached = device;
    }
    @Override public void detach() { connected = false; }
    @Override public void connect(T4AContracts.Device device) {
      connected = true;
      if (listener != null) listener.onConnectionChanged(device.id, true);
    }
    @Override public boolean isConnected(String deviceId) { return connected; }
    @Override public T4AContracts.Device cachedDevice(String deviceId) { return cached; }
    @Override public void publish(String deviceId, Map<String, Object> dps, T4AContracts.ResultCallback callback) { callback.onSuccess(); }
    @Override public void readRssi(String mac, T4AContracts.RssiCallback callback) { callback.onResult(true, -55); }
    @Override public void destroy() {}
  }

  private static final class FakeStateStore implements T4AStateStore {
    String bleAddress = "";
    @Override public Boolean rememberedLock() { return null; }
    @Override public void setRememberedLock(boolean value) {}
    @Override public boolean autoLockEnabled() { return false; }
    @Override public void setAutoLockEnabled(boolean enabled) {}
    @Override public String autoLockDistance() { return "medium"; }
    @Override public void setAutoLockDistance(String distance) {}
    @Override public Integer rssiObservedBest() { return null; }
    @Override public Integer rssiObservedWorst() { return null; }
    @Override public void setRssiObservation(int best, int worst) {}
    @Override public String bleAddress() { return bleAddress; }
    @Override public void setBleAddress(String address) { bleAddress = address == null ? "" : address; }
    @Override public void clearBleAddress() { bleAddress = ""; }
    @Override public Integer batteryObservedMin() { return null; }
    @Override public Integer batteryObservedMax() { return null; }
    @Override public Long batteryCycleStartedAt() { return null; }
    @Override public void setBatteryObservation(int min, int max, long startedAt) {}
    @Override public Integer batteryLastLivePercent() { return null; }
    @Override public Long batteryLastLiveAt() { return null; }
    @Override public void setBatteryLastLive(int percent, long observedAt) {}
    @Override public Integer batteryRechargeMinGapHours() { return null; }
    @Override public void setBatteryRechargeMinGapHours(int hours) {}
  }
}
