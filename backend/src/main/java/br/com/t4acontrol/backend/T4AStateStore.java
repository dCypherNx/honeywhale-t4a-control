package br.com.t4acontrol.backend;

/** Persistence boundary for the small set of T4A runtime state stored across app restarts. */
public interface T4AStateStore {
  /** Returns the last live lock value, or null when no trustworthy lock value has been persisted. */
  Boolean rememberedLock();

  void setRememberedLock(boolean value);

  boolean autoLockEnabled();

  void setAutoLockEnabled(boolean enabled);

  /** Returns the stored distance identifier, or null when the default should be used. */
  String autoLockDistance();

  void setAutoLockDistance(String distance);

  Integer rssiObservedBest();

  Integer rssiObservedWorst();

  void setRssiObservation(int best, int worst);

  String bleAddress();

  void setBleAddress(String address);

  void clearBleAddress();

  Integer batteryObservedMin();

  Integer batteryObservedMax();

  Long batteryCycleStartedAt();

  void setBatteryObservation(int min, int max, long startedAt);

  Integer batteryLastLivePercent();

  Long batteryLastLiveAt();

  void setBatteryLastLive(int percent, long observedAt);

  Integer batteryRechargeMinGapHours();

  void setBatteryRechargeMinGapHours(int hours);

  /** Automatic-headlight settings and learned light-sensor range. Defaults preserve old stores/tests. */
  default boolean autoLightEnabled() { return false; }

  default void setAutoLightEnabled(boolean enabled) {}

  default boolean autoLightAutoOffEnabled() { return false; }

  default void setAutoLightAutoOffEnabled(boolean enabled) {}

  default Float lightObservedMinLux() { return null; }

  default Float lightObservedMaxLux() { return null; }

  default void setLightObservation(float minLux, float maxLux) {}

  default Float autoLightThresholdLux() { return null; }

  default void setAutoLightThresholdLux(float lux) {}
}
