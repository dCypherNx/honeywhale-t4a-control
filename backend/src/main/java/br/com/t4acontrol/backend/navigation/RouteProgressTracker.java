package br.com.t4acontrol.backend.navigation;

import br.com.t4acontrol.backend.location.LocationSnapshot;

/**
 * Stateful provider-neutral matcher for physical fixes against the active route.
 *
 * <p>It is the single owner of route projection continuity. Other navigation policies consume the
 * same progress snapshot instead of projecting the same fix independently.
 */
public final class RouteProgressTracker {
  private RouteProgressSnapshot previous;

  public void reset() {
    previous = null;
  }

  public RouteProgressSnapshot observe(
      Route route, NavigationState state, LocationSnapshot location) {
    RouteProgressSnapshot current = project(route, state, location, previous);
    previous = current != null && current.available() ? current : null;
    return current;
  }

  static RouteProgressSnapshot project(
      Route route, NavigationState state, LocationSnapshot location) {
    return project(route, state, location, null);
  }

  private static RouteProgressSnapshot project(
      Route route,
      NavigationState state,
      LocationSnapshot location,
      RouteProgressSnapshot previous) {
    if (route == null || state == null || location == null
        || state.legIndex < 0 || state.legIndex >= route.legs.size()) {
      return new RouteProgressSnapshot(-1, Double.NaN, Double.NaN, false,
          location == null ? 0L : location.timestampMs);
    }

    RouteGeometry.Projection projection = RouteGeometry.project(
        route.legs.get(state.legIndex).geometry,
        location.latitude,
        location.longitude);
    if (projection == null) {
      return new RouteProgressSnapshot(state.legIndex, Double.NaN, Double.NaN, false,
          location.timestampMs);
    }

    boolean consistent = true;
    if (previous != null && previous.available() && previous.legIndex == state.legIndex) {
      if (location.timestampMs > 0L
          && previous.timestampMs > 0L
          && location.timestampMs <= previous.timestampMs) {
        consistent = false;
      } else {
        double accuracy = usableAccuracy(location.accuracyMeters);
        double allowance = accuracy > 0.0 ? accuracy : 0.0;
        consistent = projection.offsetMeters + allowance >= previous.routeOffsetMeters;
      }
    }

    return new RouteProgressSnapshot(
        state.legIndex,
        projection.offsetMeters,
        projection.lateralMeters,
        consistent,
        location.timestampMs);
  }

  private static double usableAccuracy(double value) {
    return Double.isFinite(value) && value > 0.0 ? value : 0.0;
  }
}
