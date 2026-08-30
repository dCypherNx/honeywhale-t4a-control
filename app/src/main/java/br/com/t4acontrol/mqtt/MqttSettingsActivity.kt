package br.com.t4acontrol.mqtt

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import br.com.t4acontrol.R
import br.com.t4acontrol.T4AApplication
import com.mikepenz.iconics.IconicsDrawable

/** MQTT configuration UI. Depends only on [MqttSettings], never on persistence/backend types. */
class MqttSettingsActivity : ComponentActivity() {
    companion object {
        private const val PREF_THEME = "theme_mode"
        private val TRANSPORT_VALUES = listOf("TCP", "TLS", "WS", "WSS")
        private val BLUE = Color(0xFF075EF0)
        private val GREEN = Color(0xFF00A529)
    }

    private lateinit var mqttSettings: MqttSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mqttSettings = (application as T4AApplication).mqttSettings()
        val snapshot = mqttSettings.load()
        setContent {
            val darkMode = isDarkMode()
            LaunchedEffect(darkMode) { configureSystemBars(darkMode) }
            MaterialTheme {
                BackHandler { finish() }
                MqttSettingsScreen(snapshot, darkMode)
            }
        }
    }

    @Composable
    private fun MqttSettingsScreen(initial: MqttSettings.Snapshot, darkMode: Boolean) {
        val background = if (darkMode) Color(0xFF0D1117) else Color(0xFFF7F8FB)
        val foreground = if (darkMode) Color(0xFFF1F5F9) else Color(0xFF101820)
        val muted = if (darkMode) Color(0xFFAAB4C3) else Color(0xFF667085)

        var enabled by remember { mutableStateOf(initial.enabled) }
        var host by remember { mutableStateOf(initial.host) }
        var port by remember { mutableStateOf(initial.port) }
        var transport by remember { mutableStateOf(normalizeTransport(initial.transport)) }
        var websocketPath by remember { mutableStateOf(initial.websocketPath) }
        var username by remember { mutableStateOf(initial.username) }
        var password by remember { mutableStateOf(initial.password) }
        var passwordVisible by remember { mutableStateOf(false) }
        var clientId by remember { mutableStateOf(initial.clientId) }
        var baseTopic by remember { mutableStateOf(initial.baseTopic) }
        var keepAlive by remember { mutableStateOf(initial.keepAliveSeconds) }
        var discoveryEnabled by remember { mutableStateOf(initial.discoveryEnabled) }
        var statusText by remember {
            mutableStateOf(getString(if (initial.enabled) R.string.mqtt_status_enabled else R.string.mqtt_status_disabled))
        }
        var statusActive by remember { mutableStateOf(initial.enabled) }

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
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(52.dp)
                            .background(surfaceColor(darkMode), RoundedCornerShape(99.dp))
                            .border(1.dp, BLUE, RoundedCornerShape(99.dp))
                            .clickable { finish() },
                        contentAlignment = Alignment.Center,
                    ) {
                        MdiIcon("cmd-arrow-left", BLUE, 25.dp, Modifier.size(32.dp))
                    }
                    Text(
                        getString(R.string.mqtt_title),
                        color = foreground,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 14.dp),
                    )
                }

                Text(getString(R.string.mqtt_description), color = muted, fontSize = 13.sp)

                SettingsCard(darkMode) {
                    SettingSwitch(getString(R.string.mqtt_enabled), enabled, foreground) {
                        enabled = it
                        statusActive = it
                        statusText = getString(if (it) R.string.mqtt_status_enabled else R.string.mqtt_status_disabled)
                    }
                    Text(statusText, color = if (statusActive) GREEN else muted, fontSize = 12.sp)
                }

                CollapsibleCard(getString(R.string.mqtt_broker_section), "broker", darkMode) {
                    Text(getString(R.string.mqtt_transport), color = foreground, fontWeight = FontWeight.Bold)
                    SegmentedChoice(
                        labels = TRANSPORT_VALUES,
                        selectedIndex = TRANSPORT_VALUES.indexOf(transport).coerceAtLeast(0),
                        darkMode = darkMode,
                    ) { index ->
                        val next = TRANSPORT_VALUES[index]
                        if (port.isBlank() || port == defaultPort(transport).toString()) {
                            port = defaultPort(next).toString()
                        }
                        transport = next
                    }
                    Field(
                        value = host,
                        onValueChange = { host = it },
                        label = getString(R.string.mqtt_host),
                        hint = getString(R.string.mqtt_host_hint),
                    )
                    Field(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit) },
                        label = getString(R.string.mqtt_port),
                        hint = getString(R.string.mqtt_port_hint),
                        keyboardType = KeyboardType.Number,
                    )
                    if (transport == "WS" || transport == "WSS") {
                        Field(
                            value = websocketPath,
                            onValueChange = { websocketPath = it },
                            label = getString(R.string.mqtt_websocket_path),
                            hint = getString(R.string.mqtt_websocket_path_hint),
                        )
                        Text(getString(R.string.mqtt_websocket_path_note), color = muted, fontSize = 11.sp)
                    }
                    Field(
                        value = keepAlive,
                        onValueChange = { keepAlive = it.filter(Char::isDigit) },
                        label = getString(R.string.mqtt_keep_alive),
                        hint = getString(R.string.mqtt_keep_alive_hint),
                        keyboardType = KeyboardType.Number,
                    )
                }

                CollapsibleCard(getString(R.string.mqtt_credentials_section), "credentials", darkMode) {
                    Field(
                        value = username,
                        onValueChange = { username = it },
                        label = getString(R.string.mqtt_username),
                        hint = getString(R.string.mqtt_username_hint),
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(getString(R.string.mqtt_password)) },
                        placeholder = { Text(getString(R.string.mqtt_password_hint)) },
                        singleLine = true,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                Text(getString(if (passwordVisible) R.string.hide else R.string.show))
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(getString(R.string.mqtt_password_secure), color = muted, fontSize = 11.sp)
                }

                CollapsibleCard(getString(R.string.mqtt_identity_section), "identity", darkMode) {
                    Field(
                        value = clientId,
                        onValueChange = { clientId = it },
                        label = getString(R.string.mqtt_client_id),
                        hint = getString(R.string.mqtt_client_id_hint),
                    )
                    Field(
                        value = baseTopic,
                        onValueChange = { baseTopic = it },
                        label = getString(R.string.mqtt_base_topic),
                        hint = getString(R.string.mqtt_base_topic_hint),
                    )
                    Text(getString(R.string.mqtt_topic_note), color = muted, fontSize = 11.sp)
                    SettingSwitch(getString(R.string.mqtt_discovery_enabled), discoveryEnabled, foreground) {
                        discoveryEnabled = it
                    }
                    Text(getString(R.string.mqtt_discovery_note), color = muted, fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        val result = mqttSettings.save(
                            MqttSettings.Draft(
                                enabled,
                                host.trim(),
                                port.trim(),
                                transport,
                                websocketPath.trim(),
                                username.trim(),
                                password,
                                clientId.trim(),
                                baseTopic.trim(),
                                keepAlive.trim(),
                                discoveryEnabled,
                            )
                        )
                        if (!result.saved) {
                            showError(result.error)
                        } else {
                            val saved = result.snapshot
                            enabled = saved.enabled
                            host = saved.host
                            port = saved.port
                            transport = normalizeTransport(saved.transport)
                            websocketPath = saved.websocketPath
                            username = saved.username
                            password = saved.password
                            clientId = saved.clientId
                            baseTopic = saved.baseTopic
                            keepAlive = saved.keepAliveSeconds
                            discoveryEnabled = saved.discoveryEnabled
                            statusText = getString(R.string.mqtt_status_saved)
                            statusActive = saved.enabled
                            Toast.makeText(this@MqttSettingsActivity, R.string.mqtt_saved, Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(getString(R.string.mqtt_save), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun Field(
        value: String,
        onValueChange: (String) -> Unit,
        label: String,
        hint: String,
        keyboardType: KeyboardType = KeyboardType.Text,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = { Text(hint) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun SettingSwitch(label: String, checked: Boolean, foreground: Color, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = foreground, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun SegmentedChoice(
        labels: List<String>,
        selectedIndex: Int,
        darkMode: Boolean,
        onSelected: (Int) -> Unit,
    ) {
        val safeSelected = selectedIndex.coerceIn(0, labels.lastIndex)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    selected = index == safeSelected,
                    onClick = { onSelected(index) },
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = BLUE,
                        activeContentColor = Color.White,
                        activeBorderColor = BLUE,
                        inactiveContainerColor = surfaceColor(darkMode),
                        inactiveContentColor = foregroundColor(darkMode),
                        inactiveBorderColor = outlineColor(darkMode),
                    ),
                    icon = {},
                    label = { Text(label, fontWeight = if (index == safeSelected) FontWeight.Bold else FontWeight.Medium) },
                )
            }
        }
    }

    @Composable
    private fun CollapsibleCard(title: String, key: String, darkMode: Boolean, content: @Composable () -> Unit) {
        var expanded by remember(key) {
            mutableStateOf(getSharedPreferences("t4a_settings", MODE_PRIVATE).getBoolean("mqtt_section_$key", true))
        }
        val surface = surfaceColor(darkMode)
        val outline = outlineColor(darkMode)
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = surface),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .background(headerColor(darkMode), RoundedCornerShape(10.dp))
                        .border(1.dp, outline, RoundedCornerShape(10.dp))
                        .clickable {
                            expanded = !expanded
                            getSharedPreferences("t4a_settings", MODE_PRIVATE).edit().putBoolean("mqtt_section_$key", expanded).apply()
                        }
                        .padding(start = 7.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        color = BLUE,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(vertical = 9.dp),
                    )
                    MdiIcon(if (expanded) "cmd-chevron-up" else "cmd-chevron-down", BLUE, 18.dp, Modifier.size(30.dp))
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { content() }
                }
            }
        }
    }

    @Composable
    private fun SettingsCard(darkMode: Boolean, content: @Composable () -> Unit) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = surfaceColor(darkMode)),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }

    @Composable
    private fun MdiIcon(name: String, color: Color, iconSize: Dp, modifier: Modifier = Modifier) {
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

    private fun showError(error: MqttSettings.Error) {
        val message = when (error) {
            MqttSettings.Error.HOST_REQUIRED -> R.string.mqtt_error_host
            MqttSettings.Error.PORT_INVALID -> R.string.mqtt_error_port
            MqttSettings.Error.TRANSPORT_INVALID -> R.string.mqtt_error_transport
            MqttSettings.Error.WEBSOCKET_PATH_INVALID -> R.string.mqtt_error_websocket_path
            MqttSettings.Error.CLIENT_ID_REQUIRED -> R.string.mqtt_error_client_id
            MqttSettings.Error.BASE_TOPIC_REQUIRED -> R.string.mqtt_error_base_topic
            MqttSettings.Error.KEEP_ALIVE_INVALID -> R.string.mqtt_error_keep_alive
            else -> R.string.mqtt_error_storage
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun normalizeTransport(value: String?): String =
        TRANSPORT_VALUES.firstOrNull { it.equals(value?.trim(), ignoreCase = true) } ?: "TCP"

    private fun defaultPort(transport: String): Int = when (normalizeTransport(transport)) {
        "TLS" -> 8883
        "WS" -> 80
        "WSS" -> 443
        else -> 1883
    }

    private fun isDarkMode(): Boolean = when (getSharedPreferences("t4a_settings", MODE_PRIVATE).getString(PREF_THEME, "system")) {
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

    private fun surfaceColor(darkMode: Boolean) = if (darkMode) Color(0xFF171D26) else Color.White
    private fun headerColor(darkMode: Boolean) = if (darkMode) Color(0xFF202836) else Color(0xFFF8FAFD)
    private fun outlineColor(darkMode: Boolean) = if (darkMode) Color(0xFF354052) else Color(0xFFE4E8F0)
    private fun foregroundColor(darkMode: Boolean) = if (darkMode) Color(0xFFF1F5F9) else Color(0xFF101820)
}
