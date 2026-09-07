package br.com.t4acontrol.ble;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Debug-only structural inspection of the ThingClips BLE protocol implementation.
 *
 * <p>No instances are created and no instance field values are read. Class/member names,
 * modifiers, parameter types and return types are logged. Public static final numeric/boolean
 * constants are also logged because they describe protocol modes rather than device credentials.
 */
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
        + " instanceValuesRead=false instancesCreated=false safeConstantsRead=true");
    ClassLoader loader = ThingBleProtocolIntrospector.class.getClassLoader();
    for (String name : TARGETS) inspectClass(loader, name, log);
    log.accept("[BLE/SDKMAP] FINISH instanceValuesRead=false secretsLogged=false safeConstantsRead=true");
  }

  private static void inspectClass(ClassLoader loader, String name, Consumer<String> log) {
    try {
      Class<?> type = Class.forName(name, false, loader);
      log.accept("[BLE/SDKMAP] CLASS name=" + name
          + " interface=" + type.isInterface()
          + " enum=" + type.isEnum()
          + " superclass=" + typeName(type.getSuperclass())
          + " interfaces=" + joinTypes(type.getInterfaces()));

      Constructor<?>[] constructors = type.getDeclaredConstructors();
      Arrays.sort(constructors, Comparator.comparing(Constructor::toString));
      for (Constructor<?> constructor : constructors) {
        log.accept("[BLE/SDKMAP] CTOR owner=" + name
            + " modifiers=" + Modifier.toString(constructor.getModifiers())
            + " params=" + joinTypes(constructor.getParameterTypes()));
      }

      Field[] fields = type.getDeclaredFields();
      Arrays.sort(fields, Comparator.comparing(Field::getName));
      for (Field field : fields) {
        String suffix = " valueRead=false";
        Object safeValue = safeConstantValue(field);
        if (safeValue != null) suffix = " safeConstant=" + safeValue;
        log.accept("[BLE/SDKMAP] FIELD owner=" + name
            + " name=" + field.getName()
            + " type=" + typeName(field.getType())
            + " modifiers=" + Modifier.toString(field.getModifiers())
            + suffix);
      }

      Method[] methods = type.getDeclaredMethods();
      Arrays.sort(methods, Comparator.comparing(Method::getName).thenComparing(Method::toString));
      for (Method method : methods) {
        log.accept("[BLE/SDKMAP] METHOD owner=" + name
            + " name=" + method.getName()
            + " modifiers=" + Modifier.toString(method.getModifiers())
            + " returns=" + typeName(method.getReturnType())
            + " params=" + joinTypes(method.getParameterTypes()));
      }
    } catch (Throwable error) {
      log.accept("[BLE/SDKMAP] CLASS_MISSING name=" + name
          + " error=" + error.getClass().getSimpleName());
    }
  }

  private static Object safeConstantValue(Field field) {
    int modifiers = field.getModifiers();
    if (!Modifier.isPublic(modifiers) || !Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)) {
      return null;
    }
    Class<?> type = field.getType();
    boolean safeType = type == boolean.class || type == byte.class || type == short.class
        || type == int.class || type == long.class || type == float.class || type == double.class;
    if (!safeType) return null;
    try {
      return field.get(null);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static String joinTypes(Class<?>[] types) {
    if (types == null || types.length == 0) return "[]";
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < types.length; i++) {
      if (i > 0) out.append(',');
      out.append(typeName(types[i]));
    }
    return out.append(']').toString();
  }

  private static String typeName(Class<?> type) {
    return type == null ? "<none>" : type.getName();
  }
}
