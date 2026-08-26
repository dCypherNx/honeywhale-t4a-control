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
  private static final Set<String> QUIET_DPS = Set.of("2", "3", "5", "6", "12");
  private static final long STATE_REFRESH_MS = 2000L;
  private static final long FOREGROUND_RECONNECT_MS = 5000L;
  private static final long BACKGROUND_RECONNECT_MS = 30000L;
  private static final long RSSI_REFRESH_MS = 2000L;
  private static final long RSSI_RESPONSE_TIMEOUT_MS = 6000L;
  private static final long LOCK_CONFIRM_TIMEOUT_MS = 5000L;
  private static final String PREF_LOCK_KNOWN = "lock_known";
  private static final String PREF_LOCK_VALUE = "lock_value";
  private static final String PREF_AUTO_LOCK = "auto_lock_distance";
  private static final String PREF_AUTO_LOCK_DISTANCE = "auto_lock_distance_level";
  private static final String DISTANCE_SHORT = "short",
      DISTANCE_MEDIUM = "medium",
      DISTANCE_LONG = "long";

  private final Listener listener;
  private final T4APlatform platform;
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
  private T4APlatform.Device device;
  private T4APlatform.DiscoveredDevice candidate;
  private int rssi;
  private boolean running;
  private boolean foreground = true;
  private long lastRssiRead;
  private long lastRssiResponse;
  private int rssiFailures;
  private Object pendingLockValue;
  private long pendingLockUntil;

  private final Runnable maintenance =
      new Runnable() {
        @Override
        public void run() {
          if (!running) return;
          refreshFromCache();
          if (device != null && !platform.isConnected(device.id)) {
            connectPairedDevice();
          }
          if (connected && System.currentTimeMillis() - lastRssiRead >= RSSI_REFRESH_MS) pollRssi();
          long delay =
              connected
                  ? STATE_REFRESH_MS
                  : (foreground ? FOREGROUND_RECONNECT_MS : BACKGROUND_RECONNECT_MS);
          handler.postDelayed(this, delay);
        }
      };

  public T4ABackend(Context context, Listener listener) {
    this(context, new TuyaT4APlatform(), listener);
  }

  public T4ABackend(Context context, T4APlatform platform, Listener listener) {
    this.listener = listener;
    this.platform = platform;
    this.preferences =
        context.getApplicationContext().getSharedPreferences("t4a_backend", Context.MODE_PRIVATE);
  }

  public void start() {
    running = true;
    foreground = true;
    String restoredAccount = platform.currentAccount();
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
    platform.destroy();
  }

  public void login(String email, String password) {
    message = "Autenticando…";
    emitState();
    platform.login(
        "55",
        email,
        password,
        new T4APlatform.Callback<String>() {
          @Override
          public void onSuccess(String restoredAccount) {
            handler.post(
                () -> {
                  authenticated = true;
                  account = restoredAccount.isEmpty() ? email : restoredAccount;
                  event("Conta autenticada");
                  queryHomes();
                });
          }

          @Override
          public void onError(String code, String error) {
            handler.post(() -> emit("Falha no login: " + error));
          }
        });
  }

  public void scan() {
    if (!authenticated || homeId == 0 || device != null) return;
    candidate = null;
    pairing = T4AState.Pairing.SCANNING;
    emit("Procurando T4A…");
    platform.startDiscovery(
        15000,
        new T4APlatform.DiscoveryListener() {
          @Override
          public void onDevice(T4APlatform.DiscoveredDevice found) {
            handler.post(
                () -> {
                  if (found == null || found.bound || candidate != null) return;
                  candidate = found;
                  rssi = found.rssi;
                  pairing = T4AState.Pairing.READY;
                  platform.stopDiscovery();
                  emit("T4A pronto para parear");
                });
          }

          @Override
          public void onError(String code, String error) {
            handler.post(() -> emit("Falha ao procurar T4A: " + code));
          }
        });
  }

  public void pair() {
    if (candidate == null || homeId == 0) return;
    pairing = T4AState.Pairing.PAIRING;
    emit("Pareando…");
    platform.pair(
        homeId,
        candidate,
        new T4APlatform.Callback<T4APlatform.Device>() {
          @Override
          public void onSuccess(T4APlatform.Device result) {
            handler.post(() -> attach(result));
          }

          @Override
          public void onError(String code, String error) {
            handler.post(
                () -> {
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
      preferences
          .edit()
          .putBoolean(PREF_LOCK_KNOWN, true)
          .putBoolean(PREF_LOCK_VALUE, remembered)
          .apply();
      dps.put(DP_LOCK, remembered);
    }
    Map<String, Object> command = Collections.singletonMap(dpId, value);
    raw("TX " + JSON.toJSONString(command));
    platform.publish(
        device.id,
        command,
        new T4APlatform.ResultCallback() {
          @Override
          public void onSuccess() {
            if (!QUIET_DPS.contains(dpId))
              event("Comando DP " + dpId + " aceito; aguardando confirmação");
          }

          @Override
          public void onError(String code, String error) {
            pendingDps.remove(dpId);
            if (DP_LOCK.equals(dpId)) clearPendingLock();
            emit("Comando recusado: " + code);
          }
        });
  }

  public void setAutoLockEnabled(boolean enabled) {
    preferences.edit().putBoolean(PREF_AUTO_LOCK, enabled).apply();
    event(
        enabled
            ? "Bloqueio automático por distância ativado"
            : "Bloqueio automático por distância desativado");
    emitState();
    if (enabled) evaluateAutoLock();
  }

  public void setAutoLockDistance(String distance) {
    if (!DISTANCE_SHORT.equals(distance)
        && !DISTANCE_MEDIUM.equals(distance)
        && !DISTANCE_LONG.equals(distance)) return;
    preferences.edit().putString(PREF_AUTO_LOCK_DISTANCE, distance).apply();
    emitState();
    evaluateAutoLock();
  }

  public void unpair() {
    if (device == null) return;
    pairing = T4AState.Pairing.REMOVING;
    emit("Removendo pareamento…");
    platform.remove(
        device.id,
        new T4APlatform.ResultCallback() {
          @Override
          public void onSuccess() {
            handler.post(() -> clearDevice("T4A liberado para outro aplicativo"));
          }

          @Override
          public void onError(String code, String error) {
            pairing = T4AState.Pairing.PAIRED;
            emit("Falha ao remover pareamento: " + code);
          }
        });
  }

  private void queryHomes() {
    platform.loadPrimaryHome(
        new T4APlatform.Callback<T4APlatform.Home>() {
          @Override
          public void onSuccess(T4APlatform.Home home) {
            handler.post(
                () -> {
                  if (home == null || home.id == 0) {
                    pairing = T4AState.Pairing.NO_HOME;
                    emit("Conta sem casa configurada");
                    return;
                  }
                  homeId = home.id;
                  if (home.devices.isEmpty()) clearDevice("Nenhum T4A pareado");
                  else attach(home.devices.get(0));
                });
          }

          @Override
          public void onError(String code, String error) {
            handler.post(() -> emit("Falha ao carregar T4A: " + code));
          }
        });
  }

  private void attach(T4APlatform.Device value) {
    platform.detach();
    device = value;
    pairing = T4AState.Pairing.PAIRED;
    dps.clear();
    schema.clear();
    if (value.schema != null) {
      for (Map.Entry<String, T4APlatform.DpSchema> entry : value.schema.entrySet()) {
        T4APlatform.DpSchema item = entry.getValue();
        schema.put(
            entry.getKey(), new T4AState.DpInfo(item.code, item.mode, item.type));
      }
    }
    if (value.dps != null) {
      raw("INITIAL " + JSON.toJSONString(value.dps));
      mergeDps(value.dps, false);
    }
    applyRememberedLock();
    platform.attach(
        value,
        new T4APlatform.DeviceListener() {
          @Override
          public void onDpUpdate(String id, Map<String, Object> update) {
            handler.post(
                () -> {
                  raw("RX " + JSON.toJSONString(update));
                  mergeDps(update, true);
                  connected = platform.isConnected(value.id);
                  emitState();
                });
          }

          @Override
          public void onRemoved(String id) {
            handler.post(() -> clearDevice("T4A removido"));
          }

          @Override
          public void onConnectionChanged(String id, boolean online) {
            handler.post(
                () -> {
                  connected = online;
                  emitState();
                });
          }

          @Override
          public void onDeviceInfoChanged(String id) {
            handler.post(T4ABackend.this::queryHomes);
          }
        });
    connectPairedDevice();
    emit("T4A pareado");
  }

  private void connectPairedDevice() {
    if (device == null) return;
    platform.connect(device);
    message = connected ? "T4A conectado" : "T4A pareado · conectando…";
    emitState();
  }

  private void refreshFromCache() {
    if (device == null) return;
    T4APlatform.Device latest = platform.cachedDevice(device.id);
    if (latest != null) {
      device = latest;
      mergeDps(latest.dps, false);
    }
    connected = platform.isConnected(device.id);
    message = connected ? "T4A conectado" : "T4A pareado · aguardando aproximação";
    emitState();
  }

  private void clearDevice(String reason) {
    platform.detach();
    device = null;
    candidate = null;
    connected = false;
    rssi = 0;
    clearPendingLock();
    dps.clear();
    schema.clear();
    pendingDps.clear();
    pairing = T4AState.Pairing.UNPAIRED;
    emit(reason);
  }

  private void emit(String value) {
    message = value;
    emitState();
  }

  private void event(String value) {
    listener.onEvent(value);
  }

  private void raw(String value) {
    listener.onRawLog(value);
  }

  private void emitState() {
    String name = device == null ? "" : device.name;
    String mac = device == null ? "" : device.mac;
    listener.onState(
        new T4AState(
            authenticated,
            account,
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
            message));
  }

  private void mergeDps(Map<String, Object> update, boolean confirmsCommand) {
    if (update == null) return;
    Map<String, Object> accepted = canonicalDps(update);
    // Velocidade é telemetria instantânea: somente uma notificação DP pode alterá-la.
    if (!confirmsCommand) accepted.remove("2");
    if (confirmsCommand)
      for (String id : accepted.keySet()) {
        if (!DP_LOCK.equals(id) && pendingDps.remove(id) != null && !QUIET_DPS.contains(id))
          event("DP " + id + " confirmado pelo T4A");
      }
    if (accepted.containsKey(DP_LOCK) && pendingLockValue != null) {
      Object reported = accepted.get(DP_LOCK);
      boolean confirmed =
          String.valueOf(pendingLockValue).equalsIgnoreCase(String.valueOf(reported));
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
    if (accepted.containsKey(DP_LOCK) && preferences.getBoolean(PREF_LOCK_KNOWN, false)) {
      accepted.put(DP_LOCK, preferences.getBoolean(PREF_LOCK_VALUE, true));
    }
    dps.putAll(accepted);
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

  private void clearPendingLock() {
    pendingLockValue = null;
    pendingLockUntil = 0L;
  }

  private void applyRememberedLock() {
    if (preferences.getBoolean(PREF_LOCK_KNOWN, false)) {
      dps.put(DP_LOCK, preferences.getBoolean(PREF_LOCK_VALUE, true));
    }
  }

  private void pollRssi() {
    if (device == null || device.mac.isEmpty()) return;
    long requestStarted = System.currentTimeMillis();
    lastRssiRead = requestStarted;
    try {
      platform.readRssi(
          device.mac,
          (success, value) ->
              handler.post(
                  () -> {
                    lastRssiResponse = System.currentTimeMillis();
                    if (success && value != 0) {
                      rssi = value;
                      rssiFailures = 0;
                    } else {
                      rssi = 0;
                      if (++rssiFailures >= 3) recoverRssi();
                    }
                    evaluateAutoLock();
                    emitState();
                  }));
      handler.postDelayed(
          () -> {
            if (running && connected && lastRssiResponse < requestStarted) recoverRssi();
          },
          RSSI_RESPONSE_TIMEOUT_MS);
    } catch (RuntimeException ignored) {
      rssi = 0;
      recoverRssi();
      emitState();
    }
  }

  private void recoverRssi() {
    rssiFailures = 0;
    lastRssiResponse = System.currentTimeMillis();
    connectPairedDevice();
  }

  private void evaluateAutoLock() {
    if (!preferences.getBoolean(PREF_AUTO_LOCK, false)
        || !connected
        || rssi == 0
        || pendingLockValue != null) return;
    boolean unlocked = Boolean.TRUE.equals(dps.get(DP_LOCK));
    String distance = preferences.getString(PREF_AUTO_LOCK_DISTANCE, DISTANCE_MEDIUM);
    int lockRssi =
        DISTANCE_SHORT.equals(distance) ? -40 : DISTANCE_LONG.equals(distance) ? -80 : -60;
    int unlockRssi = lockRssi + 8;
    if (rssi <= lockRssi && unlocked) {
      event("Sinal distante (" + rssi + " dBm): bloqueando");
      publish(DP_LOCK, false);
    } else if (rssi >= unlockRssi && !unlocked) {
      event("Sinal próximo (" + rssi + " dBm): desbloqueando");
      publish(DP_LOCK, true);
    }
  }
}
