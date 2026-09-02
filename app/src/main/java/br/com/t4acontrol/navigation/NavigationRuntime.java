package br.com.t4acontrol.navigation;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import br.com.t4acontrol.backend.navigation.NavigationEngine;
import br.com.t4acontrol.backend.navigation.NavigationState;
import br.com.t4acontrol.backend.navigation.Route;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/** Process-local holder for the active planned route and navigation progress. */
public final class NavigationRuntime {
  public interface Listener {
    void onNavigationChanged(Route route, NavigationState state);
  }

  private static final NavigationRuntime INSTANCE = new NavigationRuntime();

  public static NavigationRuntime get() {
    return INSTANCE;
  }

  private final NavigationEngine engine = new NavigationEngine();
  private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
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
    notifyListeners();
  }

  public void clear() {
    setRoute(null);
  }

  /** Called by the existing AndroidLocationProvider; no second GPS source is created. */
  public void onLocation(LocationSnapshot snapshot) {
    Route active = route;
    if (active == null) return;
    state = engine.update(active, state, snapshot);
    notifyListeners();
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
}
