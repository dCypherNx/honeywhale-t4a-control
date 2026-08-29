package br.com.t4acontrol.location;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import br.com.t4acontrol.backend.location.LocationSnapshot;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Android location adapter. It is active only while the T4A session asks for tracking. */
public final class AndroidLocationProvider implements AutoCloseable {
  public interface Listener {
    void onLocation(LocationSnapshot snapshot);

    void onUnavailable(String reason);
  }

  private static final long IDLE_INTERVAL_MS = 20_000L;
  private static final long IDLE_MIN_INTERVAL_MS = 20_000L;
  private static final float IDLE_MIN_DISTANCE_M = 0f;
  private static final long MOVING_INTERVAL_MS = 3_000L;
  private static final long MOVING_MIN_INTERVAL_MS = 3_000L;
  private static final float MOVING_MIN_DISTANCE_M = 0f;
  private static final long MAX_CACHED_AGE_MS = 30_000L;

  private final Context context;
  private final LocationManager manager;
  private final Executor executor;
  private final Listener listener;
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

  public AndroidLocationProvider(Context context, Listener listener) {
    this.context = Objects.requireNonNull(context).getApplicationContext();
    this.listener = Objects.requireNonNull(listener);
    this.manager = this.context.getSystemService(LocationManager.class);
    this.executor = this.context.getMainExecutor();
  }

  public void setActive(boolean value) {
    if (active == value) return;
    active = value;
    if (active) register();
    else unregister();
  }

  public void setMoving(boolean value) {
    if (moving == value) return;
    moving = value;
    if (active) register();
  }

  /** Re-evaluates runtime permission/provider state after an Activity permission result. */
  public void refreshPermissions() {
    if (active) register();
  }

  @Override
  public void close() {
    active = false;
    unregister();
  }

  private void register() {
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
      LocationRequest request = request();
      manager.requestLocationUpdates(provider, request, executor, locationListener);
      registered = true;
      lastUnavailableReason = "";
      Location cached = manager.getLastKnownLocation(provider);
      if (cached != null
          && Math.abs(System.currentTimeMillis() - cached.getTime()) <= MAX_CACHED_AGE_MS) accept(cached);
    } catch (SecurityException error) {
      unavailable("permissão de localização recusada");
    } catch (RuntimeException error) {
      unavailable("falha ao iniciar localização: " + error.getClass().getSimpleName());
    }
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
  }

  private LocationRequest request() {
    long interval = moving ? MOVING_INTERVAL_MS : IDLE_INTERVAL_MS;
    long minInterval = moving ? MOVING_MIN_INTERVAL_MS : IDLE_MIN_INTERVAL_MS;
    float minDistance = moving ? MOVING_MIN_DISTANCE_M : IDLE_MIN_DISTANCE_M;
    int quality =
        moving && hasFineLocationPermission()
            ? LocationRequest.QUALITY_HIGH_ACCURACY
            : LocationRequest.QUALITY_BALANCED_POWER_ACCURACY;
    return new LocationRequest.Builder(interval)
        .setMinUpdateIntervalMillis(minInterval)
        .setMinUpdateDistanceMeters(minDistance)
        .setQuality(quality)
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

    listener.onLocation(
        new LocationSnapshot(
            latitude,
            longitude,
            accuracy,
            location.getTime() > 0 ? location.getTime() : System.currentTimeMillis(),
            gpsSpeedKmh,
            bearing,
            altitude));
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
}
