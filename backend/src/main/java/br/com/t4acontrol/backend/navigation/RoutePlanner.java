package br.com.t4acontrol.backend.navigation;

/** Enriches an imported route with provider-supplied geometry and turn-by-turn instructions. */
public interface RoutePlanner {
  Route plan(Route importedRoute) throws RoutePlanningException;

  final class RoutePlanningException extends Exception {
    public RoutePlanningException(String message) {
      super(message);
    }

    public RoutePlanningException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
