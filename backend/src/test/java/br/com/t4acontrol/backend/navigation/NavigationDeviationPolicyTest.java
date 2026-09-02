package br.com.t4acontrol.backend.navigation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;
import org.junit.Test;

public final class NavigationDeviationPolicyTest {
  @Test public void requiresThreeAccurateSamplesAcrossSixSeconds() {
    NavigationDeviationPolicy policy = new NavigationDeviationPolicy();
    NavigationState offRoute = offRouteState();

    assertFalse(policy.shouldRecalculate(offRoute, location(1_000L, 8.0)));
    assertFalse(policy.shouldRecalculate(offRoute, location(4_000L, 8.0)));
    assertTrue(policy.shouldRecalculate(offRoute, location(7_000L, 8.0)));
  }

  @Test public void poorAccuracyResetsEvidence() {
    NavigationDeviationPolicy policy = new NavigationDeviationPolicy();
    NavigationState offRoute = offRouteState();

    assertFalse(policy.shouldRecalculate(offRoute, location(1_000L, 8.0)));
    assertFalse(policy.shouldRecalculate(offRoute, location(4_000L, 40.0)));
    assertFalse(policy.shouldRecalculate(offRoute, location(7_000L, 8.0)));
    assertFalse(policy.shouldRecalculate(offRoute, location(10_000L, 8.0)));
    assertTrue(policy.shouldRecalculate(offRoute, location(13_000L, 8.0)));
  }

  @Test public void returningToRouteResetsEvidence() {
    NavigationDeviationPolicy policy = new NavigationDeviationPolicy();
    NavigationState offRoute = offRouteState();
    Route route = route();

    assertFalse(policy.shouldRecalculate(offRoute, location(1_000L, 5.0)));
    assertFalse(policy.shouldRecalculate(NavigationState.ready(route), location(4_000L, 5.0)));
    assertFalse(policy.shouldRecalculate(offRoute, location(7_000L, 5.0)));
    assertFalse(policy.shouldRecalculate(offRoute, location(10_000L, 5.0)));
    assertTrue(policy.shouldRecalculate(offRoute, location(13_000L, 5.0)));
  }

  @Test public void cooldownPreventsRepeatedNetworkReplanning() {
    NavigationDeviationPolicy policy = new NavigationDeviationPolicy(25.0, 1, 0L, 30_000L);
    NavigationState offRoute = offRouteState();

    assertTrue(policy.shouldRecalculate(offRoute, location(10_000L, 5.0)));
    assertFalse(policy.shouldRecalculate(offRoute, location(20_000L, 5.0)));
    assertTrue(policy.shouldRecalculate(offRoute, location(40_000L, 5.0)));
  }

  private static NavigationState offRouteState() {
    Route route = route();
    NavigationInstruction instruction = route.legs.get(0).instructions.get(0);
    return NavigationState.of(NavigationState.Status.OFF_ROUTE, route, 0, 0, instruction, null);
  }

  private static Route route() {
    Waypoint origin = new Waypoint("o", "origin", -23.55, -46.63, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "destination", -23.56, -46.64, Waypoint.Role.DESTINATION);
    NavigationInstruction instruction = new NavigationInstruction(
        "i", NavigationInstruction.Maneuver.STRAIGHT, "continue", -23.555, -46.635);
    return new Route("r", "test", "ref", List.of(origin, destination),
        List.of(new RouteLeg(origin, destination, List.of(instruction))));
  }

  private static LocationSnapshot location(long timestampMs, double accuracyMeters) {
    return new LocationSnapshot(-23.55, -46.63, accuracyMeters, timestampMs, null, null, null);
  }
}
