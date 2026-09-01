package br.com.t4acontrol.session;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

/** Android adapter for TYPE_LIGHT that produces samples only while the screen is interactive. */
public final class AndroidAmbientLightMonitor implements SensorEventListener, AutoCloseable {
  public interface Listener {
    void onScreenInteractive(boolean interactive);
    void onLux(float lux);
    void onUnavailable(String reason);
  }

  private final SensorManager sensorManager;
  private final DisplayManager displayManager;
  private final PowerManager powerManager;
  private final Sensor lightSensor;
  private final Listener listener;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private boolean started;
  private boolean screenInteractive;
  private boolean sensorRegistered;

  private final DisplayManager.DisplayListener displayListener =
      new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { updateInteractiveState(); }
        @Override public void onDisplayRemoved(int displayId) { updateInteractiveState(); }
        @Override public void onDisplayChanged(int displayId) { updateInteractiveState(); }
      };

  public AndroidAmbientLightMonitor(Context context, Listener listener) {
    Context app = context.getApplicationContext();
    sensorManager = app.getSystemService(SensorManager.class);
    displayManager = app.getSystemService(DisplayManager.class);
    powerManager = app.getSystemService(PowerManager.class);
    lightSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
    this.listener = listener;
  }

  public void start() {
    if (started) return;
    started = true;
    if (displayManager != null) displayManager.registerDisplayListener(displayListener, mainHandler);
    if (lightSensor == null) listener.onUnavailable("TYPE_LIGHT unavailable");
    updateInteractiveState();
  }

  @Override
  public void onSensorChanged(SensorEvent event) {
    if (!started || !screenInteractive || event == null || event.sensor == null) return;
    if (event.sensor.getType() != Sensor.TYPE_LIGHT || event.values.length == 0) return;
    float lux = event.values[0];
    if (!Float.isFinite(lux) || lux < 0f) return;
    listener.onLux(lux);
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {
    // Ambient-light control uses the framework-provided lux value directly.
  }

  @Override
  public void close() {
    if (!started) return;
    started = false;
    unregisterSensor();
    if (displayManager != null) displayManager.unregisterDisplayListener(displayListener);
  }

  private void updateInteractiveState() {
    boolean interactive = powerManager != null && powerManager.isInteractive();
    if (interactive == screenInteractive && (interactive == sensorRegistered || lightSensor == null)) return;
    screenInteractive = interactive;
    if (interactive) registerSensor();
    else unregisterSensor();
    listener.onScreenInteractive(interactive);
  }

  private void registerSensor() {
    if (sensorRegistered || sensorManager == null || lightSensor == null) return;
    sensorRegistered = sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
    if (!sensorRegistered) listener.onUnavailable("TYPE_LIGHT registration failed");
  }

  private void unregisterSensor() {
    if (!sensorRegistered || sensorManager == null) return;
    sensorManager.unregisterListener(this, lightSensor);
    sensorRegistered = false;
  }
}
