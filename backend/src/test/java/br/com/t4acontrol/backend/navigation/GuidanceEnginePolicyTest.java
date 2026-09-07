package br.com.t4acontrol.backend.navigation;

import static org.junit.Assert.assertSame;

import java.util.Arrays;
import org.junit.Test;

public final class GuidanceEnginePolicyTest {
  @Test public void activeDirectionalInstructionIsNotHiddenBySpeedOrFixedHorizon() {
    Waypoint origin = new Waypoint("o", "origin", 0.0, 0.0, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "destination", 0.0, 0.01, Waypoint.Role.DESTINATION);
    NavigationInstruction turn = new NavigationInstruction(
        "turn", NavigationInstruction.Maneuver.TURN_RIGHT, "turn", 0.0, 0.005, 500.0, 500.0);
    NavigationInstruction arrive = new NavigationInstruction(
        "arrive", NavigationInstruction.Maneuver.ARRIVE, "arrive", 0.0, 0.01, 1000.0, 0.0);
    Route route = new Route(
        "route",
        "test",
        "ref",
        RoutingProfile.BICYCLE,
        Arrays.asList(origin, destination),
        Arrays.asList(new RouteLeg(
            origin,
            destination,
            Arrays.asList(turn, arrive),
            Arrays.asList(new GeoPoint(0.0, 0.0), new GeoPoint(0.0, 0.01)))));
    NavigationState state = NavigationState.of(
        NavigationState.Status.NAVIGATING, route, 0, 0, turn, 500.0);

    GuidanceEngine.State slow = GuidanceEngine.present(route, state, true, 5.0, null);
    GuidanceEngine.State fast = GuidanceEngine.present(route, state, true, 45.0, null);

    assertSame(turn, slow.instruction);
    assertSame(turn, fast.instruction);
  }
}
