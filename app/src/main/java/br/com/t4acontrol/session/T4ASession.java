package br.com.t4acontrol.session;

import br.com.t4acontrol.backend.T4AState;

/**
 * UI-facing session contract.
 *
 * <p>The Android UI depends on this facade instead of owning the domain backend or any provider
 * implementation. The service behind this contract owns the long-lived T4A session.
 */
public interface T4ASession {
  T4ASession EMPTY =
      new T4ASession() {
        @Override public void addListener(Listener listener) {}
        @Override public void removeListener(Listener listener) {}
        @Override public void setUiForeground(boolean foreground) {}
        @Override public void login(String email, String password) {}
        @Override public void scan() {}
        @Override public void pair() {}
        @Override public void publish(String dpId, Object value) {}
        @Override public void setLight(boolean enabled) {}
        @Override public void setAutoLockEnabled(boolean enabled) {}
        @Override public void setAutoLockDistance(String distance) {}
        @Override public void setAutoLightEnabled(boolean enabled) {}
        @Override public void setAutoLightAutoOffEnabled(boolean enabled) {}
        @Override public void setAutoLightThresholdLux(float lux) {}
        @Override public void setBatteryRechargeMinGapHours(int hours) {}
        @Override public void resolveBatteryRecharge(boolean startNewCycle) {}
        @Override public void unpair() {}
      };

  interface Listener {
    void onState(T4AState state);
    void onEvent(String event);
    void onRawLog(String entry);
  }

  void addListener(Listener listener);
  void removeListener(Listener listener);

  /** Adjusts reconnect cadence only; it does not start or stop the underlying session. */
  void setUiForeground(boolean foreground);

  void login(String email, String password);
  void scan();
  void pair();
  void publish(String dpId, Object value);

  /** Manual headlight action. Automatic mode remains enabled and records a temporary override. */
  void setLight(boolean enabled);

  void setAutoLockEnabled(boolean enabled);
  void setAutoLockDistance(String distance);
  void setAutoLightEnabled(boolean enabled);
  void setAutoLightAutoOffEnabled(boolean enabled);
  void setAutoLightThresholdLux(float lux);
  void setBatteryRechargeMinGapHours(int hours);
  void resolveBatteryRecharge(boolean startNewCycle);
  void unpair();
}
