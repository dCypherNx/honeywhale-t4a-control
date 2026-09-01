package br.com.t4acontrol.backend.persistence;

import android.content.Context;
import android.content.SharedPreferences;
import br.com.t4acontrol.backend.T4AStateStore;

/** Android persistence adapter preserving the legacy t4a_backend preference file and keys. */
public final class AndroidT4AStateStore implements T4AStateStore {
  public static final String PREFERENCES_NAME = "t4a_backend";

  static final String PREF_LOCK_KNOWN = "lock_known";
  static final String PREF_LOCK_VALUE = "lock_value";
  static final String PREF_AUTO_LOCK = "auto_lock_distance";
  static final String PREF_AUTO_LOCK_DISTANCE = "auto_lock_distance_level";
  static final String PREF_RSSI_OBSERVED_BEST = "rssi_observed_best";
  static final String PREF_RSSI_OBSERVED_WORST = "rssi_observed_worst";
  static final String PREF_BLE_ADDRESS = "ble_address";
  static final String PREF_BATTERY_OBSERVED_MIN = "battery_observed_min";
  static final String PREF_BATTERY_OBSERVED_MAX = "battery_observed_max";
  static final String PREF_BATTERY_CYCLE_STARTED_AT = "battery_cycle_started_at";
  static final String PREF_BATTERY_LAST_LIVE_PERCENT = "battery_last_live_percent";
  static final String PREF_BATTERY_LAST_LIVE_AT = "battery_last_live_at";
  static final String PREF_BATTERY_RECHARGE_MIN_GAP_HOURS = "battery_recharge_min_gap_hours";
  static final String PREF_AUTO_LIGHT_ENABLED = "auto_light_enabled";
  static final String PREF_AUTO_LIGHT_AUTO_OFF_ENABLED = "auto_light_auto_off_enabled";
  static final String PREF_LIGHT_OBSERVED_MIN_LUX = "light_observed_min_lux";
  static final String PREF_LIGHT_OBSERVED_MAX_LUX = "light_observed_max_lux";
  static final String PREF_AUTO_LIGHT_THRESHOLD_LUX = "auto_light_threshold_lux";

  private final SharedPreferences preferences;

  public AndroidT4AStateStore(Context context) {
    preferences =
        context.getApplicationContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
  }

  @Override
  public Boolean rememberedLock() {
    if (!preferences.getBoolean(PREF_LOCK_KNOWN, false)) return null;
    return preferences.getBoolean(PREF_LOCK_VALUE, true);
  }

  @Override
  public void setRememberedLock(boolean value) {
    preferences.edit()
        .putBoolean(PREF_LOCK_KNOWN, true)
        .putBoolean(PREF_LOCK_VALUE, value)
        .apply();
  }

  @Override public boolean autoLockEnabled() {
    return preferences.getBoolean(PREF_AUTO_LOCK, false);
  }

  @Override public void setAutoLockEnabled(boolean enabled) {
    preferences.edit().putBoolean(PREF_AUTO_LOCK, enabled).apply();
  }

  @Override public String autoLockDistance() {
    return preferences.getString(PREF_AUTO_LOCK_DISTANCE, null);
  }

  @Override public void setAutoLockDistance(String distance) {
    preferences.edit().putString(PREF_AUTO_LOCK_DISTANCE, distance).apply();
  }

  @Override public Integer rssiObservedBest() {
    return preferences.contains(PREF_RSSI_OBSERVED_BEST)
        ? preferences.getInt(PREF_RSSI_OBSERVED_BEST, 0)
        : null;
  }

  @Override public Integer rssiObservedWorst() {
    return preferences.contains(PREF_RSSI_OBSERVED_WORST)
        ? preferences.getInt(PREF_RSSI_OBSERVED_WORST, 0)
        : null;
  }

  @Override public void setRssiObservation(int best, int worst) {
    preferences.edit()
        .putInt(PREF_RSSI_OBSERVED_BEST, best)
        .putInt(PREF_RSSI_OBSERVED_WORST, worst)
        .apply();
  }

  @Override public String bleAddress() {
    return preferences.getString(PREF_BLE_ADDRESS, "");
  }

  @Override public void setBleAddress(String address) {
    preferences.edit().putString(PREF_BLE_ADDRESS, address == null ? "" : address).apply();
  }

  @Override public void clearBleAddress() {
    preferences.edit().remove(PREF_BLE_ADDRESS).apply();
  }

  @Override public Integer batteryObservedMin() {
    return preferences.contains(PREF_BATTERY_OBSERVED_MIN)
        ? preferences.getInt(PREF_BATTERY_OBSERVED_MIN, -1)
        : null;
  }

  @Override public Integer batteryObservedMax() {
    return preferences.contains(PREF_BATTERY_OBSERVED_MAX)
        ? preferences.getInt(PREF_BATTERY_OBSERVED_MAX, -1)
        : null;
  }

  @Override public Long batteryCycleStartedAt() {
    return preferences.contains(PREF_BATTERY_CYCLE_STARTED_AT)
        ? preferences.getLong(PREF_BATTERY_CYCLE_STARTED_AT, 0L)
        : null;
  }

  @Override public void setBatteryObservation(int min, int max, long startedAt) {
    preferences.edit()
        .putInt(PREF_BATTERY_OBSERVED_MIN, min)
        .putInt(PREF_BATTERY_OBSERVED_MAX, max)
        .putLong(PREF_BATTERY_CYCLE_STARTED_AT, startedAt)
        .apply();
  }

  @Override public Integer batteryLastLivePercent() {
    return preferences.contains(PREF_BATTERY_LAST_LIVE_PERCENT)
        ? preferences.getInt(PREF_BATTERY_LAST_LIVE_PERCENT, -1)
        : null;
  }

  @Override public Long batteryLastLiveAt() {
    return preferences.contains(PREF_BATTERY_LAST_LIVE_AT)
        ? preferences.getLong(PREF_BATTERY_LAST_LIVE_AT, 0L)
        : null;
  }

  @Override public void setBatteryLastLive(int percent, long observedAt) {
    preferences.edit()
        .putInt(PREF_BATTERY_LAST_LIVE_PERCENT, percent)
        .putLong(PREF_BATTERY_LAST_LIVE_AT, observedAt)
        .apply();
  }

  @Override public Integer batteryRechargeMinGapHours() {
    return preferences.contains(PREF_BATTERY_RECHARGE_MIN_GAP_HOURS)
        ? preferences.getInt(PREF_BATTERY_RECHARGE_MIN_GAP_HOURS, 0)
        : null;
  }

  @Override public void setBatteryRechargeMinGapHours(int hours) {
    preferences.edit().putInt(PREF_BATTERY_RECHARGE_MIN_GAP_HOURS, hours).apply();
  }

  @Override public boolean autoLightEnabled() {
    return preferences.getBoolean(PREF_AUTO_LIGHT_ENABLED, false);
  }

  @Override public void setAutoLightEnabled(boolean enabled) {
    preferences.edit().putBoolean(PREF_AUTO_LIGHT_ENABLED, enabled).apply();
  }

  @Override public boolean autoLightAutoOffEnabled() {
    return preferences.getBoolean(PREF_AUTO_LIGHT_AUTO_OFF_ENABLED, false);
  }

  @Override public void setAutoLightAutoOffEnabled(boolean enabled) {
    preferences.edit().putBoolean(PREF_AUTO_LIGHT_AUTO_OFF_ENABLED, enabled).apply();
  }

  @Override public Float lightObservedMinLux() {
    return preferences.contains(PREF_LIGHT_OBSERVED_MIN_LUX)
        ? preferences.getFloat(PREF_LIGHT_OBSERVED_MIN_LUX, 0f)
        : null;
  }

  @Override public Float lightObservedMaxLux() {
    return preferences.contains(PREF_LIGHT_OBSERVED_MAX_LUX)
        ? preferences.getFloat(PREF_LIGHT_OBSERVED_MAX_LUX, 0f)
        : null;
  }

  @Override public void setLightObservation(float minLux, float maxLux) {
    preferences.edit()
        .putFloat(PREF_LIGHT_OBSERVED_MIN_LUX, minLux)
        .putFloat(PREF_LIGHT_OBSERVED_MAX_LUX, maxLux)
        .apply();
  }

  @Override public Float autoLightThresholdLux() {
    return preferences.contains(PREF_AUTO_LIGHT_THRESHOLD_LUX)
        ? preferences.getFloat(PREF_AUTO_LIGHT_THRESHOLD_LUX, 0f)
        : null;
  }

  @Override public void setAutoLightThresholdLux(float lux) {
    preferences.edit().putFloat(PREF_AUTO_LIGHT_THRESHOLD_LUX, lux).apply();
  }
}
