package br.com.t4acontrol.ble;

import br.com.t4acontrol.backend.T4AContracts;
import br.com.t4acontrol.backend.T4ATransport;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Debug transport decorator focused on the active ThingClips BLE 4.7 handshake. */
public final class SdkIntrospectionTransport implements T4ATransport {
  private static final long[] TRACE_DELAYS_MS = {
      0L, 25L, 75L, 150L, 300L, 600L, 900L, 1050L,
      1075L, 1100L, 1125L, 1150L, 1175L, 1200L, 1225L, 1250L, 1325L,
      1375L, 1425L, 1475L, 1500L, 1525L, 1550L, 1600L, 1700L, 1850L,
      2100L, 2500L, 3000L, 4000L
  };
  private static final long ADAPTIVE_TRACE_LIMIT_MS = 15000L;
  private static final long ADAPTIVE_POLL_MS = 250L;

  private final T4ATransport delegate;
  private final Consumer<String> rawLog;

  public SdkIntrospectionTransport(T4ATransport delegate, Consumer<String> rawLog) {
    this.delegate = delegate;
    this.rawLog = rawLog == null ? ignored -> {} : rawLog;
  }

  @Override public void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener) { delegate.attach(device, listener); }
  @Override public void detach() { delegate.detach(); }

  @Override public void connect(T4AContracts.Device device) {
    ThingBleProtocolIntrospector.inspect(rawLog);
    rawLog.accept("[BLE/SDKTRACE] START mode=v47_targeted_dense_plus_adaptive classesOnly=true valuesRead=false secretsLogged=false");
    rawLog.accept("[BLE/SDKSESSION] START mode=sanitized_runtime_negotiation objectRefsRead=true secretValuesRead=false secretsLogged=false");
    rawLog.accept("[BLE/SDKSEC] START mode=v47_unresolved_slots_5_14_15 objectRefsRead=true secretValuesRead=false secretsLogged=false");
    rawLog.accept("[BLE/SDKSEC_ANCHOR] START basis=md5Login,md5Srand unresolvedSlots=5,14,15 maxDerivationDepth=3 writes=false secretsLogged=false");
    rawLog.accept("[BLE/SDKSEC_AUTH] START target=AuthKeyParam correlationsOnly=true writes=false secretsLogged=false");
    rawLog.accept("[BLE/SDKSEC_USE] START targetSlots=5,14,15 liveObjectGraph=true writes=false secretsLogged=false");
    rawLog.accept("[BLE/SDKSEC_IMPL] START targetSlots=5,14,15 structuralOnly=true writes=false secretsLogged=false");
    rawLog.accept("[BLE/SDKSEC_GRAPH] START targetSlots=5,14,15 maxDepth=9 writes=false secretsLogged=false");
    rawLog.accept("[BLE/SDKWIRE] START source=active_path payload=edges_only writes=false completePayloadLogged=false");

    // f176 proved that sampling only pbbpdbb.qddqppb misses the active queue/helper graph.
    // Start from all known active TX roots before the SDK connect call.
    ThingBleWirePathProbe.start(rawLog);
    delegate.connect(device);
    traceRuntimeWorkers(device == null ? null : device.id);
  }

  private void traceRuntimeWorkers(String deviceId) {
    Thread tracer = new Thread(() -> {
      Set<String> emitted = new HashSet<>();
      long previousDelay = 0L;
      for (long delay : TRACE_DELAYS_MS) {
        long sleepMs = Math.max(0L, delay - previousDelay);
        previousDelay = delay;
        if (!sleepQuietly(sleepMs)) return;
        captureRuntimeSnapshot(delay, emitted);
        if (shouldProbeSession(delay)) captureSession(delay);
      }
      boolean connectedCaptured = false;
      for (long delay = previousDelay + ADAPTIVE_POLL_MS; delay <= ADAPTIVE_TRACE_LIMIT_MS; delay += ADAPTIVE_POLL_MS) {
        if (!sleepQuietly(ADAPTIVE_POLL_MS)) return;
        captureRuntimeSnapshot(delay, emitted);
        boolean connected = deviceId != null && delegate.isConnected(deviceId);
        rawLog.accept("[BLE/SDKTRACE] ADAPTIVE tMs=" + delay + " connected=" + connected);
        if (connected) {
          captureSession(delay); connectedCaptured = true;
          if (!sleepQuietly(100L)) return; captureSession(delay + 100L);
          if (!sleepQuietly(200L)) return; captureSession(delay + 300L);
          break;
        }
      }
      rawLog.accept("[BLE/SDKTRACE] FINISH uniqueFrames=" + emitted.size() + " adaptiveConnectedCaptured=" + connectedCaptured + " valuesRead=false secretsLogged=false");
      rawLog.accept("[BLE/SDKSESSION] FINISH valuesLogged=safe_only secretsLogged=false");
      rawLog.accept("[BLE/SDKSEC] FINISH valuesLogged=safe_only secretValuesRead=false secretsLogged=false");
      rawLog.accept("[BLE/SDKSEC_ANCHOR] FINISH unresolvedSlots=5,14,15 writes=false secretsLogged=false");
      rawLog.accept("[BLE/SDKSEC_AUTH] FINISH writes=false secretsLogged=false");
      rawLog.accept("[BLE/SDKSEC_USE] FINISH targetSlots=5,14,15 writes=false secretsLogged=false");
      rawLog.accept("[BLE/SDKSEC_IMPL] FINISH targetSlots=5,14,15 writes=false secretsLogged=false");
      rawLog.accept("[BLE/SDKSEC_GRAPH] FINISH targetSlots=5,14,15 writes=false secretsLogged=false");
      rawLog.accept("[BLE/SDKWIRE] FINISH writes=false completePayloadLogged=false");
    }, "t4a-sdk-trace");
    tracer.setDaemon(true); tracer.start();
  }

  private boolean sleepQuietly(long sleepMs) {
    if (sleepMs <= 0L) return true;
    try { Thread.sleep(sleepMs); return true; }
    catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      rawLog.accept("[BLE/SDKTRACE] STOP reason=interrupted");
      rawLog.accept("[BLE/SDKSESSION] STOP reason=interrupted secretsLogged=false");
      rawLog.accept("[BLE/SDKSEC] STOP reason=interrupted secretsLogged=false");
      rawLog.accept("[BLE/SDKSEC_ANCHOR] STOP reason=interrupted secretsLogged=false");
      rawLog.accept("[BLE/SDKSEC_AUTH] STOP reason=interrupted secretsLogged=false");
      rawLog.accept("[BLE/SDKSEC_USE] STOP reason=interrupted secretsLogged=false");
      rawLog.accept("[BLE/SDKSEC_IMPL] STOP reason=interrupted secretsLogged=false");
      rawLog.accept("[BLE/SDKSEC_GRAPH] STOP reason=interrupted secretsLogged=false");
      rawLog.accept("[BLE/SDKWIRE] STOP reason=interrupted completePayloadLogged=false");
      return false;
    }
  }

  private void captureSession(long delayMs) {
    ThingBleLiveSessionProbe.capture(delayMs, rawLog);
    ThingBleSecurityRuntimeProbe.capture(delayMs, rawLog);
    ThingBleNamedKeyProbe.capture(delayMs, rawLog);

    if (delayMs >= 1700L) {
      ThingBleAnchoredKeyProbe.capture(delayMs, rawLog);
      ThingBleAuthKeyParamProbe.capture(delayMs, rawLog);
      ThingBleSecretUsageProbe.capture(delayMs, rawLog);
      ThingBleKeyImplementationProbe.capture(delayMs, rawLog);
      ThingBleSlotObjectGraphProbe.capture(delayMs, rawLog);
      ThingBleXRequestProbe.capture(delayMs, rawLog);
    }
  }

  private static boolean shouldProbeSession(long delayMs) {
    return delayMs == 900L || (delayMs >= 1050L && delayMs <= 1250L && delayMs % 25L == 0L)
        || delayMs == 1325L || delayMs == 1425L || delayMs == 1500L || delayMs == 1550L
        || delayMs == 1700L || delayMs == 2100L || delayMs == 3000L;
  }

  private void captureRuntimeSnapshot(long delayMs, Set<String> emitted) {
    int matchedThreads = 0, newFrames = 0;
    try {
      for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
        Thread thread = entry.getKey(); StackTraceElement[] stack = entry.getValue(); boolean threadMatched = false;
        for (StackTraceElement frame : stack) {
          String className = frame.getClassName(); if (!isRelevantRuntimeClass(className)) continue;
          threadMatched = true; String signature = className + "#" + frame.getMethodName();
          if (emitted.add(signature)) {
            newFrames++;
            rawLog.accept("[BLE/SDKTRACE] FRAME tMs=" + delayMs + " thread=" + safeThreadName(thread.getName())
                + " class=" + className + " method=" + frame.getMethodName() + " line=" + frame.getLineNumber());
          }
        }
        if (threadMatched) matchedThreads++;
      }
      rawLog.accept("[BLE/SDKTRACE] SNAPSHOT tMs=" + delayMs + " matchedThreads=" + matchedThreads + " newFrames=" + newFrames);
    } catch (Throwable error) {
      rawLog.accept("[BLE/SDKTRACE] SNAPSHOT_ERROR tMs=" + delayMs + " error=" + error.getClass().getSimpleName());
    }
  }

  private static boolean isRelevantRuntimeClass(String className) {
    return className.startsWith("com.thingclips.sdk.ble.") || className.startsWith("com.thingclips.sdk.bluetooth.")
        || className.startsWith("com.thingclips.smart.android.ble.") || className.startsWith("android.bluetooth.BluetoothGatt");
  }
  private static String safeThreadName(String value) {
    if (value == null || value.isBlank()) return "<unnamed>";
    return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
  }

  @Override public boolean isConnected(String deviceId) { return delegate.isConnected(deviceId); }
  @Override public T4AContracts.Device cachedDevice(String deviceId) { return delegate.cachedDevice(deviceId); }
  @Override public void publish(String deviceId, Map<String, Object> dps, T4AContracts.ResultCallback callback) { delegate.publish(deviceId, dps, callback); }
  @Override public void readRssi(String mac, T4AContracts.RssiCallback callback) { delegate.readRssi(mac, callback); }
  @Override public void destroy() { delegate.destroy(); }
}
