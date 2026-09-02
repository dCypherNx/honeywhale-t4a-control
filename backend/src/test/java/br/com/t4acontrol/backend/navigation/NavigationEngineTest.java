package br.com.t4acontrol.backend.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;
import org.junit.Test;

public class NavigationEngineTest {
  @Test public void routePreservesWaypointOrderAndRoles() {
    Waypoint origin = waypoint("origin", -23.55, -46.63, Waypoint.Role.ORIGIN);
    Waypoint via = waypoint("via", -23.56, -46.64, Waypoint.Role.VIA);
    Waypoint destination = waypoint("destination", -23.57, -46.65, Waypoint.Role.DESTINATION);

    Route route = new Route("route-1", "test", "reference", List.of(origin, via, destination), List.of());

    assertSame(origin, route.waypoints.get(0));
    assertSame(via, route.waypoints.get(1));
    assertSame(destination, route.waypoints.get(2));
    assertEquals(Waypoint.Role.VIA, route.waypoints.get(1).role);
  }

  @Test public void currentInstructionIsDerivedFromLocationSnapshot() {
    Waypoint origin = waypoint("origin", -23.5500, -46.6300, Waypoint.Role.ORIGIN);
    Waypoint destination = waypoint("destination", -23.5510, -46.6310, Waypoint.Role.DESTINATION);
    NavigationInstruction turn = new NavigationInstruction(
        "turn-1",
        NavigationInstruction.Maneuver.TURN_RIGHT,
        "Vire à direita",
        -23.5505,
        -46.6305);
    NavigationInstruction arrive = new NavigationInstruction(
        "arrive",
        NavigationInstruction.Maneuver.ARRIVE,
        "Destino",
        destination.latitude,
        destination.longitude);
    RouteLeg leg = new RouteLeg(origin, destination, List.of(turn, arrive));
    Route route = new Route("route-1", "test", "reference", List.of(origin, destination), List.of(leg));

    NavigationEngine engine = new NavigationEngine(20.0);
    LocationSnapshot location = new LocationSnapshot(
        -23.5500, -46.6300, 5.0, 1L, 10.0, 90.0, null);

    NavigationState state = engine.update(route, NavigationState.ready(route), location);

    assertEquals(NavigationState.Status.NAVIGATING, state.status);
    assertSame(turn, state.instruction);
    assertTrue(state.distanceToInstructionMeters > 20.0);
  }

  @Test public void reachingInstructionAdvancesWithoutProviderKnowledge() {
    Waypoint origin = waypoint("origin", -23.5500, -46.6300, Waypoint.Role.ORIGIN);
    Waypoint destination = waypoint("destination", -23.5510, -46.6310, Waypoint.Role.DESTINATION);
    NavigationInstruction turn = new NavigationInstruction(
        "turn-1",
        NavigationInstruction.Maneuver.TURN_LEFT,
        "Vire à esquerda",
        -23.5505,
        -46.6305);
    NavigationInstruction arrive = new NavigationInstruction(
        "arrive",
        NavigationInstruction.Maneuver.ARRIVE,
        "Destino",
        destination.latitude,
        destination.longitude);
    Route route = new Route(
        "route-1",
        "test",
        "reference",
        List.of(origin, destination),
        List.of(new RouteLeg(origin, destination, List.of(turn, arrive))));

    NavigationEngine engine = new NavigationEngine(25.0);
    LocationSnapshot atTurn = new LocationSnapshot(
        turn.latitude, turn.longitude, 4.0, 2L, 8.0, 180.0, null);

    NavigationState state = engine.update(route, NavigationState.ready(route), atTurn);

    assertEquals(NavigationState.Status.NAVIGATING, state.status);
    assertSame(arrive, state.instruction);
    assertEquals(1, state.instructionIndex);
  }

  @Test public void intermediateArriveAdvancesToNextLegInsteadOfEndingLoopRoute() {
    Waypoint origin = waypoint("origin", -23.63766, -46.65890, Waypoint.Role.ORIGIN);
    Waypoint stopA = waypoint("a", -23.63766, -46.65963, Waypoint.Role.VIA);
    Waypoint stopB = waypoint("b", -23.63653, -46.65796, Waypoint.Role.VIA);
    Waypoint destination = waypoint("destination", -23.63765, -46.65887, Waypoint.Role.DESTINATION);

    NavigationInstruction arriveA = new NavigationInstruction(
        "arrive-a", NavigationInstruction.Maneuver.ARRIVE, "A", stopA.latitude, stopA.longitude);
    NavigationInstruction departA = new NavigationInstruction(
        "depart-a", NavigationInstruction.Maneuver.START, "Siga para B", stopA.latitude, stopA.longitude);
    NavigationInstruction arriveB = new NavigationInstruction(
        "arrive-b", NavigationInstruction.Maneuver.ARRIVE, "B", stopB.latitude, stopB.longitude);
    NavigationInstruction departB = new NavigationInstruction(
        "depart-b", NavigationInstruction.Maneuver.START, "Siga para destino", stopB.latitude, stopB.longitude);
    NavigationInstruction finalArrive = new NavigationInstruction(
        "arrive-final", NavigationInstruction.Maneuver.ARRIVE, "Destino", destination.latitude, destination.longitude);

    Route route = new Route(
        "loop", "test", "reference", List.of(origin, stopA, stopB, destination),
        List.of(
            new RouteLeg(origin, stopA, List.of(arriveA)),
            new RouteLeg(stopA, stopB, List.of(departA, arriveB)),
            new RouteLeg(stopB, destination, List.of(departB, finalArrive))));

    NavigationEngine engine = new NavigationEngine(25.0);
    NavigationState atA = engine.update(
        route,
        NavigationState.ready(route),
        new LocationSnapshot(stopA.latitude, stopA.longitude, 4.0, 3L, 0.0, null, null));

    assertEquals(NavigationState.Status.WAYPOINT_REACHED, atA.status);
    assertEquals(1, atA.legIndex);
    assertEquals(0, atA.instructionIndex);
    assertSame(departA, atA.instruction);
  }

  @Test public void onlyArriveOnLastLegEndsRoute() {
    Waypoint origin = waypoint("origin", -23.63766, -46.65890, Waypoint.Role.ORIGIN);
    Waypoint via = waypoint("via", -23.63653, -46.65796, Waypoint.Role.VIA);
    Waypoint destination = waypoint("destination", -23.63765, -46.65887, Waypoint.Role.DESTINATION);
    NavigationInstruction arriveVia = new NavigationInstruction(
        "arrive-via", NavigationInstruction.Maneuver.ARRIVE, "Via", via.latitude, via.longitude);
    NavigationInstruction finalArrive = new NavigationInstruction(
        "arrive-final", NavigationInstruction.Maneuver.ARRIVE, "Destino", destination.latitude, destination.longitude);
    Route route = new Route(
        "loop", "test", "reference", List.of(origin, via, destination),
        List.of(
            new RouteLeg(origin, via, List.of(arriveVia)),
            new RouteLeg(via, destination, List.of(finalArrive))));

    NavigationEngine engine = new NavigationEngine(25.0);
    NavigationState afterVia = engine.update(
        route,
        NavigationState.ready(route),
        new LocationSnapshot(via.latitude, via.longitude, 4.0, 4L, 0.0, null, null));
    NavigationState finalState = engine.update(
        route,
        afterVia,
        new LocationSnapshot(destination.latitude, destination.longitude, 4.0, 5L, 0.0, null, null));

    assertEquals(NavigationState.Status.ARRIVED, finalState.status);
    assertEquals(1, finalState.legIndex);
    assertSame(finalArrive, finalState.instruction);
  }

  @Test public void missingLocationIsExplicitState() {
    Waypoint origin = waypoint("origin", -23.55, -46.63, Waypoint.Role.ORIGIN);
    Waypoint destination = waypoint("destination", -23.56, -46.64, Waypoint.Role.DESTINATION);
    Route route = new Route(
        "route-1", "test", "reference", List.of(origin, destination),
        List.of(new RouteLeg(origin, destination, List.of())));

    NavigationState state = new NavigationEngine().update(route, NavigationState.ready(route), null);

    assertEquals(NavigationState.Status.POSITION_UNAVAILABLE, state.status);
  }

  @Test public void offRouteThresholdIncludesBoundedAccuracyMargin() {
    assertEquals(40.0, NavigationEngine.effectiveOffRouteThreshold(35.0, 5.0), 0.001);
    assertEquals(60.0, NavigationEngine.effectiveOffRouteThreshold(35.0, 25.0), 0.001);
    assertEquals(60.0, NavigationEngine.effectiveOffRouteThreshold(35.0, 0.0), 0.001);
    assertEquals(60.0, NavigationEngine.effectiveOffRouteThreshold(35.0, 80.0), 0.001);
  }

  private static Waypoint waypoint(String id, double lat, double lon, Waypoint.Role role) {
    return new Waypoint(id, id, lat, lon, role);
  }
}
