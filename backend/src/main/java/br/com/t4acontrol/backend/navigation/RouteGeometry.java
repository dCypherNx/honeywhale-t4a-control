package br.com.t4acontrol.backend.navigation;

import java.util.List;

/** Shared provider-neutral geometric operations for navigation. */
final class RouteGeometry {
  static final double EARTH_RADIUS_METERS = 6_371_000.0;

  static final class Projection {
    final double offsetMeters;
    final double lateralMeters;

    Projection(double offsetMeters, double lateralMeters) {
      this.offsetMeters = offsetMeters;
      this.lateralMeters = lateralMeters;
    }
  }

  private RouteGeometry() {}

  static Projection project(List<GeoPoint> geometry, double latitude, double longitude) {
    if (geometry == null || geometry.size() < 2) return null;
    double bestDistance = Double.POSITIVE_INFINITY;
    double bestOffset = Double.NaN;
    double cumulative = 0.0;
    double refLat = Math.toRadians(latitude);

    for (int index = 0; index < geometry.size() - 1; index++) {
      GeoPoint a = geometry.get(index);
      GeoPoint b = geometry.get(index + 1);
      double segment = distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude);
      double ax = Math.toRadians(a.longitude - longitude) * Math.cos(refLat) * EARTH_RADIUS_METERS;
      double ay = Math.toRadians(a.latitude - latitude) * EARTH_RADIUS_METERS;
      double bx = Math.toRadians(b.longitude - longitude) * Math.cos(refLat) * EARTH_RADIUS_METERS;
      double by = Math.toRadians(b.latitude - latitude) * EARTH_RADIUS_METERS;
      double dx = bx - ax;
      double dy = by - ay;
      double denominator = dx * dx + dy * dy;
      double t = denominator == 0.0
          ? 0.0
          : Math.max(0.0, Math.min(1.0, -(ax * dx + ay * dy) / denominator));
      double px = ax + t * dx;
      double py = ay + t * dy;
      double lateral = Math.sqrt(px * px + py * py);
      if (lateral < bestDistance) {
        bestDistance = lateral;
        bestOffset = cumulative + t * segment;
      }
      cumulative += segment;
    }

    return Double.isFinite(bestOffset) ? new Projection(bestOffset, bestDistance) : null;
  }

  static double projectedOffsetMeters(
      List<GeoPoint> geometry, double latitude, double longitude) {
    Projection projection = project(geometry, latitude, longitude);
    return projection == null ? Double.NaN : projection.offsetMeters;
  }

  static double lengthMeters(List<GeoPoint> geometry) {
    if (geometry == null || geometry.size() < 2) return Double.NaN;
    double total = 0.0;
    for (int index = 0; index < geometry.size() - 1; index++) {
      GeoPoint a = geometry.get(index);
      GeoPoint b = geometry.get(index + 1);
      total += distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude);
    }
    return total;
  }

  static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
    double phi1 = Math.toRadians(lat1);
    double phi2 = Math.toRadians(lat2);
    double deltaPhi = Math.toRadians(lat2 - lat1);
    double deltaLambda = Math.toRadians(lon2 - lon1);
    double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
        + Math.cos(phi1) * Math.cos(phi2)
            * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
    return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  static double angularDifferenceDegrees(double a, double b) {
    double normalizedA = normalizeBearing(a);
    double normalizedB = normalizeBearing(b);
    double difference = Math.abs(normalizedA - normalizedB);
    return Math.min(difference, 360.0 - difference);
  }

  static double normalizeBearing(double value) {
    return ((value % 360.0) + 360.0) % 360.0;
  }

  static double signedBearingDelta(double from, double to) {
    return ((normalizeBearing(to) - normalizeBearing(from) + 540.0) % 360.0) - 180.0;
  }
}
