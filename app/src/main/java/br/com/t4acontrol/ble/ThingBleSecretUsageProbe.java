package br.com.t4acontrol.ble;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Debug-only passive probe that maps live byte[] fields back to getSecretKey(int) slots. */
public final class ThingBleSecretUsageProbe {
  private static final String CONTROLLER = "com.thingclips.sdk.bluetooth.dpdbqdp";
  private static final int MAX_DEPTH = 8;
  private static final int MAX_NODES = 1200;
  private static final int MAX_SLOT = 31;
  private static final int MAX_MATCHES = 80;
  private static final String[] ROOT_CLASSES = {
      "com.thingclips.sdk.bluetooth.pdbdqqp", "com.thingclips.sdk.bluetooth.pbpdbqp",
      "com.thingclips.sdk.bluetooth.pbbpdbb", "com.thingclips.sdk.bluetooth.bpqppbd",
      "com.thingclips.sdk.bluetooth.dpbbdqq", "com.thingclips.sdk.bluetooth.dpqdpqd",
      "com.thingclips.sdk.bluetooth.bbdqddq", CONTROLLER
  };

  private ThingBleSecretUsageProbe() {}

  public static void capture(long tMs, Consumer<String> rawLog) {
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    try {
      ClassLoader loader = ThingBleSecretUsageProbe.class.getClassLoader();
      Class<?> controllerClass = Class.forName(CONTROLLER, false, loader);
      Object controller = findLiveInstance(loader, controllerClass);
      if (controller == null) {
        log.accept("[BLE/SDKSEC_USE] tMs=" + tMs + " found=false secretsLogged=false");
        return;
      }

      byte[][] slots = new byte[MAX_SLOT + 1][];
      int populated = 0;
      for (int slot = 0; slot <= MAX_SLOT; slot++) {
        slots[slot] = invokeSecretKey(controller, slot);
        if (slots[slot] != null && slots[slot].length > 0) populated++;
      }

      Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      ArrayDeque<Node> queue = new ArrayDeque<>();
      for (String rootName : ROOT_CLASSES) {
        try {
          Class<?> root = Class.forName(rootName, false, loader);
          for (Field field : root.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            enqueue(queue, readField(field, null), 0, rootName + "." + field.getName());
          }
        } catch (Throwable ignored) {}
      }

      int visited = 0;
      int matches = 0;
      List<String> summaries = new ArrayList<>();
      while (!queue.isEmpty() && visited++ < MAX_NODES && matches < MAX_MATCHES) {
        Node node = queue.removeFirst();
        Object value = node.value;
        if (value == null || !seen.add(value)) continue;
        if (node.depth >= MAX_DEPTH) continue;

        if (value instanceof Map<?, ?>) {
          for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            enqueue(queue, entry.getKey(), node.depth + 1, node.path + "{key}");
            enqueue(queue, entry.getValue(), node.depth + 1, node.path + "{value}");
          }
          continue;
        }
        if (value instanceof Iterable<?>) {
          int i = 0;
          for (Object item : (Iterable<?>) value) enqueue(queue, item, node.depth + 1, node.path + "[" + (i++) + "]");
          continue;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
          if (type.getComponentType().isPrimitive()) continue;
          int len = Math.min(Array.getLength(value), 64);
          for (int i = 0; i < len; i++) enqueue(queue, Array.get(value, i), node.depth + 1, node.path + "[" + i + "]");
          continue;
        }
        if (!isTraversableType(type)) continue;

        Class<?> current = type;
        int hierarchy = 0;
        while (current != null && current != Object.class && hierarchy++ < 8 && matches < MAX_MATCHES) {
          for (Field field : current.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            Object fieldValue = readField(field, value);
            String fieldPath = type.getName() + "." + field.getName();
            if (fieldValue instanceof byte[]) {
              List<Integer> slotMatches = matchingSlots((byte[]) fieldValue, slots);
              if (!slotMatches.isEmpty()) {
                matches++;
                summaries.add(fieldPath + "=" + compact(slotMatches));
              }
              continue;
            }
            if (field.getType().isPrimitive() || field.getType() == String.class) continue;
            enqueue(queue, fieldValue, node.depth + 1, fieldPath);
          }
          current = current.getSuperclass();
        }
      }

      log.accept("[BLE/SDKSEC_USE] tMs=" + tMs
          + " found=true populatedSlots=" + populated
          + " refsVisited=" + visited
          + " matches=" + matches
          + " locations=" + summaries
          + " secretValuesCompared=true secretsLogged=false");
    } catch (Throwable error) {
      log.accept("[BLE/SDKSEC_USE] tMs=" + tMs + " error=" + error.getClass().getSimpleName()
          + " secretsLogged=false");
    }
  }

  private static Object findLiveInstance(ClassLoader loader, Class<?> targetClass) {
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    ArrayDeque<Node> queue = new ArrayDeque<>();
    for (String rootName : ROOT_CLASSES) {
      try {
        Class<?> root = Class.forName(rootName, false, loader);
        for (Field field : root.getDeclaredFields()) if (Modifier.isStatic(field.getModifiers()))
          enqueue(queue, readField(field, null), 0, rootName + "." + field.getName());
      } catch (Throwable ignored) {}
    }
    int visited = 0;
    while (!queue.isEmpty() && visited++ < MAX_NODES) {
      Node node = queue.removeFirst();
      Object value = node.value;
      if (value == null || !seen.add(value)) continue;
      if (targetClass.isInstance(value)) return value;
      if (node.depth >= MAX_DEPTH) continue;
      if (value instanceof Map<?, ?>) {
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
          enqueue(queue, entry.getKey(), node.depth + 1, node.path + "{key}");
          enqueue(queue, entry.getValue(), node.depth + 1, node.path + "{value}");
        }
        continue;
      }
      if (value instanceof Iterable<?>) {
        for (Object item : (Iterable<?>) value) enqueue(queue, item, node.depth + 1, node.path + "[]");
        continue;
      }
      Class<?> type = value.getClass();
      if (type.isArray()) {
        if (type.getComponentType().isPrimitive()) continue;
        int len = Math.min(Array.getLength(value), 64);
        for (int i = 0; i < len; i++) enqueue(queue, Array.get(value, i), node.depth + 1, node.path + "[]");
        continue;
      }
      if (!isTraversableType(type)) continue;
      Class<?> current = type;
      int hierarchy = 0;
      while (current != null && current != Object.class && hierarchy++ < 8) {
        for (Field field : current.getDeclaredFields()) {
          if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
              || field.getType() == String.class || field.getType() == byte[].class) continue;
          enqueue(queue, readField(field, value), node.depth + 1, type.getName() + "." + field.getName());
        }
        current = current.getSuperclass();
      }
    }
    return null;
  }

  private static byte[] invokeSecretKey(Object controller, int slot) {
    try {
      Method method = controller.getClass().getMethod("getSecretKey", int.class);
      Object value = method.invoke(controller, slot);
      return value instanceof byte[] ? (byte[]) value : null;
    } catch (Throwable ignored) { return null; }
  }

  private static List<Integer> matchingSlots(byte[] value, byte[][] slots) {
    if (value == null || value.length == 0) return Collections.emptyList();
    List<Integer> out = new ArrayList<>();
    for (int slot = 0; slot < slots.length; slot++) if (equalsBytes(value, slots[slot])) out.add(slot);
    return out;
  }

  private static boolean equalsBytes(byte[] a, byte[] b) {
    if (a == null || b == null || a.length != b.length) return false;
    int diff = 0;
    for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
    return diff == 0;
  }

  private static String compact(List<Integer> values) { return values.toString().replace(" ", ""); }
  private static Object readField(Field field, Object target) {
    try { field.setAccessible(true); return field.get(target); } catch (Throwable ignored) { return null; }
  }
  private static void enqueue(ArrayDeque<Node> queue, Object value, int depth, String path) {
    if (value == null || depth > MAX_DEPTH) return;
    Class<?> type = value.getClass();
    if (type == String.class || type == byte[].class || type.isPrimitive()
        || Number.class.isAssignableFrom(type) || type == Boolean.class || type.isEnum()) return;
    queue.addLast(new Node(value, depth, path));
  }
  private static boolean isTraversableType(Class<?> type) {
    String name = type.getName();
    return name.startsWith("com.thingclips.") || name.startsWith("java.util.") || name.startsWith("java.util.concurrent.");
  }

  private static final class Node {
    final Object value; final int depth; final String path;
    Node(Object value, int depth, String path) { this.value = value; this.depth = depth; this.path = path; }
  }
}
