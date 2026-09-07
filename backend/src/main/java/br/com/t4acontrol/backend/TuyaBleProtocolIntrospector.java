package br.com.t4acontrol.backend;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Debug-only structural inspection of the ThingClips BLE protocol API. Never reads field values. */
final class TuyaBleProtocolIntrospector {
  private static final AtomicBoolean LOGGED = new AtomicBoolean(false);
  private static final String[] PROTOCOL_TYPES = {
      "com.thingclips.sdk.ble.core.protocol.api.Protocol4RequestDelegate",
      "com.thingclips.sdk.ble.core.protocol.api.ProtocolRequestDelegate",
      "com.thingclips.sdk.ble.core.protocol.api.ProtocolActivatorDelegate",
      "com.thingclips.sdk.ble.core.protocol.api.ProtocolAccessRequest",
      "com.thingclips.sdk.ble.core.protocol.api.IP4SuperSecurityAction",
      "com.thingclips.sdk.ble.core.protocol.entity.AuthKeyParam",
      "com.thingclips.sdk.ble.core.protocol.entity.DeviceInfoRsp",
      "com.thingclips.sdk.ble.core.protocol.entity.PairParam",
      "com.thingclips.sdk.ble.core.protocol.entity.ConnectParam",
      "com.thingclips.sdk.ble.core.protocol.entity.ConnectRsp"
  };

  private TuyaBleProtocolIntrospector() {}

  static void logOnce(Object bleManager, Consumer<String> rawLog) {
    if (!LOGGED.compareAndSet(false, true)) return;
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    log.accept("[SDK/PROTO] INTROSPECTION_START valuesRead=false secretsLogged=false");

    for (String className : PROTOCOL_TYPES) {
      try {
        Class<?> type = Class.forName(className);
        Method[] methods = type.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        if (methods.length == 0) {
          log.accept("[SDK/PROTO] TYPE class=" + className + " kind=" + kind(type) + " methods=0");
          continue;
        }
        for (Method method : methods) {
          log.accept("[SDK/PROTO] METHOD class=" + className
              + " kind=" + kind(type)
              + " visibility=" + visibility(method.getModifiers())
              + " static=" + Modifier.isStatic(method.getModifiers())
              + " name=" + method.getName()
              + " params=" + Arrays.stream(method.getParameterTypes())
                  .map(Class::getSimpleName).collect(Collectors.joining(",", "[", "]"))
              + " returns=" + method.getReturnType().getSimpleName());
        }
      } catch (Throwable error) {
        log.accept("[SDK/PROTO] TYPE_UNAVAILABLE class=" + className
            + " error=" + error.getClass().getSimpleName());
      }
    }

    if (bleManager != null) {
      Class<?> impl = bleManager.getClass();
      log.accept("[SDK/PROTO] BLE_MANAGER_IMPL class=" + impl.getName());
      Arrays.stream(impl.getDeclaredMethods())
          .filter(TuyaBleProtocolIntrospector::interesting)
          .sorted(Comparator.comparing(Method::getName))
          .limit(80)
          .forEach(method -> log.accept("[SDK/PROTO] BLE_MANAGER_METHOD name=" + method.getName()
              + " params=" + Arrays.stream(method.getParameterTypes())
                  .map(Class::getSimpleName).collect(Collectors.joining(",", "[", "]"))
              + " returns=" + method.getReturnType().getSimpleName()));
    }
    log.accept("[SDK/PROTO] INTROSPECTION_FINISH valuesRead=false secretsLogged=false");
  }

  private static boolean interesting(Method method) {
    String name = method.getName().toLowerCase();
    return name.contains("protocol") || name.contains("auth") || name.contains("key")
        || name.contains("security") || name.contains("connect") || name.contains("write")
        || name.contains("send") || name.contains("log");
  }

  private static String kind(Class<?> type) {
    if (type.isInterface()) return "interface";
    if (type.isEnum()) return "enum";
    return "class";
  }

  private static String visibility(int modifiers) {
    if (Modifier.isPublic(modifiers)) return "public";
    if (Modifier.isProtected(modifiers)) return "protected";
    if (Modifier.isPrivate(modifiers)) return "private";
    return "package";
  }
}
