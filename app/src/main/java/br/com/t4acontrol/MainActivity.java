package br.com.t4acontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.text.DateFormat;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.HashMap;

import com.thingclips.smart.android.user.api.ILoginCallback;
import com.thingclips.smart.android.user.bean.User;
import com.thingclips.smart.android.ble.api.LeScanSetting;
import com.thingclips.smart.android.ble.api.ScanDeviceBean;
import com.thingclips.smart.android.ble.api.ScanType;
import com.thingclips.smart.android.ble.builder.BleConnectBuilder;
import com.thingclips.smart.android.device.bean.SchemaBean;
import com.thingclips.smart.home.sdk.ThingHomeSdk;
import com.thingclips.smart.home.sdk.bean.HomeBean;
import com.thingclips.smart.home.sdk.callback.IThingGetHomeListCallback;
import com.thingclips.smart.home.sdk.callback.IThingHomeResultCallback;
import com.thingclips.smart.sdk.api.IBleActivatorListener;
import com.thingclips.smart.sdk.api.IDeviceListener;
import com.thingclips.smart.sdk.api.IResultCallback;
import com.thingclips.smart.sdk.api.IThingDevice;
import com.thingclips.smart.sdk.api.IThingActivatorGetToken;
import com.thingclips.smart.sdk.bean.BleActivatorBean;
import com.thingclips.smart.sdk.bean.DeviceBean;

public final class MainActivity extends Activity {
    private static final String TAG = "T4A_APP";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private Button scanButton, connectButton;
    private TextView status, deviceInfo, log, cloudStatus, pairingStatus;
    private EditText emailInput, passwordInput;
    private LinearLayout authPanel, devicePanel, controlsPanel;
    private ScanDeviceBean tuyaCandidate;
    private long activeHomeId;
    private IThingDevice thingDevice;
    private DeviceBean activeDevice;
    private final Map<String, Object> currentDps = new TreeMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        ensurePermissions();
        User cur = ThingHomeSdk.getUserInstance().getUser();
        if (cur != null) { cloudStatus.setText("CONTA: " + cur.getEmail()); showDeviceFlow(); queryHomes(); }
    }

    private void buildUi() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(20), dp(20), dp(20));
        TextView title = new TextView(this); title.setText("T4A CONTROL"); title.setTextSize(26); title.setTextColor(0xFF101820); body.addView(title);
        cloudStatus = new TextView(this); body.addView(cloudStatus);
        authPanel = new LinearLayout(this); authPanel.setOrientation(LinearLayout.VERTICAL);
        emailInput = new EditText(this); emailInput.setHint("E-mail"); authPanel.addView(emailInput);
        passwordInput = new EditText(this); passwordInput.setHint("Senha"); passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); authPanel.addView(passwordInput);
        Button loginBtn = new Button(this); loginBtn.setText("ENTRAR"); loginBtn.setOnClickListener(v -> loginToTuya()); authPanel.addView(loginBtn);
        body.addView(authPanel);
        devicePanel = new LinearLayout(this); devicePanel.setOrientation(LinearLayout.VERTICAL); devicePanel.setVisibility(View.GONE);
        status = new TextView(this); status.setTextSize(18); devicePanel.addView(status);
        pairingStatus = new TextView(this); pairingStatus.setTextSize(16); devicePanel.addView(pairingStatus);
        deviceInfo = new TextView(this); deviceInfo.setPadding(0, 0, 0, dp(10)); devicePanel.addView(deviceInfo);
        scanButton = new Button(this); scanButton.setText("BUSCAR PATINETE"); scanButton.setOnClickListener(v -> beginScan()); devicePanel.addView(scanButton);
        connectButton = new Button(this); connectButton.setText("PAREAR"); connectButton.setEnabled(false); connectButton.setOnClickListener(v -> startTuyaActivation()); devicePanel.addView(connectButton);
        controlsPanel = new LinearLayout(this); controlsPanel.setOrientation(LinearLayout.VERTICAL); devicePanel.addView(controlsPanel);
        Button unpairBtn = new Button(this); unpairBtn.setText("REMOVER VÍNCULO"); unpairBtn.setTextColor(Color.RED); unpairBtn.setOnClickListener(v -> removePairing()); devicePanel.addView(unpairBtn);
        log = new TextView(this); log.setTextSize(10); log.setVisibility(View.GONE); devicePanel.addView(log);
        body.addView(devicePanel);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll);
    }

    private void loginToTuya() {
        String em = emailInput.getText().toString().trim(); String pw = passwordInput.getText().toString();
        if (em.isEmpty() || pw.isEmpty()) return;
        ThingHomeSdk.getUserInstance().loginWithEmail("55", em, pw, new ILoginCallback() {
            @Override public void onSuccess(User u) { runOnUiThread(() -> { cloudStatus.setText("Logado: " + u.getEmail()); showDeviceFlow(); queryHomes(); }); }
            @Override public void onError(String c, String e) { runOnUiThread(() -> setStatus("Erro Login: " + e, Color.RED)); }
        });
    }

    private void queryHomes() {
        ThingHomeSdk.getHomeManagerInstance().queryHomeList(new IThingGetHomeListCallback() {
            @Override public void onSuccess(List<HomeBean> homes) {
                runOnUiThread(() -> { if (homes != null && !homes.isEmpty()) { activeHomeId = homes.get(0).getHomeId(); loadHomeDevice(); } });
            }
            @Override public void onError(String c, String e) {}
        });
    }

    private void beginScan() {
        tuyaCandidate = null; connectButton.setEnabled(false);
        LeScanSetting s = new LeScanSetting.Builder().setTimeout(15000).addScanType(ScanType.SINGLE).setNeedBoundResult(true).build();
        ThingHomeSdk.getBleOperator().startLeScan(s, d -> runOnUiThread(() -> { if (tuyaCandidate == null) { tuyaCandidate = d; deviceInfo.setText("T4A Detectado."); connectButton.setEnabled(true); setStatus("Pronto", 0xFF2E7D32); } }));
        setStatus("Buscando...", 0xFF1565C0);
    }

    private void startTuyaActivation() {
        if (tuyaCandidate == null || activeHomeId == 0) return;
        setStatus("Sincronizando...", Color.BLUE);
        ThingHomeSdk.getActivatorInstance().getActivatorToken(activeHomeId, new IThingActivatorGetToken() {
            @Override public void onSuccess(String token) { runOnUiThread(() -> proceedActivation(token)); }
            @Override public void onFailure(String c, String e) { runOnUiThread(() -> setStatus("Erro Token", Color.RED)); }
        });
    }

    private void proceedActivation(String t) {
        setStatus("Vinculando...", Color.BLUE);
        BleActivatorBean b = new BleActivatorBean(tuyaCandidate);
        b.homeId = activeHomeId; b.address = tuyaCandidate.getAddress(); b.uuid = tuyaCandidate.getUuid(); b.productId = tuyaCandidate.getProductId(); b.deviceType = tuyaCandidate.getDeviceType();
        Map<String, Object> ext = new HashMap<>(); ext.put("token", t); b.setExtendsData(ext);
        ThingHomeSdk.getActivator().newBleActivator().startActivator(b, new IBleActivatorListener() {
            @Override public void onSuccess(DeviceBean d) { runOnUiThread(() -> { setStatus("VINCULADO!", Color.GREEN); attachDevice(d); }); }
            @Override public void onFailure(int c, String m, Object h) { runOnUiThread(() -> { setStatus("Falha: " + c, Color.RED); connectButton.setEnabled(true); }); }
        });
    }

    private void loadHomeDevice() {
        ThingHomeSdk.newHomeInstance(activeHomeId).getHomeDetail(new IThingHomeResultCallback() {
            @Override public void onSuccess(HomeBean h) { runOnUiThread(() -> { List<DeviceBean> ds = h.getDeviceList(); if (ds != null && !ds.isEmpty()) attachDevice(ds.get(0)); else setStatus("Nenhum patinete.", Color.GRAY); }); }
            @Override public void onError(String c, String e) {}
        });
    }

    private void attachDevice(DeviceBean device) {
        if (thingDevice != null) thingDevice.unRegisterDevListener();
        activeDevice = device; pairingStatus.setText("DISPOSITIVO: " + device.getName());
        currentDps.clear(); if (device.getDps() != null) currentDps.putAll(device.getDps());
        thingDevice = ThingHomeSdk.newDeviceInstance(device.getDevId());
        thingDevice.registerDeviceListener(new IDeviceListener() {
            @Override public void onDpUpdate(String devId, Map<String, Object> dps) { runOnUiThread(() -> { if (dps != null) { currentDps.putAll(dps); appendLog("Mudança: " + dps.toString()); renderControls(); } }); }
            @Override public void onRemoved(String id) { runOnUiThread(() -> setStatus("Removido", Color.RED)); }
            @Override public void onStatusChanged(String id, boolean online) { runOnUiThread(() -> { setStatus(online ? "CONECTADO" : "OFFLINE", online ? 0xFF2E7D32 : Color.RED); renderControls(); }); }
            @Override public void onNetworkStatusChanged(String id, boolean o) {}
            @Override public void onDevInfoUpdate(String id) {}
        });
        renderControls();
        startAutomaticConnection();
    }

    private void startAutomaticConnection() {
        if (activeDevice == null) return;
        BleConnectBuilder b = new BleConnectBuilder().setDevId(activeDevice.getDevId()).setUuid(activeDevice.getUuid()).setAutoConnect(true).setDirectConnect(true);
        ThingHomeSdk.getBleManager().connectBleDevice(Collections.singletonList(b));
    }

    private void renderControls() {
        controlsPanel.removeAllViews();
        if (activeDevice == null) return;
        Map<String, SchemaBean> schemas = activeDevice.getSchemaMap();
        if (schemas == null || schemas.isEmpty()) { TextView t = new TextView(this); t.setText("Sincronizando..."); controlsPanel.addView(t); return; }
        LinearLayout sec = createGroup("SEGURANÇA"), lgt = createGroup("ILUMINAÇÃO"), cfg = createGroup("CONFIGURAÇÕES"), sts = createGroup("ESTADO E TELEMETRIA");
        for (Map.Entry<String, SchemaBean> e : new TreeMap<>(schemas).entrySet()) {
            String id = e.getKey(); SchemaBean s = e.getValue(); Object v = currentDps.get(id);
            TextView label = new TextView(this); label.setText(displayDpName(id, s) + ": " + formatValue(id, v));
            label.setTextSize(16); label.setTextColor(Color.BLACK); label.setPadding(0, dp(10), 0, dp(2));
            if ("1".equals(id)) { sec.addView(label); addBoolBtn(sec, id); }
            else if ("8".equals(id)) { lgt.addView(label); addBoolBtn(lgt, id); }
            else if (isAct(id)) { cfg.addView(label); if ("bool".equals(s.getType())) addBoolBtn(cfg, id); else if ("enum".equals(s.getType())) addEnumBtn(cfg, id, s); else if ("value".equals(s.getType())) addValueBtn(cfg, id, s); }
            else { label.setTextColor(0xFF2E7D32); sts.addView(label); }
        }
        if (sec.getChildCount() > 1) controlsPanel.addView(sec); if (lgt.getChildCount() > 1) controlsPanel.addView(lgt); if (cfg.getChildCount() > 1) controlsPanel.addView(cfg); if (sts.getChildCount() > 1) controlsPanel.addView(sts);
    }

    private LinearLayout createGroup(String t) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(0, dp(15), 0, dp(15)); TextView tv = new TextView(this); tv.setText(t); tv.setTextColor(0xFF1565C0); tv.setTextSize(18); tv.setPadding(0, dp(10), 0, dp(5)); c.addView(tv); return c; }
    private void addBoolBtn(LinearLayout g, String id) {
        LinearLayout r = new LinearLayout(this);
        Button b1 = new Button(this); b1.setText("LIGAR / ATIVAR"); b1.setOnClickListener(v -> publish(id, true));
        Button b2 = new Button(this); b2.setText("DESLIGAR / DESATIVAR"); b2.setOnClickListener(v -> publish(id, false));
        r.addView(b1); r.addView(b2); g.addView(r);
    }

    private void addEnumBtn(LinearLayout g, String id, SchemaBean s) {
        try {
            JSONArray range = JSON.parseObject(s.getProperty()).getJSONArray("range");
            if (range != null) { for (int i = 0; i < range.size(); i++) { final String opt = range.getString(i); Button b = new Button(this); b.setText(translateEnum(id, opt)); b.setOnClickListener(v -> publish(id, opt)); g.addView(b); } }
        } catch (java.lang.Exception ignored) {}
    }

    private void addValueBtn(LinearLayout g, String id, SchemaBean s) {
        try {
            JSONObject prop = JSON.parseObject(s.getProperty()); final int min = prop.getIntValue("min"), max = prop.getIntValue("max"), step = prop.getIntValue("step") > 0 ? prop.getIntValue("step") : 1;
            LinearLayout r = new LinearLayout(this);
            Button b1 = new Button(this); b1.setText(" - "); b1.setOnClickListener(v -> { Object cur = currentDps.get(id); int val = (cur instanceof Integer) ? (Integer)cur : min; if (val - step >= min) publish(id, val - step); });
            Button b2 = new Button(this); b2.setText(" + "); b2.setOnClickListener(v -> { Object cur = currentDps.get(id); int val = (cur instanceof Integer) ? (Integer)cur : min; if (val + step <= max) publish(id, val + step); });
            r.addView(b1); r.addView(b2); g.addView(r);
        } catch (java.lang.Exception ignored) {}
    }

    private String formatValue(String id, Object v) {
        if (v == null) return "---"; String s = v.toString();
        if ("1".equals(id)) return s.equals("true") || s.equals("1") ? "TRAVADO" : "DESTRAVADO";
        if ("8".equals(id)) return s.equals("true") || s.equals("1") ? "ACESO" : "APAGADO";
        if ("13".equals(id)) return s.equals("1") ? "MPH" : "km/h";
        if ("14".equals(id)) return s.equals("1") ? "KICK START" : "ZERO START";
        if ("16".equals(id)) return "MARCHA " + s;
        if (v instanceof java.lang.Boolean) return (java.lang.Boolean)v ? "ATIVO" : "INATIVO"; return s;
    }

    private String translateEnum(String id, String opt) {
        if ("13".equals(id)) return "1".equals(opt) ? "MPH" : "km/h";
        if ("14".equals(id)) return "1".equals(opt) ? "KICK" : "ZERO";
        if ("16".equals(id)) return "MARCHA " + opt; return opt;
    }

    private String displayDpName(String id, SchemaBean s) {
        switch (id) {
            case "1": return "Trava do Motor"; case "8": return "Farol Frontal"; case "11": return "Limite Velocidade"; case "13": return "Unidade"; case "14": return "Modo Partida"; case "16": return "Marcha Atual"; case "17": return "Cruise Control";
            case "101": return "Bateria"; case "102": return "Velocidade"; case "103": return "Odômetro Total"; case "104": return "Odômetro Parcial"; case "105": return "Corrente Motor"; case "106": return "Tensão Bateria";
            default: return s != null ? s.getCode().toUpperCase() : "DADO " + id;
        }
    }

    private boolean isAct(String id) { return "1".equals(id) || "8".equals(id) || "11".equals(id) || "13".equals(id) || "14".equals(id) || "16".equals(id) || "17".equals(id); }
    private void publish(String id, Object val) {
        if (thingDevice == null) return;
        Map<String, Object> dps = new HashMap<>(); dps.put(id, val);
        thingDevice.publishDps(JSON.toJSONString(dps), new IResultCallback() { @Override public void onSuccess() { appendLog("Comando OK: " + id); } @Override public void onError(String c, String e) { appendLog("Erro: " + e); } });
    }

    private void removePairing() { if (thingDevice != null) thingDevice.removeDevice(new IResultCallback() { @Override public void onSuccess() { runOnUiThread(() -> { setStatus("Desvinculado", Color.GREEN); controlsPanel.removeAllViews(); }); } @Override public void onError(String c, String e) {} }); }
    private void ensurePermissions() { requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION}, 100); }
    private void setStatus(String t, int c) { status.setText(t); status.setTextColor(c); }
    private void appendLog(String m) { Log.d(TAG, m); runOnUiThread(() -> { if (log != null) { log.setVisibility(View.VISIBLE); log.append(DateFormat.getTimeInstance().format(new Date()) + " " + m + "\n"); } }); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private void showDeviceFlow() { authPanel.setVisibility(View.GONE); devicePanel.setVisibility(View.VISIBLE); }
    @Override protected void onResume() { super.onResume(); if (activeDevice != null) startAutomaticConnection(); }
    @Override protected void onDestroy() { if (thingDevice != null) { thingDevice.unRegisterDevListener(); thingDevice.onDestroy(); } super.onDestroy(); }
}
