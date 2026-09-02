package br.com.t4acontrol.navigation;

import br.com.t4acontrol.backend.navigation.NavigationInstruction;
import br.com.t4acontrol.backend.navigation.Route;
import br.com.t4acontrol.backend.navigation.RouteLeg;
import br.com.t4acontrol.backend.navigation.RoutePlanner;
import br.com.t4acontrol.backend.navigation.Waypoint;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Keyless OSM/Valhalla planner. Public endpoints are defaults and remain replaceable. */
public final class ValhallaRoutePlanner implements RoutePlanner {
  static final String DEFAULT_VALHALLA_ENDPOINT = "https://valhalla1.openstreetmap.de/route";
  static final String DEFAULT_NOMINATIM_ENDPOINT = "https://nominatim.openstreetmap.org/search";
  private static final String USER_AGENT = "RideDash-T4A/1.0";
  private static final String CLIENT_ID = "br.com.t4acontrol";
  private static final int CONNECT_TIMEOUT_MS = 8_000;
  private static final int READ_TIMEOUT_MS = 12_000;

  private final String valhallaEndpoint;
  private final String nominatimEndpoint;

  public ValhallaRoutePlanner() {
    this(DEFAULT_VALHALLA_ENDPOINT, DEFAULT_NOMINATIM_ENDPOINT);
  }

  ValhallaRoutePlanner(String valhallaEndpoint, String nominatimEndpoint) {
    this.valhallaEndpoint = valhallaEndpoint;
    this.nominatimEndpoint = nominatimEndpoint;
  }

  @Override
  public Route plan(Route importedRoute) throws RoutePlanningException {
    if (importedRoute == null) {
      throw new RoutePlanningException("Imported route is required");
    }

    try {
      List<Waypoint> resolved = resolveWaypoints(importedRoute.waypoints);
      JSONObject request = buildRequest(resolved);
      JSONObject response = postJson(valhallaEndpoint, request);
      return parseResponse(importedRoute, resolved, response);
    } catch (IOException | JSONException ex) {
      throw new RoutePlanningException("Unable to compute route with Valhalla", ex);
    }
  }

  private List<Waypoint> resolveWaypoints(List<Waypoint> waypoints)
      throws IOException, JSONException, RoutePlanningException {
    List<Waypoint> result = new ArrayList<>();
    boolean geocoded = false;
    for (Waypoint waypoint : waypoints) {
      if (waypoint.hasCoordinates()) {
        result.add(waypoint);
      } else {
        if (geocoded) {
          try {
            Thread.sleep(1_100L);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RoutePlanningException("Interrupted while respecting Nominatim rate limit", ex);
          }
        }
        result.add(resolveAddress(waypoint));
        geocoded = true;
      }
    }
    return result;
  }

  private Waypoint resolveAddress(Waypoint waypoint)
      throws IOException, JSONException, RoutePlanningException {
    if (waypoint.label == null || waypoint.label.trim().isEmpty()) {
      throw new RoutePlanningException("Waypoint has neither coordinates nor address: " + waypoint.id);
    }

    String url = nominatimEndpoint
        + "?format=jsonv2&limit=1&countrycodes=br&q="n        + URLEncoder.encode(waypoint.label.trim(), StandardCharsets.UTF_8.name());
    HttpURLConnection connection = open(url, "GET");
    try {
      int status = connection.getResponseCode();
      String body = readAll(status >= 200 && status < 300
          ? connection.getInputStream() : connection.getErrorStream());
      if (status < 200 || status >= 300) {
        throw new RoutePlanningException("Nominatim returned HTTP " + status);
      }
      JSONArray matches = new JSONArray(body);
      if (matches.length() == 0) {
        throw new RoutePlanningException("Address not found: " + waypoint.label);
      }
      JSONObject first = matches.getJSONObject(0);
      return new Waypoint(
          waypoint.id,
          waypoint.label,
          Double.parseDouble(first.getString("lat")),
          Double.parseDouble(first.getString("lon")),
          waypoint.role);
    } finally {
      connection.disconnect();
    }
  }

  static JSONObject buildRequest(List<Waypoint> waypoints)
      throws JSONException, RoutePlanningException {
    if (waypoints.size() < 2) {
      throw new RoutePlanningException("Route requires origin and destination");
    }

    JSONArray locations = new JSONArray();
    for (int i = 0; i < waypoints.size(); i++) {
      Waypoint waypoint = waypoints.get(i);
      if (!waypoint.hasCoordinates()) {
        throw new RoutePlanningException("Waypoint coordinates are unresolved: " + waypoint.id);
      }
      JSONObject location = new JSONObject();
      location.put("lat", waypoint.latitude);
      location.put("lon", waypoint.longitude);
      location.put("type", (i == 0 || i == waypoints.size() - 1) ? "break" : "through");
      locations.put(location);
    }

    JSONObject directions = new JSONObject();
    directions.put("language", "pt-BR");
    directions.put("units", "kilometers");

    JSONObject request = new JSONObject();
    request.put("locations", locations);
    request.put("costing", "motor_scooter");
    request.put("shape_format", "polyline6");
    request.put("directions_options", directions);
    return request;
  }

  static Route parseResponse(Route importedRoute, List<Waypoint> resolvedWaypoints, JSONObject response)
      throws JSONException, RoutePlanningException {
    JSONObject trip = response.optJSONObject("trip");
    JSONArray legsJson = trip == null ? null : trip.optJSONArray("legs");
    if (legsJson == null || legsJson.length() == 0) {
      throw new RoutePlanningException("Valhalla returned no route legs");
    }

    int expectedLegs = resolvedWaypoints.size() - 1;
    if (legsJson.length() != expectedLegs) {
      throw new RoutePlanningException(
          "Valhalla returned " + legsJson.length() + " legs; expected " + expectedLegs);
    }

    List<RouteLeg> legs = new ArrayList<>();
    for (int legIndex = 0; legIndex < legsJson.length(); legIndex++) {
      JSONObject legJson = legsJson.getJSONObject(legIndex);
      List<double[]> shape = decodePolyline6(legJson.getString("shape"));
      JSONArray maneuvers = legJson.optJSONArray("maneuvers");
      List<NavigationInstruction> instructions = new ArrayList<>();

      if (maneuvers != null) {
        for (int maneuverIndex = 0; maneuverIndex < maneuvers.length(); maneuverIndex++) {
          JSONObject maneuver = maneuvers.getJSONObject(maneuverIndex);
          int shapeIndex = maneuver.optInt("begin_shape_index", -1);
          if (shapeIndex < 0 || shapeIndex >= shape.size()) {
            continue;
          }
          double[] point = shape.get(shapeIndex);
          instructions.add(new NavigationInstruction(
              "valhalla-" + legIndex + "-" + maneuverIndex,
              mapManeuver(maneuver.optInt("type", 0)),
              maneuver.optString("instruction", ""),
              point[0],
              point[1]));
        }
      }

      legs.add(new RouteLeg(
          resolvedWaypoints.get(legIndex),
          resolvedWaypoints.get(legIndex + 1),
          instructions));
    }

    return new Route(
        importedRoute.id,
        "valhalla-osm",
        importedRoute.originalReference,
        resolvedWaypoints,
        legs);
  }

  static NavigationInstruction.Maneuver mapManeuver(int type) {
    if (type >= 1 && type <= 3) return NavigationInstruction.Maneuver.START;
    if (type >= 4 && type <= 6) return NavigationInstruction.Maneuver.ARRIVE;
    if (type == 12 || type == 13) return NavigationInstruction.Maneuver.U_TURN;
    if ((type >= 9 && type <= 11) || type == 18 || type == 20 || type == 23) {
      return NavigationInstruction.Maneuver.TURN_RIGHT;
    }
    if ((type >= 14 && type <= 16) || type == 19 || type == 21 || type == 24) {
      return NavigationInstruction.Maneuver.TURN_LEFT;
    }
    if (type == 7 || type == 8 || type == 17 || type == 22 || type == 25) {
      return NavigationInstruction.Maneuver.STRAIGHT;
    }
    return NavigationInstruction.Maneuver.UNKNOWN;
  }

  static List<double[]> decodePolyline6(String encoded) throws RoutePlanningException {
    if (encoded == null || encoded.isEmpty()) {
      throw new RoutePlanningException("Valhalla leg has no route shape");
    }
    List<double[]> points = new ArrayList<>();
    int index = 0;
    long latitude = 0;
    long longitude = 0;
    while (index < encoded.length()) {
      DecodeResult lat = decodeValue(encoded, index);
      index = lat.nextIndex;
      DecodeResult lon = decodeValue(encoded, index);
      index = lon.nextIndex;
      latitude += lat.delta;
      longitude += lon.delta;
      points.add(new double[] {latitude / 1_000_000d, longitude / 1_000_000d});
    }
    return points;
  }

  private static DecodeResult decodeValue(String encoded, int start) throws RoutePlanningException {
    long result = 0;
    int shift = 0;
    int index = start;
    while (index < encoded.length()) {
      int value = encoded.charAt(index++) - 63;
      result |= (long) (value & 0x1f) << shift;
      if (value < 0x20) {
        long delta = (result & 1L) != 0 ? ~(result >> 1) : (result >> 1);
        return new DecodeResult(delta, index);
      }
      shift += 5;
      if (shift > 60) break;
    }
    throw new RoutePlanningException("Invalid Valhalla polyline6 shape");
  }

  private static final class DecodeResult {
    final long delta;
    final int nextIndex;

    DecodeResult(long delta, int nextIndex) {
      this.delta = delta;
      this.nextIndex = nextIndex;
    }
  }

  private JSONObject postJson(String endpoint, JSONObject request) throws IOException, JSONException,
      RoutePlanningException {
    HttpURLConnection connection = open(endpoint, "POST");
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
    byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
    connection.setFixedLengthStreamingMode(body.length);
    try {
      try (OutputStream output = connection.getOutputStream()) {
        output.write(body);
      }
      int status = connection.getResponseCode();
      String response = readAll(status >= 200 && status < 300
          ? connection.getInputStream() : connection.getErrorStream());
      if (status < 200 || status >= 300) {
        throw new RoutePlanningException("Valhalla returned HTTP " + status);
      }
      return new JSONObject(response);
    } finally {
      connection.disconnect();
    }
  }

  private static HttpURLConnection open(String url, String method) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
    connection.setRequestMethod(method);
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(READ_TIMEOUT_MS);
    connection.setRequestProperty("User-Agent", USER_AGENT);
    connection.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9");
    connection.setRequestProperty("X-Client-Id", CLIENT_ID);
    return connection;
  }

  private static String readAll(InputStream input) throws IOException {
    if (input == null) return "";
    StringBuilder result = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) result.append(line);
    }
    return result.toString();
  }
}
