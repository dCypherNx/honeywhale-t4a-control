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

  public final boolean authenticated;
  public final String account;
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
  public final String message;

  public T4AState(
      boolean authenticated,
      String account,
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
      String message) {
    this.authenticated = authenticated;
    this.account = account;
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
    this.message = message;
  }
}
