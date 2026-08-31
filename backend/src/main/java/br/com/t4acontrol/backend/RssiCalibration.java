package br.com.t4acontrol.backend;

/** Learns trustworthy BLE RSSI extrema and derives weighted stable proximity bands. */
final class RssiCalibration {
  static final int REQUIRED_SAMPLES = 2;
  private static final double WEAK_EDGE_MARGIN = 0.10d;
  private static final double SHORT_BAND_SHARE = 0.50d;
  private static final double MEDIUM_BAND_SHARE = 0.30d;

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

    int pairBest = Math.max(recent[0], recent[1]);
    int pairWorst = Math.min(recent[0], recent[1]);
    boolean changed = false;
    if (best == null || worst == null) {
      best = pairBest;
      worst = pairWorst;
      changed = true;
    } else {
      if (pairBest > best) {
        best = pairBest;
        changed = true;
      }
      if (pairWorst < worst) {
        worst = pairWorst;
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

    // RSSI is logarithmic: equal dBm slices do not represent equal distance slices.
    // Keep the near band widest, then medium, with the far band narrowest.
    int shortMin = (int) Math.ceil(best - stableSpan * SHORT_BAND_SHARE);
    int mediumMin = (int) Math.ceil(best - stableSpan * (SHORT_BAND_SHARE + MEDIUM_BAND_SHARE));
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
}
