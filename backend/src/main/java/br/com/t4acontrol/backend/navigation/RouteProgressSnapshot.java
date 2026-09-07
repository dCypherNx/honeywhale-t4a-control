package br.com.t4acontrol.backend.navigation;

/** Immutable interpretation of one physical fix against the active route geometry. */
public final class RouteProgressSnapshot {
  public final int legIndex;
  public final double routeOffsetMeters;
  public final double lateralErrorMeters;
  public final boolean progressConsistent;
  public final long timestampMs;

  RouteProgressSnapshot(
      int legIndex,
      double routeOffsetMeters,
      double lateralErrorMeters,
      boolean progressConsistent,
      long timestampMs) {
    this.legIndex = legIndex;
    this.routeOffsetMeters = routeOffsetMeters;
    this.lateralErrorMeters = lateralErrorMeters;
    this.progressConsistent = progressConsistent;
    this.timestampMs = timestampMs;
  }

  public boolean available() {
    return legIndex >= 0
        && Double.isFinite(routeOffsetMeters)
        && Double.isFinite(lateralErrorMeters);
  }
}
