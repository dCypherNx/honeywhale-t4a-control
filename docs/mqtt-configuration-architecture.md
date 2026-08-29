# MQTT telemetry architecture

## Scope

This branch implements the telemetry/geolocation slice of Marco 2: outbound MQTT telemetry, optional Home Assistant MQTT Device Discovery, retained availability, adaptive T4A geolocation and reconnect after transport loss.

It does not subscribe to MQTT topics and does not accept remote T4A control commands.

Route import and turn-by-turn navigation are intentionally not implemented in this PR, but they **remain part of Marco 2** and are the next functional slice after physical validation of geolocation.

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

`AndroidLocationProvider` is the only Android location adapter. It converts framework `Location` objects into provider-neutral `LocationSnapshot` values. It is inactive while BLE is disconnected and changes request policy according to T4A movement reported by DP 2.

`MqttTelemetryCoordinator` owns broker connection policy, configuration reload, reconnect cadence, availability state, heartbeat, telemetry publication, location publication and discovery publication/removal. It knows only provider-neutral `T4AState`, `LocationSnapshot` and `MqttTransport` values.

`HomeAssistantDiscovery` builds the Home Assistant Device Discovery topic and JSON payload. It is provider-neutral and has no Android or Paho dependency.

`PahoMqttTransport` is the only class allowed to import Eclipse Paho. It is created by `T4AApplication`, the composition root.

`T4ASessionService` owns the runtime lifetime. Activity destruction/recreation does not tear down BLE, MQTT or an active location request.

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
- `t4a/location`: retained QoS 1 GPS JSON document containing the latest location captured while the T4A BLE session was connected.
- `homeassistant/device/t4a_<normalized-mac>/config`: retained QoS 1 Home Assistant Device Discovery configuration when discovery is enabled.

The broker LWT is `offline` on `<base>/status`. A clean shutdown also publishes `offline` best-effort before disconnecting.

`t4a/status` represents the MQTT/application runtime, not BLE presence. Actual scooter connection is exposed separately through the `connected` telemetry field and the Home Assistant `Conexão Bluetooth` binary sensor.

The retained `t4a/location` value is intentionally the **last known T4A position**. BLE disconnect stops new Android location collection but does not erase the last known position from the broker.

## Home Assistant Discovery

When discovery is enabled, the device identifier is derived from the normalized T4A MAC so formatting changes such as `DC23529724A0` versus `DC:23:52:97:24:A0` do not create duplicate devices.

Normal telemetry entities share `<base>/telemetry` as state topic and `<base>/status` as availability topic.

Discovery exposes:

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
- GPS location as an MQTT `device_tracker` in the same T4A device.

The GPS component consumes `<base>/location` as JSON attributes containing `latitude`, `longitude` and `gps_accuracy`. Its current discovery configuration also uses the same topic as `state_topic` with a reset value so Home Assistant derives the tracker state from zones/coordinates.

When the discovery switch changes from enabled to disabled while connected, the retained discovery topic is cleared with an empty retained payload so Home Assistant removes the discovered device/components.

Discovery publication is gated by the actual MQTT `onConnected()` callback; a transport reporting `isConnected()` early is not enough. This prevents the duplicate initial discovery publication observed during the first physical test.

## Telemetry semantics

Telemetry contains only values currently known to the backend. Unknown DPS-derived values are omitted rather than serialized as artificial `0`, `0.0`, empty strings or `false` measurements.

Current fields are:

- `connected`
- `device_name` when known
- `mac` when known
- `rssi_dbm` when known
- `battery_percent` when validated
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

A telemetry publish occurs when semantic telemetry changes or after 30 seconds as a heartbeat.

Location is also published independently to `<base>/location` whenever a new geographic snapshot is accepted. The retained topic preserves the latest fix without generating a separate stale-position heartbeat.

## Location policy

- BLE disconnected: Android location updates are stopped.
- BLE connected and DP 2 <= 1 km/h: balanced-power request, desired interval 20 s, explicit minimum interval 20 s, minimum displacement 10 m.
- BLE connected and DP 2 > 1 km/h: high-accuracy request when fine location is available, desired interval 3 s, explicit minimum interval 3 s, minimum displacement 2 m.
- Approximate/coarse permission remains usable, but high-accuracy mode is not requested without fine permission.
- A cached Android fix is accepted only when it is at most 30 seconds old.

The interval is a desired request cadence, not a hard guarantee from Android. The minimum-distance condition can suppress a candidate update that does not move far enough.

The provider is deliberately reusable by navigation. Navigation must consume `LocationSnapshot` rather than introduce another GPS stack.

## Validation status

Physically validated:

- MQTT connection through `wss://mqtt.jurgensen.net:443/mqtt`;
- retained `t4a/status` and `t4a/telemetry` publication;
- Home Assistant receiving the retained Device Discovery payload;
- Home Assistant creating the T4A device from discovery.

Implemented but not yet physically validated after the latest extension:

- adaptive Android location acquisition;
- `t4a/location` retained publication;
- geographic fields in `t4a/telemetry`;
- Home Assistant GPS `device_tracker` updates;
- continued location collection with the screen locked while BLE remains connected.

## Relation to Marco 2

Marco 2 is not complete with MQTT alone. Its scope is now explicitly divided into:

1. **Persistent runtime — concluded and physically validated.** BLE survives Activity recreation and screen/UI lifecycle changes.
2. **MQTT telemetry + Home Assistant Discovery — concluded and physically validated.** Publish-only telemetry, WSS transport and device discovery are operational.
3. **Geolocation telemetry — implemented, physical validation pending.** Native Android location feeds MQTT and Home Assistant without coupling to Tuya.
4. **Navigation — pending.** The APK must accept a Google Maps shared route/URL, preserve ordered stops/waypoints, save routes and present turn guidance on the main screen using the shared location source.

Marco 2 can be considered complete only after item 3 is physically validated and item 4 is implemented and validated on-device.

## Explicit non-goals

These remain outside the telemetry/geolocation PR and/or outside Marco 2:

- MQTT subscriptions;
- remote T4A control through MQTT;
- provider-independent BLE transport / Tuya SDK removal;
- ESP32 control migration;
- `ACCESS_BACKGROUND_LOCATION` permission.

Route parsing and turn-by-turn UI are **not** non-goals of Marco 2; they are the next Marco 2 implementation slice.
