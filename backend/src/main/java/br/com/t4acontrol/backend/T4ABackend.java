package br.com.t4acontrol.backend;

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
  private static final int AUTO_LOCK_SHORT_THRESHOLD_DBM = -40;
  private static final int AUTO_LOCK_MEDIUM_THRESHOLD_DBM = -65;
  private static final int AUTO_LOCK_LONG_THRESHOLD_DBM = -90;
  private static final int AUTO_LOCK_REQUIRED_SAMPLES = 1;
  private static final String DISTANCE_SHORT = "short",
      DISTANCE_MEDIUM = "medium",
      DISTANCE_LONG = "long";

  private final Listener listener;
  private final T4AProvisioner provisioner;
  private final T4ATransport transport;
  private final T4AStateStore stateStore;
  private final RssiCalibration rssiCalibration;
  private final Clock clock;
  private final Scheduler scheduler;
  private final Map<String, Object> dps = new HashMap<>();
  private final Map<String, T4AState.DpInfo> schema = new HashMap<>();
  private final Map<String, Object> pendingDps = new HashMap<>();

  private boolean authenticated;
  private String account = "";
  private T4AState.Pairing pairing = T4AState.Pairing.UNPAIRED;
  private boolean connected;
  private boolean awaitingLockRxAfterConnect;
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
  private int proximityTransitionSamples;
  private Boolean proximitySampleNear;
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
      if (connected && clock.nowMillis() - lastRssiRead >= RSSI_REFRESH_MS) pollRssi();
      long delay = connected ? STATE_REFRESH_MS : (foreground ? FOREGROUND_RECONNECT_MS : BACKGROUND_RECONNECT_MS);
      scheduler.postDelayed(this, delay);
    }
  };

  public T4ABackend(
      T4AStateStore stateStore,
      T4AProvisioner provisioner,
      T4ATransport transport,
      Listener listener,
      Clock clock,
      Scheduler scheduler) {
    this.listener = listener;
    this.provisioner = provisioner;
    this.transport = transport;
    this.stateStore = stateStore;
    this.clock = clock;
    this.scheduler = scheduler;
    this.rssiCalibration = new RssiCalibration(stateStore.rssiObservedBest(), stateStore.rssiObservedWorst());
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
    scheduler.cancel(maintenance);
    scheduler.post(maintenance);
  }

  public void setForeground(boolean value) {
    foreground = value;
    if (running) {
      scheduler.cancel(maintenance);
      scheduler.post(maintenance);
    }
  }

  public void destroy() {
    running = false;
    scheduler.cancelAll();
    try { transport.destroy(); }
    finally { if (provisioner != (Object) transport) provisioner.destroy(); }
  }

  public void login(String email, String password) {
    message = "Autenticando…";
    emitState();
    provisioner.login("55", email, password, new T4AContracts.Callback<String>() {
      @Override public void onSuccess(String restoredAccount) {
        scheduler.post(() -> {
          authenticated = true;
          account = restoredAccount.isEmpty() ? email : restoredAccount;
          event("Conta autenticada");
          queryHomes();
        });
      }
      @Override public void onError(String code, String error) {
        scheduler.post(() -> emit("Falha no login: " + error));
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
        scheduler.post(() -> {
          if (found == null || found.bound || candidate != null) return;
          candidate = found;
          if (!found.address.isEmpty()) stateStore.setBleAddress(found.address);
          rssi = found.rssi;
          pairing = T4AState.Pairing.READY;
          provisioner.stopDiscovery();
          emit("T4A pronto para parear");
        });
      }
      @Override public void onError(String code, String error) {
        scheduler.post(() -> emit("Falha ao procurar T4A: " + code));
      }
    });
  }

  public void pair() {
    if (candidate == null || homeId == 0) return;
    pairing = T4AState.Pairing.PAIRING;
    raw("[APP] PAIR requested discoveryId=" + candidate.discoveryId
        + " address=" + candidate.address
        + " bound=" + candidate.bound);
    emit("Pareando…");
    provisioner.pair(homeId, candidate, new T4AContracts.Callback<T4AContracts.Device>() {
      @Override public void onSuccess(T4AContracts.Device result) {
        scheduler.post(() -> {
          raw("[APP] PAIR success deviceId=" + (result == null ? "" : result.id));
          attach(withRememberedBleAddress(result));
        });
      }
      @Override public void onError(String code, String error) {
        scheduler.post(() -> {
          raw("[APP] PAIR error code=" + code + " message=" + T4AContracts.value(error));
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
      pendingLockUntil = clock.nowMillis() + LOCK_CONFIRM_TIMEOUT_MS;
      boolean remembered = Boolean.TRUE.equals(value);
      stateStore.setRememberedLock(remembered);
      dps.put(DP_LOCK, remembered);
    }
    Map<String, Object> command = Collections.singletonMap(dpId, value);
    raw("[SDK] TX " + JSON.toJSONString(command));
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
    stateStore.setAutoLockEnabled(enabled);
    resetAutoLockSamples();
    raw("[APP] AUTO_LOCK enabled=" + enabled + " distance=" + autoLockDistance());
    event(enabled ? "Bloqueio automático por distância ativado" : "Bloqueio automático por distância desativado");
    emitState();
  }

  public void setAutoLockDistance(String distance) {
    if (!DISTANCE_SHORT.equals(distance) && !DISTANCE_MEDIUM.equals(distance) && !DISTANCE_LONG.equals(distance)) return;
    stateStore.setAutoLockDistance(distance);
    resetAutoLockSamples();
    int threshold = autoLockThreshold(distance);
    raw("[APP] AUTO_LOCK distance=" + distance + " threshold=" + threshold + " dBm");
    emitState();
  }

  public void setBatteryRechargeMinGapHours(int hours) {
    if (!validBatteryRechargeGapHours(hours)) return;
    stateStore.setBatteryRechargeMinGapHours(hours);
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
      raw("[APP] BATTERY CYCLE CONFIRMED {\"min\":" + batteryObservedMin + ",\"max\":" + batteryObservedMax + ",\"startedAt\":" + batteryCycleStartedAt + "}");
      event("Novo ciclo de bateria iniciado");
    } else {
      mergePendingBatteryObservation();
      persistBatteryObservation();
      raw("[APP] BATTERY CYCLE REJECTED {\"candidate\":" + pendingBatteryRechargePercent + "}");
      event("Ciclo de bateria atual preservado");
    }
    clearPendingBatteryRecharge();
    emitState();
  }

  public void unpair() {
    if (device == null) return;
    final String removingDeviceId = device.id;
    raw("[APP] UNPAIR requested deviceId=" + removingDeviceId
        + " connected=" + transport.isConnected(removingDeviceId));
    pairing = T4AState.Pairing.REMOVING;
    emit("Removendo pareamento…");
    provisioner.remove(removingDeviceId, new T4AContracts.ResultCallback() {
      @Override public void onSuccess() {
        scheduler.post(() -> {
          raw("[APP] UNPAIR success deviceId=" + removingDeviceId);
          clearDevice("T4A liberado para outro aplicativo");
        });
      }
      @Override public void onError(String code, String error) {
        scheduler.post(() -> {
          raw("[APP] UNPAIR error deviceId=" + removingDeviceId
              + " code=" + code
              + " message=" + T4AContracts.value(error));
          pairing = T4AState.Pairing.PAIRED;
          emit("Falha ao remover pareamento: " + code);
        });
      }
    });
  }

  private void queryHomes() {
    provisioner.loadPrimaryHome(new T4AContracts.Callback<T4AContracts.Home>() {
      @Override public void onSuccess(T4AContracts.Home home) {
        scheduler.post(() -> {
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
        scheduler.post(() -> emit("Falha ao carregar T4A: " + code));
      }
    });
  }

  private void attach(T4AContracts.Device value) {
    transport.detach();
    value = withRememberedBleAddress(value);
    device = value;
    if (!value.mac.isEmpty()) stateStore.setBleAddress(value.mac);
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
      raw("[SDK] INITIAL " + JSON.toJSONString(value.dps));
      mergeDps(value.dps, false);
    }
    applyRememberedLock();
    final T4AContracts.Device attached = value;
    transport.attach(attached, new T4AContracts.DeviceListener() {
      @Override public void onDpUpdate(String id, Map<String, Object> update) {
        scheduler.post(() -> {
          recordConnectionTransition(transport.isConnected(attached.id));
          raw("[SDK] RX " + JSON.toJSONString(update));
          logFirstLockRxAfterConnect(update);
          mergeDps(update, true);
          emitState();
        });
      }
      @Override public void onRemoved(String id) { scheduler.post(() -> clearDevice("T4A removido")); }
      @Override public void onConnectionChanged(String id, boolean online) {
        scheduler.post(() -> { recordConnectionTransition(online); emitState(); });
      }
      @Override public void onDeviceInfoChanged(String id) { scheduler.post(T4ABackend.this::queryHomes); }
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
    recordConnectionTransition(transport.isConnected(device.id));
    message = connected ? "T4A conectado" : "T4A pareado · aguardando aproximação";
    emitState();
  }

  private T4AContracts.Device withRememberedBleAddress(T4AContracts.Device value) {
    if (value == null) return null;
    String address = value.mac;
    if (address.isEmpty() && device != null && device.id.equals(value.id) && !device.mac.isEmpty()) address = device.mac;
    if (address.isEmpty() && candidate != null && !candidate.address.isEmpty()) address = candidate.address;
    if (address.isEmpty()) address = stateStore.bleAddress();
    if (!address.isEmpty()) stateStore.setBleAddress(address);
    if (address.equals(value.mac)) return value;
    return new T4AContracts.Device(value.id, value.name, address, value.uuid, value.dps, value.schema);
  }

  private String rssiAddress() {
    if (device != null && !device.mac.isEmpty()) return device.mac;
    return stateStore.bleAddress();
  }

  private void clearDevice(String reason) {
    transport.detach();
    device = null;
    candidate = null;
    connected = false;
    awaitingLockRxAfterConnect = false;
    rssi = 0;
    resetAutoLockSamples();
    stateStore.clearBleAddress();
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
    RssiCalibration.Snapshot calibration = rssiCalibration.snapshot();
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
        stateStore.autoLockEnabled(),
        autoLockDistance(),
        calibration.best,
        calibration.worst,
        calibration.stableWorst,
        calibration.shortMin,
        calibration.mediumMin,
        calibration.longMin,
        calibration.ready,
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
    } else {
      filterLiveBattery(accepted);
      handleLiveLock(accepted);
    }
    if (confirmsCommand) {
      for (String id : accepted.keySet()) {
        if (!DP_LOCK.equals(id) && pendingDps.remove(id) != null && !QUIET_DPS.contains(id)) event("DP " + id + " confirmado pelo T4A");
      }
    }
    dps.putAll(accepted);
  }

  private void handleLiveLock(Map<String, Object> accepted) {
    if (!accepted.containsKey(DP_LOCK)) return;
    Object reported = accepted.get(DP_LOCK);
    if (pendingLockValue == null) {
      raw("[APP] LOCK RX ignored unsolicited reported=" + lockState(reported)
          + " remembered=" + rememberedLockState()
          + " local=" + lockState(dps.get(DP_LOCK)));
      accepted.remove(DP_LOCK);
      return;
    }

    boolean confirmed = String.valueOf(pendingLockValue).equalsIgnoreCase(String.valueOf(reported));
    if (confirmed) {
      clearPendingLock();
      event("Trava confirmada pelo T4A");
    } else if (clock.nowMillis() < pendingLockUntil) {
      event("Estado antigo da trava ignorado enquanto aguarda confirmação");
    } else {
      clearPendingLock();
      event("Trava não confirmou o estado solicitado");
    }
    accepted.remove(DP_LOCK);
  }

  private void filterLiveBattery(Map<String, Object> accepted) {
    if (!accepted.containsKey(DP_BATTERY)) return;
    Integer percent = batteryPercent(accepted.get(DP_BATTERY));
    if (percent == null || percent < 0 || percent > 100) {
      accepted.remove(DP_BATTERY);
      return;
    }
    if (isSpuriousFullBattery(percent)) {
      raw("[APP] BATTERY RX 100 ignored after recent sub-100 live reading last="
          + stateStore.batteryLastLivePercent());
      accepted.remove(DP_BATTERY);
      return;
    }
    accepted.put(DP_BATTERY, percent);
    trackLiveBattery(percent);
  }

  private boolean isSpuriousFullBattery(int percent) {
    if (percent != 100) return false;
    Integer previous = stateStore.batteryLastLivePercent();
    Long previousAt = stateStore.batteryLastLiveAt();
    if (previous == null || previous >= 100 || previousAt == null || previousAt <= 0L) return false;
    long rechargeMinGapMs = batteryRechargeMinGapHours() * 60L * 60L * 1000L;
    return clock.nowMillis() - previousAt < rechargeMinGapMs;
  }

  private void restoreBatteryObservation() {
    Integer min = stateStore.batteryObservedMin();
    Integer max = stateStore.batteryObservedMax();
    Long startedAt = stateStore.batteryCycleStartedAt();
    batteryObservedMin = min != null && min >= 0 ? min : null;
    batteryObservedMax = max != null && max >= 0 ? max : null;
    batteryCycleStartedAt = startedAt != null && startedAt > 0L ? startedAt : null;
  }

  private void trackLiveBattery(int percent) {
    long now = clock.nowMillis();
    Integer storedLast = stateStore.batteryLastLivePercent();
    Long storedLastAt = stateStore.batteryLastLiveAt();
    int previousLast = storedLast == null ? -1 : storedLast;
    long previousLastAt = storedLastAt == null ? 0L : storedLastAt;
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
      raw("[APP] BATTERY RECHARGE CANDIDATE {\"previousMin\":" + batteryObservedMin + ",\"previousMax\":" + batteryObservedMax + ",\"firstLive\":" + percent + ",\"gapHours\":" + batteryRechargeMinGapHours() + "}");
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

    stateStore.setBatteryLastLive(percent, now);
  }

  private void mergePendingBatteryObservation() {
    if (pendingBatteryRechargeMin != null && (batteryObservedMin == null || pendingBatteryRechargeMin < batteryObservedMin)) batteryObservedMin = pendingBatteryRechargeMin;
    if (pendingBatteryRechargeMax != null && (batteryObservedMax == null || pendingBatteryRechargeMax > batteryObservedMax)) batteryObservedMax = pendingBatteryRechargeMax;
  }

  private void persistBatteryObservation() {
    if (batteryObservedMin == null || batteryObservedMax == null || batteryCycleStartedAt == null) return;
    stateStore.setBatteryObservation(batteryObservedMin, batteryObservedMax, batteryCycleStartedAt);
  }

  private void clearPendingBatteryRecharge() {
    pendingBatteryRechargePercent = null;
    pendingBatteryRechargeMin = null;
    pendingBatteryRechargeMax = null;
    pendingBatteryRechargeDetectedAt = null;
  }

  private int batteryRechargeMinGapHours() {
    Integer stored = stateStore.batteryRechargeMinGapHours();
    int value = stored == null ? DEFAULT_BATTERY_RECHARGE_MIN_GAP_HOURS : stored;
    return validBatteryRechargeGapHours(value) ? value : DEFAULT_BATTERY_RECHARGE_MIN_GAP_HOURS;
  }

  private String autoLockDistance() {
    String value = stateStore.autoLockDistance();
    return value == null ? DISTANCE_MEDIUM : value;
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

  private void recordConnectionTransition(boolean online) {
    if (connected == online) return;
    connected = online;
    if (online) {
      awaitingLockRxAfterConnect = true;
      resetAutoLockSamples();
      raw("[APP] CONNECTION DISCONNECTED->CONNECTED autoLock="
          + stateStore.autoLockEnabled()
          + " rememberedLockKnown=" + (stateStore.rememberedLock() != null)
          + " rememberedLock=" + rememberedLockState()
          + " localLock=" + lockState(dps.get(DP_LOCK)));
    } else {
      awaitingLockRxAfterConnect = false;
      resetAutoLockSamples();
      rssiCalibration.resetWindow();
      raw("[APP] CONNECTION CONNECTED->DISCONNECTED autoLock="
          + stateStore.autoLockEnabled()
          + " rememberedLock=" + rememberedLockState()
          + " localLock=" + lockState(dps.get(DP_LOCK)));
    }
  }

  private void logFirstLockRxAfterConnect(Map<String, Object> update) {
    if (!awaitingLockRxAfterConnect || update == null) return;
    Map<String, Object> normalized = canonicalDps(update);
    if (!normalized.containsKey(DP_LOCK)) return;
    raw("[APP] CONNECTION firstLockRx=" + lockState(normalized.get(DP_LOCK))
        + " autoLock=" + stateStore.autoLockEnabled()
        + " rememberedLock=" + rememberedLockState()
        + " localLockBeforeRx=" + lockState(dps.get(DP_LOCK)));
    awaitingLockRxAfterConnect = false;
  }

  private String rememberedLockState() {
    Boolean remembered = stateStore.rememberedLock();
    if (remembered == null) return "unknown";
    return remembered ? "unlocked" : "locked";
  }

  private String lockState(Object value) {
    if (value == null) return "unknown";
    return booleanValue(value) ? "unlocked" : "locked";
  }

  private void clearPendingLock() { pendingLockValue = null; pendingLockUntil = 0L; }
  private void applyRememberedLock() {
    Boolean remembered = stateStore.rememberedLock();
    if (remembered != null) dps.put(DP_LOCK, remembered);
  }

  private void pollRssi() {
    if (device == null) return;
    String address = rssiAddress();
    if (address.isEmpty()) {
      int previousRssi = rssi;
      rssi = 0;
      resetAutoLockSamples();
      rssiCalibration.resetWindow();
      if (previousRssi != 0 && stateStore.autoLockEnabled()) raw("[BT] RSSI " + previousRssi + " -> unavailable");
      emitState();
      return;
    }
    long requestStarted = clock.nowMillis();
    lastRssiRead = requestStarted;
    try {
      transport.readRssi(address, (success, value) -> scheduler.post(() -> {
        lastRssiResponse = clock.nowMillis();
        int previousRssi = rssi;
        if (success && value != 0) {
          rssi = value;
          rssiFailures = 0;
          observeRssi(value);
          if (stateStore.autoLockEnabled() && previousRssi != value) logAutoLockRssi(previousRssi, value);
        } else {
          rssi = 0;
          resetAutoLockSamples();
          rssiCalibration.resetWindow();
          if (stateStore.autoLockEnabled() && previousRssi != 0) raw("[BT] RSSI " + previousRssi + " -> unavailable");
          if (++rssiFailures >= 3) recoverRssi();
        }
        evaluateAutoLock(true);
        emitState();
      }));
      scheduler.postDelayed(() -> {
        if (running && connected && lastRssiResponse < requestStarted) recoverRssi();
      }, RSSI_RESPONSE_TIMEOUT_MS);
    } catch (RuntimeException ignored) {
      int previousRssi = rssi;
      rssi = 0;
      resetAutoLockSamples();
      rssiCalibration.resetWindow();
      if (stateStore.autoLockEnabled() && previousRssi != 0) raw("[BT] RSSI " + previousRssi + " -> unavailable");
      recoverRssi();
      emitState();
    }
  }

  private void recoverRssi() {
    rssiFailures = 0;
    lastRssiResponse = clock.nowMillis();
    connectPairedDevice();
  }

  private int autoLockThreshold(String distance) {
    RssiCalibration.Snapshot calibration = rssiCalibration.snapshot();
    if (calibration.ready) return dynamicBandMinimum(calibration, distance);
    if (DISTANCE_SHORT.equals(distance)) return AUTO_LOCK_SHORT_THRESHOLD_DBM;
    if (DISTANCE_LONG.equals(distance)) return AUTO_LOCK_LONG_THRESHOLD_DBM;
    return AUTO_LOCK_MEDIUM_THRESHOLD_DBM;
  }

  private int dynamicBandMinimum(RssiCalibration.Snapshot calibration, String distance) {
    if (DISTANCE_SHORT.equals(distance)) return calibration.shortMin;
    if (DISTANCE_LONG.equals(distance)) return calibration.longMin;
    return calibration.mediumMin;
  }

  private void observeRssi(int value) {
    if (!rssiCalibration.observe(value)) return;
    RssiCalibration.Snapshot calibration = rssiCalibration.snapshot();
    if (calibration.best != null && calibration.worst != null) {
      stateStore.setRssiObservation(calibration.best, calibration.worst);
    }
    if (calibration.ready) {
      raw("[APP] RSSI CALIBRATION best=" + calibration.best
          + " worst=" + calibration.worst
          + " stableWorst=" + calibration.stableWorst
          + " bands={short:" + calibration.best + ".." + calibration.shortMin
          + ",medium:" + (calibration.shortMin - 1) + ".." + calibration.mediumMin
          + ",long:" + (calibration.mediumMin - 1) + ".." + calibration.longMin + "}");
    } else {
      raw("[APP] RSSI CALIBRATION learning best=" + calibration.best + " worst=" + calibration.worst);
    }
  }

  private void logAutoLockRssi(int previousRssi, int currentRssi) {
    String distance = autoLockDistance();
    int threshold = autoLockThreshold(distance);
    String lockState = Boolean.TRUE.equals(dps.get(DP_LOCK)) ? "unlocked" : "locked";
    raw("[BT] RSSI " + (previousRssi == 0 ? "initial" : previousRssi) + " -> " + currentRssi
        + " dBm autoLockDistance=" + distance + " threshold=" + threshold + " state=" + lockState);
  }

  private void evaluateAutoLock(boolean freshRssiSample) {
    if (!stateStore.autoLockEnabled() || !connected || rssi == 0 || pendingLockValue != null) {
      if (!stateStore.autoLockEnabled() || !connected || rssi == 0) resetAutoLockSamples();
      return;
    }

    boolean unlocked = Boolean.TRUE.equals(dps.get(DP_LOCK));
    String distance = autoLockDistance();
    int threshold = autoLockThreshold(distance);
    boolean near = rssi >= threshold;

    if ((near && unlocked) || (!near && !unlocked)) {
      resetAutoLockSamples();
      return;
    }
    if (!freshRssiSample) return;
    if (!confirmAutoLockSide(near, threshold)) return;

    resetAutoLockSamples();
    if (near) {
      raw("[APP] AUTO_LOCK action=unlock rssi=" + rssi + " threshold=" + threshold + " dBm");
      event("Sinal próximo (" + rssi + " dBm): desbloqueando");
      publish(DP_LOCK, true);
    } else {
      raw("[APP] AUTO_LOCK action=lock rssi=" + rssi + " threshold=" + threshold + " dBm");
      event("Sinal distante (" + rssi + " dBm): bloqueando");
      publish(DP_LOCK, false);
    }
  }

  private boolean confirmAutoLockSide(boolean near, int threshold) {
    if (proximitySampleNear == null || proximitySampleNear.booleanValue() != near) {
      proximitySampleNear = near;
      proximityTransitionSamples = 1;
    } else {
      proximityTransitionSamples++;
    }
    raw("[APP] AUTO_LOCK stability side=" + (near ? "near" : "far")
        + " sample=" + proximityTransitionSamples + "/" + AUTO_LOCK_REQUIRED_SAMPLES
        + " rssi=" + rssi + " threshold=" + threshold + " dBm");
    return proximityTransitionSamples >= AUTO_LOCK_REQUIRED_SAMPLES;
  }

  private void resetAutoLockSamples() {
    proximityTransitionSamples = 0;
    proximitySampleNear = null;
  }
}
