package br.com.t4acontrol.backend.navigation;

import java.util.List;
import java.util.Objects;

/**
 * Converts navigation urgency into a provider-neutral location/processing cadence.
 *
 * <p>The policy is deliberately bidirectional: long predictable stretches may relax into tens of
 * seconds, while maneuver execution and deviation handling remove navigation throttling entirely.
 * Long relaxation is allowed only when rich route metadata shows that no nearer enterable branch
 * requires attention.
 */
public final class NavigationCadencePolicy {
  public static final long MIN_LOCATION_INTERVAL_MS = 500L;
  public static final long MAX_LOCATION_INTERVAL_MS = 20_000L;
  public static final long MAX_UNCERTAIN_CORRIDOR_INTERVAL_MS = 2_000L;
  public static final long DEFAULT_NO_DISTANCE_INTERVAL_MS = 2_000L;
  public static final double MIN_MOVING_SPEED_KMH = 3.0;
  public static final double MAX_MIN_DISTANCE_METERS = 50.0;
  private static final double BRANCH_CRITICAL_HORIZON_SECONDS = 6.0;
  private static final double INTERSECTION_AHEAD_MARGIN_METERS = 8.0;

  public static final class Decision {
    /** Desired Android/provider sampling interval. */
    public final long locationIntervalMs;
    /** Minimum interval between navigation-engine evaluations; 0 means process every useful fix. */
    public final long processingIntervalMs;
    /** Provider should prefer precise positioning when true. */
    public final boolean highAccuracy;
    /** Provider-neutral spatial hint; Android may map this to minimum update distance. */
    public final double minDistanceMeters;

    Decision(
        long locationIntervalMs,
        long processingIntervalMs,
        boolean highAccuracy,
        double minDistanceMeters) {
      this.locationIntervalMs = locationIntervalMs;
      this.processingIntervalMs = processingIntervalMs;
      this.highAccuracy = highAccuracy;
      this.minDistanceMeters = minDistanceMeters;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof Decision)) return false;
      Decision that = (Decision) other;
      return locationIntervalMs == that.locationIntervalMs
          && processingIntervalMs == that.processingIntervalMs
          && highAccuracy == that.highAccuracy
          && Double.compare(minDistanceMeters, that.minDistanceMeters) == 0;
    }

    @Override
    public int hashCode() {
      return Objects.hash(locationIntervalMs, processingIntervalMs, highAccuracy, minDistanceMeters);
    }
  }

  /**
   * Returns null when navigation has no active sampling demand. The Android adapter can then use its
   * normal telemetry/session cadence.
   */
  public Decision evaluate(Route route, NavigationState state, Double speedKmh) {
    if (state == null) return null;
    switch (state.status) {
      case NO_ROUTE:
      case PAUSED:
      case ARRIVED:
        return null;
      case READY:
      case POSITION_UNAVAILABLE:
        return decision(1_000L, 1_000L, true, 1.0);
      case OFF_ROUTE:
      case RECALCULATING:
      case WAYPOINT_REACHED:
        return critical(speedKmh);
      case NAVIGATING:
      default:
        return navigating(route, state, speedKmh);
    }
  }

  /** Maximum-urgency demand used as soon as a credible deviation episode begins. */
  public Decision critical(Double speedKmh) {
    return decision(MIN_LOCATION_INTERVAL_MS, 0L, true, 0.0);
  }

  private Decision navigating(Route route, NavigationState state, Double speedKmh) {
    NavigationInstruction instruction = state.instruction;
    Double maneuverDistance = state.distanceToInstructionMeters;
    if (instruction == null
        || maneuverDistance == null
        || !Double.isFinite(maneuverDistance)
        || maneuverDistance < 0.0) {
      return decision(
          DEFAULT_NO_DISTANCE_INTERVAL_MS,
          DEFAULT_NO_DISTANCE_INTERVAL_MS,
          true,
          2.0);
    }

    CorridorDecision corridor = corridorDecision(route, state, maneuverDistance);
    double decisionDistance = maneuverDistance;
    double criticalHorizonSeconds = criticalHorizonSeconds(instruction.maneuver);
    if (corridor.known && Double.isFinite(corridor.nearestAlternativeBranchMeters)
        && corridor.nearestAlternativeBranchMeters < decisionDistance) {
      decisionDistance = corridor.nearestAlternativeBranchMeters;
      criticalHorizonSeconds = BRANCH_CRITICAL_HORIZON_SECONDS;
    }

    double speed = finitePositive(speedKmh) ? speedKmh : 0.0;
    if (speed < MIN_MOVING_SPEED_KMH) {
      long interval;
      if (decisionDistance <= 30.0) interval = 1_000L;
      else if (decisionDistance <= 100.0) interval = 3_000L;
      else if (decisionDistance <= 300.0) interval = 10_000L;
      else interval = MAX_LOCATION_INTERVAL_MS;
      if (!corridor.known) interval = Math.min(interval, MAX_UNCERTAIN_CORRIDOR_INTERVAL_MS);
      boolean precise = decisionDistance <= 300.0 || !corridor.known;
      return decision(interval, interval, precise, precise ? 2.0 : 10.0);
    }

    double speedMps = speed / 3.6;
    double etaSeconds = decisionDistance / speedMps;
    double slackSeconds = etaSeconds - criticalHorizonSeconds;

    if (slackSeconds <= 0.0) return critical(speedKmh);

    // Consume at most half of the time still available before the nearest decision becomes critical.
    // Re-evaluating after each accepted fix makes relaxation and acceleration continuous.
    long interval = Math.round(slackSeconds * 0.5 * 1_000.0);
    interval = clamp(interval, MIN_LOCATION_INTERVAL_MS, MAX_LOCATION_INTERVAL_MS);
    if (!corridor.known) interval = Math.min(interval, MAX_UNCERTAIN_CORRIDOR_INTERVAL_MS);

    // Once the nearest decision is within roughly 30 s of its critical horizon, prefer precision.
    boolean precise = slackSeconds <= 30.0 || decisionDistance <= 250.0 || !corridor.known;
    long processing = interval <= MIN_LOCATION_INTERVAL_MS ? 0L : interval;
    return decision(interval, processing, precise, spatialHint(interval, speedMps));
  }

  /**
   * Finds the nearest physically enterable alternative branch in the route step currently being
   * travelled. Unknown metadata is treated conservatively and disables long relaxation.
   */
  private static CorridorDecision corridorDecision(
      Route route, NavigationState state, double distanceToCurrentInstruction) {
    if (route == null
        || state.legIndex < 0
        || state.legIndex >= route.legs.size()
        || state.instructionIndex <= 0) {
      return CorridorDecision.unknown();
    }
    RouteLeg leg = route.legs.get(state.legIndex);
    int previousIndex = state.instructionIndex - 1;
    if (previousIndex >= leg.instructions.size()) return CorridorDecision.unknown();
    NavigationInstruction previous = leg.instructions.get(previousIndex);
    NavigationStepMetadata metadata = previous.metadata;
    if (metadata == null || metadata.geometry.size() < 2) return CorridorDecision.unknown();

    double totalStepMeters = geometryLength(metadata.geometry);
    if (!Double.isFinite(totalStepMeters) || totalStepMeters <= 0.0) {
      return CorridorDecision.unknown();
    }
    double travelledMeters = Math.max(0.0, totalStepMeters - distanceToCurrentInstruction);
    double nearest = Double.POSITIVE_INFINITY;
    for (NavigationStepMetadata.Intersection intersection : metadata.intersections) {
      if (!hasAlternativeExit(intersection)) continue;
      double offset = projectedOffsetMeters(metadata.geometry, intersection.latitude, intersection.longitude);
      if (!Double.isFinite(offset) || offset <= travelledMeters + INTERSECTION_AHEAD_MARGIN_METERS) {
        continue;
      }
      nearest = Math.min(nearest, offset - travelledMeters);
    }
    return CorridorDecision.known(nearest);
  }

  private static boolean hasAlternativeExit(NavigationStepMetadata.Intersection intersection) {
    if (intersection == null || intersection.entry == null || intersection.entry.isEmpty()) return false;
    for (int index = 0; index < intersection.entry.size(); index++) {
      if (!Boolean.TRUE.equals(intersection.entry.get(index))) continue;
      if (index == intersection.inIndex || index == intersection.outIndex) continue;
      return true;
    }
    return false;
  }

  private static double geometryLength(List<GeoPoint> geometry) {
    double total = 0.0;
    for (int index = 0; index < geometry.size() - 1; index++) {
      GeoPoint a = geometry.get(index);
      GeoPoint b = geometry.get(index + 1);
      total += distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude);
    }
    return total;
  }

  private static double projectedOffsetMeters(
      List<GeoPoint> geometry, double latitude, double longitude) {
    double bestDistance = Double.POSITIVE_INFINITY;
    double bestOffset = Double.NaN;
    double cumulative = 0.0;
    double refLat = Math.toRadians(latitude);
    for (int index = 0; index < geometry.size() - 1; index++) {
      GeoPoint a = geometry.get(index);
      GeoPoint b = geometry.get(index + 1);
      double segment = distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude);
      double ax = Math.toRadians(a.longitude - longitude) * Math.cos(refLat) * 6_371_000.0;
      double ay = Math.toRadians(a.latitude - latitude) * 6_371_000.0;
      double bx = Math.toRadians(b.longitude - longitude) * Math.cos(refLat) * 6_371_000.0;
      double by = Math.toRadians(b.latitude - latitude) * 6_371_000.0;
      double dx = bx - ax;
      double dy = by - ay;
      double denominator = dx * dx + dy * dy;
      double t = denominator == 0.0
          ? 0.0
          : Math.max(0.0, Math.min(1.0, -(ax * dx + ay * dy) / denominator));
      double px = ax + t * dx;
      double py = ay + t * dy;
      double lateral = Math.sqrt(px * px + py * py);
      if (lateral < bestDistance) {
        bestDistance = lateral;
        bestOffset = cumulative + t * segment;
      }
      cumulative += segment;
    }
    return bestOffset;
  }

  private static Decision decision(
      long locationIntervalMs,
      long processingIntervalMs,
      boolean highAccuracy,
      double minDistance) {
    return new Decision(
        clamp(locationIntervalMs, MIN_LOCATION_INTERVAL_MS, MAX_LOCATION_INTERVAL_MS),
        Math.max(0L, processingIntervalMs),
        highAccuracy,
        Math.max(0.0, Math.min(MAX_MIN_DISTANCE_METERS, minDistance)));
  }

  private static double spatialHint(long intervalMs, double speedMps) {
    if (intervalMs <= MIN_LOCATION_INTERVAL_MS) return 0.0;
    if (speedMps <= 0.0) return 2.0;
    double travelled = speedMps * (intervalMs / 1_000.0);
    return Math.max(2.0, Math.min(MAX_MIN_DISTANCE_METERS, travelled * 0.5));
  }

  private static double criticalHorizonSeconds(NavigationInstruction.Maneuver maneuver) {
    if (maneuver == null) return 10.0;
    switch (maneuver) {
      case U_TURN:
        return 15.0;
      case ROUNDABOUT:
        return 12.0;
      case TURN_LEFT:
      case TURN_RIGHT:
        return 10.0;
      case WAYPOINT:
      case ARRIVE:
        return 8.0;
      case START:
      case STRAIGHT:
      case UNKNOWN:
      default:
        return 6.0;
    }
  }

  private static boolean finitePositive(Double value) {
    return value != null && Double.isFinite(value) && value > 0.0;
  }

  private static long clamp(long value, long min, long max) {
    return Math.max(min, Math.min(max, value));
  }

  private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
    double earthRadiusMeters = 6_371_000.0;
    double phi1 = Math.toRadians(lat1);
    double phi2 = Math.toRadians(lat2);
    double deltaPhi = Math.toRadians(lat2 - lat1);
    double deltaLambda = Math.toRadians(lon2 - lon1);
    double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
        + Math.cos(phi1) * Math.cos(phi2)
            * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
    return earthRadiusMeters * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  private static final class CorridorDecision {
    final boolean known;
    final double nearestAlternativeBranchMeters;

    private CorridorDecision(boolean known, double nearestAlternativeBranchMeters) {
      this.known = known;
      this.nearestAlternativeBranchMeters = nearestAlternativeBranchMeters;
    }

    static CorridorDecision unknown() {
      return new CorridorDecision(false, Double.NaN);
    }

    static CorridorDecision known(double nearestAlternativeBranchMeters) {
      return new CorridorDecision(true, nearestAlternativeBranchMeters);
    }
  }
}
