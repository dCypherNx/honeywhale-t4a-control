package br.com.t4acontrol.backend.navigation;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;
import java.util.Objects;

/** Provider-neutral navigation progress engine using route geometry when available. */
public final class NavigationEngine {
  private static final double DEFAULT_REACHED_THRESHOLD_METERS = 25.0;
  private static final double DEFAULT_OFF_ROUTE_BASE_THRESHOLD_METERS = 35.0;
  private static final double MAX_ACCURACY_MARGIN_METERS = 25.0;
  private final double reachedThresholdMeters;
  private final double offRouteBaseThresholdMeters;

  public NavigationEngine() { this(DEFAULT_REACHED_THRESHOLD_METERS, DEFAULT_OFF_ROUTE_BASE_THRESHOLD_METERS); }
  public NavigationEngine(double reachedThresholdMeters) { this(reachedThresholdMeters, DEFAULT_OFF_ROUTE_BASE_THRESHOLD_METERS); }
  public NavigationEngine(double reachedThresholdMeters, double offRouteBaseThresholdMeters) {
    if (reachedThresholdMeters <= 0 || offRouteBaseThresholdMeters <= 0) throw new IllegalArgumentException("navigation thresholds must be positive");
    this.reachedThresholdMeters = reachedThresholdMeters;
    this.offRouteBaseThresholdMeters = offRouteBaseThresholdMeters;
  }

  public NavigationState update(Route route, NavigationState previous, LocationSnapshot location) {
    Objects.requireNonNull(route, "route");
    if (location == null) return NavigationState.of(NavigationState.Status.POSITION_UNAVAILABLE, route,
        previous == null ? 0 : Math.max(previous.legIndex, 0), previous == null ? 0 : Math.max(previous.instructionIndex, 0), previous == null ? null : previous.instruction, null);

    Cursor cursor = resolveCursor(route, previous);
    if (cursor == null) return NavigationState.of(NavigationState.Status.ARRIVED, route, Math.max(route.legs.size() - 1, 0), -1, null, 0.0);

    RouteLeg leg = route.legs.get(cursor.legIndex);
    Progress progress = project(leg.geometry, location.latitude, location.longitude);
    double offRouteThreshold = effectiveOffRouteThreshold(offRouteBaseThresholdMeters, location.accuracyMeters);
    if (progress != null && progress.distanceFromRouteMeters > offRouteThreshold) {
      return NavigationState.of(NavigationState.Status.OFF_ROUTE, route, cursor.legIndex, cursor.instructionIndex, cursor.instruction, null);
    }

    double distance = distanceToInstruction(cursor.instruction, progress, location);
    if (distance <= reachedThresholdMeters) {
      if (cursor.instruction.maneuver == NavigationInstruction.Maneuver.ARRIVE) {
        return NavigationState.of(NavigationState.Status.ARRIVED, route, cursor.legIndex, cursor.instructionIndex, cursor.instruction, distance);
      }
      Cursor next = nextCursor(route, cursor.legIndex, cursor.instructionIndex);
      if (next == null) return NavigationState.of(NavigationState.Status.ARRIVED, route, cursor.legIndex, cursor.instructionIndex, cursor.instruction, distance);
      NavigationState.Status status = cursor.instruction.maneuver == NavigationInstruction.Maneuver.WAYPOINT
          ? NavigationState.Status.WAYPOINT_REACHED : NavigationState.Status.NAVIGATING;
      RouteLeg nextLeg = route.legs.get(next.legIndex);
      Progress nextProgress = next.legIndex == cursor.legIndex ? progress : project(nextLeg.geometry, location.latitude, location.longitude);
      return NavigationState.of(status, route, next.legIndex, next.instructionIndex, next.instruction,
          distanceToInstruction(next.instruction, nextProgress, location));
    }
    return NavigationState.of(NavigationState.Status.NAVIGATING, route, cursor.legIndex, cursor.instructionIndex, cursor.instruction, distance);
  }

  /** Base route corridor plus a bounded GPS uncertainty margin. Accuracy 0 is unknown in Android. */
  static double effectiveOffRouteThreshold(double baseThresholdMeters, double accuracyMeters) {
    double margin = (!Double.isFinite(accuracyMeters) || accuracyMeters <= 0.0)
        ? MAX_ACCURACY_MARGIN_METERS
        : Math.min(accuracyMeters, MAX_ACCURACY_MARGIN_METERS);
    return baseThresholdMeters + margin;
  }

  private static double distanceToInstruction(NavigationInstruction instruction, Progress progress, LocationSnapshot location) {
    if (progress != null && !Double.isNaN(instruction.routeOffsetMeters)) return Math.max(0.0, instruction.routeOffsetMeters - progress.offsetMeters);
    return distanceMeters(location.latitude, location.longitude, instruction.latitude, instruction.longitude);
  }

  private static Progress project(List<GeoPoint> geometry, double latitude, double longitude) {
    if (geometry == null || geometry.size() < 2) return null;
    double bestDistance = Double.POSITIVE_INFINITY, bestOffset = 0.0, cumulative = 0.0;
    for (int i = 0; i < geometry.size() - 1; i++) {
      GeoPoint a = geometry.get(i), b = geometry.get(i + 1);
      double segment = distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude);
      double refLat = Math.toRadians(latitude);
      double ax = Math.toRadians(a.longitude - longitude) * Math.cos(refLat) * 6_371_000.0;
      double ay = Math.toRadians(a.latitude - latitude) * 6_371_000.0;
      double bx = Math.toRadians(b.longitude - longitude) * Math.cos(refLat) * 6_371_000.0;
      double by = Math.toRadians(b.latitude - latitude) * 6_371_000.0;
      double dx = bx - ax, dy = by - ay, denom = dx * dx + dy * dy;
      double t = denom == 0.0 ? 0.0 : Math.max(0.0, Math.min(1.0, -(ax * dx + ay * dy) / denom));
      double px = ax + t * dx, py = ay + t * dy, distance = Math.sqrt(px * px + py * py);
      if (distance < bestDistance) { bestDistance = distance; bestOffset = cumulative + t * segment; }
      cumulative += segment;
    }
    return new Progress(bestOffset, bestDistance);
  }

  private static Cursor resolveCursor(Route route, NavigationState previous) {
    int legIndex = previous == null || previous.legIndex < 0 ? 0 : previous.legIndex;
    int instructionIndex = previous == null || previous.instructionIndex < 0 ? 0 : previous.instructionIndex;
    return cursorAtOrAfter(route, legIndex, instructionIndex);
  }
  private static Cursor nextCursor(Route route, int legIndex, int instructionIndex) { return cursorAtOrAfter(route, legIndex, instructionIndex + 1); }
  private static Cursor cursorAtOrAfter(Route route, int legIndex, int instructionIndex) {
    for (int leg = legIndex; leg < route.legs.size(); leg++) {
      List<NavigationInstruction> instructions = route.legs.get(leg).instructions; int start = leg == legIndex ? instructionIndex : 0;
      if (start < instructions.size()) return new Cursor(leg, start, instructions.get(start));
    }
    return null;
  }

  static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
    double earthRadiusMeters = 6_371_000.0, phi1 = Math.toRadians(lat1), phi2 = Math.toRadians(lat2), deltaPhi = Math.toRadians(lat2 - lat1), deltaLambda = Math.toRadians(lon2 - lon1);
    double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2) + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
    return earthRadiusMeters * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  private static final class Progress { final double offsetMeters, distanceFromRouteMeters; Progress(double offsetMeters, double distanceFromRouteMeters) { this.offsetMeters = offsetMeters; this.distanceFromRouteMeters = distanceFromRouteMeters; } }
  private static final class Cursor { final int legIndex, instructionIndex; final NavigationInstruction instruction; Cursor(int legIndex, int instructionIndex, NavigationInstruction instruction) { this.legIndex = legIndex; this.instructionIndex = instructionIndex; this.instruction = instruction; } }
}
