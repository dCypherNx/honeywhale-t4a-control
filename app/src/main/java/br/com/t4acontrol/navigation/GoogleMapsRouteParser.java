package br.com.t4acontrol.navigation;

import br.com.t4acontrol.backend.navigation.NavigationInstruction;
import br.com.t4acontrol.backend.navigation.Route;
import br.com.t4acontrol.backend.navigation.RouteLeg;
import br.com.t4acontrol.backend.navigation.RouteParser;
import br.com.t4acontrol.backend.navigation.RoutingProfile;
import br.com.t4acontrol.backend.navigation.Waypoint;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Parses expanded Google Maps /maps/dir URLs into the neutral route model. */
public final class GoogleMapsRouteParser implements RouteParser {
  @Override
  public Route parse(String routeReference) throws RouteParseException {
    if (routeReference == null || routeReference.trim().isEmpty()) {
      throw new RouteParseException("Route reference is empty");
    }

    URI uri;
    try {
      uri = URI.create(routeReference.trim());
    } catch (IllegalArgumentException ex) {
      throw new RouteParseException("Invalid Google Maps route URL", ex);
    }

    String host = uri.getHost();
    if (!isGoogleMapsHost(host)) {
      throw new RouteParseException("Unsupported Google Maps host");
    }

    String rawPath = uri.getRawPath();
    int dirIndex = rawPath.indexOf("/maps/dir/");
    if (dirIndex < 0) {
      throw new RouteParseException("Google Maps route path was not found");
    }

    String routePath = rawPath.substring(dirIndex + "/maps/dir/".length());
    List<String> segments = new ArrayList<>();
    for (String raw : routePath.split("/")) {
      if (raw.isEmpty()) continue;
      String decoded = decode(raw);
      if (isMetadataSegment(decoded)) break;
      segments.add(decoded);
    }

    if (segments.size() < 2) {
      throw new RouteParseException("A Google Maps route requires at least origin and destination");
    }

    List<Waypoint> waypoints = new ArrayList<>();
    for (int index = 0; index < segments.size(); index++) {
      Waypoint.Role role = index == 0
          ? Waypoint.Role.ORIGIN
          : index == segments.size() - 1 ? Waypoint.Role.DESTINATION : Waypoint.Role.VIA;
      ParsedPoint point = parsePoint(segments.get(index));
      waypoints.add(new Waypoint(
          "google-" + index,
          point.label,
          point.latitude,
          point.longitude,
          role));
    }

    List<RouteLeg> legs = new ArrayList<>();
    for (int index = 0; index < waypoints.size() - 1; index++) {
      Waypoint from = waypoints.get(index);
      Waypoint to = waypoints.get(index + 1);
      legs.add(new RouteLeg(from, to, Collections.<NavigationInstruction>emptyList()));
    }

    String routeId = "google:" + Integer.toHexString(routeReference.hashCode());
    return new Route(
        routeId,
        "google-maps",
        routeReference.trim(),
        parseRoutingProfile(uri, routeReference),
        waypoints,
        legs);
  }

  static boolean isGoogleMapsHost(String host) {
    if (host == null) return false;
    String value = host.toLowerCase(Locale.ROOT);
    return value.equals("google.com")
        || value.endsWith(".google.com")
        || value.matches("(?:^|.*\\.)google\\.[a-z]{2,3}(?:\\.[a-z]{2})?");
  }

  static RoutingProfile parseRoutingProfile(URI uri, String routeReference) {
    String query = uri.getRawQuery();
    if (query != null) {
      for (String parameter : query.split("&")) {
        int separator = parameter.indexOf('=');
        String key = separator >= 0 ? parameter.substring(0, separator) : parameter;
        String value = separator >= 0 ? parameter.substring(separator + 1) : "";
        if ("travelmode".equalsIgnoreCase(decodeUnchecked(key))) {
          RoutingProfile explicit = fromTravelMode(decodeUnchecked(value));
          if (explicit != null) return explicit;
        }
      }
    }

    if (routeReference.contains("!3e0")) return RoutingProfile.CAR;
    if (routeReference.contains("!3e1")) return RoutingProfile.BICYCLE;
    if (routeReference.contains("!3e2")) return RoutingProfile.FOOT;
    return null;
  }

  private static boolean isMetadataSegment(String value) {
    if (value == null || value.isEmpty()) return false;
    String lower = value.toLowerCase(Locale.ROOT);
    return lower.startsWith("data=")
        || lower.startsWith("@")
        || lower.startsWith("dir_action=")
        || lower.startsWith("entry=");
  }

  private static RoutingProfile fromTravelMode(String travelMode) {
    if ("driving".equalsIgnoreCase(travelMode)) return RoutingProfile.CAR;
    if ("bicycling".equalsIgnoreCase(travelMode)) return RoutingProfile.BICYCLE;
    if ("walking".equalsIgnoreCase(travelMode)) return RoutingProfile.FOOT;
    return null;
  }

  private static ParsedPoint parsePoint(String value) {
    String[] parts = value.split(",");
    if (parts.length == 2) {
      try {
        return new ParsedPoint(
            value,
            Double.valueOf(parts[0].trim()),
            Double.valueOf(parts[1].trim()));
      } catch (NumberFormatException ignored) {
        // Fall through to label-only waypoint.
      }
    }
    return new ParsedPoint(value, null, null);
  }

  private static String decode(String value) throws RouteParseException {
    try {
      return URLDecoder.decode(value.replace("+", "%20"), StandardCharsets.UTF_8.name());
    } catch (Exception ex) {
      throw new RouteParseException("Unable to decode Google Maps route segment", ex);
    }
  }

  private static String decodeUnchecked(String value) {
    try {
      return URLDecoder.decode(value.replace("+", "%20"), StandardCharsets.UTF_8.name());
    } catch (Exception ignored) {
      return value;
    }
  }

  private static final class ParsedPoint {
    final String label;
    final Double latitude;
    final Double longitude;

    ParsedPoint(String label, Double latitude, Double longitude) {
      this.label = label;
      this.latitude = latitude;
      this.longitude = longitude;
    }
  }
}
