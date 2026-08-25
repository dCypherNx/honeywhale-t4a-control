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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.alibaba.fastjson.JSON;
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
    
    private static final String DP_LOCK = "1";
    private static final String DP_SPEED = "2";
    private static final String DP_BATTERY = "3";
    private static final String DP_ODO_TRIP = "5";
    private static final String DP_TIME = "6";
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
    private Button connectButton;
    private TextView status, log, cloudStatus, pairingStatus, brakeView;
    private EditText emailInput, passwordInput;
    private LinearLayout authPanel, devicePanel, controlsPanel;
    
    private TextView speedView, speedUnitView, rssiView, lockStateView, odoTotalView, batteryPercentView, odoTripView, usageTimeView;
    private final View[] batterySegments = new View[10];
    private ImageView headerBtn;
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
    private boolean isLockUpdatePending = false;

    private final Runnable statusPoller = new Runnable() {
        @Override
        public void run() {
            if (activeDevice != null && thingDevice != null) {
                syncState();
                pollRssi();
            }
            handler.postDelayed(this, 2000);
        }
    };

    private void pollRssi() {
        if (activeDevice == null || !activeDevice.getIsOnline()) {
            runOnUiThread(() -> { if (rssiView != null) rssiView.setText(R.string.offline); });
            return;
        }
        final String mac = activeDevice.getMac();
        if (mac == null || mac.isEmpty()) return;
        try {
            ThingHomeSdk.getBleOperator().readBluetoothRssi(mac, (isSuccess, rssi) -> {
                runOnUiThread(() -> {
                    if (rssiView != null) {
                        if (isSuccess && rssi != 0) {
                            currentRssi = rssi;
                            rssiView.setText(String.format(Locale.ROOT, "SINAL: %d dBm", currentRssi));
                            if (currentRssi > -60) rssiView.setTextColor(0xFF2E7D32);
                            else if (currentRssi > -80) rssiView.setTextColor(0xFFFBC02D);
                            else rssiView.setTextColor(Color.RED);
                        } else {
                            rssiView.setText("SINAL: ---");
                        }
                    }
                });
            });
        } catch (Exception e) {
            Log.e(TAG, "RSSI poll failed", e);
        }
    }

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
        
        // Ajuste de contraste da barra de status
        android.view.WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
        
        ensurePermissions();
        try {
            User cur = ThingHomeSdk.getUserInstance().getUser();
            if (cur != null) {
                cloudStatus.setText(getString(R.string.active_account, cur.getEmail() != null ? cur.getEmail() : "N/A"));
                showDeviceFlow();
                queryHomes();
            }
        } catch (Exception e) {
            Log.e(TAG, "SDK error", e);
        }
    }

    @Override protected void onPause() { super.onPause(); isAppVisible = false; }
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

    private void buildUi() {
        LinearLayout body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); 
        body.setPadding(dp(18), dp(36), dp(18), dp(10));
        
        RelativeLayout header = new RelativeLayout(this);
        TextView title = new TextView(this); title.setText(R.string.title_dash); title.setTextSize(22); 
        title.setTextColor(0xFF101820);
        RelativeLayout.LayoutParams lpTitle = new RelativeLayout.LayoutParams(-2, -2);
        lpTitle.addRule(RelativeLayout.ALIGN_PARENT_LEFT); title.setLayoutParams(lpTitle);
        
        headerBtn = new ImageView(this); 
        headerBtn.setPadding(dp(8), dp(8), dp(8), dp(8));
        RelativeLayout.LayoutParams lpHdr = new RelativeLayout.LayoutParams(dp(40), dp(40));
        lpHdr.addRule(RelativeLayout.ALIGN_PARENT_RIGHT); lpHdr.addRule(RelativeLayout.CENTER_VERTICAL);
        headerBtn.setLayoutParams(lpHdr);
        headerBtn.setOnClickListener(v -> { isSettingsMode = !isSettingsMode; refreshUi(); });
        
        header.addView(title); header.addView(headerBtn);
        body.addView(header);
        
        cloudStatus = new TextView(this); cloudStatus.setTextSize(11); body.addView(cloudStatus);
        
        authPanel = new LinearLayout(this); authPanel.setOrientation(LinearLayout.VERTICAL);
        emailInput = new EditText(this); emailInput.setHint(R.string.email_hint); authPanel.addView(emailInput);
        passwordInput = new EditText(this); passwordInput.setHint(R.string.password_hint); passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); authPanel.addView(passwordInput);
        Button loginBtn = new Button(this); loginBtn.setText(R.string.login_btn); loginBtn.setOnClickListener(v -> loginToTuya()); authPanel.addView(loginBtn);
        body.addView(authPanel);
        
        devicePanel = new LinearLayout(this); devicePanel.setOrientation(LinearLayout.VERTICAL); devicePanel.setVisibility(View.GONE);
        RelativeLayout statusRow = new RelativeLayout(this);
        status = new TextView(this); status.setTextSize(18);
        RelativeLayout.LayoutParams lpSts = new RelativeLayout.LayoutParams(-2, -2);
        lpSts.addRule(RelativeLayout.ALIGN_PARENT_LEFT); status.setLayoutParams(lpSts);
        
        rssiView = new TextView(this); rssiView.setTextSize(13);
        RelativeLayout.LayoutParams lpRssi = new RelativeLayout.LayoutParams(-2, -2);
        lpRssi.addRule(RelativeLayout.ALIGN_PARENT_RIGHT); rssiView.setLayoutParams(lpRssi);
        
        statusRow.addView(status); statusRow.addView(rssiView);
        devicePanel.addView(statusRow);
        
        brakeView = new TextView(this); brakeView.setText(R.string.brake_active); brakeView.setTextColor(Color.RED); brakeView.setTextSize(18); brakeView.setGravity(Gravity.CENTER); brakeView.setVisibility(View.GONE); devicePanel.addView(brakeView);
        pairingStatus = new TextView(this); pairingStatus.setTextSize(13); devicePanel.addView(pairingStatus);
        
        controlsPanel = new LinearLayout(this); controlsPanel.setOrientation(LinearLayout.VERTICAL); devicePanel.addView(controlsPanel);
        log = new TextView(this); log.setTextSize(10); log.setPadding(0, dp(10), 0, dp(20)); devicePanel.addView(log);
        body.addView(devicePanel);
        
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(body); setContentView(scroll);
    }

    private void refreshUi() {
        controlsPanel.removeAllViews();
        dashButtons.clear();
        modeButtons.clear();
        if (headerBtn != null) headerBtn.setImageResource(isSettingsMode ? R.drawable.ic_back : R.drawable.ic_settings);
        if (isSettingsMode) buildSettings(); else buildDashboard();
        updateValues();
    }

    private void buildDashboard() {
        LinearLayout dashRow = new LinearLayout(this); dashRow.setOrientation(LinearLayout.HORIZONTAL); dashRow.setGravity(Gravity.CENTER);
        speedView = new TextView(this); speedView.setTextSize(125); speedView.setTextColor(Color.BLACK); speedView.setIncludeFontPadding(false);
        speedUnitView = new TextView(this); speedUnitView.setTextSize(32); speedUnitView.setTextColor(Color.GRAY);
        dashRow.addView(speedView); dashRow.addView(speedUnitView); controlsPanel.addView(dashRow);

        LinearLayout teleRow = new LinearLayout(this); teleRow.setOrientation(LinearLayout.HORIZONTAL); teleRow.setGravity(Gravity.CENTER);
        odoTotalView = new TextView(this); odoTotalView.setTextSize(13); odoTotalView.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f)); odoTotalView.setGravity(Gravity.CENTER);
        odoTripView = new TextView(this); odoTripView.setTextSize(13); odoTripView.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f)); odoTripView.setGravity(Gravity.CENTER);
        usageTimeView = new TextView(this); usageTimeView.setTextSize(13); usageTimeView.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f)); usageTimeView.setGravity(Gravity.CENTER);
        teleRow.addView(odoTotalView); teleRow.addView(odoTripView); teleRow.addView(usageTimeView);
        controlsPanel.addView(teleRow);

        LinearLayout batBox = new LinearLayout(this); batBox.setOrientation(LinearLayout.VERTICAL); batBox.setGravity(Gravity.CENTER);
        batteryPercentView = new TextView(this); batteryPercentView.setTextSize(34); batteryPercentView.setGravity(Gravity.CENTER);
        batBox.addView(batteryPercentView);
        
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER);
        for(int bIdx=0; bIdx<10; bIdx++) {
            View seg = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(10));
            lp.setMargins(dp(1), 0, dp(1), 0); seg.setLayoutParams(lp);
            seg.setBackgroundColor(Color.LTGRAY);
            bar.addView(seg);
            batterySegments[bIdx] = seg;
        }
        batBox.addView(bar); batBox.setPadding(0, dp(5), 0, dp(5)); controlsPanel.addView(batBox);

        LinearLayout act = createGroup(getString(R.string.group_riding)); act.setPadding(0, 0, 0, 0);
        lockStateView = new TextView(this); lockStateView.setGravity(Gravity.CENTER); lockStateView.setPadding(0, dp(5), 0, dp(5)); act.addView(lockStateView);
        
        LinearLayout lockRow = new LinearLayout(this); lockRow.setOrientation(LinearLayout.HORIZONTAL);
        addActionButton(lockRow, DP_LOCK, getString(R.string.btn_unlock), true); 
        addActionButton(lockRow, DP_LOCK, getString(R.string.btn_lock), false);
        act.addView(lockRow);

        LinearLayout row1 = new LinearLayout(this); row1.setOrientation(LinearLayout.HORIZONTAL);
        addPersistBtn(row1, DP_LIGHT, "light");
        addPersistBtn(row1, DP_CRUISE, "bool");
        act.addView(row1);
        
        LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL);
        addPersistBtn(row2, DP_START_MODE, "start");
        act.addView(row2);
        
        LinearLayout modeRow = new LinearLayout(this); modeRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] ns = {"WALK 6", "ECO 25", "RACE 35", "SPORT 45"};
        String[] vs = {"level_0", "level_1", "level_2", "level_3"};
        for (int mIdx = 0; mIdx < 4; mIdx++) {
            final String val = vs[mIdx];
            Button b = new Button(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(60), 1.0f);
            lp.setMargins(dp(1), dp(1), dp(1), dp(1)); b.setLayoutParams(lp);
            b.setText(ns[mIdx]); b.setTextSize(10); b.setTag(val);
            b.setOnClickListener(v -> publish(DP_SPEED_LIMIT, val));
            modeButtons.add(b); modeRow.addView(b);
        }
        act.addView(modeRow); controlsPanel.addView(act);
    }

    private void buildSettings() {
        LinearLayout adm = createGroup(getString(R.string.group_admin));
        if (activeDevice != null) {
            TextView mac = new TextView(this); mac.setText(String.format("MAC: %s", activeDevice.getMac()));
            mac.setGravity(Gravity.CENTER); mac.setPadding(0, 0, 0, dp(10)); adm.addView(mac);
        }
        Button sBtn = new Button(this); sBtn.setText(R.string.scan_new_btn); sBtn.setOnClickListener(v -> beginScan()); adm.addView(sBtn);
        connectButton = new Button(this); connectButton.setText(R.string.pair_btn); connectButton.setEnabled(false); connectButton.setOnClickListener(v -> startTuyaActivation()); adm.addView(connectButton);
        Button uBtn = new Button(this); uBtn.setText(R.string.unpair_btn); uBtn.setTextColor(Color.RED); uBtn.setOnClickListener(v -> removePairing()); adm.addView(uBtn);
        controlsPanel.addView(adm);

        LinearLayout uBox = createGroup(getString(R.string.unit_box_title));
        addIconPairBtn(uBox, DP_UNIT, getString(R.string.unit_mi), getString(R.string.unit_km), "mile", "km");
        controlsPanel.addView(uBox);

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

        LinearLayout rawBox = createGroup(getString(R.string.raw_dps_title));
        TextView rawText = new TextView(this); rawText.setTag("raw_dps_text");
        rawText.setTextSize(11); rawText.setTextColor(Color.DKGRAY); rawText.setTextIsSelectable(true);
        rawBox.addView(rawText); controlsPanel.addView(rawBox);
    }

    private void updateValues() {
        if (activeDevice == null) return;
        boolean mi = Objects.equals(currentDps.get(DP_UNIT), "mile");
        double r = mi ? 0.621371 : 1.0;

        if (!isSettingsMode) {
            if (speedView != null) speedView.setText(String.format(Locale.ROOT, "%.1f", parseSafeFloat(currentDps.get(DP_SPEED)) * r));
            if (speedUnitView != null) speedUnitView.setText(mi ? " mph" : " km/h");
            
            int bat = parseSafeInt(currentDps.get(DP_BATTERY));
            if (batteryPercentView != null) {
                batteryPercentView.setText(String.format(Locale.ROOT, "%d%%", bat));
                batteryPercentView.setTextColor(bat > 20 ? 0xFF2E7D32 : Color.RED);
                for (int i = 0; i < 10; i++) {
                    if (batterySegments[i] != null) {
                        batterySegments[i].setBackgroundColor(bat >= (i + 1) * 10 ? 0xFF2E7D32 : Color.LTGRAY);
                    }
                }
            }
            if (lockStateView != null) lockStateView.setText(String.format("%s: %s", getString(R.string.dp_lock), formatValue(DP_LOCK, currentDps.get(DP_LOCK), 1.0)));
            if (odoTotalView != null) odoTotalView.setText(String.format("%s\n%s", getString(R.string.dp_odo_total), formatValue(DP_ODO_TOTAL, currentDps.get(DP_ODO_TOTAL), r)));
            if (odoTripView != null) odoTripView.setText(String.format("%s\n%s", getString(R.string.dp_odo_trip), formatValue(DP_ODO_TRIP, currentDps.get(DP_ODO_TRIP), r)));
            if (usageTimeView != null) usageTimeView.setText(String.format("%s\n%s", getString(R.string.dp_time), formatValue(DP_TIME, currentDps.get(DP_TIME), 1.0)));

            for (Map.Entry<String, Button> entry : dashButtons.entrySet()) {
                String id = entry.getKey(); Button b = entry.getValue();
                Object val = currentDps.get(id); String type = (String) b.getTag();
                String state = "";
                if ("light".equals(type)) state = isTrue(val) ? getString(R.string.state_on) : getString(R.string.state_off);
                else if ("bool".equals(type)) state = isTrue(val) ? "ON" : "OFF";
                else if ("start".equals(type)) state = "zero_start".equals(val) ? "ZERO" : "KICK";
                b.setText(String.format("%s\n%s", displayDpName(id, null), state));
            }

            Object curLimit = currentDps.get(DP_SPEED_LIMIT);
            for (Button b : modeButtons) b.setBackgroundColor(Objects.equals(b.getTag(), curLimit) ? Color.YELLOW : 0xFFE0E0E0);
        } else {
            for (int i = 0; i < controlsPanel.getChildCount(); i++) {
                View g = controlsPanel.getChildAt(i);
                if (g instanceof LinearLayout) {
                    for (int j = 0; j < ((LinearLayout)g).getChildCount(); j++) {
                        View c = ((LinearLayout)g).getChildAt(j);
                        if (c.getTag() != null && c.getTag().toString().startsWith("dp_")) {
                            String id = c.getTag().toString().substring(3);
                            SchemaBean sc = activeDevice.getSchemaMap().get(id);
                            ((TextView)c).setText(getString(R.string.dp_display, displayDpName(id, sc), formatValue(id, currentDps.get(id), r)));
                        }
                    }
                }
            }
            View rt = controlsPanel.findViewWithTag("raw_dps_text");
            if (rt instanceof TextView tv) {
                String nt = new TreeMap<>(currentDps).toString().replace(",", ",\n");
                if (!nt.equals(tv.getText().toString())) tv.setText(nt);
            }
        }
        updateLogView();
    }

    private void addActionButton(LinearLayout row, String id, String label, Object val) {
        Button b = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(60), 1.0f);
        lp.setMargins(dp(1), dp(1), dp(1), dp(1)); b.setLayoutParams(lp);
        b.setText(label); b.setPadding(0, 0, 0, 0); b.setTextSize(10);
        int icon = 0;
        if (DP_LOCK.equals(id)) icon = isTrue(val) ? R.drawable.ic_unlock : R.drawable.ic_lock;
        if (icon != 0) b.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0);
        b.setOnClickListener(v -> publish(id, val)); row.addView(b);
    }

    private void addPersistBtn(LinearLayout row, String id, String type) {
        Button b = new Button(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(60), 1.0f);
        lp.setMargins(dp(1), dp(1), dp(1), dp(1)); b.setLayoutParams(lp);
        b.setTag(type); b.setPadding(0, 0, 0, 0); b.setTextSize(10);
        int icon = 0;
        if (DP_LIGHT.equals(id)) icon = R.drawable.ic_light;
        else if (DP_CRUISE.equals(id)) icon = R.drawable.ic_cruise;
        else if (DP_START_MODE.equals(id)) icon = R.drawable.ic_start;
        if (icon != 0) b.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0);
        b.setOnClickListener(v -> {
            Object val = currentDps.get(id);
            if ("start".equals(type)) publish(id, "zero_start".equals(val) ? "not_zero_start" : "zero_start");
            else publish(id, val instanceof Boolean ? !((Boolean)val) : !isTrue(val));
        });
        row.addView(b); dashButtons.put(id, b);
    }

    private void addIconPairBtn(LinearLayout g, String id, String t1, String t2, Object v1, Object v2) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
        Button b1 = new Button(this); b1.setText(t1); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(55), 1.0f);
        b1.setLayoutParams(lp); b1.setOnClickListener(v -> publish(id, v1));
        Button b2 = new Button(this); b2.setText(t2); b2.setLayoutParams(lp); b2.setOnClickListener(v -> publish(id, v2));
        r.addView(b1); r.addView(b2); g.addView(r);
    }

    private void loginToTuya() {
        String em = emailInput.getText().toString().trim(), pw = passwordInput.getText().toString();
        if (em.isEmpty() || pw.isEmpty()) return;
        ThingHomeSdk.getUserInstance().loginWithEmail(getString(R.string.country_code), em, pw, new ILoginCallback() {
            @Override public void onSuccess(User u) { runOnUiThread(() -> { cloudStatus.setText(getString(R.string.logged_in, u.getEmail())); showDeviceFlow(); queryHomes(); }); }
            @Override public void onError(String c, String e) { runOnUiThread(() -> setStatus(getString(R.string.login_error, e), Color.RED)); }
        });
    }

    private void queryHomes() {
        ThingHomeSdk.getHomeManagerInstance().queryHomeList(new IThingGetHomeListCallback() {
            @Override public void onSuccess(List<HomeBean> homes) { if (homes != null && !homes.isEmpty()) { activeHomeId = homes.get(0).getHomeId(); loadHomeDevice(); } }
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
        LeScanSetting s = new LeScanSetting.Builder().setTimeout(15000).addScanType(ScanType.SINGLE).setNeedBoundResult(true).build();
        ThingHomeSdk.getBleOperator().startLeScan(s, scanDevice -> runOnUiThread(() -> {
            if (scanDevice == null || tuyaCandidate != null) return;
            tuyaCandidate = scanDevice; currentRssi = scanDevice.getRssi();
            if (pairingStatus != null) pairingStatus.setText(scanDevice.getName());
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

    private void proceedActivation(String token) {
        setStatus(getString(R.string.activating), Color.BLUE);
        BleActivatorBean b = new BleActivatorBean(tuyaCandidate);
        b.homeId = activeHomeId; b.address = tuyaCandidate.getAddress(); b.uuid = tuyaCandidate.getUuid(); b.productId = tuyaCandidate.getProductId(); b.deviceType = tuyaCandidate.getDeviceType();
        HashMap<String, Object> ext = new HashMap<>(); ext.put("token", token); b.setExtendsData(ext);
        ThingHomeSdk.getActivator().newBleActivator().startActivator(b, new IBleActivatorListener() {
            @Override public void onSuccess(DeviceBean d) { runOnUiThread(() -> { setStatus(getString(R.string.paired), Color.GREEN); attachDevice(d); }); }
            @Override public void onFailure(int c, String m, Object h) { runOnUiThread(() -> { setStatus(getString(R.string.activation_failed, c), Color.RED); if (connectButton != null) connectButton.setEnabled(true); }); }
        });
    }

    private void attachDevice(DeviceBean device) {
        if (thingDevice != null) thingDevice.unRegisterDevListener();
        activeDevice = device; if (pairingStatus != null) pairingStatus.setText(getString(R.string.device_label, device.getName()));
        currentDps.clear(); if (device.getDps() != null) currentDps.putAll(device.getDps());
        thingDevice = ThingHomeSdk.newDeviceInstance(device.getDevId());
        thingDevice.registerDeviceListener(new IDeviceListener() {
            @Override public void onDpUpdate(String id, Map<String, Object> dps) {
                if (dps != null) { 
                    appendLog("RAW: " + dps);
                    runOnUiThread(() -> { 
                        Map<String, Object> fdps = new HashMap<>(dps);
                        if (fdps.containsKey(DP_LOCK)) {
                            if (!isLockUpdatePending) fdps.remove(DP_LOCK); else isLockUpdatePending = false;
                        }
                        currentDps.putAll(fdps); checkBrake(fdps); updateValues(); 
                    }); 
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
        if (DP_LOCK.equals(id)) isLockUpdatePending = true;
        Map<String, Object> dps = new HashMap<>(); dps.put(id, val);
        thingDevice.publishDps(JSON.toJSONString(dps), new IResultCallback() {
            @Override public void onSuccess() { appendLog("TX: " + dps); }
            @Override public void onError(String c, String e) { appendLog("ERR: " + e); if (DP_LOCK.equals(id)) isLockUpdatePending = false; }
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
            if (d != null) { activeDevice = d; if (d.getDps() != null) { currentDps.putAll(d.getDps()); checkBrake(d.getDps()); } }
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
        StringBuilder lb = new StringBuilder(isSettingsMode ? getString(R.string.log_full) : getString(R.string.log_recent));
        int lc = isSettingsMode ? logList.size() : Math.min(logList.size(), 10);
        for (int i = 0; i < lc; i++) lb.append(logList.get(logList.size() - 1 - i)).append("\n");
        log.setText(lb);
    }

    private void appendLog(String m) {
        runOnUiThread(() -> {
            String msg = DateFormat.getTimeInstance().format(new Date()) + " " + m;
            Log.d(TAG, msg); logList.add(msg); updateLogView();
        });
    }

    private void checkBrake(Map<String, Object> dps) {
        Object obj = dps.get(DP_BRAKE);
        if (obj != null) {
            try {
                int vInt = Integer.parseInt(obj.toString());
                final boolean isBraking = (vInt & 0x04) != 0;
                runOnUiThread(() -> { if (brakeView != null) brakeView.setVisibility(isBraking ? View.VISIBLE : View.GONE); });
            } catch (Exception ignored) {}
        }
    }

    private String formatValue(String id, Object v, double r) {
        if (v == null) return "---";
        String s = v.toString();
        if (id.equals(DP_LOCK)) return isTrue(v) ? getString(R.string.state_unlocked) : getString(R.string.state_locked);
        if (id.equals(DP_LIGHT)) return isTrue(v) ? getString(R.string.state_on) : getString(R.string.state_off);
        if (id.equals(DP_SPEED_LIMIT)) {
            if (s.equals("level_0")) return "WALK";
            if (s.equals("level_1")) return "ECO";
            if (s.equals("level_2")) return "RACE";
            if (s.equals("level_3")) return "SPORT";
        }
        if (id.equals(DP_START_MODE)) return s.equals("zero_start") ? "PARTIDA ZERO" : "KICK START";
        if (id.equals(DP_ODO_TOTAL) || id.equals(DP_ODO_TRIP)) {
            try { return String.format(Locale.ROOT, "%.1f%s", (Float.parseFloat(s) / 10.0) * r, r < 1.0 ? " mi" : " km"); } catch (Exception ignored) {}
        }
        if (id.equals(DP_TIME)) {
            try { int t = Integer.parseInt(s); return String.format(Locale.ROOT, "%02d:%02d", t / 60, t % 60); } catch (Exception ignored) {}
        }
        if (id.equals(DP_VOLTAGE)) {
            try { return String.format(Locale.ROOT, "%.1f V", Float.parseFloat(s) / 10.0f); } catch (Exception ignored) {}
        }
        return v instanceof Boolean ? ((Boolean)v ? "ATIVO" : "INATIVO") : s;
    }

    private String displayDpName(String id, SchemaBean s) {
        if (id.equals(DP_LOCK)) return getString(R.string.dp_lock);
        if (id.equals(DP_LIGHT)) return getString(R.string.dp_light);
        if (id.equals(DP_UNIT)) return getString(R.string.dp_unit);
        if (id.equals(DP_ODO_TOTAL)) return getString(R.string.dp_odo_total);
        if (id.equals(DP_CRUISE)) return getString(R.string.dp_cruise);
        if (id.equals(DP_SPEED_LIMIT)) return getString(R.string.dp_limit);
        if (id.equals(DP_START_MODE)) return getString(R.string.dp_start);
        if (id.equals(DP_TIME)) return getString(R.string.dp_time);
        if (id.equals(DP_BATTERY_PERCENT)) return getString(R.string.dp_bat_pct);
        if (id.equals(DP_SPEED_VALUE)) return getString(R.string.dp_speed);
        if (id.equals(DP_ODO_TRIP)) return getString(R.string.dp_odo_trip);
        if (id.equals(DP_CURRENT)) return getString(R.string.dp_current);
        if (id.equals(DP_VOLTAGE)) return getString(R.string.dp_voltage);
        return s != null ? s.getCode().toUpperCase() : "DP " + id;
    }

    private boolean isTrue(Object v) { if (v == null) return false; String s = v.toString(); return "true".equals(s) || "1".equals(s); }
    private float parseSafeFloat(Object o) { if (o == null) return 0f; try { return Float.parseFloat(o.toString()) / 10.0f; } catch (Exception e) { return 0f; } }
    private int parseSafeInt(Object o) { if (o == null) return 0; try { return Integer.parseInt(o.toString()); } catch (Exception e) { return 0; } }
    private LinearLayout createGroup(String t) { 
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(0, dp(5), 0, dp(5)); 
        TextView tv = new TextView(this); tv.setText(t); tv.setTextColor(0xFF1565C0); tv.setTextSize(16); tv.setPadding(0, dp(5), 0, dp(2)); 
        c.addView(tv); return c; 
    }
    private void ensurePermissions() { requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION}, 100); }
    private void setStatus(String t, int c) { if (status != null) { status.setText(t); status.setTextColor(c); } }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private void showDeviceFlow() { if (authPanel != null) authPanel.setVisibility(View.GONE); if (devicePanel != null) devicePanel.setVisibility(View.VISIBLE); }
}
