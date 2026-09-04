package br.com.t4acontrol.navigation;

import android.content.Context;
import br.com.t4acontrol.backend.location.LocationSnapshot;
import br.com.t4acontrol.backend.navigation.NavigationDeviationPolicy;
import br.com.t4acontrol.backend.navigation.NavigationEngine;
import br.com.t4acontrol.backend.navigation.NavigationObservationPolicy;
import br.com.t4acontrol.backend.navigation.NavigationState;
import br.com.t4acontrol.backend.navigation.Route;
import br.com.t4acontrol.backend.navigation.RouteProgressSnapshot;
import br.com.t4acontrol.backend.navigation.RouteProgressTracker;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-local Android adapter around one navigation session. */
public final class NavigationRuntime {
  public interface Listener {
    void onNavigationChanged(Route route, NavigationState state);

    default void onNavigationRawLog(String value) {}
  }

  public interface LocationDemandListener {
    void onNavigationLocationDemandChanged(NavigationObservationPolicy.ProviderDemand demand);
  }

  private static final NavigationRuntime INSTANCE = new NavigationRuntime();

  public static NavigationRuntime get() {
    return INSTANCE;
  }

  private final NavigationEngine engine = new NavigationEngine();
  private final NavigationDeviationPolicy deviationPolicy = new NavigationDeviationPolicy();
  private final NavigationObservationPolicy observationPolicy = new NavigationObservationPolicy();
  private final RouteProgressTracker progressTracker = new RouteProgressTracker();
  private final NavigationRecoveryCoordinator recoveryCoordinator =
      new NavigationRecoveryCoordinator(new OsrmRoutePlanner());
  private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
  private final Set<LocationDemandListener> locationDemandListeners = new CopyOnWriteArraySet<>();
  private final AtomicBoolean restoreStarted = new AtomicBoolean();

  private volatile Route route;
  private volatile NavigationState state = NavigationState.noRoute();
  private volatile NavigationState pausedFromState;
  private volatile String lastLoggedStateSignature = "";
  private volatile Double guidanceSpeedKmh;
  private volatile LocationSnapshot guidanceLocation;
  private volatile boolean deviationSuspected;
  private volatile NavigationObservationPolicy.Observation latestObservation;
  private volatile NavigationObservationPolicy.ProviderDemand lastLocationDemand;

  private final NavigationRecoveryCoordinator.Host recoveryHost =
      new NavigationRecoveryCoordinator.Host() {
        @Override
        public boolean isEligible(Route activeRoute) {
          return route == activeRoute && state.status != NavigationState.Status.PAUSED;
        }

        @Override
        public void log(String value) {
          rawLog(value);
        }

        @Override
        public void recovered(Route activeRoute, Route candidate) {
          if (!isEligible(activeRoute)) return;
          setRoute(candidate);
        }

        @Override
        public void failed(
            Route activeRoute, NavigationState fallbackUiState, Exception error) {
          if (!isEligible(activeRoute)) return;
          deviationSuspected = false;
          state = fallbackUiState;
          observationPolicy.reset();
          progressTracker.reset();
          latestObservation = null;
          notifyListeners();
          notifyLocationDemandIfChanged();
          logStateIfChanged(state);
          rawLog("[NAV] RECALC FAIL route=" + activeRoute.id
              + " type=" + error.getClass().getSimpleName()
              + " message=" + String.valueOf(error.getMessage()));
        }
      };

  private NavigationRuntime() {}

  public Route route() {
    return route;
  }

  public NavigationState state() {
    return state;
  }

  /** Last GPS speed observed by navigation; retained for UI API compatibility. */
  public Double guidanceSpeedKmh() {
    return guidanceSpeedKmh;
  }

  /** Last GPS fix observed by navigation, used by guidance disambiguation. */
  public LocationSnapshot guidanceLocation() {
    return guidanceLocation;
  }

  public NavigationObservationPolicy.ProviderDemand locationDemand() {
    NavigationObservationPolicy.ProviderDemand initial = observationPolicy.initialDemand(route, state);
    if (initial.level == NavigationObservationPolicy.DemandLevel.NONE
        || initial.level == NavigationObservationPolicy.DemandLevel.CONTINUOUS) {
      return initial;
    }
    NavigationObservationPolicy.Observation observation = latestObservation;
    return observation == null ? initial : observation.providerDemand;
  }

  /** A newly shared route is immediately active; READY lasts only until the next GPS fix. */
  public void setRoute(Route value) {
    route = value;
    pausedFromState = null;
    state = value == null ? NavigationState.noRoute() : NavigationState.ready(value);
    resetLivePolicies();
    lastLoggedStateSignature = "";
    guidanceSpeedKmh = null;
    guidanceLocation = null;
    notifyListeners();
    notifyLocationDemandIfChanged();
    logStateIfChanged(state);
  }

  private void setRestoredRoutePaused(Route value) {
    route = value;
    pausedFromState = value == null ? null : NavigationState.ready(value);
    state = value == null ? NavigationState.noRoute() : NavigationState.paused(value, pausedFromState);
    resetLivePolicies();
    lastLoggedStateSignature = "";
    guidanceSpeedKmh = null;
    guidanceLocation = null;
    notifyListeners();
    notifyLocationDemandIfChanged();
    logStateIfChanged(state);
    if (value != null) rawLog("[NAV] SESSION RESTORED paused route=" + value.id);
  }

  public void pause() {
    Route active = route;
    NavigationState current = state;
    if (active == null || current.status == NavigationState.Status.PAUSED) return;
    pausedFromState = current;
    state = NavigationState.paused(active, current);
    resetLivePolicies();
    notifyListeners();
    notifyLocationDemandIfChanged();
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
    resetLivePolicies();
    notifyListeners();
    notifyLocationDemandIfChanged();
    logStateIfChanged(state);
    rawLog("[NAV] SESSION RESUMED route=" + active.id);
  }

  public void end(Context context) {
    Route active = route;
    if (context != null) new SharedPreferencesRouteReferenceStore(context).clear();
    route = null;
    pausedFromState = null;
    state = NavigationState.noRoute();
    resetLivePolicies();
    restoreStarted.set(true);
    lastLoggedStateSignature = "";
    guidanceSpeedKmh = null;
    guidanceLocation = null;
    notifyListeners();
    notifyLocationDemandIfChanged();
    if (active != null) rawLog("[NAV] SESSION ENDED route=" + active.id);
  }

  public void clear() {
    setRoute(null);
  }

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
    NavigationState current = state;
    if (active == null || current.status == NavigationState.Status.PAUSED || snapshot == null) return;

    guidanceSpeedKmh = snapshot.gpsSpeedKmh;
    guidanceLocation = snapshot;

    RouteProgressSnapshot progress = progressTracker.observe(active, current, snapshot);
    if (recoveryCoordinator.isRunning()) {
      observe(active, current, snapshot, progress, true);
      return;
    }

    NavigationState next = engine.update(active, current, snapshot, progress);
    RouteProgressSnapshot nextProgress = progress;
    if (progress == null || progress.legIndex != next.legIndex) {
      nextProgress = progressTracker.observe(active, next, snapshot);
    }

    if (next.status == NavigationState.Status.OFF_ROUTE) {
      deviationSuspected = true;
      observe(active, next, snapshot, nextProgress, true);
      logStateIfChanged(next);
      boolean confirmed = deviationPolicy.shouldRecalculate(next, snapshot);
      if (!confirmed) {
        recoveryCoordinator.considerPrefetch(active, current, next, snapshot, recoveryHost);
        return;
      }
      if (recoveryCoordinator.isRunning()) return;

      state = NavigationState.recalculating(active, next);
      notifyListeners();
      observe(active, state, snapshot, nextProgress, true);
      logStateIfChanged(state);
      rawLog("[NAV] RECALC START route=" + active.id
          + " leg=" + next.legIndex
          + " lat=" + snapshot.latitude
          + " lon=" + snapshot.longitude
          + " accuracy=" + snapshot.accuracyMeters);
      if (!recoveryCoordinator.beginConfirmed(active, next, current, snapshot, recoveryHost)) {
        state = current;
        notifyListeners();
      }
      return;
    }

    deviationSuspected = false;
    recoveryCoordinator.reset();
    state = next;
    notifyListeners();
    observe(active, next, snapshot, nextProgress, false);
    logStateIfChanged(next);
    deviationPolicy.shouldRecalculate(next, snapshot);
  }

  private void observe(
      Route active,
      NavigationState navigationState,
      LocationSnapshot snapshot,
      RouteProgressSnapshot progress,
      boolean deviationEvidence) {
    NavigationObservationPolicy.Observation observation =
        observationPolicy.observe(active, navigationState, snapshot, progress, deviationEvidence);
    latestObservation = observation;
    rawLog("[NAV] OBS " + observation.debugSummary());
    notifyLocationDemandIfChanged();
  }

  private void resetLivePolicies() {
    deviationPolicy.reset();
    observationPolicy.reset();
    progressTracker.reset();
    recoveryCoordinator.reset();
    latestObservation = null;
    deviationSuspected = false;
  }

  public void addListener(Listener listener) {
    if (listener == null) return;
    listeners.add(listener);
    listener.onNavigationChanged(route, state);
  }

  public void removeListener(Listener listener) {
    if (listener != null) listeners.remove(listener);
  }

  public void addLocationDemandListener(LocationDemandListener listener) {
    if (listener == null) return;
    locationDemandListeners.add(listener);
    listener.onNavigationLocationDemandChanged(locationDemand());
  }

  public void removeLocationDemandListener(LocationDemandListener listener) {
    if (listener != null) locationDemandListeners.remove(listener);
  }

  private void notifyListeners() {
    Route currentRoute = route;
    NavigationState currentState = state;
    for (Listener listener : listeners) listener.onNavigationChanged(currentRoute, currentState);
  }

  private void notifyLocationDemandIfChanged() {
    NavigationObservationPolicy.ProviderDemand demand = locationDemand();
    NavigationObservationPolicy.ProviderDemand previous = lastLocationDemand;
    if (demand.equals(previous)) return;
    lastLocationDemand = demand;
    for (LocationDemandListener listener : locationDemandListeners) {
      listener.onNavigationLocationDemandChanged(demand);
    }
    rawLog("[NAV] LOCATION_DEMAND level=" + demand.level
        + " maxSafeGapMs=" + (demand.maxSafeGapMs == Long.MAX_VALUE
            ? "inf"
            : String.valueOf(demand.maxSafeGapMs)));
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
