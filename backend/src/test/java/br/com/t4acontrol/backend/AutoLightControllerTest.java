package br.com.t4acontrol.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class AutoLightControllerTest {
  @Test
  public void darkConditionRequiresFiveContinuousSeconds() {
    FakeStore store = new FakeStore();
    FakeScheduler scheduler = new FakeScheduler();
    RecordingListener listener = new RecordingListener();
    AutoLightController controller = new AutoLightController(store, scheduler, listener);
    learnRange(controller);
    controller.setControlAvailable(true);
    controller.setLightState(false);
    controller.setEnabled(true);

    controller.onLux(20f);
    assertEquals(AutoLightController.DARK_HOLD_MS, scheduler.delayMillis);
    assertTrue(listener.commands.isEmpty());

    scheduler.runDelayed();
    assertEquals(1, listener.commands.size());
    assertTrue(listener.commands.get(0));
  }

  @Test
  public void screenOffCancelsPendingTurnOnAndHidesCurrentLux() {
    FakeStore store = new FakeStore();
    FakeScheduler scheduler = new FakeScheduler();
    RecordingListener listener = new RecordingListener();
    AutoLightController controller = new AutoLightController(store, scheduler, listener);
    learnRange(controller);
    controller.setControlAvailable(true);
    controller.setLightState(false);
    controller.setEnabled(true);

    controller.onLux(20f);
    assertTrue(scheduler.hasDelayed());
    controller.setScreenInteractive(false);
    assertFalse(scheduler.hasDelayed());
    assertNull(controller.snapshot().currentLux);
    scheduler.runDelayed();
    assertTrue(listener.commands.isEmpty());
  }

  @Test
  public void manualOverridePersistsUntilThresholdSideChanges() {
    FakeStore store = new FakeStore();
    FakeScheduler scheduler = new FakeScheduler();
    RecordingListener listener = new RecordingListener();
    AutoLightController controller = new AutoLightController(store, scheduler, listener);
    learnRange(controller);
    controller.setControlAvailable(true);
    controller.setLightState(false);
    controller.setEnabled(true);

    controller.onLux(20f);
    controller.onManualLightChanged(false);
    controller.onLux(15f);
    assertFalse(scheduler.hasDelayed());

    controller.onLux(80f);
    controller.onLux(20f);
    assertTrue(scheduler.hasDelayed());
    scheduler.runDelayed();
    assertEquals(1, listener.commands.size());
    assertTrue(listener.commands.get(0));
  }

  @Test
  public void autoOffRequiresExplicitPermission() {
    FakeStore store = new FakeStore();
    FakeScheduler scheduler = new FakeScheduler();
    RecordingListener listener = new RecordingListener();
    AutoLightController controller = new AutoLightController(store, scheduler, listener);
    learnRange(controller);
    controller.setControlAvailable(true);
    controller.setLightState(true);
    controller.setEnabled(true);

    controller.onLux(100f);
    assertTrue(listener.commands.isEmpty());
    controller.setAutoOffEnabled(true);
    assertEquals(1, listener.commands.size());
    assertFalse(listener.commands.get(0));
  }

  private static void learnRange(AutoLightController controller) {
    controller.setScreenInteractive(true);
    controller.onLux(10f);
    controller.onLux(10f);
    controller.onLux(100f);
    controller.onLux(100f);
    assertTrue(controller.snapshot().calibrationReady);
  }

  private static final class RecordingListener implements AutoLightController.Listener {
    final List<Boolean> commands = new ArrayList<>();

    @Override public void onAutomaticCommand(boolean enabled, float lux, float thresholdLux) {
      commands.add(enabled);
    }

    @Override public void onRawLog(String entry) {}
  }

  private static final class FakeScheduler implements Scheduler {
    Runnable delayed;
    long delayMillis;

    @Override public void post(Runnable task) { task.run(); }
    @Override public void postDelayed(Runnable task, long delayMillis) {
      this.delayed = task;
      this.delayMillis = delayMillis;
    }
    @Override public void cancel(Runnable task) { if (delayed == task) delayed = null; }
    @Override public void cancelAll() { delayed = null; }

    boolean hasDelayed() { return delayed != null; }

    void runDelayed() {
      Runnable task = delayed;
      delayed = null;
      if (task != null) task.run();
    }
  }

  private static final class FakeStore implements T4AStateStore {
    boolean autoLight;
    boolean autoOff;
    Float observedMin;
    Float observedMax;
    Float threshold;

    @Override public Boolean rememberedLock() { return null; }
    @Override public void setRememberedLock(boolean value) {}
    @Override public boolean autoLockEnabled() { return false; }
    @Override public void setAutoLockEnabled(boolean enabled) {}
    @Override public String autoLockDistance() { return null; }
    @Override public void setAutoLockDistance(String distance) {}
    @Override public Integer rssiObservedBest() { return null; }
    @Override public Integer rssiObservedWorst() { return null; }
    @Override public void setRssiObservation(int best, int worst) {}
    @Override public String bleAddress() { return ""; }
    @Override public void setBleAddress(String address) {}
    @Override public void clearBleAddress() {}
    @Override public Integer batteryObservedMin() { return null; }
    @Override public Integer batteryObservedMax() { return null; }
    @Override public Long batteryCycleStartedAt() { return null; }
    @Override public void setBatteryObservation(int min, int max, long startedAt) {}
    @Override public Integer batteryLastLivePercent() { return null; }
    @Override public Long batteryLastLiveAt() { return null; }
    @Override public void setBatteryLastLive(int percent, long observedAt) {}
    @Override public Integer batteryRechargeMinGapHours() { return null; }
    @Override public void setBatteryRechargeMinGapHours(int hours) {}

    @Override public boolean autoLightEnabled() { return autoLight; }
    @Override public void setAutoLightEnabled(boolean enabled) { autoLight = enabled; }
    @Override public boolean autoLightAutoOffEnabled() { return autoOff; }
    @Override public void setAutoLightAutoOffEnabled(boolean enabled) { autoOff = enabled; }
    @Override public Float lightObservedMinLux() { return observedMin; }
    @Override public Float lightObservedMaxLux() { return observedMax; }
    @Override public void setLightObservation(float minLux, float maxLux) {
      observedMin = minLux;
      observedMax = maxLux;
    }
    @Override public Float autoLightThresholdLux() { return threshold; }
    @Override public void setAutoLightThresholdLux(float lux) { threshold = lux; }
  }
}
