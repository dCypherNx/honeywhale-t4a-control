# Native T4A runtime policy

Normal product branches use the native BLE transport as the only runtime path to the T4A.

Tuya/ThingClips may remain behind `T4AProvisioner` for authentication, provisioning and credential acquisition. Runtime connection, reconnection, DPS commands, telemetry and RSSI must not silently fall back to `TuyaT4APlatform` or another provider transport.

If native BLE cannot establish a session, the application remains disconnected and retries the native transport. Provider-runtime connection code is reserved for branches explicitly created to investigate or test T4A connectivity.

A user-requested disconnect closes the native BLE link without unpairing and suppresses automatic reconnect until the user explicitly requests connection again.

Gateway credential export is explicit and versioned. Base credentials may be exported for an already-authorized gateway; derived session keys such as K14/K15 remain locally derived and are not exported.
