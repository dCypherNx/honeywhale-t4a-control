package br.com.t4acontrol.backend.navigation;

/** Current provider-neutral progress through a route. */
public final class NavigationState {
  public enum Status {
    NO_ROUTE,
    READY,
    NAVIGATING,
    WAYPOINT_REACHED,
    ARRIVED,
    POSITION_UNAVAILABLE,
    OFF_ROUTE,
    RECALCULATING
  }

  public final Status status;
  public final String routeId;
  public final int legIndex;
  public final int instructionIndex;
  public final NavigationInstruction instruction;
  public final Double distanceToInstructionMeters;

  private NavigationState(
      Status status,
      String routeId,
      int legIndex,
      int instructionIndex,
      NavigationInstruction instruction,
      Double distanceToInstructionMeters) {
    this.status = status;
    this.routeId = routeId;
    this.legIndex = legIndex;
    this.instructionIndex = instructionIndex;
    this.instruction = instruction;
    this.distanceToInstructionMeters = distanceToInstructionMeters;
  }

  public static NavigationState noRoute() {
    return new NavigationState(Status.NO_ROUTE, null, -1, -1, null, null);
  }

  public static NavigationState ready(Route route) {
    return new NavigationState(Status.READY, route.id, 0, 0, null, null);
  }

  public static NavigationState recalculating(Route route, NavigationState previous) {
    return new NavigationState(
        Status.RECALCULATING,
        route.id,
        previous == null ? 0 : Math.max(previous.legIndex, 0),
        previous == null ? 0 : Math.max(previous.instructionIndex, 0),
        previous == null ? null : previous.instruction,
        null);
  }

  static NavigationState of(
      Status status,
      Route route,
      int legIndex,
      int instructionIndex,
      NavigationInstruction instruction,
      Double distanceMeters) {
    return new NavigationState(
        status, route.id, legIndex, instructionIndex, instruction, distanceMeters);
  }
}
