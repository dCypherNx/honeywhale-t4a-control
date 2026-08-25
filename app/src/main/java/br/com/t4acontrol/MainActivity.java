package br.com.t4acontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import br.com.t4acontrol.backend.T4ABackend;
import br.com.t4acontrol.backend.T4AState;

public final class MainActivity extends Activity implements T4ABackend.Listener {
    private static final int REQUEST_BLUETOOTH = 100;
    private static final String DP_LOCK = "1", DP_SPEED = "2", DP_BATTERY = "3";
    private static final String DP_ODO_TRIP = "5", DP_TIME = "6", DP_LIGHT = "8";
    private static final String DP_UNIT = "11", DP_ODO_TOTAL = "12", DP_CRUISE = "13";
    private static final String DP_LEVEL = "14", DP_START = "16", DP_BRAKE = "39";

    private T4ABackend backend;
    private T4AState state;
    private LinearLayout authPanel, devicePanel, contentPanel;
    private TextView cloudStatus, status, events;
    private EditText email, password;
    private boolean settings;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        backend = new T4ABackend(this);
        ensurePermissions();
    }

    @Override protected void onResume() { super.onResume(); if (backend != null) backend.start(); }
    @Override protected void onPause() { if (backend != null) backend.stop(); super.onPause(); }
    @Override protected void onDestroy() { if (backend != null) backend.destroy(); super.onDestroy(); }

    private void buildUi() {
        LinearLayout body = vertical();
        body.setPadding(dp(18), dp(28), dp(18), dp(20));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("T4A Control", 25), new LinearLayout.LayoutParams(0, -2, 1));
        Button mode = new Button(this);
        mode.setText("Ajustes");
        mode.setOnClickListener(v -> { settings = !settings; mode.setText(settings ? "Painel" : "Ajustes"); render(); });
        header.addView(mode);
        body.addView(header);

        cloudStatus = text("Verificando conta…", 12); body.addView(cloudStatus);
        status = text("", 18); status.setPadding(0, dp(10), 0, dp(8)); body.addView(status);
        authPanel = vertical();
        email = new EditText(this); email.setHint("E-mail"); authPanel.addView(email);
        password = new EditText(this); password.setHint("Senha");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); authPanel.addView(password);
        Button login = new Button(this); login.setText("Entrar");
        login.setOnClickListener(v -> { backend.login(email.getText().toString().trim(), password.getText().toString()); password.setText(""); });
        authPanel.addView(login); body.addView(authPanel);

        devicePanel = vertical(); devicePanel.setVisibility(View.GONE);
        contentPanel = vertical(); devicePanel.addView(contentPanel);
        events = text("", 11); events.setPadding(0, dp(15), 0, 0); devicePanel.addView(events);
        body.addView(devicePanel);
        ScrollView scroll = new ScrollView(this); scroll.addView(body); setContentView(scroll);
    }

    @Override public void onState(T4AState value) { runOnUiThread(() -> { state = value; render(); }); }
    @Override public void onEvent(String event) { runOnUiThread(() -> events.setText(DateFormat.getTimeInstance().format(new Date()) + "  " + event + "\n" + events.getText())); }

    private void render() {
        if (state == null) return;
        authPanel.setVisibility(state.authenticated ? View.GONE : View.VISIBLE);
        devicePanel.setVisibility(state.authenticated ? View.VISIBLE : View.GONE);
        cloudStatus.setText(state.authenticated ? "Conta: " + state.account : "Conta não autenticada");
        status.setText(state.message); status.setTextColor(state.connected ? 0xFF2E7D32 : 0xFF555555);
        contentPanel.removeAllViews();
        if (!state.authenticated) return;
        if (settings) renderSettings(); else renderDashboard();
    }

    private void renderDashboard() {
        if (state.pairing != T4AState.Pairing.PAIRED) { renderPairingActions(); return; }
        double unitFactor = "mile".equals(state.dps.get(DP_UNIT)) ? 0.621371 : 1.0;
        TextView speed = text(String.format(Locale.ROOT, "%.1f", scaled(state.dps.get(DP_SPEED), 10.0) * unitFactor), 92);
        speed.setGravity(Gravity.CENTER); contentPanel.addView(speed);
        TextView speedUnit = text(unitFactor < 1 ? "mph" : "km/h", 25); speedUnit.setGravity(Gravity.CENTER); contentPanel.addView(speedUnit);
        int battery = integer(state.dps.get(DP_BATTERY));
        TextView batteryView = text("Bateria: " + battery + "%", 24); batteryView.setGravity(Gravity.CENTER);
        batteryView.setTextColor(battery > 20 ? 0xFF2E7D32 : Color.RED); contentPanel.addView(batteryView);

        addState("Bloqueio", lockLabel(state.dps.get(DP_LOCK)));
        addAbsoluteBooleanGroup(DP_LOCK, "Desbloquear", true, "Bloquear", false);
        addState("Farol", boolLabel(state.dps.get(DP_LIGHT)));
        addAbsoluteBooleanGroup(DP_LIGHT, "Desligar", false, "Ligar", true);
        addState("Modo", levelLabel(state.dps.get(DP_LEVEL)));
        addEnumGroup(DP_LEVEL, new String[]{"WALK", "ECO", "RACE", "SPORT"}, new Object[]{"level_0", "level_1", "level_2", "level_3"});
    }

    private void renderSettings() {
        addState("Pareamento", pairingLabel(state.pairing));
        addState("Conexão BLE", state.connected ? "conectado" : "desconectado");
        if (!state.deviceName.isEmpty()) addState("Dispositivo", state.deviceName);
        if (!state.mac.isEmpty()) addState("MAC", state.mac);
        if (state.pairing != T4AState.Pairing.PAIRED) { renderPairingActions(); return; }

        addState("Unidade", String.valueOf(state.dps.get(DP_UNIT)));
        addEnumGroup(DP_UNIT, new String[]{"Milhas", "Quilômetros"}, new Object[]{"mile", "km"});
        addState("Piloto automático", boolLabel(state.dps.get(DP_CRUISE)));
        addAbsoluteBooleanGroup(DP_CRUISE, "Desativar", false, "Ativar", true);
        addState("Partida", String.valueOf(state.dps.get(DP_START)));
        addEnumGroup(DP_START, new String[]{"Partida zero", "Impulso inicial"}, new Object[]{"zero_start", "not_zero_start"});
        addState("Odômetro total", distance(state.dps.get(DP_ODO_TOTAL)));
        addState("Percurso", distance(state.dps.get(DP_ODO_TRIP)));
        addState("Tempo de uso", String.valueOf(state.dps.get(DP_TIME)));
        addState("Freio", brakeLabel(state.dps.get(DP_BRAKE)));

        LinearLayout all = group("Estados identificados dos DPS");
        for (Map.Entry<String, Object> entry : new TreeMap<>(state.dps).entrySet()) {
            T4AState.DpInfo info = state.schema.get(entry.getKey());
            String name = info == null || info.code == null ? "DP " + entry.getKey() : info.code;
            all.addView(text(name + " (DP " + entry.getKey() + "): " + entry.getValue(), 13));
        }
        contentPanel.addView(all);
        Button remove = new Button(this); remove.setText("Remover pareamento"); remove.setTextColor(Color.RED);
        remove.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Remover pareamento?")
                .setMessage("O T4A será liberado para outro aplicativo ou plataforma.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Remover", (dialog, which) -> backend.unpair()).show());
        contentPanel.addView(remove);
    }

    private void renderPairingActions() {
        if (state.pairing == T4AState.Pairing.READY) {
            Button pair = new Button(this); pair.setText("Parear T4A"); pair.setOnClickListener(v -> backend.pair()); contentPanel.addView(pair);
        } else if (state.pairing != T4AState.Pairing.SCANNING && state.pairing != T4AState.Pairing.PAIRING) {
            Button scan = new Button(this); scan.setText("Localizar T4A"); scan.setOnClickListener(v -> backend.scan()); contentPanel.addView(scan);
        }
    }

    private void addAbsoluteBooleanGroup(String dp, String firstLabel, boolean first, String secondLabel, boolean second) {
        LinearLayout row = new LinearLayout(this);
        row.addView(action(firstLabel, () -> backend.publish(dp, first)), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(action(secondLabel, () -> backend.publish(dp, second)), new LinearLayout.LayoutParams(0, -2, 1));
        contentPanel.addView(row);
    }

    private void addEnumGroup(String dp, String[] labels, Object[] values) {
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < labels.length; i++) {
            Object value = values[i];
            row.addView(action(labels[i], () -> backend.publish(dp, value)), new LinearLayout.LayoutParams(0, -2, 1));
        }
        contentPanel.addView(row);
    }

    private void addState(String label, String value) { TextView view = text(label + ": " + value, 16); view.setPadding(0, dp(9), 0, dp(3)); contentPanel.addView(view); }
    private Button action(String label, Runnable command) { Button button = new Button(this); button.setText(label); button.setEnabled(state != null && state.connected); button.setOnClickListener(v -> command.run()); return button; }
    private LinearLayout group(String title) { LinearLayout box = vertical(); TextView heading = text(title, 18); heading.setTextColor(0xFF1565C0); heading.setPadding(0, dp(14), 0, dp(5)); box.addView(heading); return box; }
    private LinearLayout vertical() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private TextView text(String value, float size) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(0xFF101820); return view; }

    private String boolLabel(Object value) { return truth(value) ? "ligado" : "desligado"; }
    private String lockLabel(Object value) { return truth(value) ? "desbloqueado" : "bloqueado"; }
    private String levelLabel(Object value) { if (value == null) return "desconhecido"; return switch (value.toString()) { case "level_0" -> "WALK"; case "level_1" -> "ECO"; case "level_2" -> "RACE"; case "level_3" -> "SPORT"; default -> value.toString(); }; }
    private String pairingLabel(T4AState.Pairing value) { return switch (value) { case PAIRED -> "pareado"; case PAIRING -> "pareando"; case READY -> "pronto para parear"; case SCANNING -> "procurando"; case REMOVING -> "removendo"; case NO_HOME -> "casa ausente"; default -> "não pareado"; }; }
    private String brakeLabel(Object value) { return (integer(value) & 0x04) != 0 ? "acionado" : "livre"; }
    private String distance(Object value) { return String.format(Locale.ROOT, "%.1f km", scaled(value, 10.0)); }
    private boolean truth(Object value) { return value != null && (Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(value.toString()) || "1".equals(value.toString())); }
    private int integer(Object value) { try { return value == null ? 0 : Integer.parseInt(value.toString()); } catch (NumberFormatException ignored) { return 0; } }
    private double scaled(Object value, double divisor) { try { return value == null ? 0 : Double.parseDouble(value.toString()) / divisor; } catch (NumberFormatException ignored) { return 0; } }

    private void ensurePermissions() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_BLUETOOTH);
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
