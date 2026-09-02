package br.com.t4acontrol.backend.navigation;

import java.util.Objects;

/** Provider-neutral instruction anchored to a geographic point and route offset. */
public final class NavigationInstruction {
  public enum Maneuver {
    START,
    STRAIGHT,
    TURN_LEFT,
    TURN_RIGHT,
    U_TURN,
    ROUNDABOUT,
    WAYPOINT,
    ARRIVE,
    UNKNOWN
  }

  public final String id;
  public final Maneuver maneuver;
  public final String text;
  public final double latitude;
  public final double longitude;
  /** Distance from the start of the leg to this maneuver, in meters; NaN when unavailable. */
  public final double routeOffsetMeters;
  /** Distance from this maneuver to the following maneuver, in meters; NaN when unavailable. */
  public final double segmentDistanceMeters;
  /** Optional rich routing metadata. Null when the provider does not expose it. */
  public final NavigationStepMetadata metadata;

  public NavigationInstruction(
      String id,
      Maneuver maneuver,
      String text,
      double latitude,
      double longitude) {
    this(id, maneuver, text, latitude, longitude, Double.NaN, Double.NaN, null);
  }

  public NavigationInstruction(
      String id,
      Maneuver maneuver,
      String text,
      double latitude,
      double longitude,
      double routeOffsetMeters,
      double segmentDistanceMeters) {
    this(id, maneuver, text, latitude, longitude, routeOffsetMeters, segmentDistanceMeters, null);
  }

  public NavigationInstruction(
      String id,
      Maneuver maneuver,
      String text,
      double latitude,
      double longitude,
      double routeOffsetMeters,
      double segmentDistanceMeters,
      NavigationStepMetadata metadata) {
    this.id = Objects.requireNonNull(id, "id");
    this.maneuver = Objects.requireNonNull(maneuver, "maneuver");
    this.text = text;
    this.latitude = latitude;
    this.longitude = longitude;
    this.routeOffsetMeters = routeOffsetMeters;
    this.segmentDistanceMeters = segmentDistanceMeters;
    this.metadata = metadata;
  }
}
