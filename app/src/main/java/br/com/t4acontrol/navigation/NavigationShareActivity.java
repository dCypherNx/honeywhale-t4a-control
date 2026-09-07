package br.com.t4acontrol.navigation;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import br.com.t4acontrol.MainActivity;
import br.com.t4acontrol.R;
import br.com.t4acontrol.backend.navigation.Route;
import java.util.ArrayList;

/** Receives text/plain shares from Google Maps and imports them without touching T4A control flow. */
public final class NavigationShareActivity extends Activity {
  public static final String EXTRA_NAVIGATION_RAW_LOG =
      "br.com.t4acontrol.navigation.EXTRA_NAVIGATION_RAW_LOG";

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    ArrayList<String> trace = new ArrayList<>();
    String reference = sharedText(getIntent());
    if (reference == null) {
      trace.add("[NAV] SHARE FAIL stage=EXTRACT message=no_route_url");
      Toast.makeText(this, R.string.navigation_share_invalid, Toast.LENGTH_LONG).show();
      openMainAndFinish(trace);
      return;
    }

    trace.add("[NAV] SHARE RECEIVED reference=" + reference);
    NavigationRouteImporter importer =
        new NavigationRouteImporter(
            new GoogleMapsRouteReferenceResolver(getApplicationContext(), trace::add),
            new GoogleMapsRouteParser(),
            new OsrmRoutePlanner(),
            new SharedPreferencesRouteReferenceStore(getApplicationContext()),
            trace::add);
    new Thread(() -> {
      try {
        Route route = importer.importReference(reference);
        NavigationRuntime.get().setRoute(route);
        trace.add("[NAV] SHARE SUCCESS route=" + route.id);
        runOnUiThread(() -> {
          Toast.makeText(this, R.string.navigation_route_imported, Toast.LENGTH_LONG).show();
          openMainAndFinish(trace);
        });
      } catch (NavigationRouteImporter.ImportException error) {
        trace.add(
            "[NAV] SHARE FAIL type="
                + error.getClass().getSimpleName()
                + " message="
                + safeMessage(error));
        runOnUiThread(() -> {
          Toast.makeText(this, R.string.navigation_route_import_failed, Toast.LENGTH_LONG).show();
          openMainAndFinish(trace);
        });
      }
    }, "navigation-route-import").start();
  }

  private static String sharedText(Intent intent) {
    if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
    CharSequence value = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
    if (value == null) return null;
    String text = value.toString().trim();
    if (text.isEmpty()) return null;

    // Google Maps may share surrounding text together with the URL. Extract the first HTTPS token.
    for (String token : text.split("\\s+")) {
      if (token.startsWith("https://")) return trimTrailingPunctuation(token);
    }
    return text.startsWith("http://") ? trimTrailingPunctuation(text) : null;
  }

  private static String trimTrailingPunctuation(String value) {
    int end = value.length();
    while (end > 0) {
      char ch = value.charAt(end - 1);
      if (ch == '.' || ch == ',' || ch == ')' || ch == ']' || ch == '}') end--;
      else break;
    }
    return value.substring(0, end);
  }

  private void openMainAndFinish(ArrayList<String> trace) {
    Intent main = new Intent(this, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putStringArrayListExtra(EXTRA_NAVIGATION_RAW_LOG, trace);
    startActivity(main);
    finish();
  }

  private static String safeMessage(Throwable error) {
    String message = error.getMessage();
    if (message == null || message.isBlank()) return "<none>";
    return message.replace('\n', ' ').replace('\r', ' ');
  }
}
