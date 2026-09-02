package br.com.t4acontrol.navigation;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;
import br.com.t4acontrol.MainActivity;
import br.com.t4acontrol.R;

/** Receives text/plain shares from Google Maps and imports them without touching T4A control flow. */
public final class NavigationShareActivity extends Activity {
  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    String reference = sharedText(getIntent());
    if (reference == null) {
      Toast.makeText(this, R.string.navigation_share_invalid, Toast.LENGTH_LONG).show();
      openMainAndFinish();
      return;
    }

    NavigationRouteImporter importer = new NavigationRouteImporter(getApplicationContext());
    new Thread(() -> {
      try {
        importer.importReference(reference);
        runOnUiThread(() -> {
          Toast.makeText(this, R.string.navigation_route_imported, Toast.LENGTH_LONG).show();
          openMainAndFinish();
        });
      } catch (NavigationRouteImporter.ImportException error) {
        runOnUiThread(() -> {
          Toast.makeText(this, R.string.navigation_route_import_failed, Toast.LENGTH_LONG).show();
          openMainAndFinish();
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

  private void openMainAndFinish() {
    Intent main = new Intent(this, MainActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    startActivity(main);
    finish();
  }
}
