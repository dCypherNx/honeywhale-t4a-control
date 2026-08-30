package br.com.t4acontrol

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import br.com.t4acontrol.backend.T4AState
import br.com.t4acontrol.mqtt.MqttSettingsActivity
import br.com.t4acontrol.session.T4ASession
import br.com.t4acontrol.session.T4ASessionService
import br.com.t4acontrol.ui.dashboard.RidingMode
import br.com.t4acontrol.ui.dashboard.T4ADashboard
import br.com.t4acontrol.ui.dashboard.T4ADashboardActions
import br.com.t4acontrol.ui.dashboard.T4ADashboardMapper
import com.mikepenz.iconics.IconicsDrawable
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

        override fun setLight(enabled: Boolean) = session.publish(T4ADashboardMapper.DP_LIGHT, enabled)
        override fun setInitialPush(enabled: Boolean) = session.publish(T4ADashboardMapper.DP_START, if (enabled) "not_zero_start" else "zero_start")
        override fun setCruise(enabled: Boolean) = session.publish(T4ADashboardMapper.DP_CRUISE, enabled)
        override fun setLocked(locked: Boolean) = session.publish(T4ADashboardMapper.DP_LOCK, !locked)
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
            BackHandler(enabled = settings) { settings = false }
            Box(
                Modifier.fillMaxSize()
                    .background(background)
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Header(foreground)
                    if (current == null) {
                        Text(getString(R.string.session_initializing), color = foreground)
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
                        T4ADashboard(
                            state = T4ADashboardMapper.map(current, showTotalOdometer),
                            actions = dashboardActions,
                            darkMode = darkMode,
                        )
                        EventLog(settings = false, foreground = foreground)
                    }
                }
            }
        }
    }

    @Composable
    private fun Header(foreground: ComposeColor) {
        val surface = if (isDarkMode()) ComposeColor(0xFF171D26) else ComposeColor.White
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = getString(R.string.app_name).uppercase(Locale.ROOT),
                color = foreground,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier.size(52.dp)
                    .background(surface, RoundedCornerShape(99.dp))
                    .border(1.dp, ComposeColor(0xFF075EF0), RoundedCornerShape(99.dp))
                    .clickable { settings = !settings },
                contentAlignment = Alignment.Center,
            ) {
                MdiIcon(
                    name = if (settings) "cmd-arrow-left" else "cmd-cog",
                    color = ComposeColor(0xFF075EF0),
                    iconSize = 24.dp,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }

    @Composable
    private fun LoginPanel(foreground: ComposeColor) {
        var email by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        Text(getString(R.string.authentication), color = foreground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        TextField(value = email, onValueChange = { email = it }, label = { Text(getString(R.string.email_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        TextField(value = password, onValueChange = { password = it }, label = { Text(getString(R.string.password_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = PasswordVisualTransformation())
        Button(onClick = { session.login(email.trim(), password); password = "" }, modifier = Modifier.fillMaxWidth()) { Text(getString(R.string.login)) }
    }

    @Composable
    private fun PairingPanel(current: T4AState, foreground: ComposeColor) {
        val busy = current.pairing == T4AState.Pairing.SCANNING || current.pairing == T4AState.Pairing.PAIRING
        Text(getString(R.string.pairing_status, pairingLabel(current.pairing)), color = foreground)
        when {
            current.pairing == T4AState.Pairing.READY -> Button(onClick = { session.pair() }, enabled = !busy) { Text(getString(R.string.pair_t4a)) }
            !busy -> Button(onClick = { session.scan() }) { Text(getString(R.string.find_t4a)) }
        }
    }

    @Composable
    private fun SettingsPanel(current: T4AState, foreground: ComposeColor) {
        Text(getString(R.string.settings), color = foreground, fontSize = 24.sp, fontWeight = FontWeight.Bold)

        SettingsSection(getString(R.string.connection_device_section), foreground, "connection") {
            InfoLine(getString(R.string.connection_label), getString(if (current.connected) R.string.connected else R.string.disconnected), foreground)
            InfoLine(getString(R.string.device_label), current.deviceName.ifBlank { "--" }, foreground)
            InfoLine(getString(R.string.mac_label), current.mac.ifBlank { "--" }, foreground)
            InfoLine(getString(R.string.rssi_label), if (current.rssi == 0) "--" else getString(R.string.dbm, current.rssi), foreground)
        }

        SettingsSection(getString(R.string.display_section), foreground, "display") {
            val keepScreenOn = preferences().getBoolean(PREF_KEEP_SCREEN_ON, true)
            var keepOn by remember { mutableStateOf(keepScreenOn) }
            SettingSwitch(getString(R.string.keep_screen_on), keepOn, foreground) {
                keepOn = it
                preferences().edit().putBoolean(PREF_KEEP_SCREEN_ON, it).apply()
                applyKeepScreenOn(it)
            }
            Text(getString(R.string.theme_mode), color = foreground, fontWeight = FontWeight.Bold)
            SegmentedChoice(
                labels = listOf(getString(R.string.theme_system), getString(R.string.theme_light), getString(R.string.theme_dark)),
                selectedIndex = listOf("system", "light", "dark").indexOf(themeMode).coerceAtLeast(0),
            ) { index -> applyTheme(listOf("system", "light", "dark")[index]) }
        }

        SettingsSection(getString(R.string.battery_section), foreground, "battery") {
            val currentIndex = batteryRechargeGapIndex(current.batteryRechargeMinGapHours)
            Text(getString(R.string.battery_recharge_detection), color = foreground, fontWeight = FontWeight.Bold)
            SegmentedChoice(
                labels = BATTERY_RECHARGE_GAP_HOURS.map { getString(R.string.battery_recharge_gap_value, it) },
                selectedIndex = currentIndex,
            ) { index -> session.setBatteryRechargeMinGapHours(BATTERY_RECHARGE_GAP_HOURS[index]) }
            Text(
                getString(R.string.battery_recharge_detection_note),
                color = if (isDarkMode()) ComposeColor(0xFFAAB4C3) else ComposeColor(0xFF667085),
                fontSize = 11.sp,
            )
        }

        SettingsSection(getString(R.string.integrations_section), foreground, "integrations") {
            Button(onClick = { startActivity(Intent(this@MainActivity, MqttSettingsActivity::class.java)) }) { Text(getString(R.string.configure_mqtt)) }
        }

        SettingsSection(getString(R.string.automatic_lock), foreground, "auto_lock") {
            SettingSwitch(getString(R.string.state_active), current.autoLockEnabled, foreground) { session.setAutoLockEnabled(it) }
            Text(getString(R.string.auto_lock_distance), color = foreground)
            val distanceValues = listOf("short", "medium", "long")
            SegmentedChoice(
                labels = listOf(getString(R.string.distance_short), getString(R.string.distance_medium), getString(R.string.distance_long)),
                selectedIndex = distanceValues.indexOf(current.autoLockDistance).coerceAtLeast(1),
            ) { index -> session.setAutoLockDistance(distanceValues[index]) }
        }

        SettingsSection(getString(R.string.riding_preferences_section), foreground, "riding") {
            Text(getString(R.string.unit), color = foreground, fontWeight = FontWeight.Bold)
            val unitValues = listOf("km", "mile")
            val unitValue = current.dps[T4ADashboardMapper.DP_UNIT]?.toString() ?: "km"
            SegmentedChoice(
                labels = listOf(getString(R.string.kilometers), getString(R.string.miles)),
                selectedIndex = unitValues.indexOf(unitValue).coerceAtLeast(0),
            ) { index -> session.publish(T4ADashboardMapper.DP_UNIT, unitValues[index]) }

            Text(getString(R.string.cruise), color = foreground, fontWeight = FontWeight.Bold)
            SegmentedChoice(
                labels = listOf(getString(R.string.disable), getString(R.string.enable)),
                selectedIndex = if (dpBoolean(current.dps[T4ADashboardMapper.DP_CRUISE])) 1 else 0,
            ) { index -> session.publish(T4ADashboardMapper.DP_CRUISE, index == 1) }

            Text(getString(R.string.start_mode), color = foreground, fontWeight = FontWeight.Bold)
            val initialPush = current.dps[T4ADashboardMapper.DP_START]?.toString() == "not_zero_start"
            SegmentedChoice(
                labels = listOf(getString(R.string.zero_start), getString(R.string.initial_push)),
                selectedIndex = if (initialPush) 1 else 0,
            ) { index -> session.publish(T4ADashboardMapper.DP_START, if (index == 1) "not_zero_start" else "zero_start") }
        }

        SettingsSection(getString(R.string.diagnostics_section), foreground, "diagnostics") {
            current.dps.toSortedMap().forEach { (id, value) ->
                val name = current.schema[id]?.code ?: getString(R.string.dp_fallback, id)
                Text("$name ($id): $value", color = foreground, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }

        SettingsSection(getString(R.string.pairing_section), foreground, "pairing") {
            Button(onClick = { confirmUnpair() }) { Text(getString(R.string.remove_pairing)) }
        }
    }

    @Composable
    private fun SegmentedChoice(
        labels: List<String>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit,
    ) {
        val safeSelected = selectedIndex.coerceIn(0, labels.lastIndex)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = safeSelected == index,
                    onClick = { onSelected(index) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = ComposeColor(0xFF075EF0),
                        activeContentColor = ComposeColor.White,
                        activeBorderColor = ComposeColor(0xFF075EF0),
                        inactiveContainerColor = if (isDarkMode()) ComposeColor(0xFF171D26) else ComposeColor.White,
                        inactiveContentColor = if (isDarkMode()) ComposeColor(0xFFF1F5F9) else ComposeColor(0xFF101820),
                        inactiveBorderColor = if (isDarkMode()) ComposeColor(0xFF354052) else ComposeColor(0xFFE4E8F0),
                    ),
                    icon = {},
                    label = {
                        Text(
                            label,
                            fontWeight = if (safeSelected == index) FontWeight.Bold else FontWeight.Medium,
                        )
                    },
                )
            }
        }
    }

    @Composable
    private fun SettingsSection(
        title: String,
        foreground: ComposeColor,
        key: String,
        content: @Composable () -> Unit,
    ) {
        val surface = if (isDarkMode()) ComposeColor(0xFF171D26) else ComposeColor.White
        val header = if (isDarkMode()) ComposeColor(0xFF202836) else ComposeColor(0xFFF8FAFD)
        val outline = if (isDarkMode()) ComposeColor(0xFF354052) else ComposeColor(0xFFE4E8F0)
        var expanded by remember(key) { mutableStateOf(preferences().getBoolean("section_$key", true)) }
        Column(
            Modifier.fillMaxWidth()
                .shadow(2.dp, RoundedCornerShape(16.dp))
                .background(surface, RoundedCornerShape(16.dp))
                .border(1.dp, outline, RoundedCornerShape(16.dp))
                .padding(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(header, RoundedCornerShape(10.dp))
                    .clickable {
                        expanded = !expanded
                        preferences().edit().putBoolean("section_$key", expanded).apply()
                    }
                    .padding(start = 7.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, color = ComposeColor(0xFF075EF0), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f).padding(vertical = 9.dp))
                MdiIcon(
                    name = if (expanded) "cmd-chevron-up" else "cmd-chevron-down",
                    color = ComposeColor(0xFF075EF0),
                    iconSize = 18.dp,
                    modifier = Modifier.size(30.dp),
                )
            }
            if (expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { content() }
            }
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
        val surface = if (isDarkMode()) ComposeColor(0xFF171D26) else ComposeColor.White
        val outline = if (isDarkMode()) ComposeColor(0xFF354052) else ComposeColor(0xFFE4E8F0)
        Column(
            Modifier.fillMaxWidth()
                .shadow(2.dp, RoundedCornerShape(16.dp))
                .background(surface, RoundedCornerShape(16.dp))
                .border(1.dp, outline, RoundedCornerShape(16.dp))
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MdiIcon(name = "cmd-format-list-bulleted", color = ComposeColor(0xFF075EF0), iconSize = 20.dp, modifier = Modifier.size(24.dp))
                Spacer(Modifier.size(8.dp))
                Text(getString(if (settings) R.string.raw_log else R.string.events), color = ComposeColor(0xFF075EF0), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            if (settings) {
                val vertical = rememberScrollState()
                val horizontal = rememberScrollState()
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .height(220.dp)
                        .border(1.dp, outline, RoundedCornerShape(10.dp))
                        .background(if (isDarkMode()) ComposeColor(0xFF10151D) else ComposeColor(0xFFF8FAFD), RoundedCornerShape(10.dp))
                        .padding(8.dp),
                ) {
                    Column(
                        modifier = Modifier.horizontalScroll(horizontal).verticalScroll(vertical),
                    ) {
                        entries.asReversed().forEach { entry ->
                            Text(
                                entry,
                                color = foreground,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                softWrap = false,
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copyRawLog() }) { Text(getString(R.string.copy)) }
                    OutlinedButton(onClick = { saveRawLog() }) { Text(getString(R.string.save)) }
                }
            } else {
                entries.asReversed().forEach { entry ->
                    Text(entry, color = foreground, fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun MdiIcon(name: String, color: ComposeColor, iconSize: Dp, modifier: Modifier = Modifier) {
        val argb = color.toArgb()
        AndroidView(
            modifier = modifier,
            factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE } },
            update = { image ->
                val pixels = (iconSize.value * image.resources.displayMetrics.density).toInt()
                image.setImageDrawable(
                    IconicsDrawable(image.context, name).apply {
                        colorList = ColorStateList.valueOf(argb)
                        sizeXPx = pixels
                        sizeYPx = pixels
                    }
                )
            },
        )
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

    override fun onEvent(value: String) { runOnUiThread { appendEvent(value) } }
    override fun onRawLog(value: String) { runOnUiThread { appendRaw(value) } }
    private fun appendEvent(value: String) { eventHistory.add(DateFormat.getTimeInstance().format(Date()) + "  " + value) }
    private fun appendRaw(value: String) { rawHistory.add(SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date()) + " " + value) }

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
            contentResolver.openOutputStream(uri, "w")?.use { output -> output.write(rawLogText().toByteArray(Charsets.UTF_8)) }
                ?: throw java.io.IOException("MediaStore output stream unavailable")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            Toast.makeText(this, getString(R.string.raw_log_saved, fileName), Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            uri?.let { runCatching { contentResolver.delete(it, null, null) } }
            Toast.makeText(this, R.string.raw_log_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showBatteryRechargePromptIfNeeded(current: T4AState?) {
        if (!uiForeground || current?.pendingBatteryRechargePercent == null || batteryRechargeDialog != null) return
        val percent = current.pendingBatteryRechargePercent
        batteryRechargeDialog = android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.battery_recharge_possible_title))
            .setMessage(getString(R.string.battery_recharge_possible_message, percent))
            .setPositiveButton(getString(R.string.battery_recharge_confirm)) { _, _ -> session.resolveBatteryRecharge(true); batteryRechargeDialog = null }
            .setNegativeButton(getString(R.string.battery_recharge_reject)) { _, _ -> session.resolveBatteryRecharge(false); batteryRechargeDialog = null }
            .setOnCancelListener { batteryRechargeDialog = null }
            .create()
        batteryRechargeDialog?.show()
    }

    private fun confirmUnpair() {
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_pairing_question))
            .setMessage(getString(R.string.remove_pairing_message))
            .setPositiveButton(getString(R.string.remove)) { _, _ -> session.unpair() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun pairingLabel(pairing: T4AState.Pairing): String = getString(
        when (pairing) {
            T4AState.Pairing.PAIRED -> R.string.pairing_paired
            T4AState.Pairing.PAIRING -> R.string.pairing_in_progress
            T4AState.Pairing.READY -> R.string.pairing_ready
            T4AState.Pairing.SCANNING -> R.string.pairing_scanning
            T4AState.Pairing.REMOVING -> R.string.pairing_removing
            T4AState.Pairing.NO_HOME -> R.string.pairing_no_home
            T4AState.Pairing.UNPAIRED -> R.string.pairing_unpaired
        }
    )

    private fun dpBoolean(value: Any?): Boolean =
        value == true || value?.toString()?.equals("true", ignoreCase = true) == true || value?.toString() == "1"

    private fun batteryRechargeGapIndex(hours: Int): Int = BATTERY_RECHARGE_GAP_HOURS.indexOf(hours).takeIf { it >= 0 } ?: 1

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
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_BLUETOOTH,
            )
        }
    }

    private fun hasBluetoothPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH) return
        if (hasBluetoothPermissions()) {
            if (uiForeground) connectSession()
        } else {
            Toast.makeText(this, R.string.bluetooth_permissions_required, Toast.LENGTH_LONG).show()
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
            Toast.makeText(this, R.string.session_start_failed, Toast.LENGTH_LONG).show()
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
