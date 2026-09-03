package br.com.t4acontrol.backend.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class NavigationCadencePolicyTest {
  private final NavigationCadencePolicy policy = new NavigationCadencePolicy();

  @Test public void longPredictableStretchCanRelaxToTwentySeconds() {
    NavigationState state = state(NavigationInstruction.Maneuver.TURN_RIGHT, 500.0);

    NavigationCadencePolicy.Decision decision = policy.evaluate(state, 30.0);

    assertEquals(20_000L, decision.locationIntervalMs);
    assertEquals(20_000L, decision.processingIntervalMs);
    assertFalse(decision.highAccuracy);
  }

  @Test public void cadenceTightensContinuouslyAsManeuverApproaches() {
    NavigationCadencePolicy.Decision far =
        policy.evaluate(state(NavigationInstruction.Maneuver.TURN_RIGHT, 300.0), 40.0);
    NavigationCadencePolicy.Decision near =
        policy.evaluate(state(NavigationInstruction.Maneuver.TURN_RIGHT, 180.0), 40.0);
    NavigationCadencePolicy.Decision imminent =
        policy.evaluate(state(NavigationInstruction.Maneuver.TURN_RIGHT, 110.0), 40.0);

    assertTrue(far.locationIntervalMs > near.locationIntervalMs);
    assertTrue(near.locationIntervalMs > imminent.locationIntervalMs);
    assertEquals(500L, imminent.locationIntervalMs);
    assertEquals(0L, imminent.processingIntervalMs);
    assertTrue(imminent.highAccuracy);
  }

  @Test public void confirmedOrSuspectedDeviationProcessesEveryUsefulFix() {
    Route route = route();
    NavigationInstruction instruction = route.legs.get(0).instructions.get(0);
    NavigationState offRoute = NavigationState.of(
        NavigationState.Status.OFF_ROUTE, route, 0, 0, instruction, 20.0);

    NavigationCadencePolicy.Decision decision = policy.evaluate(offRoute, 35.0);

    assertEquals(500L, decision.locationIntervalMs);
    assertEquals(0L, decision.processingIntervalMs);
    assertTrue(decision.highAccuracy);
    assertEquals(0.0, decision.minDistanceMeters, 0.0);
  }

  @Test public void pausedNavigationCreatesNoLocationDemand() {
    Route route = route();
    NavigationState paused = NavigationState.paused(route, NavigationState.ready(route));

    assertNull(policy.evaluate(paused, 30.0));
  }

  private static NavigationState state(NavigationInstruction.Maneuver maneuver, double distance) {
    Route route = route(maneuver);
    return NavigationState.of(
        NavigationState.Status.NAVIGATING,
        route,
        0,
        0,
        route.legs.get(0).instructions.get(0),
        distance);
  }

  private static Route route() {
    return route(NavigationInstruction.Maneuver.TURN_RIGHT);
  }

  private static Route route(NavigationInstruction.Maneuver maneuver) {
    Waypoint origin = new Waypoint("o", "origin", 0.0, 0.0, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "destination", 0.01, 0.01, Waypoint.Role.DESTINATION);
    NavigationInstruction instruction = new NavigationInstruction(
        "m", maneuver, "maneuver", 0.005, 0.005, 500.0, 500.0);
    return new Route(
        "route",
        "test",
        "ref",
        RoutingProfile.BICYCLE,
        Arrays.asList(origin, destination),
        Arrays.asList(new RouteLeg(origin, destination, Arrays.asList(instruction))));
  }
}
