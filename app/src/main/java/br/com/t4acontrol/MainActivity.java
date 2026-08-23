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
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
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
import com.thingclips.smart.android.user.api.IRegisterCallback;
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
    private TextView status, deviceInfo, log, cloudStatus, pairingStatus, brakeView;
    private EditText emailInput, passwordInput;
    private LinearLayout authPanel, devicePanel, controlsPanel;
    private ScanDeviceBean tuyaCandidate;
    private long activeHomeId;
    private IThingDevice thingDevice;
    private DeviceBean activeDevice;
    private final Map<String, Object> currentDps = new HashMap<>();
    private boolean isForeground = false;

    private final Runnable statusPoller = new Runnable() {
        @Override
        public void run() {
            if (activeDevice != null && thingDevice != null) syncState();
            handler.postDelayed(this, 200);
        }
    };

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (activeDevice != null) {
                boolean online = false;
                Boolean onlineObj = activeDevice.getIsOnline();
                if (onlineObj != null) online = onlineObj;
                
                if (!online) {
                    appendLog("Tentando reconexão automática...");
                    startAutomaticConnection();
                }
            }
            long delay = isForeground ? 30000L : 300000L;
            handler.postDelayed(this, delay);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        if (manager != null) adapter = manager.getAdapter();
        ensurePermissions();
        User cur = ThingHomeSdk.getUserInstance().getUser();
        if (cur != null) {
            cloudStatus.setText("CONTA ATIVA: " + cur.getEmail());
            showDeviceFlow();
            queryHomes();
        }
    }

    private void buildUi() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(20), dp(20), dp(100));
        TextView title = new TextView(this); title.setText("T4A CONTROL"); title.setTextSize(26); title.setTextColor(0xFF101820); body.addView(title);
        cloudStatus = new TextView(this); body.addView(cloudStatus);
        authPanel = new LinearLayout(this); authPanel.setOrientation(LinearLayout.VERTICAL);
        emailInput = new EditText(this); emailInput.setHint("E-mail"); authPanel.addView(emailInput);
        passwordInput = new EditText(this); passwordInput.setHint("Senha"); passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); authPanel.addView(passwordInput);
        Button loginBtn = new Button(this); loginBtn.setText("ENTRAR"); loginBtn.setOnClickListener(v -> loginToTuya()); authPanel.addView(loginBtn);
        body.addView(authPanel);
        devicePanel = new LinearLayout(this); devicePanel.setOrientation(LinearLayout.VERTICAL); devicePanel.setVisibility(View.GONE);
        status = new TextView(this); status.setTextSize(18); devicePanel.addView(status);
        brakeView = new TextView(this); brakeView.setText("⚠️ FREIO ACIONADO"); brakeView.setTextColor(Color.RED); brakeView.setTextSize(24); brakeView.setGravity(Gravity.CENTER); brakeView.setVisibility(View.GONE); devicePanel.addView(brakeView);
        pairingStatus = new TextView(this); pairingStatus.setTextSize(16); devicePanel.addView(pairingStatus);
        deviceInfo = new TextView(this); deviceInfo.setPadding(0, 0, 0, dp(10)); devicePanel.addView(deviceInfo);
        scanButton = new Button(this); scanButton.setText("BUSCAR NOVO PATINETE"); scanButton.setOnClickListener(v -> beginScan()); devicePanel.addView(scanButton);
        connectButton = new Button(this); connectButton.setText("PAREAR"); connectButton.setEnabled(false); connectButton.setOnClickListener(v -> startTuyaActivation()); devicePanel.addView(connectButton);
        controlsPanel = new LinearLayout(this); controlsPanel.setOrientation(LinearLayout.VERTICAL); devicePanel.addView(controlsPanel);
        Button unpairBtn = new Button(this); unpairBtn.setText("REMOVER VÍNCULO"); unpairBtn.setTextColor(Color.RED); unpairBtn.setOnClickListener(v -> removePairing()); devicePanel.addView(unpairBtn);
        log = new TextView(this); log.setTextSize(10); log.setPadding(0, dp(20), 0, dp(50)); devicePanel.addView(log);
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
        if (activeDevice != null && activeDevice.getIsOnline()) { setStatus("Conectado", 0xFF2E7D32); return; }
        tuyaCandidate = null; connectButton.setEnabled(false);
        LeScanSetting settings = new LeScanSetting.Builder().setTimeout(15000).addScanType(ScanType.SINGLE).setNeedBoundResult(true).build();
        ThingHomeSdk.getBleOperator().startLeScan(settings, scanDevice -> runOnUiThread(() -> {
            if (scanDevice == null || tuyaCandidate != null) return;
            tuyaCandidate = scanDevice;
            deviceInfo.setText("T4A Detectado.");
            connectButton.setEnabled(true); setStatus("Pronto para parear", 0xFF2E7D32);
        }));
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

    private void proceedActivation(String pairingToken) {
        setStatus("Ativando...", Color.BLUE);
        BleActivatorBean bean = new BleActivatorBean(tuyaCandidate);
        bean.homeId = activeHomeId; bean.address = tuyaCandidate.getAddress(); bean.uuid = tuyaCandidate.getUuid(); bean.productId = tuyaCandidate.getProductId(); bean.deviceType = tuyaCandidate.getDeviceType();
        HashMap<String, Object> ext = new HashMap<>(); ext.put("token", pairingToken); bean.setExtendsData(ext);
        ThingHomeSdk.getActivator().newBleActivator().startActivator(bean, new IBleActivatorListener() {
            @Override public void onSuccess(DeviceBean d) { runOnUiThread(() -> { setStatus("VINCULADO!", Color.GREEN); attachDevice(d); }); }
            @Override public void onFailure(int c, String m, Object h) { runOnUiThread(() -> { setStatus("Falhou: " + c, Color.RED); connectButton.setEnabled(true); }); }
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
            @Override public void onDpUpdate(String id, Map<String, Object> dps) {
                if (dps != null) { appendLog("RAW: " + dps); runOnUiThread(() -> { currentDps.putAll(dps); checkBrake(dps); renderControls(); }); }
            }
            @Override public void onRemoved(String id) { runOnUiThread(() -> setStatus("Removido", Color.RED)); }
            @Override public void onStatusChanged(String id, boolean online) { runOnUiThread(() -> { setStatus(online ? "CONECTADO" : "OFFLINE", online ? 0xFF2E7D32 : Color.RED); if (online) handler.post(statusPoller); else handler.removeCallbacks(statusPoller); renderControls(); }); }
            @Override public void onNetworkStatusChanged(String id, boolean o) {}
            @Override public void onDevInfoUpdate(String id) {}
        });
        renderControls();
        startAutomaticConnection();
        handler.post(statusPoller);
    }

    private void checkBrake(Map<String, Object> dps) {
        if (dps.containsKey("39")) {
            int v = 0; try { v = Integer.parseInt(dps.get("39").toString()); } catch (Exception e) {}
            final boolean b = (v & 0x04) != 0; runOnUiThread(() -> brakeView.setVisibility(b ? View.VISIBLE : View.GONE));
        }
    }

    private void syncState() { 
        if (activeDevice != null) { 
            DeviceBean d = ThingHomeSdk.getDataInstance().getDeviceBean(activeDevice.getDevId());
            if (d != null) { activeDevice = d; if (d.getDps() != null) { currentDps.putAll(d.getDps()); checkBrake(d.getDps()); } }
            renderControls(); 
        } 
    }

    private void startAutomaticConnection() {
        if (activeDevice == null) return;
        BleConnectBuilder b = new BleConnectBuilder().setDevId(activeDevice.getDevId()).setUuid(activeDevice.getUuid()).setAutoConnect(true).setDirectConnect(true);
        ThingHomeSdk.getBleManager().connectBleDevice(Collections.singletonList(b));
    }

    private void renderControls() {
        controlsPanel.removeAllViews();
        if (activeDevice == null) return;
        boolean mi = "mile".equals(currentDps.get("11")); double r = mi ? 0.621371 : 1.0;
        float sRaw = 0; Object so = currentDps.get("2"); if (so != null) { try { sRaw = Float.parseFloat(so.toString()) / 10.0f; } catch (Exception e) {} }
        LinearLayout h = new LinearLayout(this); h.setOrientation(LinearLayout.HORIZONTAL); h.setGravity(Gravity.CENTER);
        TextView sv = new TextView(this); sv.setText(String.format(Locale.ROOT, "%.1f", sRaw * r)); sv.setTextSize(64); sv.setTextColor(Color.BLACK);
        TextView un = new TextView(this); un.setText(mi ? " mph" : " km/h"); un.setTextSize(20); un.setTextColor(Color.GRAY);
        int bat = 0; Object bo = currentDps.get("3"); if (bo != null) { try { bat = Integer.parseInt(bo.toString()); } catch (Exception e) {} }
        TextView bv = new TextView(this); bv.setText("   \ud83d\udd0b" + bat + "%"); bv.setTextSize(24); bv.setTextColor(bat > 20 ? 0xFF2E7D32 : Color.RED);
        h.addView(sv); h.addView(un); h.addView(bv); controlsPanel.addView(h);
        Map<String, SchemaBean> schemas = activeDevice.getSchemaMap(); if (schemas == null || schemas.isEmpty()) return;
        LinearLayout sec = createGroup("SEGURAN\u00c7A"), lgt = createGroup("ILUMINA\u00c7\u00c3O"), cfg = createGroup("AJUSTES T4A"), sts = createGroup("ESTADO");
        for (Map.Entry<String, SchemaBean> entry : new TreeMap<>(schemas).entrySet()) {
            String id = entry.getKey(); SchemaBean sc = entry.getValue(); Object v = currentDps.get(id);
            if (id.equals("2") || id.equals("3")) continue;
            TextView l = new TextView(this); l.setText(displayDpName(id, sc) + ": " + formatValue(id, v, r));
            l.setTextSize(16); l.setTextColor(Color.BLACK); l.setPadding(0, dp(10), 0, dp(2));
            if (id.equals("1")) { sec.addView(l); addIconPairBtn(sec, id, "\ud83d\udd12 BLOQUEAR", "\ud83d\udd13 LIBERAR", true, false); }
            else if (id.equals("8")) { lgt.addView(l); addIconPairBtn(lgt, id, "\ud83d\udca1 ACENDER", "\ud83c\udf11 APAGAR", true, false); }
            else if (id.equals("14")) { cfg.addView(l); addSpeedPresets(cfg, id, mi); }
            else if (id.equals("16")) { cfg.addView(l); addStartModePresets(cfg, id); }
            else if (isAct(id)) { cfg.addView(l); if ("bool".equals(sc.getType())) addBoolBtn(cfg, id); else if ("enum".equals(sc.getType())) addEnumBtn(cfg, id, sc); else if ("value".equals(sc.getType())) addValueBtn(cfg, id, sc); }
            else { l.setTextColor(0xFF2E7D32); sts.addView(l); }
        }
        if (sec.getChildCount() > 1) controlsPanel.addView(sec); if (lgt.getChildCount() > 1) controlsPanel.addView(lgt); if (cfg.getChildCount() > 1) controlsPanel.addView(cfg); if (sts.getChildCount() > 1) controlsPanel.addView(sts);
    }

    private LinearLayout createGroup(String t) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(0, dp(15), 0, dp(15)); TextView tv = new TextView(this); tv.setText(t); tv.setTextColor(0xFF1565C0); tv.setTextSize(18); tv.setPadding(0, dp(10), 0, dp(5)); c.addView(tv); return c; }
    private void addIconPairBtn(LinearLayout g, String id, String tOn, String tOff, boolean vOn, boolean vOff) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        Button b1 = new Button(this); b1.setText(tOn); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f); b1.setLayoutParams(lp); b1.setOnClickListener(v -> publish(id, vOn));
        Button b2 = new Button(this); b2.setText(tOff); b2.setLayoutParams(lp); b2.setOnClickListener(v -> publish(id, vOff));
        row.addView(b1); row.addView(b2); g.addView(row);
    }
    private void addBoolBtn(LinearLayout g, String id) { addIconPairBtn(g, id, "ATIVAR", "DESATIVAR", true, false); }
    private void addSpeedPresets(LinearLayout g, String id, boolean mi) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        String[] ns = mi ? new String[]{"WALK 4mi", "ECO 15mi", "RACE 21mi", "SPORT 27mi"} : new String[]{"WALK 6km", "ECO 25km", "RACE 35km", "SPORT 45km"};
        String[] vs = {"level_0", "level_1", "level_2", "level_3"};
        for (int i=0; i<4; i++) { final String val = vs[i]; Button b = new Button(this); b.setText(ns[i]); b.setTextSize(9); b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f)); b.setOnClickListener(v -> publish(id, val)); row.addView(b); }
        g.addView(row);
    }
    private void addStartModePresets(LinearLayout g, String id) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
        Button b1 = new Button(this); b1.setText("ZERO START"); b1.setOnClickListener(v -> publish(id, "zero_start"));
        Button b2 = new Button(this); b2.setText("KICK START"); b2.setOnClickListener(v -> publish(id, "not_zero_start"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f); b1.setLayoutParams(lp); b2.setLayoutParams(lp); r.addView(b1); r.addView(b2); g.addView(r);
    }
    private void addEnumBtn(LinearLayout g, String id, SchemaBean s) { try { JSONArray range = JSON.parseObject(s.getProperty()).getJSONArray("range"); if (range != null) { for (int i = 0; i < range.size(); i++) { final String opt = range.getString(i); Button b = new Button(this); b.setText(translateEnum(id, opt)); b.setOnClickListener(v -> publish(id, opt)); g.addView(b); } } } catch (Exception ignored) {} }
    private void addValueBtn(LinearLayout g, String id, SchemaBean s) { try { JSONObject prop = JSON.parseObject(s.getProperty()); final int min = prop.getIntValue("min"), max = prop.getIntValue("max"), step = prop.getIntValue("step") > 0 ? prop.getIntValue("step") : 1; LinearLayout r = new LinearLayout(this); Button b1 = new Button(this); b1.setText(" - "); b1.setOnClickListener(v -> { Object cur = currentDps.get(id); int val = (cur instanceof Integer) ? (Integer)cur : min; if (val - step >= min) publish(id, val - step); }); Button b2 = new Button(this); b2.setText(" + "); b2.setOnClickListener(v -> { Object cur = currentDps.get(id); int val = (cur instanceof Integer) ? (Integer)cur : min; if (val + step <= max) publish(id, val + step); }); r.addView(b1); r.addView(b2); g.addView(r); } catch (Exception ignored) {} }
    private String formatValue(String id, Object v, double r) {
        if (v == null) return "---"; String s = v.toString();
        if (id.equals("1")) return s.equals("true") || s.equals("1") ? "\ud83d\udd12 BLOQUEADO" : "\ud83d\udd13 LIBERADO";
        if (id.equals("8")) return s.equals("true") || s.equals("1") ? "\ud83d\udca1 ACESO" : "\ud83c\udf11 APAGADO";
        if (id.equals("14")) { if (s.equals("level_0")) return "WALK"; if (s.equals("level_1")) return "ECO"; if (s.equals("level_2")) return "RACE"; if (s.equals("level_3")) return "SPORT"; }
        if (id.equals("16")) return s.equals("zero_start") ? "PARTIDA ZERO" : "KICK START";
        if (id.equals("12") || id.equals("5")) { try { return String.format(Locale.ROOT, "%.1f%s", (Float.parseFloat(s) / 10.0) * r, r \u003c 1.0 ? " mi" : " km"); } catch (Exception ignored) {} }
        if (id.equals("106")) { try { return String.format(Locale.ROOT, "%.1f V", Float.parseFloat(s) / 10.0f); } catch (Exception ignored) {} }
        if (v instanceof Boolean) return (Boolean)v ? "ATIVO" : "INATIVO"; return s;
    }
    private String translateEnum(String id, String opt) { if (id.equals("14")) { if (opt.equals("level_0")) return "WALK"; if (opt.equals("level_1")) return "ECO"; if (opt.equals("level_2")) return "RACE"; if (opt.equals("level_3")) return "SPORT"; } if (id.equals("16")) return opt.equals("zero_start") ? "ZERO" : "KICK"; return opt; }
    private String displayDpName(String id, SchemaBean s) { switch (id) { case "1": return "Trava do Motor"; case "8": return "Farol Frontal"; case "11": return "Unidade"; case "12": return "Od\u00f4metro Total"; case "13": return "Cruise Control"; case "14": return "Limite Velocidade"; case "16": return "Modo Partida"; case "101": return "Bateria (%)"; case "102": return "Velocidade"; case "5": return "Od\u00f4metro Parcial"; case "105": return "Corrente Motor"; case "106": return "Tens\u00e3o Bateria"; default: return s != null ? s.getCode().toUpperCase() : "DADO " + id; } }
    private boolean isAct(String id) { return "1".equals(id) || "8".equals(id) || "11".equals(id) || "13".equals(id) || "14".equals(id) || "16".equals(id); }
    private void publish(String id, Object val) { if (thingDevice == null) return; Map<String, Object> dps = new HashMap<>(); dps.put(id, val); thingDevice.publishDps(JSON.toJSONString(dps), new IResultCallback() { @Override public void onSuccess() { appendLog("Comando OK: " + id); } @Override public void onError(String c, String e) { appendLog("Erro: " + e); } }); }
    private void removePairing() { if (thingDevice != null) thingDevice.removeDevice(new IResultCallback() { @Override public void onSuccess() { runOnUiThread(() -> { setStatus("Desvinculado", Color.GREEN); controlsPanel.removeAllViews(); }); } @Override public void onError(String c, String e) {} }); }
    private void ensurePermissions() { requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION}, 100); }
    private void setStatus(String t, int c) { status.setText(t); status.setTextColor(c); }
    private void appendLog(String m) { Log.d(TAG, m); runOnUiThread(() -> { if (log != null) { log.setVisibility(View.VISIBLE); log.append(DateFormat.getTimeInstance().format(new Date()) + " " + m + "\n"); } }); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private void showDeviceFlow() { authPanel.setVisibility(View.GONE); devicePanel.setVisibility(View.VISIBLE); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(statusPoller); }
    @Override protected void onResume() { super.onResume(); if (activeDevice != null) { startAutomaticConnection(); handler.post(statusPoller); } }
    @Override protected void onDestroy() { if (thingDevice != null) { thingDevice.unRegisterDevListener(); thingDevice.onDestroy(); } super.onDestroy(); }
}
