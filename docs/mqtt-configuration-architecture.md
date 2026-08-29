# MQTT telemetry architecture

## Scope

This branch implements outbound MQTT telemetry, optional Home Assistant MQTT Device Discovery, retained availability, adaptive T4A geolocation and reconnect after transport loss. It does not subscribe to any topic and does not accept remote control commands.

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
        +--> AndroidLocationProvider --> LocationSnapshot
        |                     |                       |
        |                     v                       |
        +------------> MqttTelemetryCoordinator ------+
                              |              |
                              |              +--> HomeAssistantDiscovery
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

`AndroidLocationProvider` is the only Android location adapter. It converts framework `Location` objects into provider-neutral `LocationSnapshot` values. It is inactive while BLE is disconnected. While connected and stationary it uses a lower-frequency balanced-power request; when DP 2 reports more than 1 km/h it switches to a high-accuracy, higher-frequency request when fine location is available.

`MqttTelemetryCoordinator` owns broker connection policy, configuration reload, reconnect cadence, availability state, heartbeat, telemetry publication, location publication and discovery publication/removal. It knows only provider-neutral `T4AState`, `LocationSnapshot` and `MqttTransport` values.

`HomeAssistantDiscovery` builds the Home Assistant Device Discovery topic and JSON payload. It is provider-neutral and has no Android or Paho dependency.

`PahoMqttTransport` is the only class allowed to import Eclipse Paho. It is created by `T4AApplication`, the composition root.

`T4ASessionService` owns the runtime lifetime. Activity destruction/recreation does not tear down BLE, MQTT or an active location request. The service foreground types are `connectedDevice|location`; the app intentionally does not request `ACCESS_BACKGROUND_LOCATION` because the foreground service is started from a visible Activity.

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
- `t4a/location`: retained QoS 1 GPS JSON document containing the latest location captured while the T4A is connected.
- `homeassistant/device/t4a_<normalized-mac>/config`: retained QoS 1 Home Assistant Device Discovery configuration when discovery is enabled.

The broker LWT is `offline` on `<base>/status`. A clean shutdown also publishes `offline` best-effort before disconnecting.

When discovery is enabled, the discovery payload shares `<base>/telemetry` as the normal component `state_topic` and `<base>/status` as `availability_topic`. The device identifier is derived from the normalized T4A MAC so formatting changes such as `DC23529724A0` versus `DC:23:52:97:24:A0` do not create duplicate devices.

The GPS component is exposed as an MQTT `device_tracker` in the same T4A device. It consumes `<base>/location` as both state and JSON attributes topic, using `payload_reset` so Home Assistant derives zone/state from `latitude`, `longitude` and `gps_accuracy`.

When the discovery switch changes from enabled to disabled while connected, the retained discovery topic is cleared with an empty retained payload so Home Assistant removes the discovered device/components.

Discovery currently exposes:

- battery percentage;
- speed;
- total odometer;
- trip odometer;
- ride time;
- RSSI;
- Bluetooth connection;
- lock state;
- headlight;
- cruise control;
- riding mode;
- start mode;
- unit setting;
- GPS location as a `device_tracker`.

Telemetry includes known values only. Unknown DPS-derived values are omitted instead of being serialized as artificial zero/false measurements. Current fields are:

- `connected`
- `device_name`
- `mac`
- `rssi_dbm` when known
- `battery_percent` when known
- `speed_kmh` when known
- `odometer_km` when known
- `trip_km` when known
- `ride_time_s` when known
- `locked` when known
- `headlight` when known
- `cruise` when known
- `mode` when known
- `start_mode` when known
- `unit_setting` when known
- `latitude` / `longitude` after a location fix
- `gps_accuracy`
- `location_timestamp_ms`
- optional `gps_speed_kmh`
- optional `bearing_deg`
- optional `altitude_m`
- `timestamp_ms`

A telemetry publish occurs when the semantic telemetry changes or after 30 seconds as a heartbeat. Location is also published independently to `<base>/location` when a new geographic snapshot arrives; the retained topic preserves the latest fix without creating a separate heartbeat for stale positions.

## Location policy

- BLE disconnected: Android location updates are stopped.
- BLE connected and DP 2 <= 1 km/h: balanced-power updates, target 10 s / 10 m.
- BLE connected and DP 2 > 1 km/h: high-accuracy updates when fine location is available, target 2 s / 2 m.
- Approximate/coarse permission remains usable, but high-accuracy mode is not requested.
- A cached Android fix is accepted only when it is at most 30 seconds old.
- The provider is intentionally reusable by the future navigation layer instead of embedding location logic inside MQTT or the Tuya backend.

## Explicit non-goals

- no MQTT subscriptions;
- no remote T4A control through MQTT;
- no route parsing or turn-by-turn navigation yet;
- no `ACCESS_BACKGROUND_LOCATION` permission.
