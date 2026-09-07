package br.com.t4acontrol.ble;

import br.com.t4acontrol.backend.T4AContracts;
import br.com.t4acontrol.backend.T4ATransport;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Debug transport decorator that emits the ThingClips protocol map at the first real connect. */
public final class SdkIntrospectionTransport implements T4ATransport {
  /*
   * f161 showed that the useful worker only became visible at 1500 ms, essentially
   * at the same instant the SDK reported CONNECTED. Sample much more densely around
   * that transition and keep watching briefly afterwards so short-lived protocol
   * workers have a better chance of appearing in a Java stack snapshot.
   */
  private static final long[] TRACE_DELAYS_MS = {
      0L, 25L, 75L, 150L, 300L, 600L, 900L, 1050L, 1150L, 1250L, 1325L,
      1375L, 1425L, 1475L, 1500L, 1525L, 1550L, 1600L, 1700L, 1850L,
      2100L, 2500L, 3000L, 4000L
  };

  private final T4ATransport delegate;
  private final Consumer<String> rawLog;

  public SdkIntrospectionTransport(T4ATransport delegate, Consumer<String> rawLog) {
    this.delegate = delegate;
    this.rawLog = rawLog == null ? ignored -> {} : rawLog;
  }

  @Override public void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener) {
    delegate.attach(device, listener);
  }

  @Override public void detach() { delegate.detach(); }

  @Override public void connect(T4AContracts.Device device) {
    ThingBleProtocolIntrospector.inspect(rawLog);
    rawLog.accept("[BLE/SDKTRACE] START mode=thread_stack_dense_session_window classesOnly=true valuesRead=false secretsLogged=false");
    delegate.connect(device);
    traceRuntimeWorkers();
  }

  private void traceRuntimeWorkers() {
    Thread tracer = new Thread(() -> {
      Set<String> emitted = new HashSet<>();
      long previousDelay = 0L;
      for (long delay : TRACE_DELAYS_MS) {
        long sleepMs = Math.max(0L, delay - previousDelay);
        previousDelay = delay;
        if (sleepMs > 0L) {
          try {
            Thread.sleep(sleepMs);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            rawLog.accept("[BLE/SDKTRACE] STOP reason=interrupted");
            return;
          }
        }
        captureRuntimeSnapshot(delay, emitted);
      }
      rawLog.accept("[BLE/SDKTRACE] FINISH uniqueFrames=" + emitted.size()
          + " valuesRead=false secretsLogged=false");
    }, "t4a-sdk-trace");
    tracer.setDaemon(true);
    tracer.start();
  }

  private void captureRuntimeSnapshot(long delayMs, Set<String> emitted) {
    int matchedThreads = 0;
    int newFrames = 0;
    try {
      for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
        Thread thread = entry.getKey();
        StackTraceElement[] stack = entry.getValue();
        boolean threadMatched = false;
        for (StackTraceElement frame : stack) {
          String className = frame.getClassName();
          if (!isRelevantRuntimeClass(className)) continue;
          threadMatched = true;
          String signature = className + "#" + frame.getMethodName();
          if (emitted.add(signature)) {
            newFrames++;
            rawLog.accept("[BLE/SDKTRACE] FRAME tMs=" + delayMs
                + " thread=" + safeThreadName(thread.getName())
                + " class=" + className
                + " method=" + frame.getMethodName()
                + " line=" + frame.getLineNumber());
          }
        }
        if (threadMatched) matchedThreads++;
      }
      rawLog.accept("[BLE/SDKTRACE] SNAPSHOT tMs=" + delayMs
          + " matchedThreads=" + matchedThreads + " newFrames=" + newFrames);
    } catch (Throwable error) {
      rawLog.accept("[BLE/SDKTRACE] SNAPSHOT_ERROR tMs=" + delayMs
          + " error=" + error.getClass().getSimpleName());
    }
  }

  private static boolean isRelevantRuntimeClass(String className) {
    return className.startsWith("com.thingclips.sdk.ble.")
        || className.startsWith("com.thingclips.sdk.bluetooth.")
        || className.startsWith("com.thingclips.smart.android.ble.")
        || className.startsWith("android.bluetooth.BluetoothGatt");
  }

  private static String safeThreadName(String value) {
    if (value == null || value.isBlank()) return "<unnamed>";
    return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
  }

  @Override public boolean isConnected(String deviceId) { return delegate.isConnected(deviceId); }

  @Override public T4AContracts.Device cachedDevice(String deviceId) {
    return delegate.cachedDevice(deviceId);
  }

  @Override public void publish(String deviceId, Map<String, Object> dps,
      T4AContracts.ResultCallback callback) {
    delegate.publish(deviceId, dps, callback);
  }

  @Override public void readRssi(String mac, T4AContracts.RssiCallback callback) {
    delegate.readRssi(mac, callback);
  }

  @Override public void destroy() { delegate.destroy(); }
}
