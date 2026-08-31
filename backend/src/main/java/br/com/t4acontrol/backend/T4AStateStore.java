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
}
