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

`AndroidMqttConfigurationStore` stores non-secret configuration in app-private preferences and encrypts the MQTT password with AES-GCM using an Android Keystore key. No secret is logged. The legacy boolean TLS setting remains readable and is migrated to the explicit transport model on the next save.

`MqttTelemetryCoordinator` owns broker connection policy, configuration reload, reconnect cadence, availability state, heartbeat and T4A-state-to-telemetry mapping. It knows only the provider-neutral `MqttTransport` interface.

`PahoMqttTransport` is the only class allowed to import Eclipse Paho. It is created by `T4AApplication`, the composition root.

`T4ASessionService` owns the runtime lifetime. Activity destruction/recreation does not tear down BLE or MQTT.

## Configuration

- enabled
- broker host
- port
- transport: `TCP`, `TLS`, `WS` or `WSS`
- WebSocket path, used only for `WS`/`WSS`
- username
- password
- client ID
- base topic
- keep-alive seconds
- Home Assistant MQTT Discovery enabled/disabled

Default ports are `1883` for TCP, `8883` for TLS, `80` for WS and `443` for WSS. The default WebSocket path is `/mqtt`. Client ID defaults to `t4a-control`, base topic to `t4a`, keep-alive to `60` seconds, and Home Assistant discovery to disabled.

The discovery switch is persisted and exposed through the provider-neutral configuration model. This branch does not yet publish Home Assistant discovery payloads; enabling the option only prepares the saved configuration for that implementation.

The transport maps to provider-neutral server URIs:

- TCP: `tcp://host:port`
- TLS: `ssl://host:port`
- WS: `ws://host:port/path`
- WSS: `wss://host:port/path`

For the current external T4A infrastructure, the verified Cloudflare/Mosquitto endpoint is `wss://mqtt.jurgensen.net:443/mqtt`.

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
- no Home Assistant discovery payload publication yet;
- no geolocation yet; location will be introduced as its own producer before being appended to telemetry.
