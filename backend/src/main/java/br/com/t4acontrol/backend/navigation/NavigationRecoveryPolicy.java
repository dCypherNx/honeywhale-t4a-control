package br.com.t4acontrol.backend.navigation;

import br.com.t4acontrol.backend.location.LocationSnapshot;

/**
 * Decides when an off-route observation is strong enough to pre-warm a recovery route without
 * changing the route currently presented to the rider.
 *
 * <p>Speculation is intentionally selective. We only spend a network request after the first
 * high-quality OFF_ROUTE fix while a directional maneuver is active. That catches the common
 * missed/wrong-turn case several seconds before the normal deviation confirmation, without
 * continuously calculating hypothetical alternatives at every intersection.
 */
public final class NavigationRecoveryPolicy {
  public static final double DEFAULT_MAX_ACCURACY_METERS = 25.0;
  public static final long DEFAULT_MAX_PREFETCH_AGE_MS = 20_000L;
  public static final double DEFAULT_MAX_PREFETCH_ORIGIN_DISTANCE_METERS = 250.0;

  private final double maxAccuracyMeters;
  private final long maxPrefetchAgeMs;
  private final double maxPrefetchOriginDistanceMeters;

  public NavigationRecoveryPolicy() {
    this(
        DEFAULT_MAX_ACCURACY_METERS,
        DEFAULT_MAX_PREFETCH_AGE_MS,
        DEFAULT_MAX_PREFETCH_ORIGIN_DISTANCE_METERS);
  }

  public NavigationRecoveryPolicy(
      double maxAccuracyMeters,
      long maxPrefetchAgeMs,
      double maxPrefetchOriginDistanceMeters) {
    if (maxAccuracyMeters <= 0.0) throw new IllegalArgumentException("maxAccuracyMeters must be positive");
    if (maxPrefetchAgeMs <= 0L) throw new IllegalArgumentException("maxPrefetchAgeMs must be positive");
    if (maxPrefetchOriginDistanceMeters <= 0.0) {
      throw new IllegalArgumentException("maxPrefetchOriginDistanceMeters must be positive");
    }
    this.maxAccuracyMeters = maxAccuracyMeters;
    this.maxPrefetchAgeMs = maxPrefetchAgeMs;
    this.maxPrefetchOriginDistanceMeters = maxPrefetchOriginDistanceMeters;
  }

  /** True when this observation is worth pre-warming a route in the background. */
  public boolean shouldPrefetch(
      NavigationState previousUiState,
      NavigationState observedState,
      LocationSnapshot location) {
    if (previousUiState == null || observedState == null || !goodFix(location)) return false;
    if (observedState.status != NavigationState.Status.OFF_ROUTE) return false;
    if (previousUiState.routeId == null || !previousUiState.routeId.equals(observedState.routeId)) return false;
    NavigationInstruction instruction = previousUiState.instruction;
    return instruction != null && isDirectional(instruction.maneuver);
  }

  /**
   * A prefetched route may be promoted only for the same deviation episode, while it is still
   * temporally and spatially close enough to the current rider position.
   */
  public boolean canPromote(
      String routeId,
      int prefetchedLegIndex,
      long prefetchedAtMs,
      LocationSnapshot prefetchedOrigin,
      NavigationState confirmedOffRoute,
      LocationSnapshot currentLocation) {
    if (routeId == null || confirmedOffRoute == null || currentLocation == null || prefetchedOrigin == null) return false;
    if (confirmedOffRoute.status != NavigationState.Status.OFF_ROUTE) return false;
    if (!routeId.equals(confirmedOffRoute.routeId) || prefetchedLegIndex != confirmedOffRoute.legIndex) return false;
    if (!goodFix(currentLocation) || prefetchedAtMs <= 0L || currentLocation.timestampMs < prefetchedAtMs) return false;
    if (currentLocation.timestampMs - prefetchedAtMs > maxPrefetchAgeMs) return false;
    return NavigationEngine.distanceMeters(
        prefetchedOrigin.latitude,
        prefetchedOrigin.longitude,
        currentLocation.latitude,
        currentLocation.longitude) <= maxPrefetchOriginDistanceMeters;
  }

  private boolean goodFix(LocationSnapshot location) {
    return location != null
        && Double.isFinite(location.accuracyMeters)
        && location.accuracyMeters > 0.0
        && location.accuracyMeters <= maxAccuracyMeters
        && location.timestampMs > 0L;
  }

  private static boolean isDirectional(NavigationInstruction.Maneuver maneuver) {
    return maneuver == NavigationInstruction.Maneuver.TURN_LEFT
        || maneuver == NavigationInstruction.Maneuver.TURN_RIGHT
        || maneuver == NavigationInstruction.Maneuver.U_TURN
        || maneuver == NavigationInstruction.Maneuver.ROUNDABOUT;
  }
}
