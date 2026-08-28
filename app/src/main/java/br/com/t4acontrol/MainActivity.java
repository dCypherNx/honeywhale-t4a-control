package br.com.t4acontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.IBinder;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import br.com.t4acontrol.backend.T4AState;
import br.com.t4acontrol.session.T4ASession;
import br.com.t4acontrol.session.T4ASessionService;
import com.mikepenz.iconics.IconicsDrawable;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public final class MainActivity extends Activity implements T4ASession.Listener {
  private static final int REQUEST_BLUETOOTH = 100;
  private static final String DP_LOCK = "1",
      DP_SPEED = "2",
      DP_BATTERY = "3",
      DP_TRIP = "5",
      DP_TIME = "6",
      DP_LIGHT = "8";
  private static final String DP_UNIT = "11",
      DP_TOTAL = "12",
      DP_CRUISE = "13",
      DP_LEVEL = "14",
      DP_START = "16";
  private static final String PREF_KEEP_SCREEN_ON = "keep_screen_on";
  private static final String PREF_TOTAL_ODOMETER = "show_total_odometer";
  private static final String PREF_THEME = "theme_mode";
  private static final int NAVY = 0xFF00133D,
      BLUE = 0xFF075EF0,
      GREEN = 0xFF00A529,
      RED = 0xFFE91925;

  private T4ASession session = T4ASession.EMPTY;
  private T4AState state;
  private LinearLayout authPanel, devicePanel, contentPanel;
  private ScrollView rootScroll;
  private TextView cloudStatus, status, eventTitle, events;
  private Button copyRawButton;
  private ImageButton navigationButton;
  private EditText email, password;
  private boolean settings;
  private String renderedStructure = "", lastStatusEvent = "";
  private final List<String> eventHistory = new ArrayList<>();
  private final List<String> rawHistory = new ArrayList<>();
  private TextView speed,
      speedUnit,
      battery,
      rssi,
      pairing,
      connection,
      device,
      mac,
      connectionIcon;
  private TextView lock, level, unit, cruise, start, odoTotal, odoTrip, usageTime, allDps;
  private LinearLayout batteryBar;
  private ImageView batteryIcon;
  private ImageView odometerIcon;
  private Button[] modeButtons,
      unitPreferenceButtons,
      cruisePreferenceButtons,
      startPreferenceButtons,
      themeButtons;
  private Button[] autoLockDistanceButtons;
  private ImageButton lightSwitch, startSwitch, cruiseSwitch;
  private ImageButton autoLockButton;
  private TextView autoLockLabel;
  private SpeedBar speedProgress;
  private boolean showTotalOdometer;
  private int renderedEventCount = -1, renderedRawCount = -1;
  private boolean renderedLogSettings;
  private boolean darkModeAtBuild;
  private boolean sessionBindingRequested;
  private boolean uiForeground;

  private final ServiceConnection sessionConnection =
      new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
          if (!(service instanceof T4ASessionService.SessionBinder)) {
            sessionBindingRequested = false;
            return;
          }
          session = (T4ASession) service;
          session.addListener(MainActivity.this);
          session.setUiForeground(uiForeground);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
          session = T4ASession.EMPTY;
          sessionBindingRequested = false;
        }
      };

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    buildUi();
    configureSystemBars();
    showTotalOdometer = preferences().getBoolean(PREF_TOTAL_ODOMETER, true);
    applyKeepScreenOn(preferences().getBoolean(PREF_KEEP_SCREEN_ON, true));
    ensurePermissions();
  }

  @Override
  protected void onResume() {
    super.onResume();
    uiForeground = true;
    if (hasBluetoothPermissions()) connectSession();
    session.setUiForeground(true);
  }

  @Override
  protected void onPause() {
    uiForeground = false;
    session.setUiForeground(false);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    disconnectSession();
    super.onDestroy();
  }

  @Override
  public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    if ("system".equals(preferences().getString(PREF_THEME, "system"))
        && darkModeAtBuild != isDarkMode()) rebuildUi();
    else {
      configureSystemBars();
      if (rootScroll != null) rootScroll.requestApplyInsets();
    }
  }

  @Override
  public void onBackPressed() {
    if (settings) {
      settings = false;
      if (navigationButton != null)
        navigationButton.setImageDrawable(
            iconDrawable("cmd-cog", isDarkMode() ? Color.WHITE : NAVY, 25));
      renderedStructure = "";
      render();
    } else super.onBackPressed();
  }

  private void buildUi() {
    darkModeAtBuild = isDarkMode();
    LinearLayout body = vertical();
    body.setBackgroundColor(backgroundColor());
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = text(getString(R.string.app_name).toUpperCase(Locale.ROOT), 30);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    ImageButton mode = new ImageButton(this);
    navigationButton = mode;
    mode.setImageDrawable(iconDrawable("cmd-cog", isDarkMode() ? Color.WHITE : NAVY, 25));
    mode.setBackground(round(surfaceColor(), 99, outlineColor(), 1));
    mode.setPadding(dp(12), dp(12), dp(12), dp(12));
    mode.setOnClickListener(
        v -> {
          settings = !settings;
          mode.setImageDrawable(
              iconDrawable(
                  settings ? "cmd-arrow-left" : "cmd-cog", isDarkMode() ? Color.WHITE : NAVY, 25));
          renderedStructure = "";
          render();
        });
    header.addView(mode, new LinearLayout.LayoutParams(dp(52), dp(52)));
    body.addView(header);
    cloudStatus = text(getString(R.string.checking_account), 12);
    cloudStatus.setVisibility(View.GONE);
    body.addView(cloudStatus);
    status = text("", 14);
    status.setPadding(0, dp(8), 0, dp(8));
    body.addView(status);
    authPanel = vertical();
    email = new EditText(this);
    email.setHint(R.string.email_hint);
    email.setTextColor(foregroundColor());
    email.setHintTextColor(mutedColor());
    authPanel.addView(email);
    password = new EditText(this);
    password.setHint(R.string.password_hint);
    password.setTextColor(foregroundColor());
    password.setHintTextColor(mutedColor());
    password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    authPanel.addView(password);
    Button login = new Button(this);
    login.setText(R.string.login);
    login.setOnClickListener(
        v -> {
          session.login(email.getText().toString().trim(), password.getText().toString());
          password.setText("");
        });
    authPanel.addView(login);
    body.addView(authPanel);
    devicePanel = vertical();
    devicePanel.setVisibility(View.GONE);
    contentPanel = vertical();
    devicePanel.addView(contentPanel);
    LinearLayout eventCard = card();
    LinearLayout eventHeader = new LinearLayout(this);
    eventHeader.setGravity(Gravity.CENTER_VERTICAL);
    eventTitle = text(getString(R.string.events), 18);
    eventTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    eventTitle.setTextColor(BLUE);
    setStartIcon(eventTitle, "cmd-format-list-bulleted", BLUE, 20);
    eventTitle.setCompoundDrawablePadding(dp(8));
    eventHeader.addView(eventTitle, new LinearLayout.LayoutParams(0, -2, 1));
    copyRawButton = actionCard(getString(R.string.copy), BLUE, this::copyRawLog);
    copyRawButton.setTextSize(11);
    copyRawButton.setPadding(dp(10), dp(4), dp(10), dp(4));
    copyRawButton.setVisibility(View.GONE);
    eventHeader.addView(copyRawButton, new LinearLayout.LayoutParams(-2, dp(36)));
    eventCard.addView(eventHeader);
    events = text("", 12);
    events.setTextIsSelectable(true);
    events.setPadding(0, dp(8), 0, 0);
    eventCard.addView(events);
    devicePanel.addView(eventCard);
    body.addView(devicePanel);
    ScrollView scroll = new ScrollView(this);
    rootScroll = scroll;
    scroll.setFillViewport(true);
    scroll.setClipToPadding(true);
    scroll.setBackgroundColor(backgroundColor());
    scroll.setOnApplyWindowInsetsListener(
        (view, insets) -> {
          Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
          view.setPadding(
              bars.left + dp(16), bars.top + dp(8), bars.right + dp(16), bars.bottom + dp(8));
          return insets;
        });
    scroll.addView(body);
    setContentView(scroll);
    scroll.requestApplyInsets();
  }

  @Override
  public void onState(T4AState value) {
    runOnUiThread(
        () -> {
          state = value;
          if (!value.message.isEmpty() && !value.message.equals(lastStatusEvent)) {
            lastStatusEvent = value.message;
            appendEvent(value.message);
          }
          render();
        });
  }

  @Override
  public void onEvent(String value) {
    runOnUiThread(() -> appendEvent(value));
  }

  @Override
  public void onRawLog(String value) {
    runOnUiThread(() -> appendRaw(value));
  }

  private void appendEvent(String value) {
    eventHistory.add(DateFormat.getTimeInstance().format(new Date()) + "  " + value);
    updateLogView();
  }

  private void appendRaw(String value) {
    rawHistory.add(
        new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + " " + value);
    updateLogView();
  }

  private void updateLogView() {
    if (events == null) return;
    int count = settings ? rawHistory.size() : eventHistory.size();
    if (renderedLogSettings == settings
        && count == (settings ? renderedRawCount : renderedEventCount)) return;
    renderedLogSettings = settings;
    if (settings) renderedRawCount = count;
    else renderedEventCount = count;
    List<String> source = settings ? rawHistory : eventHistory;
    if (eventTitle != null) eventTitle.setText(settings ? R.string.raw_log : R.string.events);
    if (copyRawButton != null) copyRawButton.setVisibility(settings ? View.VISIBLE : View.GONE);
    events.setTypeface(settings ? Typeface.MONOSPACE : Typeface.DEFAULT);
    events.setTextSize(settings ? 10 : 12);
    events.setIncludeFontPadding(!settings);
    events.setLineSpacing(0, settings ? 0.88f : 1f);
    events.setPadding(0, settings ? dp(3) : dp(8), 0, 0);
    StringBuilder out = new StringBuilder();
    int first = settings ? 0 : Math.max(0, source.size() - 10);
    for (int i = source.size() - 1; i >= first; i--) out.append(source.get(i)).append('\n');
    events.setText(out);
  }

  private void copyRawLog() {
    StringBuilder out = new StringBuilder();
    for (String entry : rawHistory) out.append(entry).append('\n');
    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.raw_log), out.toString()));
    Toast.makeText(this, R.string.raw_log_copied, Toast.LENGTH_SHORT).show();
  }

  private void render() {
    if (state == null) return;
    authPanel.setVisibility(state.authenticated ? View.GONE : View.VISIBLE);
    devicePanel.setVisibility(state.authenticated ? View.VISIBLE : View.GONE);
    cloudStatus.setText(
        state.authenticated
            ? getString(R.string.account, state.account)
            : getString(R.string.account_unauthenticated));
    status.setText(state.message);
    status.setTextColor(state.connected ? GREEN : mutedColor());
    status.setVisibility(
        state.authenticated && state.pairing == T4AState.Pairing.PAIRED ? View.GONE : View.VISIBLE);
    if (!state.authenticated) return;
    String structure = settings + ":" + state.pairing + ":" + new TreeMap<>(state.dps).keySet();
    if (!structure.equals(renderedStructure)) {
      renderedStructure = structure;
      contentPanel.removeAllViews();
      clearRefs();
      if (settings) buildSettings();
      else buildDashboard();
    }
    updateValues();
    updateLogView();
    setActionsEnabled(contentPanel, state.connected);
  }

  private void buildDashboard() {
    if (state.pairing != T4AState.Pairing.PAIRED) {
      buildPairingActions();
      return;
    }
    LinearLayout connectionCard = card();
    LinearLayout connectionRow = new LinearLayout(this);
    connectionRow.setGravity(Gravity.CENTER_VERTICAL);
    connectionIcon = text("", 34);
    connectionIcon.setGravity(Gravity.CENTER);
    connectionIcon.setTextColor(Color.WHITE);
    setStartIcon(connectionIcon, "cmd-check-circle", Color.WHITE, 30);
    connectionRow.addView(connectionIcon, new LinearLayout.LayoutParams(dp(54), dp(54)));
    LinearLayout connectionText = vertical();
    connection = text("", 19);
    connection.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    device = text("", 14);
    connectionText.addView(connection);
    connectionText.addView(device);
    LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0, -2, 1);
    grow.setMargins(dp(14), 0, dp(8), 0);
    connectionRow.addView(connectionText, grow);
    rssi = line();
    rssi.setGravity(Gravity.END);
    setStartIcon(rssi, "cmd-signal", GREEN, 18);
    rssi.setCompoundDrawablePadding(dp(5));
    connectionRow.addView(rssi);
    connectionCard.addView(connectionRow);
    LinearLayout batteryRow = new LinearLayout(this);
    batteryRow.setGravity(Gravity.CENTER_VERTICAL);
    batteryRow.setPadding(0, dp(7), 0, 0);
    batteryIcon = new ImageView(this);
    batteryIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    batteryIcon.setRotation(90f);
    batteryRow.addView(batteryIcon, new LinearLayout.LayoutParams(dp(38), dp(25)));
    batteryBar = new LinearLayout(this);
    batteryBar.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, dp(16), 1);
    barParams.setMargins(dp(8), 0, dp(9), 0);
    batteryRow.addView(batteryBar, barParams);
    battery = text("", 15);
    battery.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    batteryRow.addView(battery);
    connectionCard.addView(batteryRow);
    contentPanel.addView(connectionCard);

    LinearLayout speedCard = card();
    FrameLayout gauge = new FrameLayout(this);
    speedProgress = new SpeedBar();
    gauge.addView(speedProgress, new FrameLayout.LayoutParams(-1, dp(154)));
    LinearLayout speedRow = new LinearLayout(this);
    speedRow.setGravity(Gravity.CENTER);
    speed = text("", 96);
    speed.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    speedRow.addView(speed);
    speedUnit = text("", 24);
    speedUnit.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    speedUnit.setPadding(dp(12), dp(32), 0, 0);
    speedRow.addView(speedUnit);
    gauge.addView(speedRow, new FrameLayout.LayoutParams(-1, dp(126)));
    level = text("", 11);
    level.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    level.setPadding(0, dp(3), dp(8), 0);
    FrameLayout.LayoutParams levelParams =
        new FrameLayout.LayoutParams(-1, dp(34), Gravity.TOP | Gravity.END);
    gauge.addView(level, levelParams);
    speedCard.addView(gauge, new LinearLayout.LayoutParams(-1, dp(154)));
    contentPanel.addView(speedCard);

    LinearLayout tripCard = card();
    LinearLayout tripRow = new LinearLayout(this);
    tripRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout odometerMetric = metricWithIcon("cmd-speedometer", BLUE);
    odometerIcon = (ImageView) odometerMetric.getChildAt(0);
    odoTotal = (TextView) odometerMetric.getChildAt(1);
    View.OnClickListener toggleOdometer = v -> toggleOdometer();
    odometerMetric.setOnClickListener(toggleOdometer);
    odoTotal.setOnClickListener(toggleOdometer);
    LinearLayout timeMetric = metricWithIcon("cmd-clock-outline", BLUE);
    usageTime = (TextView) timeMetric.getChildAt(1);
    tripRow.addView(odometerMetric, new LinearLayout.LayoutParams(0, -2, 1));
    tripRow.addView(metricDivider(), new LinearLayout.LayoutParams(dp(1), dp(44)));
    tripRow.addView(timeMetric, new LinearLayout.LayoutParams(0, -2, 1));
    tripCard.addView(tripRow);
    contentPanel.addView(tripCard);

    LinearLayout controls = card();
    LinearLayout controlsHeader = new LinearLayout(this);
    controlsHeader.setGravity(Gravity.CENTER_VERTICAL);
    TextView controlsTitle = text(getString(R.string.riding), 19);
    controlsTitle.setTextColor(BLUE);
    controlsTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    setStartIcon(controlsTitle, "cmd-car-shift-pattern", BLUE, 21);
    controlsTitle.setCompoundDrawablePadding(dp(7));
    controlsHeader.addView(controlsTitle, new LinearLayout.LayoutParams(0, -2, 1));
    lock = line();
    lock.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    controlsHeader.addView(lock);
    controls.addView(controlsHeader);
    modeButtons =
        addEnumTo(
            controls,
            DP_LEVEL,
            new String[] {
              speedLimit(R.string.mode_walk, 6),
              speedLimit(R.string.mode_eco, 25),
              speedLimit(R.string.mode_race, 35),
              speedLimit(R.string.mode_sport, 45)
            },
            new Object[] {"level_0", "level_1", "level_2", "level_3"});
    LinearLayout switchRow = new LinearLayout(this);
    lightSwitch = addStateToggle(switchRow, getString(R.string.light), DP_LIGHT, false, true);
    startSwitch =
        addStateToggle(
            switchRow, getString(R.string.start_mode), DP_START, "zero_start", "not_zero_start");
    cruiseSwitch = addStateToggle(switchRow, getString(R.string.cruise), DP_CRUISE, false, true);
    controls.addView(switchRow);
    LinearLayout locks = new LinearLayout(this);
    locks.addView(
        lockActionCard(
            getString(R.string.unlock),
            "cmd-lock-open",
            GREEN,
            () -> session.publish(DP_LOCK, true)),
        weight());
    locks.addView(
        lockActionCard(
            getString(R.string.lock), "cmd-lock", RED, () -> session.publish(DP_LOCK, false)),
        weight());
    locks.addView(autoLockCard(), weight());
    controls.addView(locks);
    contentPanel.addView(controls);
  }

  private void buildSettings() {
    TextView heading = text(getString(R.string.settings), 24);
    heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    heading.setPadding(0, dp(8), 0, dp(8));
    contentPanel.addView(heading);
    LinearLayout connectionCard =
        collapsibleSection(getString(R.string.connection_device_section), "connection");
    pairing = line();
    connection = line();
    device = line();
    mac = line();
    rssi = line();
    connectionCard.addView(pairing);
    connectionCard.addView(connection);
    connectionCard.addView(device);
    connectionCard.addView(mac);
    connectionCard.addView(rssi);
    LinearLayout displayCard = collapsibleSection(getString(R.string.display_section), "display");
    Switch keepOn = new Switch(this);
    keepOn.setText(R.string.keep_screen_on);
    keepOn.setChecked(preferences().getBoolean(PREF_KEEP_SCREEN_ON, true));
    keepOn.setTextColor(foregroundColor());
    keepOn.setPadding(0, dp(8), 0, dp(8));
    keepOn.setOnCheckedChangeListener(
        (button, checked) -> {
          preferences().edit().putBoolean(PREF_KEEP_SCREEN_ON, checked).apply();
          applyKeepScreenOn(checked);
          appendEvent(
              getString(checked ? R.string.screen_on_enabled : R.string.screen_on_disabled));
        });
    displayCard.addView(keepOn);
    TextView themeTitle = text(getString(R.string.theme_mode), 13);
    themeTitle.setPadding(0, dp(5), 0, 0);
    displayCard.addView(themeTitle);
    themeButtons = addThemeSelector(displayCard);

    LinearLayout integrations =
        collapsibleSection(getString(R.string.integrations_section), "integrations");
    Button mqtt =
        actionCard(
            getString(R.string.configure_mqtt),
            BLUE,
            () ->
                startActivity(
                    new Intent(this, br.com.t4acontrol.mqtt.MqttSettingsActivity.class)));
    mqtt.setTag("always");
    integrations.addView(mqtt);

    if (state.pairing != T4AState.Pairing.PAIRED) {
      buildPairingActions();
      return;
    }
    LinearLayout automaticCard =
        collapsibleSection(getString(R.string.automatic_lock), "auto_lock");
    TextView distanceTitle = text(getString(R.string.auto_lock_distance), 13);
    distanceTitle.setPadding(dp(4), dp(6), dp(4), 0);
    automaticCard.addView(distanceTitle);
    autoLockDistanceButtons = addDistanceSelector(automaticCard);
    LinearLayout ridingCard =
        collapsibleSection(getString(R.string.riding_preferences_section), "riding");
    unit = line();
    ridingCard.addView(unit);
    unitPreferenceButtons =
        addCompactEnumTo(
            ridingCard,
            DP_UNIT,
            new int[] {R.string.miles, R.string.kilometers},
            new Object[] {"mile", "km"});
    cruise = line();
    ridingCard.addView(cruise);
    cruisePreferenceButtons =
        addCompactEnumTo(
            ridingCard,
            DP_CRUISE,
            new int[] {R.string.disable, R.string.enable},
            new Object[] {false, true});
    start = line();
    ridingCard.addView(start);
    startPreferenceButtons =
        addCompactEnumTo(
            ridingCard,
            DP_START,
            new int[] {R.string.zero_start, R.string.initial_push},
            new Object[] {"zero_start", "not_zero_start"});
    LinearLayout diagnostics =
        collapsibleSection(getString(R.string.diagnostics_section), "diagnostics");
    odoTotal = line();
    odoTrip = line();
    usageTime = line();
    diagnostics.addView(odoTotal);
    diagnostics.addView(odoTrip);
    diagnostics.addView(usageTime);
    allDps = text("", 12);
    allDps.setTextIsSelectable(true);
    diagnostics.addView(allDps);
    LinearLayout pairingCard = collapsibleSection(getString(R.string.pairing_section), "pairing");
    Button remove =
        actionCard(
            getString(R.string.remove_pairing),
            RED,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle(R.string.remove_pairing_question)
                    .setMessage(R.string.remove_pairing_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.remove, (d, w) -> session.unpair())
                    .show());
    remove.setTag("always");
    pairingCard.addView(remove);
  }

  private void updateValues() {
    if (speed != null) {
      boolean miles = "mile".equals(state.dps.get(DP_UNIT));
      int speedValue = (int) (scaled(state.dps.get(DP_SPEED), 10) * (miles ? 0.621371 : 1));
      set(speed, getString(R.string.speed_value, speedValue));
      set(speedUnit, getString(miles ? R.string.metric_mph : R.string.metric_kmh));
      if (speedProgress != null) speedProgress.setSpeed(speedValue);
      int value = integer(state.dps.get(DP_BATTERY));
      set(battery, getString(R.string.percent, value));
      battery.setTextColor(value > 20 ? GREEN : RED);
      if (batteryIcon != null) batteryIcon.setImageDrawable(batteryDrawable(value));
      updateBatteryBar(value);
    }
    set(
        rssi,
        getString(
            R.string.signal,
            state.rssi == 0
                ? getString(R.string.unavailable)
                : getString(R.string.dbm, state.rssi)));
    updateSignalIcon();
    boolean unlocked = truth(state.dps.get(DP_LOCK));
    set(
        lock,
        getString(R.string.lock_state, lockLabel(state.dps.get(DP_LOCK)).toUpperCase(Locale.ROOT)));
    if (lock != null) {
      lock.setTextColor(unlocked ? GREEN : RED);
      lock.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    }
    updateLevelIcon();
    set(pairing, getString(R.string.pairing_status, pairingLabel(state.pairing)));
    set(connection, getString(state.connected ? R.string.connected : R.string.disconnected));
    if (connection != null) connection.setTextColor(state.connected ? GREEN : RED);
    if (connectionIcon != null) {
      connectionIcon.setText("");
      setStartIcon(connectionIcon, "cmd-check-circle", state.connected ? GREEN : RED, 46);
      connectionIcon.setBackgroundColor(Color.TRANSPARENT);
    }
    set(device, getString(R.string.device, empty(state.deviceName)));
    set(mac, getString(R.string.mac, empty(state.mac)));
    set(
        unit,
        getString(
            R.string.unit_status,
            getString(
                "mile".equals(state.dps.get(DP_UNIT)) ? R.string.miles : R.string.kilometers)));
    set(cruise, getString(R.string.cruise_status, boolLabel(state.dps.get(DP_CRUISE))));
    set(
        start,
        getString(
            R.string.start_status,
            getString(
                "not_zero_start".equals(state.dps.get(DP_START))
                    ? R.string.initial_push
                    : R.string.zero_start)));
    set(
        odoTotal,
        getString(
            showTotalOdometer ? R.string.total_odometer : R.string.trip_odometer,
            distance(showTotalOdometer ? state.dps.get(DP_TOTAL) : state.dps.get(DP_TRIP))));
    if (odometerIcon != null)
      odometerIcon.setImageDrawable(
          iconDrawable(
              showTotalOdometer ? "cmd-speedometer" : "cmd-map-marker-distance", BLUE, 23));
    set(odoTrip, getString(R.string.trip_odometer, distance(state.dps.get(DP_TRIP))));
    set(usageTime, getString(R.string.usage_time, duration(integer(state.dps.get(DP_TIME)))));
    select(
        modeButtons,
        levelIndex(state.dps.get(DP_LEVEL)),
        new int[] {0xFF08A6B7, GREEN, 0xFFFF8A00, RED});
    select(
        autoLockDistanceButtons,
        autoLockDistanceIndex(state.autoLockDistance),
        new int[] {BLUE, BLUE, BLUE});
    select(
        unitPreferenceButtons,
        "mile".equals(state.dps.get(DP_UNIT)) ? 0 : 1,
        new int[] {BLUE, BLUE});
    select(
        cruisePreferenceButtons, truth(state.dps.get(DP_CRUISE)) ? 1 : 0, new int[] {BLUE, BLUE});
    select(
        startPreferenceButtons,
        "not_zero_start".equals(state.dps.get(DP_START)) ? 1 : 0,
        new int[] {BLUE, BLUE});
    select(
        themeButtons,
        themeIndex(preferences().getString(PREF_THEME, "system")),
        new int[] {BLUE, BLUE, BLUE});
    updateToggle(
        lightSwitch,
        truth(state.dps.get(DP_LIGHT)),
        twoLine(R.string.light, R.string.state_off),
        twoLine(R.string.light, R.string.state_on),
        DP_LIGHT);
    updateToggle(
        startSwitch,
        "not_zero_start".equals(String.valueOf(state.dps.get(DP_START))),
        twoLine(R.string.start_mode, R.string.state_zero),
        twoLine(R.string.start_mode, R.string.state_kick),
        DP_START);
    updateToggle(
        cruiseSwitch,
        truth(state.dps.get(DP_CRUISE)),
        twoLine(R.string.cruise, R.string.state_off),
        twoLine(R.string.cruise, R.string.state_on),
        DP_CRUISE);
    updateAutoLock();
    if (allDps != null) {
      StringBuilder values = new StringBuilder();
      for (Map.Entry<String, Object> entry : new TreeMap<>(state.dps).entrySet()) {
        T4AState.DpInfo info = state.schema.get(entry.getKey());
        String name =
            info == null || info.code == null
                ? getString(R.string.dp_fallback, entry.getKey())
                : info.code;
        values.append(getString(R.string.dp_entry, name, entry.getKey(), entry.getValue()));
      }
      set(allDps, values.toString());
    }
  }

  private void buildPairingActions() {
    if (state.pairing == T4AState.Pairing.READY) {
      Button b = alwaysButton(getString(R.string.pair_t4a));
      b.setOnClickListener(v -> session.pair());
      contentPanel.addView(b);
    } else if (state.pairing != T4AState.Pairing.SCANNING
        && state.pairing != T4AState.Pairing.PAIRING) {
      Button b = alwaysButton(getString(R.string.find_t4a));
      b.setOnClickListener(v -> session.scan());
      contentPanel.addView(b);
    }
  }

  private void addBooleanTo(
      LinearLayout parent, String id, String a, boolean av, String b, boolean bv) {
    LinearLayout row = new LinearLayout(this);
    row.addView(actionCard(a, BLUE, () -> session.publish(id, av)), weight());
    row.addView(actionCard(b, GREEN, () -> session.publish(id, bv)), weight());
    parent.addView(row);
  }

  private Button[] addDistanceSelector(LinearLayout parent) {
    LinearLayout row = new LinearLayout(this);
    String[] labels = {
      getString(R.string.distance_short_range, getString(R.string.distance_short)),
      getString(R.string.distance_medium_range, getString(R.string.distance_medium)),
      getString(R.string.distance_long_range, getString(R.string.distance_long))
    };
    String[] values = {"short", "medium", "long"};
    Button[] buttons = new Button[labels.length];
    for (int i = 0; i < labels.length; i++) {
      String value = values[i];
      buttons[i] = actionCard(labels[i], BLUE, () -> session.setAutoLockDistance(value));
      buttons[i].setTag(labels[i]);
      buttons[i].setTextSize(11);
      buttons[i].setPadding(dp(2), dp(3), dp(2), dp(3));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
      lp.setMargins(dp(3), dp(5), dp(3), dp(3));
      row.addView(buttons[i], lp);
    }
    parent.addView(row);
    return buttons;
  }

  private Button[] addCompactEnumTo(
      LinearLayout parent, String id, int[] labelIds, Object[] values) {
    LinearLayout row = new LinearLayout(this);
    Button[] buttons = new Button[labelIds.length];
    for (int i = 0; i < labelIds.length; i++) {
      Object value = values[i];
      String label = getString(labelIds[i]);
      buttons[i] = actionCard(label, BLUE, () -> session.publish(id, value));
      buttons[i].setTag(label);
      buttons[i].setTextSize(12);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1);
      lp.setMargins(dp(3), dp(5), dp(3), dp(3));
      row.addView(buttons[i], lp);
    }
    parent.addView(row);
    return buttons;
  }

  private Button[] addThemeSelector(LinearLayout parent) {
    LinearLayout row = new LinearLayout(this);
    int[] ids = {R.string.theme_system, R.string.theme_light, R.string.theme_dark};
    String[] values = {"system", "light", "dark"};
    Button[] buttons = new Button[3];
    for (int i = 0; i < 3; i++) {
      String value = values[i];
      String label = getString(ids[i]);
      buttons[i] = actionCard(label, BLUE, () -> applyTheme(value));
      buttons[i].setTag(label);
      buttons[i].setTextSize(11);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
      lp.setMargins(dp(3), dp(4), dp(3), dp(3));
      row.addView(buttons[i], lp);
    }
    parent.addView(row);
    return buttons;
  }

  private Button[] addEnumTo(LinearLayout parent, String id, String[] labels, Object[] values) {
    LinearLayout row = new LinearLayout(this);
    Button[] buttons = new Button[labels.length];
    String[] modeIcons = {"cmd-walk", "cmd-leaf", "cmd-flag-checkered", "cmd-speedometer"};
    for (int i = 0; i < labels.length; i++) {
      Object value = values[i];
      int color = i == 0 ? 0xFF08A6B7 : i == 1 ? GREEN : i == 2 ? 0xFFFF8A00 : RED;
      buttons[i] = actionCard(labels[i], color, () -> session.publish(id, value));
      buttons[i].setTag(labels[i]);
      if (DP_LEVEL.equals(id)) {
        Drawable icon = iconDrawable(modeIcons[i], color, 22);
        buttons[i].setCompoundDrawables(null, icon, null, null);
        buttons[i].setCompoundDrawablePadding(dp(3));
        buttons[i].setTextSize(12);
        buttons[i].setTypeface(Typeface.DEFAULT, Typeface.BOLD);
      }
      LinearLayout.LayoutParams lp = weight();
      if (DP_LEVEL.equals(id)) lp.height = dp(78);
      row.addView(buttons[i], lp);
    }
    parent.addView(row);
    return buttons;
  }

  private ImageButton addStateToggle(
      LinearLayout parent, String title, String id, Object offValue, Object onValue) {
    int color = controlColor(id);
    LinearLayout tile = vertical();
    tile.setGravity(Gravity.CENTER);
    tile.setPadding(dp(5), dp(4), dp(5), dp(4));
    tile.setBackground(round(surfaceColor(), 14, color, 1));
    TextView label = text(title, 10);
    label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    label.setGravity(Gravity.CENTER);
    ImageButton control = new ImageButton(this);
    control.setTag(label);
    control.setImageDrawable(stateIcon(id, false, color));
    control.setBackgroundColor(Color.TRANSPARENT);
    control.setPadding(dp(5), dp(5), dp(5), dp(5));
    control.setClickable(false);
    control.setFocusable(false);
    tile.addView(control, new LinearLayout.LayoutParams(dp(40), dp(40)));
    tile.addView(label, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(76), 1);
    lp.setMargins(dp(3), dp(4), dp(3), dp(4));
    parent.addView(tile, lp);
    tile.setOnClickListener(
        v -> {
          boolean checked = "on".contentEquals(control.getContentDescription());
          tile.setEnabled(false);
          control.setEnabled(false);
          session.publish(id, checked ? offValue : onValue);
        });
    return control;
  }

  private void updateToggle(
      ImageButton control, boolean checked, String offLabel, String onLabel, String dpId) {
    if (control == null) return;
    int color = controlColor(dpId);
    control.setContentDescription(checked ? "on" : "off");
    control.setImageDrawable(stateIcon(dpId, checked, color));
    TextView label = control.getTag() instanceof TextView ? (TextView) control.getTag() : null;
    if (label != null) {
      label.setText(checked ? onLabel : offLabel);
      label.setTextColor(checked ? color : foregroundColor());
    }
    View tile = (View) control.getParent();
    tile.setBackground(
        round(
            checked ? withAlpha(color, isDarkMode() ? 0x30 : 0x12) : surfaceColor(),
            14,
            color,
            checked ? 2 : 1));
    boolean enabled = state.connected && !state.pendingDps.contains(dpId);
    control.setEnabled(enabled);
    tile.setEnabled(enabled);
    tile.setAlpha(enabled ? 1f : 0.55f);
  }

  private int controlColor(String dpId) {
    if (DP_LIGHT.equals(dpId)) return 0xFFFF8A00;
    if (DP_START.equals(dpId)) return GREEN;
    return BLUE;
  }

  private Drawable stateIcon(String dpId, boolean checked, int color) {
    String icon =
        DP_LIGHT.equals(dpId)
            ? "cmd-car-light-dimmed"
            : DP_START.equals(dpId) ? "cmd-run" : "cmd-car-cruise-control";
    return iconDrawable(icon, checked ? color : 0xFF667085, 28);
  }

  private Drawable iconDrawable(String icon, int color, int size) {
    IconicsDrawable drawable = new IconicsDrawable(this, icon);
    drawable.setColorList(ColorStateList.valueOf(color));
    drawable.setSizeXPx(dp(size));
    drawable.setSizeYPx(dp(size));
    return drawable;
  }

  private Drawable batteryDrawable(int percent) {
    return iconDrawable("cmd-battery-charging", percent > 20 ? GREEN : RED, 32);
  }

  private void updateSignalIcon() {
    if (rssi == null) return;
    int value = state.rssi;
    String icon =
        value == 0
            ? "cmd-signal-cellular-outline"
            : value >= -40
                ? "cmd-signal-cellular-3"
                : value >= -60 ? "cmd-signal-cellular-2" : "cmd-signal-cellular-1";
    int color = value == 0 ? 0xFF98A2B3 : value >= -40 ? GREEN : value >= -60 ? 0xFFFF8A00 : RED;
    setStartIcon(rssi, icon, color, 18);
  }

  private void updateLevelIcon() {
    if (level == null) return;
    int index = levelIndex(state.dps.get(DP_LEVEL));
    String[] icons = {"cmd-walk", "cmd-leaf", "cmd-flag-checkered", "cmd-speedometer"};
    int[] colors = {0xFF08A6B7, GREEN, 0xFFFF8A00, RED};
    level.setText("");
    if (index >= 0) {
      level.setCompoundDrawables(null, null, iconDrawable(icons[index], colors[index], 23), null);
      level.setContentDescription(levelLabel(state.dps.get(DP_LEVEL)));
    } else {
      level.setCompoundDrawables(null, null, null, null);
      level.setContentDescription(getString(R.string.unknown));
    }
  }

  private void setStartIcon(TextView view, String icon, int color, int size) {
    view.setCompoundDrawables(iconDrawable(icon, color, size), null, null, null);
  }

  private void select(Button[] buttons, int selected, int[] colors) {
    if (buttons == null) return;
    for (int i = 0; i < buttons.length; i++) {
      String label = String.valueOf(buttons[i].getTag());
      boolean active = i == selected;
      buttons[i].setText(label);
      buttons[i].setTypeface(Typeface.DEFAULT, Typeface.BOLD);
      buttons[i].setTextColor(active ? colors[i] : withAlpha(colors[i], 0xDD));
      buttons[i].setElevation(dp(active ? 3 : 1));
      buttons[i].setBackground(
          round(
              active ? withAlpha(colors[i], isDarkMode() ? 0x38 : 0x16) : surfaceColor(),
              14,
              colors[i],
              active ? 2 : 1));
    }
  }

  private int withAlpha(int color, int alpha) {
    return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
  }

  private int levelIndex(Object value) {
    if (value == null) return -1;
    return switch (value.toString()) {
      case "level_0" -> 0;
      case "level_1" -> 1;
      case "level_2" -> 2;
      case "level_3" -> 3;
      default -> -1;
    };
  }

  private int autoLockDistanceIndex(String value) {
    return switch (value) {
      case "short" -> 0;
      case "long" -> 2;
      default -> 1;
    };
  }

  private void setActionsEnabled(View view, boolean enabled) {
    if (view instanceof Button b && !(view instanceof Switch))
      b.setEnabled(
              enabled
                      || "always".equals(b.getTag())
                      || contains(autoLockDistanceButtons, b)
                      || contains(themeButtons, b));
    if (view instanceof LinearLayout g)
      for (int i = 0; i < g.getChildCount(); i++) setActionsEnabled(g.getChildAt(i), enabled);
  }

  private boolean contains(Button[] buttons, Button target) {
    if (buttons == null) return false;
    for (Button button : buttons) if (button == target) return true;
    return false;
  }

  private void clearRefs() {
    speed =
        speedUnit =
            battery =
                rssi =
                    pairing =
                        connection =
                            device =
                                mac =
                                    connectionIcon =
                                        lock =
                                            level =
                                                unit =
                                                    cruise =
                                                        start =
                                                            odoTotal =
                                                                odoTrip = usageTime = allDps = null;
    batteryBar = null;
    batteryIcon = null;
    odometerIcon = null;
    speedProgress = null;
    modeButtons =
        autoLockDistanceButtons =
            unitPreferenceButtons =
                cruisePreferenceButtons = startPreferenceButtons = themeButtons = null;
    lightSwitch = startSwitch = cruiseSwitch = autoLockButton = null;
    autoLockLabel = null;
  }

  private void applyKeepScreenOn(boolean enabled) {
    if (enabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  private void applyTheme(String value) {
    preferences().edit().putString(PREF_THEME, value).apply();
    rebuildUi();
  }

  private void rebuildUi() {
    renderedStructure = "";
    clearRefs();
    buildUi();
    configureSystemBars();
    render();
  }

  private int themeIndex(String value) {
    return "light".equals(value) ? 1 : "dark".equals(value) ? 2 : 0;
  }

  private boolean isDarkMode() {
    String mode = preferences().getString(PREF_THEME, "system");
    if ("dark".equals(mode)) return true;
    if ("light".equals(mode)) return false;
    return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
        == Configuration.UI_MODE_NIGHT_YES;
  }

  private int backgroundColor() {
    return isDarkMode() ? 0xFF0D1117 : 0xFFF7F8FB;
  }

  private int surfaceColor() {
    return isDarkMode() ? 0xFF171D26 : Color.WHITE;
  }

  private int headerColor() {
    return isDarkMode() ? 0xFF202836 : 0xFFF8FAFD;
  }

  private int foregroundColor() {
    return isDarkMode() ? 0xFFF1F5F9 : 0xFF101820;
  }

  private int mutedColor() {
    return isDarkMode() ? 0xFFAAB4C3 : 0xFF667085;
  }

  private int outlineColor() {
    return isDarkMode() ? 0xFF354052 : 0xFFE4E8F0;
  }

  private void toggleOdometer() {
    showTotalOdometer = !showTotalOdometer;
    preferences().edit().putBoolean(PREF_TOTAL_ODOMETER, showTotalOdometer).commit();
    updateValues();
  }

  private void configureSystemBars() {
    getWindow().setStatusBarColor(backgroundColor());
    getWindow().setNavigationBarColor(backgroundColor());
    WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
    if (controller != null) {
      int light =
          isDarkMode()
              ? 0
              : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                  | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
      controller.setSystemBarsAppearance(
          light,
          WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
              | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }
  }

  private SharedPreferences preferences() {
    return getSharedPreferences("t4a_settings", MODE_PRIVATE);
  }

  private Button actionCard(String label, int color, Runnable command) {
    Button b = new Button(this);
    b.setText(label);
    b.setTextColor(color);
    b.setTextSize(14);
    b.setAllCaps(false);
    b.setGravity(Gravity.CENTER);
    b.setPadding(dp(5), dp(8), dp(5), dp(8));
    b.setBackground(round(surfaceColor(), 12, color, 1));
    b.setOnClickListener(v -> command.run());
    return b;
  }

  private LinearLayout lockActionCard(String label, String icon, int color, Runnable command) {
    LinearLayout card = vertical();
    card.setGravity(Gravity.CENTER);
    card.setBackground(round(surfaceColor(), 12, color, 1));
    ImageButton button = new ImageButton(this);
    button.setImageDrawable(iconDrawable(icon, color, 22));
    button.setBackgroundColor(Color.TRANSPARENT);
    button.setPadding(dp(4), dp(4), dp(4), dp(4));
    TextView labelView = text(label, 11);
    labelView.setTextColor(color);
    labelView.setGravity(Gravity.CENTER);
    card.addView(button, new LinearLayout.LayoutParams(dp(34), dp(34)));
    card.addView(labelView);
    View.OnClickListener action = v -> command.run();
    card.setOnClickListener(action);
    button.setOnClickListener(action);
    return card;
  }

  private LinearLayout autoLockCard() {
    LinearLayout card = vertical();
    card.setGravity(Gravity.CENTER);
    card.setBackground(round(surfaceColor(), 12, BLUE, 1));
    autoLockButton = new ImageButton(this);
    autoLockButton.setBackgroundColor(Color.TRANSPARENT);
    autoLockButton.setPadding(dp(4), dp(4), dp(4), dp(4));
    autoLockLabel = text(getString(R.string.automatic_lock), 11);
    autoLockLabel.setGravity(Gravity.CENTER);
    autoLockLabel.setTextColor(BLUE);
    card.addView(autoLockButton, new LinearLayout.LayoutParams(dp(34), dp(34)));
    card.addView(autoLockLabel);
    View.OnClickListener action = v -> session.setAutoLockEnabled(!state.autoLockEnabled);
    card.setOnClickListener(action);
    autoLockButton.setOnClickListener(action);
    return card;
  }

  private void updateAutoLock() {
    if (autoLockButton == null) return;
    boolean enabled = state.autoLockEnabled;
    autoLockButton.setImageDrawable(
        iconDrawable("cmd-bluetooth", enabled ? BLUE : mutedColor(), 22));
    autoLockLabel.setText(
        enabled
            ? twoLine(R.string.automatic_lock, R.string.state_active)
            : getString(R.string.automatic_lock));
    autoLockLabel.setTypeface(Typeface.DEFAULT, enabled ? Typeface.BOLD : Typeface.NORMAL);
    View card = (View) autoLockButton.getParent();
    card.setBackground(
        round(
            enabled ? withAlpha(BLUE, isDarkMode() ? 0x30 : 0x12) : surfaceColor(),
            12,
            BLUE,
            enabled ? 2 : 1));
  }

  private Button alwaysButton(String label) {
    Button b = new Button(this);
    b.setText(label);
    b.setTag("always");
    return b;
  }

  private LinearLayout card() {
    LinearLayout box = vertical();
    box.setPadding(dp(10), dp(10), dp(10), dp(10));
    box.setBackground(round(surfaceColor(), 16, outlineColor(), 1));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(0, dp(5), 0, dp(5));
    box.setLayoutParams(lp);
    box.setElevation(dp(2));
    return box;
  }

  private TextView sectionTitle(String value) {
    TextView title = text(value, 18);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setTextColor(BLUE);
    title.setPadding(0, 0, 0, dp(8));
    return title;
  }

  private LinearLayout collapsibleSection(String title, String key) {
    LinearLayout shell = card();
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setMinimumHeight(dp(40));
    TextView label = sectionTitle(title);
    label.setPadding(dp(4), 0, 0, 0);
    ImageView arrow = new ImageView(this);
    arrow.setScaleType(ImageView.ScaleType.CENTER);
    header.addView(label, new LinearLayout.LayoutParams(0, dp(40), 1));
    header.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(28)));
    LinearLayout body = vertical();
    body.setPadding(dp(4), dp(4), dp(4), 0);
    boolean expanded = preferences().getBoolean("section_" + key, true);
    body.setVisibility(expanded ? View.VISIBLE : View.GONE);
    setSectionArrow(arrow, expanded);
    header.setOnClickListener(
        v -> {
          boolean show = body.getVisibility() != View.VISIBLE;
          body.setVisibility(show ? View.VISIBLE : View.GONE);
          setSectionArrow(arrow, show);
          preferences().edit().putBoolean("section_" + key, show).apply();
        });
    header.setBackground(round(headerColor(), 10, 0, 0));
    header.setPadding(dp(4), 0, dp(2), 0);
    shell.addView(header);
    shell.addView(body);
    contentPanel.addView(shell);
    return body;
  }

  private void setSectionArrow(ImageView arrow, boolean expanded) {
    arrow.setImageDrawable(
        iconDrawable(expanded ? "cmd-chevron-up" : "cmd-chevron-down", BLUE, 16));
  }

  private LinearLayout metricWithIcon(String icon, int color) {
    LinearLayout box = new LinearLayout(this);
    box.setGravity(Gravity.CENTER);
    box.setPadding(dp(4), dp(2), dp(4), dp(2));
    ImageView image = new ImageView(this);
    image.setImageDrawable(iconDrawable(icon, color, 23));
    box.addView(image, new LinearLayout.LayoutParams(dp(28), dp(28)));
    TextView value = text("", 11);
    value.setPadding(dp(7), 0, 0, 0);
    box.addView(value, new LinearLayout.LayoutParams(-2, -2));
    return box;
  }

  private View metricDivider() {
    View divider = new View(this);
    divider.setBackgroundColor(outlineColor());
    return divider;
  }

  private LinearLayout.LayoutParams weight() {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(82), 1);
    lp.setMargins(dp(3), dp(5), dp(3), dp(5));
    return lp;
  }

  private GradientDrawable round(int fill, int radiusDp, int stroke, int strokeDp) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(radiusDp));
    if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
    return d;
  }

  private void updateBatteryBar(int percent) {
    if (batteryBar == null) return;
    batteryBar.removeAllViews();
    for (int i = 0; i < 16; i++) {
      View segment = new View(this);
      int threshold = (i + 1) * 100 / 16;
      int color = threshold <= percent ? (i < 3 ? RED : i < 8 ? 0xFFFFB300 : GREEN) : 0xFFE8EBF0;
      segment.setBackground(round(color, 3, 0, 0));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
      lp.setMargins(dp(1), 0, dp(1), 0);
      batteryBar.addView(segment, lp);
    }
  }

  private LinearLayout vertical() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private TextView line() {
    TextView v = text("", 15);
    v.setPadding(0, dp(4), 0, dp(1));
    return v;
  }

  private TextView text(String value, float size) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(foregroundColor());
    return v;
  }

  private void set(TextView view, String value) {
    if (view != null && !value.contentEquals(view.getText())) view.setText(value);
  }

  private String twoLine(int title, int stateValue) {
    return getString(R.string.two_line_state, getString(title), getString(stateValue));
  }

  private String speedLimit(int mode, int limit) {
    return getString(R.string.speed_limit, getString(mode), limit);
  }

  private String empty(String value) {
    return value.isEmpty() ? "--" : value;
  }

  private String boolLabel(Object value) {
    return getString(truth(value) ? R.string.state_on : R.string.state_off)
        .toLowerCase(Locale.ROOT);
  }

  private String lockLabel(Object value) {
    return getString(truth(value) ? R.string.state_unlocked : R.string.state_locked);
  }

  private String levelLabel(Object value) {
    if (value == null) return getString(R.string.unknown);
    return switch (value.toString()) {
      case "level_0" -> getString(R.string.mode_walk);
      case "level_1" -> getString(R.string.mode_eco);
      case "level_2" -> getString(R.string.mode_race);
      case "level_3" -> getString(R.string.mode_sport);
      default -> value.toString();
    };
  }

  private String pairingLabel(T4AState.Pairing value) {
    return getString(
        switch (value) {
          case PAIRED -> R.string.pairing_paired;
          case PAIRING -> R.string.pairing_in_progress;
          case READY -> R.string.pairing_ready;
          case SCANNING -> R.string.pairing_scanning;
          case REMOVING -> R.string.pairing_removing;
          case NO_HOME -> R.string.pairing_no_home;
          default -> R.string.pairing_unpaired;
        });
  }

  private String distance(Object value) {
    return getString(R.string.distance_km, scaled(value, 10));
  }

  private String duration(int seconds) {
    int safe = Math.max(0, seconds);
    return String.format(Locale.ROOT, "%02d:%02d:%02d", safe / 3600, (safe % 3600) / 60, safe % 60);
  }

  private boolean truth(Object value) {
    return value != null
        && (Boolean.TRUE.equals(value)
            || "true".equalsIgnoreCase(value.toString())
            || "1".equals(value.toString()));
  }

  private int integer(Object value) {
    try {
      return value == null ? 0 : Integer.parseInt(value.toString());
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private double scaled(Object value, double divisor) {
    try {
      return value == null ? 0 : Double.parseDouble(value.toString()) / divisor;
    } catch (NumberFormatException ignored) {
      return 0;
    }
  }

  private void ensurePermissions() {
    if (!hasBluetoothPermissions())
      requestPermissions(
          new String[] {
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
          },
          REQUEST_BLUETOOTH);
  }

  private boolean hasBluetoothPermissions() {
    return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED;
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode != REQUEST_BLUETOOTH) return;
    if (hasBluetoothPermissions()) {
      if (uiForeground) connectSession();
    } else {
      Toast.makeText(
              this,
              "Permissões Bluetooth são necessárias para manter a sessão T4A.",
              Toast.LENGTH_LONG)
          .show();
    }
  }

  private void connectSession() {
    if (sessionBindingRequested || !hasBluetoothPermissions()) return;
    Intent intent = new Intent(this, T4ASessionService.class);
    try {
      startForegroundService(intent);
      sessionBindingRequested = bindService(intent, sessionConnection, BIND_AUTO_CREATE);
    } catch (RuntimeException error) {
      sessionBindingRequested = false;
      Toast.makeText(this, "Não foi possível iniciar a sessão T4A.", Toast.LENGTH_LONG).show();
    }
  }

  private void disconnectSession() {
    session.removeListener(this);
    session.setUiForeground(false);
    session = T4ASession.EMPTY;
    if (!sessionBindingRequested) return;
    try {
      unbindService(sessionConnection);
    } catch (IllegalArgumentException ignored) {
      // Binding may already have been removed by the system.
    }
    sessionBindingRequested = false;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private final class SpeedBar extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float speedValue;

    SpeedBar() {
      super(MainActivity.this);
      setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setSpeed(float value) {
      speedValue = Math.max(0, Math.min(50, value));
      invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float left = dp(4),
          top = dp(8),
          barBottom = getHeight() - dp(28),
          width = getWidth() - 2 * left,
          unit = width / 50f;
      for (int i = 0; i < 50; i++) {
        int value = i + 1;
        float gap = dp(value % 5 == 0 ? 1 : 0);
        float x = left + i * unit;
        int progressColor =
            value <= 6 ? 0xFF08A6B7 : value <= 25 ? GREEN : value <= 35 ? 0xFFFF8A00 : RED;
        paint.setColor(
            speedValue >= value
                ? withAlpha(progressColor, isDarkMode() ? 0xCC : 0x99)
                : (isDarkMode() ? 0xFF293241 : 0xFFF0F2F6));
        canvas.drawRect(new RectF(x, top, x + unit - gap, barBottom), paint);
      }
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(dp(1));
      paint.setColor(isDarkMode() ? 0xFF526075 : 0xFFB8C2D3);
      canvas.drawRoundRect(new RectF(left, top, getWidth() - left, barBottom), dp(6), dp(6), paint);
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(BLUE);
      paint.setTextSize(dp(11));
      paint.setTextAlign(Paint.Align.CENTER);
      for (int value = 5; value < 50; value += 5) {
        float separator = left + width * (value / 50f);
        canvas.drawText(String.valueOf(value), separator, getHeight() - dp(7), paint);
      }
      paint.setTextAlign(Paint.Align.LEFT);
    }
  }
}
