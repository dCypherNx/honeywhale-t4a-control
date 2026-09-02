# Phase 6 — Navigation guidance policy

## Goal

Present the next actionable maneuver early enough for the rider to react naturally, while avoiding instructions hundreds of meters before they are useful.

The policy is intentionally based on riding behavior rather than a single fixed distance.

## Invariants

- A directional instruction must target at least 10 seconds of projected lead time.
- The UI must not expose a directional maneuver farther than 100 meters.
- Current GPS speed is always part of the activation calculation.
- A maneuver that requires more speed reduction may use a longer lead time while still appearing at a similar or shorter physical distance, because the rider is expected to decelerate after receiving the instruction.
- Straight/continue/new-name transitions do not replace `Siga em frente` with a directional command.

The 10-second invariant is evaluated against the projected motion after the instruction appears, not against the unrealistic assumption that the current speed remains constant until the maneuver.

## Current model

The UI assumes approximately linear deceleration from the current GPS speed to a maneuver-specific target speed. The activation distance is:

`distance = ((currentSpeed + targetSpeed) / 2) * leadTime`

Speeds are converted to m/s before applying the formula. The result is bounded to 15–100 m.

For speeds below the target speed, target speed is reduced to the current speed; the model never assumes acceleration before a maneuver.

Current profiles:

| Domain maneuver | Lead time | Target speed at maneuver |
| --- | ---: | ---: |
| TURN_LEFT / TURN_RIGHT | 10 s | 25 km/h |
| ROUNDABOUT | 12 s | 15 km/h |
| U_TURN | 15 s | 0 km/h |

At 45 km/h this yields approximately:

| Maneuver | Activation distance |
| --- | ---: |
| normal turn | 97 m |
| roundabout | 100 m |
| U-turn | 94 m |

The U-turn may activate at a slightly shorter physical distance than a normal turn even though it has a longer temporal window. That is intentional: the model expects near-stop deceleration, so traversing those meters takes substantially longer.

## OSRM enrichment

The OSRM adapter now requests `steps=true`, `annotations=true`, full overview geometry and GeoJSON geometry. The domain keeps the simple `NavigationInstruction.Maneuver` enum for stable UI/engine behavior, but no longer discards the richer routing semantics.

For every instruction the adapter preserves, when supplied by OSRM:

- maneuver type and modifier;
- normalized maneuver context (`TURN`, `FORK`, `MERGE`, `END_OF_ROAD`, `ON_RAMP`, `OFF_RAMP`, `CONTINUE`, `ROUNDABOUT`, `ROTARY`, etc.);
- normalized turn severity (`STRAIGHT`, `SLIGHT`, `NORMAL`, `SHARP`, `U_TURN`);
- bearing before and after the maneuver;
- roundabout/rotary exit number;
- street name, reference, pronunciation, destinations and exit labels;
- travel mode, driving side, rotary name/pronunciation;
- step duration and weight;
- every intersection reported inside the step, including location, bearings, legal-entry flags, incoming/outgoing indexes, road classes and lane indications/validity.

For every route leg the adapter also preserves:

- distance, duration, weight and summary;
- fine-grained annotation arrays for segment distance, duration, weight and speed;
- datasource indexes/names;
- OSM node IDs.

At route level it preserves total distance, duration, weight, weight profile, OSRM data version and snapped waypoint metadata (street name, snapped location, snap distance and reusable hint).

This is intentionally represented by provider-neutral optional metadata objects (`NavigationStepMetadata`, `RouteLegMetadata`, `RouteMetadata`). Existing constructors remain valid for providers that cannot supply equivalent information.

OSRM explicitly allows new maneuver types/properties to appear without an API version change. Unknown types therefore remain representable through `rawType`/`rawModifier`, while known values receive normalized enums. Unknown future fields are not interpreted automatically; adding semantic use for them requires an explicit adapter update.

## Guidance refinement now enabled

The richer metadata removes the previous information bottleneck. The next calibration step can safely distinguish at least:

- no-action / straight transition;
- slight turn;
- normal turn;
- fork / merge / end-of-road attention events;
- sharp turn;
- roundabout / rotary;
- U-turn.

The timing policy has not yet been recalibrated to every enriched category. Until that calibration is physically validated, `TURN_LEFT` and `TURN_RIGHT` still use the current normal-turn timing profile even though severity/topology are now available to the UI policy.

The policy must not infer maneuver severity from localized instruction text; structured routing metadata is authoritative.

## Validation scope

The current constants are calibrated for the T4A operating range, including speeds up to approximately 45 km/h. Physical route testing remains authoritative for final calibration.
