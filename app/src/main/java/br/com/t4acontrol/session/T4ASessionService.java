package br.com.t4acontrol.session;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import br.com.t4acontrol.MainActivity;
import br.com.t4acontrol.T4AApplication;
import br.com.t4acontrol.backend.T4ABackend;
import br.com.t4acontrol.backend.T4AState;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Process-level owner of the live T4A session.
 *
 * <p>The service owns exactly one {@link T4ABackend}. Activities can come and go without tearing
 * down provisioning, BLE transport or the last known state. Provider-specific wiring stays in the
 * application composition root; this service only talks to the neutral backend contract.
 */
public final class T4ASessionService extends Service implements T4ABackend.Listener {
  public static final String ACTION_STOP = "br.com.t4acontrol.session.STOP";

  private static final String CHANNEL_ID = "t4a_session";
  private static final int NOTIFICATION_ID = 4101;

  private final Set<T4ASession.Listener> listeners = new CopyOnWriteArraySet<>();
  private final SessionBinder binder = new SessionBinder();

  private T4ABackend backend;
  private T4AState lastState;

  @Override
  public void onCreate() {
    super.onCreate();
    createNotificationChannel();
    startForeground(
        NOTIFICATION_ID,
        notification("Sessão T4A iniciando…"),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    backend = ((T4AApplication) getApplication()).createSessionBackend(this);
    backend.start();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_STOP.equals(intent.getAction())) {
      stopForeground(STOP_FOREGROUND_REMOVE);
      stopSelf();
      return START_NOT_STICKY;
    }
    return START_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public void onDestroy() {
    listeners.clear();
    if (backend != null) {
      backend.destroy();
      backend = null;
    }
    super.onDestroy();
  }

  @Override
  public void onState(T4AState state) {
    lastState = state;
    updateNotification(state);
    for (T4ASession.Listener listener : listeners) listener.onState(state);
  }

  @Override
  public void onEvent(String event) {
    for (T4ASession.Listener listener : listeners) listener.onEvent(event);
  }

  @Override
  public void onRawLog(String entry) {
    for (T4ASession.Listener listener : listeners) listener.onRawLog(entry);
  }

  private void addListener(T4ASession.Listener listener) {
    if (listener == null) return;
    listeners.add(listener);
    if (lastState != null) listener.onState(lastState);
  }

  private void removeListener(T4ASession.Listener listener) {
    if (listener != null) listeners.remove(listener);
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
