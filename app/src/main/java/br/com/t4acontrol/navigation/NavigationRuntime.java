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
  private static final long NAVIGATION_UPDATE_INTERVAL_MS = 2_000L;

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
  private volatile NavigationState pausedFromState;
  private volatile String lastLoggedStateSignature = "";
  private volatile long lastNavigationLocationTimestampMs = Long.MIN_VALUE;
  private volatile Double guidanceSpeedKmh;

  private NavigationRuntime() {}

  public Route route() {
    return route;
  }

  public NavigationState state() {
    return state;
  }

  /** Last GPS speed accepted by the navigation cadence, used only to time visual guidance. */
  public Double guidanceSpeedKmh() {
    return guidanceSpeedKmh;
  }

  /** A newly shared route is immediately active; READY lasts only until the next GPS fix. */
  public void setRoute(Route value) {
    route = value;
    pausedFromState = null;
    state = value == null ? NavigationState.noRoute() : NavigationState.ready(value);
    deviationPolicy.reset();
    lastLoggedStateSignature = "";
    lastNavigationLocationTimestampMs = Long.MIN_VALUE;
    guidanceSpeedKmh = null;
    notifyListeners();
    logStateIfChanged(state);
  }

  private void setRestoredRoutePaused(Route value) {
    route = value;
    pausedFromState = value == null ? null : NavigationState.ready(value);
    state = value == null ? NavigationState.noRoute() : NavigationState.paused(value, pausedFromState);
    deviationPolicy.reset();
    lastLoggedStateSignature = "";
    lastNavigationLocationTimestampMs = Long.MIN_VALUE;
    guidanceSpeedKmh = null;
    notifyListeners();
    logStateIfChanged(state);
    if (value != null) rawLog("[NAV] SESSION RESTORED paused route=" + value.id);
  }

  public void pause() {
    Route active = route;
    NavigationState current = state;
    if (active == null || current.status == NavigationState.Status.PAUSED) return;
    pausedFromState = current;
    state = NavigationState.paused(active, current);
    deviationPolicy.reset();
    notifyListeners();
    logStateIfChanged(state);
    rawLog("[NAV] SESSION PAUSED route=" + active.id
        + " leg=" + current.legIndex
        + " instruction=" + current.instructionIndex);
  }

  public void resume() {
    Route active = route;
    if (active == null || state.status != NavigationState.Status.PAUSED) return;
    NavigationState resumeFrom = pausedFromState;
    state = resumeFrom == null ? NavigationState.ready(active) : resumeFrom;
    pausedFromState = null;
    deviationPolicy.reset();
    lastNavigationLocationTimestampMs = Long.MIN_VALUE;
    notifyListeners();
    logStateIfChanged(state);
    rawLog("[NAV] SESSION RESUMED route=" + active.id);
  }

  /** Ends the navigation session and removes the navigation panel. Saved-route concepts are separate. */
  public void end(Context context) {
    Route active = route;
    if (context != null) new SharedPreferencesRouteReferenceStore(context).clear();
    route = null;
    pausedFromState = null;
    state = NavigationState.noRoute();
    deviationPolicy.reset();
    recalculationRunning.set(false);
    restoreStarted.set(true);
    lastLoggedStateSignature = "";
    lastNavigationLocationTimestampMs = Long.MIN_VALUE;
    guidanceSpeedKmh = null;
    notifyListeners();
    if (active != null) rawLog("[NAV] SESSION ENDED route=" + active.id);
  }

  public void clear() {
    setRoute(null);
  }

  /** Restores a persisted route once per process, always paused until explicit user resume. */
  public void ensureRestored(Context context) {
    if (route != null || !restoreStarted.compareAndSet(false, true)) return;
    Context applicationContext = context.getApplicationContext();
    new Thread(() -> {
      try {
        Route restored = new NavigationRouteImporter(applicationContext).restore();
        if (restored != null && route == null) setRestoredRoutePaused(restored);
      } catch (NavigationRouteImporter.ImportException ignored) {
        // Keep NO_ROUTE; the persisted reference remains available for a later explicit retry.
      }
    }, "navigation-route-restore").start();
  }

  /** Called by the existing AndroidLocationProvider; no second GPS source is created. */
  public void onLocation(LocationSnapshot snapshot) {
    Route active = route;
    if (active == null || state.status == NavigationState.Status.PAUSED || snapshot == null) return;

    // Navigation intentionally runs at a stable 2 s cadence. Telemetry continues receiving every
    // location fix independently; this throttle only controls route projection/guidance churn.
    long timestamp = snapshot.timestampMs;
    if (timestamp > 0L && lastNavigationLocationTimestampMs != Long.MIN_VALUE
        && timestamp >= lastNavigationLocationTimestampMs
        && timestamp - lastNavigationLocationTimestampMs < NAVIGATION_UPDATE_INTERVAL_MS) {
      return;
    }
    if (timestamp > 0L) lastNavigationLocationTimestampMs = timestamp;
    guidanceSpeedKmh = snapshot.gpsSpeedKmh;

    // Keep RECALCULATING stable while network planning is in flight. The location provider keeps
    // running normally; only navigation-state projection pauses until the replacement is known.
    if (recalculationRunning.get()) return;

    NavigationState next = engine.update(active, state, snapshot);

    // OFF_ROUTE is evidence, not a user-facing step. Keep the last useful maneuver visible while
    // the deviation policy rejects GPS noise; when deviation is confirmed, jump directly to
    // RECALCULATING. The internal OFF_ROUTE transition remains in raw logs for diagnosis.
    if (next.status == NavigationState.Status.OFF_ROUTE) {
      logStateIfChanged(next);
      if (!deviationPolicy.shouldRecalculate(next, snapshot)) return;
      if (!recalculationRunning.compareAndSet(false, true)) return;

      state = NavigationState.recalculating(active, next);
      notifyListeners();
      logStateIfChanged(state);
      rawLog("[NAV] RECALC START route=" + active.id
          + " leg=" + next.legIndex
          + " lat=" + snapshot.latitude
          + " lon=" + snapshot.longitude
          + " accuracy=" + snapshot.accuracyMeters);
      startRecalculation(active, next, snapshot);
      return;
    }

    state = next;
    notifyListeners();
    logStateIfChanged(next);
    deviationPolicy.shouldRecalculate(next, snapshot); // resets any previous deviation evidence
  }

  private void startRecalculation(Route active, NavigationState offRouteState, LocationSnapshot snapshot) {
    new Thread(() -> {
      try {
        Route recalculated = routeRecalculator.recalculate(
            active,
            offRouteState,
            snapshot,
            new OsrmRoutePlanner());
        if (route == active && state.status != NavigationState.Status.PAUSED) {
          rawLog("[NAV] RECALC SUCCESS oldRoute=" + active.id
              + " newRoute=" + recalculated.id
              + " profile=" + recalculated.routingProfile
              + " waypoints=" + recalculated.waypoints.size());
          setRoute(recalculated);
        }
      } catch (Exception error) {
        if (route == active && state.status != NavigationState.Status.PAUSED) {
          state = offRouteState;
          notifyListeners();
          logStateIfChanged(state);
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

  private void logStateIfChanged(NavigationState value) {
    if (value == null) return;
    String instructionId = value.instruction == null ? "none" : value.instruction.id;
    String maneuver = value.instruction == null ? "none" : value.instruction.maneuver.name();
    String signature = value.status + "|" + value.legIndex + "|" + value.instructionIndex + "|" + instructionId;
    if (signature.equals(lastLoggedStateSignature)) return;
    lastLoggedStateSignature = signature;
    rawLog("[NAV] STATE status=" + value.status
        + " leg=" + value.legIndex
        + " instruction=" + value.instructionIndex
        + " maneuver=" + maneuver
        + " id=" + instructionId
        + " distance=" + String.valueOf(value.distanceToInstructionMeters));
  }

  private void rawLog(String value) {
    for (Listener listener : listeners) listener.onNavigationRawLog(value);
  }
}
