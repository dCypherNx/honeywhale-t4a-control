package br.com.t4acontrol.backend.navigation;

import java.util.Objects;

/** Ordered geographic point belonging to a route. */
public final class Waypoint {
  public enum Role { ORIGIN, VIA, DESTINATION }

  public final String id;
  public final String label;
  public final double latitude;
  public final double longitude;
  public final Role role;

  public Waypoint(String id, String label, double latitude, double longitude, Role role) {
    this.id = Objects.requireNonNull(id, "id");
    this.label = label;
    this.latitude = latitude;
    this.longitude = longitude;
    this.role = Objects.requireNonNull(role, "role");
  }
}
