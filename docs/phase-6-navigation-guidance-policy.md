# Phase 6 — Navigation guidance policy

## Goal

Present the next actionable maneuver early enough for the rider to react naturally, without showing a future turn so early that an earlier physically possible conversion can be mistaken for the intended one.

The policy is intentionally based on riding behavior rather than a single fixed distance.

## Invariants

- A normal directional instruction must provide at least 10 seconds of lead at the current GPS speed.
- Current GPS speed is always part of the activation calculation.
- There is no fixed maximum activation distance.
- Roundabouts use 12 seconds and U-turns use 15 seconds of minimum lead.
- The route, maneuver order and chosen branches remain exactly those supplied by OSRM; the guidance policy changes only when an already-selected maneuver becomes visible.
- A left/right command must not be shown while an earlier, still-unpassed intersection offers another physically enterable branch on the same side that could reasonably be confused with the intended conversion.
- Once the ambiguous intersection is passed, the original OSRM maneuver becomes eligible immediately, subject to the normal timing rule.
- Straight/continue/new-name transitions do not replace `Siga em frente` with a directional command.

## Current timing model

The activation distance is the distance that would be covered if the rider maintained the current speed for the maneuver-specific minimum lead time:

`distance = currentSpeed * leadTime`

Speed is converted to m/s before applying the formula. A 15 m floor remains only for very low non-zero speeds. If GPS speed is unavailable or invalid, the conservative fallback remains 30 m.

This deliberately treats 10 seconds as a real minimum before the maneuver. Any deceleration after the command appears increases the actual time available; it never shortens the requested lead.

Current profiles:

| Domain maneuver | Minimum lead |
| --- | ---: |
| TURN_LEFT / TURN_RIGHT | 10 s |
| ROUNDABOUT | 12 s |
| U_TURN | 15 s |

Examples:

| Speed | Normal turn | Roundabout | U-turn |
| --- | ---: | ---: | ---: |
| 30 km/h | 83 m | 100 m | 125 m |
| 40 km/h | 111 m | 133 m | 167 m |
| 45 km/h | 125 m | 150 m | 188 m |

The previous 100 m ceiling was removed after physical testing showed that a 90-degree turn at 40 km/h or more could require earlier preparation.

## Ambiguous-intersection gate

OSRM remains authoritative for routing. The application does not choose an alternate street, reorder maneuvers or override the OSRM route.

For an upcoming `TURN_LEFT` or `TURN_RIGHT`, the UI inspects the rich OSRM intersection metadata preserved on the approach step. An earlier intersection blocks the directional command only when all of the following are true:

- it is still ahead of the current projected position on the OSRM step geometry;
- it is not effectively the target maneuver intersection itself;
- OSRM marks an alternative outgoing branch as enterable;
- that alternative branch lies on the same turn side as the upcoming command;
- the alternative is not the outgoing branch selected by OSRM for the current route.

While blocked, the panel continues with neutral forward guidance rather than showing the future left/right arrow. After the rider passes the ambiguous intersection, the OSRM-selected maneuver is shown without changing the planned route.

The current ambiguity gate is intentionally limited to ordinary left/right turns. Roundabout and U-turn ambiguity require topology-specific rules and are not inferred by this first implementation.

## OSRM enrichment

The OSRM adapter requests `steps=true`, `annotations=true`, full overview geometry and GeoJSON geometry. The domain keeps the simple `NavigationInstruction.Maneuver` enum for stable UI/engine behavior while preserving richer routing semantics.

For every instruction the adapter preserves, when supplied by OSRM:

- maneuver type and modifier;
- normalized maneuver context (`TURN`, `FORK`, `MERGE`, `END_OF_ROAD`, `ON_RAMP`, `OFF_RAMP`, `CONTINUE`, `ROUNDABOUT`, `ROTARY`, etc.);
- normalized turn severity (`STRAIGHT`, `SLIGHT`, `NORMAL`, `SHARP`, `U_TURN`);
- bearing before and after the maneuver;
- roundabout/rotary exit number;
- street name, reference, pronunciation, destinations and exit labels;
- travel mode, driving side, rotary name/pronunciation;
- step duration and weight;
- per-step geometry;
- every intersection reported inside the step, including location, bearings, legal-entry flags, incoming/outgoing indexes, road classes and lane indications/validity.

For every route leg the adapter also preserves distance, duration, weight, summary and fine-grained annotation arrays. Route-level metadata preserves total metrics, OSRM data version and snapped waypoint information.

Provider-neutral optional metadata objects (`NavigationStepMetadata`, `RouteLegMetadata`, `RouteMetadata`) keep the core domain independent of OSRM. Existing constructors remain valid for providers that cannot supply equivalent information.

OSRM explicitly allows new maneuver types/properties to appear without an API version change. Unknown types remain representable through `rawType`/`rawModifier`; semantic use of future fields requires an explicit adapter update.

## Guidance refinement still pending

The richer metadata permits later physical calibration of slight, normal and sharp turns, forks, merges and end-of-road events. Until that calibration is validated, `TURN_LEFT` and `TURN_RIGHT` still share the normal-turn 10-second profile.

The policy must not infer maneuver severity from localized instruction text; structured routing metadata is authoritative.

## Validation scope

The current timing constants target the operating range already tested with the present electric scooter, including approximately 45 km/h. Physical route testing remains authoritative for final calibration.
