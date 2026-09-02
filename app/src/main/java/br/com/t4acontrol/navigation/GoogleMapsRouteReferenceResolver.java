package br.com.t4acontrol.navigation;

import br.com.t4acontrol.backend.navigation.RouteReferenceResolver;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

/** Android/network adapter that expands Google Maps shared short links without a Google SDK. */
public final class GoogleMapsRouteReferenceResolver implements RouteReferenceResolver {
  private static final int MAX_REDIRECTS = 8;
  private static final int CONNECT_TIMEOUT_MS = 8_000;
  private static final int READ_TIMEOUT_MS = 8_000;

  @Override
  public String resolve(String routeReference) throws RouteResolutionException {
    if (routeReference == null || routeReference.trim().isEmpty()) {
      throw new RouteResolutionException("Route reference is empty");
    }

    URI input;
    try {
      input = URI.create(routeReference.trim());
    } catch (IllegalArgumentException ex) {
      throw new RouteResolutionException("Invalid route reference", ex);
    }

    String host = input.getHost();
    if (host == null || !host.equalsIgnoreCase("maps.app.goo.gl")) {
      return routeReference.trim();
    }

    try {
      URL current = input.toURL();
      for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
        HttpURLConnection connection = (HttpURLConnection) current.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "RideDash/1.0");
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml");

        int status = connection.getResponseCode();
        if (status >= 300 && status < 400) {
          String location = connection.getHeaderField("Location");
          connection.disconnect();
          if (location == null || location.trim().isEmpty()) {
            throw new RouteResolutionException("Google Maps redirect has no Location header");
          }
          current = current.toURI().resolve(location).toURL();
          continue;
        }

        URL resolved = connection.getURL();
        connection.disconnect();
        return resolved.toString();
      }
      throw new RouteResolutionException("Too many Google Maps redirects");
    } catch (IOException | java.net.URISyntaxException ex) {
      throw new RouteResolutionException("Unable to resolve Google Maps shared link", ex);
    }
  }
}
