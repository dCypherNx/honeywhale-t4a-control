package br.com.t4acontrol.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import br.com.t4acontrol.backend.navigation.NavigationInstruction;
import br.com.t4acontrol.backend.navigation.NavigationStepMetadata;
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

  @Test public void selectsBikeGraphAndRichAnnotations() throws Exception {
    String url = OsrmRoutePlanner.buildRouteUrl("https://routing.openstreetmap.de", points, RoutingProfile.BICYCLE);
    assertTrue(url.contains("/routed-bike/route/v1/driving/"));
    assertTrue(url.contains("steps=true"));
    assertTrue(url.contains("annotations=true"));
    assertTrue(url.contains("overview=full"));
    assertTrue(url.contains("geometries=geojson"));
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

  @Test public void preservesManeuverTopologySeverityAndIntersectionDetail() throws Exception {
    List<Waypoint> twoPoints = points.subList(0, 2);
    Route imported = new Route("r", "google-maps", "ref", RoutingProfile.BICYCLE, twoPoints, java.util.Collections.emptyList());
    JSONObject response = new JSONObject("{\"code\":\"Ok\",\"routes\":[{\"legs\":[{"
        + "\"distance\":90,\"duration\":18,\"weight\":19,\"summary\":\"Rua A, Rua B\","
        + "\"annotation\":{\"distance\":[40,50],\"duration\":[8,10],\"weight\":[9,10],\"speed\":[5,5],\"datasources\":[0,0],\"nodes\":[101,102,103],\"metadata\":{\"datasource_names\":[\"lua profile\"]}},"
        + "\"steps\":[{\"distance\":90,\"duration\":18,\"weight\":19,\"name\":\"Rua B\",\"ref\":\"SP-1\",\"pronunciation\":\"rua be\",\"destinations\":\"Centro\",\"exits\":\"12\",\"mode\":\"cycling\",\"driving_side\":\"right\","
        + "\"maneuver\":{\"type\":\"fork\",\"modifier\":\"slight right\",\"bearing_before\":5,\"bearing_after\":35,\"location\":[-46.6595,-23.6370]},"
        + "\"intersections\":[{\"location\":[-46.6595,-23.6370],\"bearings\":[5,35,180],\"entry\":[false,true,true],\"in\":0,\"out\":1,\"classes\":[\"restricted\"],\"lanes\":[{\"indications\":[\"slight right\"],\"valid\":true}]}],"
        + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[-46.6595,-23.6370],[-46.6600,-23.6365]]}}]}]}]}" );

    Route planned = OsrmRoutePlanner.parseResponse(imported, RoutingProfile.BICYCLE, twoPoints, response);
    NavigationInstruction instruction = planned.legs.get(0).instructions.get(0);

    assertEquals(NavigationInstruction.Maneuver.TURN_RIGHT, instruction.maneuver);
    assertEquals(NavigationStepMetadata.Context.FORK, instruction.metadata.context);
    assertEquals(NavigationStepMetadata.TurnSeverity.SLIGHT, instruction.metadata.severity);
    assertEquals(5, instruction.metadata.bearingBefore);
    assertEquals(35, instruction.metadata.bearingAfter);
    assertEquals("Rua B", instruction.metadata.roadName);
    assertEquals("SP-1", instruction.metadata.roadRef);
    assertEquals("Centro", instruction.metadata.destinations);
    assertEquals(1, instruction.metadata.intersections.size());
    assertEquals("restricted", instruction.metadata.intersections.get(0).classes.get(0));
    assertEquals("slight right", instruction.metadata.intersections.get(0).lanes.get(0).indications.get(0));
    assertEquals(90.0, planned.legs.get(0).metadata.distanceMeters, 0.001);
    assertEquals(5.0, planned.legs.get(0).metadata.segmentSpeedsMetersPerSecond.get(0), 0.001);
    assertEquals(Long.valueOf(103), planned.legs.get(0).metadata.nodeIds.get(2));
  }

  @Test public void mapsAllGuidanceRelevantOSRMContextsAndSeverities() {
    assertEquals(NavigationStepMetadata.Context.MERGE, OsrmRoutePlanner.mapContext("merge"));
    assertEquals(NavigationStepMetadata.Context.ON_RAMP, OsrmRoutePlanner.mapContext("on ramp"));
    assertEquals(NavigationStepMetadata.Context.OFF_RAMP, OsrmRoutePlanner.mapContext("off ramp"));
    assertEquals(NavigationStepMetadata.Context.END_OF_ROAD, OsrmRoutePlanner.mapContext("end of road"));
    assertEquals(NavigationStepMetadata.Context.ROUNDABOUT_TURN, OsrmRoutePlanner.mapContext("roundabout turn"));
    assertEquals(NavigationStepMetadata.TurnSeverity.SHARP, OsrmRoutePlanner.mapSeverity("sharp left"));
    assertEquals(NavigationStepMetadata.TurnSeverity.NORMAL, OsrmRoutePlanner.mapSeverity("right"));
    assertEquals(NavigationStepMetadata.TurnSeverity.STRAIGHT, OsrmRoutePlanner.mapSeverity("straight"));
    assertEquals(NavigationStepMetadata.TurnSeverity.U_TURN, OsrmRoutePlanner.mapSeverity("uturn"));
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
