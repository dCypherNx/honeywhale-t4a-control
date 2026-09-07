package br.com.t4acontrol.navigation;

import android.content.Context;
import br.com.t4acontrol.backend.navigation.Route;
import br.com.t4acontrol.backend.navigation.RouteParser;
import br.com.t4acontrol.backend.navigation.RoutePlanner;
import br.com.t4acontrol.backend.navigation.RouteReferenceResolver;
import br.com.t4acontrol.backend.navigation.RouteReferenceStore;
import java.util.Objects;

/** Synchronous shared-route import pipeline. Call from a worker thread. */
public final class NavigationRouteImporter {
  interface DiagnosticSink {
    DiagnosticSink NOOP = entry -> {};
    void log(String entry);
  }

  private final RouteReferenceResolver resolver;
  private final RouteParser parser;
  private final RoutePlanner planner;
  private final RouteReferenceStore store;
  private final DiagnosticSink diagnostics;

  public NavigationRouteImporter(Context context) {
    this(
        new GoogleMapsRouteReferenceResolver(context, DiagnosticSink.NOOP::log),
        new GoogleMapsRouteParser(),
        new OsrmRoutePlanner(),
        new SharedPreferencesRouteReferenceStore(context),
        DiagnosticSink.NOOP);
  }

  NavigationRouteImporter(
      RouteReferenceResolver resolver,
      RouteParser parser,
      RoutePlanner planner,
      RouteReferenceStore store) {
    this(resolver, parser, planner, store, DiagnosticSink.NOOP);
  }

  NavigationRouteImporter(
      RouteReferenceResolver resolver,
      RouteParser parser,
      RoutePlanner planner,
      RouteReferenceStore store,
      DiagnosticSink diagnostics) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.parser = Objects.requireNonNull(parser, "parser");
    this.planner = Objects.requireNonNull(planner, "planner");
    this.store = Objects.requireNonNull(store, "store");
    this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
  }

  public Route importReference(String routeReference) throws ImportException {
    if (routeReference == null || routeReference.trim().isEmpty()) {
      diagnostics.log("[NAV] IMPORT FAIL stage=VALIDATE type=IllegalArgumentException message=empty_reference");
      throw new ImportException("Route reference is empty");
    }

    String canonicalInput = routeReference.trim();
    diagnostics.log("[NAV] IMPORT START reference=" + canonicalInput);
    String stage = "RESOLVE";
    try {
      String resolved = resolver.resolve(canonicalInput);
      diagnostics.log("[NAV] IMPORT RESOLVED url=" + resolved);

      stage = "PARSE";
      Route imported = parser.parse(resolved);
      diagnostics.log(
          "[NAV] IMPORT PARSED route="
              + imported.id
              + " profile="
              + String.valueOf(imported.routingProfile)
              + " waypoints="
              + imported.waypoints.size());

      stage = "PERSIST";
      store.save(canonicalInput);
      diagnostics.log("[NAV] IMPORT PERSISTED reference=" + canonicalInput);

      stage = "PLAN";
      Route planned = planner.plan(imported);
      diagnostics.log(
          "[NAV] IMPORT SUCCESS route="
              + planned.id
              + " profile="
              + String.valueOf(planned.routingProfile)
              + " legs="
              + planned.legs.size());
      return planned;
    } catch (RouteReferenceResolver.RouteResolutionException
        | RouteParser.RouteParseException
        | RoutePlanner.RoutePlanningException
        | RuntimeException ex) {
      diagnostics.log(
          "[NAV] IMPORT FAIL stage="
              + stage
              + " type="
              + ex.getClass().getSimpleName()
              + " message="
              + safeMessage(ex));
      throw new ImportException("Unable to import shared route at " + stage, ex);
    }
  }

  public Route restore() throws ImportException {
    String reference = store.load();
    if (reference == null) {
      diagnostics.log("[NAV] RESTORE no_saved_route");
      return null;
    }
    diagnostics.log("[NAV] RESTORE reference=" + reference);
    return importReference(reference);
  }

  public void clear() {
    store.clear();
    diagnostics.log("[NAV] ROUTE_REFERENCE cleared");
  }

  private static String safeMessage(Throwable error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) return "<none>";
    return message.replace('\n', ' ').replace('\r', ' ');
  }

  public static final class ImportException extends Exception {
    public ImportException(String message) { super(message); }
    public ImportException(String message, Throwable cause) { super(message, cause); }
  }
}
