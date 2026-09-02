package br.com.t4acontrol.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import br.com.t4acontrol.backend.navigation.Route;
import br.com.t4acontrol.backend.navigation.RoutingProfile;
import br.com.t4acontrol.backend.navigation.Waypoint;
import org.junit.Test;

public class GoogleMapsRouteParserTest {
  private static final String REAL_SHARED_ROUTE =
      "https://www.google.com/maps/dir/-23.6377584,-46.658869/-23.6133508,-46.6884184/"
          + "Av.+Brig.+Faria+Lima,+4400+-+Itaim+Bibi,+S%C3%A3o+Paulo+-+SP,+04538-132,+Brasil/"
          + "@-23.6169638,-46.6743256,12z/data=!4m7!4m6!1m0!1m0!1m2!1m1!1s0x94ce574fd39e5283:0xc15a5a1d135bcabf!3e1"
          + "?utm_campaign=ml-ardl-mgrt2&g_ep=Eg1tbF8yMDI2MDgzMF8wIJvbDyoASAJQAQ%3D%3D";

  @Test public void parsesRealGoogleMapsRoutePreservingWaypointOrderAndMode() throws Exception {
    Route route = new GoogleMapsRouteParser().parse(REAL_SHARED_ROUTE);

    assertEquals("google-maps", route.source);
    assertEquals(RoutingProfile.BICYCLE, route.routingProfile);
    assertEquals(3, route.waypoints.size());
    assertEquals(2, route.legs.size());

    Waypoint origin = route.waypoints.get(0);
    assertEquals(Waypoint.Role.ORIGIN, origin.role);
    assertTrue(origin.hasCoordinates());
    assertEquals(-23.6377584, origin.latitude, 0.0000001);
    assertEquals(-46.658869, origin.longitude, 0.0000001);

    Waypoint via = route.waypoints.get(1);
    assertEquals(Waypoint.Role.VIA, via.role);
    assertTrue(via.hasCoordinates());
    assertEquals(-23.6133508, via.latitude, 0.0000001);
    assertEquals(-46.6884184, via.longitude, 0.0000001);

    Waypoint destination = route.waypoints.get(2);
    assertEquals(Waypoint.Role.DESTINATION, destination.role);
    assertFalse(destination.hasCoordinates());
    assertNull(destination.latitude);
    assertNull(destination.longitude);
    assertEquals(
        "Av. Brig. Faria Lima, 4400 - Itaim Bibi, São Paulo - SP, 04538-132, Brasil",
        destination.label);
  }

  @Test public void readsOfficialTravelModeParameter() throws Exception {
    Route car = new GoogleMapsRouteParser().parse(
        "https://www.google.com/maps/dir/A/B?api=1&travelmode=driving");
    Route foot = new GoogleMapsRouteParser().parse(
        "https://www.google.com/maps/dir/A/B?api=1&travelmode=walking");
    Route bicycle = new GoogleMapsRouteParser().parse(
        "https://www.google.com/maps/dir/A/B?api=1&travelmode=bicycling");

    assertEquals(RoutingProfile.CAR, car.routingProfile);
    assertEquals(RoutingProfile.FOOT, foot.routingProfile);
    assertEquals(RoutingProfile.BICYCLE, bicycle.routingProfile);
  }

  @Test public void acceptsBrazilianGoogleMapsHost() throws Exception {
    Route route = new GoogleMapsRouteParser().parse(
        "https://www.google.com.br/maps/dir/-23.6,-46.6/-23.7,-46.7/data=!4m2!3e1");

    assertEquals(RoutingProfile.BICYCLE, route.routingProfile);
    assertEquals(2, route.waypoints.size());
  }
}
