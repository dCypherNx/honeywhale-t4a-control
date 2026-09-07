package br.com.t4acontrol.ble;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Debug-only structural inspection of the ThingClips BLE protocol implementation. */
public final class ThingBleProtocolIntrospector {
  private static final AtomicBoolean RAN = new AtomicBoolean(false);
  private static final String[] TARGETS = {
      "com.thingclips.sdk.ble.core.ability.options.BleConnectParams",
      "com.thingclips.sdk.ble.core.protocol.entity.ConnectParam",
      "com.thingclips.sdk.ble.core.protocol.entity.ConnectOpt",
      "com.thingclips.sdk.ble.core.protocol.entity.ConnectOpt$Builder",
      "com.thingclips.sdk.ble.core.protocol.entity.ConnectRsp",
      "com.thingclips.sdk.ble.core.protocol.entity.DeviceInfoRsp",
      "com.thingclips.sdk.ble.core.protocol.entity.AuthKeyParam",
      "com.thingclips.sdk.ble.core.protocol.entity.PairParam",
      "com.thingclips.sdk.ble.core.protocol.entity.SecretKeyUpdateParam",
      "com.thingclips.sdk.ble.core.protocol.entity.DeviceActivatorStatus",
      "com.thingclips.sdk.ble.core.protocol.entity.ActivatorResultParam",
      "com.thingclips.sdk.ble.core.bean.SecurityCertBean",
      "com.thingclips.sdk.ble.core.protocol.api.ConnectActionResponse",
      "com.thingclips.sdk.ble.core.protocol.api.ActionResponse",
      "com.thingclips.sdk.ble.core.protocol.api.ActionProgressResponse",
      "com.thingclips.sdk.ble.core.protocol.api.ProtocolRequestDelegate",
      "com.thingclips.sdk.ble.core.protocol.api.Protocol4RequestDelegate",
      "com.thingclips.sdk.ble.core.protocol.api.ProtocolActivatorDelegate",
      "com.thingclips.sdk.ble.core.protocol.api.ProtocolSecurityUpdateDelegate",
      "com.thingclips.sdk.ble.core.protocol.api.IP4SuperSecurityAction",
      "com.thingclips.sdk.ble.core.protocol.api.DeviceCapabilityBit",
      "com.thingclips.sdk.ble.core.protocol.api.CommonConstant"
  };

  private ThingBleProtocolIntrospector() {}

  public static void inspect(Consumer<String> rawLog) {
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    if (!RAN.compareAndSet(false, true)) return;
    log.accept("[BLE/SDKMAP] START targetCount=" + TARGETS.length
        + " instanceValuesRead=false instancesCreated=false safeConstantsRead=true runtimeSurface=true dependencySurface=true");
    ClassLoader loader = ThingBleProtocolIntrospector.class.getClassLoader();
    for (String name : TARGETS) inspectClass(loader, name, log);
    inspectRuntimeBleManager(loader, log);
    log.accept("[BLE/SDKMAP] FINISH instanceValuesRead=false secretsLogged=false safeConstantsRead=true runtimeSurface=true dependencySurface=true");
  }

  private static void inspectRuntimeBleManager(ClassLoader loader, Consumer<String> log) {
    try {
      Class<?> sdk = Class.forName("com.thingclips.smart.home.sdk.ThingHomeSdk", false, loader);
      Object manager = sdk.getMethod("getBleManager").invoke(null);
      if (manager == null) { log.accept("[BLE/SDKMAP] RUNTIME_BLE_MANAGER null=true"); return; }
      Class<?> concrete = manager.getClass();
      log.accept("[BLE/SDKMAP] RUNTIME_BLE_MANAGER null=false class=" + concrete.getName()
          + " fieldsRead=false valuesLogged=false");
      inspectRuntimeClassHierarchy(concrete, log);
      inspectDependencySurface(concrete, log);
    } catch (Throwable error) {
      log.accept("[BLE/SDKMAP] RUNTIME_BLE_MANAGER_ERROR error=" + error.getClass().getSimpleName());
    }
  }

  private static void inspectDependencySurface(Class<?> root, Consumer<String> log) {
    Set<String> seen = new HashSet<>();
    for (Field field : root.getDeclaredFields()) {
      Class<?> type = field.getType();
      String name = type.getName();
      if (!name.startsWith("com.thingclips.") || !seen.add(name)) continue;
      log.accept("[BLE/SDKMAP] DEPENDENCY_CLASS source=" + root.getName() + " field=" + field.getName()
          + " type=" + name + " valueRead=false");
      inspectDependencyClass(type, log);
    }
  }

  private static void inspectDependencyClass(Class<?> type, Consumer<String> log) {
    String name = type.getName();
    Field[] fields = type.getDeclaredFields();
    Arrays.sort(fields, Comparator.comparing(Field::getName));
    for (Field field : fields) {
      log.accept("[BLE/SDKMAP] DEPENDENCY_FIELD owner=" + name + " name=" + field.getName()
          + " type=" + typeName(field.getType()) + " modifiers=" + Modifier.toString(field.getModifiers())
          + " valueRead=false");
    }
    Method[] methods = type.getDeclaredMethods();
    Arrays.sort(methods, Comparator.comparing(Method::getName).thenComparing(Method::toString));
    for (Method method : methods) {
      log.accept("[BLE/SDKMAP] DEPENDENCY_METHOD owner=" + name + " name=" + method.getName()
          + " modifiers=" + Modifier.toString(method.getModifiers()) + " returns=" + typeName(method.getReturnType())
          + " params=" + joinTypes(method.getParameterTypes()));
    }
  }

  private static void inspectRuntimeClassHierarchy(Class<?> type, Consumer<String> log) {
    Class<?> current = type; int depth = 0;
    while (current != null && current != Object.class && depth < 8) {
      String name = current.getName();
      log.accept("[BLE/SDKMAP] RUNTIME_CLASS depth=" + depth + " name=" + name
          + " interfaces=" + joinTypes(current.getInterfaces()) + " fieldsRead=false");
      Field[] fields = current.getDeclaredFields(); Arrays.sort(fields, Comparator.comparing(Field::getName));
      for (Field field : fields) log.accept("[BLE/SDKMAP] RUNTIME_FIELD owner=" + name + " name=" + field.getName()
          + " type=" + typeName(field.getType()) + " modifiers=" + Modifier.toString(field.getModifiers()) + " valueRead=false");
      Method[] methods = current.getDeclaredMethods(); Arrays.sort(methods, Comparator.comparing(Method::getName).thenComparing(Method::toString));
      for (Method method : methods) log.accept("[BLE/SDKMAP] RUNTIME_METHOD owner=" + name + " name=" + method.getName()
          + " modifiers=" + Modifier.toString(method.getModifiers()) + " returns=" + typeName(method.getReturnType())
          + " params=" + joinTypes(method.getParameterTypes()));
      current = current.getSuperclass(); depth++;
    }
  }

  private static void inspectClass(ClassLoader loader, String name, Consumer<String> log) {
    try {
      Class<?> type = Class.forName(name, false, loader);
      log.accept("[BLE/SDKMAP] CLASS name=" + name + " interface=" + type.isInterface() + " enum=" + type.isEnum()
          + " superclass=" + typeName(type.getSuperclass()) + " interfaces=" + joinTypes(type.getInterfaces()));
      Constructor<?>[] constructors = type.getDeclaredConstructors(); Arrays.sort(constructors, Comparator.comparing(Constructor::toString));
      for (Constructor<?> constructor : constructors) log.accept("[BLE/SDKMAP] CTOR owner=" + name + " modifiers="
          + Modifier.toString(constructor.getModifiers()) + " params=" + joinTypes(constructor.getParameterTypes()));
      Field[] fields = type.getDeclaredFields(); Arrays.sort(fields, Comparator.comparing(Field::getName));
      for (Field field : fields) {
        String suffix = " valueRead=false"; Object safeValue = safeConstantValue(field);
        if (safeValue != null) suffix = " safeConstant=" + safeValue;
        log.accept("[BLE/SDKMAP] FIELD owner=" + name + " name=" + field.getName() + " type=" + typeName(field.getType())
            + " modifiers=" + Modifier.toString(field.getModifiers()) + suffix);
      }
      Method[] methods = type.getDeclaredMethods(); Arrays.sort(methods, Comparator.comparing(Method::getName).thenComparing(Method::toString));
      for (Method method : methods) log.accept("[BLE/SDKMAP] METHOD owner=" + name + " name=" + method.getName()
          + " modifiers=" + Modifier.toString(method.getModifiers()) + " returns=" + typeName(method.getReturnType())
          + " params=" + joinTypes(method.getParameterTypes()));
    } catch (Throwable error) { log.accept("[BLE/SDKMAP] CLASS_MISSING name=" + name + " error=" + error.getClass().getSimpleName()); }
  }

  private static Object safeConstantValue(Field field) {
    int modifiers = field.getModifiers();
    if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) return null;
    Class<?> type = field.getType();
    boolean safeType = type == boolean.class || type == byte.class || type == short.class || type == int.class
        || type == long.class || type == float.class || type == double.class;
    if (!safeType) return null;
    try { return field.get(null); } catch (Throwable ignored) { return null; }
  }

  private static String joinTypes(Class<?>[] types) {
    if (types == null || types.length == 0) return "[]";
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < types.length; i++) { if (i > 0) out.append(','); out.append(typeName(types[i])); }
    return out.append(']').toString();
  }

  private static String typeName(Class<?> type) { return type == null ? "<none>" : type.getName(); }
}
