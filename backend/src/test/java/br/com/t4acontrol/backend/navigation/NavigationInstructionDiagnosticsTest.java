package br.com.t4acontrol.backend.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;
import org.junit.Test;

public final class NavigationInstructionDiagnosticsTest {
  @Test public void focusIncludesRawProviderSemanticsAndIntersectionTopology() {
    NavigationInstruction instruction = instruction();
    Route route = route(instruction);
    NavigationState state = NavigationState.of(
        NavigationState.Status.NAVIGATING, route, 0, 0, instruction, 42.0);
    NavigationInstructionDiagnostics diagnostics = new NavigationInstructionDiagnostics();

    List<NavigationInstructionDiagnostics.Event> events = diagnostics.observe(
        null,
        state,
        new LocationSnapshot(-23.6, -46.6, 5.0, 1L, 25.0, 180.0, null),
        new RouteProgressSnapshot(0, 10.0, 3.0, true, 1L));

    assertEquals(1, events.size());
    assertEquals(NavigationInstructionDiagnostics.Kind.INSTRUCTION_FOCUS, events.get(0).kind);
    assertTrue(events.get(0).summary.contains("rawType=\"continue\""));
    assertTrue(events.get(0).summary.contains("rawModifier=\"slight left\""));
    assertTrue(events.get(0).summary.contains("context=CONTINUE"));
    assertTrue(events.get(0).summary.contains("bearingBefore=180"));
    assertTrue(events.get(0).summary.contains("bearingAfter=170"));
    assertTrue(events.get(0).summary.contains("bearings=[0, 170, 270]"));
    assertTrue(events.get(0).summary.contains("entry=[false, true, true]"));
  }

  @Test public void firstOffRouteEvidenceMarksPossibleInstructionNonComplianceOnlyOnce() {
    NavigationInstruction instruction = instruction();
    Route route = route(instruction);
    NavigationState navigating = NavigationState.of(
        NavigationState.Status.NAVIGATING, route, 0, 0, instruction, 8.0);
    NavigationState offRoute = NavigationState.of(
        NavigationState.Status.OFF_ROUTE, route, 0, 0, instruction, null);
    NavigationInstructionDiagnostics diagnostics = new NavigationInstructionDiagnostics();
    LocationSnapshot location =
        new LocationSnapshot(-23.6, -46.6, 5.0, 2L, 25.0, 260.0, null);
    RouteProgressSnapshot progress = new RouteProgressSnapshot(0, 55.0, 48.0, true, 2L);

    diagnostics.observe(null, navigating, location, progress);
    List<NavigationInstructionDiagnostics.Event> first =
        diagnostics.observe(navigating, offRoute, location, progress);
    List<NavigationInstructionDiagnostics.Event> repeated =
        diagnostics.observe(navigating, offRoute, location, progress);

    assertEquals(1, first.size());
    assertEquals(NavigationInstructionDiagnostics.Kind.INSTRUCTION_DIVERGENCE, first.get(0).kind);
    assertTrue(first.get(0).summary.contains("classification=possible_instruction_not_followed"));
    assertTrue(first.get(0).summary.contains("actualBearing=260.0"));
    assertTrue(first.get(0).summary.contains("expectedBearingAfter=170"));
    assertTrue(first.get(0).summary.contains("bearingDelta=90.0"));
    assertTrue(first.get(0).summary.contains("lateralError=48.0"));
    assertTrue(first.get(0).summary.contains("interpretation=evidence_only"));
    assertTrue(repeated.isEmpty());
  }

  private static NavigationInstruction instruction() {
    NavigationStepMetadata.Intersection intersection = new NavigationStepMetadata.Intersection(
        -23.6,
        -46.6,
        List.of(0, 170, 270),
        List.of(false, true, true),
        0,
        1,
        List.of("urban"),
        List.of());
    NavigationStepMetadata metadata = new NavigationStepMetadata(
        NavigationStepMetadata.Context.CONTINUE,
        NavigationStepMetadata.TurnSeverity.SLIGHT,
        "continue",
        "slight left",
        180,
        170,
        -1,
        "Rua Exemplo",
        null,
        null,
        null,
        null,
        "cycling",
        null,
        null,
        "right",
        10.0,
        10.0,
        List.of(new GeoPoint(-23.6, -46.6), new GeoPoint(-23.599, -46.6)),
        List.of(intersection));
    return new NavigationInstruction(
        "osrm-0-1",
        NavigationInstruction.Maneuver.TURN_LEFT,
        "Continue",
        -23.5995,
        -46.6,
        50.0,
        100.0,
        metadata);
  }

  private static Route route(NavigationInstruction instruction) {
    Waypoint origin = new Waypoint("o", "o", -23.601, -46.6, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "d", -23.59, -46.6, Waypoint.Role.DESTINATION);
    RouteLeg leg = new RouteLeg(
        origin,
        destination,
        List.of(instruction),
        List.of(new GeoPoint(-23.601, -46.6), new GeoPoint(-23.59, -46.6)));
    return new Route("route", "test", "ref", List.of(origin, destination), List.of(leg));
  }
}
