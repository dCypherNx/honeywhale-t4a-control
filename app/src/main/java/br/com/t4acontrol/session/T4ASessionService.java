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
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import br.com.t4acontrol.BuildConfig;
import br.com.t4acontrol.MainActivity;
import br.com.t4acontrol.T4AApplication;
import br.com.t4acontrol.backend.T4ABackend;
import br.com.t4acontrol.backend.T4AState;
import br.com.t4acontrol.backend.location.LocationSnapshot;
import br.com.t4acontrol.backend.mqtt.MqttTelemetryCoordinator;
import br.com.t4acontrol.location.AndroidLocationProvider;
import br.com.t4acontrol.mqtt.MqttSettingsActivity;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Process-level owner of the live T4A session.
 *
 * <p>The service owns exactly one {@link T4ABackend}, one MQTT telemetry coordinator and one
 * Android location adapter. Activities can come and go without tearing down the runtime.
 */
public final class T4ASessionService extends Service
    implements T4ABackend.Listener, MqttTelemetryCoordinator.Listener {
  public static final String ACTION_STOP = "br.com.t4acontrol.session.STOP";

  private static final String TAG = "T4ASession";
  private static final String CHANNEL_ID = "t4a_session";
  private static final int NOTIFICATION_ID = 4101;

  private final Set<T4ASession.Listener> listeners = new CopyOnWriteArraySet<>();
  private final SessionBinder binder = new SessionBinder();
  private final String instanceId = Long.toHexString(SystemClock.elapsedRealtime());

  private T4ABackend backend;
  private MqttTelemetryCoordinator mqttTelemetry;
  private AndroidLocationProvider locationProvider;
  private T4AState lastState;

  @Override
  public void onCreate() {
    super.onCreate();
    debug("CREATE instance=" + instanceId);
    createNotificationChannel();
    startForeground(
        NOTIFICATION_ID,
        notification("Sessão T4A iniciando…"),
        foregroundServiceTypes());
    T4AApplication application = (T4AApplication) getApplication();
    mqttTelemetry = application.createMqttTelemetryCoordinator(this);
    mqttTelemetry.start();
    locationProvider =
        new AndroidLocationProvider(
            this,
            new AndroidLocationProvider.Listener() {
              @Override
              public void onLocation(LocationSnapshot snapshot) {
                debug(
                    "LOCATION lat="
                        + snapshot.latitude
                        + " lon="
                        + snapshot.longitude
                        + " accuracy="
                        + snapshot.accuracyMeters);
                if (mqttTelemetry != null) mqttTelemetry.onLocation(snapshot);
              }

              @Override
              public void onUnavailable(String reason) {
                debug("LOCATION unavailable: " + reason);
              }
            });
    backend = application.createSessionBackend(this);
    backend.start();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String action = intent == null ? "<restart>" : String.valueOf(intent.getAction());
    debug("START instance=" + instanceId + " action=" + action);
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
    debug("UNBIND instance=" + instanceId);
    return true;
  }

  @Override
  public void onRebind(Intent intent) {
    super.onRebind(intent);
    debug("REBIND instance=" + instanceId);
  }

  @Override
  public void onDestroy() {
    debug("DESTROY instance=" + instanceId);
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
    lastState = state;
    if (mqttTelemetry != null) mqttTelemetry.onState(state);
    if (locationProvider != null) {
      locationProvider.setMoving(scooterMoving(state));
      locationProvider.setActive(state.connected);
    }
    updateNotification(state);
    for (T4ASession.Listener listener : listeners) listener.onState(state);
  }

  @Override
  public void onEvent(String event) {
    debug("EVENT " + event);
    for (T4ASession.Listener listener : listeners) listener.onEvent(event);
  }

  @Override
  public void onRawLog(String entry) {
    debug("RAW " + entry);
    for (T4ASession.Listener listener : listeners) listener.onRawLog(entry);
  }

  @Override
  public void onMqttEvent(String event) {
    debug("MQTT EVENT " + event);
    for (T4ASession.Listener listener : listeners) listener.onEvent(event);
  }

  @Override
  public void onMqttRawLog(String entry) {
    String value = "MQTT " + entry;
    debug(value);
    for (T4ASession.Listener listener : listeners) listener.onRawLog(value);
  }

  private void addListener(T4ASession.Listener listener) {
    if (listener == null) return;
    listeners.add(listener);
    if (lastState != null) listener.onState(lastState);
  }

  private void removeListener(T4ASession.Listener listener) {
    if (listener != null) listeners.remove(listener);
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
        .setContentTitle("T4A Control")
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
      debug("LOCATION foreground type unavailable: " + error.getClass().getSimpleName());
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

    @Override
    public void login(String email, String password) {
      if (backend != null) backend.login(email, password);
    }

    @Override
    public void scan() {
      if (backend != null) backend.scan();
    }

    @Override
    public void pair() {
      if (backend != null) backend.pair();
    }

    @Override
    public void publish(String dpId, Object value) {
      if (backend != null) backend.publish(dpId, value);
    }

    @Override
    public void setAutoLockEnabled(boolean enabled) {
      if (backend != null) backend.setAutoLockEnabled(enabled);
    }

    @Override
    public void setAutoLockDistance(String distance) {
      if (backend != null) backend.setAutoLockDistance(distance);
    }

    @Override
    public void unpair() {
      if (backend != null) backend.unpair();
    }
  }
}
