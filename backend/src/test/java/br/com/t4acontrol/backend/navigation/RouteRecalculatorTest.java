package br.com.t4acontrol.backend.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;
import org.junit.Test;

public final class RouteRecalculatorTest {
  @Test public void recalculationStartsAtCurrentPositionAndPreservesRemainingWaypoints() throws Exception {
    Waypoint origin = point("o", -23.60, -46.60, Waypoint.Role.ORIGIN);
    Waypoint via1 = point("v1", -23.61, -46.61, Waypoint.Role.VIA);
    Waypoint via2 = point("v2", -23.62, -46.62, Waypoint.Role.VIA);
    Waypoint destination = point("d", -23.63, -46.63, Waypoint.Role.DESTINATION);
    Route route = new Route(
        "r",
        "osrm",
        "https://maps.app.goo.gl/example",
        RoutingProfile.BICYCLE,
        List.of(origin, via1, via2, destination),
        List.of(
            new RouteLeg(origin, via1, List.of()),
            new RouteLeg(via1, via2, List.of()),
            new RouteLeg(via2, destination, List.of())));
    NavigationState offRoute = NavigationState.of(
        NavigationState.Status.OFF_ROUTE, route, 1, 0, null, null);
    LocationSnapshot location = new LocationSnapshot(-23.615, -46.605, 5.0, 1L, null, null, null);
    CapturingPlanner planner = new CapturingPlanner();

    Route result = new RouteRecalculator().recalculate(route, offRoute, location, planner);

    assertSame(planner.result, result);
    assertEquals(3, planner.request.waypoints.size());
    Waypoint newOrigin = planner.request.waypoints.get(0);
    assertEquals(Waypoint.Role.ORIGIN, newOrigin.role);
    assertEquals(location.latitude, newOrigin.latitude, 0.0);
    assertEquals(location.longitude, newOrigin.longitude, 0.0);
    assertEquals("v2", planner.request.waypoints.get(1).id);
    assertEquals(Waypoint.Role.VIA, planner.request.waypoints.get(1).role);
    assertEquals("d", planner.request.waypoints.get(2).id);
    assertEquals(Waypoint.Role.DESTINATION, planner.request.waypoints.get(2).role);
    assertEquals(RoutingProfile.BICYCLE, planner.request.routingProfile);
  }

  @Test(expected = IllegalArgumentException.class)
  public void recalculationRejectsNonOffRouteState() throws Exception {
    Waypoint origin = point("o", -23.60, -46.60, Waypoint.Role.ORIGIN);
    Waypoint destination = point("d", -23.63, -46.63, Waypoint.Role.DESTINATION);
    Route route = new Route("r", "test", "ref", List.of(origin, destination), List.of(new RouteLeg(origin, destination, List.of())));
    new RouteRecalculator().recalculate(
        route,
        NavigationState.ready(route),
        new LocationSnapshot(-23.61, -46.61, 5.0, 1L, null, null, null),
        new CapturingPlanner());
  }

  private static Waypoint point(String id, double lat, double lon, Waypoint.Role role) {
    return new Waypoint(id, id, lat, lon, role);
  }

  private static final class CapturingPlanner implements RoutePlanner {
    Route request;
    Route result;

    @Override public Route plan(Route importedRoute) {
      request = importedRoute;
      result = importedRoute;
      return result;
    }
  }
}
