package br.com.t4acontrol.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import br.com.t4acontrol.backend.navigation.Route;
import br.com.t4acontrol.backend.navigation.RouteLeg;
import br.com.t4acontrol.backend.navigation.RouteParser;
import br.com.t4acontrol.backend.navigation.RoutePlanner;
import br.com.t4acontrol.backend.navigation.RouteReferenceResolver;
import br.com.t4acontrol.backend.navigation.RouteReferenceStore;
import br.com.t4acontrol.backend.navigation.Waypoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class NavigationRouteImporterTest {
  @Test public void persistsOriginalSharedReferenceAfterSuccessfulParse() throws Exception {
    FakeStore store = new FakeStore();
    Route imported = route("imported");
    Route planned = route("planned");
    NavigationRouteImporter importer = new NavigationRouteImporter(
        reference -> "https://www.google.com/maps/dir/resolved",
        reference -> imported,
        route -> planned,
        store);

    Route result = importer.importReference(" https://maps.app.goo.gl/example ");

    assertSame(planned, result);
    assertEquals("https://maps.app.goo.gl/example", store.value);
  }

  @Test public void restoreReusesPersistedReference() throws Exception {
    FakeStore store = new FakeStore();
    store.value = "https://maps.app.goo.gl/saved";
    Route planned = route("planned");
    NavigationRouteImporter importer = new NavigationRouteImporter(
        reference -> reference,
        reference -> route("imported"),
        route -> planned,
        store);

    assertSame(planned, importer.restore());
    assertEquals("https://maps.app.goo.gl/saved", store.value);
  }

  @Test public void diagnosticsIdentifyFailingImportStage() {
    FakeStore store = new FakeStore();
    ArrayList<String> diagnostics = new ArrayList<>();
    NavigationRouteImporter importer = new NavigationRouteImporter(
        reference -> "https://www.google.com/maps/dir/A/B",
        reference -> { throw new RouteParser.RouteParseException("bad route"); },
        route -> route,
        store,
        diagnostics::add);

    try {
      importer.importReference("https://maps.app.goo.gl/example");
    } catch (NavigationRouteImporter.ImportException expected) {
      // Expected.
    }

    assertTrue(diagnostics.stream().anyMatch(entry -> entry.contains("IMPORT RESOLVED")));
    assertTrue(diagnostics.stream().anyMatch(entry -> entry.contains("FAIL stage=PARSE")));
    assertTrue(diagnostics.stream().anyMatch(entry -> entry.contains("RouteParseException")));
  }

  private static Route route(String id) {
    Waypoint origin = new Waypoint("o", "o", -23.6, -46.6, Waypoint.Role.ORIGIN);
    Waypoint destination = new Waypoint("d", "d", -23.7, -46.7, Waypoint.Role.DESTINATION);
    return new Route(id, "test", "ref", List.of(origin, destination), List.of(new RouteLeg(origin, destination, List.of())));
  }

  private static final class FakeStore implements RouteReferenceStore {
    String value;
    @Override public void save(String routeReference) { value = routeReference; }
    @Override public String load() { return value; }
    @Override public void clear() { value = null; }
  }
}
