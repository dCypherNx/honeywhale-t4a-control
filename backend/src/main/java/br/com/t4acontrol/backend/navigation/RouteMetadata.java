package br.com.t4acontrol.backend.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Optional route-level metrics and snapped waypoint data supplied by a route provider. */
public final class RouteMetadata {
  public static final class SnappedWaypoint {
    public final String name;
    public final double latitude;
    public final double longitude;
    public final double distanceMeters;
    public final String hint;

    public SnappedWaypoint(String name, double latitude, double longitude, double distanceMeters, String hint) {
      this.name = name;
      this.latitude = latitude;
      this.longitude = longitude;
      this.distanceMeters = distanceMeters;
      this.hint = hint;
    }
  }

  public final double distanceMeters;
  public final double durationSeconds;
  public final double weight;
  public final String weightName;
  public final String dataVersion;
  public final List<SnappedWaypoint> snappedWaypoints;

  public RouteMetadata(
      double distanceMeters,
      double durationSeconds,
      double weight,
      String weightName,
      String dataVersion,
      List<SnappedWaypoint> snappedWaypoints) {
    this.distanceMeters = distanceMeters;
    this.durationSeconds = durationSeconds;
    this.weight = weight;
    this.weightName = weightName;
    this.dataVersion = dataVersion;
    this.snappedWaypoints = Collections.unmodifiableList(new ArrayList<>(snappedWaypoints));
  }
}
