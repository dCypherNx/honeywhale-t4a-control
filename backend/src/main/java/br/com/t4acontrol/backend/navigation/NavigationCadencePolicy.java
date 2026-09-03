package br.com.t4acontrol.backend.navigation;

import java.util.Objects;

/**
 * Converts navigation urgency into a provider-neutral location/processing cadence.
 *
 * <p>The policy is deliberately bidirectional: long predictable stretches may relax into tens of
 * seconds, while maneuver execution and deviation handling remove navigation throttling entirely.
 */
public final class NavigationCadencePolicy {
  public static final long MIN_LOCATION_INTERVAL_MS = 500L;
  public static final long MAX_LOCATION_INTERVAL_MS = 20_000L;
  public static final long DEFAULT_NO_DISTANCE_INTERVAL_MS = 2_000L;
  public static final double MIN_MOVING_SPEED_KMH = 3.0;
  public static final double MAX_MIN_DISTANCE_METERS = 50.0;

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
  public Decision evaluate(NavigationState state, Double speedKmh) {
    if (state == null) return null;
    switch (state.status) {
      case NO_ROUTE:
      case PAUSED:
      case ARRIVED:
        return null;
      case READY:
      case POSITION_UNAVAILABLE:
        return decision(1_000L, 1_000L, true, 1.0, speedKmh);
      case OFF_ROUTE:
      case RECALCULATING:
        return decision(MIN_LOCATION_INTERVAL_MS, 0L, true, 0.0, speedKmh);
      case WAYPOINT_REACHED:
        return decision(MIN_LOCATION_INTERVAL_MS, 0L, true, 0.0, speedKmh);
      case NAVIGATING:
      default:
        return navigating(state, speedKmh);
    }
  }

  private Decision navigating(NavigationState state, Double speedKmh) {
    NavigationInstruction instruction = state.instruction;
    Double distance = state.distanceToInstructionMeters;
    if (instruction == null || distance == null || !Double.isFinite(distance) || distance < 0.0) {
      return decision(
          DEFAULT_NO_DISTANCE_INTERVAL_MS,
          DEFAULT_NO_DISTANCE_INTERVAL_MS,
          true,
          2.0,
          speedKmh);
    }

    double speed = finitePositive(speedKmh) ? speedKmh : 0.0;
    if (speed < MIN_MOVING_SPEED_KMH) {
      long interval;
      if (distance <= 30.0) interval = 1_000L;
      else if (distance <= 100.0) interval = 3_000L;
      else if (distance <= 300.0) interval = 10_000L;
      else interval = MAX_LOCATION_INTERVAL_MS;
      boolean precise = distance <= 300.0;
      return decision(interval, interval, precise, precise ? 2.0 : 10.0, speedKmh);
    }

    double speedMps = speed / 3.6;
    double etaSeconds = distance / speedMps;
    double criticalHorizonSeconds = criticalHorizonSeconds(instruction.maneuver);
    double slackSeconds = etaSeconds - criticalHorizonSeconds;

    if (slackSeconds <= 0.0) {
      return decision(MIN_LOCATION_INTERVAL_MS, 0L, true, 0.0, speedKmh);
    }

    // Consume at most half of the time still available before the critical maneuver horizon. This
    // makes relaxation and acceleration continuous rather than a ladder of fixed distance bands.
    long interval = Math.round(slackSeconds * 0.5 * 1_000.0);
    interval = clamp(interval, MIN_LOCATION_INTERVAL_MS, MAX_LOCATION_INTERVAL_MS);

    // Once the next decision is within roughly 30 s of its critical horizon, prefer precision.
    boolean precise = slackSeconds <= 30.0 || distance <= 250.0;
    long processing = interval <= MIN_LOCATION_INTERVAL_MS ? 0L : interval;
    return decision(interval, processing, precise, spatialHint(interval, speedMps), speedKmh);
  }

  private static Decision decision(
      long locationIntervalMs,
      long processingIntervalMs,
      boolean highAccuracy,
      double explicitMinDistance,
      Double speedKmh) {
    double minDistance = explicitMinDistance;
    if (explicitMinDistance < 0.0) {
      double speedMps = finitePositive(speedKmh) ? speedKmh / 3.6 : 0.0;
      minDistance = spatialHint(locationIntervalMs, speedMps);
    }
    return new Decision(
        clamp(locationIntervalMs, MIN_LOCATION_INTERVAL_MS, MAX_LOCATION_INTERVAL_MS),
        Math.max(0L, processingIntervalMs),
        highAccuracy,
        Math.max(0.0, Math.min(MAX_MIN_DISTANCE_METERS, minDistance)));
  }

  private static double spatialHint(long intervalMs, double speedMps) {
    if (intervalMs <= MIN_LOCATION_INTERVAL_MS) return 0.0;
    if (speedMps <= 0.0) return 2.0;
    double traveled = speedMps * (intervalMs / 1_000.0);
    return Math.max(2.0, Math.min(MAX_MIN_DISTANCE_METERS, traveled * 0.5));
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
}
