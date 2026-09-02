package br.com.t4acontrol.backend.navigation;

import java.util.Objects;

/** Provider-neutral turn or progress instruction anchored to a geographic point. */
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

  public NavigationInstruction(
      String id,
      Maneuver maneuver,
      String text,
      double latitude,
      double longitude) {
    this.id = Objects.requireNonNull(id, "id");
    this.maneuver = Objects.requireNonNull(maneuver, "maneuver");
    this.text = text;
    this.latitude = latitude;
    this.longitude = longitude;
  }
}
