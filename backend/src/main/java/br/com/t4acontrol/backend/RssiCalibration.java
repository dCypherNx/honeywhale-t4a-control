package br.com.t4acontrol.backend;

/** Learns trustworthy BLE RSSI extrema and derives weighted stable proximity bands. */
final class RssiCalibration {
  static final int REQUIRED_EXTREME_SAMPLES = 2;
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

  private Integer best;
  private Integer worst;
  private boolean bestConfirmed;
  private boolean worstConfirmed;
  private int bestCandidateCount;
  private int worstCandidateCount;
  private Integer bestCandidate;
  private Integer worstCandidate;

  RssiCalibration(Integer persistedBest, Integer persistedWorst) {
    if (validRssi(persistedBest)
        && validRssi(persistedWorst)
        && persistedBest >= persistedWorst) {
      best = persistedBest;
      worst = persistedWorst;
      bestConfirmed = true;
      worstConfirmed = true;
    }
  }

  boolean observe(int rssi) {
    if (!validRssi(rssi)) return false;

    if (best == null || worst == null) {
      best = rssi;
      worst = rssi;
      bestCandidate = rssi;
      worstCandidate = rssi;
      bestCandidateCount = 1;
      worstCandidateCount = 1;
      return false;
    }

    boolean changed = false;

    boolean supportsBest = bestConfirmed ? rssi > best : rssi >= best;
    if (supportsBest) {
      bestCandidate = bestCandidate == null ? rssi : Math.max(bestCandidate, rssi);
      bestCandidateCount++;
      if (bestCandidateCount >= REQUIRED_EXTREME_SAMPLES) {
        int confirmed = bestCandidate;
        if (!bestConfirmed || confirmed > best) {
          best = confirmed;
          changed = true;
        }
        bestConfirmed = true;
        bestCandidate = null;
        bestCandidateCount = 0;
      }
    } else {
      bestCandidate = null;
      bestCandidateCount = 0;
    }

    boolean supportsWorst = worstConfirmed ? rssi < worst : rssi <= worst;
    if (supportsWorst) {
      worstCandidate = worstCandidate == null ? rssi : Math.min(worstCandidate, rssi);
      worstCandidateCount++;
      if (worstCandidateCount >= REQUIRED_EXTREME_SAMPLES) {
        int confirmed = worstCandidate;
        if (!worstConfirmed || confirmed < worst) {
          worst = confirmed;
          changed = true;
        }
        worstConfirmed = true;
        worstCandidate = null;
        worstCandidateCount = 0;
      }
    } else {
      worstCandidate = null;
      worstCandidateCount = 0;
    }

    return changed;
  }

  void resetWindow() {
    bestCandidate = null;
    worstCandidate = null;
    bestCandidateCount = 0;
    worstCandidateCount = 0;
  }

  Snapshot snapshot() {
    Integer confirmedBest = bestConfirmed ? best : null;
    Integer confirmedWorst = worstConfirmed ? worst : null;
    if (confirmedBest == null || confirmedWorst == null) {
      return new Snapshot(confirmedBest, confirmedWorst, null, null, null, null, false);
    }

    double observedSpan = confirmedBest - confirmedWorst;
    double stableWorstRaw = confirmedWorst + observedSpan * WEAK_EDGE_MARGIN;
    double stableSpan = confirmedBest - stableWorstRaw;
    int stableWorst = (int) Math.ceil(stableWorstRaw);

    // RSSI is logarithmic: equal dBm slices do not represent equal distance slices.
    // Keep the near band widest, then medium, with the far band narrowest.
    int shortMin = (int) Math.ceil(confirmedBest - stableSpan * SHORT_BAND_SHARE);
    int mediumMin = (int) Math.ceil(confirmedBest - stableSpan * (SHORT_BAND_SHARE + MEDIUM_BAND_SHARE));
    int longMin = stableWorst;
    boolean ready = observedSpan > 0.0d
        && confirmedBest > shortMin
        && shortMin > mediumMin
        && mediumMin > longMin;
    return new Snapshot(confirmedBest, confirmedWorst, stableWorst, shortMin, mediumMin, longMin, ready);
  }

  private static boolean validRssi(Integer value) {
    return value != null && value < 0 && value >= -127;
  }

  private static boolean validRssi(int value) {
    return value < 0 && value >= -127;
  }
}
