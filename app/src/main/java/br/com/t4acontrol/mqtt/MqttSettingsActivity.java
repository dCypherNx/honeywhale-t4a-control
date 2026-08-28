package br.com.t4acontrol.mqtt;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import br.com.t4acontrol.R;
import br.com.t4acontrol.T4AApplication;
import com.mikepenz.iconics.IconicsDrawable;

/** MQTT configuration UI. Depends only on {@link MqttSettings}, never on persistence/backend types. */
public final class MqttSettingsActivity extends Activity {
  private static final int BLUE = 0xFF075EF0;
  private static final int GREEN = 0xFF00A529;
  private static final String PREF_THEME = "theme_mode";

  private MqttSettings settings;
  private Switch enabled;
  private Switch tls;
  private EditText host;
  private EditText port;
  private EditText username;
  private EditText password;
  private EditText clientId;
  private EditText baseTopic;
  private EditText keepAlive;
  private TextView status;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    settings = ((T4AApplication) getApplication()).mqttSettings();
    buildUi();
    configureSystemBars();
    load();
  }

  private void buildUi() {
    LinearLayout body = vertical();
    body.setBackgroundColor(backgroundColor());

    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    ImageButton back = new ImageButton(this);
    back.setImageDrawable(icon("cmd-arrow-left", foregroundColor(), 25));
    back.setBackground(round(surfaceColor(), 99, outlineColor(), 1));
    back.setPadding(dp(12), dp(12), dp(12), dp(12));
    back.setContentDescription(getString(R.string.mqtt_back));
    back.setOnClickListener(v -> finish());
    header.addView(back, new LinearLayout.LayoutParams(dp(52), dp(52)));

    TextView title = text(getString(R.string.mqtt_title), 28);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setPadding(dp(14), 0, 0, 0);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    body.addView(header);

    TextView description = text(getString(R.string.mqtt_description), 13);
    description.setTextColor(mutedColor());
    description.setPadding(dp(2), dp(10), dp(2), dp(8));
    body.addView(description);

    LinearLayout activation = card();
    enabled = switchView(R.string.mqtt_enabled);
    activation.addView(enabled);
    status = text("", 12);
    status.setTextColor(mutedColor());
    status.setPadding(0, dp(4), 0, 0);
    activation.addView(status);
    body.addView(activation);

    LinearLayout broker = card();
    broker.addView(sectionTitle(R.string.mqtt_broker_section));
    host = field(broker, R.string.mqtt_host, R.string.mqtt_host_hint, InputType.TYPE_CLASS_TEXT);
    port =
        field(
            broker,
            R.string.mqtt_port,
            R.string.mqtt_port_hint,
            InputType.TYPE_CLASS_NUMBER);
    tls = switchView(R.string.mqtt_tls);
    broker.addView(tls);
    keepAlive =
        field(
            broker,
            R.string.mqtt_keep_alive,
            R.string.mqtt_keep_alive_hint,
            InputType.TYPE_CLASS_NUMBER);
    body.addView(broker);

    LinearLayout credentials = card();
    credentials.addView(sectionTitle(R.string.mqtt_credentials_section));
    username =
        field(
            credentials,
            R.string.mqtt_username,
            R.string.mqtt_username_hint,
            InputType.TYPE_CLASS_TEXT);
    password =
        field(
            credentials,
            R.string.mqtt_password,
            R.string.mqtt_password_hint,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
    TextView secretNote = text(getString(R.string.mqtt_password_secure), 11);
    secretNote.setTextColor(mutedColor());
    secretNote.setPadding(dp(3), 0, dp(3), dp(6));
    credentials.addView(secretNote);
    body.addView(credentials);

    LinearLayout identity = card();
    identity.addView(sectionTitle(R.string.mqtt_identity_section));
    clientId =
        field(
            identity,
            R.string.mqtt_client_id,
            R.string.mqtt_client_id_hint,
            InputType.TYPE_CLASS_TEXT);
    baseTopic =
        field(
            identity,
            R.string.mqtt_base_topic,
            R.string.mqtt_base_topic_hint,
            InputType.TYPE_CLASS_TEXT);
    TextView topicNote = text(getString(R.string.mqtt_topic_note), 11);
    topicNote.setTextColor(mutedColor());
    topicNote.setPadding(dp(3), 0, dp(3), dp(6));
    identity.addView(topicNote);
    body.addView(identity);

    Button save = new Button(this);
    save.setText(R.string.mqtt_save);
    save.setAllCaps(false);
    save.setTextSize(15);
    save.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    save.setTextColor(Color.WHITE);
    save.setBackground(round(BLUE, 12, BLUE, 1));
    save.setOnClickListener(v -> save());
    LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-1, dp(54));
    saveParams.setMargins(0, dp(8), 0, dp(8));
    body.addView(save, saveParams);

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
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

  private void load() {
    MqttSettings.Snapshot value = settings.load();
    enabled.setChecked(value.enabled);
    host.setText(value.host);
    port.setText(value.port);
    tls.setChecked(value.tls);
    username.setText(value.username);
    password.setText(value.password);
    clientId.setText(value.clientId);
    baseTopic.setText(value.baseTopic);
    keepAlive.setText(value.keepAliveSeconds);
    updateStatus(value.enabled, false);
  }

  private void save() {
    MqttSettings.Draft draft =
        new MqttSettings.Draft(
            enabled.isChecked(),
            value(host),
            value(port),
            tls.isChecked(),
            value(username),
            value(password),
            value(clientId),
            value(baseTopic),
            value(keepAlive));
    MqttSettings.SaveResult result = settings.save(draft);
    if (!result.saved) {
      showError(result.error);
      return;
    }
    applySnapshot(result.snapshot);
    updateStatus(result.snapshot.enabled, true);
    Toast.makeText(this, R.string.mqtt_saved, Toast.LENGTH_SHORT).show();
  }

  private void applySnapshot(MqttSettings.Snapshot value) {
    enabled.setChecked(value.enabled);
    host.setText(value.host);
    port.setText(value.port);
    tls.setChecked(value.tls);
    username.setText(value.username);
    password.setText(value.password);
    clientId.setText(value.clientId);
    baseTopic.setText(value.baseTopic);
    keepAlive.setText(value.keepAliveSeconds);
  }

  private void showError(MqttSettings.Error error) {
    int message =
        switch (error) {
          case HOST_REQUIRED -> R.string.mqtt_error_host;
          case PORT_INVALID -> R.string.mqtt_error_port;
          case CLIENT_ID_REQUIRED -> R.string.mqtt_error_client_id;
          case BASE_TOPIC_REQUIRED -> R.string.mqtt_error_base_topic;
          case KEEP_ALIVE_INVALID -> R.string.mqtt_error_keep_alive;
          default -> R.string.mqtt_error_storage;
        };
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  private void updateStatus(boolean active, boolean saved) {
    status.setText(
        saved
            ? getString(R.string.mqtt_status_saved)
            : getString(active ? R.string.mqtt_status_enabled : R.string.mqtt_status_disabled));
    status.setTextColor(active ? GREEN : mutedColor());
  }

  private EditText field(LinearLayout parent, int labelId, int hintId, int inputType) {
    TextView label = text(getString(labelId), 12);
    label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    label.setPadding(dp(3), dp(7), dp(3), 0);
    parent.addView(label);
    EditText input = new EditText(this);
    input.setHint(hintId);
    input.setInputType(inputType);
    input.setSingleLine(true);
    input.setTextColor(foregroundColor());
    input.setHintTextColor(mutedColor());
    input.setTextSize(14);
    input.setPadding(dp(4), dp(3), dp(4), dp(6));
    parent.addView(input, new LinearLayout.LayoutParams(-1, dp(50)));
    return input;
  }

  private Switch switchView(int textId) {
    Switch value = new Switch(this);
    value.setText(textId);
    value.setTextColor(foregroundColor());
    value.setTextSize(14);
    value.setPadding(dp(3), dp(7), dp(3), dp(7));
    return value;
  }

  private TextView sectionTitle(int stringId) {
    TextView title = text(getString(stringId), 18);
    title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    title.setTextColor(BLUE);
    title.setPadding(dp(3), 0, dp(3), dp(5));
    return title;
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

  private LinearLayout vertical() {
    LinearLayout value = new LinearLayout(this);
    value.setOrientation(LinearLayout.VERTICAL);
    return value;
  }

  private TextView text(String value, float size) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(foregroundColor());
    return view;
  }

  private String value(EditText input) {
    return input.getText().toString().trim();
  }

  private IconicsDrawable icon(String name, int color, int size) {
    IconicsDrawable drawable = new IconicsDrawable(this, name);
    drawable.setColorList(ColorStateList.valueOf(color));
    drawable.setSizeXPx(dp(size));
    drawable.setSizeYPx(dp(size));
    return drawable;
  }

  private GradientDrawable round(int fill, int radiusDp, int stroke, int strokeDp) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(fill);
    drawable.setCornerRadius(dp(radiusDp));
    drawable.setStroke(dp(strokeDp), stroke);
    return drawable;
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

  private boolean isDarkMode() {
    String mode =
        getSharedPreferences("t4a_settings", MODE_PRIVATE).getString(PREF_THEME, "system");
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

  private int foregroundColor() {
    return isDarkMode() ? 0xFFF1F5F9 : 0xFF101820;
  }

  private int mutedColor() {
    return isDarkMode() ? 0xFFAAB4C3 : 0xFF667085;
  }

  private int outlineColor() {
    return isDarkMode() ? 0xFF354052 : 0xFFE4E8F0;
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
