# Phase 6 — navigation DEBUG diagnostics

Navigation DEBUG builds record provider semantics and physical divergence without changing routing, guidance, UI, telemetry or control behavior.

## Instruction focus

Whenever the active navigation instruction changes, the runtime emits:

`[NAV] INSTRUCTION_FOCUS ...`

The record includes the normalized maneuver plus the provider-neutral rich metadata preserved from OSRM: `context`, `severity`, `rawType`, `rawModifier`, `bearingBefore`, `bearingAfter`, road name/ref, destinations, exits, travel mode, driving side and compact intersection topology (`bearings`, legal `entry`, `in`, `out`, road classes).

This allows a later investigation to distinguish an OSRM routing instruction from an incorrect normalization or presentation decision. No extra fields are exposed in the UI.

## Physical divergence while a directional instruction is active

On the first `OFF_ROUTE` evidence associated with an active directional instruction, DEBUG emits once per instruction:

`[NAV] INSTRUCTION_DIVERGENCE classification=possible_instruction_not_followed ...`

The record contains the expected maneuver/provider semantics, expected outgoing bearing when available, actual GPS bearing, angular difference, GPS accuracy/speed, physical coordinates and route projection/lateral error.

The event is intentionally classified as **evidence only**. It does not assert that the rider made a mistake. Later analysis can determine whether the trace represents:

- a deliberate rider choice;
- an incorrect route from the routing provider;
- incorrect maneuver normalization;
- a guidance/UI interpretation problem;
- GPS/map-matching error.

The detector does not introduce new navigation thresholds. It reuses the existing `OFF_ROUTE` state as the physical-divergence trigger and therefore cannot alter navigation behavior.

## Scope boundary

The diagnostics live only in the navigation domain/runtime and are emitted only in DEBUG builds. They do not modify Tuya/BLE/MQTT telemetry, device control, dashboard layout or navigation UI behavior.
