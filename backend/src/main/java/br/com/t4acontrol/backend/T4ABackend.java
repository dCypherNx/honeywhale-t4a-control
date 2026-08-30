package br.com.t4acontrol.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.alibaba.fastjson.JSON;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class T4ABackend {
  public interface Listener {
    void onState(T4AState state);
    void onEvent(String event);
    void onRawLog(String entry);
  }

  private static final String DP_LOCK = "1";
  private static final String DP_SPEED = "2";
  private static final String DP_BATTERY = "3";
  private static final Set<String> QUIET_DPS = Set.of("2", "3", "5", "6", "12");
  private static final long STATE_REFRESH_MS = 2000L;
  private static final long FOREGROUND_RECONNECT_MS = 5000L;
  private static final long BACKGROUND_RECONNECT_MS = 30000L;
  private static final long RSSI_REFRESH_MS = 2000L;
  private static final long RSSI_RESPONSE_TIMEOUT_MS = 6000L;
  private static final long LOCK_CONFIRM_TIMEOUT_MS = 5000L;
  private static final int DEFAULT_BATTERY_RECHARGE_MIN_GAP_HOURS = 1;
  private static final int BATTERY_RECHARGE_MIN_RISE = 5;
  private static final int BATTERY_RECHARGE_MAX_DISTANCE = 1;
  private static final String PREF_LOCK_KNOWN = "lock_known";
  private static final String PREF_LOCK_VALUE = "lock_value";
  private static final String PREF_AUTO_LOCK = "auto_lock_distance";
  private static final String PREF_AUTO_LOCK_DISTANCE = "auto_lock_distance_level";
  private static final String PREF_BLE_ADDRESS = "ble_address";
  private static final String PREF_BATTERY_OBSERVED_MIN = "battery_observed_min";
  private static final String PREF_BATTERY_OBSERVED_MAX = "battery_observed_max";
  private static final String PREF_BATTERY_CYCLE_STARTED_AT = "battery_cycle_started_at";
  private static final String PREF_BATTERY_LAST_LIVE_PERCENT = "battery_last_live_percent";
  private static final String PREF_BATTERY_LAST_LIVE_AT = "battery_last_live_at";
  private static final String PREF_BATTERY_RECHARGE_MIN_GAP_HOURS = "battery_recharge_min_gap_hours";
  private static final String DISTANCE_SHORT = "short",
      DISTANCE_MEDIUM = "medium",
      DISTANCE_LONG = "long";

  private final Listener listener;
  private final T4AProvisioner provisioner;
  private final T4ATransport transport;
  private final SharedPreferences preferences;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<String, Object> dps = new HashMap<>();
  private final Map<String, T4AState.DpInfo> schema = new HashMap<>();
  private final Map<String, Object> pendingDps = new HashMap<>();

  private boolean authenticated;
  private String account = "";
  private T4AState.Pairing pairing = T4AState.Pairing.UNPAIRED;
  private boolean connected;
  private String message = "";
  private long homeId;
  private String homeName = "";
  private T4AContracts.Device device;
  private T4AContracts.DiscoveredDevice candidate;
  private int rssi;
  private boolean running;
  private boolean foreground = true;
  private long lastRssiRead;
  private long lastRssiResponse;
  private int rssiFailures;
  private Object pendingLockValue;
  private long pendingLockUntil;
  private Integer batteryObservedMin;
  private Integer batteryObservedMax;
  private Long batteryCycleStartedAt;
  private Integer pendingBatteryRechargePercent;
  private Integer pendingBatteryRechargeMin;
  private Integer pendingBatteryRechargeMax;
  private Long pendingBatteryRechargeDetectedAt;

  private final Runnable maintenance = new Runnable() {
    @Override public void run() {
      if (!running) return;
      refreshFromCache();
      if (device != null && !transport.isConnected(device.id)) connectPairedDevice();
      if (connected && System.currentTimeMillis() - lastRssiRead >= RSSI_REFRESH_MS) pollRssi();
      long delay = connected ? STATE_REFRESH_MS : (foreground ? FOREGROUND_RECONNECT_MS : BACKGROUND_RECONNECT_MS);
      handler.postDelayed(this, delay);
    }
  };

  public T4ABackend(Context context, T4AProvisioner provisioner, T4ATransport transport, Listener listener) {
    this.listener = listener;
    this.provisioner = provisioner;
    this.transport = transport;
    this.preferences = context.getApplicationContext().getSharedPreferences("t4a_backend", Context.MODE_PRIVATE);
    restoreBatteryObservation();
  }

  public void start() {
    running = true;
    foreground = true;
    String restoredAccount = provisioner.currentAccount();
    if (restoredAccount == null) {
      authenticated = false;
      emit("Entre para acessar o T4A");
      return;
    }
    authenticated = true;
    account = restoredAccount;
    queryHomes();
    handler.removeCallbacks(maintenance);
    handler.post(maintenance);
  }

  public void setForeground(boolean value) {
    foreground = value;
    if (running) {
      handler.removeCallbacks(maintenance);
      handler.post(maintenance);
    }
  }

  public void destroy() {
    running = false;
    handler.removeCallbacksAndMessages(null);
    try { transport.destroy(); }
    finally { if (provisioner != (Object) transport) provisioner.destroy(); }
  }

  public void login(String email, String password) {
    message = "Autenticando…";
    emitState();
    provisioner.login("55", email, password, new T4AContracts.Callback<String>() {
      @Override public void onSuccess(String restoredAccount) {
        handler.post(() -> {
          authenticated = true;
          account = restoredAccount.isEmpty() ? email : restoredAccount;
          event("Conta autenticada");
          queryHomes();
        });
      }
      @Override public void onError(String code, String error) {
        handler.post(() -> emit("Falha no login: " + error));
      }
    });
  }

  public void scan() {
    if (!authenticated || homeId == 0 || device != null) return;
    candidate = null;
    pairing = T4AState.Pairing.SCANNING;
    emit("Procurando T4A…");
    provisioner.startDiscovery(15000, new T4AContracts.DiscoveryListener() {
      @Override public void onDevice(T4AContracts.DiscoveredDevice found) {
        handler.post(() -> {
          if (found == null || found.bound || candidate != null) return;
          candidate = found;
          if (!found.address.isEmpty()) preferences.edit().putString(PREF_BLE_ADDRESS, found.address).apply();
          rssi = found.rssi;
          pairing = T4AState.Pairing.READY;
          provisioner.stopDiscovery();
          emit("T4A pronto para parear");
        });
      }
      @Override public void onError(String code, String error) {
        handler.post(() -> emit("Falha ao procurar T4A: " + code));
      }
    });
  }

  public void pair() {
    if (candidate == null || homeId == 0) return;
    pairing = T4AState.Pairing.PAIRING;
    emit("Pareando…");
    provisioner.pair(homeId, candidate, new T4AContracts.Callback<T4AContracts.Device>() {
      @Override public void onSuccess(T4AContracts.Device result) { handler.post(() -> attach(withRememberedBleAddress(result))); }
      @Override public void onError(String code, String error) {
        handler.post(() -> {
          pairing = T4AState.Pairing.READY;
          emit("Pareamento falhou: " + code);
        });
      }
    });
  }

  public void publish(String dpId, Object value) {
    if (device == null) return;
    if (!DP_LOCK.equals(dpId)) pendingDps.put(dpId, value);
    emitState();
    if (DP_LOCK.equals(dpId)) {
      pendingLockValue = value;
      pendingLockUntil = System.currentTimeMillis() + LOCK_CONFIRM_TIMEOUT_MS;
      boolean remembered = Boolean.TRUE.equals(value);
      preferences.edit().putBoolean(PREF_LOCK_KNOWN, true).putBoolean(PREF_LOCK_VALUE, remembered).apply();
      dps.put(DP_LOCK, remembered);
    }
    Map<String, Object> command = Collections.singletonMap(dpId, value);
    raw("TX " + JSON.toJSONString(command));
    transport.publish(device.id, command, new T4AContracts.ResultCallback() {
      @Override public void onSuccess() {
        if (!QUIET_DPS.contains(dpId)) event("Comando DP " + dpId + " aceito; aguardando confirmação");
      }
      @Override public void onError(String code, String error) {
        pendingDps.remove(dpId);
        if (DP_LOCK.equals(dpId)) clearPendingLock();
        emit("Comando recusado: " + code);
      }
    });
  }

  public void setAutoLockEnabled(boolean enabled) {
    preferences.edit().putBoolean(PREF_AUTO_LOCK, enabled).apply();
    raw("[AUTO_LOCK] enabled=" + enabled + " distance=" + preferences.getString(PREF_AUTO_LOCK_DISTANCE, DISTANCE_MEDIUM));
    event(enabled ? "Bloqueio automático por distância ativado" : "Bloqueio automático por distância desativado");
    emitState();
    if (enabled) evaluateAutoLock();
  }

  public void setAutoLockDistance(String distance) {
    if (!DISTANCE_SHORT.equals(distance) && !DISTANCE_MEDIUM.equals(distance) && !DISTANCE_LONG.equals(distance)) return;
    preferences.edit().putString(PREF_AUTO_LOCK_DISTANCE, distance).apply();
    int lockRssi = autoLockThreshold(distance);
    raw("[AUTO_LOCK] distance=" + distance + " lockAt=" + lockRssi + " unlockAt=" + (lockRssi + 8) + " dBm");
    emitState();
    evaluateAutoLock();
  }

  public void setBatteryRechargeMinGapHours(int hours) {
    if (!validBatteryRechargeGapHours(hours)) return;
    preferences.edit().putInt(PREF_BATTERY_RECHARGE_MIN_GAP_HOURS, hours).apply();
    event("Detecção de recarga configurada para " + hours + " h");
    emitState();
  }

  public void resolveBatteryRecharge(boolean startNewCycle) {
    if (pendingBatteryRechargePercent == null || pendingBatteryRechargeDetectedAt == null) return;
    if (startNewCycle) {
      batteryObservedMin = pendingBatteryRechargeMin;
      batteryObservedMax = pendingBatteryRechargeMax;
      batteryCycleStartedAt = pendingBatteryRechargeDetectedAt;
      persistBatteryObservation();
      raw("BATTERY CYCLE CONFIRMED {\"min\":" + batteryObservedMin + ",\"max\":" + batteryObservedMax + ",\"startedAt\":" + batteryCycleStartedAt + "}");
      event("Novo ciclo de bateria iniciado");
    } else {
      mergePendingBatteryObservation();
      persistBatteryObservation();
      raw("BATTERY CYCLE REJECTED {\"candidate\":" + pendingBatteryRechargePercent + "}");
      event("Ciclo de bateria atual preservado");
    }
    clearPendingBatteryRecharge();
    emitState();
  }

  public void unpair() {
    if (device == null) return;
    pairing = T4AState.Pairing.REMOVING;
    emit("Removendo pareamento…");
    provisioner.remove(device.id, new T4AContracts.ResultCallback() {
      @Override public void onSuccess() { handler.post(() -> clearDevice("T4A liberado para outro aplicativo")); }
      @Override public void onError(String code, String error) {
        pairing = T4AState.Pairing.PAIRED;
        emit("Falha ao remover pareamento: " + code);
      }
    });
  }

  private void queryHomes() {
    provisioner.loadPrimaryHome(new T4AContracts.Callback<T4AContracts.Home>() {
      @Override public void onSuccess(T4AContracts.Home home) {
        handler.post(() -> {
          if (home == null || home.id == 0) {
            homeId = 0L;
            homeName = "";
            pairing = T4AState.Pairing.NO_HOME;
            emit("Conta sem casa configurada");
            return;
          }
          homeId = home.id;
          homeName = home.name;
          if (home.devices.isEmpty()) clearDevice("Nenhum T4A pareado");
          else attach(withRememberedBleAddress(home.devices.get(0)));
        });
      }
      @Override public void onError(String code, String error) {
        handler.post(() -> emit("Falha ao carregar T4A: " + code));
      }
    });
  }

  private void attach(T4AContracts.Device value) {
    transport.detach();
    value = withRememberedBleAddress(value);
    device = value;
    if (!value.mac.isEmpty()) preferences.edit().putString(PREF_BLE_ADDRESS, value.mac).apply();
    pairing = T4AState.Pairing.PAIRED;
    dps.clear();
    schema.clear();
    if (value.schema != null) {
      for (Map.Entry<String, T4AContracts.DpSchema> entry : value.schema.entrySet()) {
        T4AContracts.DpSchema item = entry.getValue();
        schema.put(entry.getKey(), new T4AState.DpInfo(item.code, item.mode, item.type));
      }
    }
    if (value.dps != null) {
      raw("INITIAL " + JSON.toJSONString(value.dps));
      mergeDps(value.dps, false);
    }
    applyRememberedLock();
    final T4AContracts.Device attached = value;
    transport.attach(attached, new T4AContracts.DeviceListener() {
      @Override public void onDpUpdate(String id, Map<String, Object> update) {
        handler.post(() -> {
          raw("RX " + JSON.toJSONString(update));
          mergeDps(update, true);
          connected = transport.isConnected(attached.id);
          emitState();
        });
      }
      @Override public void onRemoved(String id) { handler.post(() -> clearDevice("T4A removido")); }
      @Override public void onConnectionChanged(String id, boolean online) {
        handler.post(() -> { connected = online; emitState(); });
      }
      @Override public void onDeviceInfoChanged(String id) { handler.post(T4ABackend.this::queryHomes); }
    });
    connectPairedDevice();
    emit("T4A pareado");
  }

  private void connectPairedDevice() {
    if (device == null) return;
    transport.connect(device);
    message = connected ? "T4A conectado" : "T4A pareado · conectando…";
    emitState();
  }

  private void refreshFromCache() {
    if (device == null) return;
    T4AContracts.Device latest = transport.cachedDevice(device.id);
    if (latest != null) {
      device = withRememberedBleAddress(latest);
      mergeDps(latest.dps, false);
    }
    connected = transport.isConnected(device.id);
    message = connected ? "T4A conectado" : "T4A pareado · aguardando aproximação";
    emitState();
  }

  private T4AContracts.Device withRememberedBleAddress(T4AContracts.Device value) {
    if (value == null) return null;
    String address = value.mac;
    if (address.isEmpty() && device != null && device.id.equals(value.id) && !device.mac.isEmpty()) address = device.mac;
    if (address.isEmpty() && candidate != null && !candidate.address.isEmpty()) address = candidate.address;
    if (address.isEmpty()) address = preferences.getString(PREF_BLE_ADDRESS, "");
    if (!address.isEmpty()) preferences.edit().putString(PREF_BLE_ADDRESS, address).apply();
    if (address.equals(value.mac)) return value;
    return new T4AContracts.Device(value.id, value.name, address, value.uuid, value.dps, value.schema);
  }

  private String rssiAddress() {
    if (device != null && !device.mac.isEmpty()) return device.mac;
    return preferences.getString(PREF_BLE_ADDRESS, "");
  }

  private void clearDevice(String reason) {
    transport.detach();
    device = null;
    candidate = null;
    connected = false;
    rssi = 0;
    preferences.edit().remove(PREF_BLE_ADDRESS).apply();
    clearPendingLock();
    clearPendingBatteryRecharge();
    dps.clear();
    schema.clear();
    pendingDps.clear();
    pairing = T4AState.Pairing.UNPAIRED;
    emit(reason);
  }

  private void emit(String value) { message = value; emitState(); }
  private void event(String value) { listener.onEvent(value); }
  private void raw(String value) { listener.onRawLog(value); }

  private void emitState() {
    String name = device == null ? "" : device.name;
    String mac = device == null ? "" : device.mac;
    listener.onState(new T4AState(
        authenticated,
        account,
        homeId,
        homeName,
        pairing,
        connected,
        name,
        mac,
        rssi,
        dps,
        schema,
        pendingDps.keySet(),
        preferences.getBoolean(PREF_AUTO_LOCK, false),
        preferences.getString(PREF_AUTO_LOCK_DISTANCE, DISTANCE_MEDIUM),
        batteryObservedMin,
        batteryObservedMax,
        batteryCycleStartedAt,
        batteryRechargeMinGapHours(),
        pendingBatteryRechargePercent,
        pendingBatteryRechargeDetectedAt,
        message));
  }

  private void mergeDps(Map<String, Object> update, boolean confirmsCommand) {
    if (update == null) return;
    Map<String, Object> accepted = canonicalDps(update);
    if (!confirmsCommand) {
      accepted.remove(DP_SPEED);
      accepted.remove(DP_BATTERY);
      accepted.remove(DP_LOCK);
    } else filterLiveBattery(accepted);
    if (confirmsCommand) {
      for (String id : accepted.keySet()) {
        if (!DP_LOCK.equals(id) && pendingDps.remove(id) != null && !QUIET_DPS.contains(id)) event("DP " + id + " confirmado pelo T4A");
      }
    }
    if (accepted.containsKey(DP_LOCK) && pendingLockValue != null) {
      Object reported = accepted.get(DP_LOCK);
      boolean confirmed = String.valueOf(pendingLockValue).equalsIgnoreCase(String.valueOf(reported));
      if (confirmed) {
        clearPendingLock();
        event("Trava confirmada pelo T4A");
      } else if (System.currentTimeMillis() < pendingLockUntil) {
        accepted.remove(DP_LOCK);
        event("Estado antigo da trava ignorado enquanto aguarda confirmação");
      } else {
        clearPendingLock();
        event("Trava não confirmou o estado solicitado");
      }
    }
    if (confirmsCommand && accepted.containsKey(DP_LOCK)) {
      boolean reported = booleanValue(accepted.get(DP_LOCK));
      accepted.put(DP_LOCK, reported);
      preferences.edit().putBoolean(PREF_LOCK_KNOWN, true).putBoolean(PREF_LOCK_VALUE, reported).apply();
    }
    dps.putAll(accepted);
  }

  private void filterLiveBattery(Map<String, Object> accepted) {
    if (!accepted.containsKey(DP_BATTERY)) return;
    Integer percent = batteryPercent(accepted.get(DP_BATTERY));
    if (percent == null || percent < 0 || percent > 100) {
      accepted.remove(DP_BATTERY);
      return;
    }
    accepted.put(DP_BATTERY, percent);
    trackLiveBattery(percent);
  }

  private void restoreBatteryObservation() {
    int min = preferences.getInt(PREF_BATTERY_OBSERVED_MIN, -1);
    int max = preferences.getInt(PREF_BATTERY_OBSERVED_MAX, -1);
    long startedAt = preferences.getLong(PREF_BATTERY_CYCLE_STARTED_AT, 0L);
    batteryObservedMin = min >= 0 ? min : null;
    batteryObservedMax = max >= 0 ? max : null;
    batteryCycleStartedAt = startedAt > 0L ? startedAt : null;
  }

  private void trackLiveBattery(int percent) {
    long now = System.currentTimeMillis();
    int previousLast = preferences.getInt(PREF_BATTERY_LAST_LIVE_PERCENT, -1);
    long previousLastAt = preferences.getLong(PREF_BATTERY_LAST_LIVE_AT, 0L);
    boolean hasCycle = batteryObservedMin != null && batteryObservedMax != null;
    long rechargeMinGapMs = batteryRechargeMinGapHours() * 60L * 60L * 1000L;
    boolean rechargeEvidence = hasCycle
        && pendingBatteryRechargePercent == null
        && previousLast >= 0
        && previousLastAt > 0L
        && now - previousLastAt >= rechargeMinGapMs
        && percent >= previousLast + BATTERY_RECHARGE_MIN_RISE
        && percent >= batteryObservedMax - BATTERY_RECHARGE_MAX_DISTANCE;

    if (!hasCycle) {
      batteryObservedMin = percent;
      batteryObservedMax = percent;
      batteryCycleStartedAt = now;
      persistBatteryObservation();
    } else if (rechargeEvidence) {
      pendingBatteryRechargePercent = percent;
      pendingBatteryRechargeMin = percent;
      pendingBatteryRechargeMax = percent;
      pendingBatteryRechargeDetectedAt = now;
      raw("BATTERY RECHARGE CANDIDATE {\"previousMin\":" + batteryObservedMin + ",\"previousMax\":" + batteryObservedMax + ",\"firstLive\":" + percent + ",\"gapHours\":" + batteryRechargeMinGapHours() + "}");
      event("Possível recarga de bateria detectada");
    } else if (pendingBatteryRechargePercent != null) {
      if (pendingBatteryRechargeMin == null || percent < pendingBatteryRechargeMin) pendingBatteryRechargeMin = percent;
      if (pendingBatteryRechargeMax == null || percent > pendingBatteryRechargeMax) pendingBatteryRechargeMax = percent;
    } else {
      boolean changed = false;
      if (percent < batteryObservedMin) { batteryObservedMin = percent; changed = true; }
      if (percent > batteryObservedMax) { batteryObservedMax = percent; changed = true; }
      if (batteryCycleStartedAt == null) { batteryCycleStartedAt = now; changed = true; }
      if (changed) persistBatteryObservation();
    }

    preferences.edit().putInt(PREF_BATTERY_LAST_LIVE_PERCENT, percent).putLong(PREF_BATTERY_LAST_LIVE_AT, now).apply();
  }

  private void mergePendingBatteryObservation() {
    if (pendingBatteryRechargeMin != null && (batteryObservedMin == null || pendingBatteryRechargeMin < batteryObservedMin)) batteryObservedMin = pendingBatteryRechargeMin;
    if (pendingBatteryRechargeMax != null && (batteryObservedMax == null || pendingBatteryRechargeMax > batteryObservedMax)) batteryObservedMax = pendingBatteryRechargeMax;
  }

  private void persistBatteryObservation() {
    if (batteryObservedMin == null || batteryObservedMax == null || batteryCycleStartedAt == null) return;
    preferences.edit()
        .putInt(PREF_BATTERY_OBSERVED_MIN, batteryObservedMin)
        .putInt(PREF_BATTERY_OBSERVED_MAX, batteryObservedMax)
        .putLong(PREF_BATTERY_CYCLE_STARTED_AT, batteryCycleStartedAt)
        .apply();
  }

  private void clearPendingBatteryRecharge() {
    pendingBatteryRechargePercent = null;
    pendingBatteryRechargeMin = null;
    pendingBatteryRechargeMax = null;
    pendingBatteryRechargeDetectedAt = null;
  }

  private int batteryRechargeMinGapHours() {
    int value = preferences.getInt(PREF_BATTERY_RECHARGE_MIN_GAP_HOURS, DEFAULT_BATTERY_RECHARGE_MIN_GAP_HOURS);
    return validBatteryRechargeGapHours(value) ? value : DEFAULT_BATTERY_RECHARGE_MIN_GAP_HOURS;
  }

  private boolean validBatteryRechargeGapHours(int hours) { return hours == 1 || hours == 2 || hours == 4 || hours == 8; }
  private Integer batteryPercent(Object value) { return integerValue(value); }
  private Integer integerValue(Object value) {
    if (value instanceof Number) return ((Number) value).intValue();
    try { return Integer.parseInt(String.valueOf(value)); }
    catch (NumberFormatException ignored) { return null; }
  }
  private boolean booleanValue(Object value) {
    return value != null && (Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(value.toString()) || "1".equals(value.toString()));
  }

  private Map<String, Object> canonicalDps(Map<String, Object> update) {
    Map<String, Object> normalized = new HashMap<>();
    for (Map.Entry<String, Object> entry : update.entrySet()) {
      String incoming = entry.getKey();
      String id = incoming;
      if (!schema.containsKey(incoming)) {
        for (Map.Entry<String, T4AState.DpInfo> known : schema.entrySet()) {
          String code = known.getValue().code;
          if (code != null && code.equalsIgnoreCase(incoming)) {
            id = known.getKey();
            break;
          }
        }
      }
      normalized.put(id, entry.getValue());
    }
    return normalized;
  }

  private void clearPendingLock() { pendingLockValue = null; pendingLockUntil = 0L; }
  private void applyRememberedLock() {
    if (preferences.getBoolean(PREF_LOCK_KNOWN, false)) dps.put(DP_LOCK, preferences.getBoolean(PREF_LOCK_VALUE, true));
  }

  private void pollRssi() {
    if (device == null) return;
    String address = rssiAddress();
    if (address.isEmpty()) {
      int previousRssi = rssi;
      rssi = 0;
      if (previousRssi != 0 && preferences.getBoolean(PREF_AUTO_LOCK, false)) raw("[AUTO_LOCK] RSSI " + previousRssi + " -> unavailable");
      emitState();
      return;
    }
    long requestStarted = System.currentTimeMillis();
    lastRssiRead = requestStarted;
    try {
      transport.readRssi(address, (success, value) -> handler.post(() -> {
        lastRssiResponse = System.currentTimeMillis();
        int previousRssi = rssi;
        if (success && value != 0) {
          rssi = value;
          rssiFailures = 0;
          if (preferences.getBoolean(PREF_AUTO_LOCK, false) && previousRssi != value) logAutoLockRssi(previousRssi, value);
        } else {
          rssi = 0;
          if (preferences.getBoolean(PREF_AUTO_LOCK, false) && previousRssi != 0) raw("[AUTO_LOCK] RSSI " + previousRssi + " -> unavailable");
          if (++rssiFailures >= 3) recoverRssi();
        }
        evaluateAutoLock();
        emitState();
      }));
      handler.postDelayed(() -> {
        if (running && connected && lastRssiResponse < requestStarted) recoverRssi();
      }, RSSI_RESPONSE_TIMEOUT_MS);
    } catch (RuntimeException ignored) {
      int previousRssi = rssi;
      rssi = 0;
      if (preferences.getBoolean(PREF_AUTO_LOCK, false) && previousRssi != 0) raw("[AUTO_LOCK] RSSI " + previousRssi + " -> unavailable");
      recoverRssi();
      emitState();
    }
  }

  private void recoverRssi() {
    rssiFailures = 0;
    lastRssiResponse = System.currentTimeMillis();
    connectPairedDevice();
  }

  private int autoLockThreshold(String distance) {
    return DISTANCE_SHORT.equals(distance) ? -40 : DISTANCE_LONG.equals(distance) ? -80 : -60;
  }

  private void logAutoLockRssi(int previousRssi, int currentRssi) {
    String distance = preferences.getString(PREF_AUTO_LOCK_DISTANCE, DISTANCE_MEDIUM);
    int lockRssi = autoLockThreshold(distance);
    int unlockRssi = lockRssi + 8;
    String lockState = Boolean.TRUE.equals(dps.get(DP_LOCK)) ? "unlocked" : "locked";
    raw("[AUTO_LOCK] RSSI " + (previousRssi == 0 ? "initial" : previousRssi) + " -> " + currentRssi + " dBm distance=" + distance + " lockAt=" + lockRssi + " unlockAt=" + unlockRssi + " state=" + lockState);
  }

  private void evaluateAutoLock() {
    if (!preferences.getBoolean(PREF_AUTO_LOCK, false) || !connected || rssi == 0 || pendingLockValue != null) return;
    boolean unlocked = Boolean.TRUE.equals(dps.get(DP_LOCK));
    String distance = preferences.getString(PREF_AUTO_LOCK_DISTANCE, DISTANCE_MEDIUM);
    int lockRssi = autoLockThreshold(distance);
    int unlockRssi = lockRssi + 8;
    if (rssi <= lockRssi && unlocked) {
      raw("[AUTO_LOCK] action=lock rssi=" + rssi + " threshold=" + lockRssi + " dBm");
      event("Sinal distante (" + rssi + " dBm): bloqueando");
      publish(DP_LOCK, false);
    } else if (rssi >= unlockRssi && !unlocked) {
      raw("[AUTO_LOCK] action=unlock rssi=" + rssi + " threshold=" + unlockRssi + " dBm");
      event("Sinal próximo (" + rssi + " dBm): desbloqueando");
      publish(DP_LOCK, true);
    }
  }
}
