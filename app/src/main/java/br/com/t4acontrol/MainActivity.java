package br.com.t4acontrol;

import android.Manifest;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import java.util.HashMap;
import java.util.Objects;

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
    private static final String DP_BRAKE = "39";
    private static final String DP_SPEED = "2";
    private static final String DP_BATTERY = "3";
    private static final String DP_UNIT = "11";
    private static final String DP_ODO_TOTAL = "12";
    private static final String DP_LOCK = "1";
    private static final String DP_LIGHT = "8";
    private static final String DP_SPEED_LIMIT = "14";
    private static final String DP_START_MODE = "16";
    private static final String DP_CRUISE = "13";
    private static final String DP_BATTERY_PERCENT = "101";
    private static final String DP_SPEED_VALUE = "102";
    private static final String DP_ODO_TRIP = "5";
    private static final String DP_CURRENT = "105";
    private static final String DP_VOLTAGE = "106";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button connectButton;
    private TextView status, deviceInfo, log, cloudStatus, pairingStatus, brakeView;
    private EditText emailInput, passwordInput;
    private LinearLayout authPanel, devicePanel, controlsPanel;
    private ScanDeviceBean tuyaCandidate;
    private long activeHomeId;
    private IThingDevice thingDevice;
    private DeviceBean activeDevice;
    private final Map<String, Object> currentDps = new HashMap<>();
    private final List<String> logList = new ArrayList<>();
    private boolean isSettingsMode = false;
    private boolean isAppVisible = false;

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
                DeviceBean d = ThingHomeSdk.getDataInstance().getDeviceBean(activeDevice.getDevId());
                boolean online = d != null && d.getIsOnline();
                if (!online) {
                    appendLog("Auto-reconexão agressiva...");
                    startAutomaticConnection();
                }
            }
            handler.postDelayed(this, isAppVisible ? 5000L : 30000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        ensurePermissions();
        User cur = ThingHomeSdk.getUserInstance().getUser();
        if (cur != null) {
            cloudStatus.setText(getString(R.string.active_account, cur.getEmail()));
            showDeviceFlow();
            queryHomes();
        }
    }

    private void buildUi() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(20), dp(20), dp(20), dp(100));
        TextView title = new TextView(this); title.setText(R.string.title_dash); title.setTextSize(26); title.setTextColor(0xFF101820); body.addView(title);
        cloudStatus = new TextView(this); body.addView(cloudStatus);
        authPanel = new LinearLayout(this); authPanel.setOrientation(LinearLayout.VERTICAL);
        emailInput = new EditText(this); emailInput.setHint(R.string.email_hint); authPanel.addView(emailInput);
        passwordInput = new EditText(this); passwordInput.setHint(R.string.password_hint); passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); authPanel.addView(passwordInput);
        Button loginBtn = new Button(this); loginBtn.setText(R.string.login_btn); loginBtn.setOnClickListener(v -> loginToTuya()); authPanel.addView(loginBtn);
        body.addView(authPanel);
        devicePanel = new LinearLayout(this); devicePanel.setOrientation(LinearLayout.VERTICAL); devicePanel.setVisibility(View.GONE);
        status = new TextView(this); status.setTextSize(18); devicePanel.addView(status);
        brakeView = new TextView(this); brakeView.setText(R.string.brake_active); brakeView.setTextColor(Color.RED); brakeView.setTextSize(24); brakeView.setGravity(Gravity.CENTER); brakeView.setVisibility(View.GONE); devicePanel.addView(brakeView);
        pairingStatus = new TextView(this); pairingStatus.setTextSize(16); devicePanel.addView(pairingStatus);
        deviceInfo = new TextView(this); deviceInfo.setPadding(0, 0, 0, dp(10)); devicePanel.addView(deviceInfo);
        
        LinearLayout nav = new LinearLayout(this); nav.setOrientation(LinearLayout.HORIZONTAL);
        Button btnDash = new Button(this); btnDash.setText(R.string.nav_dash); btnDash.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        Button btnCfg = new Button(this); btnCfg.setText(R.string.nav_configs); btnCfg.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
        btnDash.setOnClickListener(v -> { isSettingsMode = false; renderControls(); });
        btnCfg.setOnClickListener(v -> { isSettingsMode = true; renderControls(); });
        nav.addView(btnDash); nav.addView(btnCfg); devicePanel.addView(nav);

        Button scanButton = new Button(this); scanButton.setText(R.string.scan_new_btn); scanButton.setOnClickListener(v -> beginScan()); devicePanel.addView(scanButton);
        connectButton = new Button(this); connectButton.setText(R.string.pair_btn); connectButton.setEnabled(false); connectButton.setOnClickListener(v -> startTuyaActivation()); devicePanel.addView(connectButton);
        controlsPanel = new LinearLayout(this); controlsPanel.setOrientation(LinearLayout.VERTICAL); devicePanel.addView(controlsPanel);
        Button unpairBtn = new Button(this); unpairBtn.setText(R.string.unpair_btn); unpairBtn.setTextColor(Color.RED); unpairBtn.setOnClickListener(v -> removePairing()); devicePanel.addView(unpairBtn);
        log = new TextView(this); log.setTextSize(10); log.setPadding(0, dp(20), 0, dp(50)); devicePanel.addView(log);
        body.addView(devicePanel);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll);
    }

    private void loginToTuya() {
        String em = emailInput.getText().toString().trim(); String pw = passwordInput.getText().toString();
        if (em.isEmpty() || pw.isEmpty()) return;
        ThingHomeSdk.getUserInstance().loginWithEmail(getString(R.string.country_code), em, pw, new ILoginCallback() {
            @Override public void onSuccess(User u) { runOnUiThread(() -> { cloudStatus.setText(getString(R.string.logged_in, u.getEmail())); showDeviceFlow(); queryHomes(); }); }
            @Override public void onError(String c, String e) { runOnUiThread(() -> setStatus(getString(R.string.login_error, e), Color.RED)); }
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
        if (activeDevice != null && activeDevice.getIsOnline()) { setStatus(getString(R.string.connected), 0xFF2E7D32); return; }
        tuyaCandidate = null; connectButton.setEnabled(false);
        LeScanSetting settings = new LeScanSetting.Builder().setTimeout(15000).addScanType(ScanType.SINGLE).setNeedBoundResult(true).build();
        ThingHomeSdk.getBleOperator().startLeScan(settings, scanDevice -> runOnUiThread(() -> {
            if (scanDevice == null || tuyaCandidate != null) return;
            tuyaCandidate = scanDevice;
            deviceInfo.setText(getString(R.string.paired));
            connectButton.setEnabled(true); setStatus(getString(R.string.ready_to_pair), 0xFF2E7D32);
        }));
        setStatus(getString(R.string.searching), 0xFF1565C0);
    }

    private void startTuyaActivation() {
        if (tuyaCandidate == null || activeHomeId == 0) return;
        setStatus(getString(R.string.syncing), Color.BLUE);
        ThingHomeSdk.getActivatorInstance().getActivatorToken(activeHomeId, new IThingActivatorGetToken() {
            @Override public void onSuccess(String token) { runOnUiThread(() -> proceedActivation(token)); }
            @Override public void onFailure(String c, String e) { runOnUiThread(() -> setStatus(getString(R.string.token_error), Color.RED)); }
        });
    }

    private void proceedActivation(String pairingToken) {
        setStatus(getString(R.string.activating), Color.BLUE);
        BleActivatorBean bean = new BleActivatorBean(tuyaCandidate);
        bean.homeId = activeHomeId; bean.address = tuyaCandidate.getAddress(); bean.uuid = tuyaCandidate.getUuid(); bean.productId = tuyaCandidate.getProductId(); bean.deviceType = tuyaCandidate.getDeviceType();
        HashMap<String, Object> ext = new HashMap<>(); ext.put("token", pairingToken); bean.setExtendsData(ext);
        ThingHomeSdk.getActivator().newBleActivator().startActivator(bean, new IBleActivatorListener() {
            @Override public void onSuccess(DeviceBean d) { runOnUiThread(() -> { setStatus(getString(R.string.paired), Color.GREEN); attachDevice(d); }); }
            @Override public void onFailure(int c, String m, Object h) { runOnUiThread(() -> { setStatus(getString(R.string.activation_failed, c), Color.RED); connectButton.setEnabled(true); }); }
        });
    }

    private void loadHomeDevice() {
        ThingHomeSdk.newHomeInstance(activeHomeId).getHomeDetail(new IThingHomeResultCallback() {
            @Override public void onSuccess(HomeBean h) { runOnUiThread(() -> { List<DeviceBean> ds = h.getDeviceList(); if (ds != null && !ds.isEmpty()) attachDevice(ds.get(0)); else setStatus(getString(R.string.no_scooter), Color.GRAY); }); }
            @Override public void onError(String c, String e) {}
        });
    }

    private void attachDevice(DeviceBean device) {
        if (thingDevice != null) thingDevice.unRegisterDevListener();
        activeDevice = device; pairingStatus.setText(getString(R.string.device_label, device.getName()));
        currentDps.clear(); if (device.getDps() != null) currentDps.putAll(device.getDps());
        thingDevice = ThingHomeSdk.newDeviceInstance(device.getDevId());
        thingDevice.registerDeviceListener(new IDeviceListener() {
            @Override public void onDpUpdate(String id, Map<String, Object> dps) {
                if (dps != null) { appendLog("RAW: " + dps); runOnUiThread(() -> { currentDps.putAll(dps); checkBrake(dps); renderControls(); }); }
            }
            @Override public void onRemoved(String id) { runOnUiThread(() -> setStatus(getString(R.string.removed), Color.RED)); }
            @Override public void onStatusChanged(String id, boolean online) { runOnUiThread(() -> { setStatus(online ? getString(R.string.connected) : getString(R.string.offline), online ? 0xFF2E7D32 : Color.RED); if (online) handler.post(statusPoller); else handler.removeCallbacks(statusPoller); renderControls(); }); }
            @Override public void onNetworkStatusChanged(String id, boolean o) {}
            @Override public void onDevInfoUpdate(String id) {}
        });
        renderControls();
        startAutomaticConnection();
        handler.post(statusPoller);
    }

    private void checkBrake(Map<String, Object> dps) {
        Object obj = dps.get(DP_BRAKE);
        if (obj != null) {
            try {
                int vInt = Integer.parseInt(obj.toString());
                boolean isBraking = (vInt & 0x04) != 0;
                runOnUiThread(() -> brakeView.setVisibility(isBraking ? View.VISIBLE : View.GONE));
            } catch (Exception ignored) {}
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
        BleConnectBuilder b = new BleConnectBuilder().setDevId(activeDevice.getDevId()).setUuid(activeDevice.getUuid()).setDirectConnect(true);
        ThingHomeSdk.getBleManager().connectBleDevice(Collections.singletonList(b));
    }

    private void renderControls() {
        controlsPanel.removeAllViews();
        if (activeDevice == null) return;
        boolean mi = "mile".equals(currentDps.get(DP_UNIT)); double r = mi ? 0.621371 : 1.0;
        
        if (!isSettingsMode) {
            renderDashboard(mi, r);
        } else {
            renderSettings(mi, r);
        }
        updateLogView();
    }

    private void renderDashboard(boolean mi, double r) {
        float dashSpeed = parseSafeFloat(currentDps.get(DP_SPEED));
        LinearLayout dashRow = new LinearLayout(this); dashRow.setOrientation(LinearLayout.HORIZONTAL); dashRow.setGravity(Gravity.CENTER);
        TextView sv = new TextView(this); sv.setText(String.format(Locale.ROOT, "%.1f", dashSpeed * r)); sv.setTextSize(72); sv.setTextColor(Color.BLACK);
        TextView un = new TextView(this); un.setText(mi ? " mph" : " km/h"); un.setTextSize(24); un.setTextColor(Color.GRAY);
        dashRow.addView(sv); dashRow.addView(un); controlsPanel.addView(dashRow);

        int bat = parseSafeInt(currentDps.get(DP_BATTERY));
        TextView bv = new TextView(this); bv.setText(getString(R.string.battery_label, bat)); bv.setTextSize(28); bv.setGravity(Gravity.CENTER);
        bv.setTextColor(bat > 20 ? 0xFF2E7D32 : Color.RED); controlsPanel.addView(bv);

        LinearLayout unitBox = createGroup(getString(R.string.unit_box_title));
        addIconPairBtn(unitBox, DP_UNIT, getString(R.string.unit_mi), getString(R.string.unit_km), "mile", "km");
        controlsPanel.addView(unitBox);
        
        Object odoTotal = currentDps.get(DP_ODO_TOTAL);
        TextView ot = new TextView(this); ot.setText(getString(R.string.odo_total_label, formatValue(DP_ODO_TOTAL, odoTotal, r)));
        ot.setTextSize(18); ot.setPadding(0, dp(20), 0, 0); controlsPanel.addView(ot);
    }

    private int parseSafeInt(Object o) {
        if (o == null) return 0;
        try {
            return Integer.parseInt(o.toString());
        } catch (Exception ignored) {
            return 0;
        }
    }

    private float parseSafeFloat(Object o) {
        if (o == null) return 0f;
        try {
            return Float.parseFloat(o.toString()) / 10.0f;
        } catch (Exception ignored) {
            return 0f;
        }
    }

    private void renderSettings(boolean mi, double r) {
        Map<String, SchemaBean> schemas = activeDevice.getSchemaMap(); if (schemas == null || schemas.isEmpty()) return;
        LinearLayout sec = createGroup(getString(R.string.group_security)), lgt = createGroup(getString(R.string.group_lights)), cfg = createGroup(getString(R.string.group_settings)), sts = createGroup(getString(R.string.group_status));
        for (Map.Entry<String, SchemaBean> entry : new TreeMap<>(schemas).entrySet()) {
            String id = entry.getKey(); SchemaBean sc = entry.getValue(); Object v = currentDps.get(id);
            switch (id) {
                case DP_SPEED, DP_BATTERY -> { continue; }
                default -> {}
            }
            
            TextView l = new TextView(this); l.setText(getString(R.string.dp_display, displayDpName(id, sc), formatValue(id, v, r)));
            l.setTextSize(16); l.setTextColor(Color.BLACK); l.setPadding(0, dp(10), 0, dp(2));
            if (Objects.equals(id, DP_LOCK)) { sec.addView(l); addIconPairBtn(sec, id, "🔒 BLOQUEAR", "🔓 LIBERAR", true, false); }
            else if (Objects.equals(id, DP_LIGHT)) { lgt.addView(l); addIconPairBtn(lgt, id, "💡 ACENDER", "🌚 APAGAR", true, false); }
            else if (Objects.equals(id, DP_SPEED_LIMIT)) { cfg.addView(l); addSpeedPresets(cfg, id, mi); }
            else if (Objects.equals(id, DP_START_MODE)) { cfg.addView(l); addStartModePresets(cfg, id); }
            else if (isAct(id)) {
                cfg.addView(l);
                String type = sc.getType();
                switch (type) {
                    case "bool" -> addBoolBtn(cfg, id);
                    case "enum" -> addEnumBtn(cfg, id, sc);
                    case "value" -> addValueBtn(cfg, id, sc);
                    default -> {}
                }
            }
            else { l.setTextColor(0xFF2E7D32); sts.addView(l); }
        }
        if (sec.getChildCount() > 1) controlsPanel.addView(sec); if (lgt.getChildCount() > 1) controlsPanel.addView(lgt); if (cfg.getChildCount() > 1) controlsPanel.addView(cfg); if (sts.getChildCount() > 1) controlsPanel.addView(sts);
    }

    private void updateLogView() {
        if (log == null) return;
        StringBuilder logBuilder = new StringBuilder(isSettingsMode ? getString(R.string.log_full) : getString(R.string.log_recent));
        int logCount = isSettingsMode ? logList.size() : Math.min(logList.size(), 10);
        if (logCount > 0) {
            for (int logIdx = 0; logIdx < logCount; logIdx++) {
                logBuilder.append(logList.get(logList.size() - 1 - logIdx)).append("\n");
            }
        }
        log.setText(logBuilder);
    }

    private void appendLog(String m) {
        String msg = DateFormat.getTimeInstance().format(new Date()) + " " + m;
        Log.d(TAG, msg);
        logList.add(msg);
        runOnUiThread(this::updateLogView);
    }

    private LinearLayout createGroup(String t) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(0, dp(15), 0, dp(15)); TextView tv = new TextView(this); tv.setText(t); tv.setTextColor(0xFF1565C0); tv.setTextSize(18); tv.setPadding(0, dp(10), 0, dp(5)); c.addView(tv); return c; }
    private void addIconPairBtn(LinearLayout g, String id, String tOn, String tOff, Object vOn, Object vOff) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        Button b1 = new Button(this); b1.setText(tOn); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f); b1.setLayoutParams(lp); b1.setOnClickListener(v -> publish(id, vOn));
        Button b2 = new Button(this); b2.setText(tOff); b2.setLayoutParams(lp); b2.setOnClickListener(v -> publish(id, vOff));
        row.addView(b1); row.addView(b2); g.addView(row);
    }
    private void addBoolBtn(LinearLayout g, String id) { addIconPairBtn(g, id, getString(R.string.activate_btn), getString(R.string.deactivate_btn), true, false); }
    private void addSpeedPresets(LinearLayout g, String id, boolean mi) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        String[] ns = mi ? new String[]{"WALK 4mi", "ECO 15mi", "RACE 21mi", "SPORT 27mi"} : new String[]{"WALK 6km", "ECO 25km", "RACE 35km", "SPORT 45km"};
        String[] vs = {"level_0", "level_1", "level_2", "level_3"};
        for (int pIdx = 0; pIdx < 4; pIdx++) {
            final String val = vs[pIdx];
            Button b = new Button(this); b.setText(ns[pIdx]); b.setTextSize(9); b.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f)); b.setOnClickListener(v -> publish(id, val)); row.addView(b);
        }
        g.addView(row);
    }
    private void addStartModePresets(LinearLayout g, String id) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
        Button b1 = new Button(this); b1.setText(R.string.zero_start_btn); b1.setOnClickListener(v -> publish(id, "zero_start"));
        Button b2 = new Button(this); b2.setText(R.string.kick_start_btn); b2.setOnClickListener(v -> publish(id, "not_zero_start"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1.0f); b1.setLayoutParams(lp); b2.setLayoutParams(lp); r.addView(b1); r.addView(b2); g.addView(r);
    }
    private void addEnumBtn(LinearLayout g, String id, SchemaBean s) {
        try {
            JSONArray range = JSON.parseObject(s.getProperty()).getJSONArray("range");
            if (range != null && !range.isEmpty()) {
                for (int rIdx = 0; rIdx < range.size(); rIdx++) {
                    final String opt = range.getString(rIdx);
                    Button b = new Button(this); b.setText(translateEnum(id, opt)); b.setOnClickListener(v -> publish(id, opt)); g.addView(b);
                }
            }
        } catch (Exception ignored) {}
    }
    private void addValueBtn(LinearLayout g, String id, SchemaBean s) { try { JSONObject prop = JSON.parseObject(s.getProperty()); final int min = prop.getIntValue("min"), max = prop.getIntValue("max"), step = prop.getIntValue("step") > 0 ? prop.getIntValue("step") : 1; LinearLayout r = new LinearLayout(this); Button b1 = new Button(this); b1.setText(" -"); b1.setOnClickListener(v -> { Object cur = currentDps.get(id); int val = (cur instanceof Integer inv) ? inv : min; if (val - step >= min) publish(id, val - step); }); Button b2 = new Button(this); b2.setText(" +"); b2.setOnClickListener(v -> { Object cur = currentDps.get(id); int val = (cur instanceof Integer inv) ? inv : min; if (val + step <= max) publish(id, val + step); }); r.addView(b1); r.addView(b2); g.addView(r); } catch (Exception ignored) {} }
    private String formatValue(String id, Object v, double r) {
        if (v == null) return "---"; String s = v.toString();
        return switch (id) {
            case DP_LOCK -> isTrue(s) ? "🔒 BLOQUEADO" : "🔓 LIBERADO";
            case DP_LIGHT -> isTrue(s) ? "💡 ACESO" : "🌚 APAGADO";
            case DP_SPEED_LIMIT -> switch (s) {
                case "level_0" -> "WALK";
                case "level_1" -> "ECO";
                case "level_2" -> "RACE";
                case "level_3" -> "SPORT";
                default -> s;
            };
            case DP_START_MODE -> "zero_start".equals(s) ? "PARTIDA ZERO" : "KICK START";
            case DP_ODO_TOTAL, DP_ODO_TRIP -> {
                try {
                    yield String.format(Locale.ROOT, "%.1f%s", (Float.parseFloat(s) / 10.0) * r, r < 1.0 ? " mi" : " km");
                } catch (Exception ignored) {
                    yield s;
                }
            }
            case DP_VOLTAGE -> {
                try {
                    yield String.format(Locale.ROOT, "%.1f V", Float.parseFloat(s) / 10.0f);
                } catch (Exception ignored) {
                    yield s;
                }
            }
            default -> v instanceof Boolean b ? (b ? "ATIVO" : "INATIVO") : s;
        };
    }

    private boolean isTrue(String s) {
        return "true".equals(s) || "1".equals(s);
    }

    private String translateEnum(String id, String opt) {
        return switch (id) {
            case DP_SPEED_LIMIT -> switch (opt) {
                case "level_0" -> "WALK";
                case "level_1" -> "ECO";
                case "level_2" -> "RACE";
                case "level_3" -> "SPORT";
                default -> opt;
            };
            case DP_START_MODE -> "zero_start".equals(opt) ? "ZERO" : "KICK";
            default -> opt;
        };
    }

    private String displayDpName(String id, SchemaBean s) {
        return switch (id) {
            case DP_LOCK -> "Trava do Motor";
            case DP_LIGHT -> "Farol Frontal";
            case DP_UNIT -> "Unidade";
            case DP_ODO_TOTAL -> "Odômetro Total";
            case DP_CRUISE -> "Cruise Control";
            case DP_SPEED_LIMIT -> "Limite Velocidade";
            case DP_START_MODE -> "Modo Partida";
            case DP_BATTERY_PERCENT -> "Bateria (%)";
            case DP_SPEED_VALUE -> "Velocidade";
            case DP_ODO_TRIP -> "Odômetro Parcial";
            case DP_CURRENT -> "Corrente Motor";
            case DP_VOLTAGE -> "Tensão Bateria";
            default -> s != null ? s.getCode().toUpperCase() : "DADO " + id;
        };
    }

    private boolean isAct(String id) {
        return switch (id) {
            case DP_LOCK, DP_LIGHT, DP_UNIT, DP_CRUISE, DP_SPEED_LIMIT, DP_START_MODE -> true;
            default -> false;
        };
    }

    private void publish(String id, Object val) {
        if (thingDevice == null) return;
        Map<String, Object> dps = new HashMap<>();
        dps.put(id, val);
        thingDevice.publishDps(JSON.toJSONString(dps), new IResultCallback() {
            @Override public void onSuccess() { appendLog(getString(R.string.command_ok, id)); }
            @Override public void onError(String c, String e) { appendLog(getString(R.string.command_error, e)); }
        });
    }

    private void removePairing() {
        if (thingDevice != null) {
            thingDevice.removeDevice(new IResultCallback() {
                @Override public void onSuccess() {
                    runOnUiThread(() -> {
                        setStatus(getString(R.string.unlinked), Color.GREEN);
                        controlsPanel.removeAllViews();
                    });
                }
                @Override public void onError(String c, String e) {}
            });
        }
    }

    private void ensurePermissions() { requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION}, 100); }
    private void setStatus(String t, int c) { status.setText(t); status.setTextColor(c); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private void showDeviceFlow() { authPanel.setVisibility(View.GONE); devicePanel.setVisibility(View.VISIBLE); }
    @Override protected void onPause() { 
        super.onPause(); 
        isAppVisible = false;
    }
    @Override protected void onResume() { 
        super.onResume(); 
        isAppVisible = true;
        handler.removeCallbacks(reconnectRunnable);
        handler.post(reconnectRunnable);
        if (activeDevice != null) { 
            startAutomaticConnection(); 
            handler.removeCallbacks(statusPoller);
            handler.post(statusPoller); 
        } 
    }
    @Override protected void onDestroy() { 
        handler.removeCallbacks(statusPoller);
        handler.removeCallbacks(reconnectRunnable);
        if (thingDevice != null) { thingDevice.unRegisterDevListener(); thingDevice.onDestroy(); } 
        super.onDestroy(); 
    }
}
