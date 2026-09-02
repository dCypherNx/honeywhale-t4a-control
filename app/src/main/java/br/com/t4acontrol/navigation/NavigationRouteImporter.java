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
  private final RouteReferenceResolver resolver;
  private final RouteParser parser;
  private final RoutePlanner planner;
  private final RouteReferenceStore store;

  public NavigationRouteImporter(Context context) {
    this(
        new GoogleMapsRouteReferenceResolver(),
        new GoogleMapsRouteParser(),
        new OsrmRoutePlanner(),
        new SharedPreferencesRouteReferenceStore(context));
  }

  NavigationRouteImporter(
      RouteReferenceResolver resolver,
      RouteParser parser,
      RoutePlanner planner,
      RouteReferenceStore store) {
    this.resolver = Objects.requireNonNull(resolver, "resolver");
    this.parser = Objects.requireNonNull(parser, "parser");
    this.planner = Objects.requireNonNull(planner, "planner");
    this.store = Objects.requireNonNull(store, "store");
  }

  public Route importReference(String routeReference) throws ImportException {
    if (routeReference == null || routeReference.trim().isEmpty()) {
      throw new ImportException("Route reference is empty");
    }
    String canonicalInput = routeReference.trim();
    try {
      String resolved = resolver.resolve(canonicalInput);
      Route imported = parser.parse(resolved);
      // Persist the user's original shared reference after it is proven parseable. This lets a
      // temporarily unavailable routing service be retried without depending on provider geometry.
      store.save(canonicalInput);
      return planner.plan(imported);
    } catch (RouteReferenceResolver.RouteResolutionException
        | RouteParser.RouteParseException
        | RoutePlanner.RoutePlanningException
        | RuntimeException ex) {
      throw new ImportException("Unable to import shared route", ex);
    }
  }

  public Route restore() throws ImportException {
    String reference = store.load();
    return reference == null ? null : importReference(reference);
  }

  public void clear() {
    store.clear();
  }

  public static final class ImportException extends Exception {
    public ImportException(String message) { super(message); }
    public ImportException(String message, Throwable cause) { super(message, cause); }
  }
}
