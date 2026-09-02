package br.com.t4acontrol.navigation;

import android.content.Context;
import br.com.t4acontrol.backend.location.LocationSnapshot;
import br.com.t4acontrol.backend.navigation.NavigationDeviationPolicy;
import br.com.t4acontrol.backend.navigation.NavigationEngine;
import br.com.t4acontrol.backend.navigation.NavigationState;
import br.com.t4acontrol.backend.navigation.Route;
import br.com.t4acontrol.backend.navigation.RouteRecalculator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local holder for the active planned route and navigation progress. */
public final class NavigationRuntime {
  public interface Listener {
    void onNavigationChanged(Route route, NavigationState state);

    default void onNavigationRawLog(String value) {}
  }

  private static final NavigationRuntime INSTANCE = new NavigationRuntime();

  public static NavigationRuntime get() {
    return INSTANCE;
  }

  private final NavigationEngine engine = new NavigationEngine();
  private final NavigationDeviationPolicy deviationPolicy = new NavigationDeviationPolicy();
  private final RouteRecalculator routeRecalculator = new RouteRecalculator();
  private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
  private final AtomicBoolean restoreStarted = new AtomicBoolean();
  private final AtomicBoolean recalculationRunning = new AtomicBoolean();
  private volatile Route route;
  private volatile NavigationState state = NavigationState.noRoute();

  private NavigationRuntime() {}

  public Route route() {
    return route;
  }

  public NavigationState state() {
    return state;
  }

  public void setRoute(Route value) {
    route = value;
    state = value == null ? NavigationState.noRoute() : NavigationState.ready(value);
    deviationPolicy.reset();
    notifyListeners();
  }

  public void clear() {
    setRoute(null);
  }

  /** Restores the canonical shared reference once per process and replans it off the UI thread. */
  public void ensureRestored(Context context) {
    if (route != null || !restoreStarted.compareAndSet(false, true)) return;
    Context applicationContext = context.getApplicationContext();
    new Thread(() -> {
      try {
        Route restored = new NavigationRouteImporter(applicationContext).restore();
        if (restored != null && route == null) setRoute(restored);
      } catch (NavigationRouteImporter.ImportException ignored) {
        // Keep NO_ROUTE; the persisted reference remains available for a later explicit retry.
      }
    }, "navigation-route-restore").start();
  }

  /** Called by the existing AndroidLocationProvider; no second GPS source is created. */
  public void onLocation(LocationSnapshot snapshot) {
    Route active = route;
    if (active == null) return;

    // Keep RECALCULATING stable while network planning is in flight. The location provider keeps
    // running normally; only navigation-state projection pauses until the replacement is known.
    if (recalculationRunning.get()) return;

    NavigationState next = engine.update(active, state, snapshot);
    state = next;
    notifyListeners();

    if (!deviationPolicy.shouldRecalculate(next, snapshot)) return;
    if (!recalculationRunning.compareAndSet(false, true)) return;

    state = NavigationState.recalculating(active, next);
    notifyListeners();
    rawLog("[NAV] RECALC START route=" + active.id
        + " leg=" + next.legIndex
        + " lat=" + snapshot.latitude
        + " lon=" + snapshot.longitude
        + " accuracy=" + snapshot.accuracyMeters);

    // Route planning performs network I/O and must never block AndroidLocationProvider's main
    // executor. Keep an explicit RECALCULATING state visible until success or failure is known.
    new Thread(() -> {
      try {
        Route recalculated = routeRecalculator.recalculate(
            active,
            next,
            snapshot,
            new OsrmRoutePlanner());
        // Do not replace a route that the user imported while recalculation was in flight.
        if (route == active) {
          rawLog("[NAV] RECALC SUCCESS oldRoute=" + active.id
              + " newRoute=" + recalculated.id
              + " profile=" + recalculated.routingProfile
              + " waypoints=" + recalculated.waypoints.size());
          setRoute(recalculated);
        }
      } catch (Exception error) {
        if (route == active) {
          state = next;
          notifyListeners();
        }
        rawLog("[NAV] RECALC FAIL route=" + active.id
            + " type=" + error.getClass().getSimpleName()
            + " message=" + String.valueOf(error.getMessage()));
      } finally {
        recalculationRunning.set(false);
      }
    }, "navigation-route-recalculate").start();
  }

  public void addListener(Listener listener) {
    if (listener == null) return;
    listeners.add(listener);
    listener.onNavigationChanged(route, state);
  }

  public void removeListener(Listener listener) {
    if (listener != null) listeners.remove(listener);
  }

  private void notifyListeners() {
    Route currentRoute = route;
    NavigationState currentState = state;
    for (Listener listener : listeners) listener.onNavigationChanged(currentRoute, currentState);
  }

  private void rawLog(String value) {
    for (Listener listener : listeners) listener.onNavigationRawLog(value);
  }
}
