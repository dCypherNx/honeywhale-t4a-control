package br.com.t4acontrol.backend;

import android.os.Handler;
import android.os.Looper;

import com.alibaba.fastjson.JSON;
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
import com.thingclips.smart.sdk.api.IBleActivatorListener;
import com.thingclips.smart.sdk.api.IDeviceListener;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.thingclips.smart.sdk.api.IThingActivatorGetToken;
import com.thingclips.smart.sdk.api.IThingDevice;
import com.thingclips.smart.sdk.bean.BleActivatorBean;
import com.thingclips.smart.sdk.bean.DeviceBean;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class T4ABackend {
    public interface Listener {
        void onState(T4AState state);
        void onEvent(String event);
    }

    private static final long STATE_REFRESH_MS = 2000L;
    private static final long RECONNECT_MS = 10000L;

    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Object> dps = new HashMap<>();
    private final Map<String, T4AState.DpInfo> schema = new HashMap<>();

    private boolean authenticated;
    private String account = "";
    private T4AState.Pairing pairing = T4AState.Pairing.UNPAIRED;
    private boolean connected;
    private String message = "";
    private long homeId;
    private DeviceBean device;
    private IThingDevice thingDevice;
    private ScanDeviceBean candidate;
    private int rssi;
    private boolean running;

    private final Runnable maintenance = new Runnable() {
        @Override public void run() {
            if (!running) return;
            refreshFromCache();
            if (device != null && !ThingHomeSdk.getBleManager().isBleLocalOnline(device.getDevId())) {
                connectPairedDevice();
            }
            handler.postDelayed(this, connected ? STATE_REFRESH_MS : RECONNECT_MS);
        }
    };

    public T4ABackend(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        running = true;
        User user = ThingHomeSdk.getUserInstance().getUser();
        if (user == null) {
            authenticated = false;
            emit("Entre para acessar o T4A");
            return;
        }
        authenticated = true;
        account = user.getEmail() == null ? "" : user.getEmail();
        queryHomes();
        handler.removeCallbacks(maintenance);
        handler.post(maintenance);
    }

    public void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
    }

    public void destroy() {
        stop();
        ThingHomeSdk.getBleOperator().stopLeScan();
        if (thingDevice != null) {
            thingDevice.unRegisterDevListener();
            thingDevice.onDestroy();
            thingDevice = null;
        }
    }

    public void login(String email, String password) {
        message = "Autenticando…";
        emitState();
        ThingHomeSdk.getUserInstance().loginWithEmail("55", email, password, new ILoginCallback() {
            @Override public void onSuccess(User user) {
                authenticated = true;
                account = user.getEmail() == null ? email : user.getEmail();
                event("Conta autenticada");
                queryHomes();
            }
            @Override public void onError(String code, String error) { emit("Falha no login: " + error); }
        });
    }

    public void scan() {
        if (!authenticated || homeId == 0 || device != null) return;
        candidate = null;
        pairing = T4AState.Pairing.SCANNING;
        emit("Procurando T4A…");
        LeScanSetting setting = new LeScanSetting.Builder().setTimeout(15000)
                .addScanType(ScanType.SINGLE).setNeedBoundResult(false).build();
        ThingHomeSdk.getBleOperator().startLeScan(setting, found -> handler.post(() -> {
            if (found == null || found.getIsbind() || candidate != null) return;
            candidate = found;
            rssi = found.getRssi();
            pairing = T4AState.Pairing.READY;
            ThingHomeSdk.getBleOperator().stopLeScan();
            emit("T4A pronto para parear");
        }));
    }

    public void pair() {
        if (candidate == null || homeId == 0) return;
        pairing = T4AState.Pairing.PAIRING;
        emit("Pareando…");
        ThingHomeSdk.getActivatorInstance().getActivatorToken(homeId, new IThingActivatorGetToken() {
            @Override public void onSuccess(String token) { handler.post(() -> activate(token)); }
            @Override public void onFailure(String code, String error) {
                pairing = T4AState.Pairing.READY;
                emit("Falha ao obter token: " + code);
            }
        });
    }

    public void publish(String dpId, Object value) {
        if (thingDevice == null) return;
        Map<String, Object> command = Collections.singletonMap(dpId, value);
        thingDevice.publishDps(JSON.toJSONString(command), new IResultCallback() {
            @Override public void onSuccess() { event("Comando DP " + dpId + " aceito; aguardando confirmação"); }
            @Override public void onError(String code, String error) { emit("Comando recusado: " + code); }
        });
    }

    public void unpair() {
        if (thingDevice == null) return;
        pairing = T4AState.Pairing.REMOVING;
        emit("Removendo pareamento…");
        thingDevice.removeDevice(new IResultCallback() {
            @Override public void onSuccess() { handler.post(() -> clearDevice("T4A liberado para outro aplicativo")); }
            @Override public void onError(String code, String error) {
                pairing = T4AState.Pairing.PAIRED;
                emit("Falha ao remover pareamento: " + code);
            }
        });
    }

    private void queryHomes() {
        ThingHomeSdk.getHomeManagerInstance().queryHomeList(new IThingGetHomeListCallback() {
            @Override public void onSuccess(List<HomeBean> homes) {
                handler.post(() -> {
                    if (homes == null || homes.isEmpty()) {
                        pairing = T4AState.Pairing.NO_HOME;
                        emit("Conta sem casa configurada");
                        return;
                    }
                    homeId = homes.get(0).getHomeId();
                    loadHome();
                });
            }
            @Override public void onError(String code, String error) { emit("Falha ao consultar casas: " + code); }
        });
    }

    private void loadHome() {
        ThingHomeSdk.newHomeInstance(homeId).getHomeDetail(new IThingHomeResultCallback() {
            @Override public void onSuccess(HomeBean home) {
                handler.post(() -> {
                    List<DeviceBean> devices = home.getDeviceList();
                    if (devices == null || devices.isEmpty()) clearDevice("Nenhum T4A pareado");
                    else attach(devices.get(0));
                });
            }
            @Override public void onError(String code, String error) { emit("Falha ao carregar T4A: " + code); }
        });
    }

    private void activate(String token) {
        BleActivatorBean bean = new BleActivatorBean(candidate);
        bean.homeId = homeId;
        bean.address = candidate.getAddress();
        bean.uuid = candidate.getUuid();
        bean.productId = candidate.getProductId();
        bean.deviceType = candidate.getDeviceType();
        Map<String, Object> ext = new HashMap<>();
        ext.put("token", token);
        bean.setExtendsData(ext);
        ThingHomeSdk.getActivator().newBleActivator().startActivator(bean, new IBleActivatorListener() {
            @Override public void onSuccess(DeviceBean result) { handler.post(() -> attach(result)); }
            @Override public void onFailure(int code, String msg, Object handle) {
                pairing = T4AState.Pairing.READY;
                emit("Pareamento falhou: " + code);
            }
        });
    }

    private void attach(DeviceBean value) {
        if (thingDevice != null) thingDevice.unRegisterDevListener();
        device = value;
        pairing = T4AState.Pairing.PAIRED;
        dps.clear();
        if (value.getDps() != null) dps.putAll(value.getDps());
        schema.clear();
        if (value.getSchemaMap() != null) {
            for (Map.Entry<String, SchemaBean> entry : value.getSchemaMap().entrySet()) {
                SchemaBean item = entry.getValue();
                schema.put(entry.getKey(), new T4AState.DpInfo(item.getCode(), item.getMode(), item.getType()));
            }
        }
        thingDevice = ThingHomeSdk.newDeviceInstance(value.getDevId());
        thingDevice.registerDeviceListener(new IDeviceListener() {
            @Override public void onDpUpdate(String id, Map<String, Object> update) {
                handler.post(() -> {
                    if (update != null) dps.putAll(update);
                    connected = ThingHomeSdk.getBleManager().isBleLocalOnline(value.getDevId());
                    emitState();
                });
            }
            @Override public void onRemoved(String id) { handler.post(() -> clearDevice("T4A removido")); }
            @Override public void onStatusChanged(String id, boolean online) {
                handler.post(() -> { connected = ThingHomeSdk.getBleManager().isBleLocalOnline(id); emitState(); });
            }
            @Override public void onNetworkStatusChanged(String id, boolean online) { }
            @Override public void onDevInfoUpdate(String id) { handler.post(T4ABackend.this::loadHome); }
        });
        connectPairedDevice();
        emit("T4A pareado");
    }

    private void connectPairedDevice() {
        if (device == null) return;
        BleConnectBuilder builder = new BleConnectBuilder().setDevId(device.getDevId())
                .setUuid(device.getUuid()).setDirectConnect(true).setAutoConnect(true).setScanTimeout(30);
        ThingHomeSdk.getBleManager().connectBleDevice(Collections.singletonList(builder));
        message = connected ? "T4A conectado" : "T4A pareado · conectando…";
        emitState();
    }

    private void refreshFromCache() {
        if (device == null) return;
        DeviceBean latest = ThingHomeSdk.getDataInstance().getDeviceBean(device.getDevId());
        if (latest != null) {
            device = latest;
            if (latest.getDps() != null) dps.putAll(latest.getDps());
        }
        connected = ThingHomeSdk.getBleManager().isBleLocalOnline(device.getDevId());
        message = connected ? "T4A conectado" : "T4A pareado · aguardando aproximação";
        emitState();
    }

    private void clearDevice(String reason) {
        if (thingDevice != null) {
            thingDevice.unRegisterDevListener();
            thingDevice.onDestroy();
        }
        thingDevice = null;
        device = null;
        candidate = null;
        connected = false;
        dps.clear();
        schema.clear();
        pairing = T4AState.Pairing.UNPAIRED;
        emit(reason);
    }

    private void emit(String value) { message = value; emitState(); }
    private void event(String value) { listener.onEvent(value); }
    private void emitState() {
        String name = device == null ? "" : device.getName();
        String mac = device == null ? "" : device.getMac();
        listener.onState(new T4AState(authenticated, account, pairing, connected, name, mac,
                rssi, dps, schema, message));
    }
}
