package br.com.t4acontrol.navigation;

import android.content.Context;
import android.content.SharedPreferences;
import br.com.t4acontrol.backend.navigation.RouteReferenceStore;
import java.util.Objects;

/** Android persistence for the canonical shared route reference. */
public final class SharedPreferencesRouteReferenceStore implements RouteReferenceStore {
  private static final String PREFS_NAME = "navigation";
  private static final String KEY_ACTIVE_ROUTE_REFERENCE = "active_route_reference";

  private final SharedPreferences preferences;

  public SharedPreferencesRouteReferenceStore(Context context) {
    Objects.requireNonNull(context, "context");
    this.preferences = context.getApplicationContext()
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
  }

  @Override public void save(String routeReference) {
    if (routeReference == null || routeReference.trim().isEmpty()) {
      throw new IllegalArgumentException("routeReference is required");
    }
    preferences.edit().putString(KEY_ACTIVE_ROUTE_REFERENCE, routeReference.trim()).apply();
  }

  @Override public String load() {
    return preferences.getString(KEY_ACTIVE_ROUTE_REFERENCE, null);
  }

  @Override public void clear() {
    preferences.edit().remove(KEY_ACTIVE_ROUTE_REFERENCE).apply();
  }
}
