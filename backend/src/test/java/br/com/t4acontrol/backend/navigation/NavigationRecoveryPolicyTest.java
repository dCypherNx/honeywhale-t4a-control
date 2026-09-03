package br.com.t4acontrol.backend.navigation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;
import org.junit.Test;

public final class NavigationRecoveryPolicyTest {
  @Test public void firstGoodOffRouteFixDuringTurnIsEligibleForPrefetch() {
    Route route = route();
    NavigationInstruction turn = route.legs.get(0).instructions.get(0);
    NavigationState previous = NavigationState.of(
        NavigationState.Status.NAVIGATING, route, 0, 0, turn, 18.0);
    NavigationState observed = NavigationState.of(
        NavigationState.Status.OFF_ROUTE, route, 0, 0, turn, null);

    assertTrue(new NavigationRecoveryPolicy().shouldPrefetch(
        previous,
        observed,
        location(-23.5505, -46.6305, 5.0, 10_000L)));
  }

  @Test public void straightOrPoorAccuracyDoesNotSpendSpeculativeRequest() {
    Route route = route();
    NavigationInstruction straight = new NavigationInstruction(
        "straight", NavigationInstruction.Maneuver.STRAIGHT, "continue", -23.5505, -46.6305);
    NavigationState previous = NavigationState.of(
        NavigationState.Status.NAVIGATING, route, 0, 0, straight, 18.0);
    NavigationState observed = NavigationState.of(
        NavigationState.Status.OFF_ROUTE, route, 0, 0, straight, null);
    NavigationRecoveryPolicy policy = new NavigationRecoveryPolicy();

    assertFalse(policy.shouldPrefetch(
        previous,
        observed,
        location(-23.5505, -46.6305, 5.0, 10_000L)));

    NavigationInstruction turn = route.legs.get(0).instructions.get(0);
    previous = NavigationState.of(NavigationState.Status.NAVIGATING, route, 0, 0, turn, 18.0);
    observed = NavigationState.of(NavigationState.Status.OFF_ROUTE, route, 0, 0, turn, null);
    assertFalse(policy.shouldPrefetch(
        previous,
        observed,
        location(-23.5505, -46.6305, 40.0, 10_000L)));
  }

  @Test public void freshSameLegPrefetchCanBePromotedAfterConfirmation() {
    Route route = route();
    NavigationInstruction turn = route.legs.get(0).instructions.get(0);
    NavigationState confirmed = NavigationState.of(
        NavigationState.Status.OFF_ROUTE, route, 0, 0, turn, null);
    NavigationRecoveryPolicy policy = new NavigationRecoveryPolicy();
    LocationSnapshot origin = location(-23.5505, -46.6305, 5.0, 10_000L);
    LocationSnapshot current = location(-23.5510, -46.6310, 5.0, 16_000L);

    assertTrue(policy.canPromote(route.id, 0, 10_000L, origin, confirmed, current));
    assertFalse(policy.canPromote(route.id, 1, 10_000L, origin, confirmed, current));
    assertFalse(policy.canPromote(
        route.id,
        0,
        10_000L,
        origin,
        confirmed,
        location(-23.5510, -46.6310, 5.0, 31_000L)));
  }

  private static Route route() {
    Waypoint origin = new Waypoint("o", "origin", -23.5500, -46.6300, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "destination", -23.5520, -46.6320, Waypoint.Role.DESTINATION);
    NavigationInstruction turn = new NavigationInstruction(
        "turn", NavigationInstruction.Maneuver.TURN_RIGHT, "turn", -23.5505, -46.6305, 75.0, 50.0);
    NavigationInstruction arrive = new NavigationInstruction(
        "arrive", NavigationInstruction.Maneuver.ARRIVE, "arrive", destination.latitude, destination.longitude, 200.0, 0.0);
    RouteLeg leg = new RouteLeg(origin, destination, List.of(turn, arrive));
    return new Route("route", "test", "ref", List.of(origin, destination), List.of(leg));
  }

  private static LocationSnapshot location(
      double latitude,
      double longitude,
      double accuracy,
      long timestampMs) {
    return new LocationSnapshot(latitude, longitude, accuracy, timestampMs, 20.0, 90.0, null);
  }
}
