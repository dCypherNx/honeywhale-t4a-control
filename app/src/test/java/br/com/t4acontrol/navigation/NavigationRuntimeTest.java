package br.com.t4acontrol.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import br.com.t4acontrol.backend.navigation.GeoPoint;
import br.com.t4acontrol.backend.navigation.NavigationInstruction;
import br.com.t4acontrol.backend.navigation.NavigationState;
import br.com.t4acontrol.backend.navigation.Route;
import br.com.t4acontrol.backend.navigation.RouteLeg;
import br.com.t4acontrol.backend.navigation.RoutingProfile;
import br.com.t4acontrol.backend.navigation.Waypoint;
import java.util.Arrays;
import org.junit.After;
import org.junit.Test;

public final class NavigationRuntimeTest {
  @After public void clearRuntime() {
    NavigationRuntime.get().clear();
  }

  @Test public void activeRouteAdvancesFromExistingLocationStream() {
    Route route = route();
    NavigationRuntime runtime = NavigationRuntime.get();
    runtime.setRoute(route);
    runtime.onLocation(location(-23.5500, -46.6300, 1L));

    assertSame(route, runtime.route());
    assertEquals(NavigationState.Status.NAVIGATING, runtime.state().status);
    assertSame(route.legs.get(0).instructions.get(0), runtime.state().instruction);
  }

  @Test public void pausedNavigationIgnoresLocationUntilExplicitResume() {
    Route route = route();
    NavigationRuntime runtime = NavigationRuntime.get();
    runtime.setRoute(route);
    runtime.onLocation(location(-23.5500, -46.6300, 1L));
    NavigationInstruction turn = runtime.state().instruction;

    runtime.pause();
    assertEquals(NavigationState.Status.PAUSED, runtime.state().status);
    assertSame(turn, runtime.state().instruction);

    runtime.onLocation(location(-23.5510, -46.6310, 2L));
    assertEquals(NavigationState.Status.PAUSED, runtime.state().status);
    assertSame(turn, runtime.state().instruction);

    runtime.resume();
    assertEquals(NavigationState.Status.NAVIGATING, runtime.state().status);
    assertSame(turn, runtime.state().instruction);
  }

  private static Route route() {
    Waypoint origin = new Waypoint("o", "origin", -23.5500, -46.6300, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "destination", -23.5510, -46.6310, Waypoint.Role.DESTINATION);
    NavigationInstruction turn = new NavigationInstruction(
        "turn", NavigationInstruction.Maneuver.TURN_RIGHT, "turn", -23.5505, -46.6305, 75.0, 50.0);
    NavigationInstruction arrive = new NavigationInstruction(
        "arrive", NavigationInstruction.Maneuver.ARRIVE, "arrive", -23.5510, -46.6310, 150.0, 0.0);
    return new Route(
        "route", "test", "ref", RoutingProfile.BICYCLE,
        Arrays.asList(origin, destination),
        Arrays.asList(new RouteLeg(
            origin,
            destination,
            Arrays.asList(turn, arrive),
            Arrays.asList(
                new GeoPoint(-23.5500, -46.6300),
                new GeoPoint(-23.5505, -46.6305),
                new GeoPoint(-23.5510, -46.6310)))));
  }

  private static LocationSnapshot location(double latitude, double longitude, long timestamp) {
    return new LocationSnapshot(latitude, longitude, 5.0, timestamp, 5.0, 90.0, null);
  }
}
