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
    private TextView status, deviceInfo, log, cloudStatus, pairingStatus;
    private EditText emailInput, passwordInput;
    private LinearLayout authPanel, devicePanel, controlsPanel;
    private ScanDeviceBean tuyaCandidate;
    private long activeHomeId;
    private IThingDevice thingDevice;
    private DeviceBean activeDevice;
    private final Map<String, Object> currentDps = new HashMap<>();

    private final Runnable statusPoller = new Runnable() {
        @Override
        public void run() {
            if (activeDevice != null && thingDevice != null) syncState();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        if (manager != null) adapter = manager.getAdapter();
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
        scanButton = new Button(this); scanButton.setText("BUSCAR NOVO PATINETE"); scanButton.setOnClickListener(v -> beginScan()); devicePanel.addView(scanButton);
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
            @Override public void onDpUpdate(String id, Map<String, Object> dps) { runOnUiThread(() -> { if (dps != null) { currentDps.putAll(dps); appendLog("Estado atualizado"); renderControls(); } }); }
            @Override public void onRemoved(String id) { runOnUiThread(() -> setStatus("Removido", Color.RED)); }
            @Override public void onStatusChanged(String id, boolean online) { runOnUiThread(() -> { setStatus(online ? "CONECTADO" : "OFFLINE", online ? 0xFF2E7D32 : Color.RED); if (online) handler.post(statusPoller); else handler.removeCallbacks(statusPoller); renderControls(); }); }
            @Override public void onNetworkStatusChanged(String id, boolean o) {}
            @Override public void onDevInfoUpdate(String id) {}
        });
        renderControls();
        startAutomaticConnection();
        handler.post(statusPoller);
    }

    private void syncState() { 
        if (activeDevice != null) { 
            DeviceBean d = ThingHomeSdk.getDataInstance().getDeviceBean(activeDevice.getDevId());
            if (d != null) { activeDevice = d; if (d.getDps() != null) currentDps.putAll(d.getDps()); }
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
        
        // Cabeçalho de Telemetria (Velocidade e Bateria)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(10), 0, dp(10));

        // Velocímetro
        float speed = 0; Object speedObj = currentDps.get("2");
        if (speedObj != null) { try { speed = Float.parseFloat(speedObj.toString()) / 10.0f; } catch (Exception ignored) {} }
        TextView sv = new TextView(this); sv.setText(String.format(Locale.ROOT, "%.1f", speed)); sv.setTextSize(64); sv.setTextColor(Color.BLACK);
        
        TextView unit = new TextView(this); unit.setText(" km/h"); unit.setTextSize(20); unit.setTextColor(Color.GRAY);
        
        // Bateria em destaque
        int battery = 0; Object batObj = currentDps.get("3");
        if (batObj != null) { try { battery = Integer.parseInt(batObj.toString()); } catch (Exception ignored) {} }
        TextView bv = new TextView(this);
        bv.setText(String.format(Locale.ROOT, "   🔋 %d%%", battery));
        bv.setTextSize(28);
        bv.setTextColor(battery > 20 ? 0xFF2E7D32 : Color.RED);

        header.addView(sv); header.addView(unit); header.addView(bv);
        controlsPanel.addView(header);

        Map<String, SchemaBean> schemas = activeDevice.getSchemaMap();
        if (schemas == null || schemas.isEmpty()) { TextView t = new TextView(this); t.setText("Sincronizando..."); controlsPanel.addView(t); return; }
        
        LinearLayout sec = createGroup("SEGURANÇA"), lgt = createGroup("ILUMINAÇÃO"), cfg = createGroup("AJUSTES DO T4A"), sts = createGroup("ESTADO E TELEMETRIA");
        
        for (Map.Entry<String, SchemaBean> e : new TreeMap<>(schemas).entrySet()) {
            String id = e.getKey(); SchemaBean s = e.getValue(); Object v = currentDps.get(id);
            if ("2".equals(id) || "3".equals(id)) continue; // Já mostrados no cabeçalho
            
            TextView label = new TextView(this); label.setText(displayDpName(id, s) + ": " + formatValue(id, v));
            label.setTextSize(16); label.setTextColor(Color.BLACK); label.setPadding(0, dp(10), 0, dp(2));
            
            if ("1".equals(id)) { 
                sec.addView(label); 
                addIconPairBtn(sec, id, "🔒 BLOQUEAR", "🔓 LIBERAR", false, true); 
            }
            else if ("8".equals(id)) { 
                lgt.addView(label); 
                addIconPairBtn(lgt, id, "💡 ACENDER", "🌑 APAGAR", true, false); 
            }
            else if ("14".equals(id)) { 
                cfg.addView(label); addSpeedPresets(cfg, id); 
            }
            else if ("16".equals(id)) {
                cfg.addView(label); addStartModePresets(cfg, id);
            }
            else if ("13".equals(id)) {
                cfg.addView(label); addIconPairBtn(cfg, id, "CRUISE ON", "CRUISE OFF", true, false);
            }
            else if (isAct(id)) { 
                cfg.addView(label); 
                if ("bool".equals(s.getType())) addBoolBtn(cfg, id); 
                else if ("enum".equals(s.getType())) addEnumBtn(cfg, id, s); 
                else if ("value".equals(s.getType())) addValueBtn(cfg, id, s); 
            } else { 
                label.setTextColor(0xFF2E7D32); sts.addView(label); 
            }
        }
        if (sec.getChildCount() > 1) controlsPanel.addView(sec);
        if (lgt.getChildCount() > 1) controlsPanel.addView(lgt);
        if (cfg.getChildCount() > 1) controlsPanel.addView(cfg);
        if (sts.getChildCount() > 1) controlsPanel.addView(sts);
    }

    private LinearLayout createGroup(String t) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(0, dp(15), 0, dp(15)); TextView tv = new TextView(this); tv.setText(t); tv.setTextColor(0xFF1565C0); tv.setTextSize(18); tv.setPadding(0, dp(10), 0, dp(5)); c.addView(tv); return c; }
    
    private void addIconPairBtn(LinearLayout g, String id, String tOn, String tOff, boolean vOn, boolean vOff) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        Button b1 = new Button(this); b1.setText(tOn); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        b1.setLayoutParams(lp); b1.setOnClickListener(v -> publish(id, vOn));
        Button b2 = new Button(this); b2.setText(tOff); b2.setLayoutParams(lp); b2.setOnClickListener(v -> publish(id, vOff));
        row.addView(b1); row.addView(b2); g.addView(row);
    }

    private void addBoolBtn(LinearLayout g, String id) { addIconPairBtn(g, id, "ATIVAR", "DESATIVAR", true, false); }

    private void addSpeedPresets(LinearLayout g, String id) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"WALK 5km", "ECO 25km", "RACE 32km", "SPORT 40km"};
        String[] values = {"level_0", "level_1", "level_2", "level_3"};
        for (int i=0; i<4; i++) {
            final String val = values[i]; Button b = new Button(this); b.setText(names[i]); b.setTextSize(10);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f); b.setLayoutParams(lp);
            b.setOnClickListener(v -> publish(id, val)); row.addView(b);
        }
        g.addView(row);
    }

    private void addStartModePresets(LinearLayout g, String id) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"PARTIDA ZERO", "PARTIDA KICK"};
        String[] codes = {"zero_start", "not_zero_start"};
        for (int i=0; i<2; i++) {
            final String c = codes[i]; Button b = new Button(this); b.setText(labels[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f); b.setLayoutParams(lp);
            b.setOnClickListener(v -> publish(id, c)); row.addView(b);
        }
        g.addView(row);
    }

    private void addEnumBtn(LinearLayout g, String id, SchemaBean s) {
        try {
            JSONArray range = JSON.parseObject(s.getProperty()).getJSONArray("range");
            if (range != null) { for (int i = 0; i < range.size(); i++) { final String opt = range.getString(i); Button b = new Button(this); b.setText(translateEnum(id, opt)); b.setOnClickListener(v -> publish(id, opt)); g.addView(b); } }
        } catch (Exception ignored) {}
    }

    private void addValueBtn(LinearLayout g, String id, SchemaBean s) {
        try {
            JSONObject prop = JSON.parseObject(s.getProperty()); final int min = prop.getIntValue("min"), max = prop.getIntValue("max"), step = prop.getIntValue("step") > 0 ? prop.getIntValue("step") : 1;
            LinearLayout r = new LinearLayout(this);
            Button b1 = new Button(this); b1.setText(" - "); b1.setOnClickListener(v -> { Object cur = currentDps.get(id); int val = (cur instanceof Integer) ? (Integer)cur : min; if (val - step >= min) publish(id, val - step); });
            Button b2 = new Button(this); b2.setText(" + "); b2.setOnClickListener(v -> { Object cur = currentDps.get(id); int val = (cur instanceof Integer) ? (Integer)cur : min; if (val + step <= max) publish(id, val + step); });
            r.addView(b1); r.addView(b2); g.addView(r);
        } catch (Exception ignored) {}
    }

    private String formatValue(String id, Object v) {
        if (v == null) return "---"; String s = v.toString();
        if ("1".equals(id)) return s.equals("false") || s.equals("0") ? "🔒 BLOQUEADO" : "🔓 LIBERADO";
        if ("8".equals(id)) return s.equals("true") || s.equals("1") ? "💡 ACESO" : "🌑 APAGADO";
        if ("13".equals(id)) return s.equals("true") || s.equals("1") ? "ATIVO" : "INATIVO";
        if ("14".equals(id)) {
            if (s.equals("level_0")) return "WALK 5km/h"; if (s.equals("level_1")) return "ECO 25km/h";
            if (s.equals("level_2")) return "RACE 32km/h"; if (s.equals("level_3")) return "SPORT 40km/h";
        }
        if ("16".equals(id)) return s.equals("zero_start") ? "PARTIDA ZERO" : "KICK START";
        if ("12".equals(id) || "5".equals(id)) { try { return String.format(Locale.ROOT, "%.1f km", Float.parseFloat(s) / 10.0f); } catch (Exception ignored) {} }
        if ("106".equals(id)) { try { return String.format(Locale.ROOT, "%.1f V", Float.parseFloat(s) / 10.0f); } catch (Exception ignored) {} }
        if (v instanceof Boolean) return (Boolean)v ? "ATIVO" : "INATIVO"; return s;
    }

    private String translateEnum(String id, String opt) {
        if ("14".equals(id)) {
            if (opt.equals("level_0")) return "WALK"; if (opt.equals("level_1")) return "ECO";
            if (opt.equals("level_2")) return "RACE"; if (opt.equals("level_3")) return "SPORT";
        }
        if ("16".equals(id)) return opt.equals("zero_start") ? "ZERO" : "KICK";
        return opt;
    }

    private String displayDpName(String id, SchemaBean s) {
        switch (id) {
            case "1": return "Trava do Motor"; case "8": return "Farol Frontal"; case "11": return "Unidade"; case "12": return "Odômetro Total"; case "13": return "Controle de Cruzeiro"; case "14": return "Limite de Velocidade"; case "16": return "Modo de Partida";
            case "101": return "Bateria (%)"; case "102": return "Velocidade"; case "5": return "Odômetro Parcial"; case "105": return "Corrente Motor"; case "106": return "Tensão Bateria";
            default: return (s != null && s.getCode() != null) ? s.getCode().toUpperCase() : "DADO " + id;
        }
    }

    private boolean isAct(String id) { return "1".equals(id) || "8".equals(id) || "11".equals(id) || "13".equals(id) || "14".equals(id) || "16".equals(id); }
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
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(statusPoller); }
    @Override protected void onResume() { super.onResume(); if (activeDevice != null) { startAutomaticConnection(); handler.post(statusPoller); } }
    @Override protected void onDestroy() { if (thingDevice != null) { thingDevice.unRegisterDevListener(); thingDevice.onDestroy(); } super.onDestroy(); }
}
