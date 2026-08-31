from pathlib import Path


def replace(path, old, new):
    p = Path(path)
    text = p.read_text()
    if old not in text:
        raise SystemExit(f"pattern not found in {path}: {old[:120]!r}")
    p.write_text(text.replace(old, new, 1))


def append_before_last(path, content):
    p = Path(path)
    text = p.read_text()
    pos = text.rfind("}\n")
    if pos < 0:
        raise SystemExit(f"closing brace not found in {path}")
    p.write_text(text[:pos] + content + text[pos:])


# Pure calibration model.
Path("backend/src/main/java/br/com/t4acontrol/backend/RssiCalibration.java").write_text(r'''package br.com.t4acontrol.backend;

/** Learns trustworthy BLE RSSI extrema and derives three equal stable proximity bands. */
final class RssiCalibration {
  static final int REQUIRED_SAMPLES = 3;
  private static final double WEAK_EDGE_MARGIN = 0.10d;

  static final class Snapshot {
    final Integer best;
    final Integer worst;
    final Integer stableWorst;
    final Integer shortMin;
    final Integer mediumMin;
    final Integer longMin;
    final boolean ready;

    Snapshot(
        Integer best,
        Integer worst,
        Integer stableWorst,
        Integer shortMin,
        Integer mediumMin,
        Integer longMin,
        boolean ready) {
      this.best = best;
      this.worst = worst;
      this.stableWorst = stableWorst;
      this.shortMin = shortMin;
      this.mediumMin = mediumMin;
      this.longMin = longMin;
      this.ready = ready;
    }
  }

  private final int[] recent = new int[REQUIRED_SAMPLES];
  private int recentCount;
  private int recentIndex;
  private Integer best;
  private Integer worst;

  RssiCalibration(Integer persistedBest, Integer persistedWorst) {
    if (validRssi(persistedBest)
        && validRssi(persistedWorst)
        && persistedBest >= persistedWorst) {
      best = persistedBest;
      worst = persistedWorst;
    }
  }

  boolean observe(int rssi) {
    if (!validRssi(rssi)) return false;
    recent[recentIndex] = rssi;
    recentIndex = (recentIndex + 1) % REQUIRED_SAMPLES;
    if (recentCount < REQUIRED_SAMPLES) recentCount++;
    if (recentCount < REQUIRED_SAMPLES) return false;

    int median = median3(recent[0], recent[1], recent[2]);
    boolean changed = false;
    if (best == null || worst == null) {
      best = median;
      worst = median;
      changed = true;
    } else {
      if (median > best) {
        best = median;
        changed = true;
      }
      if (median < worst) {
        worst = median;
        changed = true;
      }
    }
    return changed;
  }

  void resetWindow() {
    recentCount = 0;
    recentIndex = 0;
  }

  Snapshot snapshot() {
    if (best == null || worst == null) {
      return new Snapshot(best, worst, null, null, null, null, false);
    }

    double observedSpan = best - worst;
    double stableWorstRaw = worst + observedSpan * WEAK_EDGE_MARGIN;
    double stableSpan = best - stableWorstRaw;
    int stableWorst = (int) Math.ceil(stableWorstRaw);
    int shortMin = (int) Math.ceil(best - stableSpan / 3.0d);
    int mediumMin = (int) Math.ceil(best - (2.0d * stableSpan / 3.0d));
    int longMin = stableWorst;
    boolean ready = observedSpan > 0.0d
        && best > shortMin
        && shortMin > mediumMin
        && mediumMin > longMin;
    return new Snapshot(best, worst, stableWorst, shortMin, mediumMin, longMin, ready);
  }

  private static boolean validRssi(Integer value) {
    return value != null && value < 0 && value >= -127;
  }

  private static boolean validRssi(int value) {
    return value < 0 && value >= -127;
  }

  private static int median3(int a, int b, int c) {
    if (a > b) { int t = a; a = b; b = t; }
    if (b > c) { int t = b; b = c; c = t; }
    if (a > b) { int t = a; a = b; b = t; }
    return b;
  }
}
''')

# Persistence contract.
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4AStateStore.java",
    "  void setAutoLockDistance(String distance);\n\n  String bleAddress();",
    "  void setAutoLockDistance(String distance);\n\n  Integer rssiObservedBest();\n\n  Integer rssiObservedWorst();\n\n  void setRssiObservation(int best, int worst);\n\n  String bleAddress();")

# Android persistence adapter.
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/persistence/AndroidT4AStateStore.java",
    "  static final String PREF_AUTO_LOCK_DISTANCE = \"auto_lock_distance_level\";\n  static final String PREF_BLE_ADDRESS = \"ble_address\";",
    "  static final String PREF_AUTO_LOCK_DISTANCE = \"auto_lock_distance_level\";\n  static final String PREF_RSSI_OBSERVED_BEST = \"rssi_observed_best\";\n  static final String PREF_RSSI_OBSERVED_WORST = \"rssi_observed_worst\";\n  static final String PREF_BLE_ADDRESS = \"ble_address\";")
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/persistence/AndroidT4AStateStore.java",
    "  @Override public void setAutoLockDistance(String distance) {\n    preferences.edit().putString(PREF_AUTO_LOCK_DISTANCE, distance).apply();\n  }\n\n  @Override public String bleAddress() {",
    "  @Override public void setAutoLockDistance(String distance) {\n    preferences.edit().putString(PREF_AUTO_LOCK_DISTANCE, distance).apply();\n  }\n\n  @Override public Integer rssiObservedBest() {\n    return preferences.contains(PREF_RSSI_OBSERVED_BEST)\n        ? preferences.getInt(PREF_RSSI_OBSERVED_BEST, 0)\n        : null;\n  }\n\n  @Override public Integer rssiObservedWorst() {\n    return preferences.contains(PREF_RSSI_OBSERVED_WORST)\n        ? preferences.getInt(PREF_RSSI_OBSERVED_WORST, 0)\n        : null;\n  }\n\n  @Override public void setRssiObservation(int best, int worst) {\n    preferences.edit()\n        .putInt(PREF_RSSI_OBSERVED_BEST, best)\n        .putInt(PREF_RSSI_OBSERVED_WORST, worst)\n        .apply();\n  }\n\n  @Override public String bleAddress() {")

# State exposed to UI.
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4AState.java",
    "  public final boolean autoLockEnabled;\n  public final String autoLockDistance;\n  public final Integer batteryObservedMin;",
    "  public final boolean autoLockEnabled;\n  public final String autoLockDistance;\n  public final Integer rssiObservedBest;\n  public final Integer rssiObservedWorst;\n  public final Integer rssiStableWorst;\n  public final Integer rssiShortMin;\n  public final Integer rssiMediumMin;\n  public final Integer rssiLongMin;\n  public final boolean rssiCalibrationReady;\n  public final Integer batteryObservedMin;")
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4AState.java",
    "      boolean autoLockEnabled,\n      String autoLockDistance,\n      Integer batteryObservedMin,",
    "      boolean autoLockEnabled,\n      String autoLockDistance,\n      Integer rssiObservedBest,\n      Integer rssiObservedWorst,\n      Integer rssiStableWorst,\n      Integer rssiShortMin,\n      Integer rssiMediumMin,\n      Integer rssiLongMin,\n      boolean rssiCalibrationReady,\n      Integer batteryObservedMin,")
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4AState.java",
    "    this.autoLockEnabled = autoLockEnabled;\n    this.autoLockDistance = autoLockDistance;\n    this.batteryObservedMin = batteryObservedMin;",
    "    this.autoLockEnabled = autoLockEnabled;\n    this.autoLockDistance = autoLockDistance;\n    this.rssiObservedBest = rssiObservedBest;\n    this.rssiObservedWorst = rssiObservedWorst;\n    this.rssiStableWorst = rssiStableWorst;\n    this.rssiShortMin = rssiShortMin;\n    this.rssiMediumMin = rssiMediumMin;\n    this.rssiLongMin = rssiLongMin;\n    this.rssiCalibrationReady = rssiCalibrationReady;\n    this.batteryObservedMin = batteryObservedMin;")

# Backend integration.
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4ABackend.java",
    "  private final T4AStateStore stateStore;\n  private final Handler handler = new Handler(Looper.getMainLooper());",
    "  private final T4AStateStore stateStore;\n  private final RssiCalibration rssiCalibration;\n  private final Handler handler = new Handler(Looper.getMainLooper());")
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4ABackend.java",
    "    this.transport = transport;\n    this.stateStore = stateStore;\n    restoreBatteryObservation();",
    "    this.transport = transport;\n    this.stateStore = stateStore;\n    this.rssiCalibration = new RssiCalibration(stateStore.rssiObservedBest(), stateStore.rssiObservedWorst());\n    restoreBatteryObservation();")
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4ABackend.java",
    "  private void emitState() {\n    String name = device == null ? \"\" : device.name;\n    String mac = device == null ? \"\" : device.mac;\n    listener.onState(new T4AState(",
    "  private void emitState() {\n    String name = device == null ? \"\" : device.name;\n    String mac = device == null ? \"\" : device.mac;\n    RssiCalibration.Snapshot calibration = rssiCalibration.snapshot();\n    listener.onState(new T4AState(")
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4ABackend.java",
    "        stateStore.autoLockEnabled(),\n        autoLockDistance(),\n        batteryObservedMin,",
    "        stateStore.autoLockEnabled(),\n        autoLockDistance(),\n        calibration.best,\n        calibration.worst,\n        calibration.stableWorst,\n        calibration.shortMin,\n        calibration.mediumMin,\n        calibration.longMin,\n        calibration.ready,\n        batteryObservedMin,")
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4ABackend.java",
    "      rssi = 0;\n      resetLongRangeWeakSamples();\n      if (previousRssi != 0 && stateStore.autoLockEnabled()) raw(\"[BT] RSSI \" + previousRssi + \" -> unavailable\");",
    "      rssi = 0;\n      resetLongRangeWeakSamples();\n      rssiCalibration.resetWindow();\n      if (previousRssi != 0 && stateStore.autoLockEnabled()) raw(\"[BT] RSSI \" + previousRssi + \" -> unavailable\");")
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4ABackend.java",
    "          rssi = value;\n          rssiFailures = 0;\n          if (stateStore.autoLockEnabled() && previousRssi != value) logAutoLockRssi(previousRssi, value);",
    "          rssi = value;\n          rssiFailures = 0;\n          observeRssi(value);\n          if (stateStore.autoLockEnabled() && previousRssi != value) logAutoLockRssi(previousRssi, value);")
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4ABackend.java",
    "          rssi = 0;\n          resetLongRangeWeakSamples();\n          if (stateStore.autoLockEnabled() && previousRssi != 0) raw(\"[BT] RSSI \" + previousRssi + \" -> unavailable\");",
    "          rssi = 0;\n          resetLongRangeWeakSamples();\n          rssiCalibration.resetWindow();\n          if (stateStore.autoLockEnabled() && previousRssi != 0) raw(\"[BT] RSSI \" + previousRssi + \" -> unavailable\");")
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4ABackend.java",
    "      rssi = 0;\n      resetLongRangeWeakSamples();\n      if (stateStore.autoLockEnabled() && previousRssi != 0) raw(\"[BT] RSSI \" + previousRssi + \" -> unavailable\");\n      recoverRssi();",
    "      rssi = 0;\n      resetLongRangeWeakSamples();\n      rssiCalibration.resetWindow();\n      if (stateStore.autoLockEnabled() && previousRssi != 0) raw(\"[BT] RSSI \" + previousRssi + \" -> unavailable\");\n      recoverRssi();")
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4ABackend.java",
    "      awaitingLockRxAfterConnect = false;\n      resetLongRangeWeakSamples();\n      raw(\"[APP] CONNECTION CONNECTED->DISCONNECTED autoLock=\"",
    "      awaitingLockRxAfterConnect = false;\n      resetLongRangeWeakSamples();\n      rssiCalibration.resetWindow();\n      raw(\"[APP] CONNECTION CONNECTED->DISCONNECTED autoLock=\"")
replace(
    "backend/src/main/java/br/com/t4acontrol/backend/T4ABackend.java",
    "  private int autoLockThreshold(String distance) {\n    if (DISTANCE_SHORT.equals(distance)) return AUTO_LOCK_SHORT_THRESHOLD_DBM;\n    if (DISTANCE_LONG.equals(distance)) return AUTO_LOCK_LONG_THRESHOLD_DBM;\n    return AUTO_LOCK_MEDIUM_THRESHOLD_DBM;\n  }\n\n  private int autoUnlockThreshold(String distance) {\n    return autoLockThreshold(distance) + AUTO_UNLOCK_HYSTERESIS_DB;\n  }\n\n  private void logAutoLockRssi(int previousRssi, int currentRssi) {",
    "  private int autoLockThreshold(String distance) {\n    RssiCalibration.Snapshot calibration = rssiCalibration.snapshot();\n    if (calibration.ready) return dynamicBandMinimum(calibration, distance) - AUTO_UNLOCK_HYSTERESIS_DB;\n    if (DISTANCE_SHORT.equals(distance)) return AUTO_LOCK_SHORT_THRESHOLD_DBM;\n    if (DISTANCE_LONG.equals(distance)) return AUTO_LOCK_LONG_THRESHOLD_DBM;\n    return AUTO_LOCK_MEDIUM_THRESHOLD_DBM;\n  }\n\n  private int autoUnlockThreshold(String distance) {\n    RssiCalibration.Snapshot calibration = rssiCalibration.snapshot();\n    if (calibration.ready) return dynamicBandMinimum(calibration, distance);\n    return autoLockThreshold(distance) + AUTO_UNLOCK_HYSTERESIS_DB;\n  }\n\n  private int dynamicBandMinimum(RssiCalibration.Snapshot calibration, String distance) {\n    if (DISTANCE_SHORT.equals(distance)) return calibration.shortMin;\n    if (DISTANCE_LONG.equals(distance)) return calibration.longMin;\n    return calibration.mediumMin;\n  }\n\n  private void observeRssi(int value) {\n    if (!rssiCalibration.observe(value)) return;\n    RssiCalibration.Snapshot calibration = rssiCalibration.snapshot();\n    if (calibration.best != null && calibration.worst != null) {\n      stateStore.setRssiObservation(calibration.best, calibration.worst);\n    }\n    if (calibration.ready) {\n      raw(\"[APP] RSSI CALIBRATION best=\" + calibration.best\n          + \" worst=\" + calibration.worst\n          + \" stableWorst=\" + calibration.stableWorst\n          + \" bands={short:\" + calibration.best + \"..\" + calibration.shortMin\n          + \",medium:\" + (calibration.shortMin - 1) + \"..\" + calibration.mediumMin\n          + \",long:\" + (calibration.mediumMin - 1) + \"..\" + calibration.longMin + \"}\");\n    } else {\n      raw(\"[APP] RSSI CALIBRATION learning best=\" + calibration.best + \" worst=\" + calibration.worst);\n    }\n  }\n\n  private void logAutoLockRssi(int previousRssi, int currentRssi) {")

# Settings use learned values rather than hard-coded bands.
replace(
    "app/src/main/java/br/com/t4acontrol/ui/settings/SettingsScreen.kt",
    '''        Text(stringResource(R.string.auto_lock_distance), color = foreground)\n        val distanceValues = listOf("short", "medium", "long")\n        val selectedDistanceIndex = distanceValues.indexOf(current.autoLockDistance).takeIf { it >= 0 } ?: 1\n        SegmentedChoice(\n            labels = listOf(stringResource(R.string.distance_short), stringResource(R.string.distance_medium), stringResource(R.string.distance_long)),\n            selectedIndex = selectedDistanceIndex,\n        ) { index -> actions.setAutoLockDistanceFromSettings(distanceValues[index]) }''',
    '''        val distanceValues = listOf("short", "medium", "long")\n        val selectedDistanceIndex = distanceValues.indexOf(current.autoLockDistance).takeIf { it >= 0 } ?: 1\n        val dynamicLabels = if (current.rssiCalibrationReady) {\n            listOf(\n                stringResource(R.string.distance_dynamic, stringResource(R.string.distance_short_name), current.rssiObservedBest, current.rssiShortMin),\n                stringResource(R.string.distance_dynamic, stringResource(R.string.distance_medium_name), current.rssiShortMin!! - 1, current.rssiMediumMin),\n                stringResource(R.string.distance_dynamic, stringResource(R.string.distance_long_name), current.rssiMediumMin!! - 1, current.rssiLongMin),\n            )\n        } else {\n            listOf(\n                stringResource(R.string.distance_short_name),\n                stringResource(R.string.distance_medium_name),\n                stringResource(R.string.distance_long_name),\n            )\n        }\n        Text(\n            if (current.rssiCalibrationReady) {\n                stringResource(R.string.auto_lock_calibrated_range, current.rssiObservedBest, current.rssiObservedWorst, current.rssiStableWorst)\n            } else {\n                stringResource(R.string.auto_lock_calibrating)\n            },\n            color = foreground,\n        )\n        SegmentedChoice(\n            labels = dynamicLabels,\n            selectedIndex = selectedDistanceIndex,\n        ) { index -> actions.setAutoLockDistanceFromSettings(distanceValues[index]) }''')

replace(
    "app/src/main/res/values/strings.xml",
    "    <string name=\"auto_lock_distance\">Faixa de sinal Bluetooth considerada · -15 a -90 dBm</string>",
    "    <string name=\"auto_lock_distance\">Faixa de sinal Bluetooth</string>\n    <string name=\"auto_lock_calibrating\">Calibrando automaticamente o alcance Bluetooth…</string>\n    <string name=\"auto_lock_calibrated_range\">Calibração: melhor %1$d dBm · pior %2$d dBm · estável até %3$d dBm</string>\n    <string name=\"distance_short_name\">Curta</string>\n    <string name=\"distance_medium_name\">Média</string>\n    <string name=\"distance_long_name\">Longa</string>\n    <string name=\"distance_dynamic\">%1$s\\n%2$d a %3$d dBm</string>")

# Existing backend tests: persistence contract + dynamic integration coverage.
replace(
    "backend/src/test/java/br/com/t4acontrol/backend/T4ABackendTest.java",
    "    String autoLockDistance;\n    String bleAddress = \"\";",
    "    String autoLockDistance;\n    Integer rssiObservedBest;\n    Integer rssiObservedWorst;\n    String bleAddress = \"\";")
replace(
    "backend/src/test/java/br/com/t4acontrol/backend/T4ABackendTest.java",
    "    @Override public String autoLockDistance() { return autoLockDistance; }\n    @Override public void setAutoLockDistance(String distance) { autoLockDistance = distance; }\n    @Override public String bleAddress() { return bleAddress; }",
    "    @Override public String autoLockDistance() { return autoLockDistance; }\n    @Override public void setAutoLockDistance(String distance) { autoLockDistance = distance; }\n    @Override public Integer rssiObservedBest() { return rssiObservedBest; }\n    @Override public Integer rssiObservedWorst() { return rssiObservedWorst; }\n    @Override public void setRssiObservation(int best, int worst) { rssiObservedBest = best; rssiObservedWorst = worst; }\n    @Override public String bleAddress() { return bleAddress; }")
replace(
    "backend/src/test/java/br/com/t4acontrol/backend/T4ABackendTest.java",
    "  private void forceRechargeGapFrom(int percent) {",
    '''  @Test public void seededDynamicCalibrationUsesTenPercentWeakEdgeMargin() {\n    stateStore.rememberedLock = false;\n    stateStore.autoLockDistance = "short";\n    stateStore.rssiObservedBest = -15;\n    stateStore.rssiObservedWorst = -90;\n    startConnected(Collections.emptyMap());\n\n    T4AState state = listener.latest();\n    assertTrue(state.rssiCalibrationReady);\n    assertEquals(Integer.valueOf(-82), state.rssiStableWorst);\n    assertEquals(Integer.valueOf(-37), state.rssiShortMin);\n    assertEquals(Integer.valueOf(-60), state.rssiMediumMin);\n    assertEquals(Integer.valueOf(-82), state.rssiLongMin);\n\n    backend.setAutoLockEnabled(true);\n    int before = transport.publishCalls;\n    transport.rssi = -37;\n    pollRssiNow();\n\n    assertEquals(before + 1, transport.publishCalls);\n    assertEquals(true, transport.lastPublished.get("1"));\n    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("AUTO_LOCK action=unlock rssi=-37 threshold=-37")));\n  }\n\n  @Test public void seededDynamicMediumLocksBelowLearnedBand() {\n    stateStore.rememberedLock = true;\n    stateStore.autoLockDistance = "medium";\n    stateStore.rssiObservedBest = -15;\n    stateStore.rssiObservedWorst = -90;\n    startConnected(Collections.emptyMap());\n    backend.setAutoLockEnabled(true);\n    int before = transport.publishCalls;\n    transport.rssi = -61;\n\n    pollRssiNow();\n\n    assertEquals(before + 1, transport.publishCalls);\n    assertEquals(false, transport.lastPublished.get("1"));\n    assertTrue(listener.raw.stream().anyMatch(value -> value.contains("AUTO_LOCK action=lock rssi=-61 threshold=-61")));\n  }\n\n  private void forceRechargeGapFrom(int percent) {''')

# Pure calibration regression tests.
Path("backend/src/test/java/br/com/t4acontrol/backend/RssiCalibrationTest.java").write_text(r'''package br.com.t4acontrol.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RssiCalibrationTest {
  @Test public void derivesExpectedBandsFromMinusFifteenAndMinusNinety() {
    RssiCalibration calibration = new RssiCalibration(-15, -90);
    RssiCalibration.Snapshot snapshot = calibration.snapshot();

    assertTrue(snapshot.ready);
    assertEquals(Integer.valueOf(-82), snapshot.stableWorst);
    assertEquals(Integer.valueOf(-37), snapshot.shortMin);
    assertEquals(Integer.valueOf(-60), snapshot.mediumMin);
    assertEquals(Integer.valueOf(-82), snapshot.longMin);
  }

  @Test public void singleOutlierCannotRedefineExtreme() {
    RssiCalibration calibration = new RssiCalibration(-20, -60);

    calibration.observe(-50);
    calibration.observe(-50);
    calibration.observe(-100);

    RssiCalibration.Snapshot snapshot = calibration.snapshot();
    assertEquals(Integer.valueOf(-20), snapshot.best);
    assertEquals(Integer.valueOf(-60), snapshot.worst);
  }

  @Test public void medianOfThreeConsecutiveSamplesCanExpandObservedRange() {
    RssiCalibration calibration = new RssiCalibration(-20, -60);

    calibration.observe(-85);
    calibration.observe(-86);
    assertFalse(calibration.observe(-84));
    RssiCalibration.Snapshot snapshot = calibration.snapshot();
    assertEquals(Integer.valueOf(-85), snapshot.worst);
  }
}
''')

# Persistence tests include the new optional keys without disturbing legacy compatibility.
replace(
    "backend/src/test/java/br/com/t4acontrol/backend/persistence/AndroidT4AStateStoreTest.java",
    "        .putString(\"auto_lock_distance_level\", \"long\")\n        .putString(\"ble_address\", \"AA:BB:CC:DD:EE:FF\")",
    "        .putString(\"auto_lock_distance_level\", \"long\")\n        .putInt(\"rssi_observed_best\", -18)\n        .putInt(\"rssi_observed_worst\", -91)\n        .putString(\"ble_address\", \"AA:BB:CC:DD:EE:FF\")")
replace(
    "backend/src/test/java/br/com/t4acontrol/backend/persistence/AndroidT4AStateStoreTest.java",
    "    assertEquals(\"long\", store.autoLockDistance());\n    assertEquals(\"AA:BB:CC:DD:EE:FF\", store.bleAddress());",
    "    assertEquals(\"long\", store.autoLockDistance());\n    assertEquals(Integer.valueOf(-18), store.rssiObservedBest());\n    assertEquals(Integer.valueOf(-91), store.rssiObservedWorst());\n    assertEquals(\"AA:BB:CC:DD:EE:FF\", store.bleAddress());")
replace(
    "backend/src/test/java/br/com/t4acontrol/backend/persistence/AndroidT4AStateStoreTest.java",
    "    store.setAutoLockDistance(\"short\");\n    store.setBleAddress(\"11:22:33:44:55:66\");",
    "    store.setAutoLockDistance(\"short\");\n    store.setRssiObservation(-16, -88);\n    store.setBleAddress(\"11:22:33:44:55:66\");")
replace(
    "backend/src/test/java/br/com/t4acontrol/backend/persistence/AndroidT4AStateStoreTest.java",
    "    assertEquals(\"short\", preferences.getString(\"auto_lock_distance_level\", null));\n    assertEquals(\"11:22:33:44:55:66\", preferences.getString(\"ble_address\", null));",
    "    assertEquals(\"short\", preferences.getString(\"auto_lock_distance_level\", null));\n    assertEquals(-16, preferences.getInt(\"rssi_observed_best\", 0));\n    assertEquals(-88, preferences.getInt(\"rssi_observed_worst\", 0));\n    assertEquals(\"11:22:33:44:55:66\", preferences.getString(\"ble_address\", null));")
replace(
    "backend/src/test/java/br/com/t4acontrol/backend/persistence/AndroidT4AStateStoreTest.java",
    "    assertNull(store.autoLockDistance());\n    assertEquals(\"\", store.bleAddress());",
    "    assertNull(store.autoLockDistance());\n    assertNull(store.rssiObservedBest());\n    assertNull(store.rssiObservedWorst());\n    assertEquals(\"\", store.bleAddress());")

print("dynamic RSSI patch applied")
