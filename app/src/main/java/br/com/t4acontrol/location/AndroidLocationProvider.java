package br.com.t4acontrol.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import br.com.t4acontrol.backend.location.LocationSnapshot;
import br.com.t4acontrol.backend.navigation.NavigationCadencePolicy;
import br.com.t4acontrol.navigation.NavigationRuntime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Android location adapter. It is active only while the scooter session asks for tracking. */
public final class AndroidLocationProvider implements AutoCloseable {
  public interface Listener {
    void onLocation(LocationSnapshot snapshot);

    void onUnavailable(String reason);
  }

  private static final long IDLE_INTERVAL_MS = 20_000L;
  private static final float IDLE_MIN_DISTANCE_M = 10f;
  private static final long MOVING_INTERVAL_MS = 2_000L;
  private static final float MOVING_MIN_DISTANCE_M = 2f;
  private static final long MAX_CACHED_AGE_MS = 30_000L;
  private static final double RECONFIGURE_INTERVAL_RATIO = 1.35;

  private final Context context;
  private final LocationManager manager;
  private final Executor executor;
  private final Listener listener;
  private final NavigationRuntime.LocationDemandListener navigationDemandListener;
  private final LocationListener locationListener =
      new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
          accept(location);
        }
      };

  private boolean active;
  private boolean moving;
  private boolean registered;
  private String provider = "";
  private String lastUnavailableReason = "";
  private NavigationCadencePolicy.Decision navigationDemand;
  private RequestSpec appliedRequest;

  public AndroidLocationProvider(Context context, Listener listener) {
    this.context = Objects.requireNonNull(context).getApplicationContext();
    this.listener = Objects.requireNonNull(listener);
    this.manager = this.context.getSystemService(LocationManager.class);
    this.executor = this.context.getMainExecutor();
    this.navigationDemandListener =
        decision -> executor.execute(() -> {
          navigationDemand = decision;
          if (active) reconfigureIfNeeded();
        });
    NavigationRuntime.get().addLocationDemandListener(navigationDemandListener);
  }

  public void setActive(boolean value) {
    if (active == value) return;
    active = value;
    if (active) register(true);
    else unregister();
  }

  public void setMoving(boolean value) {
    if (moving == value) return;
    moving = value;
    if (active) reconfigureIfNeeded();
  }

  /** Re-evaluates runtime permission/provider state after an Activity permission result. */
  public void refreshPermissions() {
    if (active) register(true);
  }

  @Override
  public void close() {
    NavigationRuntime.get().removeLocationDemandListener(navigationDemandListener);
    active = false;
    unregister();
  }

  private void register(boolean acceptCached) {
    unregister();
    if (!active) return;
    if (!hasAnyLocationPermission()) {
      unavailable("permissão de localização ausente");
      return;
    }
    if (manager == null) {
      unavailable("serviço de localização indisponível");
      return;
    }

    provider = selectProvider();
    if (provider.isEmpty()) {
      unavailable("nenhum provedor de localização disponível");
      return;
    }

    try {
      RequestSpec spec = desiredRequest();
      manager.requestLocationUpdates(provider, request(spec), executor, locationListener);
      registered = true;
      appliedRequest = spec;
      lastUnavailableReason = "";
      if (acceptCached) {
        Location cached = manager.getLastKnownLocation(provider);
        if (cached != null
            && Math.abs(System.currentTimeMillis() - cached.getTime()) <= MAX_CACHED_AGE_MS) {
          accept(cached);
        }
      }
    } catch (SecurityException error) {
      unavailable("permissão de localização recusada");
    } catch (RuntimeException error) {
      unavailable("falha ao iniciar localização: " + error.getClass().getSimpleName());
    }
  }

  private void reconfigureIfNeeded() {
    RequestSpec desired = desiredRequest();
    if (registered && !materiallyDifferent(appliedRequest, desired)) return;
    register(false);
  }

  private void unregister() {
    if (manager != null && registered) {
      try {
        manager.removeUpdates(locationListener);
      } catch (RuntimeException ignored) {
        // Best effort; the process/service lifecycle will also release the listener.
      }
    }
    registered = false;
    appliedRequest = null;
  }

  private RequestSpec desiredRequest() {
    NavigationCadencePolicy.Decision demand = navigationDemand;
    if (demand != null) {
      int quality =
          demand.highAccuracy && hasFineLocationPermission()
              ? LocationRequest.QUALITY_HIGH_ACCURACY
              : LocationRequest.QUALITY_BALANCED_POWER_ACCURACY;
      return new RequestSpec(
          demand.locationIntervalMs,
          demand.locationIntervalMs,
          (float) demand.minDistanceMeters,
          quality);
    }

    if (moving) {
      return new RequestSpec(
          MOVING_INTERVAL_MS,
          MOVING_INTERVAL_MS,
          MOVING_MIN_DISTANCE_M,
          hasFineLocationPermission()
              ? LocationRequest.QUALITY_HIGH_ACCURACY
              : LocationRequest.QUALITY_BALANCED_POWER_ACCURACY);
    }
    return new RequestSpec(
        IDLE_INTERVAL_MS,
        IDLE_INTERVAL_MS,
        IDLE_MIN_DISTANCE_M,
        LocationRequest.QUALITY_BALANCED_POWER_ACCURACY);
  }

  private static boolean materiallyDifferent(RequestSpec current, RequestSpec desired) {
    if (current == null) return true;
    if (current.quality != desired.quality) return true;
    if ((current.intervalMs <= NavigationCadencePolicy.MIN_LOCATION_INTERVAL_MS)
        != (desired.intervalMs <= NavigationCadencePolicy.MIN_LOCATION_INTERVAL_MS)) return true;
    long smaller = Math.max(1L, Math.min(current.intervalMs, desired.intervalMs));
    long larger = Math.max(current.intervalMs, desired.intervalMs);
    if ((double) larger / smaller >= RECONFIGURE_INTERVAL_RATIO) return true;
    return Math.abs(current.minDistanceMeters - desired.minDistanceMeters) >= 10f;
  }

  private static LocationRequest request(RequestSpec spec) {
    return new LocationRequest.Builder(spec.intervalMs)
        .setMinUpdateIntervalMillis(spec.minIntervalMs)
        .setMinUpdateDistanceMeters(spec.minDistanceMeters)
        .setQuality(spec.quality)
        .build();
  }

  private String selectProvider() {
    List<String> providers = manager.getAllProviders();
    if (providers.contains(LocationManager.FUSED_PROVIDER)
        && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) return LocationManager.FUSED_PROVIDER;
    if (providers.contains(LocationManager.GPS_PROVIDER)
        && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return LocationManager.GPS_PROVIDER;
    if (providers.contains(LocationManager.NETWORK_PROVIDER)
        && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return LocationManager.NETWORK_PROVIDER;
    return "";
  }

  private void accept(Location location) {
    if (location == null) return;
    double latitude = location.getLatitude();
    double longitude = location.getLongitude();
    if (!Double.isFinite(latitude)
        || !Double.isFinite(longitude)
        || latitude < -90d
        || latitude > 90d
        || longitude < -180d
        || longitude > 180d) return;

    Double gpsSpeedKmh =
        location.hasSpeed() ? Math.max(0d, location.getSpeed() * 3.6d) : null;
    Double bearing = location.hasBearing() ? (double) location.getBearing() : null;
    Double altitude = location.hasAltitude() ? location.getAltitude() : null;
    double accuracy = location.hasAccuracy() ? Math.max(0d, location.getAccuracy()) : 0d;

    LocationSnapshot snapshot =
        new LocationSnapshot(
            latitude,
            longitude,
            accuracy,
            location.getTime() > 0 ? location.getTime() : System.currentTimeMillis(),
            gpsSpeedKmh,
            bearing,
            altitude);
    NavigationRuntime.get().onLocation(snapshot);
    listener.onLocation(snapshot);
  }

  private boolean hasAnyLocationPermission() {
    return hasFineLocationPermission()
        || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  private boolean hasFineLocationPermission() {
    return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  private void unavailable(String reason) {
    if (reason.equals(lastUnavailableReason)) return;
    lastUnavailableReason = reason;
    listener.onUnavailable(reason);
  }

  private static final class RequestSpec {
    final long intervalMs;
    final long minIntervalMs;
    final float minDistanceMeters;
    final int quality;

    RequestSpec(long intervalMs, long minIntervalMs, float minDistanceMeters, int quality) {
      this.intervalMs = intervalMs;
      this.minIntervalMs = minIntervalMs;
      this.minDistanceMeters = minDistanceMeters;
      this.quality = quality;
    }
  }
}
