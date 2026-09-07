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
 * Debug-only passive probe for ThingClips low-level XRequest objects.
 *
 * <p>It never sends data and never logs complete payloads. For each distinct request it records
 * transport metadata plus chunk lengths and a short prefix/suffix sufficient to identify the 4.7
 * wire framing while keeping the encrypted body redacted.
 */
public final class ThingBleXRequestProbe {
  private static final String ROOT = "com.thingclips.sdk.bluetooth.pbbpdbb";
  private static final String XREQUEST = "com.thingclips.smart.android.ble.connect.request.XRequest";
  private static final int MAX_DEPTH = 6;
  private static final int MAX_NODES = 500;
  private static final Set<String> EMITTED = ConcurrentHashMap.newKeySet();

  private ThingBleXRequestProbe() {}

  public static void capture(long tMs, Consumer<String> rawLog) {
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    try {
      ClassLoader loader = ThingBleXRequestProbe.class.getClassLoader();
      Class<?> rootClass = Class.forName(ROOT, false, loader);
      Class<?> requestClass = Class.forName(XREQUEST, false, loader);
      Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      ArrayDeque<Node> queue = new ArrayDeque<>();
      for (Field field : rootClass.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers())) enqueue(queue, readField(field, null), 0);
      }

      int visited = 0;
      int found = 0;
      while (!queue.isEmpty() && visited++ < MAX_NODES) {
        Node node = queue.removeFirst();
        Object value = node.value;
        if (value == null || !seen.add(value)) continue;
        if (requestClass.isInstance(value)) {
          found++;
          emitRequest(tMs, value, log);
          continue;
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
          int len = Math.min(Array.getLength(value), 64);
          for (int i = 0; i < len; i++) enqueue(queue, Array.get(value, i), node.depth + 1);
          continue;
        }
        if (!isTraversable(type)) continue;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
          for (Field field : current.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Class<?> ft = field.getType();
            if (ft.isPrimitive() || ft == String.class || ft == byte[].class) continue;
            enqueue(queue, readField(field, value), node.depth + 1);
          }
        }
      }
      log.accept("[BLE/SDKWIRE] tMs=" + tMs + " xRequestsVisible=" + found
          + " uniqueLogged=" + EMITTED.size() + " writes=false completePayloadLogged=false");
    } catch (Throwable error) {
      log.accept("[BLE/SDKWIRE] tMs=" + tMs + " error=" + error.getClass().getSimpleName()
          + " writes=false completePayloadLogged=false");
    }
  }

  private static void emitRequest(long tMs, Object request, Consumer<String> log) {
    Object commandsObj = invokeNoArg(request, "getCommand");
    if (!(commandsObj instanceof byte[][])) return;
    byte[][] commands = (byte[][]) commandsObj;
    int total = 0;
    StringBuilder chunks = new StringBuilder();
    StringBuilder fingerprint = new StringBuilder();
    for (int i = 0; i < commands.length; i++) {
      byte[] chunk = commands[i];
      if (chunk == null) continue;
      total += chunk.length;
      String prefix = edgeHex(chunk, 0, Math.min(6, chunk.length));
      String suffix = edgeHex(chunk, Math.max(0, chunk.length - Math.min(4, chunk.length)), chunk.length);
      if (chunks.length() > 0) chunks.append(',');
      chunks.append(i).append(':').append(chunk.length).append(':').append(prefix).append("..").append(suffix);
      fingerprint.append(i).append('/').append(chunk.length).append('/').append(prefix).append('/').append(suffix).append(';');
    }
    int code = invokeInt(request, "getCode");
    int backCode = invokeInt(request, "getBack_code");
    boolean noRsp = invokeBoolean(request, "isWriteNoRsp");
    String service = safeUuid(invokeNoArg(request, "getServiceUuid"));
    String characteristic = safeUuid(invokeNoArg(request, "getCharacterUuid"));
    String key = code + "|" + backCode + "|" + noRsp + "|" + service + "|" + characteristic + "|" + fingerprint;
    if (!EMITTED.add(key)) return;
    log.accept("[BLE/SDKWIRE_REQ] tMs=" + tMs + " code=" + code + " backCode=" + backCode
        + " writeNoRsp=" + noRsp + " service=" + service + " characteristic=" + characteristic
        + " chunks=" + commands.length + " totalLength=" + total + " edges=" + chunks
        + " writes=false completePayloadLogged=false");
  }

  private static String edgeHex(byte[] data, int start, int end) {
    StringBuilder out = new StringBuilder();
    for (int i = start; i < end; i++) out.append(String.format("%02X", data[i] & 0xff));
    return out.toString();
  }

  private static String safeUuid(Object value) {
    return value instanceof UUID ? value.toString() : "<unknown>";
  }

  private static Object invokeNoArg(Object target, String name) {
    try {
      Method method = target.getClass().getMethod(name);
      return method.invoke(target);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static int invokeInt(Object target, String name) {
    Object value = invokeNoArg(target, name);
    return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
  }

  private static boolean invokeBoolean(Object target, String name) {
    Object value = invokeNoArg(target, name);
    return value instanceof Boolean && (Boolean) value;
  }

  private static Object readField(Field field, Object target) {
    try {
      field.setAccessible(true);
      return field.get(target);
    } catch (Throwable ignored) {
      return null;
    }
  }

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
    final Object value;
    final int depth;
    Node(Object value, int depth) { this.value = value; this.depth = depth; }
  }
}
