package br.com.t4acontrol.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.os.Looper;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class T4AAutoLockBandTest {
  @Test public void shortBandUnlocksAtMinus38() {
    assertUnlocksWhenSignalReentersBand("short", -38, -39);
  }

  @Test public void mediumBandUnlocksAtMinus64() {
    assertUnlocksWhenSignalReentersBand("medium", -64, -64);
  }

  @Test public void longBandUnlocksAtMinus89() {
    assertUnlocksWhenSignalReentersBand("long", -89, -89);
  }

  private void assertUnlocksWhenSignalReentersBand(String distance, int rssi, int expectedUnlockAt) {
    FakeStateStore stateStore = new FakeStateStore();
    stateStore.rememberedLock = false;
    stateStore.autoLockEnabled = true;
    stateStore.autoLockDistance = distance;

    T4AContracts.Device device = new T4AContracts.Device(
        "device-1",
        "T4A Test",
        "AA:BB:CC:DD:EE:FF",
        "uuid",
        Collections.emptyMap(),
        Collections.emptyMap());
    FakeProvisioner provisioner = new FakeProvisioner(device);
    FakeTransport transport = new FakeTransport(device, rssi);
    RecordingListener listener = new RecordingListener();
    T4ABackend backend = new T4ABackend(stateStore, provisioner, transport, listener);

    try {
      backend.start();
      Shadows.shadowOf(Looper.getMainLooper()).idle();

      assertEquals(1, transport.publishCalls);
      assertEquals(Boolean.TRUE, transport.lastPublished.get("1"));
      assertTrue(listener.raw.stream().anyMatch(value -> value.contains("unlockAt=" + expectedUnlockAt)));
      assertTrue(listener.raw.stream().anyMatch(value -> value.contains("AUTO_LOCK action=unlock")));
    } finally {
      backend.destroy();
    }
  }

  private static final class FakeStateStore implements T4AStateStore {
    Boolean rememberedLock;
    boolean autoLockEnabled;
    String autoLockDistance;
    String bleAddress = "";

    @Override public Boolean rememberedLock() { return rememberedLock; }
    @Override public void setRememberedLock(boolean value) { rememberedLock = value; }
    @Override public boolean autoLockEnabled() { return autoLockEnabled; }
    @Override public void setAutoLockEnabled(boolean enabled) { autoLockEnabled = enabled; }
    @Override public String autoLockDistance() { return autoLockDistance; }
    @Override public void setAutoLockDistance(String distance) { autoLockDistance = distance; }
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

  private static final class FakeProvisioner implements T4AProvisioner {
    final T4AContracts.Home home;

    FakeProvisioner(T4AContracts.Device device) {
      home = new T4AContracts.Home(1L, "Casa", List.of(device));
    }

    @Override public String currentAccount() { return "account"; }
    @Override public void login(String countryCode, String email, String password, T4AContracts.Callback<String> callback) {
      callback.onSuccess(email);
    }
    @Override public void loadPrimaryHome(T4AContracts.Callback<T4AContracts.Home> callback) { callback.onSuccess(home); }
    @Override public void startDiscovery(long timeoutMs, T4AContracts.DiscoveryListener listener) {}
    @Override public void stopDiscovery() {}
    @Override public void pair(long homeId, T4AContracts.DiscoveredDevice device, T4AContracts.Callback<T4AContracts.Device> callback) {}
    @Override public void remove(String deviceId, T4AContracts.ResultCallback callback) { callback.onSuccess(); }
    @Override public void destroy() {}
  }

  private static final class FakeTransport implements T4ATransport {
    final T4AContracts.Device device;
    final int rssi;
    T4AContracts.DeviceListener listener;
    int publishCalls;
    Map<String, Object> lastPublished = Collections.emptyMap();
    boolean connected;

    FakeTransport(T4AContracts.Device device, int rssi) {
      this.device = device;
      this.rssi = rssi;
    }

    @Override public void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener) { this.listener = listener; }
    @Override public void detach() {}
    @Override public void connect(T4AContracts.Device device) {
      connected = true;
      if (listener != null) listener.onConnectionChanged(device.id, true);
    }
    @Override public boolean isConnected(String deviceId) { return connected; }
    @Override public T4AContracts.Device cachedDevice(String deviceId) { return device; }
    @Override public void publish(String deviceId, Map<String, Object> dps, T4AContracts.ResultCallback callback) {
      publishCalls++;
      lastPublished = new HashMap<>(dps);
      callback.onSuccess();
    }
    @Override public void readRssi(String mac, T4AContracts.RssiCallback callback) { callback.onResult(true, rssi); }
    @Override public void destroy() {}
  }

  private static final class RecordingListener implements T4ABackend.Listener {
    final java.util.ArrayList<String> raw = new java.util.ArrayList<>();

    @Override public void onState(T4AState state) {}
    @Override public void onEvent(String event) {}
    @Override public void onRawLog(String entry) { raw.add(entry); }
  }
}
