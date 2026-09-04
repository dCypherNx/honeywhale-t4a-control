package br.com.t4acontrol.backend.navigation;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Stateful, provider-neutral assessment of how much location observation the current navigation
 * context needs.
 *
 * <p>This policy deliberately does not encode maneuver time horizons or fixed navigation cadence
 * bands. It observes every supplied fix, derives confidence and geometric clearance from the live
 * route context, and exposes a maximum safe observation gap. Android-specific request frequencies
 * are chosen later by the platform adapter.
 */
public final class NavigationObservationPolicy {
  /**
   * Valhalla/Meili recommends trace density between roughly one point/second and one point/10 s for
   * reliable map matching. This is a sampling-quality guard, not a maneuver horizon.
   */
  public static final long TRACE_DENSITY_MAX_GAP_MS = 10_000L;

  public enum DemandLevel {
    NONE,
    RELAXED,
    BALANCED,
    PRECISE,
    CONTINUOUS
  }

  public enum Confidence {
    UNKNOWN,
    DEGRADED,
    STABLE
  }

  public static final class ProviderDemand {
    public final DemandLevel level;
    /** Maximum geometrically safe gap inferred from the current state; may be Long.MAX_VALUE. */
    public final long maxSafeGapMs;

    ProviderDemand(DemandLevel level, long maxSafeGapMs) {
      this.level = Objects.requireNonNull(level);
      this.maxSafeGapMs = Math.max(0L, maxSafeGapMs);
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) return true;
      if (!(other instanceof ProviderDemand)) return false;
      ProviderDemand that = (ProviderDemand) other;
      return level == that.level && maxSafeGapMs == that.maxSafeGapMs;
    }

    @Override
    public int hashCode() {
      return Objects.hash(level, maxSafeGapMs);
    }
  }

  /** Immutable diagnostic snapshot produced for every observed location. */
  public static final class Observation {
    public final Confidence locationConfidence;
    public final Confidence routeConfidence;
    public final double locationUncertaintyMeters;
    public final double routeLateralErrorMeters;
    public final double routeProgressMeters;
    public final double decisionDistanceMeters;
    public final double decisionClearanceMeters;
    public final double observationBudgetSeconds;
    public final boolean corridorKnown;
    public final boolean alternativeBranchAhead;
    public final boolean progressConsistent;
    public final ProviderDemand providerDemand;
    public final String reason;

    Observation(
        Confidence locationConfidence,
        Confidence routeConfidence,
        double locationUncertaintyMeters,
        double routeLateralErrorMeters,
        double routeProgressMeters,
        double decisionDistanceMeters,
        double decisionClearanceMeters,
        double observationBudgetSeconds,
        boolean corridorKnown,
        boolean alternativeBranchAhead,
        boolean progressConsistent,
        ProviderDemand providerDemand,
        String reason) {
      this.locationConfidence = locationConfidence;
      this.routeConfidence = routeConfidence;
      this.locationUncertaintyMeters = locationUncertaintyMeters;
      this.routeLateralErrorMeters = routeLateralErrorMeters;
      this.routeProgressMeters = routeProgressMeters;
      this.decisionDistanceMeters = decisionDistanceMeters;
      this.decisionClearanceMeters = decisionClearanceMeters;
      this.observationBudgetSeconds = observationBudgetSeconds;
      this.corridorKnown = corridorKnown;
      this.alternativeBranchAhead = alternativeBranchAhead;
      this.progressConsistent = progressConsistent;
      this.providerDemand = providerDemand;
      this.reason = reason;
    }

    public String debugSummary() {
      return String.format(
          Locale.US,
          "location=%s route=%s uncertainty=%.1f lateral=%.1f progress=%.1f decision=%.1f clearance=%.1f budgetSec=%s corridorKnown=%s branchAhead=%s progressConsistent=%s demand=%s maxGapMs=%s reason=%s",
          locationConfidence,
          routeConfidence,
          locationUncertaintyMeters,
          routeLateralErrorMeters,
          routeProgressMeters,
          decisionDistanceMeters,
          decisionClearanceMeters,
          Double.isFinite(observationBudgetSeconds)
              ? String.format(Locale.US, "%.2f", observationBudgetSeconds)
              : "inf",
          corridorKnown,
          alternativeBranchAhead,
          progressConsistent,
          providerDemand.level,
          providerDemand.maxSafeGapMs == Long.MAX_VALUE
              ? "inf"
              : String.valueOf(providerDemand.maxSafeGapMs),
          reason);
    }
  }

  private Sample previous;

  public void reset() {
    previous = null;
  }

  public ProviderDemand initialDemand(Route route, NavigationState state) {
    if (route == null || state == null) return none();
    switch (state.status) {
      case NO_ROUTE:
      case PAUSED:
      case ARRIVED:
        return none();
      case OFF_ROUTE:
      case RECALCULATING:
      case WAYPOINT_REACHED:
        return continuous();
      case READY:
      case POSITION_UNAVAILABLE:
      case NAVIGATING:
      default:
        return new ProviderDemand(DemandLevel.PRECISE, TRACE_DENSITY_MAX_GAP_MS);
    }
  }

  public Observation observe(
      Route route,
      NavigationState state,
      LocationSnapshot location,
      boolean deviationSuspected) {
    if (route == null || state == null || location == null) {
      return unavailable("missing_context");
    }

    Projection projection = projectCurrentLeg(route, state, location);
    double accuracy = usableAccuracy(location.accuracyMeters);
    double lateral = projection == null ? Double.NaN : projection.lateralMeters;
    double uncertainty = uncertaintyMeters(accuracy, lateral);

    boolean progressConsistent = progressConsistent(projection, accuracy, location.timestampMs);
    Confidence locationConfidence =
        accuracy > 0.0
            ? (progressConsistent || previous == null ? Confidence.STABLE : Confidence.DEGRADED)
            : Confidence.UNKNOWN;
    Confidence routeConfidence =
        projection == null
            ? Confidence.UNKNOWN
            : (progressConsistent ? Confidence.STABLE : Confidence.DEGRADED);

    Corridor corridor = corridor(route, state);
    double instructionDistance = finiteNonNegative(state.distanceToInstructionMeters)
        ? state.distanceToInstructionMeters
        : Double.NaN;
    double decisionDistance = instructionDistance;
    boolean branchAhead = false;
    if (corridor.known
        && Double.isFinite(corridor.nearestAlternativeBranchMeters)
        && (!Double.isFinite(decisionDistance)
            || corridor.nearestAlternativeBranchMeters < decisionDistance)) {
      decisionDistance = corridor.nearestAlternativeBranchMeters;
      branchAhead = true;
    }

    double clearance = Double.isFinite(decisionDistance)
        ? Math.max(0.0, decisionDistance - uncertainty)
        : Double.POSITIVE_INFINITY;
    double speedMps = usableSpeedMps(location.gpsSpeedKmh);
    double budgetSeconds = speedMps > 0.0
        ? clearance / speedMps
        : (clearance <= 0.0 ? 0.0 : Double.POSITIVE_INFINITY);
    long maxSafeGapMs = toGapMs(budgetSeconds);

    ProviderDemand demand;
    String reason;
    if (deviationSuspected
        || state.status == NavigationState.Status.OFF_ROUTE
        || state.status == NavigationState.Status.RECALCULATING
        || state.status == NavigationState.Status.WAYPOINT_REACHED
        || clearance <= 0.0) {
      demand = continuous();
      reason = deviationSuspected ? "deviation_evidence" : "decision_clearance_exhausted";
    } else if (locationConfidence != Confidence.STABLE
        || routeConfidence != Confidence.STABLE) {
      demand = new ProviderDemand(DemandLevel.PRECISE, maxSafeGapMs);
      reason = "confidence_not_stable";
    } else if (!corridor.known) {
      demand = new ProviderDemand(DemandLevel.PRECISE, maxSafeGapMs);
      reason = "corridor_unknown";
    } else if (budgetSeconds <= TRACE_DENSITY_MAX_GAP_MS / 1_000.0) {
      demand = new ProviderDemand(DemandLevel.PRECISE, maxSafeGapMs);
      reason = branchAhead ? "branch_within_trace_window" : "decision_within_trace_window";
    } else if (branchAhead) {
      demand = new ProviderDemand(DemandLevel.BALANCED, maxSafeGapMs);
      reason = "alternative_branch_ahead";
    } else {
      demand = new ProviderDemand(DemandLevel.RELAXED, maxSafeGapMs);
      reason = "stable_simple_corridor";
    }

    Sample current = new Sample(
        location.timestampMs,
        accuracy,
        projection == null ? Double.NaN : projection.offsetMeters);
    previous = current;

    return new Observation(
        locationConfidence,
        routeConfidence,
        uncertainty,
        lateral,
        projection == null ? Double.NaN : projection.offsetMeters,
        decisionDistance,
        clearance,
        budgetSeconds,
        corridor.known,
        branchAhead,
        progressConsistent,
        demand,
        reason);
  }

  private Observation unavailable(String reason) {
    return new Observation(
        Confidence.UNKNOWN,
        Confidence.UNKNOWN,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        Double.NaN,
        0.0,
        0.0,
        false,
        false,
        false,
        new ProviderDemand(DemandLevel.PRECISE, TRACE_DENSITY_MAX_GAP_MS),
        reason);
  }

  private boolean progressConsistent(Projection projection, double accuracy, long timestampMs) {
    Sample last = previous;
    if (projection == null || last == null || !Double.isFinite(last.routeOffsetMeters)) return true;
    if (timestampMs > 0L && last.timestampMs > 0L && timestampMs <= last.timestampMs) return false;
    double uncertaintyAllowance = Math.max(accuracy, last.accuracyMeters);
    return projection.offsetMeters + uncertaintyAllowance >= last.routeOffsetMeters;
  }

  private static double uncertaintyMeters(double accuracy, double lateral) {
    if (accuracy <= 0.0 && !Double.isFinite(lateral)) return Double.POSITIVE_INFINITY;
    if (accuracy <= 0.0) return Math.max(0.0, lateral);
    if (!Double.isFinite(lateral)) return accuracy;
    return Math.max(accuracy, Math.max(0.0, lateral));
  }

  private static Projection projectCurrentLeg(
      Route route, NavigationState state, LocationSnapshot location) {
    if (state.legIndex < 0 || state.legIndex >= route.legs.size()) return null;
    return project(route.legs.get(state.legIndex).geometry, location.latitude, location.longitude);
  }

  /**
   * Locates an enterable branch before the currently active instruction using the outgoing geometry
   * of the previous OSRM step. Missing rich metadata is explicitly unknown rather than guessed.
   */
  private static Corridor corridor(Route route, NavigationState state) {
    if (state.legIndex < 0
        || state.legIndex >= route.legs.size()
        || state.instructionIndex <= 0) {
      return Corridor.unknown();
    }
    RouteLeg leg = route.legs.get(state.legIndex);
    int previousIndex = state.instructionIndex - 1;
    if (previousIndex >= leg.instructions.size()) return Corridor.unknown();
    NavigationInstruction previousInstruction = leg.instructions.get(previousIndex);
    NavigationStepMetadata metadata = previousInstruction.metadata;
    if (metadata == null || metadata.geometry == null || metadata.geometry.size() < 2) {
      return Corridor.unknown();
    }

    double stepLength = geometryLength(metadata.geometry);
    double distanceToInstruction = finiteNonNegative(state.distanceToInstructionMeters)
        ? state.distanceToInstructionMeters
        : Double.NaN;
    if (!Double.isFinite(stepLength) || !Double.isFinite(distanceToInstruction)) {
      return Corridor.unknown();
    }
    double travelled = Math.max(0.0, stepLength - distanceToInstruction);
    double nearest = Double.POSITIVE_INFINITY;
    for (NavigationStepMetadata.Intersection intersection : metadata.intersections) {
      if (!hasAlternativeExit(intersection)) continue;
      double offset = projectedOffsetMeters(
          metadata.geometry, intersection.latitude, intersection.longitude);
      if (!Double.isFinite(offset) || offset <= travelled) continue;
      nearest = Math.min(nearest, offset - travelled);
    }
    return Corridor.known(nearest);
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

  private static Projection project(List<GeoPoint> geometry, double latitude, double longitude) {
    if (geometry == null || geometry.size() < 2) return null;
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
    return Double.isFinite(bestOffset) ? new Projection(bestOffset, bestDistance) : null;
  }

  private static double projectedOffsetMeters(
      List<GeoPoint> geometry, double latitude, double longitude) {
    Projection projection = project(geometry, latitude, longitude);
    return projection == null ? Double.NaN : projection.offsetMeters;
  }

  private static double geometryLength(List<GeoPoint> geometry) {
    if (geometry == null || geometry.size() < 2) return Double.NaN;
    double total = 0.0;
    for (int index = 0; index < geometry.size() - 1; index++) {
      GeoPoint a = geometry.get(index);
      GeoPoint b = geometry.get(index + 1);
      total += distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude);
    }
    return total;
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

  private static long toGapMs(double budgetSeconds) {
    if (!Double.isFinite(budgetSeconds)) return Long.MAX_VALUE;
    if (budgetSeconds <= 0.0) return 0L;
    double millis = budgetSeconds * 1_000.0;
    return millis >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, Math.round(millis));
  }

  private static double usableAccuracy(double value) {
    return Double.isFinite(value) && value > 0.0 ? value : 0.0;
  }

  private static double usableSpeedMps(Double speedKmh) {
    return speedKmh != null && Double.isFinite(speedKmh) && speedKmh > 0.0
        ? speedKmh / 3.6
        : 0.0;
  }

  private static boolean finiteNonNegative(Double value) {
    return value != null && Double.isFinite(value) && value >= 0.0;
  }

  private static ProviderDemand none() {
    return new ProviderDemand(DemandLevel.NONE, Long.MAX_VALUE);
  }

  private static ProviderDemand continuous() {
    return new ProviderDemand(DemandLevel.CONTINUOUS, 0L);
  }

  private static final class Projection {
    final double offsetMeters;
    final double lateralMeters;

    Projection(double offsetMeters, double lateralMeters) {
      this.offsetMeters = offsetMeters;
      this.lateralMeters = lateralMeters;
    }
  }

  private static final class Corridor {
    final boolean known;
    final double nearestAlternativeBranchMeters;

    private Corridor(boolean known, double nearestAlternativeBranchMeters) {
      this.known = known;
      this.nearestAlternativeBranchMeters = nearestAlternativeBranchMeters;
    }

    static Corridor unknown() {
      return new Corridor(false, Double.NaN);
    }

    static Corridor known(double nearestAlternativeBranchMeters) {
      return new Corridor(true, nearestAlternativeBranchMeters);
    }
  }

  private static final class Sample {
    final long timestampMs;
    final double accuracyMeters;
    final double routeOffsetMeters;

    Sample(long timestampMs, double accuracyMeters, double routeOffsetMeters) {
      this.timestampMs = timestampMs;
      this.accuracyMeters = accuracyMeters;
      this.routeOffsetMeters = routeOffsetMeters;
    }
  }
}
