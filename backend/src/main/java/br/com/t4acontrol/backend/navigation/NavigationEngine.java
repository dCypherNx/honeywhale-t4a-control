package br.com.t4acontrol.backend.navigation;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;
import java.util.Objects;

/** Minimal navigation progress engine. Geometry/provider-specific routing stays outside this class. */
public final class NavigationEngine {
  private static final double DEFAULT_REACHED_THRESHOLD_METERS = 25.0;
  private final double reachedThresholdMeters;

  public NavigationEngine() {
    this(DEFAULT_REACHED_THRESHOLD_METERS);
  }

  public NavigationEngine(double reachedThresholdMeters) {
    if (reachedThresholdMeters <= 0) {
      throw new IllegalArgumentException("reachedThresholdMeters must be positive");
    }
    this.reachedThresholdMeters = reachedThresholdMeters;
  }

  public NavigationState update(
      Route route,
      NavigationState previous,
      LocationSnapshot location) {
    Objects.requireNonNull(route, "route");
    if (location == null) {
      return NavigationState.of(
          NavigationState.Status.POSITION_UNAVAILABLE,
          route,
          previous == null ? 0 : Math.max(previous.legIndex, 0),
          previous == null ? 0 : Math.max(previous.instructionIndex, 0),
          previous == null ? null : previous.instruction,
          null);
    }

    Cursor cursor = resolveCursor(route, previous);
    if (cursor == null) {
      return NavigationState.of(
          NavigationState.Status.ARRIVED,
          route,
          Math.max(route.legs.size() - 1, 0),
          -1,
          null,
          0.0);
    }

    NavigationInstruction instruction = cursor.instruction;
    double distance = distanceMeters(
        location.latitude,
        location.longitude,
        instruction.latitude,
        instruction.longitude);

    if (distance <= reachedThresholdMeters) {
      Cursor next = nextCursor(route, cursor.legIndex, cursor.instructionIndex);
      NavigationState.Status reachedStatus =
          instruction.maneuver == NavigationInstruction.Maneuver.ARRIVE
              ? NavigationState.Status.ARRIVED
              : instruction.maneuver == NavigationInstruction.Maneuver.WAYPOINT
                  ? NavigationState.Status.WAYPOINT_REACHED
                  : NavigationState.Status.NAVIGATING;

      if (next == null) {
        return NavigationState.of(
            NavigationState.Status.ARRIVED,
            route,
            cursor.legIndex,
            cursor.instructionIndex,
            instruction,
            distance);
      }

      if (reachedStatus == NavigationState.Status.WAYPOINT_REACHED) {
        return NavigationState.of(
            reachedStatus,
            route,
            next.legIndex,
            next.instructionIndex,
            next.instruction,
            distanceMeters(
                location.latitude,
                location.longitude,
                next.instruction.latitude,
                next.instruction.longitude));
      }

      return NavigationState.of(
          NavigationState.Status.NAVIGATING,
          route,
          next.legIndex,
          next.instructionIndex,
          next.instruction,
          distanceMeters(
              location.latitude,
              location.longitude,
              next.instruction.latitude,
              next.instruction.longitude));
    }

    return NavigationState.of(
        NavigationState.Status.NAVIGATING,
        route,
        cursor.legIndex,
        cursor.instructionIndex,
        instruction,
        distance);
  }

  private static Cursor resolveCursor(Route route, NavigationState previous) {
    int legIndex = previous == null || previous.legIndex < 0 ? 0 : previous.legIndex;
    int instructionIndex =
        previous == null || previous.instructionIndex < 0 ? 0 : previous.instructionIndex;
    return cursorAtOrAfter(route, legIndex, instructionIndex);
  }

  private static Cursor nextCursor(Route route, int legIndex, int instructionIndex) {
    return cursorAtOrAfter(route, legIndex, instructionIndex + 1);
  }

  private static Cursor cursorAtOrAfter(Route route, int legIndex, int instructionIndex) {
    for (int leg = legIndex; leg < route.legs.size(); leg++) {
      List<NavigationInstruction> instructions = route.legs.get(leg).instructions;
      int start = leg == legIndex ? instructionIndex : 0;
      if (start < instructions.size()) {
        return new Cursor(leg, start, instructions.get(start));
      }
    }
    return null;
  }

  static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
    double earthRadiusMeters = 6_371_000.0;
    double phi1 = Math.toRadians(lat1);
    double phi2 = Math.toRadians(lat2);
    double deltaPhi = Math.toRadians(lat2 - lat1);
    double deltaLambda = Math.toRadians(lon2 - lon1);
    double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
        + Math.cos(phi1) * Math.cos(phi2)
        * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return earthRadiusMeters * c;
  }

  private static final class Cursor {
    final int legIndex;
    final int instructionIndex;
    final NavigationInstruction instruction;

    Cursor(int legIndex, int instructionIndex, NavigationInstruction instruction) {
      this.legIndex = legIndex;
      this.instructionIndex = instructionIndex;
      this.instruction = instruction;
    }
  }
}
