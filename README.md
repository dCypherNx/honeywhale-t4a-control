# Honeywhale T4A Control

Android controller for the Honeywhale T4A, with native BLE runtime, MQTT telemetry, Home Assistant integration and a replaceable provisioning boundary.

> Production runtime policy: normal product branches use native BLE exclusively for T4A connection, reconnection, commands, telemetry and RSSI. Tuya/ThingClips remains available only behind provisioning/credential acquisition. Runtime fallback to the Tuya SDK is reserved for explicitly connection-focused experimental branches.

See `docs/session-architecture.md` for lifecycle, runtime ownership and architectural invariants.
