package br.com.t4acontrol.backend;

import br.com.t4acontrol.backend.T4AContracts.*;
import com.alibaba.fastjson.JSON;
import com.thingclips.smart.android.ble.api.BleLogCallback;
import com.thingclips.smart.android.ble.api.LeScanSetting;
import com.thingclips.smart.android.ble.api.ScanDeviceBean;
import com.thingclips.smart.android.ble.api.ScanType;
import com.thingclips.smart.android.ble.builder.BleConnectBuilder;
import com.thingclips.smart.android.device.bean.SchemaBean;
import com.thingclips.smart.android.user.api.ILoginCallback;
import com.thingclips.smart.android.user.bean.User;
import com.thingclips.smart.home.sdk.ThingHomeSdk;
import com.thingclips.smart.home.sdk.bean.HomeBean;
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback;
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback;
import com.thingclips.smart.sdk.api.*;
import com.thingclips.smart.sdk.bean.BleActivatorBean;
import com.thingclips.smart.sdk.bean.DeviceBean;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** ThingClips implementation of both replaceable boundaries. No Tuya type escapes this class. */
public final class TuyaT4APlatform implements T4AProvisioner, T4ATransport {
  private static final int BUSINESS_LOG_MAX_CHARS = 420;
  private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile("(?i)(local.?key|sec.?key|dev.?key|auth.?key|login.?key|token|password|passwd|pwd)\\s*[:=]\\s*[^,;\\s}]+", Pattern.CASE_INSENSITIVE);

  private final Map<String, ScanDeviceBean> discoveries = new HashMap<>();
  private final Consumer<String> rawLog;
  private final BleLogCallback bleLogCallback;
  private final Set<String> knownSecrets = new LinkedHashSet<>();
  private IThingDevice activeDevice;

  public TuyaT4APlatform() { this(null); }

  public TuyaT4APlatform(Consumer<String> rawLog) {
    this.rawLog = rawLog == null ? ignored -> {} : rawLog;
    this.bleLogCallback = (fileName, content) ->
        this.rawLog.accept("[SDK/BLELOG] tag=" + safeTag(fileName)
            + " content=" + sanitizeBusinessLog(content));
    ThingHomeSdk.getBleManager().registerBusinessLog(bleLogCallback);
    this.rawLog.accept("[SDK/BLELOG] REGISTERED internalCommunication=true secretsRedacted=true");
  }

  @Override public String currentAccount() { User user = ThingHomeSdk.getUserInstance().getUser(); return user == null ? null : T4AContracts.value(user.getEmail()); }
  @Override public void login(String countryCode, String email, String password, Callback<String> callback) { ThingHomeSdk.getUserInstance().loginWithEmail(countryCode, email, password, new ILoginCallback() { @Override public void onSuccess(User user) { String account = user == null ? "" : T4AContracts.value(user.getEmail()); callback.onSuccess(account.isEmpty() ? email : account); } @Override public void onError(String code, String error) { callback.onError(code, error); } }); }
  @Override public void loadPrimaryHome(Callback<Home> callback) { ThingHomeSdk.getHomeManagerInstance().queryHomeList(new IThingGetHomeListCallback() { @Override public void onSuccess(List<HomeBean> homes) { if (homes == null || homes.isEmpty()) { callback.onSuccess(new Home(0L, "", Collections.emptyList())); return; } long homeId = homes.get(0).getHomeId(); ThingHomeSdk.newHomeInstance(homeId).getHomeDetail(new IThingHomeResultCallback() { @Override public void onSuccess(HomeBean home) { List<Device> devices = new ArrayList<>(); if (home != null && home.getDeviceList() != null) for (DeviceBean device : home.getDeviceList()) devices.add(toDevice(device)); callback.onSuccess(new Home(homeId, home == null ? "" : T4AContracts.value(home.getName()), devices)); } @Override public void onError(String code, String error) { callback.onError(code, error); } }); } @Override public void onError(String code, String error) { callback.onError(code, error); } }); }
  @Override public void startDiscovery(long timeoutMs, DiscoveryListener listener) { discoveries.clear(); LeScanSetting setting = new LeScanSetting.Builder().setTimeout(timeoutMs).addScanType(ScanType.SINGLE).setNeedBoundResult(true).build(); ThingHomeSdk.getBleOperator().startLeScan(setting, found -> { if (found == null) return; String discoveryId = discoveryId(found); discoveries.put(discoveryId, found); listener.onDevice(new DiscoveredDevice(discoveryId, found.getAddress(), found.getUuid(), found.getProductId(), found.getDeviceType(), found.getIsbind(), found.getRssi())); }); }
  @Override public void stopDiscovery() { ThingHomeSdk.getBleOperator().stopLeScan(); }
  @Override public void pair(long homeId, DiscoveredDevice device, Callback<Device> callback) { ScanDeviceBean nativeDevice = discoveries.get(device.discoveryId); if (nativeDevice == null) { callback.onError("DISCOVERY_EXPIRED", "O resultado do scan não está mais disponível"); return; } ThingHomeSdk.getActivatorInstance().getActivatorToken(homeId, new IThingActivatorGetToken() { @Override public void onSuccess(String token) { activate(homeId, nativeDevice, token, callback); } @Override public void onFailure(String code, String error) { callback.onError(code, error); } }); }
  private void activate(long homeId, ScanDeviceBean candidate, String token, Callback<Device> callback) { BleActivatorBean bean = new BleActivatorBean(candidate); bean.homeId = homeId; bean.address = candidate.getAddress(); bean.uuid = candidate.getUuid(); bean.productId = candidate.getProductId(); bean.deviceType = candidate.getDeviceType(); Map<String,Object> extra = new HashMap<>(); extra.put("token", token); bean.setExtendsData(extra); ThingHomeSdk.getActivator().newBleActivator().startActivator(bean, new IBleActivatorListener() { @Override public void onSuccess(DeviceBean result) { callback.onSuccess(toDevice(result)); } @Override public void onFailure(int code, String message, Object handle) { callback.onError(String.valueOf(code), message); } }); }
  @Override public void attach(Device device, DeviceListener listener) { detach(); activeDevice = ThingHomeSdk.newDeviceInstance(device.id); activeDevice.registerDeviceListener(new IDeviceListener() { @Override public void onDpUpdate(String id, Map<String,Object> update) { listener.onDpUpdate(id, update); } @Override public void onRemoved(String id) { listener.onRemoved(id); } @Override public void onStatusChanged(String id, boolean online) { listener.onConnectionChanged(id, isConnected(id)); } @Override public void onNetworkStatusChanged(String id, boolean online) {} @Override public void onDevInfoUpdate(String id) { listener.onDeviceInfoChanged(id); } }); }
  @Override public void detach() { if (activeDevice != null) { activeDevice.unRegisterDevListener(); activeDevice.onDestroy(); activeDevice = null; } }
  @Override public void connect(Device device) {
    DeviceBean source = ThingHomeSdk.getDataInstance().getDeviceBean(device.id);
    rememberSecrets(source);
    logBleConnectionSelection(device.id, source);
    BleConnectBuilder builder = new BleConnectBuilder().setDevId(device.id).setUuid(device.uuid).setDirectConnect(true).setAutoConnect(true).setScanTimeout(30);
    ThingHomeSdk.getBleManager().connectBleDevice(Collections.singletonList(builder));
  }
  @Override public boolean isConnected(String deviceId) { return ThingHomeSdk.getBleManager().isBleLocalOnline(deviceId); }
  @Override public Device cachedDevice(String deviceId) { return toDevice(ThingHomeSdk.getDataInstance().getDeviceBean(deviceId)); }
  @Override public void publish(String deviceId, Map<String,Object> dps, ResultCallback callback) { if (activeDevice == null) { callback.onError("NOT_ATTACHED", "Dispositivo sem sessão ativa"); return; } activeDevice.publishDps(JSON.toJSONString(dps), new IResultCallback() { @Override public void onSuccess() { callback.onSuccess(); } @Override public void onError(String code, String error) { callback.onError(code, error); } }); }
  @Override public void readRssi(String mac, RssiCallback callback) { ThingHomeSdk.getBleOperator().readBluetoothRssi(mac, callback::onResult); }
  @Override public void remove(String deviceId, ResultCallback callback) { if (activeDevice == null) { callback.onError("NOT_ATTACHED", "Dispositivo sem sessão ativa"); return; } activeDevice.removeDevice(new IResultCallback() { @Override public void onSuccess() { callback.onSuccess(); } @Override public void onError(String code, String error) { callback.onError(code, error); } }); }
  @Override public void destroy() { stopDiscovery(); detach(); discoveries.clear(); knownSecrets.clear(); ThingHomeSdk.getBleManager().unregisterBusinessLog(bleLogCallback); }

  private void logBleConnectionSelection(String deviceId, DeviceBean source) {
    try {
      int deviceType = ThingHomeSdk.getBleManager().getDeviceType(deviceId);
      int connectAbility = ThingHomeSdk.getBleManager().getBleConnectAbility(deviceId);
      int expandAttr = ThingHomeSdk.getBleManager().getBleExpandAttr(deviceId);
      int configFlag = ThingHomeSdk.getBleManager().getConfigDeviceFlag(deviceId);
      rawLog.accept("[SDK/BLESELECT] deviceType=" + deviceType
          + " connectAbility=" + connectAbility
          + " expandAttr=" + expandAttr
          + " configFlag=" + configFlag
          + " pv=" + safeMetadata(source == null ? null : source.getPv())
          + " protocolAttribute=" + (source == null ? "<none>" : source.getProtocolAttribute())
          + " baseAttribute=" + (source == null ? "<none>" : source.getBaseAttribute())
          + " ability=" + (source == null ? "<none>" : source.getAbility())
          + " localKeyLength=" + secretLength(source == null ? null : source.getLocalKey())
          + " secKeyLength=" + secretLength(source == null ? null : source.getSecKey())
          + " devKeyLength=" + secretLength(source == null ? null : source.getDevKey())
          + " secretsLogged=false");
    } catch (Throwable error) {
      rawLog.accept("[SDK/BLESELECT] error=" + error.getClass().getSimpleName() + " secretsLogged=false");
    }
  }

  private static int secretLength(String value) { return value == null ? 0 : value.length(); }
  private static String safeMetadata(String value) {
    if (value == null || value.isBlank()) return "<none>";
    return value.replace(' ', '_').replace('\n', '_').replace('\r', '_');
  }

  private void rememberSecrets(DeviceBean source) {
    if (source == null) return;
    knownSecrets.clear();
    addSecret(source.getLocalKey());
    addSecret(source.getSecKey());
    addSecret(source.getDevKey());
  }

  private void addSecret(String value) {
    if (value == null || value.isEmpty()) return;
    knownSecrets.add(value);
    if (value.length() >= 6) knownSecrets.add(value.substring(0, 6));
  }

  private String sanitizeBusinessLog(String content) {
    if (content == null) return "<null>";
    String sanitized = SENSITIVE_ASSIGNMENT.matcher(content).replaceAll("$1=<redacted>");
    for (String secret : knownSecrets) {
      if (!secret.isEmpty()) sanitized = sanitized.replace(secret, "<redacted>");
    }
    sanitized = sanitized.replace('\n', ' ').replace('\r', ' ').trim();
    if (sanitized.length() > BUSINESS_LOG_MAX_CHARS) {
      sanitized = sanitized.substring(0, BUSINESS_LOG_MAX_CHARS) + "…";
    }
    return "len=" + content.length() + " " + sanitized;
  }

  private static String safeTag(String value) {
    if (value == null || value.isBlank()) return "<none>";
    return value.replace('\n', '_').replace('\r', '_');
  }

  private static String discoveryId(ScanDeviceBean device) { return T4AContracts.value(device.getUuid()) + "|" + T4AContracts.value(device.getAddress()); }
  private static String value(String value) { return value == null ? "" : value; }

  private static Device toDevice(DeviceBean source) {
    if (source == null) return null;
    Map<String,DpSchema> schema = new HashMap<>();
    if (source.getSchemaMap() != null) for (Map.Entry<String,SchemaBean> entry : source.getSchemaMap().entrySet()) { SchemaBean item = entry.getValue(); schema.put(entry.getKey(), new DpSchema(item.getCode(), item.getMode(), item.getType(), item.getProperty())); }
    Map<String,String> protocol = new LinkedHashMap<>();
    protocol.put("pv", value(source.getPv())); protocol.put("bv", value(source.getBv())); protocol.put("productVer", value(source.getProductVer())); protocol.put("verSw", value(source.getVerSw())); protocol.put("cadv", value(source.getCadv())); protocol.put("categoryCode", value(source.getCategoryCode())); protocol.put("deviceCategory", value(source.getDeviceCategory())); protocol.put("ability", String.valueOf(source.getAbility())); protocol.put("attribute", String.valueOf(source.getAttribute())); protocol.put("devAttribute", String.valueOf(source.getDevAttribute())); protocol.put("protocolAttribute", String.valueOf(source.getProtocolAttribute())); protocol.put("baseAttribute", String.valueOf(source.getBaseAttribute())); protocol.put("singleBle", String.valueOf(source.isSingleBle())); protocol.put("encrypted", String.valueOf(source.isEncrypt())); protocol.put("bleCommunication", String.valueOf(source.isHasBleCommunication()));
    String devKey = source.getDevKey(); String secKey = source.getSecKey();
    protocol.put("devKeyAvailable", String.valueOf(devKey != null && !devKey.isEmpty())); protocol.put("devKeyLength", String.valueOf(devKey == null ? 0 : devKey.length())); protocol.put("secKeyAvailable", String.valueOf(secKey != null && !secKey.isEmpty())); protocol.put("secKeyLength", String.valueOf(secKey == null ? 0 : secKey.length()));
    return new Device(source.getDevId(), source.getName(), source.getMac(), source.getUuid(), source.getProductId(), source.getLocalKey(), secKey, protocol, source.getDps(), schema);
  }
}
