package br.com.t4acontrol.backend.navigation;

/** Resolves an external route reference before provider-specific parsing. */
public interface RouteReferenceResolver {
  String resolve(String routeReference) throws RouteResolutionException;

  final class RouteResolutionException extends Exception {
    public RouteResolutionException(String message) {
      super(message);
    }

    public RouteResolutionException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
