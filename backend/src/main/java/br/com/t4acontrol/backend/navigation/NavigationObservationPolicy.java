package br.com.t4acontrol.backend.navigation;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Provider-neutral assessment of how much physical observation navigation needs.
 *
 * <p>No maneuver time horizon or fixed navigation cadence is encoded here. The policy consumes one
 * shared route-progress interpretation and derives confidence, geometric clearance and a continuous
 * maximum safe observation gap. Platform adapters decide how to translate that demand into real
 * location-provider capabilities.
 */
public final class NavigationObservationPolicy {
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

  // Compatibility only for isolated callers/tests. Production shares one tracker through runtime.
  private final RouteProgressTracker compatibilityTracker = new RouteProgressTracker();

  public void reset() {
    compatibilityTracker.reset();
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
        return new ProviderDemand(DemandLevel.PRECISE, Long.MAX_VALUE);
    }
  }

  public Observation observe(
      Route route,
      NavigationState state,
      LocationSnapshot location,
      boolean deviationSuspected) {
    RouteProgressSnapshot progress =
        route == null || state == null || location == null
            ? null
            : compatibilityTracker.observe(route, state, location);
    return observe(route, state, location, progress, deviationSuspected);
  }

  public Observation observe(
      Route route,
      NavigationState state,
      LocationSnapshot location,
      RouteProgressSnapshot progress,
      boolean deviationSuspected) {
    if (route == null || state == null || location == null) return unavailable("missing_context");

    double accuracy = usableAccuracy(location.accuracyMeters);
    double lateral = progress == null || !progress.available()
        ? Double.NaN
        : progress.lateralErrorMeters;
    double routeOffset = progress == null || !progress.available()
        ? Double.NaN
        : progress.routeOffsetMeters;
    boolean progressConsistent = progress != null && progress.available() && progress.progressConsistent;
    double uncertainty = uncertaintyMeters(accuracy, lateral);

    Confidence locationConfidence = accuracy <= 0.0
        ? Confidence.UNKNOWN
        : (progress == null || !progress.available() || progressConsistent
            ? Confidence.STABLE
            : Confidence.DEGRADED);
    Confidence routeConfidence = progress == null || !progress.available()
        ? Confidence.UNKNOWN
        : (progressConsistent ? Confidence.STABLE : Confidence.DEGRADED);

    Corridor corridor = corridor(route, state);
    double decisionDistance = finiteNonNegative(state.distanceToInstructionMeters)
        ? state.distanceToInstructionMeters
        : Double.NaN;
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
    } else if (locationConfidence != Confidence.STABLE || routeConfidence != Confidence.STABLE) {
      demand = new ProviderDemand(DemandLevel.PRECISE, maxSafeGapMs);
      reason = "confidence_not_stable";
    } else if (!corridor.known) {
      demand = new ProviderDemand(DemandLevel.PRECISE, maxSafeGapMs);
      reason = "corridor_unknown";
    } else if (branchAhead) {
      demand = new ProviderDemand(DemandLevel.BALANCED, maxSafeGapMs);
      reason = "alternative_branch_ahead";
    } else {
      demand = new ProviderDemand(DemandLevel.RELAXED, maxSafeGapMs);
      reason = "stable_simple_corridor";
    }

    return new Observation(
        locationConfidence,
        routeConfidence,
        uncertainty,
        lateral,
        routeOffset,
        decisionDistance,
        clearance,
        budgetSeconds,
        corridor.known,
        branchAhead,
        progressConsistent,
        demand,
        reason);
  }

  private static Observation unavailable(String reason) {
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
        new ProviderDemand(DemandLevel.PRECISE, 0L),
        reason);
  }

  private static double uncertaintyMeters(double accuracy, double lateral) {
    if (accuracy <= 0.0 && !Double.isFinite(lateral)) return Double.POSITIVE_INFINITY;
    if (accuracy <= 0.0) return Math.max(0.0, lateral);
    if (!Double.isFinite(lateral)) return accuracy;
    return Math.max(accuracy, Math.max(0.0, lateral));
  }

  private static Corridor corridor(Route route, NavigationState state) {
    if (state.legIndex < 0 || state.legIndex >= route.legs.size() || state.instructionIndex <= 0) {
      return Corridor.unknown();
    }
    RouteLeg leg = route.legs.get(state.legIndex);
    int previousIndex = state.instructionIndex - 1;
    if (previousIndex >= leg.instructions.size()) return Corridor.unknown();
    NavigationStepMetadata metadata = leg.instructions.get(previousIndex).metadata;
    if (metadata == null || metadata.geometry == null || metadata.geometry.size() < 2) {
      return Corridor.unknown();
    }

    double stepLength = RouteGeometry.lengthMeters(metadata.geometry);
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
      double offset = RouteGeometry.projectedOffsetMeters(
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

  private static long toGapMs(double budgetSeconds) {
    if (!Double.isFinite(budgetSeconds)) return Long.MAX_VALUE;
    if (budgetSeconds <= 0.0) return 0L;
    double millis = budgetSeconds * 1_000.0;
    return millis >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(1L, Math.round(millis));
  }

  private static boolean finiteNonNegative(Double value) {
    return value != null && Double.isFinite(value) && value >= 0.0;
  }

  private static double usableAccuracy(double value) {
    return Double.isFinite(value) && value > 0.0 ? value : 0.0;
  }

  private static double usableSpeedMps(Double speedKmh) {
    return speedKmh != null && Double.isFinite(speedKmh) && speedKmh > 0.0
        ? speedKmh / 3.6
        : 0.0;
  }

  private static ProviderDemand none() {
    return new ProviderDemand(DemandLevel.NONE, Long.MAX_VALUE);
  }

  private static ProviderDemand continuous() {
    return new ProviderDemand(DemandLevel.CONTINUOUS, 0L);
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
}
