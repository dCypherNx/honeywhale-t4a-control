package br.com.t4acontrol.backend.navigation;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;

/**
 * Provider-neutral visual-guidance policy. Route progress stays in {@link NavigationEngine}; this
 * class decides which already-selected route instruction is safe and timely to expose to the UI.
 */
public final class GuidanceEngine {
  private static final double GUIDANCE_MIN_METERS = 15.0;
  private static final double GUIDANCE_DEFAULT_METERS = 30.0;
  private static final double NORMAL_TURN_SECONDS = 10.0;
  private static final double ROUNDABOUT_SECONDS = 12.0;
  private static final double U_TURN_SECONDS = 15.0;
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

  public static State present(
      Route route,
      NavigationState navigation,
      boolean waypointsVisible,
      Double gpsSpeedKmh,
      LocationSnapshot location) {
    State state = basePresentation(route, navigation, waypointsVisible);
    if (state.status != NavigationState.Status.NAVIGATING || state.instruction == null) return state;

    NavigationInstruction.Maneuver maneuver = state.instruction.maneuver;
    if (!isDirectionalManeuver(maneuver) || state.distanceMeters == null) return state;
    if (state.distanceMeters > activationDistanceMeters(gpsSpeedKmh, maneuver)) return hidden(state);
    if (hasAmbiguousTurnAhead(route, state, location)) return hidden(state);
    return state;
  }

  public static double activationDistanceMeters(
      Double gpsSpeedKmh, NavigationInstruction.Maneuver maneuver) {
    if (gpsSpeedKmh == null || !Double.isFinite(gpsSpeedKmh) || gpsSpeedKmh <= 1.0) {
      return GUIDANCE_DEFAULT_METERS;
    }
    return Math.max(GUIDANCE_MIN_METERS, (gpsSpeedKmh / 3.6) * leadSeconds(maneuver));
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

  private static double leadSeconds(NavigationInstruction.Maneuver maneuver) {
    if (maneuver == NavigationInstruction.Maneuver.U_TURN) return U_TURN_SECONDS;
    if (maneuver == NavigationInstruction.Maneuver.ROUNDABOUT) return ROUNDABOUT_SECONDS;
    return NORMAL_TURN_SECONDS;
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

    Double currentOffset = projectOffsetMeters(metadata.geometry, location.latitude, location.longitude);
    if (currentOffset == null) return false;
    for (NavigationStepMetadata.Intersection intersection : metadata.intersections) {
      if (NavigationEngine.distanceMeters(
              intersection.latitude, intersection.longitude, target.latitude, target.longitude)
          <= TARGET_INTERSECTION_IGNORE_METERS) continue;
      Double intersectionOffset =
          projectOffsetMeters(metadata.geometry, intersection.latitude, intersection.longitude);
      if (intersectionOffset == null
          || intersectionOffset <= currentOffset + AMBIGUOUS_INTERSECTION_PASS_METERS) continue;
      if (offersAlternativeTurn(intersection, target.maneuver)) return true;
    }
    return false;
  }

  private static boolean offersAlternativeTurn(
      NavigationStepMetadata.Intersection intersection,
      NavigationInstruction.Maneuver maneuver) {
    List<Integer> bearings = intersection.bearings;
    if (intersection.inIndex < 0 || intersection.inIndex >= bearings.size()) return false;
    double incomingTravelBearing = normalizeBearing(bearings.get(intersection.inIndex) + 180.0);

    for (int index = 0; index < bearings.size(); index++) {
      if (index == intersection.inIndex || index == intersection.outIndex) continue;
      if (index >= intersection.entry.size() || !intersection.entry.get(index)) continue;
      double delta = signedBearingDelta(incomingTravelBearing, bearings.get(index));
      if (maneuver == NavigationInstruction.Maneuver.TURN_RIGHT
          && delta >= TURN_BRANCH_MIN_DEGREES
          && delta <= TURN_BRANCH_MAX_DEGREES) return true;
      if (maneuver == NavigationInstruction.Maneuver.TURN_LEFT
          && delta <= -TURN_BRANCH_MIN_DEGREES
          && delta >= -TURN_BRANCH_MAX_DEGREES) return true;
    }
    return false;
  }

  private static Double projectOffsetMeters(
      List<GeoPoint> geometry, double latitude, double longitude) {
    if (geometry == null || geometry.size() < 2) return null;
    double bestDistance = Double.POSITIVE_INFINITY;
    double bestOffset = 0.0;
    double cumulative = 0.0;
    double refLat = Math.toRadians(latitude);

    for (int index = 0; index < geometry.size() - 1; index++) {
      GeoPoint a = geometry.get(index);
      GeoPoint b = geometry.get(index + 1);
      double segment = NavigationEngine.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude);
      double ax = Math.toRadians(a.longitude - longitude) * Math.cos(refLat) * 6_371_000.0;
      double ay = Math.toRadians(a.latitude - latitude) * 6_371_000.0;
      double bx = Math.toRadians(b.longitude - longitude) * Math.cos(refLat) * 6_371_000.0;
      double by = Math.toRadians(b.latitude - latitude) * 6_371_000.0;
      double dx = bx - ax;
      double dy = by - ay;
      double denominator = dx * dx + dy * dy;
      double t = denominator == 0.0
          ? 0.0
          : Math.max(0.0, Math.min(1.0, -(ax * dx + ay * dy) / denominator));
      double px = ax + t * dx;
      double py = ay + t * dy;
      double distance = Math.sqrt(px * px + py * py);
      if (distance < bestDistance) {
        bestDistance = distance;
        bestOffset = cumulative + t * segment;
      }
      cumulative += segment;
    }
    return bestOffset;
  }

  private static boolean isDirectionalManeuver(NavigationInstruction.Maneuver maneuver) {
    return maneuver == NavigationInstruction.Maneuver.TURN_LEFT
        || maneuver == NavigationInstruction.Maneuver.TURN_RIGHT
        || maneuver == NavigationInstruction.Maneuver.U_TURN
        || maneuver == NavigationInstruction.Maneuver.ROUNDABOUT;
  }

  private static double normalizeBearing(double value) {
    return ((value % 360.0) + 360.0) % 360.0;
  }

  private static double signedBearingDelta(double from, double to) {
    return ((normalizeBearing(to) - normalizeBearing(from) + 540.0) % 360.0) - 180.0;
  }

  private static VisibleInstruction nextVisibleInstruction(Route route, int legIndex, int instructionIndex) {
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
