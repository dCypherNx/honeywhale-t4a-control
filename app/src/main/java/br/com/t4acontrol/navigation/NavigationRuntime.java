package br.com.t4acontrol.navigation;

import android.content.Context;
import br.com.t4acontrol.backend.location.LocationSnapshot;
import br.com.t4acontrol.backend.navigation.NavigationDeviationPolicy;
import br.com.t4acontrol.backend.navigation.NavigationEngine;
import br.com.t4acontrol.backend.navigation.NavigationObservationPolicy;
import br.com.t4acontrol.backend.navigation.NavigationRecoveryPolicy;
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

  public interface LocationDemandListener {
    void onNavigationLocationDemandChanged(NavigationObservationPolicy.ProviderDemand demand);
  }

  private static final NavigationRuntime INSTANCE = new NavigationRuntime();

  public static NavigationRuntime get() {
    return INSTANCE;
  }

  private final NavigationEngine engine = new NavigationEngine();
  private final NavigationDeviationPolicy deviationPolicy = new NavigationDeviationPolicy();
  private final NavigationRecoveryPolicy recoveryPolicy = new NavigationRecoveryPolicy();
  private final NavigationObservationPolicy observationPolicy = new NavigationObservationPolicy();
  private final RouteRecalculator routeRecalculator = new RouteRecalculator();
  private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
  private final Set<LocationDemandListener> locationDemandListeners = new CopyOnWriteArraySet<>();
  private final AtomicBoolean restoreStarted = new AtomicBoolean();
  private final AtomicBoolean recalculationRunning = new AtomicBoolean();
  private volatile Route route;
  private volatile NavigationState state = NavigationState.noRoute();
  private volatile NavigationState pausedFromState;
  private volatile SpeculativeRecovery speculativeRecovery;
  private volatile String lastLoggedStateSignature = "";
  private volatile Double guidanceSpeedKmh;
  private volatile LocationSnapshot guidanceLocation;
  private volatile boolean deviationSuspected;
  private volatile NavigationObservationPolicy.Observation latestObservation;
  private volatile NavigationObservationPolicy.ProviderDemand lastLocationDemand;

  private NavigationRuntime() {}

  public Route route() {
    return route;
  }

  public NavigationState state() {
    return state;
  }

  /** Last GPS speed observed by navigation, used to time visual guidance. */
  public Double guidanceSpeedKmh() {
    return guidanceSpeedKmh;
  }

  /** Last GPS fix observed by navigation, used to disambiguate visual guidance. */
  public LocationSnapshot guidanceLocation() {
    return guidanceLocation;
  }

  /** Latest provider-neutral observation demand. */
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
    speculativeRecovery = null;
    state = value == null ? NavigationState.noRoute() : NavigationState.ready(value);
    deviationPolicy.reset();
    observationPolicy.reset();
    latestObservation = null;
    deviationSuspected = false;
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
    speculativeRecovery = null;
    state = value == null ? NavigationState.noRoute() : NavigationState.paused(value, pausedFromState);
    deviationPolicy.reset();
    observationPolicy.reset();
    latestObservation = null;
    deviationSuspected = false;
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
    speculativeRecovery = null;
    state = NavigationState.paused(active, current);
    deviationPolicy.reset();
    observationPolicy.reset();
    latestObservation = null;
    deviationSuspected = false;
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
    speculativeRecovery = null;
    deviationPolicy.reset();
    observationPolicy.reset();
    latestObservation = null;
    deviationSuspected = false;
    notifyListeners();
    notifyLocationDemandIfChanged();
    logStateIfChanged(state);
    rawLog("[NAV] SESSION RESUMED route=" + active.id);
  }

  /** Ends the navigation session and removes the navigation panel. Saved-route concepts are separate. */
  public void end(Context context) {
    Route active = route;
    if (context != null) new SharedPreferencesRouteReferenceStore(context).clear();
    route = null;
    pausedFromState = null;
    speculativeRecovery = null;
    state = NavigationState.noRoute();
    deviationPolicy.reset();
    observationPolicy.reset();
    latestObservation = null;
    deviationSuspected = false;
    recalculationRunning.set(false);
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

    // Every supplied fix is observed and processed. Sampling economy belongs to the provider demand;
    // runtime no longer invents an independent fixed-time throttle that can hide useful evidence.
    guidanceSpeedKmh = snapshot.gpsSpeedKmh;
    guidanceLocation = snapshot;

    if (recalculationRunning.get()) {
      observe(active, state, snapshot, true);
      return;
    }

    NavigationState previousUiState = state;
    NavigationState next = engine.update(active, previousUiState, snapshot);

    // OFF_ROUTE is evidence, not a user-facing step. The first credible episode immediately pushes
    // observation demand to CONTINUOUS while the normal multi-sample policy still controls when a
    // visible reroute is committed.
    if (next.status == NavigationState.Status.OFF_ROUTE) {
      deviationSuspected = true;
      observe(active, next, snapshot, true);
      logStateIfChanged(next);
      boolean confirmed = deviationPolicy.shouldRecalculate(next, snapshot);
      if (!confirmed) {
        if (recoveryPolicy.shouldPrefetch(previousUiState, next, snapshot)) {
          startSpeculativeRecovery(active, next, snapshot);
        }
        return;
      }
      if (!recalculationRunning.compareAndSet(false, true)) return;

      state = NavigationState.recalculating(active, next);
      notifyListeners();
      observe(active, state, snapshot, true);
      logStateIfChanged(state);
      rawLog("[NAV] RECALC START route=" + active.id
          + " leg=" + next.legIndex
          + " lat=" + snapshot.latitude
          + " lon=" + snapshot.longitude
          + " accuracy=" + snapshot.accuracyMeters);
      startConfirmedRecovery(active, next, previousUiState, snapshot);
      return;
    }

    deviationSuspected = false;
    speculativeRecovery = null;
    state = next;
    notifyListeners();
    observe(active, next, snapshot, false);
    logStateIfChanged(next);
    deviationPolicy.shouldRecalculate(next, snapshot); // resets any previous deviation evidence
  }

  private void observe(
      Route active,
      NavigationState navigationState,
      LocationSnapshot snapshot,
      boolean deviationEvidence) {
    NavigationObservationPolicy.Observation observation =
        observationPolicy.observe(active, navigationState, snapshot, deviationEvidence);
    latestObservation = observation;
    rawLog("[NAV] OBS " + observation.debugSummary());
    notifyLocationDemandIfChanged();
  }

  /**
   * Starts one speculative OSRM request for the current deviation episode. It never changes visible
   * navigation by itself. If the rider returns to the route, the candidate is discarded.
   */
  private void startSpeculativeRecovery(
      Route active,
      NavigationState offRouteState,
      LocationSnapshot snapshot) {
    SpeculativeRecovery current = speculativeRecovery;
    if (current != null
        && current.activeRoute == active
        && current.legIndex == offRouteState.legIndex
        && !current.failed) {
      return;
    }

    SpeculativeRecovery task = new SpeculativeRecovery(
        active,
        offRouteState,
        snapshot,
        offRouteState.legIndex,
        snapshot.timestampMs);
    speculativeRecovery = task;
    rawLog("[NAV] RECOVERY PREWARM START route=" + active.id
        + " leg=" + offRouteState.legIndex
        + " lat=" + snapshot.latitude
        + " lon=" + snapshot.longitude);

    new Thread(() -> {
      try {
        Route candidate = routeRecalculator.recalculate(
            active,
            offRouteState,
            snapshot,
            new OsrmRoutePlanner());
        if (speculativeRecovery != task || route != active) return;
        task.result = candidate;
        rawLog("[NAV] RECOVERY PREWARM READY route=" + active.id
            + " candidate=" + candidate.id);
        if (task.confirmed) promoteSpeculativeRecovery(task);
      } catch (Exception error) {
        if (speculativeRecovery != task || route != active) return;
        task.failed = true;
        rawLog("[NAV] RECOVERY PREWARM FAIL route=" + active.id
            + " type=" + error.getClass().getSimpleName()
            + " message=" + String.valueOf(error.getMessage()));
        if (task.confirmed) {
          speculativeRecovery = null;
          startRecalculation(active, task.offRouteState, task.fallbackUiState, task.confirmedLocation);
        }
      }
    }, "navigation-route-prewarm").start();
  }

  private void startConfirmedRecovery(
      Route active,
      NavigationState offRouteState,
      NavigationState fallbackUiState,
      LocationSnapshot snapshot) {
    SpeculativeRecovery task = speculativeRecovery;
    if (task != null
        && !task.failed
        && task.activeRoute == active
        && recoveryPolicy.canPromote(
            active.id,
            task.legIndex,
            task.startedAtMs,
            task.origin,
            offRouteState,
            snapshot)) {
      task.confirmed = true;
      task.offRouteState = offRouteState;
      task.fallbackUiState = fallbackUiState;
      task.confirmedLocation = snapshot;
      if (task.result != null) {
        promoteSpeculativeRecovery(task);
      } else {
        rawLog("[NAV] RECOVERY PREWARM WAIT route=" + active.id
            + " leg=" + offRouteState.legIndex);
      }
      return;
    }

    speculativeRecovery = null;
    startRecalculation(active, offRouteState, fallbackUiState, snapshot);
  }

  private void promoteSpeculativeRecovery(SpeculativeRecovery task) {
    Route active = task.activeRoute;
    Route candidate = task.result;
    if (candidate == null
        || speculativeRecovery != task
        || route != active
        || !task.confirmed
        || state.status == NavigationState.Status.PAUSED) {
      return;
    }
    rawLog("[NAV] RECOVERY PREWARM PROMOTE oldRoute=" + active.id
        + " newRoute=" + candidate.id
        + " savedNetworkWait=true");
    speculativeRecovery = null;
    setRoute(candidate);
    recalculationRunning.set(false);
  }

  private void startRecalculation(
      Route active,
      NavigationState offRouteState,
      NavigationState fallbackUiState,
      LocationSnapshot snapshot) {
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
          deviationSuspected = false;
          state = fallbackUiState;
          observationPolicy.reset();
          latestObservation = null;
          notifyListeners();
          notifyLocationDemandIfChanged();
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

  private static final class SpeculativeRecovery {
    final Route activeRoute;
    final LocationSnapshot origin;
    final int legIndex;
    final long startedAtMs;
    volatile NavigationState offRouteState;
    volatile NavigationState fallbackUiState;
    volatile LocationSnapshot confirmedLocation;
    volatile Route result;
    volatile boolean confirmed;
    volatile boolean failed;

    SpeculativeRecovery(
        Route activeRoute,
        NavigationState offRouteState,
        LocationSnapshot origin,
        int legIndex,
        long startedAtMs) {
      this.activeRoute = activeRoute;
      this.offRouteState = offRouteState;
      this.fallbackUiState = offRouteState;
      this.origin = origin;
      this.confirmedLocation = origin;
      this.legIndex = legIndex;
      this.startedAtMs = startedAtMs;
    }
  }
}
