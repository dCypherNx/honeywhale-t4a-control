# MQTT telemetry architecture

## Scope

This branch implements outbound MQTT telemetry. It connects to the configured broker, publishes retained availability and T4A telemetry, and reconnects after transport loss. It does not subscribe to any topic and does not accept remote control commands.

## Ownership

```text
MqttSettingsActivity
        |
        v
   MqttSettings
        |
        v
DefaultMqttSettings
        |
        v
MqttConfigurationStore <-----------------------------+
        |                                             |
        v                                             |
AndroidMqttConfigurationStore                         |
                                                      |
T4ASessionService                                     |
        |                                             |
        +--> T4ABackend --> T4AState                  |
        |                     |                       |
        |                     v                       |
        +------------> MqttTelemetryCoordinator ------+
                              |
                              v
                        MqttTransport
                              |
                              v
                      PahoMqttTransport
                              |
                              v
                         MQTT broker
```

`MqttSettingsActivity` owns Android widgets and presentation only. It does not read/write MQTT persistence, use Android Keystore APIs, instantiate MQTT clients, or import backend MQTT classes.

`MqttSettings` is the UI-facing contract. `DefaultMqttSettings` parses editable values and delegates persistence.

`MqttConfiguration` is the backend model. `MqttConfigurationStore` is the persistence boundary and exposes a cheap revision value so the live runtime can detect saved configuration changes without coupling to the Activity.

`AndroidMqttConfigurationStore` stores non-secret configuration in app-private preferences and encrypts the MQTT password with AES-GCM using an Android Keystore key. No secret is logged.

`MqttTelemetryCoordinator` owns broker connection policy, configuration reload, reconnect cadence, availability state, heartbeat and T4A-state-to-telemetry mapping. It knows only the provider-neutral `MqttTransport` interface.

`PahoMqttTransport` is the only class allowed to import Eclipse Paho. It is created by `T4AApplication`, the composition root.

`T4ASessionService` owns the runtime lifetime. Activity destruction/recreation does not tear down BLE or MQTT.

## Configuration

- enabled
- broker host
- port
- TLS enabled
- username
- password
- client ID
- base topic
- keep-alive seconds

Defaults are port `1883`, client ID `t4a-control`, base topic `t4a`, and keep-alive `60` seconds.

## Topics

Given base topic `t4a`:

- `t4a/status`: retained QoS 1 string, `online` or `offline`.
- `t4a/telemetry`: retained QoS 1 JSON document.

The broker LWT is `offline` on `<base>/status`. A clean shutdown also publishes `offline` best-effort before disconnecting.

Telemetry currently contains:

- `connected`
- `device_name`
- `mac`
- `rssi_dbm`
- `battery_percent`
- `speed_kmh`
- `odometer_km`
- `trip_km`
- `ride_time_s`
- `locked`
- `headlight`
- `cruise`
- `mode`
- `start_mode`
- `unit_setting`
- `timestamp_ms`

A publish occurs when the semantic telemetry changes or after 30 seconds as a heartbeat.

## Explicit non-goals

- no MQTT subscriptions;
- no remote T4A control through MQTT;
- no Home Assistant discovery in this step;
- no geolocation yet; location will be introduced as its own producer before being appended to telemetry.
