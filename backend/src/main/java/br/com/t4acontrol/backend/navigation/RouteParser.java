package br.com.t4acontrol.backend.navigation;

/** Imports an external route reference into the neutral navigation model. */
public interface RouteParser {
  Route parse(String routeReference) throws RouteParseException;

  final class RouteParseException extends Exception {
    public RouteParseException(String message) {
      super(message);
    }

    public RouteParseException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
