# MQTT configuration architecture

## Scope

This branch introduces MQTT configuration only. It does not create a broker connection, publish telemetry, subscribe to topics, or accept remote control commands.

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
MqttConfigurationStore
        |
        v
AndroidMqttConfigurationStore
```

`MqttSettingsActivity` owns Android widgets and presentation only. It must not read/write MQTT `SharedPreferences`, use Android Keystore APIs, instantiate MQTT clients, or import backend MQTT persistence classes.

`MqttSettings` is the UI-facing contract. It represents editable fields as strings so parsing and validation do not leak into widgets.

`DefaultMqttSettings` is the application adapter. It parses form values, maps backend validation to UI-neutral errors, and delegates persistence.

`MqttConfiguration` is the backend model. It normalizes topic values and defines configuration validation.

`MqttConfigurationStore` is the persistence boundary. Future MQTT transport code should depend on this boundary or on a dedicated configuration provider rather than reading UI state.

`AndroidMqttConfigurationStore` stores non-secret configuration in app-private preferences and encrypts the MQTT password with AES-GCM using an Android Keystore key. No secret is logged.

`T4AApplication` remains the composition root and is the only app component that wires the UI contract to the backend persistence implementation.

## Configuration fields

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

## Next step

The telemetry publisher should be implemented as a separate runtime component owned by the persistent T4A session layer. It may consume `T4AState` and `MqttConfiguration`, but it must not depend on `MqttSettingsActivity` or Android widgets. MQTT remains telemetry-only unless the project explicitly changes that policy.
