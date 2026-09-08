# Session architecture

RideDash keeps provisioning, runtime transport, UI and telemetry behind separate boundaries so that provider dependencies do not leak through the application.

## Production runtime policy

For normal product branches, the active T4A runtime transport is native BLE only.

- Tuya/ThingClips may remain available behind `T4AProvisioner` for authentication, provisioning and acquisition of the device credentials required by the native runtime.
- Runtime BLE connection, reconnection, commands, telemetry and RSSI must not fall back to `TuyaT4APlatform` or another provider transport.
- If native BLE cannot establish a session, the product remains disconnected and may retry the native transport; it must not silently switch providers.
- Provider-runtime connection code is reserved for branches explicitly created to investigate/test T4A connectivity and must not be merged into a normal product composition root.
- Derived runtime keys such as K14/K15 are computed locally from exported base credentials and are not part of the persistent provider dependency.

The application composition root enforces this by injecting `NativeBleTransport` with an unavailable/null runtime fallback. Tuya initialization remains only because provisioning still depends on the provider SDK.

## Session ownership

`T4ASessionService` owns the long-lived backend session. Activities depend on the `T4ASession` facade and must not construct provider adapters directly.

The backend depends on neutral boundaries:

- `T4AProvisioner` for account/home/discovery/pairing/removal operations;
- `T4ATransport` for live BLE connectivity, DPS commands and telemetry;
- `T4AStateStore` for persisted application state;
- injected clock/scheduler abstractions for deterministic behavior and tests.

## Manual connection control

A user-requested disconnect is distinct from unpairing. It closes the active BLE link while preserving provisioning material. Automatic reconnect remains suspended until the user explicitly requests connection again.

This manual override belongs to the backend/session layer so background maintenance cannot immediately undo the user's request.

## Gateway credential export

Gateway export is an explicit user action exposed through Settings. The exported payload is versioned and contains only the base runtime material required by an already-authorized gateway, including device identity/MAC, UUID, product ID, complete login/local key, security key and useful non-secret protocol metadata.

Derived session keys are not exported, and secret values must never be written to raw diagnostic logs.
