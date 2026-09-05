package br.com.t4acontrol.navigation;

import br.com.t4acontrol.backend.location.LocationSnapshot;
import br.com.t4acontrol.backend.navigation.NavigationRecoveryPolicy;
import br.com.t4acontrol.backend.navigation.NavigationState;
import br.com.t4acontrol.backend.navigation.Route;
import br.com.t4acontrol.backend.navigation.RoutePlanner;
import br.com.t4acontrol.backend.navigation.RouteRecalculator;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns speculative/confirmed route-recovery workflow and its background planning lifecycle. */
final class NavigationRecoveryCoordinator {
  interface Host {
    boolean isEligible(Route activeRoute);

    void log(String value);

    void recovered(Route activeRoute, Route candidate);

    void failed(Route activeRoute, NavigationState fallbackUiState, Exception error);
  }

  private final NavigationRecoveryPolicy policy = new NavigationRecoveryPolicy();
  private final RouteRecalculator recalculator = new RouteRecalculator();
  private final RoutePlanner planner;
  private final AtomicBoolean running = new AtomicBoolean();
  private volatile Speculative task;

  NavigationRecoveryCoordinator(RoutePlanner planner) {
    this.planner = planner;
  }

  boolean isRunning() {
    return running.get();
  }

  void reset() {
    task = null;
    running.set(false);
  }

  void considerPrefetch(
      Route active,
      NavigationState previousUiState,
      NavigationState offRouteState,
      LocationSnapshot snapshot,
      Host host) {
    if (!policy.shouldPrefetch(previousUiState, offRouteState, snapshot)) return;
    Speculative current = task;
    if (current != null
        && current.activeRoute == active
        && current.legIndex == offRouteState.legIndex
        && !current.failed) return;

    Speculative created = new Speculative(
        active,
        offRouteState,
        previousUiState,
        snapshot,
        offRouteState.legIndex,
        snapshot.timestampMs);
    task = created;
    host.log("[NAV] RECOVERY PREWARM START route=" + active.id
        + " leg=" + offRouteState.legIndex
        + " lat=" + snapshot.latitude
        + " lon=" + snapshot.longitude);
    startPlanning(created, host, true);
  }

  boolean beginConfirmed(
      Route active,
      NavigationState offRouteState,
      NavigationState fallbackUiState,
      LocationSnapshot snapshot,
      Host host) {
    if (!running.compareAndSet(false, true)) return false;

    Speculative current = task;
    if (current != null
        && !current.failed
        && current.activeRoute == active
        && policy.canPromote(
            active.id,
            current.legIndex,
            current.startedAtMs,
            current.origin,
            offRouteState,
            snapshot)) {
      current.confirmed = true;
      current.offRouteState = offRouteState;
      current.fallbackUiState = fallbackUiState;
      current.confirmedLocation = snapshot;
      if (current.result != null) {
        promote(current, host);
      } else {
        host.log("[NAV] RECOVERY PREWARM WAIT route=" + active.id
            + " leg=" + offRouteState.legIndex);
      }
      return true;
    }

    task = null;
    Speculative direct = new Speculative(
        active,
        offRouteState,
        fallbackUiState,
        snapshot,
        offRouteState.legIndex,
        snapshot.timestampMs);
    direct.confirmed = true;
    direct.confirmedLocation = snapshot;
    task = direct;
    startPlanning(direct, host, false);
    return true;
  }

  private void startPlanning(Speculative candidate, Host host, boolean prewarm) {
    new Thread(() -> {
      try {
        Route result = recalculator.recalculate(
            candidate.activeRoute,
            candidate.offRouteState,
            candidate.origin,
            planner);
        if (task != candidate || !host.isEligible(candidate.activeRoute)) return;
        candidate.result = result;
        if (prewarm) {
          host.log("[NAV] RECOVERY PREWARM READY route=" + candidate.activeRoute.id
              + " candidate=" + result.id);
        }
        if (candidate.confirmed) promote(candidate, host);
      } catch (Exception error) {
        if (task != candidate || !host.isEligible(candidate.activeRoute)) return;
        candidate.failed = true;
        if (prewarm) {
          host.log("[NAV] RECOVERY PREWARM FAIL route=" + candidate.activeRoute.id
              + " type=" + error.getClass().getSimpleName()
              + " message=" + String.valueOf(error.getMessage()));
        }
        if (candidate.confirmed) {
          task = null;
          running.set(false);
          host.failed(candidate.activeRoute, candidate.fallbackUiState, error);
        }
      }
    }, prewarm ? "navigation-route-prewarm" : "navigation-route-recalculate").start();
  }

  private void promote(Speculative candidate, Host host) {
    if (candidate.result == null
        || task != candidate
        || !candidate.confirmed
        || !host.isEligible(candidate.activeRoute)) return;
    Route result = candidate.result;
    host.log("[NAV] RECOVERY "
        + (candidate.startedAtMs == candidate.origin.timestampMs ? "PROMOTE" : "SUCCESS")
        + " oldRoute=" + candidate.activeRoute.id
        + " newRoute=" + result.id);
    task = null;
    running.set(false);
    host.recovered(candidate.activeRoute, result);
  }

  private static final class Speculative {
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

    Speculative(
        Route activeRoute,
        NavigationState offRouteState,
        NavigationState fallbackUiState,
        LocationSnapshot origin,
        int legIndex,
        long startedAtMs) {
      this.activeRoute = activeRoute;
      this.offRouteState = offRouteState;
      this.fallbackUiState = fallbackUiState;
      this.origin = origin;
      this.confirmedLocation = origin;
      this.legIndex = legIndex;
      this.startedAtMs = startedAtMs;
    }
  }
}
