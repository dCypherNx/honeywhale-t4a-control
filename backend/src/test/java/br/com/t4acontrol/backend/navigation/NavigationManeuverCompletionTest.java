package br.com.t4acontrol.backend.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class NavigationManeuverCompletionTest {
  @Test
  public void riderAlignedWithOutgoingStepAdvancesBeforeOffsetFallback() {
    Waypoint origin = new Waypoint("o", "o", 0.0, 0.0, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "d", 0.001, 0.001, Waypoint.Role.DESTINATION);
    List<GeoPoint> outgoing = List.of(new GeoPoint(0.001, 0.0), new GeoPoint(0.001, 0.001));
    NavigationStepMetadata metadata = new NavigationStepMetadata(
        NavigationStepMetadata.Context.TURN,
        NavigationStepMetadata.TurnSeverity.NORMAL,
        "turn",
        "right",
        0,
        90,
        -1,
        "",
        "",
        "",
        "",
        "",
        "driving",
        "",
        "",
        "right",
        10.0,
        10.0,
        outgoing,
        Collections.emptyList());
    NavigationInstruction turn = new NavigationInstruction(
        "turn",
        NavigationInstruction.Maneuver.TURN_RIGHT,
        "Vire à direita",
        0.001,
        0.0,
        111.2,
        111.2,
        metadata);
    NavigationInstruction arrive = new NavigationInstruction(
        "arrive",
        NavigationInstruction.Maneuver.ARRIVE,
        "Destino",
        destination.latitude,
        destination.longitude,
        222.4,
        0.0,
        null);
    RouteLeg leg = new RouteLeg(
        origin,
        destination,
        List.of(turn, arrive),
        List.of(new GeoPoint(0.0, 0.0), new GeoPoint(0.001, 0.0), new GeoPoint(0.001, 0.001)));
    Route route = new Route("r", "osrm", "ref", List.of(origin, destination), List.of(leg));

    LocationSnapshot justAfterTurn = new LocationSnapshot(
        0.001,
        0.00005,
        3.0,
        1L,
        20.0,
        90.0,
        null);

    NavigationState state = new NavigationEngine().update(route, NavigationState.ready(route), justAfterTurn);

    assertEquals(NavigationState.Status.NAVIGATING, state.status);
    assertEquals(1, state.instructionIndex);
    assertSame(arrive, state.instruction);
  }

  @Test
  public void wrongBearingDoesNotPrematurelyCompleteTurn() {
    Waypoint origin = new Waypoint("o", "o", 0.0, 0.0, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "d", 0.001, 0.001, Waypoint.Role.DESTINATION);
    NavigationStepMetadata metadata = new NavigationStepMetadata(
        NavigationStepMetadata.Context.TURN,
        NavigationStepMetadata.TurnSeverity.NORMAL,
        "turn",
        "right",
        0,
        90,
        -1,
        "",
        "",
        "",
        "",
        "",
        "driving",
        "",
        "",
        "right",
        10.0,
        10.0,
        List.of(new GeoPoint(0.001, 0.0), new GeoPoint(0.001, 0.001)),
        Collections.emptyList());
    NavigationInstruction turn = new NavigationInstruction(
        "turn", NavigationInstruction.Maneuver.TURN_RIGHT, "Vire à direita",
        0.001, 0.0, 111.2, 111.2, metadata);
    NavigationInstruction arrive = new NavigationInstruction(
        "arrive", NavigationInstruction.Maneuver.ARRIVE, "Destino",
        destination.latitude, destination.longitude, 222.4, 0.0, null);
    Route route = new Route(
        "r", "osrm", "ref", List.of(origin, destination),
        List.of(new RouteLeg(
            origin,
            destination,
            List.of(turn, arrive),
            List.of(new GeoPoint(0.0, 0.0), new GeoPoint(0.001, 0.0), new GeoPoint(0.001, 0.001)))));

    LocationSnapshot notAligned = new LocationSnapshot(0.001, 0.00005, 3.0, 1L, 20.0, 0.0, null);
    NavigationState state = new NavigationEngine().update(route, NavigationState.ready(route), notAligned);

    assertSame(turn, state.instruction);
    assertEquals(0, state.instructionIndex);
  }
}
