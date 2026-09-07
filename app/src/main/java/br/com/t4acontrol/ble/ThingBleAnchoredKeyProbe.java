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

/** Debug-only follow-up after f173 identified MD5(loginKey)=slot4 and MD5(srand)=slots2/12. */
public final class ThingBleAnchoredKeyProbe {
  private static final String CONTROLLER = "com.thingclips.sdk.bluetooth.dpdbqdp";
  private static final int MAX_SLOT = 31;
  private static final int MAX_DEPTH = 7;
  private static final int MAX_NODES = 700;
  private static final String[] ROOT_CLASSES = {
      "com.thingclips.sdk.bluetooth.pdbdqqp", "com.thingclips.sdk.bluetooth.pbpdbqp",
      "com.thingclips.sdk.bluetooth.pbbpdbb", "com.thingclips.sdk.bluetooth.bpqppbd",
      "com.thingclips.sdk.bluetooth.dpbbdqq", "com.thingclips.sdk.bluetooth.dpqdpqd",
      "com.thingclips.sdk.bluetooth.bbdqddq", CONTROLLER
  };

  private ThingBleAnchoredKeyProbe() {}

  public static void capture(long tMs, Consumer<String> rawLog) {
    Consumer<String> log = rawLog == null ? ignored -> {} : rawLog;
    try {
      ClassLoader loader = ThingBleAnchoredKeyProbe.class.getClassLoader();
      Class<?> controllerClass = Class.forName(CONTROLLER, false, loader);
      Object controller = findLiveInstance(loader, controllerClass);
      if (controller == null) { log.accept("[BLE/SDKSEC_ANCHOR] tMs=" + tMs + " found=false secretsLogged=false"); return; }
      Object cp = invokeNoArg(controller, "getConnectParam");
      Object di = invokeNoArg(controller, "getDeviceInfo");
      Object rep = readNamedField(controller, "rep");
      Map<String, byte[]> roots = new LinkedHashMap<>();
      put(roots, "loginKey", readNamedField(cp, "loginKey"));
      put(roots, "loginKeyComplete", readNamedField(cp, "loginKeyComplete"));
      put(roots, "secretKey", readNamedField(cp, "secretKey"));
      put(roots, "devId", readNamedField(cp, "devId"));
      put(roots, "uuid", readNamedField(cp, "uuid"));
      put(roots, "authKey", readNamedField(di, "authKey"));
      put(roots, "srand", readNamedField(rep, "srand"));
      byte[][] slots = new byte[MAX_SLOT + 1][];
      int populated = 0;
      for (int i = 0; i <= MAX_SLOT; i++) { slots[i] = invokeSecretKey(controller, i); if (slots[i] != null && slots[i].length > 0) populated++; }
      byte[] login = roots.get("loginKey"), srand = roots.get("srand");
      if (login == null || srand == null || populated == 0) {
        log.accept("[BLE/SDKSEC_ANCHOR] tMs=" + tMs + " ready=false roots=" + roots.size() + " populatedSlots=" + populated + " secretsLogged=false"); return;
      }
      byte[] loginMd5 = digest16("MD5", login), srandMd5 = digest16("MD5", srand);
      Stats stats = new Stats();
      test("ANCHOR", "MD5_LOGIN", 1, loginMd5, slots, stats, log, tMs);
      test("ANCHOR", "MD5_SRAND", 1, srandMd5, slots, stats, log, tMs);

      // Step 2: mix the two proven anchors with every known root in both directions.
      Map<String, byte[]> anchors = new LinkedHashMap<>();
      anchors.put("md5Login", loginMd5); anchors.put("md5Srand", srandMd5);
      for (Map.Entry<String, byte[]> a : anchors.entrySet()) {
        for (Map.Entry<String, byte[]> r : roots.entrySet()) {
          byte[] ar = concat(a.getValue(), r.getValue()), ra = concat(r.getValue(), a.getValue());
          test("HASH", "MD5_" + a.getKey() + "_" + r.getKey(), 2, digest16("MD5", ar), slots, stats, log, tMs);
          test("HASH", "MD5_" + r.getKey() + "_" + a.getKey(), 2, digest16("MD5", ra), slots, stats, log, tMs);
          test("HASH", "SHA256_" + a.getKey() + "_" + r.getKey(), 2, digest16("SHA-256", ar), slots, stats, log, tMs);
          test("HASH", "SHA256_" + r.getKey() + "_" + a.getKey(), 2, digest16("SHA-256", ra), slots, stats, log, tMs);
          test("HMAC", "HMACMD5_" + a.getKey() + "_" + r.getKey(), 2, hmac16("HmacMD5", a.getValue(), r.getValue()), slots, stats, log, tMs);
          test("HMAC", "HMACMD5_" + r.getKey() + "_" + a.getKey(), 2, hmac16("HmacMD5", r.getValue(), a.getValue()), slots, stats, log, tMs);
          test("HMAC", "HMACSHA256_" + a.getKey() + "_" + r.getKey(), 2, hmac16("HmacSHA256", a.getValue(), r.getValue()), slots, stats, log, tMs);
          byte[] rb = block16(r.getValue());
          if (rb != null) {
            test("AES", "AESENC_" + a.getKey() + "_" + r.getKey(), 2, aes(a.getValue(), rb, Cipher.ENCRYPT_MODE), slots, stats, log, tMs);
            test("AES", "AESDEC_" + a.getKey() + "_" + r.getKey(), 2, aes(a.getValue(), rb, Cipher.DECRYPT_MODE), slots, stats, log, tMs);
            test("XOR", "XOR_" + a.getKey() + "_" + r.getKey(), 2, xor(a.getValue(), rb), slots, stats, log, tMs);
          }
        }
      }

      // Step 3: combine the two proven anchors directly, then transform once more.
      byte[][] pairs = {concat(loginMd5, srandMd5), concat(srandMd5, loginMd5)};
      String[] pairNames = {"login_srand", "srand_login"};
      for (int i = 0; i < pairs.length; i++) {
        byte[] m = digest16("MD5", pairs[i]);
        byte[] s = digest16("SHA-256", pairs[i]);
        test("HASH", "MD5_ANCHORS_" + pairNames[i], 2, m, slots, stats, log, tMs);
        test("HASH", "SHA256_ANCHORS_" + pairNames[i], 2, s, slots, stats, log, tMs);
        test("HASH", "MD5_MD5_ANCHORS_" + pairNames[i], 3, digest16("MD5", m), slots, stats, log, tMs);
        test("HASH", "SHA256_MD5_ANCHORS_" + pairNames[i], 3, digest16("SHA-256", m), slots, stats, log, tMs);
        test("HASH", "MD5_SHA256_ANCHORS_" + pairNames[i], 3, digest16("MD5", s), slots, stats, log, tMs);
      }
      test("HMAC", "HMACMD5_LOGIN_SRAND", 2, hmac16("HmacMD5", loginMd5, srandMd5), slots, stats, log, tMs);
      test("HMAC", "HMACMD5_SRAND_LOGIN", 2, hmac16("HmacMD5", srandMd5, loginMd5), slots, stats, log, tMs);
      test("HMAC", "HMACSHA256_LOGIN_SRAND", 2, hmac16("HmacSHA256", loginMd5, srandMd5), slots, stats, log, tMs);
      test("HMAC", "HMACSHA256_SRAND_LOGIN", 2, hmac16("HmacSHA256", srandMd5, loginMd5), slots, stats, log, tMs);
      test("AES", "AESENC_LOGIN_SRAND", 2, aes(loginMd5, srandMd5, Cipher.ENCRYPT_MODE), slots, stats, log, tMs);
      test("AES", "AESENC_SRAND_LOGIN", 2, aes(srandMd5, loginMd5, Cipher.ENCRYPT_MODE), slots, stats, log, tMs);
      test("AES", "AESDEC_LOGIN_SRAND", 2, aes(loginMd5, srandMd5, Cipher.DECRYPT_MODE), slots, stats, log, tMs);
      test("AES", "AESDEC_SRAND_LOGIN", 2, aes(srandMd5, loginMd5, Cipher.DECRYPT_MODE), slots, stats, log, tMs);
      test("XOR", "XOR_ANCHORS", 2, xor(loginMd5, srandMd5), slots, stats, log, tMs);

      log.accept("[BLE/SDKSEC_ANCHOR] tMs=" + tMs + " ready=true roots=" + roots.size()
          + " populatedSlots=" + populated + " candidates=" + stats.candidates + " matches=" + stats.matches
          + " maxDerivationDepth=3 writes=false secretValuesCompared=true secretsLogged=false");
    } catch (Throwable e) {
      log.accept("[BLE/SDKSEC_ANCHOR] tMs=" + tMs + " error=" + e.getClass().getSimpleName() + " secretsLogged=false");
    }
  }

  private static void test(String family, String path, int depth, byte[] value, byte[][] slots, Stats stats, Consumer<String> log, long tMs) {
    if (value == null || value.length == 0) return; stats.candidates++;
    List<Integer> matches = new ArrayList<>();
    for (int i = 0; i < slots.length; i++) if (eq(value, slots[i])) matches.add(i);
    if (matches.isEmpty()) return; stats.matches += matches.size();
    log.accept("[BLE/SDKSEC_ANCHOR_MATCH] tMs=" + tMs + " family=" + family + " path=" + path
        + " depth=" + depth + " slots=" + matches.toString().replace(" ", "")
        + " candidateLength=" + value.length + " secretsLogged=false");
  }

  private static byte[] digest16(String alg, byte[] data) { try { return Arrays.copyOf(MessageDigest.getInstance(alg).digest(data), 16); } catch (Throwable e) { return null; } }
  private static byte[] hmac16(String alg, byte[] key, byte[] data) { try { Mac m=Mac.getInstance(alg); m.init(new SecretKeySpec(key, alg)); return Arrays.copyOf(m.doFinal(data),16); } catch(Throwable e){return null;} }
  private static byte[] aes(byte[] key, byte[] block, int mode) { if(key==null||block==null||key.length!=16||block.length!=16)return null; try{Cipher c=Cipher.getInstance("AES/ECB/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"));return c.doFinal(block);}catch(Throwable e){return null;} }
  private static byte[] xor(byte[] a, byte[] b){if(a==null||b==null||a.length!=16||b.length!=16)return null;byte[] o=new byte[16];for(int i=0;i<16;i++)o[i]=(byte)(a[i]^b[i]);return o;}
  private static byte[] block16(byte[] v){if(v==null||v.length==0)return null;byte[] b=new byte[16];System.arraycopy(v,0,b,0,Math.min(16,v.length));return b;}
  private static byte[] concat(byte[] a,byte[] b){if(a==null||b==null)return null;byte[] o=new byte[a.length+b.length];System.arraycopy(a,0,o,0,a.length);System.arraycopy(b,0,o,a.length,b.length);return o;}
  private static boolean eq(byte[] a,byte[] b){if(a==null||b==null||a.length!=b.length)return false;int d=0;for(int i=0;i<a.length;i++)d|=a[i]^b[i];return d==0;}
  private static void put(Map<String,byte[]> m,String n,Object v){byte[] b=bytes(v);if(b!=null&&b.length>0)m.put(n,b);}
  private static byte[] bytes(Object v){if(v instanceof byte[])return(byte[])v;if(v instanceof String)return((String)v).getBytes(StandardCharsets.UTF_8);return null;}
  private static byte[] invokeSecretKey(Object c,int slot){try{Method m=c.getClass().getMethod("getSecretKey",int.class);Object v=m.invoke(c,slot);return v instanceof byte[]?(byte[])v:null;}catch(Throwable e){return null;}}
  private static Object invokeNoArg(Object t,String n){try{return t.getClass().getMethod(n).invoke(t);}catch(Throwable e){return null;}}
  private static Object readNamedField(Object t,String n){if(t==null)return null;Class<?> c=t.getClass();for(int d=0;c!=null&&c!=Object.class&&d<8;d++,c=c.getSuperclass()){try{return readField(c.getDeclaredField(n),t);}catch(NoSuchFieldException e){}catch(Throwable e){return null;}}return null;}
  private static Object readField(Field f,Object t){try{f.setAccessible(true);return f.get(t);}catch(Throwable e){return null;}}
  private static Object findLiveInstance(ClassLoader l,Class<?> target){Set<Object>s=Collections.newSetFromMap(new IdentityHashMap<>());ArrayDeque<Node>q=new ArrayDeque<>();for(String n:ROOT_CLASSES){try{Class<?>r=Class.forName(n,false,l);for(Field f:r.getDeclaredFields())if(Modifier.isStatic(f.getModifiers()))enq(q,readField(f,null),0);}catch(Throwable e){}}int v=0;while(!q.isEmpty()&&v++<MAX_NODES){Node n=q.removeFirst();Object o=n.v;if(o==null||!s.add(o))continue;if(target.isInstance(o))return o;if(n.d>=MAX_DEPTH)continue;if(o instanceof Map<?,?>){for(Map.Entry<?,?>e:((Map<?,?>)o).entrySet()){enq(q,e.getKey(),n.d+1);enq(q,e.getValue(),n.d+1);}continue;}if(o instanceof Iterable<?>){for(Object x:(Iterable<?>)o)enq(q,x,n.d+1);continue;}Class<?>t=o.getClass();if(t.isArray()){if(t.getComponentType().isPrimitive())continue;for(int i=0;i<Math.min(Array.getLength(o),64);i++)enq(q,Array.get(o,i),n.d+1);continue;}if(!trav(t))continue;for(Class<?>c=t;c!=null&&c!=Object.class;c=c.getSuperclass())for(Field f:c.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())&&!f.getType().isPrimitive()&&f.getType()!=String.class&&f.getType()!=byte[].class)enq(q,readField(f,o),n.d+1);}return null;}
  private static void enq(ArrayDeque<Node>q,Object v,int d){if(v==null||d>MAX_DEPTH)return;Class<?>t=v.getClass();if(t==String.class||t==byte[].class||Number.class.isAssignableFrom(t)||t==Boolean.class||t.isEnum())return;q.addLast(new Node(v,d));}
  private static boolean trav(Class<?>t){String n=t.getName();return n.startsWith("com.thingclips.")||n.startsWith("java.util.")||n.startsWith("java.util.concurrent.");}
  private static final class Node{final Object v;final int d;Node(Object v,int d){this.v=v;this.d=d;}}
  private static final class Stats{int candidates;int matches;}
}
