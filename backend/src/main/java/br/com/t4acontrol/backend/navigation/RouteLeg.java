package br.com.t4acontrol.backend.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One ordered segment between two route waypoints. */
public final class RouteLeg {
  public final Waypoint from;
  public final Waypoint to;
  public final List<NavigationInstruction> instructions;
  public final List<GeoPoint> geometry;

  public RouteLeg(Waypoint from, Waypoint to, List<NavigationInstruction> instructions) {
    this(from, to, instructions, Collections.<GeoPoint>emptyList());
  }

  public RouteLeg(
      Waypoint from,
      Waypoint to,
      List<NavigationInstruction> instructions,
      List<GeoPoint> geometry) {
    this.from = Objects.requireNonNull(from, "from");
    this.to = Objects.requireNonNull(to, "to");
    this.instructions = Collections.unmodifiableList(new ArrayList<>(
        Objects.requireNonNull(instructions, "instructions")));
    this.geometry = Collections.unmodifiableList(new ArrayList<>(
        Objects.requireNonNull(geometry, "geometry")));
  }
}
