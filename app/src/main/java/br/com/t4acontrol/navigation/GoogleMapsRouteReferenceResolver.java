package br.com.t4acontrol.navigation;

import br.com.t4acontrol.backend.navigation.RouteReferenceResolver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android/network adapter that expands Google Maps shared short links without a Google SDK. */
public final class GoogleMapsRouteReferenceResolver implements RouteReferenceResolver {
  private static final int MAX_REDIRECTS = 8;
  private static final int CONNECT_TIMEOUT_MS = 8_000;
  private static final int READ_TIMEOUT_MS = 8_000;
  private static final int MAX_HTML_CHARS = 256 * 1024;

  private static final Pattern META_REFRESH = Pattern.compile(
      "(?is)<meta[^>]+http-equiv\\s*=\\s*['\"]?refresh['\"]?[^>]+content\\s*=\\s*['\"][^'\"]*?url\\s*=\\s*([^'\">]+)");
  private static final Pattern META_REFRESH_REVERSED = Pattern.compile(
      "(?is)<meta[^>]+content\\s*=\\s*['\"][^'\"]*?url\\s*=\\s*([^'\">]+)[^>]+http-equiv\\s*=\\s*['\"]?refresh['\"]?");
  private static final Pattern JS_LOCATION = Pattern.compile(
      "(?is)(?:window\\.)?location(?:\\.href)?\\s*=\\s*['\"]([^'\"]+)['\"]");
  private static final Pattern JS_REPLACE = Pattern.compile(
      "(?is)(?:window\\.)?location\\.replace\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
  private static final Pattern GOOGLE_ROUTE_URL = Pattern.compile(
      "(?is)https?://(?:www\\.)?google\\.[^\\s'\"<>]+/maps/dir/[^\\s'\"<>]+");

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

        if (status >= 200 && status < 300) {
          URL responseUrl = connection.getURL();
          String html = readBody(connection);
          connection.disconnect();

          String embedded = extractRedirectTarget(html);
          if (embedded != null) {
            URL next = responseUrl.toURI().resolve(decodeHtml(embedded.trim())).toURL();
            if (!sameUrl(current, next)) {
              current = next;
              continue;
            }
          }

          String resolved = responseUrl.toString();
          if (looksLikeExpandedRoute(resolved)) return resolved;
          throw new RouteResolutionException(
              "Google Maps short link returned HTTP " + status + " without an expanded /maps/dir route");
        }

        connection.disconnect();
        throw new RouteResolutionException("Google Maps short link returned HTTP " + status);
      }
      throw new RouteResolutionException("Too many Google Maps redirects");
    } catch (IOException | java.net.URISyntaxException ex) {
      throw new RouteResolutionException("Unable to resolve Google Maps shared link", ex);
    }
  }

  static String extractRedirectTarget(String html) {
    if (html == null || html.isEmpty()) return null;
    String normalized = html
        .replace("\\u003d", "=")
        .replace("\\u0026", "&")
        .replace("\\u003f", "?")
        .replace("\\u002f", "/")
        .replace("\\/", "/");

    String value = firstGroup(META_REFRESH, normalized);
    if (value != null) return value;
    value = firstGroup(META_REFRESH_REVERSED, normalized);
    if (value != null) return value;
    value = firstGroup(JS_REPLACE, normalized);
    if (value != null) return value;
    value = firstGroup(JS_LOCATION, normalized);
    if (value != null) return value;
    return firstMatch(GOOGLE_ROUTE_URL, normalized);
  }

  private static String readBody(HttpURLConnection connection) throws IOException {
    InputStream stream = connection.getInputStream();
    StringBuilder result = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      char[] buffer = new char[4096];
      while (result.length() < MAX_HTML_CHARS) {
        int count = reader.read(buffer, 0, Math.min(buffer.length, MAX_HTML_CHARS - result.length()));
        if (count < 0) break;
        result.append(buffer, 0, count);
      }
    }
    return result.toString();
  }

  private static String firstGroup(Pattern pattern, String value) {
    Matcher matcher = pattern.matcher(value);
    return matcher.find() ? matcher.group(1) : null;
  }

  private static String firstMatch(Pattern pattern, String value) {
    Matcher matcher = pattern.matcher(value);
    return matcher.find() ? matcher.group() : null;
  }

  private static boolean looksLikeExpandedRoute(String value) {
    try {
      URI uri = URI.create(value);
      String host = uri.getHost();
      String path = uri.getRawPath();
      return host != null
          && (host.equalsIgnoreCase("google.com") || host.contains("google."))
          && path != null
          && path.contains("/maps/dir/");
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private static boolean sameUrl(URL first, URL second) {
    return first.toString().equals(second.toString());
  }

  private static String decodeHtml(String value) {
    return value
        .replace("&amp;", "&")
        .replace("&#38;", "&")
        .replace("&#x26;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'");
  }
}
