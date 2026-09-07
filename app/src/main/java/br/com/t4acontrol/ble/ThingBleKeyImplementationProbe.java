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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Debug-only structural probe focused on the implementations behind BLE 4.7 key slots 5/14/15. */
public final class ThingBleKeyImplementationProbe {
  private static final String CONTROLLER = "com.thingclips.sdk.bluetooth.dpdbqdp";
  private static final int MAX_DEPTH = 7;
  private static final int MAX_NODES = 700;
  private static final AtomicBoolean LOGGED = new AtomicBoolean(false);
  private static final String[] ROOT_CLASSES = {
      "com.thingclips.sdk.bluetooth.pdbdqqp", "com.thingclips.sdk.bluetooth.pbpdbqp",
      "com.thingclips.sdk.bluetooth.pbbpdbb", "com.thingclips.sdk.bluetooth.bpqppbd",
      "com.thingclips.sdk.bluetooth.dpbbdqq", "com.thingclips.sdk.bluetooth.dpqdpqd",
      "com.thingclips.sdk.bluetooth.bbdqddq", CONTROLLER
  };

  private ThingBleKeyImplementationProbe() {}

  public static void capture(long tMs, Consumer<String> rawLog) {
    if (LOGGED.get()) return;
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    try {
      ClassLoader loader = ThingBleKeyImplementationProbe.class.getClassLoader();
      Class<?> controllerBase = Class.forName(CONTROLLER, false, loader);
      Object controller = findLiveInstance(loader, controllerBase);
      if (controller == null) return;

      byte[] k5 = invokeSlot(controller, 5);
      byte[] k14 = invokeSlot(controller, 14);
      byte[] k15 = invokeSlot(controller, 15);
      if (!present(k5) || !present(k14) || !present(k15)) return;
      if (!LOGGED.compareAndSet(false, true)) return;

      StringBuilder line = new StringBuilder("[BLE/SDKSEC_IMPL] tMs=").append(tMs)
          .append(" runtimeClass=").append(controller.getClass().getName());
      appendMethodOwner(line, controller.getClass(), "getSecretKey", int.class);
      appendByteArrayMethods(line, controller.getClass());
      line.append(" slot5Length=").append(k5.length)
          .append(" slot14Length=").append(k14.length)
          .append(" slot15Length=").append(k15.length)
          .append(" valuesLogged=false secretsLogged=false");
      log.accept(line.toString());
    } catch (Throwable error) {
      log.accept("[BLE/SDKSEC_IMPL] tMs=" + tMs + " error="
          + error.getClass().getSimpleName() + " secretsLogged=false");
    }
  }

  private static void appendMethodOwner(StringBuilder line, Class<?> runtime, String name,
      Class<?>... params) {
    try {
      Method method = runtime.getMethod(name, params);
      line.append(' ').append(name).append("Owner=")
          .append(method.getDeclaringClass().getName());
    } catch (Throwable ignored) {}
  }

  private static void appendByteArrayMethods(StringBuilder line, Class<?> runtime) {
    int count = 0;
    for (Class<?> type = runtime; type != null && type != Object.class; type = type.getSuperclass()) {
      for (Method method : type.getDeclaredMethods()) {
        if (method.getReturnType() != byte[].class) continue;
        Class<?>[] params = method.getParameterTypes();
        if (!(params.length == 0 || (params.length == 1 && params[0] == int.class))) continue;
        if (count++ >= 24) break;
        line.append(" byteMethod=").append(type.getName()).append('#').append(method.getName())
            .append('/').append(params.length);
      }
      if (count >= 24) break;
    }
    line.append(" byteMethodCount=").append(count);
  }

  private static boolean present(byte[] value) { return value != null && value.length > 0; }

  private static byte[] invokeSlot(Object controller, int slot) {
    try {
      Method method = controller.getClass().getMethod("getSecretKey", int.class);
      Object value = method.invoke(controller, slot);
      return value instanceof byte[] ? (byte[]) value : null;
    } catch (Throwable ignored) { return null; }
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
      Node node = queue.removeFirst(); Object value = node.value;
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
      if (!traversable(type)) continue;
      for (Class<?> current = type; current != null && current != Object.class;
          current = current.getSuperclass()) {
        for (Field field : current.getDeclaredFields()) {
          if (Modifier.isStatic(field.getModifiers())) continue;
          Class<?> ft = field.getType();
          if (ft.isPrimitive() || ft == String.class || ft == byte[].class) continue;
          enqueue(queue, readField(field, value), node.depth + 1);
        }
      }
    }
    return null;
  }

  private static Object readField(Field field, Object target) {
    try { field.setAccessible(true); return field.get(target); }
    catch (Throwable ignored) { return null; }
  }

  private static void enqueue(ArrayDeque<Node> queue, Object value, int depth) {
    if (value == null || depth > MAX_DEPTH) return;
    Class<?> type = value.getClass();
    if (type == String.class || type == byte[].class || Number.class.isAssignableFrom(type)
        || type == Boolean.class || type.isEnum()) return;
    queue.addLast(new Node(value, depth));
  }

  private static boolean traversable(Class<?> type) {
    String name = type.getName();
    return name.startsWith("com.thingclips.") || name.startsWith("java.util.")
        || name.startsWith("java.util.concurrent.");
  }

  private static final class Node {
    final Object value; final int depth;
    Node(Object value, int depth) { this.value = value; this.depth = depth; }
  }
}
