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

/** Debug-only probe for the live protocol 4.x AuthKeyParam. No secret bytes are logged. */
public final class ThingBleAuthKeyParamProbe {
  private static final String AUTH_PARAM = "com.thingclips.sdk.ble.core.protocol.entity.AuthKeyParam";
  private static final String CONTROLLER = "com.thingclips.sdk.bluetooth.dpdbqdp";
  private static final int MAX_DEPTH = 8;
  private static final int MAX_NODES = 900;
  private static final int MAX_SLOT = 31;
  private static final String[] ROOT_CLASSES = {
      "com.thingclips.sdk.bluetooth.pdbdqqp", "com.thingclips.sdk.bluetooth.pbpdbqp",
      "com.thingclips.sdk.bluetooth.pbbpdbb", "com.thingclips.sdk.bluetooth.bpqppbd",
      "com.thingclips.sdk.bluetooth.dpbbdqq", "com.thingclips.sdk.bluetooth.dpqdpqd",
      "com.thingclips.sdk.bluetooth.bbdqddq", CONTROLLER
  };

  private ThingBleAuthKeyParamProbe() {}

  public static void capture(long tMs, Consumer<String> rawLog) {
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    try {
      ClassLoader loader = ThingBleAuthKeyParamProbe.class.getClassLoader();
      Class<?> authClass = Class.forName(AUTH_PARAM, false, loader);
      Class<?> controllerClass = Class.forName(CONTROLLER, false, loader);
      Object authParam = findLiveInstance(loader, authClass);
      Object controller = findLiveInstance(loader, controllerClass);
      if (authParam == null) {
        log.accept("[BLE/SDKSEC_AUTH] tMs=" + tMs + " found=false controllerAvailable="
            + (controller != null) + " secretsLogged=false");
        return;
      }

      Object encryptedAuthKey = readNamedField(authParam, "encryptedAuthKey");
      Object random = readNamedField(authParam, "random");
      Object connectParam = controller == null ? null : invokeNoArg(controller, "getConnectParam");
      Object deviceInfo = controller == null ? null : invokeNoArg(controller, "getDeviceInfo");
      Object rep = controller == null ? null : readNamedField(controller, "rep");

      Object loginKey = readNamedField(connectParam, "loginKey");
      Object loginKeyComplete = readNamedField(connectParam, "loginKeyComplete");
      Object secretKey = readNamedField(connectParam, "secretKey");
      Object authKey = readNamedField(deviceInfo, "authKey");
      Object srand = readNamedField(rep, "srand");

      byte[][] slots = new byte[MAX_SLOT + 1][];
      if (controller != null) {
        for (int i = 0; i <= MAX_SLOT; i++) slots[i] = invokeSecretKey(controller, i);
      }

      log.accept("[BLE/SDKSEC_AUTH] tMs=" + tMs
          + " found=true class=" + authParam.getClass().getName()
          + " encryptedAuthKeyLength=" + secretLength(encryptedAuthKey)
          + " encryptedAuthKeySlots=" + matchingSlots(encryptedAuthKey, slots)
          + " encryptedAuthKeyEqAuthKey=" + equalsFlexible(encryptedAuthKey, authKey)
          + " encryptedAuthKeyEqLoginKey=" + equalsFlexible(encryptedAuthKey, loginKey)
          + " encryptedAuthKeyEqLoginKeyComplete=" + equalsFlexible(encryptedAuthKey, loginKeyComplete)
          + " encryptedAuthKeyEqSecretKey=" + equalsFlexible(encryptedAuthKey, secretKey)
          + " encryptedAuthKeyEqSrand=" + equalsFlexible(encryptedAuthKey, srand)
          + " randomLength=" + secretLength(random)
          + " randomSlots=" + matchingSlots(random, slots)
          + " randomEqSrand=" + equalsFlexible(random, srand)
          + " randomEqAuthKey=" + equalsFlexible(random, authKey)
          + " randomEqLoginKey=" + equalsFlexible(random, loginKey)
          + " secretValuesCompared=true secretsLogged=false");
    } catch (Throwable error) {
      log.accept("[BLE/SDKSEC_AUTH] tMs=" + tMs + " error="
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
      for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
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

  private static byte[] invokeSecretKey(Object controller, int slot) {
    try {
      Method method = controller.getClass().getMethod("getSecretKey", int.class);
      Object value = method.invoke(controller, slot);
      return value instanceof byte[] ? (byte[]) value : null;
    } catch (Throwable ignored) { return null; }
  }

  private static Object invokeNoArg(Object target, String methodName) {
    if (target == null) return null;
    try { return target.getClass().getMethod(methodName).invoke(target); }
    catch (Throwable ignored) { return null; }
  }

  private static Object readNamedField(Object target, String name) {
    if (target == null) return null;
    for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
      try { return readField(type.getDeclaredField(name), target); }
      catch (NoSuchFieldException ignored) {}
      catch (Throwable ignored) { return null; }
    }
    return null;
  }

  private static Object readField(Field field, Object target) {
    try { field.setAccessible(true); return field.get(target); }
    catch (Throwable ignored) { return null; }
  }

  private static String matchingSlots(Object value, byte[][] slots) {
    byte[] bytes = secretBytes(value);
    if (bytes == null || bytes.length == 0) return "[]";
    List<Integer> matches = new ArrayList<>();
    for (int i = 0; i < slots.length; i++) if (equalsBytes(bytes, slots[i])) matches.add(i);
    return matches.toString().replace(" ", "");
  }

  private static boolean equalsFlexible(Object a, Object b) {
    byte[] aa = secretBytes(a), bb = secretBytes(b);
    return equalsBytes(aa, bb);
  }

  private static byte[] secretBytes(Object value) {
    if (value instanceof byte[]) return (byte[]) value;
    if (value instanceof String) return ((String) value).getBytes(StandardCharsets.UTF_8);
    return null;
  }

  private static boolean equalsBytes(byte[] a, byte[] b) {
    if (a == null || b == null || a.length != b.length) return false;
    int diff = 0;
    for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
    return diff == 0;
  }

  private static int secretLength(Object value) {
    byte[] bytes = secretBytes(value);
    return bytes == null ? 0 : bytes.length;
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
