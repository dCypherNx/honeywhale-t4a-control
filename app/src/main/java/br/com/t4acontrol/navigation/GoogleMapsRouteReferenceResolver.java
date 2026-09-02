package br.com.t4acontrol.navigation;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import br.com.t4acontrol.backend.navigation.RouteReferenceResolver;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Android/network adapter that expands Google Maps shared short links without a Google SDK. */
public final class GoogleMapsRouteReferenceResolver implements RouteReferenceResolver {
  private static final int MAX_REDIRECTS = 8;
  private static final int CONNECT_TIMEOUT_MS = 8_000;
  private static final int READ_TIMEOUT_MS = 8_000;
  private static final int MAX_HTML_CHARS = 256 * 1024;
  private static final long WEBVIEW_TIMEOUT_MS = 12_000L;
  private static final Consumer<String> NOOP_DIAGNOSTICS = entry -> {};
  private static final String APP_USER_AGENT = "RideDash/1.0";
  private static final String BROWSER_USER_AGENT =
      "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36";

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

  private final Context context;
  private final Consumer<String> diagnostics;

  public GoogleMapsRouteReferenceResolver() {
    this(null, NOOP_DIAGNOSTICS);
  }

  GoogleMapsRouteReferenceResolver(Consumer<String> diagnostics) {
    this(null, diagnostics);
  }

  GoogleMapsRouteReferenceResolver(Context context, Consumer<String> diagnostics) {
    this.context = context == null ? null : context.getApplicationContext();
    this.diagnostics = diagnostics == null ? NOOP_DIAGNOSTICS : diagnostics;
  }

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

    Set<String> visited = new LinkedHashSet<>();
    try {
      URL current = input.toURL();
      for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
        String currentKey = normalizeForCycleDetection(current);
        if (!visited.add(currentKey)) {
          diagnostics.accept("[NAV] RESOLVE CYCLE hop=" + hop + " url=" + safeUrl(current));
          throw new RouteResolutionException("Google Maps redirect cycle detected at " + safeUrl(current));
        }

        HttpURLConnection connection = openConnection(current, APP_USER_AGENT);
        int status = connection.getResponseCode();
        diagnostics.accept(
            "[NAV] RESOLVE HOP "
                + hop
                + " attempt=APP status="
                + status
                + " url="
                + safeUrl(current));

        if (status == HttpURLConnection.HTTP_NOT_FOUND
            && "maps.app.goo.gl".equalsIgnoreCase(current.getHost())) {
          connection.disconnect();
          diagnostics.accept(
              "[NAV] RESOLVE RETRY hop=" + hop + " reason=HTTP_404 identity=ANDROID_BROWSER");
          connection = openConnection(current, BROWSER_USER_AGENT);
          status = connection.getResponseCode();
          diagnostics.accept(
              "[NAV] RESOLVE HOP "
                  + hop
                  + " attempt=ANDROID_BROWSER status="
                  + status
                  + " url="
                  + safeUrl(current));

          if (status == HttpURLConnection.HTTP_NOT_FOUND && context != null) {
            connection.disconnect();
            diagnostics.accept(
                "[NAV] RESOLVE FALLBACK hop=" + hop + " reason=HTTP_404 mechanism=WEBVIEW");
            return resolveWithWebView(routeReference.trim());
          }
        }

        if (status >= 300 && status < 400) {
          String location = connection.getHeaderField("Location");
          connection.disconnect();
          if (location == null || location.trim().isEmpty()) {
            throw new RouteResolutionException("Google Maps redirect has no Location header");
          }
          URL next = current.toURI().resolve(location.trim()).toURL();
          diagnostics.accept(
              "[NAV] RESOLVE NEXT hop=" + hop + " source=HTTP_LOCATION url=" + safeUrl(next));
          if (looksLikeExpandedRoute(next.toString())) {
            diagnostics.accept(
                "[NAV] RESOLVE SUCCESS hop=" + hop + " source=HTTP_LOCATION url=" + safeUrl(next));
            return next.toString();
          }
          current = next;
          continue;
        }

        if (status >= 200 && status < 300) {
          URL responseUrl = connection.getURL();
          String resolved = responseUrl.toString();
          if (looksLikeExpandedRoute(resolved)) {
            connection.disconnect();
            diagnostics.accept(
                "[NAV] RESOLVE SUCCESS hop=" + hop + " source=HTTP_RESPONSE url=" + safeUrl(responseUrl));
            return resolved;
          }

          String html = readBody(connection);
          connection.disconnect();

          RedirectTarget embedded = extractRedirectTargetWithSource(html);
          if (embedded != null) {
            String target = decodeHtml(embedded.value.trim());
            if ("EMBEDDED_ROUTE".equals(embedded.source) && looksLikeExpandedRoute(target)) {
              diagnostics.accept(
                  "[NAV] RESOLVE SUCCESS hop=" + hop + " source=EMBEDDED_ROUTE url=" + safeText(target));
              return target;
            }

            URL next = responseUrl.toURI().resolve(target).toURL();
            diagnostics.accept(
                "[NAV] RESOLVE NEXT hop="
                    + hop
                    + " source="
                    + embedded.source
                    + " url="
                    + safeUrl(next));
            if (!sameUrl(current, next)) {
              current = next;
              continue;
            }
          }

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
    RedirectTarget target = extractRedirectTargetWithSource(html);
    return target == null ? null : target.value;
  }

  static String extractExpandedRouteFromNavigationUrl(String value) {
    if (value == null || value.isBlank()) return null;
    if (looksLikeExpandedRoute(value)) return value;

    try {
      String candidate = value;
      if (candidate.startsWith("intent://")) {
        int intentMarker = candidate.indexOf("#Intent;");
        if (intentMarker > 0) candidate = "https://" + candidate.substring(9, intentMarker);
      }
      Uri uri = Uri.parse(candidate);
      String linked = uri.getQueryParameter("link");
      if (linked != null && looksLikeExpandedRoute(linked)) return linked;
      String fallback = uri.getQueryParameter("url");
      if (fallback != null && looksLikeExpandedRoute(fallback)) return fallback;
    } catch (RuntimeException ignored) {
      // Malformed navigation URLs are ignored; the WebView may still expose a later usable URL.
    }
    return null;
  }

  private String resolveWithWebView(String routeReference) throws RouteResolutionException {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      throw new RouteResolutionException("WebView route resolution cannot block the main thread");
    }

    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<String> resolved = new AtomicReference<>();
    AtomicReference<String> failure = new AtomicReference<>();
    Handler main = new Handler(Looper.getMainLooper());
    AtomicReference<WebView> webViewRef = new AtomicReference<>();

    main.post(
        () -> {
          try {
            WebView webView = new WebView(context);
            webViewRef.set(webView);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(false);
            settings.setUserAgentString(BROWSER_USER_AGENT);
            webView.setWebViewClient(
                new WebViewClient() {
                  private boolean inspect(String url, String source) {
                    if (url == null || url.isBlank()) return false;
                    diagnostics.accept("[NAV] RESOLVE WEBVIEW source=" + source + " url=" + safeText(url));
                    String expanded = extractExpandedRouteFromNavigationUrl(url);
                    if (expanded == null) return false;
                    if (resolved.compareAndSet(null, expanded)) {
                      diagnostics.accept("[NAV] RESOLVE SUCCESS mechanism=WEBVIEW url=" + safeText(expanded));
                      finished.countDown();
                    }
                    return true;
                  }

                  @Override
                  public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    String url = request == null || request.getUrl() == null ? null : request.getUrl().toString();
                    boolean captured = inspect(url, "OVERRIDE");
                    return captured || (url != null && url.startsWith("intent://"));
                  }

                  @Override
                  @SuppressWarnings("deprecation")
                  public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    boolean captured = inspect(url, "OVERRIDE_LEGACY");
                    return captured || (url != null && url.startsWith("intent://"));
                  }

                  @Override
                  public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                    inspect(url, "PAGE_STARTED");
                  }

                  @Override
                  public void onPageFinished(WebView view, String url) {
                    inspect(url, "PAGE_FINISHED");
                  }

                  @Override
                  public void onReceivedHttpError(
                      WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                    if (request != null && request.isForMainFrame() && errorResponse != null) {
                      diagnostics.accept(
                          "[NAV] RESOLVE WEBVIEW HTTP_ERROR status="
                              + errorResponse.getStatusCode()
                              + " url="
                              + safeText(request.getUrl() == null ? "" : request.getUrl().toString()));
                    }
                  }

                  @Override
                  public void onReceivedError(
                      WebView view, WebResourceRequest request, WebResourceError error) {
                    if (request != null && request.isForMainFrame() && resolved.get() == null) {
                      failure.compareAndSet(
                          null,
                          error == null
                              ? "WebView navigation error"
                              : "WebView navigation error " + error.getErrorCode());
                      finished.countDown();
                    }
                  }
                });
            diagnostics.accept("[NAV] RESOLVE WEBVIEW START url=" + safeText(routeReference));
            webView.loadUrl(routeReference);
          } catch (RuntimeException error) {
            failure.set("WebView unavailable: " + error.getClass().getSimpleName());
            finished.countDown();
          }
        });

    boolean completed;
    try {
      completed = finished.await(WEBVIEW_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new RouteResolutionException("WebView route resolution interrupted", error);
    } finally {
      main.post(
          () -> {
            WebView webView = webViewRef.getAndSet(null);
            if (webView != null) {
              webView.stopLoading();
              webView.destroy();
            }
          });
    }

    String result = resolved.get();
    if (result != null) return result;
    if (!completed) {
      diagnostics.accept("[NAV] RESOLVE WEBVIEW TIMEOUT ms=" + WEBVIEW_TIMEOUT_MS);
      throw new RouteResolutionException("Google Maps WebView resolution timed out");
    }
    throw new RouteResolutionException(
        failure.get() == null ? "Google Maps WebView did not expose an expanded route" : failure.get());
  }

  private static HttpURLConnection openConnection(URL url, String userAgent) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
    connection.setReadTimeout(READ_TIMEOUT_MS);
    connection.setRequestMethod("GET");
    connection.setRequestProperty("User-Agent", userAgent);
    connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
    connection.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.7,en;q=0.6");
    return connection;
  }

  private static RedirectTarget extractRedirectTargetWithSource(String html) {
    if (html == null || html.isEmpty()) return null;
    String normalized = html
        .replace("\\u003d", "=")
        .replace("\\u0026", "&")
        .replace("\\u003f", "?")
        .replace("\\u002f", "/")
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\'", "'");

    String value = firstGroup(META_REFRESH, normalized);
    if (value != null) return new RedirectTarget("META_REFRESH", value);
    value = firstGroup(META_REFRESH_REVERSED, normalized);
    if (value != null) return new RedirectTarget("META_REFRESH", value);
    value = firstGroup(JS_REPLACE, normalized);
    if (value != null) return new RedirectTarget("JS_REPLACE", value);
    value = firstGroup(JS_LOCATION, normalized);
    if (value != null) return new RedirectTarget("JS_LOCATION", value);
    value = firstMatch(GOOGLE_ROUTE_URL, normalized);
    return value == null ? null : new RedirectTarget("EMBEDDED_ROUTE", value);
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
      return GoogleMapsRouteParser.isGoogleMapsHost(host)
          && path != null
          && path.contains("/maps/dir/");
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private static boolean sameUrl(URL first, URL second) {
    return normalizeForCycleDetection(first).equals(normalizeForCycleDetection(second));
  }

  private static String normalizeForCycleDetection(URL value) {
    String text = value.toString();
    int fragment = text.indexOf('#');
    return fragment >= 0 ? text.substring(0, fragment) : text;
  }

  private static String safeUrl(URL value) {
    return safeText(value.toString());
  }

  private static String safeText(String value) {
    if (value == null) return "";
    String text = value.replace('\n', '_').replace('\r', '_');
    return text.length() <= 1200 ? text : text.substring(0, 1200) + "…";
  }

  private static String decodeHtml(String value) {
    return value
        .replace("&amp;", "&")
        .replace("&#38;", "&")
        .replace("&#x26;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'");
  }

  private static final class RedirectTarget {
    final String source;
    final String value;

    RedirectTarget(String source, String value) {
      this.source = source;
      this.value = value;
    }
  }
}
