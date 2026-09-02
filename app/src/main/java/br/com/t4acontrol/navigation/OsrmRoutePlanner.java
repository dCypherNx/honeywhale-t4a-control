package br.com.t4acontrol.navigation;

import br.com.t4acontrol.backend.navigation.GeoPoint;
import br.com.t4acontrol.backend.navigation.NavigationInstruction;
import br.com.t4acontrol.backend.navigation.NavigationStepMetadata;
import br.com.t4acontrol.backend.navigation.Route;
import br.com.t4acontrol.backend.navigation.RouteLeg;
import br.com.t4acontrol.backend.navigation.RouteLegMetadata;
import br.com.t4acontrol.backend.navigation.RouteMetadata;
import br.com.t4acontrol.backend.navigation.RoutePlanner;
import br.com.t4acontrol.backend.navigation.RoutingProfile;
import br.com.t4acontrol.backend.navigation.Waypoint;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Keyless OSRM planner using profile-specific routing.openstreetmap.de graphs. */
public final class OsrmRoutePlanner implements RoutePlanner {
  static final String DEFAULT_ROUTING_BASE = "https://routing.openstreetmap.de";
  static final String DEFAULT_NOMINATIM_ENDPOINT = "https://nominatim.openstreetmap.org/search";
  private static final String USER_AGENT = "RideDash-T4A/1.0";
  private static final int CONNECT_TIMEOUT_MS = 8_000;
  private static final int READ_TIMEOUT_MS = 12_000;

  private final String routingBase;
  private final String nominatimEndpoint;
  private final RoutingProfile overrideProfile;

  public OsrmRoutePlanner() { this(DEFAULT_ROUTING_BASE, DEFAULT_NOMINATIM_ENDPOINT, null); }
  public OsrmRoutePlanner(RoutingProfile overrideProfile) { this(DEFAULT_ROUTING_BASE, DEFAULT_NOMINATIM_ENDPOINT, overrideProfile); }
  OsrmRoutePlanner(String routingBase, String nominatimEndpoint, RoutingProfile overrideProfile) {
    this.routingBase = routingBase; this.nominatimEndpoint = nominatimEndpoint; this.overrideProfile = overrideProfile;
  }

  @Override public Route plan(Route importedRoute) throws RoutePlanningException {
    if (importedRoute == null) throw new RoutePlanningException("Imported route is required");
    RoutingProfile selectedProfile = overrideProfile != null ? overrideProfile
        : importedRoute.routingProfile != null ? importedRoute.routingProfile : RoutingProfile.BICYCLE;
    try {
      List<Waypoint> points = resolveWaypoints(importedRoute.waypoints);
      return parseResponse(importedRoute, selectedProfile, points,
          getJson(buildRouteUrl(routingBase, points, selectedProfile)));
    } catch (IOException | JSONException ex) { throw new RoutePlanningException("Unable to compute route with OSRM", ex); }
  }

  private List<Waypoint> resolveWaypoints(List<Waypoint> waypoints) throws IOException, JSONException, RoutePlanningException {
    List<Waypoint> resolved = new ArrayList<>(); boolean geocoded = false;
    for (Waypoint waypoint : waypoints) {
      if (waypoint.hasCoordinates()) { resolved.add(waypoint); continue; }
      if (geocoded) try { Thread.sleep(1_100L); } catch (InterruptedException ex) {
        Thread.currentThread().interrupt(); throw new RoutePlanningException("Interrupted while respecting Nominatim rate limit", ex);
      }
      resolved.add(resolveAddress(waypoint)); geocoded = true;
    }
    return resolved;
  }

  private Waypoint resolveAddress(Waypoint waypoint) throws IOException, JSONException, RoutePlanningException {
    if (waypoint.label == null || waypoint.label.trim().isEmpty()) throw new RoutePlanningException("Waypoint has neither coordinates nor address: " + waypoint.id);
    String url = nominatimEndpoint + "?format=jsonv2&limit=1&countrycodes=br&q=" + URLEncoder.encode(waypoint.label.trim(), StandardCharsets.UTF_8.name());
    JSONObject match = firstNominatimResult(url, waypoint.label);
    return new Waypoint(waypoint.id, waypoint.label, Double.parseDouble(match.getString("lat")), Double.parseDouble(match.getString("lon")), waypoint.role);
  }

  private JSONObject firstNominatimResult(String url, String label) throws IOException, JSONException, RoutePlanningException {
    HttpURLConnection connection = open(url);
    try {
      int status = connection.getResponseCode(); String body = readAll(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
      if (status < 200 || status >= 300) throw new RoutePlanningException("Nominatim returned HTTP " + status);
      JSONArray matches = new JSONArray(body); if (matches.length() == 0) throw new RoutePlanningException("Address not found: " + label);
      return matches.getJSONObject(0);
    } finally { connection.disconnect(); }
  }

  static String buildRouteUrl(String routingBase, List<Waypoint> waypoints, RoutingProfile profile) throws RoutePlanningException {
    if (waypoints == null || waypoints.size() < 2) throw new RoutePlanningException("Route requires origin and destination");
    StringBuilder coordinates = new StringBuilder();
    for (Waypoint waypoint : waypoints) {
      if (!waypoint.hasCoordinates()) throw new RoutePlanningException("Waypoint coordinates are unresolved: " + waypoint.id);
      if (coordinates.length() > 0) coordinates.append(';');
      coordinates.append(waypoint.longitude).append(',').append(waypoint.latitude);
    }
    return routingBase + "/" + graph(profile) + "/route/v1/driving/" + coordinates
        + "?steps=true&annotations=true&overview=full&geometries=geojson";
  }

  static String graph(RoutingProfile profile) throws RoutePlanningException {
    if (profile == RoutingProfile.BICYCLE) return "routed-bike";
    if (profile == RoutingProfile.FOOT) return "routed-foot";
    if (profile == RoutingProfile.CAR) return "routed-car";
    throw new RoutePlanningException("Unsupported routing profile: " + profile);
  }

  static Route parseResponse(Route importedRoute, RoutingProfile selectedProfile, List<Waypoint> waypoints, JSONObject response) throws JSONException, RoutePlanningException {
    if (!"Ok".equals(response.optString("code"))) throw new RoutePlanningException("OSRM returned " + response.optString("code", "unknown error"));
    JSONArray routes = response.optJSONArray("routes");
    if (routes == null || routes.length() == 0) throw new RoutePlanningException("OSRM returned no route");
    JSONObject routeJson = routes.getJSONObject(0);
    JSONArray legsJson = routeJson.optJSONArray("legs");
    if (legsJson == null || legsJson.length() != waypoints.size() - 1) throw new RoutePlanningException("OSRM returned an unexpected number of route legs");

    List<RouteLeg> legs = new ArrayList<>();
    for (int legIndex = 0; legIndex < legsJson.length(); legIndex++) {
      JSONObject legJson = legsJson.getJSONObject(legIndex);
      JSONArray steps = legJson.optJSONArray("steps");
      List<NavigationInstruction> instructions = new ArrayList<>();
      List<GeoPoint> geometry = new ArrayList<>();
      double routeOffset = 0.0;
      if (steps != null) for (int stepIndex = 0; stepIndex < steps.length(); stepIndex++) {
        JSONObject step = steps.getJSONObject(stepIndex);
        JSONObject maneuver = step.getJSONObject("maneuver");
        JSONArray location = maneuver.getJSONArray("location");
        double stepDistance = step.optDouble("distance", 0.0);
        NavigationInstruction.Maneuver mapped = mapManeuver(maneuver.optString("type"), maneuver.optString("modifier"));
        if (mapped == NavigationInstruction.Maneuver.ARRIVE && legIndex < legsJson.length() - 1) {
          mapped = NavigationInstruction.Maneuver.WAYPOINT;
        }
        instructions.add(new NavigationInstruction(
            "osrm-" + legIndex + "-" + stepIndex,
            mapped,
            instructionText(step, maneuver),
            location.getDouble(1),
            location.getDouble(0),
            routeOffset,
            stepDistance,
            parseStepMetadata(step, maneuver)));
        appendGeometry(geometry, step.optJSONObject("geometry"));
        routeOffset += stepDistance;
      }
      legs.add(new RouteLeg(
          waypoints.get(legIndex),
          waypoints.get(legIndex + 1),
          instructions,
          geometry,
          parseLegMetadata(legJson)));
    }
    return new Route(
        importedRoute.id,
        "osrm",
        importedRoute.originalReference,
        selectedProfile,
        waypoints,
        legs,
        parseRouteMetadata(response, routeJson));
  }

  private static NavigationStepMetadata parseStepMetadata(JSONObject step, JSONObject maneuver) throws JSONException {
    String type = maneuver.optString("type", "");
    String modifier = maneuver.optString("modifier", "");
    return new NavigationStepMetadata(
        mapContext(type),
        mapSeverity(modifier),
        type,
        modifier,
        maneuver.has("bearing_before") ? maneuver.optInt("bearing_before", -1) : -1,
        maneuver.has("bearing_after") ? maneuver.optInt("bearing_after", -1) : -1,
        maneuver.has("exit") ? maneuver.optInt("exit", -1) : -1,
        optionalString(step, "name"),
        optionalString(step, "ref"),
        optionalString(step, "pronunciation"),
        optionalString(step, "destinations"),
        optionalString(step, "exits"),
        optionalString(step, "mode"),
        optionalString(step, "rotary_name"),
        optionalString(step, "rotary_pronunciation"),
        optionalString(step, "driving_side"),
        step.has("duration") ? step.optDouble("duration", Double.NaN) : Double.NaN,
        step.has("weight") ? step.optDouble("weight", Double.NaN) : Double.NaN,
        parseIntersections(step.optJSONArray("intersections")));
  }

  private static List<NavigationStepMetadata.Intersection> parseIntersections(JSONArray array) throws JSONException {
    if (array == null) return Collections.emptyList();
    List<NavigationStepMetadata.Intersection> result = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject intersection = array.getJSONObject(i);
      JSONArray location = intersection.optJSONArray("location");
      double longitude = location != null && location.length() > 0 ? location.optDouble(0, Double.NaN) : Double.NaN;
      double latitude = location != null && location.length() > 1 ? location.optDouble(1, Double.NaN) : Double.NaN;
      result.add(new NavigationStepMetadata.Intersection(
          latitude,
          longitude,
          intList(intersection.optJSONArray("bearings")),
          booleanList(intersection.optJSONArray("entry")),
          intersection.has("in") ? intersection.optInt("in", -1) : -1,
          intersection.has("out") ? intersection.optInt("out", -1) : -1,
          stringList(intersection.optJSONArray("classes")),
          parseLanes(intersection.optJSONArray("lanes"))));
    }
    return result;
  }

  private static List<NavigationStepMetadata.Lane> parseLanes(JSONArray array) throws JSONException {
    if (array == null) return Collections.emptyList();
    List<NavigationStepMetadata.Lane> result = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject lane = array.getJSONObject(i);
      result.add(new NavigationStepMetadata.Lane(
          stringList(lane.optJSONArray("indications")),
          lane.optBoolean("valid", false),
          lane.optBoolean("active", false)));
    }
    return result;
  }

  private static RouteLegMetadata parseLegMetadata(JSONObject leg) throws JSONException {
    JSONObject annotation = leg.optJSONObject("annotation");
    JSONObject metadata = annotation != null ? annotation.optJSONObject("metadata") : null;
    return new RouteLegMetadata(
        leg.has("distance") ? leg.optDouble("distance", Double.NaN) : Double.NaN,
        leg.has("duration") ? leg.optDouble("duration", Double.NaN) : Double.NaN,
        leg.has("weight") ? leg.optDouble("weight", Double.NaN) : Double.NaN,
        optionalString(leg, "summary"),
        doubleList(annotation == null ? null : annotation.optJSONArray("distance")),
        doubleList(annotation == null ? null : annotation.optJSONArray("duration")),
        doubleList(annotation == null ? null : annotation.optJSONArray("weight")),
        doubleList(annotation == null ? null : annotation.optJSONArray("speed")),
        intList(annotation == null ? null : annotation.optJSONArray("datasources")),
        stringList(metadata == null ? null : metadata.optJSONArray("datasource_names")),
        longList(annotation == null ? null : annotation.optJSONArray("nodes")));
  }

  private static RouteMetadata parseRouteMetadata(JSONObject response, JSONObject route) {
    return new RouteMetadata(
        route.has("distance") ? route.optDouble("distance", Double.NaN) : Double.NaN,
        route.has("duration") ? route.optDouble("duration", Double.NaN) : Double.NaN,
        route.has("weight") ? route.optDouble("weight", Double.NaN) : Double.NaN,
        optionalString(route, "weight_name"),
        optionalString(response, "data_version"),
        parseSnappedWaypoints(response.optJSONArray("waypoints")));
  }

  private static List<RouteMetadata.SnappedWaypoint> parseSnappedWaypoints(JSONArray array) {
    if (array == null) return Collections.emptyList();
    List<RouteMetadata.SnappedWaypoint> result = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject waypoint = array.optJSONObject(i);
      if (waypoint == null) continue;
      JSONArray location = waypoint.optJSONArray("location");
      double longitude = location != null && location.length() > 0 ? location.optDouble(0, Double.NaN) : Double.NaN;
      double latitude = location != null && location.length() > 1 ? location.optDouble(1, Double.NaN) : Double.NaN;
      result.add(new RouteMetadata.SnappedWaypoint(
          optionalString(waypoint, "name"),
          latitude,
          longitude,
          waypoint.has("distance") ? waypoint.optDouble("distance", Double.NaN) : Double.NaN,
          optionalString(waypoint, "hint")));
    }
    return result;
  }

  static NavigationStepMetadata.Context mapContext(String type) {
    if ("turn".equals(type)) return NavigationStepMetadata.Context.TURN;
    if ("new name".equals(type)) return NavigationStepMetadata.Context.NEW_NAME;
    if ("depart".equals(type)) return NavigationStepMetadata.Context.DEPART;
    if ("arrive".equals(type)) return NavigationStepMetadata.Context.ARRIVE;
    if ("merge".equals(type)) return NavigationStepMetadata.Context.MERGE;
    if ("on ramp".equals(type) || "on_ramp".equals(type) || "ramp".equals(type)) return NavigationStepMetadata.Context.ON_RAMP;
    if ("off ramp".equals(type) || "off_ramp".equals(type)) return NavigationStepMetadata.Context.OFF_RAMP;
    if ("fork".equals(type)) return NavigationStepMetadata.Context.FORK;
    if ("end of road".equals(type) || "end_of_road".equals(type)) return NavigationStepMetadata.Context.END_OF_ROAD;
    if ("continue".equals(type)) return NavigationStepMetadata.Context.CONTINUE;
    if ("roundabout".equals(type)) return NavigationStepMetadata.Context.ROUNDABOUT;
    if ("rotary".equals(type)) return NavigationStepMetadata.Context.ROTARY;
    if ("roundabout turn".equals(type) || "roundabout_turn".equals(type)) return NavigationStepMetadata.Context.ROUNDABOUT_TURN;
    if ("notification".equals(type)) return NavigationStepMetadata.Context.NOTIFICATION;
    if ("exit roundabout".equals(type) || "exit_roundabout".equals(type)) return NavigationStepMetadata.Context.EXIT_ROUNDABOUT;
    if ("exit rotary".equals(type) || "exit_rotary".equals(type)) return NavigationStepMetadata.Context.EXIT_ROTARY;
    return NavigationStepMetadata.Context.UNKNOWN;
  }

  static NavigationStepMetadata.TurnSeverity mapSeverity(String modifier) {
    if ("uturn".equals(modifier)) return NavigationStepMetadata.TurnSeverity.U_TURN;
    if ("sharp left".equals(modifier) || "sharp right".equals(modifier)) return NavigationStepMetadata.TurnSeverity.SHARP;
    if ("slight left".equals(modifier) || "slight right".equals(modifier)) return NavigationStepMetadata.TurnSeverity.SLIGHT;
    if ("left".equals(modifier) || "right".equals(modifier)) return NavigationStepMetadata.TurnSeverity.NORMAL;
    if ("straight".equals(modifier)) return NavigationStepMetadata.TurnSeverity.STRAIGHT;
    return NavigationStepMetadata.TurnSeverity.UNKNOWN;
  }

  private static void appendGeometry(List<GeoPoint> target, JSONObject geometry) throws JSONException {
    if (geometry == null) return;
    JSONArray coordinates = geometry.optJSONArray("coordinates"); if (coordinates == null) return;
    for (int i = 0; i < coordinates.length(); i++) {
      JSONArray coordinate = coordinates.getJSONArray(i); GeoPoint point = new GeoPoint(coordinate.getDouble(1), coordinate.getDouble(0));
      if (!target.isEmpty()) { GeoPoint last = target.get(target.size() - 1); if (last.latitude == point.latitude && last.longitude == point.longitude) continue; }
      target.add(point);
    }
  }

  static NavigationInstruction.Maneuver mapManeuver(String type, String modifier) {
    if ("depart".equals(type)) return NavigationInstruction.Maneuver.START;
    if ("arrive".equals(type)) return NavigationInstruction.Maneuver.ARRIVE;
    if ("roundabout".equals(type) || "rotary".equals(type)) return NavigationInstruction.Maneuver.ROUNDABOUT;
    if ("uturn".equals(modifier)) return NavigationInstruction.Maneuver.U_TURN;
    if (modifier != null && modifier.contains("left")) return NavigationInstruction.Maneuver.TURN_LEFT;
    if (modifier != null && modifier.contains("right")) return NavigationInstruction.Maneuver.TURN_RIGHT;
    if ("straight".equals(modifier) || "continue".equals(type) || "new name".equals(type) || "notification".equals(type)
        || "exit roundabout".equals(type) || "exit rotary".equals(type)) return NavigationInstruction.Maneuver.STRAIGHT;
    return NavigationInstruction.Maneuver.UNKNOWN;
  }

  private static String instructionText(JSONObject step, JSONObject maneuver) {
    String name = step.optString("name", ""), type = maneuver.optString("type", ""), modifier = maneuver.optString("modifier", "");
    StringBuilder text = new StringBuilder(type); if (!modifier.isEmpty()) text.append(' ').append(modifier); if (!name.isEmpty()) text.append(" — ").append(name); return text.toString();
  }

  private static String optionalString(JSONObject object, String key) {
    return object.has(key) && !object.isNull(key) ? object.optString(key, null) : null;
  }

  private static List<Integer> intList(JSONArray array) {
    if (array == null) return Collections.emptyList();
    List<Integer> result = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) result.add(array.optInt(i));
    return result;
  }

  private static List<Long> longList(JSONArray array) {
    if (array == null) return Collections.emptyList();
    List<Long> result = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) result.add(array.optLong(i));
    return result;
  }

  private static List<Double> doubleList(JSONArray array) {
    if (array == null) return Collections.emptyList();
    List<Double> result = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) result.add(array.optDouble(i, Double.NaN));
    return result;
  }

  private static List<Boolean> booleanList(JSONArray array) {
    if (array == null) return Collections.emptyList();
    List<Boolean> result = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) result.add(array.optBoolean(i, false));
    return result;
  }

  private static List<String> stringList(JSONArray array) {
    if (array == null) return Collections.emptyList();
    List<String> result = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) result.add(array.optString(i, ""));
    return result;
  }

  private JSONObject getJson(String url) throws IOException, JSONException, RoutePlanningException {
    HttpURLConnection connection = open(url);
    try { int status = connection.getResponseCode(); String body = readAll(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream()); if (status < 200 || status >= 300) throw new RoutePlanningException("OSRM returned HTTP " + status); return new JSONObject(body); }
    finally { connection.disconnect(); }
  }

  private static HttpURLConnection open(String url) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection(); connection.setRequestMethod("GET"); connection.setConnectTimeout(CONNECT_TIMEOUT_MS); connection.setReadTimeout(READ_TIMEOUT_MS); connection.setRequestProperty("User-Agent", USER_AGENT); connection.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9"); return connection;
  }

  private static String readAll(InputStream input) throws IOException {
    if (input == null) return ""; StringBuilder result = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) { String line; while ((line = reader.readLine()) != null) result.append(line); }
    return result.toString();
  }
}
