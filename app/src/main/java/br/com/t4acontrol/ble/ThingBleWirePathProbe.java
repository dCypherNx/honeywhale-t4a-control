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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Debug-only passive sampler for the active ThingClips TX path. */
public final class ThingBleWirePathProbe {
  private static final String[] ROOT_CLASSES = {
      "com.thingclips.sdk.bluetooth.pbbpdbb",
      "com.thingclips.sdk.bluetooth.bpqppbd",
      "com.thingclips.sdk.bluetooth.pbpdbqp",
      "com.thingclips.sdk.bluetooth.dpdbqdp",
      "com.thingclips.sdk.bluetooth.bdpddpb"
  };
  private static final String XREQUEST = "com.thingclips.smart.android.ble.connect.request.XRequest";
  private static final String HELPER = "com.thingclips.sdk.bluetooth.dpppbbd";
  private static final long INTERVAL_MS = 5L;
  private static final long WINDOW_MS = 9000L;
  private static final int MAX_DEPTH = 7;
  private static final int MAX_NODES = 700;
  private static final Set<String> EMITTED = ConcurrentHashMap.newKeySet();

  private ThingBleWirePathProbe() {}

  public static void start(Consumer<String> rawLog) {
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    Thread t = new Thread(() -> run(log), "t4a-sdk-wire-path");
    t.setDaemon(true);
    t.start();
  }

  private static void run(Consumer<String> log) {
    long started = System.nanoTime();
    int samples = 0, requestHits = 0, helperHits = 0;
    log.accept("[BLE/SDKWIRE_PATH] START intervalMs=" + INTERVAL_MS + " windowMs=" + WINDOW_MS
        + " roots=" + ROOT_CLASSES.length + " writes=false completePayloadLogged=false");
    while ((System.nanoTime() - started) / 1_000_000L <= WINDOW_MS) {
      Snapshot s = sample((System.nanoTime() - started) / 1_000_000L, log);
      samples++; requestHits += s.requests; helperHits += s.helpers;
      try { Thread.sleep(INTERVAL_MS); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
    }
    log.accept("[BLE/SDKWIRE_PATH] FINISH samples=" + samples + " requestHits=" + requestHits
        + " helperHits=" + helperHits + " uniqueLogged=" + EMITTED.size()
        + " writes=false completePayloadLogged=false");
  }

  private static Snapshot sample(long tMs, Consumer<String> log) {
    int requests = 0, helpers = 0;
    try {
      ClassLoader loader = ThingBleWirePathProbe.class.getClassLoader();
      Class<?> requestClass = Class.forName(XREQUEST, false, loader);
      Class<?> helperClass = Class.forName(HELPER, false, loader);
      ArrayDeque<Node> q = new ArrayDeque<>();
      for (String name : ROOT_CLASSES) {
        try {
          Class<?> c = Class.forName(name, false, loader);
          for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
            for (Field f : cur.getDeclaredFields()) {
              if (Modifier.isStatic(f.getModifiers())) enqueue(q, readField(f, null), 0);
            }
          }
        } catch (Throwable ignored) {}
      }
      Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
      int visited = 0;
      while (!q.isEmpty() && visited++ < MAX_NODES) {
        Node n = q.removeFirst(); Object v = n.value;
        if (v == null || !seen.add(v)) continue;
        if (requestClass.isInstance(v)) { requests++; emitRequest(tMs, v, log); }
        if (helperClass.isInstance(v)) { helpers++; emitHelper(tMs, v, log); }
        if (n.depth >= MAX_DEPTH) continue;
        if (v instanceof Map<?, ?>) {
          for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) { enqueue(q, e.getKey(), n.depth+1); enqueue(q, e.getValue(), n.depth+1); }
          continue;
        }
        if (v instanceof Iterable<?>) { for (Object x : (Iterable<?>) v) enqueue(q, x, n.depth+1); continue; }
        Class<?> type = v.getClass();
        if (type.isArray()) {
          if (type.getComponentType().isPrimitive()) continue;
          for (int i=0;i<Math.min(Array.getLength(v),64);i++) enqueue(q, Array.get(v,i), n.depth+1);
          continue;
        }
        if (!traversable(type)) continue;
        for (Class<?> cur = type; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
          for (Field f : cur.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            Class<?> ft = f.getType();
            if (ft.isPrimitive() || ft == String.class || ft == byte[].class || ft == byte[][].class) continue;
            enqueue(q, readField(f, v), n.depth+1);
          }
        }
      }
    } catch (Throwable ignored) {}
    return new Snapshot(requests, helpers);
  }

  private static void emitHelper(long tMs, Object helper, Consumer<String> log) {
    Object raw = readNamedField(helper, "bdpdqbp");
    if (!(raw instanceof byte[][])) return;
    byte[][] chunks = (byte[][]) raw;
    String summary = summarize(chunks);
    Object index = readNamedField(helper, "pdqppqb");
    String key = "H|" + index + "|" + summary;
    if (!EMITTED.add(key)) return;
    log.accept("[BLE/SDKWIRE_PATH_HELPER] tMs=" + tMs + " chunkCount=" + chunks.length
        + " index=" + (index instanceof Number ? index : "<unknown>") + " edges=" + summary
        + " writes=false completePayloadLogged=false");
  }

  private static void emitRequest(long tMs, Object request, Consumer<String> log) {
    Object commandsObj = invokeNoArg(request, "getCommand");
    if (!(commandsObj instanceof byte[][])) return;
    byte[][] commands = (byte[][]) commandsObj;
    int code = invokeInt(request, "getCode");
    int backCode = invokeInt(request, "getBack_code");
    boolean noRsp = invokeBoolean(request, "isWriteNoRsp");
    String service = safeUuid(invokeNoArg(request, "getServiceUuid"));
    String characteristic = safeUuid(invokeNoArg(request, "getCharacterUuid"));
    String summary = summarize(commands);
    String key = "R|"+code+'|'+backCode+'|'+noRsp+'|'+service+'|'+characteristic+'|'+summary;
    if (!EMITTED.add(key)) return;
    log.accept("[BLE/SDKWIRE_PATH_REQ] tMs="+tMs+" code="+code+" backCode="+backCode
        +" writeNoRsp="+noRsp+" service="+service+" characteristic="+characteristic
        +" chunks="+commands.length+" edges="+summary+" writes=false completePayloadLogged=false");
  }

  private static String summarize(byte[][] chunks) {
    StringBuilder out = new StringBuilder();
    for (int i=0;i<chunks.length;i++) {
      byte[] c = chunks[i]; if (c == null) continue;
      if (out.length()>0) out.append(',');
      out.append(i).append(':').append(c.length).append(':')
          .append(hex(c,0,Math.min(6,c.length))).append("..")
          .append(hex(c,Math.max(0,c.length-Math.min(4,c.length)),c.length));
    }
    return out.toString();
  }
  private static String hex(byte[] d,int s,int e){StringBuilder o=new StringBuilder();for(int i=s;i<e;i++)o.append(String.format("%02X",d[i]&0xff));return o.toString();}
  private static Object readNamedField(Object t,String n){for(Class<?>c=t.getClass();c!=null&&c!=Object.class;c=c.getSuperclass()){try{return readField(c.getDeclaredField(n),t);}catch(Throwable ignored){}}return null;}
  private static Object readField(Field f,Object t){try{f.setAccessible(true);return f.get(t);}catch(Throwable e){return null;}}
  private static Object invokeNoArg(Object t,String n){try{Method m=t.getClass().getMethod(n);return m.invoke(t);}catch(Throwable e){return null;}}
  private static int invokeInt(Object t,String n){Object v=invokeNoArg(t,n);return v instanceof Number?((Number)v).intValue():Integer.MIN_VALUE;}
  private static boolean invokeBoolean(Object t,String n){Object v=invokeNoArg(t,n);return v instanceof Boolean&&(Boolean)v;}
  private static String safeUuid(Object v){return v instanceof UUID?v.toString():"<unknown>";}
  private static void enqueue(ArrayDeque<Node>q,Object v,int d){if(v==null||d>MAX_DEPTH)return;Class<?>t=v.getClass();if(t==String.class||t==byte[].class||Number.class.isAssignableFrom(t)||t==Boolean.class||t.isEnum())return;q.addLast(new Node(v,d));}
  private static boolean traversable(Class<?>t){String n=t.getName();return n.startsWith("com.thingclips.")||n.startsWith("java.util.")||n.startsWith("java.util.concurrent.");}
  private static final class Node{final Object value;final int depth;Node(Object v,int d){value=v;depth=d;}}
  private static final class Snapshot{final int requests,helpers;Snapshot(int r,int h){requests=r;helpers=h;}}
}
