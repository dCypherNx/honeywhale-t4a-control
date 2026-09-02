package br.com.t4acontrol.backend.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable provider-neutral route imported from an external source. */
public final class Route {
  public final String id;
  public final String source;
  public final String originalReference;
  public final List<Waypoint> waypoints;
  public final List<RouteLeg> legs;

  public Route(
      String id,
      String source,
      String originalReference,
      List<Waypoint> waypoints,
      List<RouteLeg> legs) {
    this.id = Objects.requireNonNull(id, "id");
    this.source = source;
    this.originalReference = originalReference;
    this.waypoints = Collections.unmodifiableList(new ArrayList<>(
        Objects.requireNonNull(waypoints, "waypoints")));
    this.legs = Collections.unmodifiableList(new ArrayList<>(
        Objects.requireNonNull(legs, "legs")));

    if (this.waypoints.size() < 2) {
      throw new IllegalArgumentException("A route requires at least origin and destination");
    }
  }
}
