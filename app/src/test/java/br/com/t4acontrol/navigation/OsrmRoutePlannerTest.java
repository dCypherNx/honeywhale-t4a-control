package br.com.t4acontrol.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import br.com.t4acontrol.backend.navigation.RoutingProfile;
import br.com.t4acontrol.backend.navigation.Waypoint;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class OsrmRoutePlannerTest {
  private final List<Waypoint> points = Arrays.asList(
      new Waypoint("o", "origin", -23.6377584, -46.658869, Waypoint.Role.ORIGIN),
      new Waypoint("v", "via", -23.6133508, -46.6884184, Waypoint.Role.VIA),
      new Waypoint("d", "destination", -23.5900000, -46.7000000, Waypoint.Role.DESTINATION));

  @Test public void selectsBikeGraph() throws Exception {
    String url = OsrmRoutePlanner.buildRouteUrl("https://routing.openstreetmap.de", points, RoutingProfile.BICYCLE);
    assertTrue(url.contains("/routed-bike/route/v1/driving/"));
  }

  @Test public void selectsFootGraph() throws Exception {
    String url = OsrmRoutePlanner.buildRouteUrl("https://routing.openstreetmap.de", points, RoutingProfile.FOOT);
    assertTrue(url.contains("/routed-foot/route/v1/driving/"));
  }

  @Test public void selectsCarGraph() throws Exception {
    String url = OsrmRoutePlanner.buildRouteUrl("https://routing.openstreetmap.de", points, RoutingProfile.CAR);
    assertTrue(url.contains("/routed-car/route/v1/driving/"));
  }

  @Test public void preservesWaypointOrder() throws Exception {
    String url = OsrmRoutePlanner.buildRouteUrl("https://routing.openstreetmap.de", points, RoutingProfile.BICYCLE);
    assertTrue(url.indexOf("-46.658869,-23.6377584") < url.indexOf("-46.6884184,-23.6133508"));
    assertTrue(url.indexOf("-46.6884184,-23.6133508") < url.indexOf("-46.7,-23.59"));
    assertEquals("routed-bike", OsrmRoutePlanner.graph(RoutingProfile.BICYCLE));
  }
}
