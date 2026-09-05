package br.com.t4acontrol.backend.navigation;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.Arrays;
import org.junit.Test;

public final class RouteProgressTrackerTest {
  @Test public void forwardProgressRemainsConsistentAndLargeRegressionDoesNot() {
    Route route = route();
    NavigationState state = NavigationState.ready(route);
    RouteProgressTracker tracker = new RouteProgressTracker();

    RouteProgressSnapshot first = tracker.observe(route, state, fix(0.0010, 1_000L, 3.0));
    RouteProgressSnapshot forward = tracker.observe(route, state, fix(0.0020, 2_000L, 3.0));
    RouteProgressSnapshot backward = tracker.observe(route, state, fix(0.0005, 3_000L, 3.0));

    assertTrue(first.available());
    assertTrue(first.progressConsistent);
    assertTrue(forward.progressConsistent);
    assertFalse(backward.progressConsistent);
  }

  private static Route route() {
    Waypoint origin = new Waypoint("o", "origin", 0.0, 0.0, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "destination", 0.0, 0.01, Waypoint.Role.DESTINATION);
    NavigationInstruction turn = new NavigationInstruction(
        "turn", NavigationInstruction.Maneuver.TURN_RIGHT, "turn", 0.0, 0.005, 500.0, 500.0);
    RouteLeg leg = new RouteLeg(
        origin,
        destination,
        Arrays.asList(turn),
        Arrays.asList(new GeoPoint(0.0, 0.0), new GeoPoint(0.0, 0.01)));
    return new Route(
        "route", "test", "ref", RoutingProfile.BICYCLE,
        Arrays.asList(origin, destination), Arrays.asList(leg));
  }

  private static LocationSnapshot fix(double longitude, long timestamp, double accuracy) {
    return new LocationSnapshot(0.0, longitude, accuracy, timestamp, 20.0, 90.0, null);
  }
}
