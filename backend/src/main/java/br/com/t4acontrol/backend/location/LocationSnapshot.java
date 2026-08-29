package br.com.t4acontrol.backend.location;

/** Immutable provider-neutral geographic snapshot for telemetry and future navigation. */
public final class LocationSnapshot {
  public final double latitude;
  public final double longitude;
  public final double accuracyMeters;
  public final long timestampMs;
  public final Double gpsSpeedKmh;
  public final Double bearingDegrees;
  public final Double altitudeMeters;

  public LocationSnapshot(
      double latitude,
      double longitude,
      double accuracyMeters,
      long timestampMs,
      Double gpsSpeedKmh,
      Double bearingDegrees,
      Double altitudeMeters) {
    this.latitude = latitude;
    this.longitude = longitude;
    this.accuracyMeters = accuracyMeters;
    this.timestampMs = timestampMs;
    this.gpsSpeedKmh = gpsSpeedKmh;
    this.bearingDegrees = bearingDegrees;
    this.altitudeMeters = altitudeMeters;
  }
}
