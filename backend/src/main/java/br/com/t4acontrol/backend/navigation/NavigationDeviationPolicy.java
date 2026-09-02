package br.com.t4acontrol.backend.navigation;

import br.com.t4acontrol.backend.location.LocationSnapshot;

/**
 * Confirms that OFF_ROUTE is persistent enough to justify a network recalculation.
 *
 * <p>The engine remains responsible for geometry/cross-track detection. This policy filters noisy
 * GPS fixes and requires temporal persistence before a deviation is considered confirmed.
 */
public final class NavigationDeviationPolicy {
  public static final double DEFAULT_MAX_ACCURACY_METERS = 25.0;
  public static final int DEFAULT_REQUIRED_SAMPLES = 3;
  public static final long DEFAULT_MIN_DURATION_MS = 6_000L;
  public static final long DEFAULT_RECALCULATION_COOLDOWN_MS = 30_000L;

  private final double maxAccuracyMeters;
  private final int requiredSamples;
  private final long minDurationMs;
  private final long recalculationCooldownMs;

  private int consecutiveOffRouteSamples;
  private long firstOffRouteTimestampMs = -1L;
  private long lastRecalculationTimestampMs = Long.MIN_VALUE;

  public NavigationDeviationPolicy() {
    this(
        DEFAULT_MAX_ACCURACY_METERS,
        DEFAULT_REQUIRED_SAMPLES,
        DEFAULT_MIN_DURATION_MS,
        DEFAULT_RECALCULATION_COOLDOWN_MS);
  }

  public NavigationDeviationPolicy(
      double maxAccuracyMeters,
      int requiredSamples,
      long minDurationMs,
      long recalculationCooldownMs) {
    if (maxAccuracyMeters <= 0.0) throw new IllegalArgumentException("maxAccuracyMeters must be positive");
    if (requiredSamples < 1) throw new IllegalArgumentException("requiredSamples must be positive");
    if (minDurationMs < 0L) throw new IllegalArgumentException("minDurationMs cannot be negative");
    if (recalculationCooldownMs < 0L) throw new IllegalArgumentException("recalculationCooldownMs cannot be negative");
    this.maxAccuracyMeters = maxAccuracyMeters;
    this.requiredSamples = requiredSamples;
    this.minDurationMs = minDurationMs;
    this.recalculationCooldownMs = recalculationCooldownMs;
  }

  /** Returns true only when this sample confirms a deviation and recalculation is allowed now. */
  public synchronized boolean shouldRecalculate(NavigationState state, LocationSnapshot location) {
    if (state == null || location == null) {
      resetEvidence();
      return false;
    }

    // A poor fix must never contribute evidence. Reset instead of merely ignoring it so separated
    // noisy samples cannot accumulate into a false confirmation.
    if (!Double.isFinite(location.accuracyMeters)
        || location.accuracyMeters < 0.0
        || location.accuracyMeters > maxAccuracyMeters) {
      resetEvidence();
      return false;
    }

    if (state.status != NavigationState.Status.OFF_ROUTE) {
      resetEvidence();
      return false;
    }

    long timestamp = location.timestampMs;
    if (timestamp <= 0L) {
      resetEvidence();
      return false;
    }

    if (consecutiveOffRouteSamples == 0 || timestamp < firstOffRouteTimestampMs) {
      consecutiveOffRouteSamples = 1;
      firstOffRouteTimestampMs = timestamp;
    } else {
      consecutiveOffRouteSamples++;
    }

    boolean enoughSamples = consecutiveOffRouteSamples >= requiredSamples;
    boolean enoughTime = timestamp - firstOffRouteTimestampMs >= minDurationMs;
    boolean cooldownExpired =
        lastRecalculationTimestampMs == Long.MIN_VALUE
            || timestamp - lastRecalculationTimestampMs >= recalculationCooldownMs;

    if (!enoughSamples || !enoughTime || !cooldownExpired) return false;

    lastRecalculationTimestampMs = timestamp;
    resetEvidence();
    return true;
  }

  public synchronized void reset() {
    resetEvidence();
    lastRecalculationTimestampMs = Long.MIN_VALUE;
  }

  private void resetEvidence() {
    consecutiveOffRouteSamples = 0;
    firstOffRouteTimestampMs = -1L;
  }
}
