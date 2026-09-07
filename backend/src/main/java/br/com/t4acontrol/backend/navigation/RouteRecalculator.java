package br.com.t4acontrol.backend.navigation;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Rebuilds the remaining route from the current position after an off-route event. */
public final class RouteRecalculator {
  public Route recalculate(
      Route activeRoute,
      NavigationState state,
      LocationSnapshot currentLocation,
      RoutePlanner planner) throws RoutePlanner.RoutePlanningException {
    Objects.requireNonNull(activeRoute, "activeRoute");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(currentLocation, "currentLocation");
    Objects.requireNonNull(planner, "planner");

    if (state.status != NavigationState.Status.OFF_ROUTE) {
      throw new IllegalArgumentException("Route recalculation requires OFF_ROUTE state");
    }
    if (!activeRoute.id.equals(state.routeId)) {
      throw new IllegalArgumentException("Navigation state belongs to a different route");
    }
    if (activeRoute.waypoints.size() < 2) {
      throw new IllegalArgumentException("Active route has insufficient waypoints");
    }

    int currentLeg = Math.max(0, Math.min(state.legIndex, activeRoute.waypoints.size() - 2));
    List<Waypoint> remaining = new ArrayList<>();
    remaining.add(new Waypoint(
        "recalc-origin",
        "Current position",
        currentLocation.latitude,
        currentLocation.longitude,
        Waypoint.Role.ORIGIN));

    for (int index = currentLeg + 1; index < activeRoute.waypoints.size(); index++) {
      Waypoint original = activeRoute.waypoints.get(index);
      Waypoint.Role role = index == activeRoute.waypoints.size() - 1
          ? Waypoint.Role.DESTINATION
          : Waypoint.Role.VIA;
      remaining.add(new Waypoint(
          original.id,
          original.label,
          original.latitude,
          original.longitude,
          role));
    }

    List<RouteLeg> placeholderLegs = new ArrayList<>();
    for (int index = 0; index < remaining.size() - 1; index++) {
      placeholderLegs.add(new RouteLeg(
          remaining.get(index),
          remaining.get(index + 1),
          Collections.<NavigationInstruction>emptyList()));
    }

    Route request = new Route(
        activeRoute.id + ":recalc",
        activeRoute.source,
        activeRoute.originalReference,
        activeRoute.routingProfile,
        remaining,
        placeholderLegs);
    return planner.plan(request);
  }
}
