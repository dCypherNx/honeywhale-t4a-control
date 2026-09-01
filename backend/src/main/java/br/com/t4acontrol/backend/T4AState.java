package br.com.t4acontrol.backend;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class T4AState {
  public enum Pairing {
    NO_HOME,
    UNPAIRED,
    SCANNING,
    READY,
    PAIRING,
    PAIRED,
    REMOVING
  }

  public static final class DpInfo {
    public final String code;
    public final String mode;
    public final String type;

    public DpInfo(String code, String mode, String type) {
      this.code = code;
      this.mode = mode;
      this.type = type;
    }
  }

  /** Neutral automatic-headlight snapshot. Android sensor APIs never cross this boundary. */
  public static final class AutoLightState {
    public final boolean enabled;
    public final boolean autoOffEnabled;
    public final boolean screenInteractive;
    public final Float currentLux;
    public final Float observedMinLux;
    public final Float observedMaxLux;
    public final Float thresholdLux;
    public final boolean calibrationReady;

    public AutoLightState(
        boolean enabled,
        boolean autoOffEnabled,
        boolean screenInteractive,
        Float currentLux,
        Float observedMinLux,
        Float observedMaxLux,
        Float thresholdLux,
        boolean calibrationReady) {
      this.enabled = enabled;
      this.autoOffEnabled = autoOffEnabled;
      this.screenInteractive = screenInteractive;
      this.currentLux = currentLux;
      this.observedMinLux = observedMinLux;
      this.observedMaxLux = observedMaxLux;
      this.thresholdLux = thresholdLux;
      this.calibrationReady = calibrationReady;
    }

    public static AutoLightState empty() {
      return new AutoLightState(false, false, false, null, null, null, null, false);
    }
  }

  public final boolean authenticated;
  public final String account;
  public final long homeId;
  public final String homeName;
  public final Pairing pairing;
  public final boolean connected;
  public final String deviceName;
  public final String mac;
  public final int rssi;
  public final Map<String, Object> dps;
  public final Map<String, DpInfo> schema;
  public final Set<String> pendingDps;
  public final boolean autoLockEnabled;
  public final String autoLockDistance;
  public final Integer rssiObservedBest;
  public final Integer rssiObservedWorst;
  public final Integer rssiStableWorst;
  public final Integer rssiShortMin;
  public final Integer rssiMediumMin;
  public final Integer rssiLongMin;
  public final boolean rssiCalibrationReady;
  public final Integer batteryObservedMin;
  public final Integer batteryObservedMax;
  public final Long batteryCycleStartedAt;
  public final int batteryRechargeMinGapHours;
  public final Integer pendingBatteryRechargePercent;
  public final Long pendingBatteryRechargeDetectedAt;
  public final String message;
  public final AutoLightState autoLight;

  /** Compatibility constructor for consumers that do not need RSSI calibration details. */
  public T4AState(
      boolean authenticated,
      String account,
      long homeId,
      String homeName,
      Pairing pairing,
      boolean connected,
      String deviceName,
      String mac,
      int rssi,
      Map<String, Object> dps,
      Map<String, DpInfo> schema,
      Set<String> pendingDps,
      boolean autoLockEnabled,
      String autoLockDistance,
      Integer batteryObservedMin,
      Integer batteryObservedMax,
      Long batteryCycleStartedAt,
      int batteryRechargeMinGapHours,
      Integer pendingBatteryRechargePercent,
      Long pendingBatteryRechargeDetectedAt,
      String message) {
    this(
        authenticated,
        account,
        homeId,
        homeName,
        pairing,
        connected,
        deviceName,
        mac,
        rssi,
        dps,
        schema,
        pendingDps,
        autoLockEnabled,
        autoLockDistance,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        batteryObservedMin,
        batteryObservedMax,
        batteryCycleStartedAt,
        batteryRechargeMinGapHours,
        pendingBatteryRechargePercent,
        pendingBatteryRechargeDetectedAt,
        message,
        AutoLightState.empty());
  }

  /** Compatibility constructor preserving the pre-auto-light full snapshot signature. */
  public T4AState(
      boolean authenticated,
      String account,
      long homeId,
      String homeName,
      Pairing pairing,
      boolean connected,
      String deviceName,
      String mac,
      int rssi,
      Map<String, Object> dps,
      Map<String, DpInfo> schema,
      Set<String> pendingDps,
      boolean autoLockEnabled,
      String autoLockDistance,
      Integer rssiObservedBest,
      Integer rssiObservedWorst,
      Integer rssiStableWorst,
      Integer rssiShortMin,
      Integer rssiMediumMin,
      Integer rssiLongMin,
      boolean rssiCalibrationReady,
      Integer batteryObservedMin,
      Integer batteryObservedMax,
      Long batteryCycleStartedAt,
      int batteryRechargeMinGapHours,
      Integer pendingBatteryRechargePercent,
      Long pendingBatteryRechargeDetectedAt,
      String message) {
    this(
        authenticated,
        account,
        homeId,
        homeName,
        pairing,
        connected,
        deviceName,
        mac,
        rssi,
        dps,
        schema,
        pendingDps,
        autoLockEnabled,
        autoLockDistance,
        rssiObservedBest,
        rssiObservedWorst,
        rssiStableWorst,
        rssiShortMin,
        rssiMediumMin,
        rssiLongMin,
        rssiCalibrationReady,
        batteryObservedMin,
        batteryObservedMax,
        batteryCycleStartedAt,
        batteryRechargeMinGapHours,
        pendingBatteryRechargePercent,
        pendingBatteryRechargeDetectedAt,
        message,
        AutoLightState.empty());
  }

  public T4AState(
      boolean authenticated,
      String account,
      long homeId,
      String homeName,
      Pairing pairing,
      boolean connected,
      String deviceName,
      String mac,
      int rssi,
      Map<String, Object> dps,
      Map<String, DpInfo> schema,
      Set<String> pendingDps,
      boolean autoLockEnabled,
      String autoLockDistance,
      Integer rssiObservedBest,
      Integer rssiObservedWorst,
      Integer rssiStableWorst,
      Integer rssiShortMin,
      Integer rssiMediumMin,
      Integer rssiLongMin,
      boolean rssiCalibrationReady,
      Integer batteryObservedMin,
      Integer batteryObservedMax,
      Long batteryCycleStartedAt,
      int batteryRechargeMinGapHours,
      Integer pendingBatteryRechargePercent,
      Long pendingBatteryRechargeDetectedAt,
      String message,
      AutoLightState autoLight) {
    this.authenticated = authenticated;
    this.account = account;
    this.homeId = homeId;
    this.homeName = homeName;
    this.pairing = pairing;
    this.connected = connected;
    this.deviceName = deviceName;
    this.mac = mac;
    this.rssi = rssi;
    this.dps = Collections.unmodifiableMap(new HashMap<>(dps));
    this.schema = Collections.unmodifiableMap(new HashMap<>(schema));
    this.pendingDps = Collections.unmodifiableSet(new java.util.HashSet<>(pendingDps));
    this.autoLockEnabled = autoLockEnabled;
    this.autoLockDistance = autoLockDistance;
    this.rssiObservedBest = rssiObservedBest;
    this.rssiObservedWorst = rssiObservedWorst;
    this.rssiStableWorst = rssiStableWorst;
    this.rssiShortMin = rssiShortMin;
    this.rssiMediumMin = rssiMediumMin;
    this.rssiLongMin = rssiLongMin;
    this.rssiCalibrationReady = rssiCalibrationReady;
    this.batteryObservedMin = batteryObservedMin;
    this.batteryObservedMax = batteryObservedMax;
    this.batteryCycleStartedAt = batteryCycleStartedAt;
    this.batteryRechargeMinGapHours = batteryRechargeMinGapHours;
    this.pendingBatteryRechargePercent = pendingBatteryRechargePercent;
    this.pendingBatteryRechargeDetectedAt = pendingBatteryRechargeDetectedAt;
    this.message = message;
    this.autoLight = autoLight == null ? AutoLightState.empty() : autoLight;
  }
}
