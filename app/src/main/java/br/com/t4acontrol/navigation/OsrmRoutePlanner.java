package br.com.t4acontrol.navigation;

import br.com.t4acontrol.backend.navigation.NavigationInstruction;
import br.com.t4acontrol.backend.navigation.Route;
import br.com.t4acontrol.backend.navigation.RouteLeg;
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
  private final RoutingProfile profile;

  public OsrmRoutePlanner(RoutingProfile profile) {
    this(DEFAULT_ROUTING_BASE, DEFAULT_NOMINATIM_ENDPOINT, profile);
  }

  OsrmRoutePlanner(String routingBase, String nominatimEndpoint, RoutingProfile profile) {
    this.routingBase = routingBase;
    this.nominatimEndpoint = nominatimEndpoint;
    this.profile = profile;
  }

  @Override
  public Route plan(Route importedRoute) throws RoutePlanningException {
    if (importedRoute == null) throw new RoutePlanningException("Imported route is required");
    if (profile == null) throw new RoutePlanningException("Routing profile is required");
    try {
      List<Waypoint> points = resolveWaypoints(importedRoute.waypoints);
      JSONObject response = getJson(buildRouteUrl(points, profile));
      return parseResponse(importedRoute, points, response);
    } catch (IOException | JSONException ex) {
      throw new RoutePlanningException("Unable to compute route with OSRM", ex);
    }
  }

  private List<Waypoint> resolveWaypoints(List<Waypoint> waypoints)
      throws IOException, JSONException, RoutePlanningException {
    List<Waypoint> resolved = new ArrayList<>();
    boolean geocoded = false;
    for (Waypoint waypoint : waypoints) {
      if (waypoint.hasCoordinates()) {
        resolved.add(waypoint);
        continue;
      }
      if (geocoded) {
        try {
          Thread.sleep(1_100L);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new RoutePlanningException("Interrupted while respecting Nominatim rate limit", ex);
        }
      }
      resolved.add(resolveAddress(waypoint));
      geocoded = true;
    }
    return resolved;
  }

  private Waypoint resolveAddress(Waypoint waypoint)
      throws IOException, JSONException, RoutePlanningException {
    if (waypoint.label == null || waypoint.label.trim().isEmpty()) {
      throw new RoutePlanningException("Waypoint has neither coordinates nor address: " + waypoint.id);
    }
    String url = nominatimEndpoint + "?format=jsonv2&limit=1&countrycodes=br&q="
        + URLEncoder.encode(waypoint.label.trim(), StandardCharsets.UTF_8.name());
    JSONObject match = firstNominatimResult(url, waypoint.label);
    return new Waypoint(
        waypoint.id,
        waypoint.label,
        Double.parseDouble(match.getString("lat")),
        Double.parseDouble(match.getString("lon")),
        waypoint.role);
  }

  private JSONObject firstNominatimResult(String url, String label)
      throws IOException, JSONException, RoutePlanningException {
    HttpURLConnection connection = open(url);
    try {
      int status = connection.getResponseCode();
      String body = readAll(status >= 200 && status < 300
          ? connection.getInputStream() : connection.getErrorStream());
      if (status < 200 || status >= 300) {
        throw new RoutePlanningException("Nominatim returned HTTP " + status);
      }
      JSONArray matches = new JSONArray(body);
      if (matches.length() == 0) throw new RoutePlanningException("Address not found: " + label);
      return matches.getJSONObject(0);
    } finally {
      connection.disconnect();
    }
  }

  static String buildRouteUrl(List<Waypoint> waypoints, RoutingProfile profile)
      throws RoutePlanningException {
    if (waypoints == null || waypoints.size() < 2) {
      throw new RoutePlanningException("Route requires origin and destination");
    }
    StringBuilder coordinates = new StringBuilder();
    for (Waypoint waypoint : waypoints) {
      if (!waypoint.hasCoordinates()) {
        throw new RoutePlanningException("Waypoint coordinates are unresolved: " + waypoint.id);
      }
      if (coordinates.length() > 0) coordinates.append(';');
      coordinates.append(waypoint.longitude).append(',').append(waypoint.latitude);
    }
    return DEFAULT_ROUTING_BASE + "/" + graph(profile)
        + "/route/v1/driving/" + coordinates
        + "?steps=true&overview=full&geometries=geojson";
  }

  private String buildRouteUrl(List<Waypoint> waypoints, RoutingProfile selected)
      throws RoutePlanningException {
    return buildRouteUrl(waypoints, selected).replace(DEFAULT_ROUTING_BASE, routingBase);
  }

  static String graph(RoutingProfile profile) throws RoutePlanningException {
    if (profile == RoutingProfile.BICYCLE) return "routed-bike";
    if (profile == RoutingProfile.FOOT) return "routed-foot";
    if (profile == RoutingProfile.CAR) return "routed-car";
    throw new RoutePlanningException("Unsupported routing profile: " + profile);
  }

  static Route parseResponse(Route importedRoute, List<Waypoint> waypoints, JSONObject response)
      throws JSONException, RoutePlanningException {
    if (!"Ok".equals(response.optString("code"))) {
      throw new RoutePlanningException("OSRM returned " + response.optString("code", "unknown error"));
    }
    JSONArray routes = response.optJSONArray("routes");
    if (routes == null || routes.length() == 0) throw new RoutePlanningException("OSRM returned no route");
    JSONArray legsJson = routes.getJSONObject(0).optJSONArray("legs");
    if (legsJson == null || legsJson.length() != waypoints.size() - 1) {
      throw new RoutePlanningException("OSRM returned an unexpected number of route legs");
    }

    List<RouteLeg> legs = new ArrayList<>();
    for (int legIndex = 0; legIndex < legsJson.length(); legIndex++) {
      JSONArray steps = legsJson.getJSONObject(legIndex).optJSONArray("steps");
      List<NavigationInstruction> instructions = new ArrayList<>();
      if (steps != null) {
        for (int stepIndex = 0; stepIndex < steps.length(); stepIndex++) {
          JSONObject step = steps.getJSONObject(stepIndex);
          JSONObject maneuver = step.getJSONObject("maneuver");
          JSONArray location = maneuver.getJSONArray("location");
          instructions.add(new NavigationInstruction(
              "osrm-" + legIndex + "-" + stepIndex,
              mapManeuver(maneuver.optString("type"), maneuver.optString("modifier")),
              instructionText(step, maneuver),
              location.getDouble(1),
              location.getDouble(0)));
        }
      }
      legs.add(new RouteLeg(waypoints.get(legIndex), waypoints.get(legIndex + 1), instructions));
    }

    return new Route(importedRoute.id, "osrm", importedRoute.originalReference, waypoints, legs);
  }

  static NavigationInstruction.Maneuver mapManeuver(String type, String modifier) {
    if ("depart".equals(type)) return NavigationInstruction.Maneuver.START;
    if ("arrive".equals(type)) return NavigationInstruction.Maneuver.ARRIVE;
    if ("roundabout".equals(type) || "rotary".equals(type)) return NavigationInstruction.Maneuver.ROUNDABOUT;
    if ("uturn".equals(modifier)) return NavigationInstruction.Maneuver.U_TURN;
    if (modifier != null && modifier.contains("left")) return NavigationInstruction.Maneuver.TURN_LEFT;
    if (modifier != null && modifier.contains("right")) return NavigationInstruction.Maneuver.TURN_RIGHT;
    if ("straight".equals(modifier) || "continue".equals(type) || "new name".equals(type)) {
      return NavigationInstruction.Maneuver.STRAIGHT;
    }
    return NavigationInstruction.Maneuver.UNKNOWN;
  }

  private static String instructionText(JSONObject step, JSONObject maneuver) {
    String name = step.optString("name", "");
    String type = maneuver.optString("type", "");
    String modifier = maneuver.optString("modifier", "");
    StringBuilder text = new StringBuilder(type);
    if (!modifier.isEmpty()) text.append(' ').append(modifier);
    if (!name.isEmpty()) text.append(" — ").append(name);
    return text.toString();
  }

  private JSONObject getJson(String url) throws IOException, JSONException, RoutePlanningException {
    HttpURLConnection connection = open(url);
    try {
      int status = connection.getResponseCode();
      String body = readAll(status >= 200 && status < 300
          ? connection.getInputStream() : connection.getErrorStream());
      if (status < 200 || status >= 300) throw new RoutePlanningException("OSRM returned HTTP " + status);
      return new JSONObject(body);
    } finally {
      connection.disconnect();
    }
  }

  private static HttpURLConnection open(String url) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setRequestMethod("GET");
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(READ_TIMEOUT_MS);
    connection.setRequestProperty("User-Agent", USER_AGENT);
    connection.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9");
    return connection;
  }

  private static String readAll(InputStream input) throws IOException {
    if (input == null) return "";
    StringBuilder result = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) result.append(line);
    }
    return result.toString();
  }
}
