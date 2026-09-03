package br.com.t4acontrol.backend.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class NavigationCadencePolicyTest {
  private final NavigationCadencePolicy policy = new NavigationCadencePolicy();

  @Test public void longPredictableStretchCanRelaxToTwentySeconds() {
    Route route = route(false);
    NavigationState state = navigating(route, 500.0);

    NavigationCadencePolicy.Decision decision = policy.evaluate(route, state, 30.0);

    assertEquals(20_000L, decision.locationIntervalMs);
    assertEquals(20_000L, decision.processingIntervalMs);
    assertFalse(decision.highAccuracy);
  }

  @Test public void cadenceTightensContinuouslyAsManeuverApproaches() {
    Route route = route(false);
    NavigationCadencePolicy.Decision far = policy.evaluate(route, navigating(route, 300.0), 40.0);
    NavigationCadencePolicy.Decision near = policy.evaluate(route, navigating(route, 180.0), 40.0);
    NavigationCadencePolicy.Decision imminent = policy.evaluate(route, navigating(route, 110.0), 40.0);

    assertTrue(far.locationIntervalMs > near.locationIntervalMs);
    assertTrue(near.locationIntervalMs > imminent.locationIntervalMs);
    assertEquals(500L, imminent.locationIntervalMs);
    assertEquals(0L, imminent.processingIntervalMs);
    assertTrue(imminent.highAccuracy);
  }

  @Test public void nearerAlternativeBranchPreventsLongSleep() {
    Route route = route(true);
    NavigationCadencePolicy.Decision decision = policy.evaluate(route, navigating(route, 500.0), 30.0);

    assertTrue(decision.locationIntervalMs < 10_000L);
    assertTrue(decision.highAccuracy);
  }

  @Test public void unknownCorridorIsConservativelyCappedAtTwoSeconds() {
    Route route = routeWithoutMetadata();
    NavigationState state = NavigationState.of(
        NavigationState.Status.NAVIGATING,
        route,
        0,
        1,
        route.legs.get(0).instructions.get(1),
        500.0);

    NavigationCadencePolicy.Decision decision = policy.evaluate(route, state, 30.0);

    assertEquals(2_000L, decision.locationIntervalMs);
    assertTrue(decision.highAccuracy);
  }

  @Test public void confirmedOrSuspectedDeviationProcessesEveryUsefulFix() {
    Route route = route(false);
    NavigationInstruction instruction = route.legs.get(0).instructions.get(1);
    NavigationState offRoute = NavigationState.of(
        NavigationState.Status.OFF_ROUTE, route, 0, 1, instruction, 20.0);

    NavigationCadencePolicy.Decision decision = policy.evaluate(route, offRoute, 35.0);

    assertEquals(500L, decision.locationIntervalMs);
    assertEquals(0L, decision.processingIntervalMs);
    assertTrue(decision.highAccuracy);
    assertEquals(0.0, decision.minDistanceMeters, 0.0);
  }

  @Test public void pausedNavigationCreatesNoLocationDemand() {
    Route route = route(false);
    NavigationState paused = NavigationState.paused(route, NavigationState.ready(route));

    assertNull(policy.evaluate(route, paused, 30.0));
  }

  private static NavigationState navigating(Route route, double distance) {
    return NavigationState.of(
        NavigationState.Status.NAVIGATING,
        route,
        0,
        1,
        route.legs.get(0).instructions.get(1),
        distance);
  }

  private static Route route(boolean withAlternativeBranch) {
    Waypoint origin = new Waypoint("o", "origin", 0.0, 0.0, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "destination", 0.0, 0.009, Waypoint.Role.DESTINATION);

    NavigationStepMetadata.Intersection branch = new NavigationStepMetadata.Intersection(
        0.0,
        0.0009,
        Arrays.asList(270, 90, 0),
        Arrays.asList(false, true, true),
        0,
        1,
        Collections.emptyList(),
        Collections.emptyList());
    NavigationStepMetadata previousMetadata = new NavigationStepMetadata(
        NavigationStepMetadata.Context.DEPART,
        NavigationStepMetadata.TurnSeverity.STRAIGHT,
        "depart",
        "straight",
        90,
        90,
        0,
        "",
        "",
        "",
        "",
        "",
        "cycling",
        "",
        "",
        "right",
        60.0,
        60.0,
        Arrays.asList(new GeoPoint(0.0, 0.0), new GeoPoint(0.0, 0.0045)),
        withAlternativeBranch ? Arrays.asList(branch) : Collections.emptyList());

    NavigationInstruction previous = new NavigationInstruction(
        "start",
        NavigationInstruction.Maneuver.START,
        "start",
        0.0,
        0.0,
        0.0,
        500.0,
        previousMetadata);
    NavigationInstruction turn = new NavigationInstruction(
        "turn",
        NavigationInstruction.Maneuver.TURN_RIGHT,
        "turn",
        0.0,
        0.0045,
        500.0,
        500.0);

    RouteLeg leg = new RouteLeg(
        origin,
        destination,
        Arrays.asList(previous, turn),
        Arrays.asList(new GeoPoint(0.0, 0.0), new GeoPoint(0.0, 0.009)));
    return new Route(
        "route",
        "test",
        "ref",
        RoutingProfile.BICYCLE,
        Arrays.asList(origin, destination),
        Arrays.asList(leg));
  }

  private static Route routeWithoutMetadata() {
    Waypoint origin = new Waypoint("o", "origin", 0.0, 0.0, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "destination", 0.0, 0.009, Waypoint.Role.DESTINATION);
    NavigationInstruction previous = new NavigationInstruction(
        "start", NavigationInstruction.Maneuver.START, "start", 0.0, 0.0, 0.0, 500.0);
    NavigationInstruction turn = new NavigationInstruction(
        "turn", NavigationInstruction.Maneuver.TURN_RIGHT, "turn", 0.0, 0.0045, 500.0, 500.0);
    RouteLeg leg = new RouteLeg(
        origin,
        destination,
        Arrays.asList(previous, turn),
        Arrays.asList(new GeoPoint(0.0, 0.0), new GeoPoint(0.0, 0.009)));
    return new Route(
        "route",
        "test",
        "ref",
        RoutingProfile.BICYCLE,
        Arrays.asList(origin, destination),
        Arrays.asList(leg));
  }
}
