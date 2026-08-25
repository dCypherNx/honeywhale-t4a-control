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
    
    // T4A Datapoints
    private static final String DP_LOCK = "1";
    private static final String DP_SPEED = "2";
    private static final String DP_BATTERY = "3";
    private static final String DP_ODO_TRIP = "5";
    private static final String DP_LIGHT = "8";
    private static final String DP_UNIT = "11";
    private static final String DP_ODO_TOTAL = "12";
    private static final String DP_CRUISE = "13";
    private static final String DP_SPEED_LIMIT = "14";
    private static final String DP_START_MODE = "16";
    private static final String DP_BRAKE = "39";
    private static final String DP_BATTERY_PERCENT = "101";
    private static final String DP_SPEED_VALUE = "102";
    private static final String DP_CURRENT = "105";
    private static final String DP_VOLTAGE = "106";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Button scanButton, connectButton;
    private TextView status, deviceInfo, log, cloudStatus, pairingStatus, brakeView;
    private EditText emailInput, passwordInput;
    private LinearLayout authPanel, devicePanel, controlsPanel;
    
    // UI estática persistente
    private TextView speedView, speedUnitView, batteryView, rssiView, lockStateView, odoTotalView;
    private final List<Button> modeButtons = new ArrayList<>();
    private final Map<String, Button> dashButtons = new HashMap<>();
    
    private ScanDeviceBean tuyaCandidate;
    private long activeHomeId;
    private IThingDevice thingDevice;
    private DeviceBean activeDevice;
    private int currentRssi = 0;
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
                    appendLog("Auto-reconexão ativa...");
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
        try {
            User cur = ThingHomeSdk.getUserInstance().getUser();
            if (cur != null) {
                String email = cur.getEmail();
                cloudStatus.setText(getString(R.string.active_account, email != null ? email : "N/A"));
                showDeviceFlow();
                queryHomes();
            }
        } catch (Exception e) {
            Log.e(TAG, "SDK error", e);
        }
    }

    @Override
    protected void onPause() { 
        super.onPause(); 
        isAppVisible = false;
    }

    @Override
    protected void onResume() { 
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

    @Override
    protected void onDestroy() { 
        handler.removeCallbacks(statusPoller);
        handler.removeCallbacks(reconnectRunnable);
        if (thingDevice != null) { thingDevice.unRegisterDevListener(); thingDevice.onDestroy(); } 
        super.onDestroy(); 
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
        btnDash.setOnClickListener(v -> { if (isSettingsMode) { isSettingsMode = false; refreshUi(); } });
        btnCfg.setOnClickListener(v -> { if (!isSettingsMode) { isSettingsMode = true; refreshUi(); } });
        nav.addView(btnDash); nav.addView(btnCfg); devicePanel.addView(nav);

        controlsPanel = new LinearLayout(this); controlsPanel.setOrientation(LinearLayout.VERTICAL); devicePanel.addView(controlsPanel);
        log = new TextView(this); log.setTextSize(10); log.setPadding(0, dp(20), 0, dp(50)); devicePanel.addView(log);
        body.addView(devicePanel);
        
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll);
    }

    private void refreshUi() {
        controlsPanel.removeAllViews();
        dashButtons.clear();
        modeButtons.clear();
        if (isSettingsMode) buildSettings();
        else buildDashboard();
        updateValues();
    }

    private void buildDashboard() {
        LinearLayout dashRow = new LinearLayout(this); dashRow.setOrientation(LinearLayout.HORIZONTAL); dashRow.setGravity(Gravity.CENTER);
        speedView = new TextView(this); speedView.setTextSize(120); speedView.setTextColor(Color.BLACK);
        speedUnitView = new TextView(this); speedUnitView.setTextSize(32); speedUnitView.setTextColor(Color.GRAY);
        dashRow.addView(speedView); dashRow.addView(speedUnitView); controlsPanel.addView(dashRow);

        batteryView = new TextView(this); batteryView.setTextSize(40); batteryView.setGravity(Gravity.CENTER);
        controlsPanel.addView(batteryView);
        
        rssiView = new TextView(this); rssiView.setGravity(Gravity.CENTER); rssiView.setTextColor(Color.BLUE);
        controlsPanel.addView(rssiView);

        LinearLayout act = createGroup(getString(R.string.group_riding));
        lockStateView = new TextView(this); lockStateView.setGravity(Gravity.CENTER); lockStateView.setPadding(0, dp(5), 0, dp(5)); act.addView(lockStateView);
        
        LinearLayout lockRow = new LinearLayout(this); lockRow.setOrientation(LinearLayout.HORIZONTAL);
        addActionButton(lockRow, DP_LOCK, getString(R.string.btn_unlock), false); 
        addActionButton(lockRow, DP_LOCK, getString(R.string.btn_lock), true);
        act.addView(lockRow);

        LinearLayout row1 = new LinearLayout(this); row1.setOrientation(LinearLayout.HORIZONTAL);
        addPersistBtn(row1, DP_LIGHT, getString(R.string.dp_light), "light");
        addPersistBtn(row1, DP_CRUISE, getString(R.string.dp_cruise), "bool");
        act.addView(row1);
        
        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL);
        addPersistBtn(row2, DP_START_MODE, getString(R.string.dp_start), "start");
        act.addView(row2);
        
        LinearLayout modeRow = new LinearLayout(this); modeRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] ns = {"WALK 6", "ECO 25", "RACE 35", "SPORT 45"};
        String[] vs = {"level_0", "level_1", "level_2", "level_3"};
        for (int mIdx = 0; mIdx < 4; mIdx++) {
            final String val = vs[mIdx];
            Button b = new Button(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(65), 1.0f);
            lp.setMargins(dp(2), dp(2), dp(2), dp(2)); b.setLayoutParams(lp);
            b.setText(ns[mIdx]); b.setTextSize(10); b.setPadding(0, 0, 0, 0);
            b.setTag(val);
            b.setOnClickListener(v -> publish(DP_SPEED_LIMIT, val));
            modeButtons.add(b); modeRow.addView(b);
        }
        act.addView(modeRow);
        controlsPanel.addView(act);

        LinearLayout unitBox = createGroup(getString(R.string.unit_box_title));
        addIconPairBtn(unitBox, DP_UNIT, getString(R.string.unit_mi), getString(R.string.unit_km), "mile", "km");
        controlsPanel.addView(unitBox);
        
        odoTotalView = new TextView(this); odoTotalView.setTextSize(18); odoTotalView.setPadding(0, dp(20), 0, 0); 
        controlsPanel.addView(odoTotalView);
    }

    private void buildSettings() {
        LinearLayout adm = createGroup(getString(R.string.group_admin));
        if (activeDevice != null) {
            TextView mac = new TextView(this); mac.setText("MAC: " + activeDevice.getMac()); mac.setGravity(Gravity.CENTER); mac.setPadding(0, 0, 0, dp(10)); adm.addView(mac);
        }
        scanButton = new Button(this); scanButton.setText(R.string.scan_new_btn); scanButton.setOnClickListener(v -> beginScan()); adm.addView(scanButton);
        connectButton = new Button(this); connectButton.setText(R.string.pair_btn); connectButton.setEnabled(false); connectButton.setOnClickListener(v -> startTuyaActivation()); adm.addView(connectButton);
        Button unpairBtn = new Button(this); unpairBtn.setText(R.string.unpair_btn); unpairBtn.setTextColor(Color.RED); unpairBtn.setOnClickListener(v -> removePairing()); adm.addView(unpairBtn);
        controlsPanel.addView(adm);

        Map<String, SchemaBean> schemas = activeDevice != null ? activeDevice.getSchemaMap() : null;
        if (schemas != null) {
            LinearLayout sts = createGroup(getString(R.string.group_status));
            for (Map.Entry<String, SchemaBean> entry : new TreeMap<>(schemas).entrySet()) {
                String id = entry.getKey();
                TextView l = new TextView(this); l.setTag("dp_" + id);
                l.setTextSize(14); l.setTextColor(0xFF2E7D32); l.setPadding(0, dp(5), 0, dp(2));
                sts.addView(l);
            }
            controlsPanel.addView(sts);
        }
    }

    private void updateValues() {
        if (activeDevice == null) return;
        boolean mi = Objects.equals(currentDps.get(DP_UNIT), "mile");
        double r = mi ? 0.621371 : 1.0;

        if (!isSettingsMode) {
            if (speedView != null) speedView.setText(String.format(Locale.ROOT, "%.1f", parseSafeFloat(currentDps.get(DP_SPEED)) * r));
            if (speedUnitView != null) speedUnitView.setText(mi ? " mph" : " km/h");
            
            int bat = parseSafeInt(currentDps.get(DP_BATTERY));
            if (batteryView != null) {
                batteryView.setText(getString(R.string.battery_label, bat));
                batteryView.setTextColor(bat > 20 ? 0xFF2E7D32 : Color.RED);
            }
            if (rssiView != null) rssiView.setText("Sinal: " + currentRssi + " dBm");
            if (lockStateView != null) lockStateView.setText(getString(R.string.dp_lock) + ": " + formatValue(DP_LOCK, currentDps.get(DP_LOCK), 1.0));
            if (odoTotalView != null) odoTotalView.setText(getString(R.string.odo_total_label, formatValue(DP_ODO_TOTAL, currentDps.get(DP_ODO_TOTAL), r)));

            for (Map.Entry<String, Button> entry : dashButtons.entrySet()) {
                String id = entry.getKey(); Button b = entry.getValue();
                Object val = currentDps.get(id); String type = (String) b.getTag();
                String label = displayDpName(id, null);
                String state = "";
                if ("light".equals(type)) state = isTrue(val) ? getString(R.string.state_on) : getString(R.string.state_off);
                else if ("bool".equals(type)) state = isTrue(val) ? "ON" : "OFF";
                else if ("start".equals(type)) state = "zero_start".equals(val) ? "ZERO" : "KICK";
                b.setText(label + "\n" + state);
            }

            Object curLimit = currentDps.get(DP_SPEED_LIMIT);
            for (Button b : modeButtons) {
                if (Objects.equals(b.getTag(), curLimit)) b.setBackgroundColor(Color.YELLOW);
                else b.setBackgroundColor(0xFFE0E0E0);
            }
        } else {
            for (int i = 0; i < controlsPanel.getChildCount(); i++) {
                View group = controlsPanel.getChildAt(i);
                if (group instanceof LinearLayout) {
                    for (int j = 0; j < ((LinearLayout)group).getChildCount(); j++) {
                        View child = ((LinearLayout)group).getChildAt(j);
                        if (child.getTag() != null && child.getTag().toString().startsWith("dp_")) {
                            String id = child.getTag().toString().substring(3);
                            SchemaBean sc = activeDevice.getSchemaMap().get(id);
                            ((TextView)child).setText(getString(R.string.dp_display, displayDpName(id, sc), formatValue(id, currentDps.get(id), r)));
                        }
                    }
                }
            }
        }
        updateLogView();
    }

    private void addActionButton(LinearLayout row, String id, String label, Object val) {
        Button b = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(65), 1.0f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2)); b.setLayoutParams(lp);
        b.setText(label); b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(v -> publish(id, val)); row.addView(b);
    }

    private void addPersistBtn(LinearLayout row, String id, String label, String type) {
        Button b = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(65), 1.0f);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2)); b.setLayoutParams(lp);
        b.setTag(type); b.setPadding(0, 0, 0, 0);
        b.setOnClickListener(v -> {
            Object val = currentDps.get(id);
            if ("start".equals(type)) publish(id, "zero_start".equals(val) ? "not_zero_start" : "zero_start");
            else if (val instanceof Boolean) publish(id, !((Boolean)val));
            else if (val != null) publish(id, !isTrue(val));
            else publish(id, true);
        });
        row.addView(b);
        dashButtons.put(id, b);
    }

    private void addIconPairBtn(LinearLayout g, String id, String tOn, String tOff, Object vOn, Object vOff) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        Button b1 = new Button(this); b1.setText(tOn); 
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(55), 1.0f);
        b1.setLayoutParams(lp); b1.setOnClickListener(v -> publish(id, vOn));
        Button b2 = new Button(this); b2.setText(tOff); b2.setLayoutParams(lp); b2.setOnClickListener(v -> publish(id, vOff));
        row.addView(b1); row.addView(b2); g.addView(row);
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
                if (homes != null && !homes.isEmpty()) { activeHomeId = homes.get(0).getHomeId(); loadHomeDevice(); }
            }
            @Override public void onError(String c, String e) {}
        });
    }

    private void loadHomeDevice() { 
        ThingHomeSdk.newHomeInstance(activeHomeId).getHomeDetail(new IThingHomeResultCallback() { 
            @Override public void onSuccess(HomeBean h) { 
                runOnUiThread(() -> { List<DeviceBean> ds = h.getDeviceList(); if (ds != null && !ds.isEmpty()) attachDevice(ds.get(0)); else setStatus(getString(R.string.no_scooter), Color.GRAY); }); 
            } 
            @Override public void onError(String c, String e) {} 
        }); 
    }

    private void beginScan() {
        if (activeDevice != null && activeDevice.getIsOnline()) { setStatus(getString(R.string.connected), 0xFF2E7D32); return; }
        tuyaCandidate = null; if (connectButton != null) connectButton.setEnabled(false);
        LeScanSetting settings = new LeScanSetting.Builder().setTimeout(15000).addScanType(ScanType.SINGLE).setNeedBoundResult(true).build();
        ThingHomeSdk.getBleOperator().startLeScan(settings, scanDevice -> runOnUiThread(() -> {
            if (scanDevice == null || tuyaCandidate != null) return;
            tuyaCandidate = scanDevice; currentRssi = scanDevice.getRssi();
            deviceInfo.setText(scanDevice.getName());
            if (connectButton != null) connectButton.setEnabled(true); setStatus(getString(R.string.ready_to_pair), 0xFF2E7D32);
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
            @Override public void onFailure(int c, String m, Object h) { runOnUiThread(() -> { setStatus(getString(R.string.activation_failed, c), Color.RED); if (connectButton != null) connectButton.setEnabled(true); }); }
        });
    }

    private void attachDevice(DeviceBean device) {
        if (thingDevice != null) thingDevice.unRegisterDevListener();
        activeDevice = device; pairingStatus.setText(getString(R.string.device_label, device.getName()));
        currentDps.clear(); if (device.getDps() != null) currentDps.putAll(device.getDps());
        thingDevice = ThingHomeSdk.newDeviceInstance(device.getDevId());
        thingDevice.registerDeviceListener(new IDeviceListener() {
            @Override public void onDpUpdate(String id, Map<String, Object> dps) {
                if (dps != null) { 
                    appendLog("RAW: " + dps);
                    runOnUiThread(() -> { currentDps.putAll(dps); checkBrake(dps); updateValues(); }); 
                }
            }
            @Override public void onRemoved(String id) { runOnUiThread(() -> setStatus(getString(R.string.removed), Color.RED)); }
            @Override public void onStatusChanged(String id, boolean online) { runOnUiThread(() -> { setStatus(online ? getString(R.string.connected) : getString(R.string.offline), online ? 0xFF2E7D32 : Color.RED); if (online) handler.post(statusPoller); else handler.removeCallbacks(statusPoller); updateValues(); }); }
            @Override public void onNetworkStatusChanged(String id, boolean o) {}
            @Override public void onDevInfoUpdate(String id) {}
        });
        refreshUi(); startAutomaticConnection(); handler.post(statusPoller);
    }

    private void publish(String id, Object val) {
        if (thingDevice == null) return;
        Map<String, Object> dps = new HashMap<>(); dps.put(id, val);
        thingDevice.publishDps(JSON.toJSONString(dps), new IResultCallback() {
            @Override public void onSuccess() { appendLog("TX: " + dps); }
            @Override public void onError(String c, String e) { appendLog("ERR: " + e); }
        });
    }

    private void removePairing() {
        if (thingDevice != null) thingDevice.removeDevice(new IResultCallback() {
            @Override public void onSuccess() { runOnUiThread(() -> { setStatus(getString(R.string.unlinked), Color.GREEN); controlsPanel.removeAllViews(); }); }
            @Override public void onError(String c, String e) {}
        });
    }

    private void syncState() { 
        if (activeDevice != null) { 
            DeviceBean d = ThingHomeSdk.getDataInstance().getDeviceBean(activeDevice.getDevId());
            if (d != null) { 
                activeDevice = d; 
                if (d.getDps() != null) { 
                    currentDps.putAll(d.getDps()); 
                    checkBrake(d.getDps()); 
                } 
            }
            runOnUiThread(this::updateValues); 
        } 
    }

    private void startAutomaticConnection() {
        if (activeDevice == null) return;
        BleConnectBuilder b = new BleConnectBuilder().setDevId(activeDevice.getDevId()).setUuid(activeDevice.getUuid()).setDirectConnect(true);
        ThingHomeSdk.getBleManager().connectBleDevice(Collections.singletonList(b));
    }

    private void updateLogView() {
        if (log == null) return;
        StringBuilder logBuilder = new StringBuilder(isSettingsMode ? getString(R.string.log_full) : getString(R.string.log_recent));
        int logCount = isSettingsMode ? logList.size() : Math.min(logList.size(), 10);
        for (int i = 0; i < logCount; i++) { logBuilder.append(logList.get(logList.size() - 1 - i)).append("\n"); }
        log.setText(logBuilder);
    }

    private void appendLog(String m) {
        String msg = DateFormat.getTimeInstance().format(new Date()) + " " + m;
        Log.d(TAG, msg); logList.add(msg); runOnUiThread(this::updateLogView);
    }

    private void checkBrake(Map<String, Object> dps) {
        Object obj = dps.get(DP_BRAKE);
        if (obj != null) {
            try {
                int vInt = Integer.parseInt(obj.toString());
                final boolean isBraking = (vInt & 0x04) != 0;
                runOnUiThread(() -> brakeView.setVisibility(isBraking ? View.VISIBLE : View.GONE));
            } catch (Exception ignored) {}
        }
    }

    private String formatValue(String id, Object v, double r) {
        if (v == null) return "---"; String s = v.toString();
        if (id.equals(DP_LOCK)) return isTrue(v) ? getString(R.string.state_locked) : getString(R.string.state_unlocked);
        if (id.equals(DP_LIGHT)) return isTrue(v) ? getString(R.string.state_on) : getString(R.string.state_off);
        if (id.equals(DP_SPEED_LIMIT)) { if (s.equals("level_0")) return "WALK"; if (s.equals("level_1")) return "ECO"; if (s.equals("level_2")) return "RACE"; if (s.equals("level_3")) return "SPORT"; }
        if (id.equals(DP_START_MODE)) return s.equals("zero_start") ? "PARTIDA ZERO" : "KICK START";
        if (id.equals(DP_ODO_TOTAL) || id.equals(DP_ODO_TRIP)) { try { return String.format(Locale.ROOT, "%.1f%s", (Float.parseFloat(s) / 10.0) * r, r < 1.0 ? " mi" : " km"); } catch (Exception ignored) { return s; } }
        if (id.equals(DP_VOLTAGE)) { try { return String.format(Locale.ROOT, "%.1f V", Float.parseFloat(s) / 10.0f); } catch (Exception ignored) { return s; } }
        if (v instanceof Boolean b) return b ? "ATIVO" : "INATIVO"; return s;
    }

    private String displayDpName(String id, SchemaBean s) {
        if (id.equals(DP_LOCK)) return getString(R.string.dp_lock); if (id.equals(DP_LIGHT)) return getString(R.string.dp_light); if (id.equals(DP_UNIT)) return getString(R.string.dp_unit); if (id.equals(DP_ODO_TOTAL)) return getString(R.string.dp_odo_total); if (id.equals(DP_CRUISE)) return getString(R.string.dp_cruise); if (id.equals(DP_SPEED_LIMIT)) return getString(R.string.dp_limit); if (id.equals(DP_START_MODE)) return getString(R.string.dp_start); if (id.equals(DP_BATTERY_PERCENT)) return getString(R.string.dp_bat_pct); if (id.equals(DP_SPEED_VALUE)) return getString(R.string.dp_speed); if (id.equals(DP_ODO_TRIP)) return getString(R.string.dp_odo_trip); if (id.equals(DP_CURRENT)) return getString(R.string.dp_current); if (id.equals(DP_VOLTAGE)) return getString(R.string.dp_voltage);
        return s != null ? s.getCode().toUpperCase() : "DP " + id;
    }

    private boolean isTrue(Object v) { if (v == null) return false; String s = v.toString(); return "true".equals(s) || "1".equals(s); }
    private float parseSafeFloat(Object o) { if (o == null) return 0f; try { return Float.parseFloat(o.toString()) / 10.0f; } catch (Exception e) { return 0f; } }
    private int parseSafeInt(Object o) { if (o == null) return 0; try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; } }
    private LinearLayout createGroup(String t) { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(0, dp(15), 0, dp(15)); TextView tv = new TextView(this); tv.setText(t); tv.setTextColor(0xFF1565C0); tv.setTextSize(18); tv.setPadding(0, dp(10), 0, dp(5)); c.addView(tv); return c; }
    private void ensurePermissions() { requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION}, 100); }
    private void setStatus(String t, int c) { status.setText(t); status.setTextColor(c); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private void showDeviceFlow() { authPanel.setVisibility(View.GONE); devicePanel.setVisibility(View.VISIBLE); }
}
