# T4A session architecture

## Ownership

The Android UI is not the owner of the T4A connection.

| Component | Responsibility |
| --- | --- |
| `MainActivity` | Rendering, UI preferences and user intents. Binds/unbinds from the session only. |
| `T4ASession` | UI-facing contract for state observation and T4A commands. |
| `T4ASessionService` | Process-level owner of one live `T4ABackend`; keeps the connected-device foreground service alive and replays the latest state to a new UI client. |
| `T4ABackend` | Provider-neutral coordination of authentication state, pairing state, DPS, command confirmation, reconnect cadence and telemetry validation. |
| `T4AProvisioner` | Account, inventory, discovery, pairing and removal boundary. |
| `T4ATransport` | Runtime device session: attach/connect, DPS publish, cached state and RSSI. |
| `T4AApplication` | Composition root. Provider-specific construction is allowed here; Activities must not instantiate provider/backend implementations. |
| `TuyaT4APlatform` | Current Tuya implementation of provisioning and transport boundaries. |

## Lifecycle rule

`T4ABackend.start()` and `T4ABackend.destroy()` belong to `T4ASessionService`.

`MainActivity.onPause()` only informs the session that the UI is no longer foreground so reconnect cadence may be relaxed. `MainActivity.onDestroy()` only removes its listener and unbinds. It must never destroy the transport.

This prevents Activity recreation, display lock/unlock and configuration changes from creating a new BLE attach cycle or a new cached `INITIAL` snapshot.

## Research evidence retained outside Git

The implementation is informed by, but does not commit, the archived research material under `C:\Projetos\t4a-research`:

- HCI `btsnoop` captures for baseline, light, lock, unbind and new-pairing flows;
- `parse_btsnoop_att.py` used to inspect ATT traffic;
- full and targeted JADX output from the Honeywhale/Play Store APK;
- official APK and Play Store certificate evidence;
- the abandoned `secure-device-credentials` prototype and complete Git bundle/patch;
- the pre-hotfix source snapshot.

These artifacts are evidence, not application dependencies.

## Credential seam

The abandoned credential prototype proved that Android Keystore + authenticated local encryption is a viable persistence mechanism, but its old `deviceId + uuid + localKey` completeness assumption is not part of the current architecture.

Future credential extraction/storage must be added behind the composition root without changing `MainActivity` or the session ownership model. `localKey`, `secKey`, `communicationModes`, `btScyChannel` and any other provider material remain implementation details until experiments establish the minimum BLE credential set.

## Acceptance criteria for this branch

1. Recreating `MainActivity` must not create a new `T4ABackend`.
2. `MainActivity` must not call `T4ABackend.start()` or `destroy()`.
3. A newly bound Activity receives the latest `T4AState` immediately.
4. Screen lock/backgrounding keeps the service and transport alive.
5. Existing commands, pairing, battery validation and auto-lock behavior remain unchanged.
6. The Tuya provider remains behind `T4AProvisioner`/`T4ATransport` boundaries.
7. MQTT, navigation, geolocation and provider-independent BLE are separate follow-up changes.

## Device validation

After installing the branch APK, capture one continuous test session:

1. connect to the T4A and wait for live RX telemetry;
2. lock the phone screen long enough for the Activity to be paused or reclaimed;
3. unlock and reopen the app;
4. verify commands and telemetry continue without a new pairing flow;
5. compare HCI/ATT evidence before and after the UI recreation and confirm that no second session bootstrap is caused solely by Activity lifecycle.
