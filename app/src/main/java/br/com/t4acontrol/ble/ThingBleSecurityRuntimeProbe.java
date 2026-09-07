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

/** Debug-only sanitized probe for the active ThingClips BLE security controller. */
public final class ThingBleSecurityRuntimeProbe {
  private static final String CONTROLLER = "com.thingclips.sdk.bluetooth.dpdbqdp";
  private static final int MAX_DEPTH = 7;
  private static final int MAX_NODES = 700;
  private static final int SECRET_SLOT_MAX = 31;
  private static final String[] ROOT_CLASSES = {
      "com.thingclips.sdk.bluetooth.pdbdqqp", "com.thingclips.sdk.bluetooth.pbpdbqp",
      "com.thingclips.sdk.bluetooth.pbbpdbb", "com.thingclips.sdk.bluetooth.bpqppbd",
      "com.thingclips.sdk.bluetooth.dpbbdqq", "com.thingclips.sdk.bluetooth.dpqdpqd",
      "com.thingclips.sdk.bluetooth.bbdqddq", CONTROLLER
  };

  private ThingBleSecurityRuntimeProbe() {}

  public static void capture(long tMs, Consumer<String> rawLog) {
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    try {
      ClassLoader loader = ThingBleSecurityRuntimeProbe.class.getClassLoader();
      Class<?> controllerClass = Class.forName(CONTROLLER, false, loader);
      Object controller = findLiveInstance(loader, controllerClass);
      if (controller == null) {
        log.accept("[BLE/SDKSEC] tMs=" + tMs + " found=false refsTraversed=true secretValuesRead=false secretsLogged=false");
        return;
      }
      logController(tMs, controller, log);
    } catch (Throwable error) {
      log.accept("[BLE/SDKSEC] tMs=" + tMs + " error=" + error.getClass().getSimpleName() + " secretsLogged=false");
    }
  }

  private static Object findLiveInstance(ClassLoader loader, Class<?> targetClass) {
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    ArrayDeque<Node> queue = new ArrayDeque<>();
    for (String rootName : ROOT_CLASSES) {
      try {
        Class<?> root = Class.forName(rootName, false, loader);
        for (Field field : root.getDeclaredFields()) if (Modifier.isStatic(field.getModifiers())) enqueue(queue, readField(field, null), 0);
      } catch (Throwable ignored) {}
    }
    int visited = 0;
    while (!queue.isEmpty() && visited++ < MAX_NODES) {
      Node node = queue.removeFirst(); Object value = node.value;
      if (value == null || !seen.add(value)) continue;
      if (targetClass.isInstance(value)) return value;
      if (node.depth >= MAX_DEPTH) continue;
      if (value instanceof Map<?, ?>) {
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) { enqueue(queue, entry.getKey(), node.depth + 1); enqueue(queue, entry.getValue(), node.depth + 1); }
        continue;
      }
      if (value instanceof Iterable<?>) { for (Object item : (Iterable<?>) value) enqueue(queue, item, node.depth + 1); continue; }
      Class<?> type = value.getClass();
      if (type.isArray()) {
        if (type.getComponentType().isPrimitive()) continue;
        int len = Math.min(Array.getLength(value), 64); for (int i = 0; i < len; i++) enqueue(queue, Array.get(value, i), node.depth + 1); continue;
      }
      if (!isTraversableType(type)) continue;
      Class<?> current = type; int hierarchy = 0;
      while (current != null && current != Object.class && hierarchy++ < 8) {
        for (Field field : current.getDeclaredFields()) {
          if (Modifier.isStatic(field.getModifiers())) continue;
          Class<?> ft = field.getType(); if (ft.isPrimitive() || ft == String.class || ft == byte[].class) continue;
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
    Object rep = readNamedField(controller, "rep");

    log.accept("[BLE/SDKSEC] tMs=" + tMs + " found=true class=" + controller.getClass().getName()
        + " deviceSecurityLevel=" + invokeInt(controller, "getDeviceSecurityLevel")
        + " deviceSecurityFlag=" + invokeBoolean(controller, "getDeviceSecurityFlag")
        + " packetMaxSize=" + invokeInt(controller, "getPacketMaxSize")
        + " sessionFlag=" + invokeInt(controller, "getSessionFlag")
        + " wifiActivatorFlag=" + invokeInt(controller, "getWifiActivatorFlag")
        + " needRequestAuthKey=" + invokeBoolean(controller, "needRequestAuthKey")
        + " securityRawAvailable=" + (securityRaw != null) + " secretValuesRead=false secretsLogged=false");

    if (connectOpt != null) log.accept("[BLE/SDKSEC_OPT] tMs=" + tMs + " connectType=" + invokeInt(connectOpt, "getConnectType")
        + " securityLevel=" + invokeInt(connectOpt, "getSecurityLevel") + " newSecurity=" + invokeBoolean(connectOpt, "isNewSecurity")
        + " oldSecurity=" + invokeBoolean(connectOpt, "isOldSecurity") + " securityNeedUpdate=" + invokeBoolean(connectOpt, "isSecurityNeedUpdate") + " secretsLogged=false");

    if (connectParam != null) log.accept("[BLE/SDKSEC_PARAM] tMs=" + tMs
        + " loginKeyLength=" + secretLength(readNamedField(connectParam, "loginKey"))
        + " loginKeyCompleteLength=" + secretLength(readNamedField(connectParam, "loginKeyComplete"))
        + " secretKeyLength=" + secretLength(readNamedField(connectParam, "secretKey"))
        + " localKeyLength=" + secretLength(readNamedField(connectParam, "localKey"))
        + " devIdLength=" + secretLength(readNamedField(connectParam, "devId"))
        + " uuidLength=" + secretLength(readNamedField(connectParam, "uuid")) + " secretValuesRead=false secretsLogged=false");

    if (deviceInfo != null) log.accept("[BLE/SDKSEC_DEVICE] tMs=" + tMs
        + " protocolVersion=" + safeText(readNamedField(deviceInfo, "protocolVersion"))
        + " enableSecurity=" + safeBoolean(readNamedField(deviceInfo, "enableSecurity"))
        + " isBind=" + safeBoolean(readNamedField(deviceInfo, "isBind"))
        + " newAuthKey=" + safeBoolean(readNamedField(deviceInfo, "newAuthKey"))
        + " authKeyLength=" + secretLength(readNamedField(deviceInfo, "authKey"))
        + " deviceCapabilityLength=" + secretLength(readNamedField(deviceInfo, "deviceCapability")) + " secretsLogged=false");

    if (securityRaw != null) logSecurityRaw(tMs, securityRaw, connectParam, deviceInfo, rep, log);
    logSecretKeySlots(tMs, controller, connectParam, deviceInfo, rep, log);
  }

  private static void logSecurityRaw(long tMs, Object raw, Object connectParam, Object deviceInfo, Object rep, Consumer<String> log) {
    Object loginKey = connectParam == null ? null : readNamedField(connectParam, "loginKey");
    Object loginKeyComplete = connectParam == null ? null : readNamedField(connectParam, "loginKeyComplete");
    Object secretKey = connectParam == null ? null : readNamedField(connectParam, "secretKey");
    Object authKey = deviceInfo == null ? null : readNamedField(deviceInfo, "authKey");
    Object srand = rep == null ? null : readNamedField(rep, "srand");

    StringBuilder line = new StringBuilder("[BLE/SDKSEC_RAW] tMs=").append(tMs).append(" class=").append(raw.getClass().getName());
    for (Field field : raw.getClass().getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers())) continue;
      Object value = readField(field, raw); String name = field.getName();
      line.append(' ').append(name).append("Type=").append(typeLabel(value));
      if (value instanceof String || value instanceof byte[]) {
        line.append(' ').append(name).append("Length=").append(secretLength(value));
        line.append(' ').append(name).append("EqLoginKey=").append(secretEqualsFlexible(value, loginKey));
        line.append(' ').append(name).append("EqLoginKeyComplete=").append(secretEqualsFlexible(value, loginKeyComplete));
        line.append(' ').append(name).append("EqSecretKey=").append(secretEqualsFlexible(value, secretKey));
        line.append(' ').append(name).append("EqAuthKey=").append(secretEqualsFlexible(value, authKey));
        line.append(' ').append(name).append("EqSrand=").append(secretEqualsFlexible(value, srand));
      } else if (value instanceof Boolean) line.append(' ').append(name).append("Value=").append(value);
      else if (value instanceof Number) line.append(' ').append(name).append("Value=").append(value);
    }
    line.append(" srandAvailable=").append(srand != null).append(" srandLength=").append(secretLength(srand));
    line.append(" secretValuesCompared=true secretsLogged=false"); log.accept(line.toString());
  }

  private static void logSecretKeySlots(long tMs, Object controller, Object connectParam,
      Object deviceInfo, Object rep, Consumer<String> log) {
    Object loginKey = connectParam == null ? null : readNamedField(connectParam, "loginKey");
    Object loginKeyComplete = connectParam == null ? null : readNamedField(connectParam, "loginKeyComplete");
    Object secretKey = connectParam == null ? null : readNamedField(connectParam, "secretKey");
    Object authKey = deviceInfo == null ? null : readNamedField(deviceInfo, "authKey");
    Object srand = rep == null ? null : readNamedField(rep, "srand");
    List<byte[]> groups = new ArrayList<>();
    StringBuilder line = new StringBuilder("[BLE/SDKSEC_KEYS] tMs=").append(tMs);
    int populated = 0;
    for (int slot = 0; slot <= SECRET_SLOT_MAX; slot++) {
      byte[] value = invokeSecretKey(controller, slot);
      if (value == null || value.length == 0) continue;
      populated++;
      int group = secretGroup(groups, value);
      line.append(" slot").append(slot).append("Length=").append(value.length)
          .append(" slot").append(slot).append("Group=").append(group)
          .append(" slot").append(slot).append("EqLoginKey=").append(secretEqualsFlexible(value, loginKey))
          .append(" slot").append(slot).append("EqLoginKeyComplete=").append(secretEqualsFlexible(value, loginKeyComplete))
          .append(" slot").append(slot).append("EqSecretKey=").append(secretEqualsFlexible(value, secretKey))
          .append(" slot").append(slot).append("EqAuthKey=").append(secretEqualsFlexible(value, authKey))
          .append(" slot").append(slot).append("EqSrand=").append(secretEqualsFlexible(value, srand));
    }
    line.append(" populated=").append(populated)
        .append(" maxSlot=").append(SECRET_SLOT_MAX)
        .append(" secretValuesCompared=true secretsLogged=false");
    log.accept(line.toString());
  }

  private static byte[] invokeSecretKey(Object controller, int slot) {
    try {
      Method method = controller.getClass().getMethod("getSecretKey", int.class);
      Object value = method.invoke(controller, slot);
      return value instanceof byte[] ? (byte[]) value : null;
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static int secretGroup(List<byte[]> groups, byte[] value) {
    for (int i = 0; i < groups.size(); i++) if (secretEqualsBytes(groups.get(i), value)) return i + 1;
    groups.add(value.clone());
    return groups.size();
  }

  private static boolean secretEqualsBytes(byte[] a, byte[] b) {
    if (a == null || b == null || a.length != b.length) return false;
    int diff = 0; for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i]; return diff == 0;
  }

  private static boolean secretEqualsFlexible(Object a, Object b) {
    if (a == null || b == null) return false;
    byte[] aa = secretBytes(a); byte[] bb = secretBytes(b);
    if (aa == null || bb == null || aa.length != bb.length) return false;
    return secretEqualsBytes(aa, bb);
  }

  private static byte[] secretBytes(Object value) {
    if (value instanceof byte[]) return (byte[]) value;
    if (value instanceof String) {
      try { return ((String) value).getBytes("UTF-8"); } catch (Exception ignored) { return null; }
    }
    return null;
  }

  private static String typeLabel(Object value) {
    if (value == null) return "null"; if (value instanceof String) return "String"; if (value instanceof byte[]) return "byte[]";
    if (value instanceof Boolean) return "boolean"; if (value instanceof Number) return "number"; return value.getClass().getSimpleName();
  }

  private static Object readNamedField(Object target, String name) {
    if (target == null) return null; Class<?> type = target.getClass(); int depth = 0;
    while (type != null && type != Object.class && depth++ < 8) {
      try { return readField(type.getDeclaredField(name), target); }
      catch (NoSuchFieldException ignored) { type = type.getSuperclass(); }
      catch (Throwable ignored) { return null; }
    }
    return null;
  }
  private static Object readField(Field field, Object target) { try { field.setAccessible(true); return field.get(target); } catch (Throwable ignored) { return null; } }
  private static Object invokeNoArg(Object target, String methodName) { try { Method m = target.getClass().getMethod(methodName); return m.invoke(target); } catch (Throwable ignored) { return null; } }
  private static int invokeInt(Object target, String methodName) { Object v = invokeNoArg(target, methodName); return v instanceof Number ? ((Number) v).intValue() : Integer.MIN_VALUE; }
  private static boolean invokeBoolean(Object target, String methodName) { Object v = invokeNoArg(target, methodName); return v instanceof Boolean && (Boolean) v; }
  private static String safeBoolean(Object value) { return value instanceof Boolean ? String.valueOf(value) : "<unknown>"; }
  private static String safeText(Object value) { if (!(value instanceof String)) return "<unknown>"; String t = (String) value; return t.length() > 32 ? "<redacted_length_" + t.length() + ">" : t.replace('\n', '_').replace('\r', '_'); }
  private static int secretLength(Object value) { if (value instanceof String) return ((String) value).length(); if (value instanceof byte[]) return ((byte[]) value).length; return 0; }
  private static void enqueue(ArrayDeque<Node> queue, Object value, int depth) { if (value == null || depth > MAX_DEPTH) return; Class<?> t = value.getClass(); if (t == String.class || t == byte[].class || t.isPrimitive() || Number.class.isAssignableFrom(t) || t == Boolean.class || t.isEnum()) return; queue.addLast(new Node(value, depth)); }
  private static boolean isTraversableType(Class<?> type) { String n = type.getName(); return n.startsWith("com.thingclips.") || n.startsWith("java.util.") || n.startsWith("java.util.concurrent."); }
  private static final class Node { final Object value; final int depth; Node(Object value, int depth) { this.value = value; this.depth = depth; } }
}
