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
 * Debug-only runtime probe for the active ThingClips BLE protocol session.
 *
 * It deliberately logs only non-secret negotiation metadata. Object references are
 * traversed to locate the live protocol engine; Strings/byte arrays are never logged
 * except for approved protocol-version fields and byte-array lengths.
 */
public final class ThingBleLiveSessionProbe {
  private static final String ENGINE = "com.thingclips.sdk.bluetooth.bdpddpb";
  private static final String DEVICE_INFO_REP = "com.thingclips.sdk.ble.core.packet.bean.DeviceInfoRep";
  private static final int MAX_DEPTH = 6;
  private static final int MAX_NODES = 500;

  private static final String[] ROOT_CLASSES = {
      "com.thingclips.sdk.bluetooth.pdbdqqp",
      "com.thingclips.sdk.bluetooth.pbpdbqp",
      "com.thingclips.sdk.bluetooth.pbbpdbb",
      "com.thingclips.sdk.bluetooth.bpqppbd",
      "com.thingclips.sdk.bluetooth.dpbbdqq",
      "com.thingclips.sdk.bluetooth.dpqdpqd",
      "com.thingclips.sdk.bluetooth.dpdbqdp",
      "com.thingclips.sdk.bluetooth.bbdqddq",
      ENGINE
  };

  private ThingBleLiveSessionProbe() {}

  public static void capture(long tMs, Consumer<String> rawLog) {
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    try {
      ClassLoader loader = ThingBleLiveSessionProbe.class.getClassLoader();
      Class<?> engineClass = Class.forName(ENGINE, false, loader);
      Object engine = findLiveEngine(loader, engineClass);
      if (engine == null) {
        log.accept("[BLE/SDKSESSION] tMs=" + tMs
            + " found=false refsTraversed=true valuesLogged=false secretsLogged=false");
        return;
      }
      logEngine(tMs, engine, log);
    } catch (Throwable error) {
      log.accept("[BLE/SDKSESSION] tMs=" + tMs + " error="
          + error.getClass().getSimpleName() + " secretsLogged=false");
    }
  }

  private static Object findLiveEngine(ClassLoader loader, Class<?> engineClass) {
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    ArrayDeque<Node> queue = new ArrayDeque<>();

    for (String rootName : ROOT_CLASSES) {
      try {
        Class<?> root = Class.forName(rootName, false, loader);
        for (Field field : root.getDeclaredFields()) {
          if (!Modifier.isStatic(field.getModifiers())) continue;
          Object value = readField(field, null);
          enqueue(queue, value, 0);
        }
      } catch (Throwable ignored) {
      }
    }

    int visited = 0;
    while (!queue.isEmpty() && visited++ < MAX_NODES) {
      Node node = queue.removeFirst();
      Object value = node.value;
      if (value == null || !seen.add(value)) continue;
      if (engineClass.isInstance(value)) return value;
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
      while (current != null && current != Object.class && hierarchy++ < 6) {
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

  private static void logEngine(long tMs, Object engine, Consumer<String> log) {
    Object rep = readNamedField(engine, "rep");
    Object tempRep = readNamedField(engine, "tempDeviceInfoRep");
    Object activeRep = rep != null ? rep : tempRep;

    StringBuilder line = new StringBuilder("[BLE/SDKSESSION] tMs=").append(tMs)
        .append(" found=true class=").append(engine.getClass().getName())
        .append(" protocolVersion=").append(safeNumber(readNamedField(engine, "protocolVersion")))
        .append(" securityLevel=").append(safeNumber(readNamedField(engine, "securityLevel")))
        .append(" packetMaxSize=").append(safeNumber(readNamedField(engine, "packetMaxSize")))
        .append(" slFlag=").append(safeBoolean(readNamedField(engine, "slFlag")))
        .append(" supportStructDp=").append(safeBoolean(readNamedField(engine, "isSupportStructDp")))
        .append(" keyVersion=").append(safeNumber(readNamedField(engine, "mKeyVersion")))
        .append(" signatureAlgorithm=").append(safeNumber(readNamedField(engine, "mSignatureAlgorithm")))
        .append(" serverRandomLength=").append(secretLength(readNamedField(engine, "serverRandom")))
        .append(" repAvailable=").append(activeRep != null)
        .append(" valuesLogged=safe_only secretsLogged=false");
    log.accept(line.toString());

    Object connectOpt = readNamedField(engine, "mConnectOpt");
    if (connectOpt != null) logConnectOpt(tMs, connectOpt, log);
    if (activeRep != null && DEVICE_INFO_REP.equals(activeRep.getClass().getName())) {
      logDeviceInfoRep(tMs, activeRep, log);
    }

    Object deviceInfoRsp = readNamedField(engine, "mDeviceInfoRep");
    if (deviceInfoRsp != null) logDeviceInfoRsp(tMs, deviceInfoRsp, log);
  }

  private static void logConnectOpt(long tMs, Object opt, Consumer<String> log) {
    log.accept("[BLE/SDKSESSION_OPT] tMs=" + tMs
        + " connectType=" + invokeInt(opt, "getConnectType")
        + " securityLevel=" + invokeInt(opt, "getSecurityLevel")
        + " newSecurity=" + invokeBoolean(opt, "isNewSecurity")
        + " oldSecurity=" + invokeBoolean(opt, "isOldSecurity")
        + " securityNeedUpdate=" + invokeBoolean(opt, "isSecurityNeedUpdate")
        + " secretsLogged=false");
  }

  private static void logDeviceInfoRep(long tMs, Object rep, Consumer<String> log) {
    Object srand = readNamedField(rep, "srand");
    log.accept("[BLE/SDKSESSION_DEVICEINFO] tMs=" + tMs
        + " protocolVersion=" + safeText(readNamedField(rep, "protocolVersion"))
        + " securityLevel=" + safeNumber(readNamedField(rep, "securityLevel"))
        + " deviceCapability=" + safeText(readNamedField(rep, "deviceCapability"))
        + " isBind=" + safeBoolean(readNamedField(rep, "isBind"))
        + " newAuthKey=" + safeBoolean(readNamedField(rep, "newAuthKey"))
        + " enableSecurityUpdate=" + safeBoolean(readNamedField(rep, "enableSecurityUpdate"))
        + " supportSecurityUpdate=" + safeBoolean(readNamedField(rep, "supportSecurityUpdate"))
        + " v4NeedAuth=" + safeBoolean(readNamedField(rep, "v4NeedAuth"))
        + " v4NeedServerAuth=" + safeBoolean(readNamedField(rep, "v4NeedServerAuth"))
        + " packetMaxSize=" + safeNumber(readNamedField(rep, "packetMaxSize"))
        + " srandLength=" + secretLength(srand)
        + " secretsLogged=false");
  }

  private static void logDeviceInfoRsp(long tMs, Object rsp, Consumer<String> log) {
    log.accept("[BLE/SDKSESSION_RSP] tMs=" + tMs
        + " protocolVersion=" + safeText(readNamedField(rsp, "protocolVersion"))
        + " enableSecurity=" + safeBoolean(readNamedField(rsp, "enableSecurity"))
        + " isBind=" + safeBoolean(readNamedField(rsp, "isBind"))
        + " newAuthKey=" + safeBoolean(readNamedField(rsp, "newAuthKey"))
        + " deviceCapability=" + safeText(readNamedField(rsp, "deviceCapability"))
        + " authKeyAvailable=" + (secretLength(readNamedField(rsp, "authKey")) > 0)
        + " authKeyLength=" + secretLength(readNamedField(rsp, "authKey"))
        + " secretsLogged=false");
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

  private static int invokeInt(Object target, String methodName) {
    Object value = invokeNoArg(target, methodName);
    return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
  }

  private static boolean invokeBoolean(Object target, String methodName) {
    Object value = invokeNoArg(target, methodName);
    return value instanceof Boolean && (Boolean) value;
  }

  private static Object invokeNoArg(Object target, String methodName) {
    try {
      Method method = target.getClass().getMethod(methodName);
      return method.invoke(target);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static String safeNumber(Object value) {
    return value instanceof Number ? String.valueOf(value) : "<unknown>";
  }

  private static String safeBoolean(Object value) {
    return value instanceof Boolean ? String.valueOf(value) : "<unknown>";
  }

  private static String safeText(Object value) {
    if (!(value instanceof String)) return "<unknown>";
    String text = (String) value;
    if (text.length() > 64) return "<redacted_length_" + text.length() + ">";
    return text.replace('\n', '_').replace('\r', '_');
  }

  private static int secretLength(Object value) {
    if (value instanceof String) return ((String) value).length();
    if (value instanceof byte[]) return ((byte[]) value).length;
    return 0;
  }

  private static final class Node {
    final Object value;
    final int depth;
    Node(Object value, int depth) { this.value = value; this.depth = depth; }
  }
}
