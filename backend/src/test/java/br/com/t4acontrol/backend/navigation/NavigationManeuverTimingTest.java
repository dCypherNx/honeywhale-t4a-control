package br.com.t4acontrol.backend.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;
import org.junit.Test;

public final class NavigationManeuverTimingTest {
  @Test public void turnRemainsActiveInsideWaypointRadiusUntilActuallyPassed() {
    Waypoint origin = waypoint("origin", 0.0, 0.0, Waypoint.Role.ORIGIN);
    Waypoint destination = waypoint("destination", 0.0010, 0.0, Waypoint.Role.DESTINATION);
    NavigationInstruction turn = new NavigationInstruction(
        "turn", NavigationInstruction.Maneuver.TURN_RIGHT, "turn",
        0.00050, 0.0, 55.6, 33.4);
    NavigationInstruction arrive = new NavigationInstruction(
        "arrive", NavigationInstruction.Maneuver.ARRIVE, "arrive",
        0.00080, 0.0, 89.0, 0.0);
    RouteLeg leg = new RouteLeg(
        origin,
        destination,
        List.of(turn, arrive),
        List.of(new GeoPoint(0.0, 0.0), new GeoPoint(0.0010, 0.0)));
    Route route = new Route("route", "test", "ref", List.of(origin, destination), List.of(leg));

    NavigationEngine engine = new NavigationEngine(25.0);
    NavigationState state = engine.update(
        route,
        NavigationState.ready(route),
        // About 5.6 m before the turn: old logic already discarded the turn because it was <25 m.
        new LocationSnapshot(0.00045, 0.0, 4.0, 1L, 5.0, 0.0, null));

    assertEquals(NavigationState.Status.NAVIGATING, state.status);
    assertSame(turn, state.instruction);
    assertEquals(0, state.instructionIndex);
  }

  @Test public void turnAdvancesOnlyAfterRiderPassesManeuverPoint() {
    Waypoint origin = waypoint("origin", 0.0, 0.0, Waypoint.Role.ORIGIN);
    Waypoint destination = waypoint("destination", 0.0010, 0.0, Waypoint.Role.DESTINATION);
    NavigationInstruction turn = new NavigationInstruction(
        "turn", NavigationInstruction.Maneuver.TURN_LEFT, "turn",
        0.00050, 0.0, 55.6, 33.4);
    NavigationInstruction arrive = new NavigationInstruction(
        "arrive", NavigationInstruction.Maneuver.ARRIVE, "arrive",
        0.00080, 0.0, 89.0, 0.0);
    RouteLeg leg = new RouteLeg(
        origin,
        destination,
        List.of(turn, arrive),
        List.of(new GeoPoint(0.0, 0.0), new GeoPoint(0.0010, 0.0)));
    Route route = new Route("route", "test", "ref", List.of(origin, destination), List.of(leg));

    NavigationEngine engine = new NavigationEngine(25.0);
    NavigationState state = engine.update(
        route,
        NavigationState.ready(route),
        // About 11 m beyond the maneuver, exceeding the 8 m pass tolerance.
        new LocationSnapshot(0.00060, 0.0, 4.0, 2L, 5.0, 0.0, null));

    assertEquals(NavigationState.Status.NAVIGATING, state.status);
    assertSame(arrive, state.instruction);
    assertEquals(1, state.instructionIndex);
  }

  @Test public void closeWaypointDoesNotSuppressTurnBeforeIt() {
    Waypoint origin = waypoint("origin", 0.0, 0.0, Waypoint.Role.ORIGIN);
    Waypoint stop = waypoint("stop", 0.00065, 0.0, Waypoint.Role.VIA);
    Waypoint destination = waypoint("destination", 0.0012, 0.0, Waypoint.Role.DESTINATION);
    NavigationInstruction turnNearStop = new NavigationInstruction(
        "turn-near-stop", NavigationInstruction.Maneuver.TURN_RIGHT, "turn",
        0.00050, 0.0, 55.6, 16.7);
    NavigationInstruction arriveStop = new NavigationInstruction(
        "arrive-stop", NavigationInstruction.Maneuver.ARRIVE, "stop",
        0.00065, 0.0, 72.3, 0.0);
    NavigationInstruction departStop = new NavigationInstruction(
        "depart-stop", NavigationInstruction.Maneuver.START, "depart",
        0.00065, 0.0, 0.0, 60.0);
    NavigationInstruction finalArrive = new NavigationInstruction(
        "final-arrive", NavigationInstruction.Maneuver.ARRIVE, "destination",
        0.0012, 0.0, 60.0, 0.0);
    Route route = new Route(
        "route", "test", "ref", List.of(origin, stop, destination),
        List.of(
            new RouteLeg(
                origin,
                stop,
                List.of(turnNearStop, arriveStop),
                List.of(new GeoPoint(0.0, 0.0), new GeoPoint(0.00065, 0.0))),
            new RouteLeg(
                stop,
                destination,
                List.of(departStop, finalArrive),
                List.of(new GeoPoint(0.00065, 0.0), new GeoPoint(0.0012, 0.0)))));

    NavigationEngine engine = new NavigationEngine(25.0);
    NavigationState approachingTurn = engine.update(
        route,
        NavigationState.ready(route),
        new LocationSnapshot(0.00046, 0.0, 4.0, 3L, 5.0, 0.0, null));

    assertSame(turnNearStop, approachingTurn.instruction);
    assertEquals(0, approachingTurn.legIndex);
    assertEquals(0, approachingTurn.instructionIndex);
  }

  private static Waypoint waypoint(String id, double lat, double lon, Waypoint.Role role) {
    return new Waypoint(id, id, lat, lon, role);
  }
}
