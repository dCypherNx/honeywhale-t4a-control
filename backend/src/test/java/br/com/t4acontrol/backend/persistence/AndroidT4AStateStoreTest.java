package br.com.t4acontrol.backend.persistence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import br.com.t4acontrol.backend.T4AStateStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class AndroidT4AStateStoreTest {
  private Context context;
  private SharedPreferences preferences;

  @Before public void setUp() {
    context = RuntimeEnvironment.getApplication();
    preferences = context.getSharedPreferences(AndroidT4AStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
  }

  @After public void tearDown() {
    preferences.edit().clear().commit();
  }

  @Test public void readsLegacyPreferenceFileAndKeysWithoutMigration() {
    preferences.edit()
        .putBoolean("lock_known", true)
        .putBoolean("lock_value", false)
        .putBoolean("auto_lock_distance", true)
        .putString("auto_lock_distance_level", "long")
        .putString("ble_address", "AA:BB:CC:DD:EE:FF")
        .putInt("battery_observed_min", 22)
        .putInt("battery_observed_max", 91)
        .putLong("battery_cycle_started_at", 123456789L)
        .putInt("battery_last_live_percent", 44)
        .putLong("battery_last_live_at", 987654321L)
        .putInt("battery_recharge_min_gap_hours", 4)
        .commit();

    T4AStateStore store = new AndroidT4AStateStore(context);

    assertEquals(Boolean.FALSE, store.rememberedLock());
    assertTrue(store.autoLockEnabled());
    assertEquals("long", store.autoLockDistance());
    assertEquals("AA:BB:CC:DD:EE:FF", store.bleAddress());
    assertEquals(Integer.valueOf(22), store.batteryObservedMin());
    assertEquals(Integer.valueOf(91), store.batteryObservedMax());
    assertEquals(Long.valueOf(123456789L), store.batteryCycleStartedAt());
    assertEquals(Integer.valueOf(44), store.batteryLastLivePercent());
    assertEquals(Long.valueOf(987654321L), store.batteryLastLiveAt());
    assertEquals(Integer.valueOf(4), store.batteryRechargeMinGapHours());
  }

  @Test public void writesExactlyTheLegacyKeysAndPreferenceTypes() {
    T4AStateStore store = new AndroidT4AStateStore(context);

    store.setRememberedLock(true);
    store.setAutoLockEnabled(false);
    store.setAutoLockDistance("short");
    store.setBleAddress("11:22:33:44:55:66");
    store.setBatteryObservation(15, 88, 111222333L);
    store.setBatteryLastLive(37, 444555666L);
    store.setBatteryRechargeMinGapHours(8);

    assertTrue(preferences.getBoolean("lock_known", false));
    assertTrue(preferences.getBoolean("lock_value", false));
    assertFalse(preferences.getBoolean("auto_lock_distance", true));
    assertEquals("short", preferences.getString("auto_lock_distance_level", null));
    assertEquals("11:22:33:44:55:66", preferences.getString("ble_address", null));
    assertEquals(15, preferences.getInt("battery_observed_min", -1));
    assertEquals(88, preferences.getInt("battery_observed_max", -1));
    assertEquals(111222333L, preferences.getLong("battery_cycle_started_at", -1L));
    assertEquals(37, preferences.getInt("battery_last_live_percent", -1));
    assertEquals(444555666L, preferences.getLong("battery_last_live_at", -1L));
    assertEquals(8, preferences.getInt("battery_recharge_min_gap_hours", -1));

    store.clearBleAddress();
    assertFalse(preferences.contains("ble_address"));
  }

  @Test public void missingOptionalLegacyValuesRemainAbsent() {
    T4AStateStore store = new AndroidT4AStateStore(context);

    assertNull(store.rememberedLock());
    assertFalse(store.autoLockEnabled());
    assertNull(store.autoLockDistance());
    assertEquals("", store.bleAddress());
    assertNull(store.batteryObservedMin());
    assertNull(store.batteryObservedMax());
    assertNull(store.batteryCycleStartedAt());
    assertNull(store.batteryLastLivePercent());
    assertNull(store.batteryLastLiveAt());
    assertNull(store.batteryRechargeMinGapHours());
  }
}
