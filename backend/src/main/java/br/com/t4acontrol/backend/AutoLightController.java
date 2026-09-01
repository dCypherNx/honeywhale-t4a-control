package br.com.t4acontrol.backend;

/**
 * Neutral automatic-headlight policy.
 *
 * <p>Sensor acquisition and screen-state detection remain Android concerns. This class receives only
 * neutral lux samples and an interactive-screen flag, learns trustworthy extrema, and decides when a
 * headlight command is allowed.
 */
public final class AutoLightController implements AutoCloseable {
  public static final long DARK_HOLD_MS = 5000L;

  public interface Listener {
    void onAutomaticCommand(boolean enabled, float lux, float thresholdLux);
    void onRawLog(String entry);
  }

  private final T4AStateStore stateStore;
  private final Scheduler scheduler;
  private final Listener listener;
  private final LightCalibration calibration;

  private boolean screenInteractive;
  private boolean controlAvailable;
  private Float currentLux;
  private boolean currentLightOn;
  private Boolean manualOverrideDarkSide;
  private boolean manualOverridePendingSide;
  private boolean darkHoldScheduled;

  private final Runnable darkHold = new Runnable() {
    @Override public void run() {
      darkHoldScheduled = false;
      Float threshold = thresholdLux();
      if (!screenInteractive
          || !controlAvailable
          || !stateStore.autoLightEnabled()
          || currentLux == null
          || threshold == null
          || currentLux > threshold
          || currentLightOn
          || isManualOverrideFor(true)) {
        return;
      }
      currentLightOn = true;
      listener.onRawLog("[APP] AUTO_LIGHT action=on lux=" + formatLux(currentLux)
          + " threshold=" + formatLux(threshold) + " holdMs=" + DARK_HOLD_MS);
      listener.onAutomaticCommand(true, currentLux, threshold);
    }
  };

  public AutoLightController(T4AStateStore stateStore, Scheduler scheduler, Listener listener) {
    this.stateStore = stateStore;
    this.scheduler = scheduler;
    this.listener = listener;
    this.calibration =
        new LightCalibration(stateStore.lightObservedMinLux(), stateStore.lightObservedMaxLux());
  }

  public void setScreenInteractive(boolean interactive) {
    if (screenInteractive == interactive) return;
    screenInteractive = interactive;
    if (!interactive) {
      currentLux = null;
      cancelDarkHold();
      calibration.resetWindow();
      listener.onRawLog("[APP] AUTO_LIGHT screen=off sampling=disabled");
    } else {
      listener.onRawLog("[APP] AUTO_LIGHT screen=on sampling=enabled");
    }
  }

  public void setControlAvailable(boolean available) {
    controlAvailable = available;
    if (!available) cancelDarkHold();
  }

  public void onLux(float lux) {
    if (!screenInteractive || !validLux(lux)) return;
    currentLux = lux;

    if (calibration.observe(lux)) {
      LightCalibration.Snapshot learned = calibration.snapshot();
      if (learned.minLux != null && learned.maxLux != null) {
        stateStore.setLightObservation(learned.minLux, learned.maxLux);
        listener.onRawLog("[APP] AUTO_LIGHT calibration min=" + formatLux(learned.minLux)
            + " max=" + formatLux(learned.maxLux));
      }
    }

    Boolean dark = currentDarkSide();
    if (manualOverridePendingSide && dark != null) {
      manualOverrideDarkSide = dark;
      manualOverridePendingSide = false;
    } else if (manualOverrideDarkSide != null
        && dark != null
        && manualOverrideDarkSide.booleanValue() != dark.booleanValue()) {
      manualOverrideDarkSide = null;
    }

    evaluate();
  }

  public void setLightState(boolean enabled) {
    currentLightOn = enabled;
  }

  public void onManualLightChanged(boolean enabled) {
    currentLightOn = enabled;
    cancelDarkHold();
    Boolean dark = currentDarkSide();
    if (dark == null) {
      manualOverrideDarkSide = null;
      manualOverridePendingSide = true;
    } else {
      manualOverrideDarkSide = dark;
      manualOverridePendingSide = false;
    }
    listener.onRawLog("[APP] AUTO_LIGHT manual=" + (enabled ? "on" : "off")
        + " overrideSide=" + (dark == null ? "pending" : (dark ? "dark" : "bright")));
  }

  public void setEnabled(boolean enabled) {
    stateStore.setAutoLightEnabled(enabled);
    cancelDarkHold();
    listener.onRawLog("[APP] AUTO_LIGHT enabled=" + enabled
        + " threshold=" + nullableLux(thresholdLux()));
    if (enabled) evaluate();
  }

  public void setAutoOffEnabled(boolean enabled) {
    stateStore.setAutoLightAutoOffEnabled(enabled);
    listener.onRawLog("[APP] AUTO_LIGHT autoOff=" + enabled);
    if (enabled) evaluate();
  }

  public void setThresholdLux(float lux) {
    LightCalibration.Snapshot learned = calibration.snapshot();
    if (!learned.ready || learned.minLux == null || learned.maxLux == null || !validLux(lux)) return;
    float clamped = Math.max(learned.minLux, Math.min(learned.maxLux, lux));
    stateStore.setAutoLightThresholdLux(clamped);
    cancelDarkHold();
    listener.onRawLog("[APP] AUTO_LIGHT threshold=" + formatLux(clamped)
        + " range=" + formatLux(learned.minLux) + ".." + formatLux(learned.maxLux));
    evaluate();
  }

  public T4AState.AutoLightState snapshot() {
    LightCalibration.Snapshot learned = calibration.snapshot();
    return new T4AState.AutoLightState(
        stateStore.autoLightEnabled(),
        stateStore.autoLightAutoOffEnabled(),
        screenInteractive,
        screenInteractive ? currentLux : null,
        learned.minLux,
        learned.maxLux,
        thresholdLux(),
        learned.ready);
  }

  @Override
  public void close() {
    cancelDarkHold();
    scheduler.cancelAll();
  }

  private void evaluate() {
    Float threshold = thresholdLux();
    if (!screenInteractive
        || !controlAvailable
        || !stateStore.autoLightEnabled()
        || currentLux == null
        || threshold == null) {
      cancelDarkHold();
      return;
    }

    boolean dark = currentLux <= threshold;
    if (isManualOverrideFor(dark)) {
      cancelDarkHold();
      return;
    }

    if (dark) {
      if (currentLightOn) {
        cancelDarkHold();
        return;
      }
      scheduleDarkHold();
      return;
    }

    cancelDarkHold();
    if (stateStore.autoLightAutoOffEnabled() && currentLightOn) {
      currentLightOn = false;
      listener.onRawLog("[APP] AUTO_LIGHT action=off lux=" + formatLux(currentLux)
          + " threshold=" + formatLux(threshold));
      listener.onAutomaticCommand(false, currentLux, threshold);
    }
  }

  private Boolean currentDarkSide() {
    Float threshold = thresholdLux();
    if (!screenInteractive || currentLux == null || threshold == null) return null;
    return currentLux <= threshold;
  }

  private boolean isManualOverrideFor(boolean dark) {
    return manualOverrideDarkSide != null && manualOverrideDarkSide.booleanValue() == dark;
  }

  private void scheduleDarkHold() {
    if (darkHoldScheduled) return;
    darkHoldScheduled = true;
    scheduler.postDelayed(darkHold, DARK_HOLD_MS);
  }

  private void cancelDarkHold() {
    if (!darkHoldScheduled) return;
    scheduler.cancel(darkHold);
    darkHoldScheduled = false;
  }

  private Float thresholdLux() {
    LightCalibration.Snapshot learned = calibration.snapshot();
    if (!learned.ready || learned.minLux == null || learned.maxLux == null) return null;
    Float stored = stateStore.autoLightThresholdLux();
    if (stored != null && validLux(stored)) {
      return Math.max(learned.minLux, Math.min(learned.maxLux, stored));
    }

    // Logarithmic midpoint matches the logarithmic UI slider and preserves useful low-light precision.
    double logMin = Math.log1p(learned.minLux);
    double logMax = Math.log1p(learned.maxLux);
    return (float) Math.expm1((logMin + logMax) / 2.0d);
  }

  private static boolean validLux(float lux) {
    return Float.isFinite(lux) && lux >= 0f;
  }

  private static String nullableLux(Float lux) {
    return lux == null ? "unavailable" : formatLux(lux);
  }

  private static String formatLux(float lux) {
    return String.format(java.util.Locale.ROOT, "%.1f", lux);
  }
}
