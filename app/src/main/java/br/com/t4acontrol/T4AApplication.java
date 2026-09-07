package br.com.t4acontrol;

import android.app.Application;
import br.com.t4acontrol.backend.AutoLightController;
import br.com.t4acontrol.backend.T4ABackend;
import br.com.t4acontrol.backend.T4AProvisioner;
import br.com.t4acontrol.backend.T4ASdk;
import br.com.t4acontrol.backend.T4ATransport;
import br.com.t4acontrol.backend.TuyaT4APlatform;
import br.com.t4acontrol.backend.TuyaT4AProvisioner;
import br.com.t4acontrol.backend.mqtt.AndroidMqttConfigurationStore;
import br.com.t4acontrol.backend.mqtt.MqttConfigurationStore;
import br.com.t4acontrol.backend.mqtt.MqttTelemetryCoordinator;
import br.com.t4acontrol.backend.persistence.AndroidT4AStateStore;
import br.com.t4acontrol.ble.DelayedFallbackTransport;
import br.com.t4acontrol.ble.NativeBleTransport;
import br.com.t4acontrol.mqtt.DefaultMqttSettings;
import br.com.t4acontrol.mqtt.MqttSettings;
import br.com.t4acontrol.mqtt.PahoMqttTransport;
import br.com.t4acontrol.runtime.AndroidClock;
import br.com.t4acontrol.runtime.AndroidScheduler;

public final class T4AApplication extends Application {
  private MqttConfigurationStore mqttConfigurationStore;
  private MqttSettings mqttSettings;

  /**
   * Application composition root for a live T4A session.
   *
   * <p>Master keeps the native BLE runtime plus the Tuya fallback, but deliberately excludes every
   * ThingClips introspection/probe layer. Experimental SDK diagnostics live only on
   * feature/direct-ble-transport-fallback.
   */
  public T4ABackend createSessionBackend(T4ABackend.Listener listener) {
    T4AProvisioner provisioner = new TuyaT4AProvisioner();
    T4ATransport tuyaTransport = new TuyaT4APlatform(listener::onRawLog);
    T4ATransport delayedTuya =
        new DelayedFallbackTransport(tuyaTransport, listener::onRawLog);
    T4ATransport transport =
        new NativeBleTransport(this, delayedTuya, listener::onRawLog);
    return new T4ABackend(
        new AndroidT4AStateStore(this),
        provisioner,
        transport,
        listener,
        new AndroidClock(),
        new AndroidScheduler());
  }

  /** Creates the neutral automatic-headlight policy while keeping Android adapters out of backend. */
  public AutoLightController createAutoLightController(AutoLightController.Listener listener) {
    return new AutoLightController(
        new AndroidT4AStateStore(this),
        new AndroidScheduler(),
        listener);
  }

  /** Returns the UI-facing MQTT settings interface; persistence stays behind the composition root. */
  public MqttSettings mqttSettings() {
    return mqttSettings;
  }

  /** Creates the runtime MQTT telemetry coordinator with its provider adapter hidden here. */
  public MqttTelemetryCoordinator createMqttTelemetryCoordinator(
      MqttTelemetryCoordinator.Listener listener) {
    return new MqttTelemetryCoordinator(
        mqttConfigurationStore, new PahoMqttTransport(), listener);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    // Production/master must never enable ThingClips SDK debug logging/introspection.
    T4ASdk.initialize(this, false);
    mqttConfigurationStore = new AndroidMqttConfigurationStore(getApplicationContext());
    mqttSettings = new DefaultMqttSettings(mqttConfigurationStore);
  }

  @Override
  public void onTerminate() {
    T4ASdk.destroy();
    super.onTerminate();
  }
}
