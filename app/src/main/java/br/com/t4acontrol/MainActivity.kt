package br.com.t4acontrol

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Insets
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.t4acontrol.backend.T4AState
import br.com.t4acontrol.mqtt.MqttSettingsActivity
import br.com.t4acontrol.session.T4ASession
import br.com.t4acontrol.session.T4ASessionService
import br.com.t4acontrol.ui.dashboard.RidingMode
import br.com.t4acontrol.ui.dashboard.T4ADashboard
import br.com.t4acontrol.ui.dashboard.T4ADashboardActions
import br.com.t4acontrol.ui.dashboard.T4ADashboardMapper
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), T4ASession.Listener {
    companion object {
        private const val REQUEST_BLUETOOTH = 100
        private const val PREF_KEEP_SCREEN_ON = "keep_screen_on"
        private const val PREF_TOTAL_ODOMETER = "show_total_odometer"
        private const val PREF_THEME = "theme_mode"
        private val BATTERY_RECHARGE_GAP_HOURS = intArrayOf(1, 2, 4, 8)
    }

    private var session: T4ASession = T4ASession.EMPTY
    private var sessionBindingRequested = false
    private var uiForeground = false
    private var batteryRechargeDialog: android.app.AlertDialog? = null

    private var state by mutableStateOf<T4AState?>(null)
    private var settings by mutableStateOf(false)
    private var showTotalOdometer by mutableStateOf(true)
    private var themeMode by mutableStateOf("system")
    private var configurationTick by mutableIntStateOf(0)
    private var lastStatusEvent = ""

    private val eventHistory = mutableStateListOf<String>()
    private val rawHistory = mutableStateListOf<String>()

    private val sessionConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (service !is T4ASessionService.SessionBinder) {
                sessionBindingRequested = false
                return
            }
            session = service as T4ASession
            session.addListener(this@MainActivity)
            session.setUiForeground(uiForeground)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            session = T4ASession.EMPTY
            sessionBindingRequested = false
        }
    }

    private val dashboardActions = object : T4ADashboardActions {
        override fun setRidingMode(mode: RidingMode) {
            val value = when (mode) {
                RidingMode.WALK -> "level_0"
                RidingMode.ECO -> "level_1"
                RidingMode.RACE -> "level_2"
                RidingMode.SPORT -> "level_3"
                RidingMode.UNKNOWN -> return
            }
            session.publish(T4ADashboardMapper.DP_LEVEL, value)
        }

        override fun setLight(enabled: Boolean) =
            session.publish(T4ADashboardMapper.DP_LIGHT, enabled)

        override fun setInitialPush(enabled: Boolean) =
            session.publish(T4ADashboardMapper.DP_START, if (enabled) "not_zero_start" else "zero_start")

        override fun setCruise(enabled: Boolean) =
            session.publish(T4ADashboardMapper.DP_CRUISE, enabled)

        override fun setLocked(locked: Boolean) =
            session.publish(T4ADashboardMapper.DP_LOCK, !locked)

        override fun setAutoLock(enabled: Boolean) = session.setAutoLockEnabled(enabled)

        override fun toggleOdometer() {
            showTotalOdometer = !showTotalOdometer
            preferences().edit().putBoolean(PREF_TOTAL_ODOMETER, showTotalOdometer).apply()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showTotalOdometer = preferences().getBoolean(PREF_TOTAL_ODOMETER, true)
        themeMode = preferences().getString(PREF_THEME, "system") ?: "system"
        applyKeepScreenOn(preferences().getBoolean(PREF_KEEP_SCREEN_ON, true))
        ensurePermissions()

        setContent {
            configurationTick
            val darkMode = isDarkMode()
            LaunchedEffect(darkMode) { configureSystemBars(darkMode) }
            T4AApp(darkMode)
        }
    }

    override fun onResume() {
        super.onResume()
        uiForeground = true
        if (hasBluetoothPermissions()) connectSession()
        session.setUiForeground(true)
        showBatteryRechargePromptIfNeeded(state)
    }

    override fun onPause() {
        uiForeground = false
        batteryRechargeDialog?.dismiss()
        batteryRechargeDialog = null
        session.setUiForeground(false)
        super.onPause()
    }

    override fun onDestroy() {
        disconnectSession()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configurationTick++
    }

    @Composable
    private fun T4AApp(darkMode: Boolean) {
        val background = if (darkMode) ComposeColor(0xFF0D1117) else ComposeColor(0xFFF7F8FB)
        val foreground = if (darkMode) ComposeColor(0xFFF1F5F9) else ComposeColor(0xFF101820)
        val current = state

        MaterialTheme {
            Column(
                Modifier.fillMaxSize()
                    .background(background)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Header(foreground)

                if (current == null) {
                    Text("Inicializando sessão T4A…", color = foreground)
                    return@Column
                }

                if (!current.authenticated) {
                    LoginPanel(foreground)
                    return@Column
                }

                if (current.pairing != T4AState.Pairing.PAIRED) {
                    Text(current.message, color = foreground)
                    PairingPanel(current, foreground)
                    EventLog(settings = false, foreground = foreground)
                    return@Column
                }

                if (settings) {
                    SettingsPanel(current, foreground)
                    EventLog(settings = true, foreground = foreground)
                } else {
                    val dashboardState = T4ADashboardMapper.map(current, showTotalOdometer)
                    T4ADashboard(
                        state = dashboardState,
                        actions = dashboardActions,
                        darkMode = darkMode,
                    )
                    EventLog(settings = false, foreground = foreground)
                }
            }
        }
    }

    @Composable
    private fun Header(foreground: ComposeColor) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = getString(R.string.app_name).uppercase(Locale.ROOT),
                color = foreground,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { settings = !settings }) {
                Text(if (settings) "Voltar" else "Configurações")
            }
        }
    }

    @Composable
    private fun LoginPanel(foreground: ComposeColor) {
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }

        Text("Autenticação", color = foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        TextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("E-mail") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        TextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Senha") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        Button(
            onClick = {
                session.login(email.trim(), password)
                password = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Entrar") }
    }

    @Composable
    private fun PairingPanel(current: T4AState, foreground: ComposeColor) {
        val busy = current.pairing == T4AState.Pairing.SCANNING || current.pairing == T4AState.Pairing.PAIRING
        Text("Pareamento: ${current.pairing}", color = foreground)
        when {
            current.pairing == T4AState.Pairing.READY ->
                Button(onClick = { session.pair() }, enabled = !busy) { Text("Parear T4A") }
            !busy -> Button(onClick = { session.scan() }) { Text("Localizar T4A") }
        }
    }

    @Composable
    private fun SettingsPanel(current: T4AState, foreground: ComposeColor) {
        Text("Configurações", color = foreground, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        SettingsSection("Conexão e dispositivo", foreground) {
            InfoLine("Conexão", if (current.connected) "Conectado" else "Desconectado", foreground)
            InfoLine("Dispositivo", current.deviceName.ifBlank { "--" }, foreground)
            InfoLine("MAC", current.mac.ifBlank { "--" }, foreground)
            InfoLine("RSSI", if (current.rssi == 0) "--" else "${current.rssi} dBm", foreground)
        }

        SettingsSection("Exibição", foreground) {
            val keepScreenOn = preferences().getBoolean(PREF_KEEP_SCREEN_ON, true)
            var keepOn by remember { mutableStateOf(keepScreenOn) }
            SettingSwitch("Manter tela ligada", keepOn, foreground) {
                keepOn = it
                preferences().edit().putBoolean(PREF_KEEP_SCREEN_ON, it).apply()
                applyKeepScreenOn(it)
            }
            Text("Tema", color = foreground, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("system" to "Sistema", "light" to "Claro", "dark" to "Escuro").forEach { (value, label) ->
                    OutlinedButton(onClick = { applyTheme(value) }) { Text(label) }
                }
            }
        }

        SettingsSection("Bateria", foreground) {
            val currentIndex = batteryRechargeGapIndex(current.batteryRechargeMinGapHours)
            var sliderIndex by remember(current.batteryRechargeMinGapHours) { mutableStateOf(currentIndex.toFloat()) }
            Text("Intervalo mínimo para detectar nova recarga: ${BATTERY_RECHARGE_GAP_HOURS[sliderIndex.toInt().coerceIn(0, 3)]} h", color = foreground)
            Slider(
                value = sliderIndex,
                onValueChange = { sliderIndex = it },
                onValueChangeFinished = {
                    session.setBatteryRechargeMinGapHours(BATTERY_RECHARGE_GAP_HOURS[sliderIndex.toInt().coerceIn(0, 3)])
                },
                valueRange = 0f..3f,
                steps = 2,
            )
            InfoLine("Mínimo observado", current.batteryObservedMin?.let { "$it%" } ?: "--", foreground)
            InfoLine("Máximo observado", current.batteryObservedMax?.let { "$it%" } ?: "--", foreground)
        }

        SettingsSection("Bloqueio automático", foreground) {
            SettingSwitch("Ativo", current.autoLockEnabled, foreground) { session.setAutoLockEnabled(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("short" to "Curta", "medium" to "Média", "long" to "Longa").forEach { (value, label) ->
                    OutlinedButton(onClick = { session.setAutoLockDistance(value) }) { Text(label) }
                }
            }
        }

        SettingsSection("Preferências de pilotagem", foreground) {
            Text("Unidade", color = foreground, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { session.publish(T4ADashboardMapper.DP_UNIT, "mile") }) { Text("Milhas") }
                OutlinedButton(onClick = { session.publish(T4ADashboardMapper.DP_UNIT, "km") }) { Text("Quilômetros") }
            }
            Text("Cruzeiro", color = foreground, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { session.publish(T4ADashboardMapper.DP_CRUISE, false) }) { Text("Desativar") }
                OutlinedButton(onClick = { session.publish(T4ADashboardMapper.DP_CRUISE, true) }) { Text("Ativar") }
            }
            Text("Partida", color = foreground, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { session.publish(T4ADashboardMapper.DP_START, "zero_start") }) { Text("Zero") }
                OutlinedButton(onClick = { session.publish(T4ADashboardMapper.DP_START, "not_zero_start") }) { Text("Impulso") }
            }
        }

        SettingsSection("Integrações", foreground) {
            Button(onClick = { startActivity(Intent(this@MainActivity, MqttSettingsActivity::class.java)) }) {
                Text("Configurar MQTT")
            }
        }

        SettingsSection("Diagnóstico", foreground) {
            current.dps.toSortedMap().forEach { (id, value) ->
                val name = current.schema[id]?.code ?: "DP $id"
                Text("$name ($id): $value", color = foreground, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        SettingsSection("Pareamento", foreground) {
            Button(onClick = { confirmUnpair() }) { Text("Remover pareamento") }
        }
    }

    @Composable
    private fun SettingsSection(title: String, foreground: ComposeColor, content: @Composable () -> Unit) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, color = ComposeColor(0xFF075EF0), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            content()
        }
    }

    @Composable
    private fun SettingSwitch(label: String, checked: Boolean, foreground: ComposeColor, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = foreground, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun InfoLine(label: String, value: String, foreground: ComposeColor) {
        Row(Modifier.fillMaxWidth()) {
            Text("$label:", color = foreground, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.45f))
            Text(value, color = foreground, modifier = Modifier.weight(0.55f))
        }
    }

    @Composable
    private fun EventLog(settings: Boolean, foreground: ComposeColor) {
        val source = if (settings) rawHistory else eventHistory
        val entries = if (settings) source else source.takeLast(10)
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(if (settings) "Log raw" else "Eventos", color = ComposeColor(0xFF075EF0), fontWeight = FontWeight.Bold)
            entries.asReversed().forEach { entry ->
                Text(
                    entry,
                    color = foreground,
                    fontFamily = if (settings) FontFamily.Monospace else FontFamily.Default,
                    fontSize = if (settings) 10.sp else 12.sp,
                )
            }
            if (settings) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copyRawLog() }) { Text("Copiar") }
                    OutlinedButton(onClick = { saveRawLog() }) { Text("Salvar") }
                }
            }
        }
    }

    override fun onState(value: T4AState) {
        runOnUiThread {
            state = value
            if (value.message.isNotEmpty() && value.message != lastStatusEvent) {
                lastStatusEvent = value.message
                appendEvent(value.message)
            }
            showBatteryRechargePromptIfNeeded(value)
        }
    }

    override fun onEvent(value: String) {
        runOnUiThread { appendEvent(value) }
    }

    override fun onRawLog(value: String) {
        runOnUiThread { appendRaw(value) }
    }

    private fun appendEvent(value: String) {
        eventHistory.add(DateFormat.getTimeInstance().format(Date()) + "  " + value)
    }

    private fun appendRaw(value: String) {
        rawHistory.add(SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date()) + " " + value)
    }

    private fun rawLogText(): String = buildString {
        append("T4A ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})\n")
        rawHistory.forEach { append(it).append('\n') }
    }

    private fun copyRawLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.raw_log), rawLogText()))
        Toast.makeText(this, R.string.raw_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun saveRawLog() {
        val fileName = "t4a-raw-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())}.log"
        var uri: android.net.Uri? = null
        try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS + "/T4A")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("MediaStore insert returned null")
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                output.write(rawLogText().toByteArray(Charsets.UTF_8))
            } ?: throw java.io.IOException("MediaStore output stream unavailable")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            Toast.makeText(this, "Log salvo em Downloads/T4A: $fileName", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            uri?.let {
                runCatching { contentResolver.delete(it, null, null) }
            }
            Toast.makeText(this, "Não foi possível salvar o log raw.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showBatteryRechargePromptIfNeeded(current: T4AState?) {
        if (!uiForeground || current?.pendingBatteryRechargePercent == null || batteryRechargeDialog != null) return
        val percent = current.pendingBatteryRechargePercent
        batteryRechargeDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Recarga detectada")
            .setMessage("A bateria subiu para $percent%. Deseja iniciar um novo ciclo de bateria?")
            .setPositiveButton("Novo ciclo") { _, _ ->
                session.resolveBatteryRecharge(true)
                batteryRechargeDialog = null
            }
            .setNegativeButton("Manter ciclo") { _, _ ->
                session.resolveBatteryRecharge(false)
                batteryRechargeDialog = null
            }
            .setOnCancelListener { batteryRechargeDialog = null }
            .create()
        batteryRechargeDialog?.show()
    }

    private fun confirmUnpair() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Remover pareamento")
            .setMessage("Deseja remover o pareamento atual do T4A?")
            .setPositiveButton("Remover") { _, _ -> session.unpair() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun batteryRechargeGapIndex(hours: Int): Int =
        BATTERY_RECHARGE_GAP_HOURS.indexOf(hours).takeIf { it >= 0 } ?: 1

    private fun applyTheme(value: String) {
        themeMode = value
        preferences().edit().putString(PREF_THEME, value).apply()
        configurationTick++
    }

    private fun isDarkMode(): Boolean = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    private fun configureSystemBars(darkMode: Boolean) {
        window.statusBarColor = if (darkMode) 0xFF0D1117.toInt() else 0xFFF7F8FB.toInt()
        window.navigationBarColor = if (darkMode) 0xFF0D1117.toInt() else 0xFFF7F8FB.toInt()
        window.decorView.windowInsetsController?.let { controller ->
            val appearance = if (darkMode) 0 else WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            controller.setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
    }

    private fun preferences() = getSharedPreferences("t4a_settings", MODE_PRIVATE)

    private fun applyKeepScreenOn(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun ensurePermissions() {
        if (!hasBluetoothPermissions()) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
                REQUEST_BLUETOOTH,
            )
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        if (hasBluetoothPermissions()) {
            if (uiForeground) connectSession()
        } else {
            Toast.makeText(
                this,
                "Permissões Bluetooth são necessárias para manter a sessão T4A.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun connectSession() {
        if (sessionBindingRequested || !hasBluetoothPermissions()) return
        val intent = Intent(this, T4ASessionService::class.java)
        try {
            startForegroundService(intent)
            sessionBindingRequested = bindService(intent, sessionConnection, BIND_AUTO_CREATE)
        } catch (error: RuntimeException) {
            sessionBindingRequested = false
            Toast.makeText(this, "Não foi possível iniciar a sessão T4A.", Toast.LENGTH_LONG).show()
        }
    }

    private fun disconnectSession() {
        session.removeListener(this)
        session.setUiForeground(false)
        session = T4ASession.EMPTY
        if (!sessionBindingRequested) return
        runCatching { unbindService(sessionConnection) }
        sessionBindingRequested = false
    }
}
