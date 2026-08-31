package br.com.t4acontrol.backend;

/** Learns trustworthy BLE RSSI extrema and derives three equal stable proximity bands. */
final class RssiCalibration {
  static final int REQUIRED_SAMPLES = 3;
  private static final double WEAK_EDGE_MARGIN = 0.10d;

  static final class Snapshot {
    final Integer best;
    final Integer worst;
    final Integer stableWorst;
    final Integer shortMin;
    final Integer mediumMin;
    final Integer longMin;
    final boolean ready;

    Snapshot(
        Integer best,
        Integer worst,
        Integer stableWorst,
        Integer shortMin,
        Integer mediumMin,
        Integer longMin,
        boolean ready) {
      this.best = best;
      this.worst = worst;
      this.stableWorst = stableWorst;
      this.shortMin = shortMin;
      this.mediumMin = mediumMin;
      this.longMin = longMin;
      this.ready = ready;
    }
  }

  private final int[] recent = new int[REQUIRED_SAMPLES];
  private int recentCount;
  private int recentIndex;
  private Integer best;
  private Integer worst;

  RssiCalibration(Integer persistedBest, Integer persistedWorst) {
    if (validRssi(persistedBest)
        && validRssi(persistedWorst)
        && persistedBest >= persistedWorst) {
      best = persistedBest;
      worst = persistedWorst;
    }
  }

  boolean observe(int rssi) {
    if (!validRssi(rssi)) return false;
    recent[recentIndex] = rssi;
    recentIndex = (recentIndex + 1) % REQUIRED_SAMPLES;
    if (recentCount < REQUIRED_SAMPLES) recentCount++;
    if (recentCount < REQUIRED_SAMPLES) return false;

    int median = median3(recent[0], recent[1], recent[2]);
    boolean changed = false;
    if (best == null || worst == null) {
      best = median;
      worst = median;
      changed = true;
    } else {
      if (median > best) {
        best = median;
        changed = true;
      }
      if (median < worst) {
        worst = median;
        changed = true;
      }
    }
    return changed;
  }

  void resetWindow() {
    recentCount = 0;
    recentIndex = 0;
  }

  Snapshot snapshot() {
    if (best == null || worst == null) {
      return new Snapshot(best, worst, null, null, null, null, false);
    }

    double observedSpan = best - worst;
    double stableWorstRaw = worst + observedSpan * WEAK_EDGE_MARGIN;
    double stableSpan = best - stableWorstRaw;
    int stableWorst = (int) Math.ceil(stableWorstRaw);
    int shortMin = (int) Math.ceil(best - stableSpan / 3.0d);
    int mediumMin = (int) Math.ceil(best - (2.0d * stableSpan / 3.0d));
    int longMin = stableWorst;
    boolean ready = observedSpan > 0.0d
        && best > shortMin
        && shortMin > mediumMin
        && mediumMin > longMin;
    return new Snapshot(best, worst, stableWorst, shortMin, mediumMin, longMin, ready);
  }

  private static boolean validRssi(Integer value) {
    return value != null && value < 0 && value >= -127;
  }

  private static boolean validRssi(int value) {
    return value < 0 && value >= -127;
  }

  private static int median3(int a, int b, int c) {
    if (a > b) { int t = a; a = b; b = t; }
    if (b > c) { int t = b; b = c; c = t; }
    if (a > b) { int t = a; a = b; b = t; }
    return b;
  }
}
