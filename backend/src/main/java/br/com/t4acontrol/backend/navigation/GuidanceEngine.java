package br.com.t4acontrol.backend.navigation;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;

/**
 * Provider-neutral visual-guidance policy.
 *
 * <p>Route progress stays in {@link NavigationEngine}. This class only decides which already-active
 * instruction is safe to expose. It deliberately contains no fixed maneuver time horizon: a
 * directional instruction becomes visible as soon as it is the active route instruction and no
 * earlier physically enterable same-side branch makes that instruction ambiguous.
 */
public final class GuidanceEngine {
  private static final double AMBIGUOUS_INTERSECTION_PASS_METERS = 8.0;
  private static final double TARGET_INTERSECTION_IGNORE_METERS = 12.0;
  private static final double TURN_BRANCH_MIN_DEGREES = 25.0;
  private static final double TURN_BRANCH_MAX_DEGREES = 155.0;

  private GuidanceEngine() {}

  public static final class State {
    public final NavigationState.Status status;
    public final NavigationInstruction instruction;
    public final Double distanceMeters;
    public final int legIndex;
    public final int instructionIndex;

    State(
        NavigationState.Status status,
        NavigationInstruction instruction,
        Double distanceMeters,
        int legIndex,
        int instructionIndex) {
      this.status = status;
      this.instruction = instruction;
      this.distanceMeters = distanceMeters;
      this.legIndex = legIndex;
      this.instructionIndex = instructionIndex;
    }
  }

  /**
   * gpsSpeedKmh remains in the signature for API compatibility with the UI adapter but is no longer
   * used to impose an arbitrary lead-time rule.
   */
  public static State present(
      Route route,
      NavigationState navigation,
      boolean waypointsVisible,
      Double gpsSpeedKmh,
      LocationSnapshot location) {
    State state = basePresentation(route, navigation, waypointsVisible);
    if (state.status != NavigationState.Status.NAVIGATING || state.instruction == null) return state;
    if (!isDirectionalManeuver(state.instruction.maneuver)) return state;
    if (hasAmbiguousTurnAhead(route, state, location)) return hidden(state);
    return state;
  }

  private static State basePresentation(
      Route route, NavigationState state, boolean waypointsVisible) {
    if (state.status == NavigationState.Status.PAUSED || waypointsVisible || route == null) {
      return fromNavigationState(state);
    }

    boolean hidesWaypoint =
        state.status == NavigationState.Status.WAYPOINT_REACHED
            || (state.instruction != null
                && state.instruction.maneuver == NavigationInstruction.Maneuver.WAYPOINT);
    if (!hidesWaypoint) return fromNavigationState(state);

    VisibleInstruction next = nextVisibleInstruction(route, state.legIndex, state.instructionIndex);
    if (next == null) {
      return new State(NavigationState.Status.NAVIGATING, null, null, -1, -1);
    }
    double baseDistance = state.distanceToInstructionMeters == null ? 0.0 : state.distanceToInstructionMeters;
    double extraDistance = Double.isFinite(next.instruction.routeOffsetMeters)
        ? next.instruction.routeOffsetMeters
        : 0.0;
    return new State(
        NavigationState.Status.NAVIGATING,
        next.instruction,
        Math.max(0.0, baseDistance + extraDistance),
        next.legIndex,
        next.instructionIndex);
  }

  private static State fromNavigationState(NavigationState state) {
    return new State(
        state.status,
        state.instruction,
        state.distanceToInstructionMeters,
        state.legIndex,
        state.instructionIndex);
  }

  private static State hidden(State state) {
    return new State(
        NavigationState.Status.NAVIGATING,
        null,
        state.distanceMeters,
        state.legIndex,
        state.instructionIndex);
  }

  private static boolean hasAmbiguousTurnAhead(Route route, State state, LocationSnapshot location) {
    NavigationInstruction target = state.instruction;
    if (target == null || location == null || route == null) return false;
    if (target.maneuver != NavigationInstruction.Maneuver.TURN_LEFT
        && target.maneuver != NavigationInstruction.Maneuver.TURN_RIGHT) return false;
    if (state.legIndex < 0 || state.legIndex >= route.legs.size()) return false;

    List<NavigationInstruction> instructions = route.legs.get(state.legIndex).instructions;
    if (state.instructionIndex <= 0 || state.instructionIndex >= instructions.size()) return false;
    NavigationStepMetadata metadata = instructions.get(state.instructionIndex - 1).metadata;
    if (metadata == null || metadata.geometry.size() < 2 || metadata.intersections.isEmpty()) return false;

    RouteGeometry.Projection current =
        RouteGeometry.project(metadata.geometry, location.latitude, location.longitude);
    if (current == null) return false;

    for (NavigationStepMetadata.Intersection intersection : metadata.intersections) {
      if (RouteGeometry.distanceMeters(
              intersection.latitude, intersection.longitude, target.latitude, target.longitude)
          <= TARGET_INTERSECTION_IGNORE_METERS) continue;
      double intersectionOffset = RouteGeometry.projectedOffsetMeters(
          metadata.geometry, intersection.latitude, intersection.longitude);
      if (!Double.isFinite(intersectionOffset)
          || intersectionOffset <= current.offsetMeters + AMBIGUOUS_INTERSECTION_PASS_METERS) continue;
      if (offersAlternativeTurn(intersection, target.maneuver)) return true;
    }
    return false;
  }

  private static boolean offersAlternativeTurn(
      NavigationStepMetadata.Intersection intersection,
      NavigationInstruction.Maneuver maneuver) {
    List<Integer> bearings = intersection.bearings;
    if (intersection.inIndex < 0 || intersection.inIndex >= bearings.size()) return false;
    double incomingTravelBearing =
        RouteGeometry.normalizeBearing(bearings.get(intersection.inIndex) + 180.0);

    for (int index = 0; index < bearings.size(); index++) {
      if (index == intersection.inIndex || index == intersection.outIndex) continue;
      if (index >= intersection.entry.size() || !intersection.entry.get(index)) continue;
      double delta = RouteGeometry.signedBearingDelta(incomingTravelBearing, bearings.get(index));
      if (maneuver == NavigationInstruction.Maneuver.TURN_RIGHT
          && delta >= TURN_BRANCH_MIN_DEGREES
          && delta <= TURN_BRANCH_MAX_DEGREES) return true;
      if (maneuver == NavigationInstruction.Maneuver.TURN_LEFT
          && delta <= -TURN_BRANCH_MIN_DEGREES
          && delta >= -TURN_BRANCH_MAX_DEGREES) return true;
    }
    return false;
  }

  private static boolean isDirectionalManeuver(NavigationInstruction.Maneuver maneuver) {
    return maneuver == NavigationInstruction.Maneuver.TURN_LEFT
        || maneuver == NavigationInstruction.Maneuver.TURN_RIGHT
        || maneuver == NavigationInstruction.Maneuver.U_TURN
        || maneuver == NavigationInstruction.Maneuver.ROUNDABOUT;
  }

  private static VisibleInstruction nextVisibleInstruction(
      Route route, int legIndex, int instructionIndex) {
    for (int leg = Math.max(0, legIndex); leg < route.legs.size(); leg++) {
      List<NavigationInstruction> instructions = route.legs.get(leg).instructions;
      int start = leg == legIndex ? Math.max(0, instructionIndex + 1) : 0;
      for (int index = start; index < instructions.size(); index++) {
        NavigationInstruction instruction = instructions.get(index);
        if (instruction.maneuver != NavigationInstruction.Maneuver.WAYPOINT
            && instruction.maneuver != NavigationInstruction.Maneuver.START) {
          return new VisibleInstruction(instruction, leg, index);
        }
      }
    }
    return null;
  }

  private static final class VisibleInstruction {
    final NavigationInstruction instruction;
    final int legIndex;
    final int instructionIndex;

    VisibleInstruction(NavigationInstruction instruction, int legIndex, int instructionIndex) {
      this.instruction = instruction;
      this.legIndex = legIndex;
      this.instructionIndex = instructionIndex;
    }
  }
}
