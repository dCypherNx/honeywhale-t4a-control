package br.com.t4acontrol.backend.navigation;

/** Provider-neutral geographic point used by route geometry. */
public final class GeoPoint {
  public final double latitude;
  public final double longitude;

  public GeoPoint(double latitude, double longitude) {
    this.latitude = latitude;
    this.longitude = longitude;
  }
}
