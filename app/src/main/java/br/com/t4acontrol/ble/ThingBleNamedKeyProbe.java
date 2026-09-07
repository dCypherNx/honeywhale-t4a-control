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

/** Debug-only probe mapping named ThingClips key getters to public getSecretKey slots. */
public final class ThingBleNamedKeyProbe {
  private static final String ENGINE = "com.thingclips.sdk.bluetooth.bdpddpb";
  private static final int MAX_DEPTH = 7;
  private static final int MAX_NODES = 700;
  private static final int MAX_SLOT = 31;
  private static final String[] NAMED_METHODS = {
      "getSecretKey1", "getSecretKey1Random", "getSecretKey2", "getSecretKey4",
      "getSecretKey5", "getSecretKey11", "getSecretKey11Random", "getSecretKey12",
      "getSecretKey14", "getSecretKey15"
  };
  private static final String[] ROOT_CLASSES = {
      "com.thingclips.sdk.bluetooth.pdbdqqp", "com.thingclips.sdk.bluetooth.pbpdbqp",
      "com.thingclips.sdk.bluetooth.pbbpdbb", "com.thingclips.sdk.bluetooth.bpqppbd",
      "com.thingclips.sdk.bluetooth.dpbbdqq", "com.thingclips.sdk.bluetooth.dpqdpqd",
      "com.thingclips.sdk.bluetooth.bbdqddq", "com.thingclips.sdk.bluetooth.dpdbqdp", ENGINE
  };

  private ThingBleNamedKeyProbe() {}

  public static void capture(long tMs, Consumer<String> rawLog) {
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    try {
      ClassLoader loader = ThingBleNamedKeyProbe.class.getClassLoader();
      Class<?> engineClass = Class.forName(ENGINE, false, loader);
      Object engine = findLiveInstance(loader, engineClass);
      if (engine == null) {
        log.accept("[BLE/SDKSEC_NAMED] tMs=" + tMs + " found=false secretsLogged=false");
        return;
      }

      byte[][] slots = new byte[MAX_SLOT + 1][];
      for (int slot = 0; slot <= MAX_SLOT; slot++) slots[slot] = invokeSlot(engine, slot);

      StringBuilder line = new StringBuilder("[BLE/SDKSEC_NAMED] tMs=").append(tMs)
          .append(" found=true class=").append(engine.getClass().getName());
      for (String methodName : NAMED_METHODS) {
        byte[] value = invokeNamed(engine, methodName);
        line.append(' ').append(methodName).append("Length=").append(value == null ? 0 : value.length)
            .append(' ').append(methodName).append("Slots=").append(matchingSlots(value, slots));
      }
      line.append(" secretValuesCompared=true secretsLogged=false");
      log.accept(line.toString());
    } catch (Throwable error) {
      log.accept("[BLE/SDKSEC_NAMED] tMs=" + tMs + " error="
          + error.getClass().getSimpleName() + " secretsLogged=false");
    }
  }

  private static Object findLiveInstance(ClassLoader loader, Class<?> targetClass) {
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    ArrayDeque<Node> queue = new ArrayDeque<>();
    for (String rootName : ROOT_CLASSES) {
      try {
        Class<?> root = Class.forName(rootName, false, loader);
        for (Field field : root.getDeclaredFields()) {
          if (Modifier.isStatic(field.getModifiers())) enqueue(queue, readField(field, null), 0);
        }
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
      if (!isTraversableType(type)) continue;
      Class<?> current = type;
      int hierarchy = 0;
      while (current != null && current != Object.class && hierarchy++ < 8) {
        for (Field field : current.getDeclaredFields()) {
          if (Modifier.isStatic(field.getModifiers())) continue;
          Class<?> ft = field.getType();
          if (ft.isPrimitive() || ft == String.class || ft == byte[].class) continue;
          enqueue(queue, readField(field, value), node.depth + 1);
        }
        current = current.getSuperclass();
      }
    }
    return null;
  }

  private static byte[] invokeNamed(Object engine, String name) {
    try {
      Method method = engine.getClass().getDeclaredMethod(name);
      method.setAccessible(true);
      Object value = method.invoke(engine);
      return value instanceof byte[] ? (byte[]) value : null;
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static byte[] invokeSlot(Object engine, int slot) {
    try {
      Method method = engine.getClass().getMethod("getSecretKey", int.class);
      Object value = method.invoke(engine, slot);
      return value instanceof byte[] ? (byte[]) value : null;
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static String matchingSlots(byte[] value, byte[][] slots) {
    if (value == null || value.length == 0) return "[]";
    List<Integer> matches = new ArrayList<>();
    for (int slot = 0; slot < slots.length; slot++) {
      if (equalsBytes(value, slots[slot])) matches.add(slot);
    }
    return matches.toString().replace(" ", "");
  }

  private static boolean equalsBytes(byte[] a, byte[] b) {
    if (a == null || b == null || a.length != b.length) return false;
    int diff = 0;
    for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
    return diff == 0;
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
    if (type == String.class || type == byte[].class || type.isPrimitive()
        || Number.class.isAssignableFrom(type) || type == Boolean.class || type.isEnum()) return;
    queue.addLast(new Node(value, depth));
  }

  private static boolean isTraversableType(Class<?> type) {
    String name = type.getName();
    return name.startsWith("com.thingclips.")
        || name.startsWith("java.util.")
        || name.startsWith("java.util.concurrent.");
  }

  private static final class Node {
    final Object value;
    final int depth;
    Node(Object value, int depth) { this.value = value; this.depth = depth; }
  }
}
