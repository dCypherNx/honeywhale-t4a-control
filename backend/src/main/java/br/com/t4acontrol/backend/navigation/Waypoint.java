package br.com.t4acontrol.backend.navigation;

import java.util.Objects;

/** Ordered point belonging to a route; coordinates may be unresolved immediately after import. */
public final class Waypoint {
  public enum Role { ORIGIN, VIA, DESTINATION }

  public final String id;
  public final String label;
  public final Double latitude;
  public final Double longitude;
  public final Role role;

  public Waypoint(String id, String label, Double latitude, Double longitude, Role role) {
    this.id = Objects.requireNonNull(id, "id");
    this.label = label;
    if ((latitude == null) != (longitude == null)) {
      throw new IllegalArgumentException("latitude and longitude must both be present or both be absent");
    }
    this.latitude = latitude;
    this.longitude = longitude;
    this.role = Objects.requireNonNull(role, "role");
  }

  public boolean hasCoordinates() {
    return latitude != null && longitude != null;
  }
}
