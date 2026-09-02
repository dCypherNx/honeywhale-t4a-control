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
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Google Routes API adapter. Keeps provider/network details outside the neutral backend. */
public final class GoogleRoutesRoutePlanner implements RoutePlanner {
  private static final String ENDPOINT =
      "https://routes.googleapis.com/directions/v2:computeRoutes";
  private static final String FIELD_MASK =
      "routes.legs.steps.endLocation,routes.legs.steps.navigationInstruction";
  private static final int CONNECT_TIMEOUT_MS = 8_000;
  private static final int READ_TIMEOUT_MS = 12_000;

  private final String apiKey;

  public GoogleRoutesRoutePlanner(String apiKey) {
    this.apiKey = apiKey == null ? "" : apiKey.trim();
  }

  @Override
  public Route plan(Route importedRoute) throws RoutePlanningException {
    if (importedRoute == null) {
      throw new RoutePlanningException("Imported route is required");
    }
    if (apiKey.isEmpty()) {
      throw new RoutePlanningException("Google Routes API key is not configured");
    }

    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
      connection.setRequestMethod("POST");
      connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
      connection.setReadTimeout(READ_TIMEOUT_MS);
      connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
      connection.setRequestProperty("X-Goog-Api-Key", apiKey);
      connection.setRequestProperty("X-Goog-FieldMask", FIELD_MASK);

      byte[] request = buildRequest(importedRoute).toString().getBytes(StandardCharsets.UTF_8);
      connection.setFixedLengthStreamingMode(request.length);
      try (OutputStream output = connection.getOutputStream()) {
        output.write(request);
      }

      int status = connection.getResponseCode();
      String body = readAll(status >= 200 && status < 300
          ? connection.getInputStream()
          : connection.getErrorStream());
      if (status < 200 || status >= 300) {
        throw new RoutePlanningException(
            "Google Routes API returned HTTP " + status + errorSuffix(body));
      }

      return parseResponse(importedRoute, new JSONObject(body));
    } catch (IOException | JSONException ex) {
      throw new RoutePlanningException("Unable to compute route with Google Routes API", ex);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private static JSONObject buildRequest(Route route) throws JSONException, RoutePlanningException {
    List<Waypoint> waypoints = route.waypoints;
    if (waypoints.size() < 2) {
      throw new RoutePlanningException("Route requires origin and destination");
    }

    JSONObject request = new JSONObject();
    request.put("origin", toGoogleWaypoint(waypoints.get(0)));
    request.put("destination", toGoogleWaypoint(waypoints.get(waypoints.size() - 1)));
    request.put("travelMode", "TWO_WHEELER");
    request.put("languageCode", "pt-BR");
    request.put("units", "METRIC");

    if (waypoints.size() > 2) {
      JSONArray intermediates = new JSONArray();
      for (int i = 1; i < waypoints.size() - 1; i++) {
        intermediates.put(toGoogleWaypoint(waypoints.get(i)));
      }
      request.put("intermediates", intermediates);
    }
    return request;
  }

  private static JSONObject toGoogleWaypoint(Waypoint waypoint)
      throws JSONException, RoutePlanningException {
    JSONObject result = new JSONObject();
    if (waypoint.hasCoordinates()) {
      JSONObject latLng = new JSONObject();
      latLng.put("latitude", waypoint.latitude);
      latLng.put("longitude", waypoint.longitude);
      JSONObject location = new JSONObject();
      location.put("latLng", latLng);
      result.put("location", location);
      return result;
    }

    if (waypoint.label != null && !waypoint.label.trim().isEmpty()) {
      result.put("address", waypoint.label.trim());
      return result;
    }

    throw new RoutePlanningException("Waypoint has neither coordinates nor address: " + waypoint.id);
  }

  private static Route parseResponse(Route importedRoute, JSONObject response)
      throws JSONException, RoutePlanningException {
    JSONArray routes = response.optJSONArray("routes");
    if (routes == null || routes.length() == 0) {
      throw new RoutePlanningException("Google Routes API returned no route");
    }

    JSONArray googleLegs = routes.getJSONObject(0).optJSONArray("legs");
    if (googleLegs == null || googleLegs.length() == 0) {
      throw new RoutePlanningException("Google Routes API returned no route legs");
    }

    List<RouteLeg> legs = new ArrayList<>();
    int expectedLegs = importedRoute.waypoints.size() - 1;
    if (googleLegs.length() != expectedLegs) {
      throw new RoutePlanningException(
          "Google Routes API returned " + googleLegs.length()
              + " legs; expected " + expectedLegs);
    }

    for (int legIndex = 0; legIndex < googleLegs.length(); legIndex++) {
      JSONObject googleLeg = googleLegs.getJSONObject(legIndex);
      JSONArray steps = googleLeg.optJSONArray("steps");
      List<NavigationInstruction> instructions = new ArrayList<>();

      if (steps != null) {
        for (int stepIndex = 0; stepIndex < steps.length(); stepIndex++) {
          JSONObject step = steps.getJSONObject(stepIndex);
          JSONObject instruction = step.optJSONObject("navigationInstruction");
          JSONObject endLocation = step.optJSONObject("endLocation");
          JSONObject latLng = endLocation == null ? null : endLocation.optJSONObject("latLng");
          if (instruction == null || latLng == null) {
            continue;
          }

          String providerManeuver = instruction.optString("maneuver", "MANEUVER_UNSPECIFIED");
          String text = instruction.optString("instructions", "");
          instructions.add(new NavigationInstruction(
              "google-" + legIndex + "-" + stepIndex,
              mapManeuver(providerManeuver),
              text,
              latLng.getDouble("latitude"),
              latLng.getDouble("longitude")));
        }
      }

      Waypoint from = importedRoute.waypoints.get(legIndex);
      Waypoint to = importedRoute.waypoints.get(legIndex + 1);
      legs.add(new RouteLeg(from, to, instructions));
    }

    return new Route(
        importedRoute.id,
        "google-routes",
        importedRoute.originalReference,
        importedRoute.waypoints,
        legs);
  }

  private static NavigationInstruction.Maneuver mapManeuver(String value) {
    String maneuver = value == null ? "" : value.toUpperCase();
    if (maneuver.contains("UTURN")) {
      return NavigationInstruction.Maneuver.U_TURN;
    }
    if (maneuver.contains("LEFT")) {
      return NavigationInstruction.Maneuver.TURN_LEFT;
    }
    if (maneuver.contains("RIGHT")) {
      return NavigationInstruction.Maneuver.TURN_RIGHT;
    }
    if (maneuver.equals("STRAIGHT") || maneuver.equals("NAME_CHANGE") || maneuver.equals("KEEP_STRAIGHT")) {
      return NavigationInstruction.Maneuver.STRAIGHT;
    }
    if (maneuver.equals("DEPART")) {
      return NavigationInstruction.Maneuver.START;
    }
    if (maneuver.equals("DESTINATION") || maneuver.equals("DESTINATION_LEFT")
        || maneuver.equals("DESTINATION_RIGHT")) {
      return NavigationInstruction.Maneuver.ARRIVE;
    }
    return NavigationInstruction.Maneuver.UNKNOWN;
  }

  private static String readAll(InputStream input) throws IOException {
    if (input == null) {
      return "";
    }
    StringBuilder result = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        result.append(line);
      }
    }
    return result.toString();
  }

  private static String errorSuffix(String body) {
    if (body == null || body.trim().isEmpty()) {
      return "";
    }
    try {
      JSONObject error = new JSONObject(body).optJSONObject("error");
      if (error != null) {
        String message = error.optString("message", "");
        if (!message.isEmpty()) {
          return ": " + message;
        }
      }
    } catch (JSONException ignored) {
      // Keep HTTP status as the stable error signal.
    }
    return "";
  }
}
