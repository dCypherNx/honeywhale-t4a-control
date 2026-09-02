package br.com.t4acontrol.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import br.com.t4acontrol.backend.navigation.NavigationInstruction;
import br.com.t4acontrol.backend.navigation.Route;
import br.com.t4acontrol.backend.navigation.RouteLeg;
import br.com.t4acontrol.backend.navigation.Waypoint;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class ValhallaRoutePlannerTest {
  @Test
  public void buildsMotorScooterRequestPreservingWaypointOrder() throws Exception {
    List<Waypoint> points = Arrays.asList(
        new Waypoint("o", "origin", -23.6377584, -46.658869, Waypoint.Role.ORIGIN),
        new Waypoint("v", "via", -23.6133508, -46.6884184, Waypoint.Role.VIA),
        new Waypoint("d", "destination", -23.5900000, -46.7000000, Waypoint.Role.DESTINATION));

    JSONObject request = ValhallaRoutePlanner.buildRequest(points);

    assertEquals("motor_scooter", request.getString("costing"));
    assertEquals("polyline6", request.getString("shape_format"));
    assertEquals("pt-BR", request.getJSONObject("directions_options").getString("language"));
    JSONArray locations = request.getJSONArray("locations");
    assertEquals(3, locations.length());
    assertEquals("break", locations.getJSONObject(0).getString("type"));
    assertEquals("through", locations.getJSONObject(1).getString("type"));
    assertEquals("break", locations.getJSONObject(2).getString("type"));
  }

  @Test
  public void anchorsManeuversToPolylineShapeIndexes() throws Exception {
    Waypoint origin = new Waypoint("o", "origin", -23.637758, -46.658869, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "destination", -23.613351, -46.688418, Waypoint.Role.DESTINATION);
    List<Waypoint> points = Arrays.asList(origin, destination);
    Route imported = new Route("route", "google-maps", "shared", points, new ArrayList<RouteLeg>());

    JSONObject leg = new JSONObject();
    leg.put("shape", "znval@hry~wA{ta@tvTq~Kb~b@");
    JSONArray maneuvers = new JSONArray();
    maneuvers.put(new JSONObject()
        .put("type", 1)
        .put("instruction", "Siga em frente")
        .put("begin_shape_index", 0));
    maneuvers.put(new JSONObject()
        .put("type", 10)
        .put("instruction", "Vire à direita")
        .put("begin_shape_index", 1));
    maneuvers.put(new JSONObject()
        .put("type", 4)
        .put("instruction", "Você chegou")
        .put("begin_shape_index", 2));
    leg.put("maneuvers", maneuvers);

    JSONObject response = new JSONObject()
        .put("trip", new JSONObject().put("legs", new JSONArray().put(leg)));

    Route planned = ValhallaRoutePlanner.parseResponse(imported, points, response);

    assertEquals("valhalla-osm", planned.source);
    assertEquals(1, planned.legs.size());
    List<NavigationInstruction> instructions = planned.legs.get(0).instructions;
    assertEquals(3, instructions.size());
    assertSame(NavigationInstruction.Maneuver.START, instructions.get(0).maneuver);
    assertSame(NavigationInstruction.Maneuver.TURN_RIGHT, instructions.get(1).maneuver);
    assertSame(NavigationInstruction.Maneuver.ARRIVE, instructions.get(2).maneuver);
    assertEquals(-23.620000, instructions.get(1).latitude, 0.000001);
    assertEquals(-46.670000, instructions.get(1).longitude, 0.000001);
  }

  @Test
  public void mapsValhallaTurnFamiliesToNeutralManeuvers() {
    assertSame(NavigationInstruction.Maneuver.TURN_LEFT,
        ValhallaRoutePlanner.mapManeuver(15));
    assertSame(NavigationInstruction.Maneuver.TURN_RIGHT,
        ValhallaRoutePlanner.mapManeuver(10));
    assertSame(NavigationInstruction.Maneuver.U_TURN,
        ValhallaRoutePlanner.mapManeuver(12));
    assertSame(NavigationInstruction.Maneuver.UNKNOWN,
        ValhallaRoutePlanner.mapManeuver(26));
  }
}
