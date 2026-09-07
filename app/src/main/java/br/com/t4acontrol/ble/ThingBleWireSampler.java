package br.com.t4acontrol.ble;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Debug-only high-frequency sampler for transient ThingClips BLE TX objects.
 *
 * <p>Samples only the pbbpdbb singleton object graph at shallow depth. No write is performed and
 * complete payloads are never logged; only request metadata and short chunk edges are emitted.
 */
public final class ThingBleWireSampler {
  private static final String ROOT = "com.thingclips.sdk.bluetooth.pbbpdbb";
  private static final String XREQUEST = "com.thingclips.smart.android.ble.connect.request.XRequest";
  private static final String CHUNK_HELPER = "com.thingclips.sdk.bluetooth.dpppbbd";
  private static final long SAMPLE_INTERVAL_MS = 10L;
  private static final long SAMPLE_WINDOW_MS = 8000L;
  private static final int MAX_DEPTH = 4;
  private static final int MAX_NODES = 180;
  private static final Set<String> EMITTED = ConcurrentHashMap.newKeySet();

  private ThingBleWireSampler() {}

  public static void start(Consumer<String> rawLog) {
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    Thread sampler = new Thread(() -> sampleLoop(log), "t4a-sdk-wire-sampler");
    sampler.setDaemon(true);
    sampler.start();
  }

  private static void sampleLoop(Consumer<String> log) {
    long started = System.nanoTime();
    int samples = 0;
    int requestHits = 0;
    int helperHits = 0;
    log.accept("[BLE/SDKWIRE_FAST] START intervalMs=" + SAMPLE_INTERVAL_MS + " windowMs="
        + SAMPLE_WINDOW_MS + " writes=false completePayloadLogged=false");
    while ((System.nanoTime() - started) / 1_000_000L <= SAMPLE_WINDOW_MS) {
      Snapshot result = sample((System.nanoTime() - started) / 1_000_000L, log);
      samples++;
      requestHits += result.requests;
      helperHits += result.helpers;
      try { Thread.sleep(SAMPLE_INTERVAL_MS); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
    }
    log.accept("[BLE/SDKWIRE_FAST] FINISH samples=" + samples + " requestHits=" + requestHits
        + " helperHits=" + helperHits + " uniqueLogged=" + EMITTED.size()
        + " writes=false completePayloadLogged=false");
  }

  private static Snapshot sample(long tMs, Consumer<String> log) {
    int requests = 0;
    int helpers = 0;
    try {
      ClassLoader loader = ThingBleWireSampler.class.getClassLoader();
      Class<?> rootClass = Class.forName(ROOT, false, loader);
      Class<?> requestClass = Class.forName(XREQUEST, false, loader);
      Class<?> helperClass = Class.forName(CHUNK_HELPER, false, loader);
      Object singleton = readStaticField(rootClass, "qddqppb");
      if (singleton == null) return new Snapshot(0, 0);

      Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      ArrayDeque<Node> queue = new ArrayDeque<>();
      queue.add(new Node(singleton, 0));
      int visited = 0;
      while (!queue.isEmpty() && visited++ < MAX_NODES) {
        Node node = queue.removeFirst();
        Object value = node.value;
        if (value == null || !seen.add(value)) continue;
        if (requestClass.isInstance(value)) {
          requests++;
          emitRequest(tMs, value, log);
          continue;
        }
        if (helperClass.isInstance(value)) {
          helpers++;
          emitHelper(tMs, value, log);
        }
        if (node.depth >= MAX_DEPTH) continue;
        if (value instanceof Map<?, ?>) {
          for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            enqueue(queue, entry.getKey(), node.depth + 1);
            enqueue(queue, entry.getValue(), node.depth + 1);
          }
          continue;
        }
        if (value instanceof Iterable<?>) {
          for (Object item : (Iterable<?>) value) enqueue(queue, item, node.depth + 1);
          continue;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
          if (type.getComponentType().isPrimitive()) continue;
          int len = Math.min(Array.getLength(value), 32);
          for (int i = 0; i < len; i++) enqueue(queue, Array.get(value, i), node.depth + 1);
          continue;
        }
        if (!isTraversable(type)) continue;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
          for (Field field : current.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Class<?> ft = field.getType();
            if (ft.isPrimitive() || ft == String.class || ft == byte[].class || ft == byte[][].class) continue;
            enqueue(queue, readField(field, value), node.depth + 1);
          }
        }
      }
    } catch (Throwable ignored) {}
    return new Snapshot(requests, helpers);
  }

  private static void emitRequest(long tMs, Object request, Consumer<String> log) {
    Object commandsObj = invokeNoArg(request, "getCommand");
    if (!(commandsObj instanceof byte[][])) return;
    byte[][] commands = (byte[][]) commandsObj;
    int code = invokeInt(request, "getCode");
    int backCode = invokeInt(request, "getBack_code");
    boolean noRsp = invokeBoolean(request, "isWriteNoRsp");
    String service = safeUuid(invokeNoArg(request, "getServiceUuid"));
    String characteristic = safeUuid(invokeNoArg(request, "getCharacterUuid"));
    String chunks = summarize(commands);
    String key = "R|" + code + '|' + backCode + '|' + noRsp + '|' + service + '|' + characteristic + '|' + chunks;
    if (!EMITTED.add(key)) return;
    log.accept("[BLE/SDKWIRE_FAST_REQ] tMs=" + tMs + " code=" + code + " backCode=" + backCode
        + " writeNoRsp=" + noRsp + " service=" + service + " characteristic=" + characteristic
        + " chunks=" + commands.length + " edges=" + chunks
        + " writes=false completePayloadLogged=false");
  }

  private static void emitHelper(long tMs, Object helper, Consumer<String> log) {
    Object raw = readNamedField(helper, "bdpdqbp");
    if (!(raw instanceof byte[][])) return;
    byte[][] chunks = (byte[][]) raw;
    Object index = readNamedField(helper, "pdqppqb");
    String summary = summarize(chunks);
    String key = "H|" + index + '|' + summary;
    if (!EMITTED.add(key)) return;
    log.accept("[BLE/SDKWIRE_FAST_HELPER] tMs=" + tMs + " chunkCount=" + chunks.length
        + " index=" + (index instanceof Number ? index : "<unknown>") + " edges=" + summary
        + " writes=false completePayloadLogged=false");
  }

  private static String summarize(byte[][] chunks) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < chunks.length; i++) {
      byte[] chunk = chunks[i];
      if (chunk == null) continue;
      if (out.length() > 0) out.append(',');
      out.append(i).append(':').append(chunk.length).append(':')
          .append(edgeHex(chunk, 0, Math.min(6, chunk.length))).append("..")
          .append(edgeHex(chunk, Math.max(0, chunk.length - Math.min(4, chunk.length)), chunk.length));
    }
    return out.toString();
  }

  private static String edgeHex(byte[] data, int start, int end) {
    StringBuilder out = new StringBuilder();
    for (int i = start; i < end; i++) out.append(String.format("%02X", data[i] & 0xff));
    return out.toString();
  }

  private static Object readStaticField(Class<?> type, String name) {
    try { return readField(type.getDeclaredField(name), null); }
    catch (Throwable ignored) { return null; }
  }

  private static Object readNamedField(Object target, String name) {
    if (target == null) return null;
    for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
      try { return readField(c.getDeclaredField(name), target); }
      catch (NoSuchFieldException ignored) {}
      catch (Throwable ignored) { return null; }
    }
    return null;
  }

  private static Object readField(Field field, Object target) {
    try { field.setAccessible(true); return field.get(target); }
    catch (Throwable ignored) { return null; }
  }

  private static Object invokeNoArg(Object target, String name) {
    try { Method method = target.getClass().getMethod(name); return method.invoke(target); }
    catch (Throwable ignored) { return null; }
  }

  private static int invokeInt(Object target, String name) {
    Object value = invokeNoArg(target, name);
    return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
  }

  private static boolean invokeBoolean(Object target, String name) {
    Object value = invokeNoArg(target, name);
    return value instanceof Boolean && (Boolean) value;
  }

  private static String safeUuid(Object value) { return value instanceof UUID ? value.toString() : "<unknown>"; }

  private static void enqueue(ArrayDeque<Node> queue, Object value, int depth) {
    if (value == null || depth > MAX_DEPTH) return;
    Class<?> type = value.getClass();
    if (type == String.class || type == byte[].class || Number.class.isAssignableFrom(type)
        || type == Boolean.class || type.isEnum()) return;
    queue.addLast(new Node(value, depth));
  }

  private static boolean isTraversable(Class<?> type) {
    String name = type.getName();
    return name.startsWith("com.thingclips.") || name.startsWith("java.util.")
        || name.startsWith("java.util.concurrent.");
  }

  private static final class Node {
    final Object value; final int depth;
    Node(Object value, int depth) { this.value = value; this.depth = depth; }
  }

  private static final class Snapshot {
    final int requests; final int helpers;
    Snapshot(int requests, int helpers) { this.requests = requests; this.helpers = helpers; }
  }
}
