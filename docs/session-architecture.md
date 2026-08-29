# T4A session architecture

## Ownership

The Android UI is not the owner of the T4A connection or of the long-lived telemetry runtime.

| Component | Responsibility |
| --- | --- |
| `MainActivity` | Rendering, UI preferences, permission requests and user intents. Binds/unbinds from the session only. |
| `T4ASession` | UI-facing contract for state observation and T4A commands. |
| `T4ASessionService` | Process-level owner of one live `T4ABackend`, one MQTT telemetry coordinator and one Android location adapter; keeps the foreground service alive and replays the latest state to a new UI client. |
| `T4ABackend` | Provider-neutral coordination of authentication state, pairing state, DPS, command confirmation, reconnect cadence and T4A telemetry validation. |
| `T4AProvisioner` | Account, inventory, discovery, pairing and removal boundary. |
| `T4ATransport` | Runtime device session: attach/connect, DPS publish, cached state and RSSI. |
| `MqttTelemetryCoordinator` | Provider-neutral MQTT connection policy, retained availability, semantic telemetry, location publication and Home Assistant discovery. |
| `AndroidLocationProvider` | Android-only location adapter. It converts framework `Location` values into neutral `LocationSnapshot` objects and is active only while the T4A BLE session is connected. |
| `LocationSnapshot` | Provider-neutral geographic snapshot shared by telemetry today and reusable by the future navigation layer. |
| `T4AApplication` | Composition root. Provider-specific construction is allowed here; Activities must not instantiate provider/backend implementations. |
| `TuyaT4APlatform` | Current Tuya implementation of provisioning and transport boundaries. |

## Lifecycle rule

`T4ABackend.start()` and `T4ABackend.destroy()` belong to `T4ASessionService`.

The same service also owns MQTT and location lifetimes. `MainActivity.onPause()` only informs the session that the UI is no longer foreground so BLE reconnect cadence may be relaxed. `MainActivity.onDestroy()` only removes its listener and unbinds. It must never destroy BLE, MQTT or location runtimes.

This prevents Activity recreation, display lock/unlock and configuration changes from creating a new BLE attach cycle or a new cached `INITIAL` snapshot. It also allows MQTT telemetry to continue independently of the UI.

The foreground service always declares the `connectedDevice` type. When location permission is available it also activates the `location` type. The app does not request `ACCESS_BACKGROUND_LOCATION`; the foreground service is started while the Activity is visible and may continue an already-started location session while the UI is no longer active.

## Location lifecycle

Location represents the position associated with the T4A while the phone maintains the BLE session:

- BLE disconnected: Android location updates are stopped;
- BLE connected and T4A stationary (`DP 2 <= 1 km/h`): balanced-power request with desired interval 20 s, explicit minimum interval 20 s and minimum displacement 10 m;
- BLE connected and T4A moving (`DP 2 > 1 km/h`): high-accuracy request when fine permission is available, with desired interval 3 s, explicit minimum interval 3 s and minimum displacement 2 m;
- a cached Android location is accepted only when at most 30 seconds old.

The Android interval is a requested/desired cadence, not a hard scheduling guarantee. The minimum-distance constraint may suppress a candidate update that has not moved far enough.

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

## Architectural invariants

1. Recreating `MainActivity` must not create a new `T4ABackend`.
2. `MainActivity` must not call `T4ABackend.start()` or `destroy()`.
3. A newly bound Activity receives the latest `T4AState` immediately.
4. Screen lock/backgrounding keeps the service and transport alive.
5. Existing commands, pairing, battery validation and auto-lock behavior remain independent of Activity lifetime.
6. The Tuya provider remains behind `T4AProvisioner`/`T4ATransport` boundaries.
7. MQTT and location belong to the service lifetime, not to the Activity lifetime.
8. Location reaches MQTT through neutral `LocationSnapshot`; MQTT does not own Android location APIs.
9. The future navigation layer must consume the same neutral location source instead of creating a second GPS implementation.
10. MQTT remains publish-only and must not become a remote-control path for the T4A.

## Validation status

Physically validated on the Galaxy S25:

- Activity unbind/rebind does not recreate the service/backend session;
- BLE commands remain functional across UI recreation;
- a second Activity-generated `INITIAL` is not introduced;
- MQTT over WSS reaches the broker;
- Home Assistant Device Discovery reaches Home Assistant and creates the T4A device.

Implemented but still requiring physical validation after the latest changes:

- adaptive location collection;
- retained `t4a/location` publication;
- geographic fields appended to `t4a/telemetry`;
- Home Assistant GPS `device_tracker` behavior during a real movement test.

## Device validation for the location extension

After installing a build containing the location changes, capture one continuous test session:

1. grant precise location permission and connect to the T4A;
2. verify a stationary location fix is obtained and published;
3. move with the T4A and confirm the provider switches to the moving policy;
4. verify `t4a/location` and `t4a/telemetry` contain coherent coordinates and timestamps;
5. lock the screen and confirm BLE/MQTT/location continue under the foreground service;
6. stop moving and confirm the provider returns to the stationary policy;
7. disconnect BLE and confirm Android location collection stops while the retained last-known T4A position remains available to MQTT/Home Assistant.
