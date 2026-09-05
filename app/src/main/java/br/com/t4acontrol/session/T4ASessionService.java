package br.com.t4acontrol.session;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import br.com.t4acontrol.BuildConfig;
import br.com.t4acontrol.MainActivity;
import br.com.t4acontrol.T4AApplication;
import br.com.t4acontrol.backend.AutoLightController;
import br.com.t4acontrol.backend.T4ABackend;
import br.com.t4acontrol.backend.T4AState;
import br.com.t4acontrol.backend.location.LocationSnapshot;
import br.com.t4acontrol.backend.mqtt.MqttTelemetryCoordinator;
import br.com.t4acontrol.location.AndroidLocationProvider;
import br.com.t4acontrol.mqtt.MqttSettingsActivity;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Process-level owner of the live T4A session. */
public final class T4ASessionService extends Service
    implements T4ABackend.Listener, MqttTelemetryCoordinator.Listener {
  public static final String ACTION_STOP = "br.com.t4acontrol.session.STOP";

  private static final String TAG = "T4ASession";
  private static final String CHANNEL_ID = "t4a_session";
  private static final int NOTIFICATION_ID = 4101;
  private static final String DP_LIGHT = "8";
  private static final long FIRST_STATE_RECOVERY_MS = 5_000L;

  private final Set<T4ASession.Listener> listeners = new CopyOnWriteArraySet<>();
  private final SessionBinder binder = new SessionBinder();
  private final String instanceId = Long.toHexString(SystemClock.elapsedRealtime());
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  private T4ABackend backend;
  private AutoLightController autoLightController;
  private AndroidAmbientLightMonitor ambientLightMonitor;
  private MqttTelemetryCoordinator mqttTelemetry;
  private AndroidLocationProvider locationProvider;
  private AndroidBleRssiMonitor androidBleRssiMonitor;
  private ConnectivityManager connectivityManager;
  private ConnectivityManager.NetworkCallback networkCallback;
  private T4AState lastState;
  private String activeNetworkTag = "NET";
  private String lastNetworkSignature = "";
  private boolean firstStateRecoveryScheduled;
  private boolean firstStateRecoveryAttempted;

  @Override
  public void onCreate() {
    super.onCreate();
    debug("CREATE instance=" + instanceId);
    createNotificationChannel();
    startForeground(
        NOTIFICATION_ID,
        notification("RideDash iniciando…"),
        foregroundServiceTypes());
    startNetworkDiagnostics();

    androidBleRssiMonitor =
        new AndroidBleRssiMonitor(
            this,
            (mac, rssi, status) -> {
              if (rssi != null) {
                rawTagged("BT", "RSSI " + rssi + " dBm mac=" + mac + " source=android_scan");
              } else {
                rawTagged("BT", "RSSI unavailable mac=" + mac + " source=android_scan status=" + status);
              }
            });

    T4AApplication application = (T4AApplication) getApplication();
    autoLightController =
        application.createAutoLightController(
            new AutoLightController.Listener() {
              @Override
              public void onAutomaticCommand(boolean enabled, float lux, float thresholdLux) {
                if (backend != null && lastState != null && lastState.connected) {
                  backend.publish(DP_LIGHT, enabled);
                }
              }

              @Override
              public void onRawLog(String entry) {
                if (entry != null && !entry.isBlank()) forwardRaw(entry);
              }
            });

    mqttTelemetry = application.createMqttTelemetryCoordinator(this);
    mqttTelemetry.start();
    locationProvider =
        new AndroidLocationProvider(
            this,
            new AndroidLocationProvider.Listener() {
              @Override
              public void onLocation(LocationSnapshot snapshot) {
                rawTagged(
                    "GPS",
                    "FIX lat="
                        + snapshot.latitude
                        + " lon="
                        + snapshot.longitude
                        + " accuracy="
                        + snapshot.accuracyMeters);
                if (mqttTelemetry != null) mqttTelemetry.onLocation(snapshot);
              }

              @Override
              public void onUnavailable(String reason) {
                rawTagged("GPS", "UNAVAILABLE " + reason);
              }
            });

    createAndStartBackend("create");

    ambientLightMonitor =
        new AndroidAmbientLightMonitor(
            this,
            new AndroidAmbientLightMonitor.Listener() {
              @Override
              public void onScreenInteractive(boolean interactive) {
                if (autoLightController == null) return;
                autoLightController.setScreenInteractive(interactive);
                emitAutoLightState();
              }

              @Override
              public void onLux(float lux) {
                if (autoLightController == null) return;
                autoLightController.onLux(lux);
                emitAutoLightState();
              }

              @Override
              public void onUnavailable(String reason) {
                rawTagged("ANDROID", "LIGHT_SENSOR unavailable=" + reason);
              }
            });
    ambientLightMonitor.start();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? "<restart>" : String.valueOf(intent.getAction());
    rawTagged("ANDROID", "START instance=" + instanceId + " action=" + action);
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
      return START_NOT_STICKY;
    }
    return START_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    debug("BIND instance=" + instanceId);
    return binder;
  }

  @Override
  public boolean onUnbind(Intent intent) {
    rawTagged("ANDROID", "UNBIND instance=" + instanceId);
    return true;
  }

  @Override
  public void onRebind(Intent intent) {
    super.onRebind(intent);
    rawTagged("ANDROID", "REBIND instance=" + instanceId + " state=" + (lastState == null ? "none" : "ready"));
    scheduleFirstStateRecoveryIfNeeded();
  }

  @Override
  public void onDestroy() {
    rawTagged("ANDROID", "DESTROY instance=" + instanceId);
    mainHandler.removeCallbacksAndMessages(null);
    stopNetworkDiagnostics();
    if (ambientLightMonitor != null) {
      ambientLightMonitor.close();
      ambientLightMonitor = null;
    }
    if (autoLightController != null) {
      autoLightController.close();
      autoLightController = null;
    }
    if (androidBleRssiMonitor != null) {
      androidBleRssiMonitor.close();
      androidBleRssiMonitor = null;
    }
    if (locationProvider != null) {
      locationProvider.close();
      locationProvider = null;
    }
    if (mqttTelemetry != null) {
      mqttTelemetry.stop();
      mqttTelemetry = null;
    }
    if (backend != null) {
      backend.destroy();
      backend = null;
    }
    listeners.clear();
    super.onDestroy();
  }

  @Override
  public void onState(T4AState state) {
    T4AState previous = lastState;
    if (autoLightController != null) {
      if (!state.pendingDps.contains(DP_LIGHT)) {
        autoLightController.setLightState(dpBoolean(state.dps.get(DP_LIGHT)));
      }
      autoLightController.setControlAvailable(state.connected);
    }
    T4AState decorated = decorateAutoLight(state);
    lastState = decorated;
    firstStateRecoveryScheduled = false;
    if (previous == null) {
      rawTagged(
          "ANDROID",
          "SESSION_STATE first authenticated="
              + decorated.authenticated
              + " pairing="
              + decorated.pairing
              + " connected="
              + decorated.connected);
    }
    if (previous == null || previous.connected != decorated.connected) {
      rawTagged("SDK", decorated.connected ? "CONNECTED" : "DISCONNECTED");
    }
    if (androidBleRssiMonitor != null) {
      androidBleRssiMonitor.setTarget(decorated.mac, decorated.connected);
    }
    if (mqttTelemetry != null) mqttTelemetry.onState(decorated);
    if (locationProvider != null) {
      locationProvider.setMoving(scooterMoving(decorated));
      locationProvider.setActive(decorated.connected);
    }
    updateNotification(decorated);
    for (T4ASession.Listener listener : listeners) listener.onState(decorated);
  }

  @Override
  public void onEvent(String event) {
    String source = sdkEvent(event) ? "SDK" : "T4A";
    rawTagged(source, "EVENT " + event);
    if (!operationalEvent(event)) return;
    for (T4ASession.Listener listener : listeners) listener.onEvent(event);
  }

  @Override
  public void onRawLog(String entry) {
    if (entry == null || entry.isBlank()) return;
    String value = entry;
    if (value.startsWith("[BT] RSSI ")) {
      value = "[SDK]" + value.substring(4);
    }
    if (value.startsWith("[")) {
      forwardRaw(value);
    } else {
      rawTagged("T4A", value);
    }
  }

  @Override
  public void onMqttEvent(String event) {
    rawTagged("MQTT", "EVENT " + event);
  }

  @Override
  public void onMqttRawLog(String entry) {
    rawTagged("MQTT", entry);
  }

  private void createAndStartBackend(String reason) {
    T4AApplication application = (T4AApplication) getApplication();
    backend = application.createSessionBackend(this);
    rawTagged("ANDROID", "SESSION_BACKEND start reason=" + reason + " instance=" + instanceId);
    backend.start();
  }

  private void scheduleFirstStateRecoveryIfNeeded() {
    if (lastState != null || backend == null || firstStateRecoveryScheduled || firstStateRecoveryAttempted) return;
    firstStateRecoveryScheduled = true;
    mainHandler.postDelayed(
        () -> {
          firstStateRecoveryScheduled = false;
          if (lastState != null || backend == null || listeners.isEmpty()) return;
          firstStateRecoveryAttempted = true;
          rawTagged(
              "ANDROID",
              "SESSION_STALLED no_state_after_ms="
                  + FIRST_STATE_RECOVERY_MS
                  + " action=restart_backend instance="
                  + instanceId);
          try {
            backend.destroy();
          } catch (RuntimeException error) {
            rawTagged("ANDROID", "SESSION_BACKEND destroy_error=" + error.getClass().getSimpleName());
          }
          backend = null;
          createAndStartBackend("first_state_recovery");
        },
        FIRST_STATE_RECOVERY_MS);
  }

  private void rawTagged(String source, String message) {
    forwardRaw("[" + source + "] " + message);
  }

  private void forwardRaw(String value) {
    debug("RAW " + value);
    for (T4ASession.Listener listener : listeners) listener.onRawLog(value);
  }

  private boolean operationalEvent(String event) {
    if (event == null || event.isBlank()) return false;
    return event.startsWith("T4A ")
        || event.startsWith("Trava ")
        || event.startsWith("Bloqueio automático")
        || event.startsWith("Novo ciclo de bateria")
        || event.startsWith("Recarga de bateria");
  }

  private boolean sdkEvent(String event) {
    if (event == null) return false;
    String value = event.toLowerCase(java.util.Locale.ROOT);
    return value.contains("login")
        || value.contains("conta")
        || value.contains("casa")
        || value.contains("pareamento falhou")
        || value.contains("falha ao")
        || value.contains("comando recusado")
        || value.contains("autentic");
  }

  private void addListener(T4ASession.Listener listener) {
    if (listener == null) return;
    listeners.add(listener);
    listener.onRawLog(
        "VERSION {\"versionName\":\""
            + BuildConfig.VERSION_NAME
            + "\",\"versionCode\":"
            + BuildConfig.VERSION_CODE
            + "}");
    listener.onRawLog(
        "[ANDROID] UI_ATTACHED instance="
            + instanceId
            + " state="
            + (lastState == null ? "none" : "ready")
            + " backend="
            + (backend == null ? "none" : "ready"));
    emitCurrentNetwork(listener);
    if (lastState != null) listener.onState(lastState);
    else scheduleFirstStateRecoveryIfNeeded();
  }

  private void removeListener(T4ASession.Listener listener) {
    if (listener != null) listeners.remove(listener);
  }

  private void emitAutoLightState() {
    if (lastState == null) return;
    lastState = decorateAutoLight(lastState);
    for (T4ASession.Listener listener : listeners) listener.onState(lastState);
  }

  private T4AState decorateAutoLight(T4AState state) {
    T4AState.AutoLightState autoLight =
        autoLightController == null ? state.autoLight : autoLightController.snapshot();
    return new T4AState(
        state.authenticated,
        state.account,
        state.homeId,
        state.homeName,
        state.pairing,
        state.connected,
        state.deviceName,
        state.mac,
        state.rssi,
        state.dps,
        state.schema,
        state.pendingDps,
        state.autoLockEnabled,
        state.autoLockDistance,
        state.rssiObservedBest,
        state.rssiObservedWorst,
        state.rssiStableWorst,
        state.rssiShortMin,
        state.rssiMediumMin,
        state.rssiLongMin,
        state.rssiCalibrationReady,
        state.batteryObservedMin,
        state.batteryObservedMax,
        state.batteryCycleStartedAt,
        state.batteryRechargeMinGapHours,
        state.pendingBatteryRechargePercent,
        state.pendingBatteryRechargeDetectedAt,
        state.message,
        autoLight);
  }

  private void startNetworkDiagnostics() {
    connectivityManager = getSystemService(ConnectivityManager.class);
    if (connectivityManager == null) return;
    networkCallback =
        new ConnectivityManager.NetworkCallback() {
          @Override
          public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
            String tag = networkTag(capabilities);
            boolean validated =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            boolean internet =
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            String signature = tag + ":" + validated + ":" + internet;
            activeNetworkTag = tag;
            if (signature.equals(lastNetworkSignature)) return;
            lastNetworkSignature = signature;
            rawTagged(tag, "AVAILABLE internet=" + internet + " validated=" + validated);
          }

          @Override
          public void onLost(Network network) {
            rawTagged(activeNetworkTag, "LOST");
            lastNetworkSignature = "";
            activeNetworkTag = "NET";
          }
        };
    try {
      connectivityManager.registerDefaultNetworkCallback(networkCallback);
    } catch (RuntimeException error) {
      rawTagged("ANDROID", "NETWORK_CALLBACK unavailable=" + error.getClass().getSimpleName());
    }
  }

  private void stopNetworkDiagnostics() {
    if (connectivityManager == null || networkCallback == null) return;
    try {
      connectivityManager.unregisterNetworkCallback(networkCallback);
    } catch (RuntimeException ignored) {
      // Callback may already have been removed by the framework.
    }
    networkCallback = null;
    connectivityManager = null;
  }

  private String networkTag(NetworkCapabilities capabilities) {
    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "NET/WIFI";
    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "NET/CELL";
    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "NET/ETH";
    if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "NET/VPN";
    return "NET";
  }

  private void emitCurrentNetwork(T4ASession.Listener listener) {
    if (connectivityManager == null) return;
    try {
      Network network = connectivityManager.getActiveNetwork();
      NetworkCapabilities caps =
          network == null ? null : connectivityManager.getNetworkCapabilities(network);
      if (caps == null) {
        listener.onRawLog("[NET] UNAVAILABLE");
        return;
      }
      String tag = networkTag(caps);
      listener.onRawLog(
          "["
              + tag
              + "] CURRENT internet="
              + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
              + " validated="
              + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
    } catch (RuntimeException error) {
      listener.onRawLog("[ANDROID] NETWORK_STATE error=" + error.getClass().getSimpleName());
    }
  }

  private void debug(String message) {
    if (BuildConfig.DEBUG) Log.d(TAG, message);
  }

  private void createNotificationChannel() {
    NotificationChannel channel =
        new NotificationChannel(
            CHANNEL_ID, "Sessão T4A", NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("Mantém a conexão Bluetooth com o T4A ativa em segundo plano.");
    getSystemService(NotificationManager.class).createNotificationChannel(channel);
  }

  private Notification notification(String text) {
    Intent openIntent = new Intent(this, MainActivity.class);
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    Intent mqttIntent = new Intent(this, MqttSettingsActivity.class);
    PendingIntent mqtt =
        PendingIntent.getActivity(
            this,
            2,
            mqttIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    Intent stopIntent = new Intent(this, T4ASessionService.class).setAction(ACTION_STOP);
    PendingIntent stop =
        PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    return new Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("RideDash")
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .addAction(new Notification.Action.Builder(null, "MQTT", mqtt).build())
        .addAction(new Notification.Action.Builder(null, "Encerrar", stop).build())
        .build();
  }

  private void updateNotification(T4AState state) {
    String text;
    if (state.connected) text = "T4A conectado";
    else if (state.pairing == T4AState.Pairing.PAIRED) text = "T4A pareado · aguardando aproximação";
    else if (state.message != null && !state.message.isEmpty()) text = state.message;
    else text = "Sessão T4A ativa";
    getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
  }

  private int foregroundServiceTypes() {
    int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
    if (hasLocationPermission()) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
    return types;
  }

  private boolean hasLocationPermission() {
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  private void refreshForegroundTypes() {
    try {
      startForeground(NOTIFICATION_ID, notification("Sessão T4A ativa"), foregroundServiceTypes());
    } catch (RuntimeException error) {
      rawTagged("ANDROID", "LOCATION_FOREGROUND unavailable=" + error.getClass().getSimpleName());
    }
  }

  private boolean scooterMoving(T4AState state) {
    if (state == null || !state.dps.containsKey("2")) return false;
    try {
      return Double.parseDouble(String.valueOf(state.dps.get("2"))) / 10.0 > 1.0;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private boolean dpBoolean(Object value) {
    return Boolean.TRUE.equals(value)
        || (value != null && "true".equalsIgnoreCase(String.valueOf(value)))
        || (value != null && "1".equals(String.valueOf(value)));
  }

  public final class SessionBinder extends Binder implements T4ASession {
    @Override
    public void addListener(Listener listener) {
      T4ASessionService.this.addListener(listener);
    }

    @Override
    public void removeListener(Listener listener) {
      T4ASessionService.this.removeListener(listener);
    }

    @Override
    public void setUiForeground(boolean foreground) {
      if (backend != null) backend.setForeground(foreground);
      if (foreground) {
        refreshForegroundTypes();
        if (locationProvider != null) locationProvider.refreshPermissions();
      }
    }

    @Override public void login(String email, String password) { if (backend != null) backend.login(email, password); }
    @Override public void scan() { if (backend != null) backend.scan(); }
    @Override public void pair() { if (backend != null) backend.pair(); }
    @Override public void publish(String dpId, Object value) { if (backend != null) backend.publish(dpId, value); }

    @Override
    public void setLight(boolean enabled) {
      if (autoLightController != null) autoLightController.onManualLightChanged(enabled);
      if (backend != null) backend.publish(DP_LIGHT, enabled);
      emitAutoLightState();
    }

    @Override public void setAutoLockEnabled(boolean enabled) { if (backend != null) backend.setAutoLockEnabled(enabled); }
    @Override public void setAutoLockDistance(String distance) { if (backend != null) backend.setAutoLockDistance(distance); }

    @Override
    public void setAutoLightEnabled(boolean enabled) {
      if (autoLightController != null) autoLightController.setEnabled(enabled);
      emitAutoLightState();
    }

    @Override
    public void setAutoLightAutoOffEnabled(boolean enabled) {
      if (autoLightController != null) autoLightController.setAutoOffEnabled(enabled);
      emitAutoLightState();
    }

    @Override
    public void setAutoLightThresholdLux(float lux) {
      if (autoLightController != null) autoLightController.setThresholdLux(lux);
      emitAutoLightState();
    }

    @Override public void setBatteryRechargeMinGapHours(int hours) { if (backend != null) backend.setBatteryRechargeMinGapHours(hours); }
    @Override public void resolveBatteryRecharge(boolean startNewCycle) { if (backend != null) backend.resolveBatteryRecharge(startNewCycle); }
    @Override public void unpair() { if (backend != null) backend.unpair(); }
  }
}