# Phase 6 — Live navigation observation policy

## Why this replaced cadence bands

Navigation must not be driven by fixed maneuver horizons or by a table such as 500 ms / 2 s / 20 s. Those values turn a continuous physical problem into arbitrary timing bands and can hide relevant movement between samples.

The runtime now uses a provider-neutral `NavigationObservationPolicy` that observes the live route context and expresses **how much observation is currently needed**.

## Professional references behind the design

- Mapbox Navigation separates raw location from enhanced/map-matched location and uses raw fixes to determine instruction timing, route matching and deviation. Its documented default Navigation SDK v3 location request is 1 s desired, 500 ms minimum, high accuracy. These are adapter capabilities, not maneuver rules.
- Valhalla/Meili map-matching guidance recommends an approximate trace density between one point per second and one point per ten seconds because larger gaps increase matching ambiguity.
- Android documents request interval and quality as provider hints. High accuracy can cost more power; balanced/low-power requests can reduce it. The platform may still deliver updates faster than the requested interval.
- Organic Maps community discussion explicitly questions degrading navigation frequency for assumed battery savings without physical measurements. Battery gain must therefore be measured rather than presumed.

## Live state

For every location supplied by Android the policy records or derives:

- location uncertainty from the actual reported accuracy and route lateral error;
- route projection and progress consistency against the preceding observation;
- confidence in the current location and route match;
- the nearest consequential decision boundary: the active maneuver or an earlier physically enterable branch exposed by OSRM intersection metadata;
- geometric decision clearance after subtracting current uncertainty;
- observation budget: how long the current speed would take to consume that clearance;
- whether the forward corridor is actually known from rich OSRM metadata.

No 10/12/15-second maneuver horizon exists in this policy. There is no navigation-specific 20-second ceiling and no navigation-specific 500 ms floor.

## Provider demand

The continuous state is translated to coarse platform demands only at the Android boundary:

- `CONTINUOUS`: deviation/recalculation or exhausted decision clearance;
- `PRECISE`: confidence is not stable, corridor is unknown, or a consequential decision is within the trace-density window;
- `BALANCED`: stable context but a real alternative branch is still ahead;
- `RELAXED`: stable location, stable route progress, known corridor and no nearer alternative branch;
- `NONE`: navigation does not currently request location.

The Android adapter deliberately uses stable request profiles instead of constantly unregistering and registering a `LocationRequest` for tiny numerical changes in the observation budget.

`PRECISE` and `CONTINUOUS` use the mature active-navigation request shape documented by Mapbox: 1 s desired, 500 ms minimum, high accuracy when fine permission exists.

`BALANCED` and `RELAXED` use a 10-second desired interval because that is the upper edge of Valhalla/Meili's recommended trace-density range. `BALANCED` requests balanced accuracy; `RELAXED` requests low power.

The 500 ms and 10 s values therefore belong to the **Android sampling adapter**, backed by external implementation guidance. They are not maneuver horizons and do not define when navigation decisions occur.

## Runtime processing

`NavigationRuntime` no longer has an independent fixed-time throttle. Every fix delivered by the provider is passed through route progress/deviation logic and through the observation policy.

This matters because a provider may deliver a useful update earlier than requested. Android explicitly allows that behavior, and navigation should not discard the information merely because an application timer has not expired.

## Diagnostics and calibration

Every observed fix emits a `[NAV] OBS` diagnostic containing:

- location confidence;
- route confidence;
- uncertainty;
- lateral route error;
- route progress;
- decision distance;
- clearance;
- observation budget;
- corridor metadata availability;
- alternative-branch presence;
- progress consistency;
- resulting demand level and reason.

`[NAV] LOCATION_DEMAND` records actual demand transitions.

These fields are intentionally observable before introducing more sophisticated confidence weighting. Physical logs should determine future calibration. New constants must not be promoted to navigation rules merely because they appear to work in one route.

## Energy validation

The design creates a real opportunity for energy savings because `RELAXED` changes both requested frequency and Android quality, rather than only skipping CPU work after GPS has already produced a fix.

The magnitude of the saving is not assumed. It must be measured on the target phone during comparable routes, alongside navigation quality and responsiveness.
