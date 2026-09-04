package br.com.t4acontrol.backend.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class NavigationObservationPolicyTest {
  @Test public void simpleStableCorridorCanRelaxWithoutHardNavigationCeiling() {
    NavigationObservationPolicy policy = new NavigationObservationPolicy();
    Route route = route(false, true);
    NavigationState state = navigating(route, 500.0);

    policy.observe(route, state, fix(0.0, 0.0005, 5.0, 1_000L, 30.0), false);
    NavigationObservationPolicy.Observation observation =
        policy.observe(route, state, fix(0.0, 0.0010, 5.0, 2_000L, 30.0), false);

    assertEquals(NavigationObservationPolicy.Confidence.STABLE, observation.locationConfidence);
    assertEquals(NavigationObservationPolicy.Confidence.STABLE, observation.routeConfidence);
    assertEquals(NavigationObservationPolicy.DemandLevel.RELAXED, observation.providerDemand.level);
    assertTrue(observation.observationBudgetSeconds > 10.0);
    assertTrue(observation.providerDemand.maxSafeGapMs > 10_000L);
  }

  @Test public void alternativeBranchBecomesLiveDecisionBoundary() {
    NavigationObservationPolicy policy = new NavigationObservationPolicy();
    Route route = route(true, true);
    NavigationState state = navigating(route, 500.0);

    NavigationObservationPolicy.Observation observation =
        policy.observe(route, state, fix(0.0, 0.0005, 5.0, 1_000L, 30.0), false);

    assertTrue(observation.corridorKnown);
    assertTrue(observation.alternativeBranchAhead);
    assertTrue(observation.decisionDistanceMeters < 500.0);
  }

  @Test public void unknownCorridorDoesNotPretendConfidence() {
    NavigationObservationPolicy policy = new NavigationObservationPolicy();
    Route route = route(false, false);
    NavigationState state = navigating(route, 500.0);

    NavigationObservationPolicy.Observation observation =
        policy.observe(route, state, fix(0.0, 0.0005, 5.0, 1_000L, 30.0), false);

    assertEquals(NavigationObservationPolicy.DemandLevel.PRECISE, observation.providerDemand.level);
    assertEquals("corridor_unknown", observation.reason);
  }

  @Test public void deviationImmediatelyDemandsContinuousObservation() {
    NavigationObservationPolicy policy = new NavigationObservationPolicy();
    Route route = route(false, true);
    NavigationState state = navigating(route, 500.0);

    NavigationObservationPolicy.Observation observation =
        policy.observe(route, state, fix(0.0, 0.0005, 5.0, 1_000L, 30.0), true);

    assertEquals(NavigationObservationPolicy.DemandLevel.CONTINUOUS, observation.providerDemand.level);
    assertEquals(0L, observation.providerDemand.maxSafeGapMs);
  }

  @Test public void uncertaintyConsumesGeometricClearanceInsteadOfFixedTimeHorizon() {
    NavigationObservationPolicy policy = new NavigationObservationPolicy();
    Route route = route(false, true);
    NavigationState state = navigating(route, 100.0);

    NavigationObservationPolicy.Observation accurate =
        policy.observe(route, state, fix(0.0, 0.0005, 5.0, 1_000L, 36.0), false);
    policy.reset();
    NavigationObservationPolicy.Observation uncertain =
        policy.observe(route, state, fix(0.0, 0.0005, 30.0, 1_000L, 36.0), false);

    assertTrue(accurate.decisionClearanceMeters > uncertain.decisionClearanceMeters);
    assertTrue(accurate.observationBudgetSeconds > uncertain.observationBudgetSeconds);
  }

  private static NavigationState navigating(Route route, double distance) {
    return NavigationState.of(
        NavigationState.Status.NAVIGATING,
        route,
        0,
        1,
        route.legs.get(0).instructions.get(1),
        distance);
  }

  private static LocationSnapshot fix(
      double latitude,
      double longitude,
      double accuracy,
      long timestamp,
      double speedKmh) {
    return new LocationSnapshot(latitude, longitude, accuracy, timestamp, speedKmh, 90.0, null);
  }

  private static Route route(boolean withAlternativeBranch, boolean withMetadata) {
    Waypoint origin = new Waypoint("o", "origin", 0.0, 0.0, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "destination", 0.0, 0.009, Waypoint.Role.DESTINATION);

    NavigationStepMetadata metadata = null;
    if (withMetadata) {
      NavigationStepMetadata.Intersection branch = new NavigationStepMetadata.Intersection(
          0.0,
          0.0015,
          Arrays.asList(270, 90, 0),
          Arrays.asList(false, true, true),
          0,
          1,
          Collections.emptyList(),
          Collections.emptyList());
      metadata = new NavigationStepMetadata(
          NavigationStepMetadata.Context.DEPART,
          NavigationStepMetadata.TurnSeverity.STRAIGHT,
          "depart",
          "straight",
          90,
          90,
          0,
          "",
          "",
          "",
          "",
          "",
          "cycling",
          "",
          "",
          "right",
          60.0,
          60.0,
          Arrays.asList(new GeoPoint(0.0, 0.0), new GeoPoint(0.0, 0.0045)),
          withAlternativeBranch ? Arrays.asList(branch) : Collections.emptyList());
    }

    NavigationInstruction previous = new NavigationInstruction(
        "start",
        NavigationInstruction.Maneuver.START,
        "start",
        0.0,
        0.0,
        0.0,
        500.0,
        metadata);
    NavigationInstruction turn = new NavigationInstruction(
        "turn",
        NavigationInstruction.Maneuver.TURN_RIGHT,
        "turn",
        0.0,
        0.0045,
        500.0,
        500.0);
    RouteLeg leg = new RouteLeg(
        origin,
        destination,
        Arrays.asList(previous, turn),
        Arrays.asList(new GeoPoint(0.0, 0.0), new GeoPoint(0.0, 0.009)));
    return new Route(
        "route",
        "test",
        "ref",
        RoutingProfile.BICYCLE,
        Arrays.asList(origin, destination),
        Arrays.asList(leg));
  }
}
