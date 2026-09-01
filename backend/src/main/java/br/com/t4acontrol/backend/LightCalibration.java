package br.com.t4acontrol.backend;

/** Learns trustworthy ambient-light extrema using the same two-sample confirmation as RSSI. */
final class LightCalibration {
  static final int REQUIRED_EXTREME_SAMPLES = 2;

  static final class Snapshot {
    final Float minLux;
    final Float maxLux;
    final boolean ready;

    Snapshot(Float minLux, Float maxLux, boolean ready) {
      this.minLux = minLux;
      this.maxLux = maxLux;
      this.ready = ready;
    }
  }

  private Float minLux;
  private Float maxLux;
  private boolean minConfirmed;
  private boolean maxConfirmed;
  private Float minCandidate;
  private Float maxCandidate;
  private int minCandidateCount;
  private int maxCandidateCount;

  LightCalibration(Float persistedMinLux, Float persistedMaxLux) {
    if (validLux(persistedMinLux)
        && validLux(persistedMaxLux)
        && persistedMinLux <= persistedMaxLux) {
      minLux = persistedMinLux;
      maxLux = persistedMaxLux;
      minConfirmed = true;
      maxConfirmed = true;
    }
  }

  boolean observe(float lux) {
    if (!validLux(lux)) return false;

    if (minLux == null || maxLux == null) {
      minLux = lux;
      maxLux = lux;
      minCandidate = lux;
      maxCandidate = lux;
      minCandidateCount = 1;
      maxCandidateCount = 1;
      return false;
    }

    boolean changed = false;

    boolean supportsMin = minConfirmed ? lux < minLux : lux <= minLux;
    if (supportsMin) {
      minCandidate = minCandidate == null ? lux : Math.min(minCandidate, lux);
      minCandidateCount++;
      if (minCandidateCount >= REQUIRED_EXTREME_SAMPLES) {
        float confirmed = minCandidate;
        if (!minConfirmed || confirmed < minLux) {
          minLux = confirmed;
          changed = true;
        }
        minConfirmed = true;
        minCandidate = null;
        minCandidateCount = 0;
      }
    } else {
      minCandidate = null;
      minCandidateCount = 0;
    }

    boolean supportsMax = maxConfirmed ? lux > maxLux : lux >= maxLux;
    if (supportsMax) {
      maxCandidate = maxCandidate == null ? lux : Math.max(maxCandidate, lux);
      maxCandidateCount++;
      if (maxCandidateCount >= REQUIRED_EXTREME_SAMPLES) {
        float confirmed = maxCandidate;
        if (!maxConfirmed || confirmed > maxLux) {
          maxLux = confirmed;
          changed = true;
        }
        maxConfirmed = true;
        maxCandidate = null;
        maxCandidateCount = 0;
      }
    } else {
      maxCandidate = null;
      maxCandidateCount = 0;
    }

    return changed;
  }

  void resetWindow() {
    minCandidate = null;
    maxCandidate = null;
    minCandidateCount = 0;
    maxCandidateCount = 0;
  }

  Snapshot snapshot() {
    Float confirmedMin = minConfirmed ? minLux : null;
    Float confirmedMax = maxConfirmed ? maxLux : null;
    boolean ready = confirmedMin != null && confirmedMax != null && confirmedMax > confirmedMin;
    return new Snapshot(confirmedMin, confirmedMax, ready);
  }

  private static boolean validLux(Float value) {
    return value != null && validLux(value.floatValue());
  }

  private static boolean validLux(float value) {
    return Float.isFinite(value) && value >= 0f;
  }
}
