package br.com.t4acontrol.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import br.com.t4acontrol.backend.T4AContracts;
import br.com.t4acontrol.backend.T4ATransport;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Native FD50 transport. Tuya is used only by the injected fallback/provisioner. */
public final class NativeBleTransport implements T4ATransport {
  /** Device-to-app DPS report (DpsReportRep v4). */
  private static final int DPS_REPORT = 0x8006;
  private static final long CONNECT_TIMEOUT_MS = 12000L;
  private static final long GATT_RETRY_DELAY_MS = 750L;
  private static final int MAX_GATT_ATTEMPTS = 3;
  private static final int GATT_CONNECTION_TIMEOUT = 147;
  private static final int REQUESTED_MTU = 247;
  private static final UUID SERVICE = UUID.fromString("0000fd50-0000-1000-8000-00805f9b34fb");
  private static final UUID WRITE = UUID.fromString("00000001-0000-1001-8001-00805f9b07d0");
  private static final UUID NOTIFY = UUID.fromString("00000002-0000-1001-8001-00805f9b07d0");
  private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

  private final Context context;
  private final T4ATransport fallback;
  private final Consumer<String> rawLog;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final Object lock = new Object();
  private final TuyaBle47Codec.Reassembler reassembler = new TuyaBle47Codec.Reassembler();
  private final Map<String, Object> dps = new LinkedHashMap<>();
  private final Map<Integer, T4AContracts.ResultCallback> pendingResults = new LinkedHashMap<>();
  private final Set<String> loggedDpLayouts = new HashSet<>();

  private T4AContracts.Device device;
  private T4AContracts.DeviceListener listener;
  private BluetoothGatt gatt;
  private BluetoothGattCharacteristic writeCharacteristic;
  private boolean nativeAttempt;
  private boolean nativeConnected;
  private boolean usingFallback;
  private int mtu = 20;
  private int nextSequence = 1;
  private byte[] key14;
  private byte[] key15;
  private boolean infoReceived;
  private int connectionAttempts;
  private String pendingMac;
  private T4AContracts.RssiCallback rssiCallback;

  public NativeBleTransport(Context context, T4ATransport fallback, Consumer<String> rawLog) {
    this.context = context.getApplicationContext();
    this.fallback = fallback;
    this.rawLog = rawLog == null ? ignored -> {} : rawLog;
  }

  @Override
  public void attach(T4AContracts.Device device, T4AContracts.DeviceListener listener) {
    detachNative(false);
    this.device = device;
    this.listener = listener;
    this.usingFallback = false;
    this.nativeConnected = false;
    this.dps.clear();
    this.loggedDpLayouts.clear();
    if (device != null && device.dps != null) this.dps.putAll(device.dps);
  }

  @Override
  public void detach() {
    detachNative(false);
    fallback.detach();
    device = null;
    listener = null;
    usingFallback = false;
  }

  @Override
  public void connect(T4AContracts.Device requested) {
    if (requested == null) {
      fallback.connect(null);
      return;
    }
    if (usingFallback) {
      fallback.connect(requested);
      return;
    }
    synchronized (lock) {
      if (nativeConnected || nativeAttempt) return;
      nativeAttempt = true;
      device = requested;
      nextSequence = 1;
      infoReceived = false;
      key14 = null;
      key15 = null;
      reassembler.reset();
    }
    raw("CONNECT_DIRECT_START deviceId=" + requested.id + " mac=" + maskMac(requested.mac));
    if (requested.mac == null || requested.mac.isBlank()
        || requested.localKey == null || requested.localKey.length() < 6
        || requested.securityKey == null || requested.securityKey.length() != 16) {
      fail("missing_neutral_credentials");
      return;
    }
    try {
      BluetoothManager manager = context.getSystemService(BluetoothManager.class);
      BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
      String mac = normalizeMac(requested.mac);
      if (adapter == null || !adapter.isEnabled() || !BluetoothAdapter.checkBluetoothAddress(mac)) {
        fail("bluetooth_or_address_unavailable");
        return;
      }
      synchronized (lock) {
        pendingMac = mac;
        connectionAttempts = 0;
      }
      handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
      startGattConnection();
    } catch (SecurityException | IllegalArgumentException error) {
      fail("gatt_start_" + error.getClass().getSimpleName());
    }
  }

  @Override
  public void disconnect(T4AContracts.Device requested) {
    T4AContracts.Device current = requested == null ? device : requested;
    if (usingFallback) {
      fallback.disconnect(current);
      return;
    }
    disconnectNative("manual");
  }

  private void startGattConnection() {
    String mac;
    int attempt;
    synchronized (lock) {
      if (!nativeAttempt || nativeConnected || pendingMac == null) return;
      mac = pendingMac;
      attempt = ++connectionAttempts;
    }
    try {
      BluetoothManager manager = context.getSystemService(BluetoothManager.class);
      BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
      if (adapter == null || !adapter.isEnabled() || !BluetoothAdapter.checkBluetoothAddress(mac)) {
        fail("bluetooth_or_address_unavailable");
        return;
      }
      BluetoothDevice remote = adapter.getRemoteDevice(mac);
      BluetoothGatt connection =
          remote.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
      synchronized (lock) { gatt = connection; }
      raw("GATT_CONNECT_REQUEST attempt=" + attempt + " mac=" + maskMac(mac));
    } catch (SecurityException | IllegalArgumentException error) {
      fail("gatt_start_" + error.getClass().getSimpleName());
    }
  }

  @Override
  public boolean isConnected(String deviceId) {
    synchronized (lock) { return nativeConnected && device != null && device.id.equals(deviceId); }
  }

  @Override
  public T4AContracts.Device cachedDevice(String deviceId) {
    synchronized (lock) {
      if (device == null || !device.id.equals(deviceId)) return null;
      return new T4AContracts.Device(device.id, device.name, device.mac, device.uuid,
          device.productId, device.localKey, device.securityKey, device.protocolMetadata, dps,
          device.schema);
    }
  }

  @Override
  public void publish(String deviceId, Map<String, Object> values, T4AContracts.ResultCallback callback) {
    if (!isConnected(deviceId)) { fallback.publish(deviceId, values, callback); return; }
    try {
      byte[] payload = encodeDps(values, true);
      int sequence = send(TuyaBle47Codec.PUBLISH_DPS, payload, null);
      if (callback != null) callback.onSuccess();
      raw("TX_DPS sequence=" + sequence + " count=" + (values == null ? 0 : values.size()) + " direct=true");
    } catch (RuntimeException | GeneralSecurityException error) {
      if (callback != null) callback.onError("DIRECT_CODEC", error.getMessage());
    }
  }

  @Override
  public void readRssi(String mac, T4AContracts.RssiCallback callback) {
    BluetoothGatt active;
    synchronized (lock) { active = gatt; rssiCallback = callback; }
    if (!nativeConnected || active == null) { fallback.readRssi(mac, callback); return; }
    try {
      if (!active.readRemoteRssi() && callback != null) callback.onResult(false, 0);
    } catch (SecurityException error) {
      if (callback != null) callback.onResult(false, 0);
    }
  }

  @Override
  public void destroy() { detach(); fallback.destroy(); }

  private final Runnable connectTimeout = () -> fail("timeout");

  private final BluetoothGattCallback callback = new BluetoothGattCallback() {
    @Override public void onConnectionStateChange(BluetoothGatt connection, int status, int state) {
      if (!isActiveConnection(connection)) return;
      raw("CONNECTION_STATE status=" + status + " state=" + state);
      if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
        if (!nativeConnected && scheduleGattRetry(connection, status, state)) return;
        if (!nativeConnected) fail("connection_status_" + status); else disconnectNative("connection_lost");
        return;
      }
      if (state == BluetoothProfile.STATE_CONNECTED) {
        try {
          boolean started = connection.requestMtu(REQUESTED_MTU);
          raw("MTU_REQUEST requested=" + REQUESTED_MTU + " started=" + started);
          if (!started) requestServices(connection);
        } catch (SecurityException error) { requestServices(connection); }
      }
    }

    @Override public void onMtuChanged(BluetoothGatt connection, int value, int status) {
      mtu = status == BluetoothGatt.GATT_SUCCESS ? value : 20;
      raw("MTU_RESULT status=" + status + " mtu=" + mtu);
      requestServices(connection);
    }

    @Override public void onServicesDiscovered(BluetoothGatt connection, int status) {
      if (status != BluetoothGatt.GATT_SUCCESS) { fail("services_" + status); return; }
      BluetoothGattService service = connection.getService(SERVICE);
      BluetoothGattCharacteristic notify = service == null ? null : service.getCharacteristic(NOTIFY);
      writeCharacteristic = service == null ? null : service.getCharacteristic(WRITE);
      BluetoothGattDescriptor cccd = notify == null ? null : notify.getDescriptor(CCCD);
      if (notify == null || writeCharacteristic == null || cccd == null) { fail("fd50_characteristics_missing"); return; }
      try {
        if (!connection.setCharacteristicNotification(notify, true)) { fail("notify_enable_false"); return; }
        int statusCode = connection.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        raw("NOTIFY_CCCD_WRITE_REQUEST status=" + statusCode);
        if (statusCode != BluetoothGatt.GATT_SUCCESS) fail("notify_descriptor_" + statusCode);
      } catch (SecurityException error) { fail("notify_security"); }
    }

    @Override public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int status) {
      raw("NOTIFY_CCCD_WRITE_RESULT status=" + status);
      if (status == BluetoothGatt.GATT_SUCCESS) sendDeviceInfo(); else fail("notify_descriptor_result_" + status);
    }

    @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value) {
      receive(value);
    }

    @SuppressWarnings("deprecation")
    @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic) {
      receive(characteristic.getValue());
    }

    @Override public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value, int status) {
      receiveRead(value, status);
    }

    @SuppressWarnings("deprecation")
    @Override public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int status) {
      receiveRead(characteristic.getValue(), status);
    }

    @Override public void onReadRemoteRssi(BluetoothGatt connection, int value, int status) {
      T4AContracts.RssiCallback callback = rssiCallback;
      rssiCallback = null;
      if (callback != null) callback.onResult(status == BluetoothGatt.GATT_SUCCESS, value);
    }
  };

  private boolean isActiveConnection(BluetoothGatt connection) {
    synchronized (lock) { return gatt == connection; }
  }

  private boolean scheduleGattRetry(BluetoothGatt connection, int status, int state) {
    if (status != 133 && status != GATT_CONNECTION_TIMEOUT
        && !(status == BluetoothGatt.GATT_SUCCESS
        && state == BluetoothProfile.STATE_DISCONNECTED)) return false;
    int nextAttempt;
    synchronized (lock) {
      if (!nativeAttempt || nativeConnected || gatt != connection
          || connectionAttempts >= MAX_GATT_ATTEMPTS) return false;
      nextAttempt = connectionAttempts + 1;
      gatt = null;
    }
    closeGatt(connection);
    handler.removeCallbacks(connectTimeout);
    handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS);
    raw("GATT_RETRY_SCHEDULED nextAttempt=" + nextAttempt + " reason=connection_status_" + status);
    handler.postDelayed(this::startGattConnection, GATT_RETRY_DELAY_MS);
    return true;
  }

  private void requestServices(BluetoothGatt connection) {
    try { if (!connection.discoverServices()) fail("discover_services_false"); }
    catch (SecurityException error) { fail("discover_security"); }
  }

  private void sendDeviceInfo() {
    T4AContracts.Device current = device;
    try {
      key14 = TuyaBle47Codec.deriveKey14(current.localKey, current.securityKey);
      send(TuyaBle47Codec.DEVICE_INFO, TuyaBle47Codec.deviceInfoPayload(mtu), null, key14, 14);
      raw("DEVICE_INFO_SENT selector=14 payloadLength=2 mtu=" + mtu + " direct=true");
    } catch (RuntimeException | GeneralSecurityException error) { fail("device_info_build"); }
  }

  private void receiveRead(byte[] value, int status) {
    if (status == BluetoothGatt.GATT_SUCCESS && value != null && value.length > 0) receive(value);
  }

  private void receive(byte[] fragment) {
    if (fragment == null || fragment.length == 0) return;
    try {
      byte[] envelope = reassembler.accept(fragment);
      if (envelope == null) return;
      int selector = envelope[0] & 0xFF;
      byte[] key = selector == 14 ? key14 : key15;
      TuyaBle47Codec.Message message = TuyaBle47Codec.decode(envelope, key);
      if (message.command != DPS_REPORT) {
        raw("RX_FRAME command=" + message.command + " sequence=" + message.sequence + " ack=" + message.acknowledgement + " selector=" + selector);
      }
      if (message.command == DPS_REPORT) acknowledgeDpsReport(message);
      if (message.command == TuyaBle47Codec.DEVICE_INFO && !infoReceived) {
        TuyaBle47Codec.DeviceInfo info = TuyaBle47Codec.parseDeviceInfo(message.payload);
        infoReceived = true;
        key15 = TuyaBle47Codec.deriveKey15(device.localKey, device.securityKey, info.srand);
        raw("DEVICE_INFO_ACCEPTED protocol=" + info.protocolVersion + " bound=" + info.bound + " v4NeedAuth=" + info.v4NeedAuth + " v4NeedServerAuth=" + info.v4NeedServerAuth + " direct=true");
        send(TuyaBle47Codec.PAIR, TuyaBle47Codec.pairPayload(device.uuid, device.localKey.substring(0, 6), device.id, device.localKey, device.securityKey), null);
      } else if (message.command == TuyaBle47Codec.PAIR) {
        markConnected();
        requestDps();
      } else if (message.command >= 0x8000) {
        Map<String, Object> update = decodeDps(message.payload, message.command);
        if (!update.isEmpty() && listener != null) listener.onDpUpdate(device.id, update);
      }
      completeResult(message);
    } catch (GeneralSecurityException | IllegalArgumentException error) {
      raw("RX_FRAME_ERROR type=" + error.getClass().getSimpleName() + " reason=" + safeReason(error));
      if (!nativeConnected) fail("frame_rejected");
    }
  }

  private void acknowledgeDpsReport(TuyaBle47Codec.Message message)
      throws GeneralSecurityException {
    if (message.payload == null || message.payload.length < 7) return;
    int bType = message.payload[5] & 0xFF;
    boolean needAck = (bType & 0x80) == 0;
    if (!needAck) return;
    byte[] payload = TuyaBle47Codec.dpsReportAckPayload(message.payload);
    int sequence = send(message.command, payload, null);
    raw("DPS_REPORT_ACK sequence=" + sequence + " reportSequence="
        + message.sequence + " direct=true");
  }

  private void requestDps() {
    if (device == null || device.schema == null || device.schema.isEmpty()) return;
    byte[] ids = new byte[device.schema.size()]; int index = 0;
    for (String code : device.schema.keySet()) try { ids[index++] = (byte) Integer.parseInt(code); } catch (NumberFormatException ignored) {}
    if (index != ids.length) ids = Arrays.copyOf(ids, index);
    try { send(TuyaBle47Codec.QUERY_DPS, ids, null); } catch (RuntimeException | GeneralSecurityException error) { raw("QUERY_DPS_ERROR"); }
  }

  private void markConnected() {
    synchronized (lock) { nativeConnected = true; nativeAttempt = false; }
    handler.removeCallbacks(connectTimeout);
    raw("SESSION_CONNECTED transport=native_fd50 protocol=4.7 sdk=false");
    if (listener != null) listener.onConnectionChanged(device.id, true);
  }

  private int send(int command, byte[] payload, T4AContracts.ResultCallback callback) throws GeneralSecurityException {
    return send(command, payload, callback, key15, 15);
  }

  private int send(int command, byte[] payload, T4AContracts.ResultCallback callback, byte[] key, int selector) throws GeneralSecurityException {
    if (writeCharacteristic == null || gatt == null) throw new IllegalStateException("GATT write unavailable");
    int sequence = nextSequence++;
    byte[] envelope = TuyaBle47Codec.encode(sequence, 0, command, payload, selector, key, null);
    if (callback != null) pendingResults.put(sequence, callback);
    for (byte[] packet : TuyaBle47Codec.fragment(envelope, mtu)) {
      if (!write(packet)) throw new IllegalStateException("GATT write rejected");
    }
    return sequence;
  }

  private boolean write(byte[] value) {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        return gatt.writeCharacteristic(writeCharacteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS;
      }
      writeCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
      writeCharacteristic.setValue(value);
      return gatt.writeCharacteristic(writeCharacteristic);
    } catch (SecurityException error) { return false; }
  }

  private void completeResult(TuyaBle47Codec.Message message) {
    T4AContracts.ResultCallback callback = pendingResults.remove(message.acknowledgement);
    if (callback == null && (message.command == TuyaBle47Codec.PUBLISH_DPS || message.command == TuyaBle47Codec.PAIR)) callback = pendingResults.remove(message.sequence);
    if (callback != null) callback.onSuccess();
  }

  private Map<String, Object> decodeDps(byte[] payload, int command) {
    Map<String, Object> best;
    if (command == DPS_REPORT) {
      // DpsReportRep(4) carries version, sequence, type/ack and flag before
      // records with a two-byte big-endian value length.
      best = decodeRecords(payload, 7, true);
    } else {
      best = decodeRecords(payload, 0, false);
      if (best.isEmpty()) {
        int[] offsets = {1, 4, 5, 8};
        for (int offset : offsets) {
          Map<String, Object> candidate = decodeRecords(payload, offset, false);
          if (candidate.size() > best.size()) best = candidate;
        }
      }
    }
    dps.putAll(best);
    return best;
  }

  private Map<String, Object> decodeRecords(byte[] payload, int offset, boolean length16) {
    Map<String, Object> result = new LinkedHashMap<>();
    if (payload == null || offset < 0 || offset > payload.length) return result;
    int position = offset;
    int headerLength = length16 ? 4 : 3;
    while (position + headerLength <= payload.length) {
      int id = payload[position++] & 0xFF;
      int type = payload[position++] & 0xFF;
      int length = length16
          ? ((payload[position++] & 0xFF) << 8) | (payload[position++] & 0xFF)
          : payload[position++] & 0xFF;
      if (length > payload.length - position) return new LinkedHashMap<>();
      byte[] data = Arrays.copyOfRange(payload, position, position + length); position += length;
      if (device != null && !device.schema.containsKey(String.valueOf(id))) continue;
      String layout = id + ":" + type + ":" + length + ":" + offset + ":" + (length16 ? 2 : 1);
      if (loggedDpLayouts.add(layout)) raw("DP_LAYOUT code=" + id + " wireType=" + type + " valueLength=" + length + " offset=" + offset);
      result.put(String.valueOf(id), decodeValue(String.valueOf(id), type, data));
    }
    return position == payload.length ? result : new LinkedHashMap<>();
  }

  private Object decodeValue(String code, int wireType, byte[] data) {
    T4AContracts.DpSchema schema = device == null ? null : device.schema.get(code);
    String type = schemaType(schema);
    if (wireType == 1 || type.contains("bool")) return data.length > 0 && data[0] != 0;
    if (wireType == 2 || type.contains("value") || type.contains("int")) {
      long value = 0; for (byte item : data) value = (value << 8) | (item & 0xFF); return value > Integer.MAX_VALUE ? value : (int) value;
    }
    if (wireType == 4 || type.contains("enum")) {
      if (data.length == 1) {
        int index = data[0] & 0xFF;
        String mapped = enumValue(schema, index);
        if (mapped == null) {
          String marker = "enum:" + code + ":" + hex(data);
          if (loggedDpLayouts.add(marker)) {
            raw("DP_ENUM_UNMAPPED code=" + code + " raw=" + hex(data));
          }
        }
        return mapped == null ? index : mapped;
      }
      return new String(data, StandardCharsets.UTF_8);
    }
    if (wireType == 3 || type.contains("string")) return new String(data, StandardCharsets.UTF_8);
    if (wireType == 5 && data.length <= 4) {
      long value = 0; for (byte item : data) value = (value << 8) | (item & 0xFF); return value > Integer.MAX_VALUE ? value : (int) value;
    }
    return Arrays.copyOf(data, data.length);
  }

  private static String enumValue(T4AContracts.DpSchema schema, int index) {
    JSONArray range = enumRange(schema);
    if (range == null || index < 0 || index >= range.length()) return null;
    Object value = range.opt(index);
    return value == null || JSONObject.NULL.equals(value) ? null : String.valueOf(value);
  }

  private static JSONArray enumRange(T4AContracts.DpSchema schema) {
    if (schema == null || schema.property == null || schema.property.isBlank()) return null;
    try {
      String property = schema.property.trim();
      return property.startsWith("[")
          ? new JSONArray(property)
          : new JSONObject(property).optJSONArray("range");
    } catch (Exception ignored) {
      return null;
    }
  }

  private byte[] encodeDps(Map<String, Object> values, boolean withSequence) {
    ByteBuffer out = ByteBuffer.allocate(1 + (withSequence ? 4 : 0) + (values == null ? 0 : values.size() * 260)).order(ByteOrder.BIG_ENDIAN);
    out.put((byte) 0);
    if (withSequence) out.putInt(nextSequence);
    if (values != null) for (Map.Entry<String, Object> entry : values.entrySet()) {
      int id; try { id = Integer.parseInt(entry.getKey()); } catch (NumberFormatException error) { continue; }
      byte[] data = encodeValue(entry.getKey(), entry.getValue());
      if (data.length > 65535) throw new IllegalArgumentException("DP value too large");
      out.put((byte) id).put((byte) typeOf(entry.getKey())).putShort((short) data.length).put(data);
    }
    return Arrays.copyOf(out.array(), out.position());
  }

  private int typeOf(String code) {
    String type = schemaType(device == null ? null : device.schema.get(code));
    if (type.contains("bool")) return 1;
    if (type.contains("value") || type.contains("int")) return 2;
    if (type.contains("enum")) return 4;
    if (type.contains("bitmap")) return 5;
    return 3;
  }

  private static String schemaType(T4AContracts.DpSchema schema) {
    if (schema == null) return "";
    if (schema.property != null && !schema.property.isBlank()) {
      try {
        String declared = new JSONObject(schema.property).optString("type", "");
        if (!declared.isBlank()) return declared.toLowerCase(Locale.ROOT);
      } catch (Exception ignored) {
        // Fall through to the provider's top-level type when property is malformed.
      }
    }
    return schema.type == null ? "" : schema.type.toLowerCase(Locale.ROOT);
  }
  private byte[] encodeValue(String code, Object value) {
    int type = typeOf(code);
    if (type == 1) return new byte[] {Boolean.TRUE.equals(value) ? (byte) 1 : 0};
    if (type == 2) {
      int integer = value instanceof Number
          ? ((Number) value).intValue()
          : Integer.parseInt(String.valueOf(value));
      return ByteBuffer.allocate(4).putInt(integer).array();
    }
    if (type == 4) return new byte[] {(byte) enumIndex(code, value)};
    if (value instanceof byte[]) return (byte[]) value;
    return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
  }

  private int enumIndex(String code, Object value) {
    if (value instanceof Number) return ((Number) value).intValue();
    T4AContracts.DpSchema schema = device == null ? null : device.schema.get(code);
    JSONArray range = enumRange(schema);
    String requested = String.valueOf(value);
    if (range != null) {
      for (int index = 0; index < range.length(); index++) {
        Object item = range.opt(index);
        if (item != null && requested.equals(String.valueOf(item))) return index;
      }
    }
    try {
      return Integer.parseInt(requested);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException("unknown enum value for DP " + code + ": " + requested);
    }
  }

  private static String hex(byte[] data) {
    StringBuilder out = new StringBuilder(data.length * 2);
    for (byte item : data) out.append(String.format(Locale.ROOT, "%02X", item & 0xFF));
    return out.toString();
  }

  private void fail(String reason) {
    T4AContracts.Device current;
    synchronized (lock) { if (!nativeAttempt && gatt == null && !nativeConnected) return; current = device; nativeAttempt = false; nativeConnected = false; }
    handler.removeCallbacks(connectTimeout);
    detachNative(false);
    raw("CONNECT_DIRECT_FAILED reason=" + reason + " fallback=true");
    if (current != null && !usingFallback) { usingFallback = true; fallback.attach(current, listener); fallback.connect(current); }
  }

  private void disconnectNative(String reason) { detachNative(true); raw("SESSION_DISCONNECTED reason=" + reason); }

  private void raw(String message) { rawLog.accept("[BLE/NATIVE] " + message); }

  private static String safeReason(Exception error) {
    String message = error == null ? null : error.getMessage();
    if (message == null || message.isBlank()) return "unspecified";
    return message.replaceAll("[^A-Za-z0-9_ -]", "_");
  }

  private void detachNative(boolean notify) {
    handler.removeCallbacks(connectTimeout);
    BluetoothGatt active;
    synchronized (lock) { active = gatt; gatt = null; nativeAttempt = false; nativeConnected = false; writeCharacteristic = null; infoReceived = false; pendingResults.clear(); reassembler.reset(); }
    synchronized (lock) { pendingMac = null; connectionAttempts = 0; }
    if (active != null) closeGatt(active);
    if (notify && listener != null && device != null) listener.onConnectionChanged(device.id, false);
  }

  private void closeGatt(BluetoothGatt active) {
    try { active.disconnect(); } catch (RuntimeException ignored) {}
    try { active.close(); } catch (RuntimeException ignored) {}
  }

  private static String normalizeMac(String mac) { String value = mac.trim().toUpperCase(Locale.ROOT); if (BluetoothAdapter.checkBluetoothAddress(value)) return value; String compact = value.replace(":", "").replace("-", ""); if (compact.length()!=12) return value; StringBuilder out=new StringBuilder(17); for(int i=0;i<12;i+=2){if(out.length()>0)out.append(':');out.append(compact,i,i+2);} return out.toString(); }
  private static String maskMac(String mac) { return mac == null || mac.length()<5 ? "<unknown>" : "**:**:**:**:" + mac.substring(mac.length()-5); }
}
