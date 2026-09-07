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
import java.util.function.Consumer;

/**
 * Debug-only probe for the active ThingClips BLE protocol controller.
 *
 * It intentionally reads only non-secret flags/numbers plus lengths/presence of
 * key material. Secret contents are never emitted.
 */
public final class ThingBleSecurityRuntimeProbe {
  private static final String CONTROLLER = "com.thingclips.sdk.bluetooth.dpdbqdp";
  private static final int MAX_DEPTH = 7;
  private static final int MAX_NODES = 700;

  private static final String[] ROOT_CLASSES = {
      "com.thingclips.sdk.bluetooth.pdbdqqp",
      "com.thingclips.sdk.bluetooth.pbpdbqp",
      "com.thingclips.sdk.bluetooth.pbbpdbb",
      "com.thingclips.sdk.bluetooth.bpqppbd",
      "com.thingclips.sdk.bluetooth.dpbbdqq",
      "com.thingclips.sdk.bluetooth.dpqdpqd",
      "com.thingclips.sdk.bluetooth.bbdqddq",
      CONTROLLER
  };

  private ThingBleSecurityRuntimeProbe() {}

  public static void capture(long tMs, Consumer<String> rawLog) {
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    try {
      ClassLoader loader = ThingBleSecurityRuntimeProbe.class.getClassLoader();
      Class<?> controllerClass = Class.forName(CONTROLLER, false, loader);
      Object controller = findLiveInstance(loader, controllerClass);
      if (controller == null) {
        log.accept("[BLE/SDKSEC] tMs=" + tMs
            + " found=false refsTraversed=true secretValuesRead=false secretsLogged=false");
        return;
      }
      logController(tMs, controller, log);
    } catch (Throwable error) {
      log.accept("[BLE/SDKSEC] tMs=" + tMs + " error="
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
          if (!Modifier.isStatic(field.getModifiers())) continue;
          enqueue(queue, readField(field, null), 0);
        }
      } catch (Throwable ignored) {
      }
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
          Class<?> fieldType = field.getType();
          if (fieldType.isPrimitive() || fieldType == String.class || fieldType == byte[].class) continue;
          enqueue(queue, readField(field, value), node.depth + 1);
        }
        current = current.getSuperclass();
      }
    }
    return null;
  }

  private static void logController(long tMs, Object controller, Consumer<String> log) {
    Object connectParam = invokeNoArg(controller, "getConnectParam");
    Object deviceInfo = invokeNoArg(controller, "getDeviceInfo");
    Object connectOpt = readNamedField(controller, "mConnectOpt");
    Object securityRaw = readNamedField(controller, "securityRaw");

    log.accept("[BLE/SDKSEC] tMs=" + tMs
        + " found=true class=" + controller.getClass().getName()
        + " deviceSecurityLevel=" + invokeInt(controller, "getDeviceSecurityLevel")
        + " deviceSecurityFlag=" + invokeBoolean(controller, "getDeviceSecurityFlag")
        + " packetMaxSize=" + invokeInt(controller, "getPacketMaxSize")
        + " sessionFlag=" + invokeInt(controller, "getSessionFlag")
        + " wifiActivatorFlag=" + invokeInt(controller, "getWifiActivatorFlag")
        + " needRequestAuthKey=" + invokeBoolean(controller, "needRequestAuthKey")
        + " securityRawAvailable=" + (securityRaw != null)
        + " secretValuesRead=false secretsLogged=false");

    if (connectOpt != null) {
      log.accept("[BLE/SDKSEC_OPT] tMs=" + tMs
          + " connectType=" + invokeInt(connectOpt, "getConnectType")
          + " securityLevel=" + invokeInt(connectOpt, "getSecurityLevel")
          + " newSecurity=" + invokeBoolean(connectOpt, "isNewSecurity")
          + " oldSecurity=" + invokeBoolean(connectOpt, "isOldSecurity")
          + " securityNeedUpdate=" + invokeBoolean(connectOpt, "isSecurityNeedUpdate")
          + " secretsLogged=false");
    }

    if (connectParam != null) {
      log.accept("[BLE/SDKSEC_PARAM] tMs=" + tMs
          + " loginKeyLength=" + secretLength(readNamedField(connectParam, "loginKey"))
          + " loginKeyCompleteLength=" + secretLength(readNamedField(connectParam, "loginKeyComplete"))
          + " secretKeyLength=" + secretLength(readNamedField(connectParam, "secretKey"))
          + " localKeyLength=" + secretLength(readNamedField(connectParam, "localKey"))
          + " devIdLength=" + secretLength(readNamedField(connectParam, "devId"))
          + " uuidLength=" + secretLength(readNamedField(connectParam, "uuid"))
          + " secretValuesRead=false secretsLogged=false");
    }

    if (deviceInfo != null) {
      log.accept("[BLE/SDKSEC_DEVICE] tMs=" + tMs
          + " protocolVersion=" + safeText(readNamedField(deviceInfo, "protocolVersion"))
          + " enableSecurity=" + safeBoolean(readNamedField(deviceInfo, "enableSecurity"))
          + " isBind=" + safeBoolean(readNamedField(deviceInfo, "isBind"))
          + " newAuthKey=" + safeBoolean(readNamedField(deviceInfo, "newAuthKey"))
          + " authKeyLength=" + secretLength(readNamedField(deviceInfo, "authKey"))
          + " deviceCapabilityLength=" + secretLength(readNamedField(deviceInfo, "deviceCapability"))
          + " secretsLogged=false");
    }
  }

  private static Object readNamedField(Object target, String name) {
    if (target == null) return null;
    Class<?> type = target.getClass();
    int depth = 0;
    while (type != null && type != Object.class && depth++ < 8) {
      try {
        Field field = type.getDeclaredField(name);
        return readField(field, target);
      } catch (NoSuchFieldException ignored) {
        type = type.getSuperclass();
      } catch (Throwable ignored) {
        return null;
      }
    }
    return null;
  }

  private static Object readField(Field field, Object target) {
    try {
      field.setAccessible(true);
      return field.get(target);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static Object invokeNoArg(Object target, String methodName) {
    try {
      Method method = target.getClass().getMethod(methodName);
      return method.invoke(target);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static int invokeInt(Object target, String methodName) {
    Object value = invokeNoArg(target, methodName);
    return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
  }

  private static boolean invokeBoolean(Object target, String methodName) {
    Object value = invokeNoArg(target, methodName);
    return value instanceof Boolean && (Boolean) value;
  }

  private static String safeBoolean(Object value) {
    return value instanceof Boolean ? String.valueOf(value) : "<unknown>";
  }

  private static String safeText(Object value) {
    if (!(value instanceof String)) return "<unknown>";
    String text = (String) value;
    if (text.length() > 32) return "<redacted_length_" + text.length() + ">";
    return text.replace('\n', '_').replace('\r', '_');
  }

  private static int secretLength(Object value) {
    if (value instanceof String) return ((String) value).length();
    if (value instanceof byte[]) return ((byte[]) value).length;
    return 0;
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
    Node(Object value, int depth) {
      this.value = value;
      this.depth = depth;
    }
  }
}
