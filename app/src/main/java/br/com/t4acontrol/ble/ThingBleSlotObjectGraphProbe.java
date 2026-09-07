package br.com.t4acontrol.ble;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Debug-only passive probe locating unresolved getSecretKey slots in the live SDK object graph. */
public final class ThingBleSlotObjectGraphProbe {
  private static final String CONTROLLER = "com.thingclips.sdk.bluetooth.dpdbqdp";
  private static final int[] TARGET_SLOTS = {5, 14, 15};
  private static final int MAX_DEPTH = 9;
  private static final int MAX_NODES = 1400;
  private static final String[] ROOT_CLASSES = {
      "com.thingclips.sdk.bluetooth.pdbdqqp", "com.thingclips.sdk.bluetooth.pbpdbqp",
      "com.thingclips.sdk.bluetooth.pbbpdbb", "com.thingclips.sdk.bluetooth.bpqppbd",
      "com.thingclips.sdk.bluetooth.dpbbdqq", "com.thingclips.sdk.bluetooth.dpqdpqd",
      "com.thingclips.sdk.bluetooth.bbdqddq", CONTROLLER
  };

  private ThingBleSlotObjectGraphProbe() {}

  public static void capture(long tMs, Consumer<String> rawLog) {
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    try {
      ClassLoader loader = ThingBleSlotObjectGraphProbe.class.getClassLoader();
      Class<?> controllerClass = Class.forName(CONTROLLER, false, loader);
      Object controller = findLiveInstance(loader, controllerClass);
      if (controller == null) {
        log.accept("[BLE/SDKSEC_GRAPH] tMs=" + tMs + " found=false secretsLogged=false");
        return;
      }
      byte[][] targets = new byte[TARGET_SLOTS.length][];
      int ready = 0;
      for (int i = 0; i < TARGET_SLOTS.length; i++) {
        targets[i] = invokeSecretKey(controller, TARGET_SLOTS[i]);
        if (targets[i] != null && targets[i].length > 0) ready++;
      }
      if (ready == 0) {
        log.accept("[BLE/SDKSEC_GRAPH] tMs=" + tMs + " found=true targetSlotsReady=0 secretsLogged=false");
        return;
      }

      Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      ArrayDeque<Node> queue = new ArrayDeque<>();
      queue.add(new Node(controller, 0, "controller"));
      int visited = 0, matches = 0;
      while (!queue.isEmpty() && visited++ < MAX_NODES) {
        Node node = queue.removeFirst();
        Object value = node.value;
        if (value == null || !seen.add(value) || node.depth > MAX_DEPTH) continue;
        Class<?> type = value.getClass();

        if (value instanceof Map<?, ?>) {
          int i = 0;
          for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
            if (i++ >= 96) break;
            enqueue(queue, e.getKey(), node.depth + 1, node.path + ".mapKey");
            enqueue(queue, e.getValue(), node.depth + 1, node.path + ".mapValue");
          }
          continue;
        }
        if (value instanceof Iterable<?>) {
          int i = 0;
          for (Object item : (Iterable<?>) value) {
            if (i++ >= 96) break;
            enqueue(queue, item, node.depth + 1, node.path + "[]");
          }
          continue;
        }
        if (type.isArray()) {
          if (type.getComponentType().isPrimitive()) continue;
          int len = Math.min(Array.getLength(value), 96);
          for (int i = 0; i < len; i++) enqueue(queue, Array.get(value, i), node.depth + 1, node.path + "[]");
          continue;
        }
        if (!isTraversableType(type)) continue;

        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
          for (Field field : current.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Object fieldValue = readField(field, value);
            if (fieldValue == null) continue;
            String path = compactPath(node.path + "." + current.getSimpleName() + "." + field.getName());
            byte[] candidate = bytes(fieldValue);
            if (candidate != null && candidate.length == 16) {
              List<Integer> slotMatches = matchingTargetSlots(candidate, targets);
              if (!slotMatches.isEmpty()) {
                matches += slotMatches.size();
                log.accept("[BLE/SDKSEC_GRAPH_MATCH] tMs=" + tMs
                    + " owner=" + current.getName()
                    + " field=" + field.getName()
                    + " valueType=" + (fieldValue instanceof byte[] ? "byte[]" : "String")
                    + " length=16 slots=" + slotMatches.toString().replace(" ", "")
                    + " path=" + path
                    + " secretsLogged=false");
              }
            }
            if (shouldTraverse(fieldValue)) enqueue(queue, fieldValue, node.depth + 1, path);
          }
        }
      }
      log.accept("[BLE/SDKSEC_GRAPH] tMs=" + tMs + " found=true targetSlotsReady=" + ready
          + " visited=" + visited + " matches=" + matches + " maxDepth=" + MAX_DEPTH
          + " secretValuesCompared=true secretsLogged=false");
    } catch (Throwable e) {
      log.accept("[BLE/SDKSEC_GRAPH] tMs=" + tMs + " error=" + e.getClass().getSimpleName() + " secretsLogged=false");
    }
  }

  private static Object findLiveInstance(ClassLoader loader, Class<?> target) {
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    ArrayDeque<Node> queue = new ArrayDeque<>();
    for (String rootName : ROOT_CLASSES) {
      try {
        Class<?> root = Class.forName(rootName, false, loader);
        for (Field f : root.getDeclaredFields()) if (Modifier.isStatic(f.getModifiers())) enqueue(queue, readField(f, null), 0, rootName);
      } catch (Throwable ignored) {}
    }
    int visited = 0;
    while (!queue.isEmpty() && visited++ < MAX_NODES) {
      Node node = queue.removeFirst(); Object value = node.value;
      if (value == null || !seen.add(value)) continue;
      if (target.isInstance(value)) return value;
      if (node.depth >= MAX_DEPTH) continue;
      if (value instanceof Map<?, ?>) {
        for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) { enqueue(queue, e.getKey(), node.depth + 1, node.path); enqueue(queue, e.getValue(), node.depth + 1, node.path); }
        continue;
      }
      if (value instanceof Iterable<?>) { for (Object item : (Iterable<?>) value) enqueue(queue, item, node.depth + 1, node.path); continue; }
      Class<?> type = value.getClass();
      if (type.isArray()) {
        if (type.getComponentType().isPrimitive()) continue;
        for (int i = 0; i < Math.min(Array.getLength(value), 64); i++) enqueue(queue, Array.get(value, i), node.depth + 1, node.path);
        continue;
      }
      if (!isTraversableType(type)) continue;
      for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
        for (Field f : c.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers()) && shouldTraverseType(f.getType())) enqueue(queue, readField(f, value), node.depth + 1, node.path);
      }
    }
    return null;
  }

  private static List<Integer> matchingTargetSlots(byte[] value, byte[][] targets) {
    List<Integer> out = new ArrayList<>();
    for (int i = 0; i < targets.length; i++) if (equalsBytes(value, targets[i])) out.add(TARGET_SLOTS[i]);
    return out;
  }
  private static boolean equalsBytes(byte[] a, byte[] b) {
    if (a == null || b == null || a.length != b.length) return false;
    int diff = 0; for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i]; return diff == 0;
  }
  private static byte[] bytes(Object value) {
    if (value instanceof byte[]) return (byte[]) value;
    if (value instanceof String) return ((String) value).getBytes(StandardCharsets.UTF_8);
    return null;
  }
  private static byte[] invokeSecretKey(Object controller, int slot) {
    try { Method m = controller.getClass().getMethod("getSecretKey", int.class); Object v = m.invoke(controller, slot); return v instanceof byte[] ? (byte[]) v : null; }
    catch (Throwable ignored) { return null; }
  }
  private static Object readField(Field field, Object target) { try { field.setAccessible(true); return field.get(target); } catch (Throwable ignored) { return null; } }
  private static void enqueue(ArrayDeque<Node> q, Object value, int depth, String path) { if (value != null && depth <= MAX_DEPTH && shouldTraverse(value)) q.addLast(new Node(value, depth, path)); }
  private static boolean shouldTraverse(Object value) { return value != null && shouldTraverseType(value.getClass()); }
  private static boolean shouldTraverseType(Class<?> type) {
    if (type.isPrimitive() || type == String.class || type == byte[].class || Number.class.isAssignableFrom(type) || type == Boolean.class || type.isEnum()) return false;
    String n = type.getName(); return n.startsWith("com.thingclips.") || n.startsWith("java.util.") || n.startsWith("java.util.concurrent.");
  }
  private static boolean isTraversableType(Class<?> type) { return shouldTraverseType(type); }
  private static String compactPath(String path) { return path.length() <= 220 ? path : "..." + path.substring(path.length() - 217); }
  private static final class Node { final Object value; final int depth; final String path; Node(Object v, int d, String p) { value = v; depth = d; path = p; } }
}
