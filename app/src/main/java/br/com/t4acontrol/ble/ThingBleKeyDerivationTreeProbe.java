package br.com.t4acontrol.ble;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Debug-only bounded derivation explorer for Tuya BLE 4.7.
 *
 * <p>No candidate bytes are logged or persisted. The probe compares locally generated candidates
 * against getSecretKey(int) and only logs family/path/depth/slot matches. It deliberately uses three
 * lateral families (HASH, HMAC, AES) and advances at most three derivation steps in each family.
 */
public final class ThingBleKeyDerivationTreeProbe {
  private static final String CONTROLLER = "com.thingclips.sdk.bluetooth.dpdbqdp";
  private static final int MAX_DEPTH = 7;
  private static final int MAX_NODES = 700;
  private static final int MAX_SLOT = 31;
  private static final int TREE_DEPTH = 3;
  private static final String[] ROOT_CLASSES = {
      "com.thingclips.sdk.bluetooth.pdbdqqp", "com.thingclips.sdk.bluetooth.pbpdbqp",
      "com.thingclips.sdk.bluetooth.pbbpdbb", "com.thingclips.sdk.bluetooth.bpqppbd",
      "com.thingclips.sdk.bluetooth.dpbbdqq", "com.thingclips.sdk.bluetooth.dpqdpqd",
      "com.thingclips.sdk.bluetooth.bbdqddq", CONTROLLER
  };

  private ThingBleKeyDerivationTreeProbe() {}

  public static void capture(long tMs, Consumer<String> rawLog) {
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    try {
      ClassLoader loader = ThingBleKeyDerivationTreeProbe.class.getClassLoader();
      Class<?> controllerClass = Class.forName(CONTROLLER, false, loader);
      Object controller = findLiveInstance(loader, controllerClass);
      if (controller == null) {
        log.accept("[BLE/SDKSEC_TREE] tMs=" + tMs + " found=false secretsLogged=false");
        return;
      }

      Object connectParam = invokeNoArg(controller, "getConnectParam");
      Object deviceInfo = invokeNoArg(controller, "getDeviceInfo");
      Object rep = readNamedField(controller, "rep");
      Map<String, byte[]> roots = collectRoots(connectParam, deviceInfo, rep);
      byte[][] slots = new byte[MAX_SLOT + 1][];
      int populatedSlots = 0;
      for (int slot = 0; slot <= MAX_SLOT; slot++) {
        slots[slot] = invokeSecretKey(controller, slot);
        if (slots[slot] != null && slots[slot].length > 0) populatedSlots++;
      }

      if (roots.isEmpty() || populatedSlots == 0) {
        log.accept("[BLE/SDKSEC_TREE] tMs=" + tMs + " found=true roots=" + roots.size()
            + " populatedSlots=" + populatedSlots + " ready=false secretsLogged=false");
        return;
      }

      Stats optimistic = new Stats("optimistic");
      Stats pessimistic = new Stats("pessimistic");
      runHashFamily(roots, slots, optimistic, pessimistic, log, tMs);
      runHmacFamily(roots, slots, optimistic, pessimistic, log, tMs);
      runAesFamily(roots, slots, optimistic, pessimistic, log, tMs);

      log.accept("[BLE/SDKSEC_TREE] tMs=" + tMs
          + " found=true roots=" + roots.size()
          + " populatedSlots=" + populatedSlots
          + " lateralFamilies=3 maxDepth=" + TREE_DEPTH
          + " optimisticCandidates=" + optimistic.candidates
          + " optimisticMatches=" + optimistic.matches
          + " pessimisticCandidates=" + pessimistic.candidates
          + " pessimisticMatches=" + pessimistic.matches
          + " secretValuesCompared=true secretsLogged=false");
    } catch (Throwable error) {
      log.accept("[BLE/SDKSEC_TREE] tMs=" + tMs + " error="
          + error.getClass().getSimpleName() + " secretsLogged=false");
    }
  }

  private static void runHashFamily(Map<String, byte[]> roots, byte[][] slots, Stats optimistic,
      Stats pessimistic, Consumer<String> log, long tMs) {
    for (Map.Entry<String, byte[]> entry : roots.entrySet()) {
      byte[] root = entry.getValue();
      byte[] currentMd5 = root;
      byte[] currentSha = root;
      for (int depth = 1; depth <= TREE_DEPTH; depth++) {
        currentMd5 = digest16("MD5", currentMd5);
        currentSha = digest16("SHA-256", currentSha);
        testCandidate("HASH", "MD5_CHAIN", entry.getKey(), depth, currentMd5, slots,
            optimistic, log, tMs);
        testCandidate("HASH", "SHA256_CHAIN", entry.getKey(), depth, currentSha, slots,
            optimistic, log, tMs);
      }

      byte[] srand = roots.get("srand");
      byte[] authKey = roots.get("authKey");
      for (byte[] salt : new byte[][] {srand, authKey}) {
        String saltName = salt == srand ? "srand" : "authKey";
        if (salt == null || salt.length == 0) continue;
        byte[] forward = root;
        byte[] reverse = root;
        for (int depth = 1; depth <= TREE_DEPTH; depth++) {
          forward = digest16("SHA-256", concat(forward, salt));
          reverse = digest16("SHA-256", concat(salt, reverse));
          testCandidate("HASH", "SHA256_APPEND_" + saltName, entry.getKey(), depth, forward,
              slots, pessimistic, log, tMs);
          testCandidate("HASH", "SHA256_PREPEND_" + saltName, entry.getKey(), depth, reverse,
              slots, pessimistic, log, tMs);
        }
      }
    }
  }

  private static void runHmacFamily(Map<String, byte[]> roots, byte[][] slots, Stats optimistic,
      Stats pessimistic, Consumer<String> log, long tMs) {
    List<String> preferredData = Arrays.asList("srand", "authKey", "loginKey");
    for (Map.Entry<String, byte[]> keyEntry : roots.entrySet()) {
      if (keyEntry.getValue().length < 16) continue;
      for (String dataName : preferredData) {
        byte[] data = roots.get(dataName);
        if (data == null || data.length == 0) continue;
        byte[] current = keyEntry.getValue();
        for (int depth = 1; depth <= TREE_DEPTH; depth++) {
          current = hmac16(current, data);
          testCandidate("HMAC", "HMAC_SHA256_" + dataName, keyEntry.getKey(), depth, current,
              slots, optimistic, log, tMs);
        }
      }

      for (Map.Entry<String, byte[]> dataEntry : roots.entrySet()) {
        if (preferredData.contains(dataEntry.getKey())) continue;
        byte[] current = keyEntry.getValue();
        for (int depth = 1; depth <= TREE_DEPTH; depth++) {
          current = hmac16(current, dataEntry.getValue());
          testCandidate("HMAC", "HMAC_SHA256_" + dataEntry.getKey(), keyEntry.getKey(), depth,
              current, slots, pessimistic, log, tMs);
        }
      }
    }
  }

  private static void runAesFamily(Map<String, byte[]> roots, byte[][] slots, Stats optimistic,
      Stats pessimistic, Consumer<String> log, long tMs) {
    List<String> preferredPlain = Arrays.asList("srand", "authKey", "loginKeyComplete", "secretKey");
    for (Map.Entry<String, byte[]> keyEntry : roots.entrySet()) {
      byte[] key = normalize16(keyEntry.getValue());
      if (key == null) continue;
      for (Map.Entry<String, byte[]> plainEntry : roots.entrySet()) {
        byte[] plain = block16(plainEntry.getValue());
        if (plain == null) continue;
        byte[] current = plain;
        Stats stats = preferredPlain.contains(plainEntry.getKey()) ? optimistic : pessimistic;
        for (int depth = 1; depth <= TREE_DEPTH; depth++) {
          current = aesEcbEncrypt(key, current);
          testCandidate("AES", "AES_ECB_" + plainEntry.getKey(), keyEntry.getKey(), depth,
              current, slots, stats, log, tMs);
          if (current == null) break;
        }
      }
    }
  }

  private static void testCandidate(String family, String path, String rootName, int depth,
      byte[] candidate, byte[][] slots, Stats stats, Consumer<String> log, long tMs) {
    if (candidate == null || candidate.length == 0) return;
    stats.candidates++;
    List<Integer> matches = matchingSlots(candidate, slots);
    if (matches.isEmpty()) return;
    stats.matches += matches.size();
    log.accept("[BLE/SDKSEC_TREE_MATCH] tMs=" + tMs
        + " mode=" + stats.mode
        + " family=" + family
        + " path=" + path
        + " root=" + rootName
        + " depth=" + depth
        + " slots=" + compact(matches)
        + " candidateLength=" + candidate.length
        + " secretsLogged=false");
  }

  private static Map<String, byte[]> collectRoots(Object connectParam, Object deviceInfo, Object rep) {
    Map<String, byte[]> roots = new LinkedHashMap<>();
    putRoot(roots, "loginKey", readNamedField(connectParam, "loginKey"));
    putRoot(roots, "loginKeyComplete", readNamedField(connectParam, "loginKeyComplete"));
    putRoot(roots, "secretKey", readNamedField(connectParam, "secretKey"));
    putRoot(roots, "localKey", readNamedField(connectParam, "localKey"));
    putRoot(roots, "devId", readNamedField(connectParam, "devId"));
    putRoot(roots, "uuid", readNamedField(connectParam, "uuid"));
    putRoot(roots, "authKey", readNamedField(deviceInfo, "authKey"));
    putRoot(roots, "srand", readNamedField(rep, "srand"));
    return roots;
  }

  private static void putRoot(Map<String, byte[]> roots, String name, Object value) {
    byte[] bytes = secretBytes(value);
    if (bytes != null && bytes.length > 0) roots.put(name, bytes);
  }

  private static byte[] digest16(String algorithm, byte[] input) {
    if (input == null) return null;
    try {
      byte[] digest = MessageDigest.getInstance(algorithm).digest(input);
      return Arrays.copyOf(digest, 16);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static byte[] hmac16(byte[] key, byte[] data) {
    if (key == null || data == null || key.length == 0) return null;
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return Arrays.copyOf(mac.doFinal(data), 16);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static byte[] aesEcbEncrypt(byte[] key, byte[] block) {
    if (key == null || block == null || key.length != 16 || block.length != 16) return null;
    try {
      Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
      return cipher.doFinal(block);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static byte[] normalize16(byte[] value) {
    if (value == null) return null;
    if (value.length == 16) return value;
    return null;
  }

  private static byte[] block16(byte[] value) {
    if (value == null || value.length == 0) return null;
    byte[] block = new byte[16];
    System.arraycopy(value, 0, block, 0, Math.min(value.length, 16));
    return block;
  }

  private static byte[] concat(byte[] a, byte[] b) {
    if (a == null || b == null) return null;
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private static List<Integer> matchingSlots(byte[] value, byte[][] slots) {
    if (value == null) return Collections.emptyList();
    List<Integer> matches = new ArrayList<>();
    for (int slot = 0; slot < slots.length; slot++) {
      if (equalsBytes(value, slots[slot])) matches.add(slot);
    }
    return matches;
  }

  private static String compact(List<Integer> values) {
    return values.toString().replace(" ", "");
  }

  private static boolean equalsBytes(byte[] a, byte[] b) {
    if (a == null || b == null || a.length != b.length) return false;
    int diff = 0;
    for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
    return diff == 0;
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

  private static Object readNamedField(Object target, String name) {
    if (target == null) return null;
    Class<?> type = target.getClass();
    int depth = 0;
    while (type != null && type != Object.class && depth++ < 8) {
      try {
        return readField(type.getDeclaredField(name), target);
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
    if (target == null) return null;
    try {
      Method method = target.getClass().getMethod(methodName);
      return method.invoke(target);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static byte[] secretBytes(Object value) {
    if (value instanceof byte[]) return (byte[]) value;
    if (value instanceof String) return ((String) value).getBytes(StandardCharsets.UTF_8);
    return null;
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

  private static final class Stats {
    final String mode;
    int candidates;
    int matches;
    Stats(String mode) { this.mode = mode; }
  }

  private static final class Node {
    final Object value;
    final int depth;
    Node(Object value, int depth) { this.value = value; this.depth = depth; }
  }
}
