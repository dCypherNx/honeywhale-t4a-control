package br.com.t4acontrol.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import br.com.t4acontrol.backend.navigation.NavigationInstruction;
import br.com.t4acontrol.backend.navigation.Route;
import br.com.t4acontrol.backend.navigation.RoutingProfile;
import br.com.t4acontrol.backend.navigation.Waypoint;
import java.util.Arrays;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public final class OsrmRoutePlannerTest {
  private final List<Waypoint> points = Arrays.asList(
      new Waypoint("o", "origin", -23.6377584, -46.658869, Waypoint.Role.ORIGIN),
      new Waypoint("v", "via", -23.6133508, -46.6884184, Waypoint.Role.VIA),
      new Waypoint("d", "destination", -23.5900000, -46.7000000, Waypoint.Role.DESTINATION));

  @Test public void selectsBikeGraph() throws Exception {
    assertTrue(OsrmRoutePlanner.buildRouteUrl("https://routing.openstreetmap.de", points, RoutingProfile.BICYCLE).contains("/routed-bike/route/v1/driving/"));
  }
  @Test public void selectsFootGraph() throws Exception {
    assertTrue(OsrmRoutePlanner.buildRouteUrl("https://routing.openstreetmap.de", points, RoutingProfile.FOOT).contains("/routed-foot/route/v1/driving/"));
  }
  @Test public void selectsCarGraph() throws Exception {
    assertTrue(OsrmRoutePlanner.buildRouteUrl("https://routing.openstreetmap.de", points, RoutingProfile.CAR).contains("/routed-car/route/v1/driving/"));
  }
  @Test public void preservesWaypointOrder() throws Exception {
    String url = OsrmRoutePlanner.buildRouteUrl("https://routing.openstreetmap.de", points, RoutingProfile.BICYCLE);
    assertTrue(url.indexOf("-46.658869,-23.6377584") < url.indexOf("-46.6884184,-23.6133508"));
    assertTrue(url.indexOf("-46.6884184,-23.6133508") < url.indexOf("-46.7,-23.59"));
    assertEquals("routed-bike", OsrmRoutePlanner.graph(RoutingProfile.BICYCLE));
  }

  @Test public void preservesStepDistanceOffsetAndGeometry() throws Exception {
    List<Waypoint> twoPoints = points.subList(0, 2);
    Route imported = new Route("r", "google-maps", "ref", RoutingProfile.BICYCLE, twoPoints, java.util.Collections.emptyList());
    JSONObject response = new JSONObject("{\"code\":\"Ok\",\"routes\":[{\"legs\":[{\"steps\":["
        + "{\"distance\":120.5,\"name\":\"Rua A\",\"maneuver\":{\"type\":\"depart\",\"modifier\":\"straight\",\"location\":[-46.658869,-23.6377584]},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[-46.658869,-23.6377584],[-46.6595,-23.6370]]}},"
        + "{\"distance\":80.0,\"name\":\"Rua B\",\"maneuver\":{\"type\":\"turn\",\"modifier\":\"right\",\"location\":[-46.6595,-23.6370]},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[-46.6595,-23.6370],[-46.6600,-23.6365]]}}"
        + "]}]}]}");
    Route planned = OsrmRoutePlanner.parseResponse(imported, RoutingProfile.BICYCLE, twoPoints, response);
    assertEquals(2, planned.legs.get(0).instructions.size());
    assertEquals(120.5, planned.legs.get(0).instructions.get(0).segmentDistanceMeters, 0.001);
    assertEquals(120.5, planned.legs.get(0).instructions.get(1).routeOffsetMeters, 0.001);
    assertEquals(3, planned.legs.get(0).geometry.size());
  }

  @Test public void intermediateArriveBecomesWaypointAndFinalArriveRemainsDestination() throws Exception {
    Route imported = new Route("r", "google-maps", "ref", RoutingProfile.BICYCLE, points, java.util.Collections.emptyList());
    JSONObject response = new JSONObject("{\"code\":\"Ok\",\"routes\":[{\"legs\":["
        + "{\"steps\":[{\"distance\":10,\"name\":\"Parada A\",\"maneuver\":{\"type\":\"arrive\",\"modifier\":\"straight\",\"location\":[-46.6884184,-23.6133508]},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[-46.658869,-23.6377584],[-46.6884184,-23.6133508]]}}]},"
        + "{\"steps\":[{\"distance\":10,\"name\":\"Destino\",\"maneuver\":{\"type\":\"arrive\",\"modifier\":\"straight\",\"location\":[-46.7,-23.59]},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[-46.6884184,-23.6133508],[-46.7,-23.59]]}}]}"
        + "]}]}");

    Route planned = OsrmRoutePlanner.parseResponse(imported, RoutingProfile.BICYCLE, points, response);

    assertEquals(NavigationInstruction.Maneuver.WAYPOINT, planned.legs.get(0).instructions.get(0).maneuver);
    assertEquals(NavigationInstruction.Maneuver.ARRIVE, planned.legs.get(1).instructions.get(0).maneuver);
  }
}
