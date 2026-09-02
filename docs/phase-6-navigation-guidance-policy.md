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

## What OSRM currently provides

OSRM itself exposes richer maneuver information through `maneuver.type` and `maneuver.modifier`, including distinctions such as slight/sharp turns, forks, merges and end-of-road situations.

The current `OsrmRoutePlanner.mapManeuver()` deliberately collapses that information into the provider-neutral domain enum:

- any left modifier -> `TURN_LEFT`
- any right modifier -> `TURN_RIGHT`
- `roundabout` / `rotary` -> `ROUNDABOUT`
- `uturn` -> `U_TURN`
- `straight`, `continue`, `new name` -> `STRAIGHT`

Therefore the application cannot currently distinguish, with reliable domain data, between:

- a slight curve and a normal turn;
- a normal turn and a sharp turn;
- a conventional junction, fork, merge or end-of-road event when they collapse to the same left/right direction.

The guidance policy must not infer these categories from localized instruction text. Doing so would couple navigation behavior to OSRM wording and make the domain provider-specific again.

## Planned refinement

If physical validation shows that normal left/right treatment is insufficient, enrich `NavigationInstruction` with provider-neutral maneuver severity/topology metadata while preserving OSRM's raw semantic distinction at the adapter boundary. Then add profiles for at least:

- no-action / straight transition;
- slight turn;
- normal turn;
- attention/junction;
- sharp turn;
- roundabout;
- U-turn.

Until that domain enrichment exists, `TURN_LEFT` and `TURN_RIGHT` remain normal-turn profiles. This is a known limitation, not an implicit approximation.

## Validation scope

The current constants are calibrated for the T4A operating range, including speeds up to approximately 45 km/h. Physical route testing remains authoritative for final calibration.
