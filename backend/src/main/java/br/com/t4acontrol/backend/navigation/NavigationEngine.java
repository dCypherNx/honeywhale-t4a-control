package br.com.t4acontrol.backend.navigation;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;
import java.util.Objects;

/** Provider-neutral navigation state engine. Geometry matching is supplied by RouteProgressTracker. */
public final class NavigationEngine {
  private static final double DEFAULT_REACHED_THRESHOLD_METERS = 25.0;
  private static final double DEFAULT_OFF_ROUTE_BASE_THRESHOLD_METERS = 35.0;
  private static final double MAX_ACCURACY_MARGIN_METERS = 25.0;
  private static final double MANEUVER_PASS_THRESHOLD_METERS = 8.0;
  private static final double MANEUVER_FALLBACK_THRESHOLD_METERS = 8.0;
  private static final double OUTGOING_STEP_MIN_PROGRESS_METERS = 3.0;
  private static final double OUTGOING_STEP_BASE_CORRIDOR_METERS = 8.0;
  private static final double OUTGOING_BEARING_TOLERANCE_DEGREES = 50.0;
  private final double reachedThresholdMeters;
  private final double offRouteBaseThresholdMeters;

  public NavigationEngine() {
    this(DEFAULT_REACHED_THRESHOLD_METERS, DEFAULT_OFF_ROUTE_BASE_THRESHOLD_METERS);
  }

  public NavigationEngine(double reachedThresholdMeters) {
    this(reachedThresholdMeters, DEFAULT_OFF_ROUTE_BASE_THRESHOLD_METERS);
  }

  public NavigationEngine(double reachedThresholdMeters, double offRouteBaseThresholdMeters) {
    if (reachedThresholdMeters <= 0 || offRouteBaseThresholdMeters <= 0) {
      throw new IllegalArgumentException("navigation thresholds must be positive");
    }
    this.reachedThresholdMeters = reachedThresholdMeters;
    this.offRouteBaseThresholdMeters = offRouteBaseThresholdMeters;
  }

  /** Backward-compatible entry point for isolated engine tests/callers. */
  public NavigationState update(Route route, NavigationState previous, LocationSnapshot location) {
    RouteProgressSnapshot progress =
        location == null ? null : RouteProgressTracker.project(route, previous, location);
    return update(route, previous, location, progress);
  }

  /** Uses the same route-progress interpretation that observation/deviation consumers receive. */
  public NavigationState update(
      Route route,
      NavigationState previous,
      LocationSnapshot location,
      RouteProgressSnapshot progress) {
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

    RouteProgressSnapshot activeProgress = progress;
    if (activeProgress == null || activeProgress.legIndex != cursor.legIndex) {
      NavigationState cursorState = NavigationState.of(
          NavigationState.Status.NAVIGATING,
          route,
          cursor.legIndex,
          cursor.instructionIndex,
          cursor.instruction,
          previous == null ? null : previous.distanceToInstructionMeters);
      activeProgress = RouteProgressTracker.project(route, cursorState, location);
    }

    double offRouteThreshold =
        effectiveOffRouteThreshold(offRouteBaseThresholdMeters, location.accuracyMeters);
    if (activeProgress != null
        && activeProgress.available()
        && activeProgress.lateralErrorMeters > offRouteThreshold) {
      return NavigationState.of(
          NavigationState.Status.OFF_ROUTE,
          route,
          cursor.legIndex,
          cursor.instructionIndex,
          cursor.instruction,
          null);
    }

    double distance = distanceToInstruction(cursor.instruction, activeProgress, location);
    if (instructionCompleted(cursor.instruction, activeProgress, distance, location)) {
      if (cursor.instruction.maneuver == NavigationInstruction.Maneuver.ARRIVE) {
        if (cursor.legIndex >= route.legs.size() - 1) {
          return NavigationState.of(
              NavigationState.Status.ARRIVED,
              route,
              cursor.legIndex,
              cursor.instructionIndex,
              cursor.instruction,
              distance);
        }

        Cursor nextLeg = cursorAtOrAfter(route, cursor.legIndex + 1, 0);
        if (nextLeg == null) {
          return NavigationState.of(
              NavigationState.Status.ARRIVED,
              route,
              cursor.legIndex,
              cursor.instructionIndex,
              cursor.instruction,
              distance);
        }
        NavigationState nextLegState = NavigationState.of(
            NavigationState.Status.NAVIGATING,
            route,
            nextLeg.legIndex,
            nextLeg.instructionIndex,
            nextLeg.instruction,
            null);
        RouteProgressSnapshot nextProgress = RouteProgressTracker.project(route, nextLegState, location);
        return NavigationState.of(
            NavigationState.Status.WAYPOINT_REACHED,
            route,
            nextLeg.legIndex,
            nextLeg.instructionIndex,
            nextLeg.instruction,
            distanceToInstruction(nextLeg.instruction, nextProgress, location));
      }

      Cursor next = nextCursor(route, cursor.legIndex, cursor.instructionIndex);
      if (next == null) {
        return NavigationState.of(
            NavigationState.Status.ARRIVED,
            route,
            cursor.legIndex,
            cursor.instructionIndex,
            cursor.instruction,
            distance);
      }
      NavigationState.Status status =
          cursor.instruction.maneuver == NavigationInstruction.Maneuver.WAYPOINT
              ? NavigationState.Status.WAYPOINT_REACHED
              : NavigationState.Status.NAVIGATING;
      RouteProgressSnapshot nextProgress = activeProgress;
      if (next.legIndex != cursor.legIndex) {
        NavigationState nextState = NavigationState.of(
            status, route, next.legIndex, next.instructionIndex, next.instruction, null);
        nextProgress = RouteProgressTracker.project(route, nextState, location);
      }
      return NavigationState.of(
          status,
          route,
          next.legIndex,
          next.instructionIndex,
          next.instruction,
          distanceToInstruction(next.instruction, nextProgress, location));
    }

    return NavigationState.of(
        NavigationState.Status.NAVIGATING,
        route,
        cursor.legIndex,
        cursor.instructionIndex,
        cursor.instruction,
        distance);
  }

  private boolean instructionCompleted(
      NavigationInstruction instruction,
      RouteProgressSnapshot progress,
      double distance,
      LocationSnapshot location) {
    if (instruction.maneuver == NavigationInstruction.Maneuver.ARRIVE
        || instruction.maneuver == NavigationInstruction.Maneuver.WAYPOINT
        || instruction.maneuver == NavigationInstruction.Maneuver.START) {
      return distance <= reachedThresholdMeters;
    }
    if (completedOnOutgoingStep(instruction, location)) return true;
    if (progress != null && progress.available() && Double.isFinite(instruction.routeOffsetMeters)) {
      return progress.routeOffsetMeters
          >= instruction.routeOffsetMeters + MANEUVER_PASS_THRESHOLD_METERS;
    }
    return distance <= MANEUVER_FALLBACK_THRESHOLD_METERS;
  }

  private static boolean completedOnOutgoingStep(
      NavigationInstruction instruction, LocationSnapshot location) {
    if (!isDirectionalManeuver(instruction.maneuver) || instruction.metadata == null) return false;
    List<GeoPoint> outgoingGeometry = instruction.metadata.geometry;
    RouteGeometry.Projection outgoing =
        RouteGeometry.project(outgoingGeometry, location.latitude, location.longitude);
    if (outgoing == null || outgoing.offsetMeters < OUTGOING_STEP_MIN_PROGRESS_METERS) return false;

    double accuracyMargin =
        !Double.isFinite(location.accuracyMeters) || location.accuracyMeters <= 0.0
            ? 0.0
            : Math.min(location.accuracyMeters, 8.0);
    if (outgoing.lateralMeters > OUTGOING_STEP_BASE_CORRIDOR_METERS + accuracyMargin) return false;

    int bearingAfter = instruction.metadata.bearingAfter;
    if (bearingAfter < 0
        || location.bearingDegrees == null
        || !Double.isFinite(location.bearingDegrees)) return false;
    return RouteGeometry.angularDifferenceDegrees(location.bearingDegrees, bearingAfter)
        <= OUTGOING_BEARING_TOLERANCE_DEGREES;
  }

  private static boolean isDirectionalManeuver(NavigationInstruction.Maneuver maneuver) {
    return maneuver == NavigationInstruction.Maneuver.TURN_LEFT
        || maneuver == NavigationInstruction.Maneuver.TURN_RIGHT
        || maneuver == NavigationInstruction.Maneuver.U_TURN
        || maneuver == NavigationInstruction.Maneuver.ROUNDABOUT;
  }

  static double effectiveOffRouteThreshold(double baseThresholdMeters, double accuracyMeters) {
    double margin =
        (!Double.isFinite(accuracyMeters) || accuracyMeters <= 0.0)
            ? MAX_ACCURACY_MARGIN_METERS
            : Math.min(accuracyMeters, MAX_ACCURACY_MARGIN_METERS);
    return baseThresholdMeters + margin;
  }

  private static double distanceToInstruction(
      NavigationInstruction instruction,
      RouteProgressSnapshot progress,
      LocationSnapshot location) {
    if (progress != null && progress.available() && Double.isFinite(instruction.routeOffsetMeters)) {
      return Math.max(0.0, instruction.routeOffsetMeters - progress.routeOffsetMeters);
    }
    return distanceMeters(
        location.latitude,
        location.longitude,
        instruction.latitude,
        instruction.longitude);
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
      if (start < instructions.size()) return new Cursor(leg, start, instructions.get(start));
    }
    return null;
  }

  static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
    return RouteGeometry.distanceMeters(lat1, lon1, lat2, lon2);
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
