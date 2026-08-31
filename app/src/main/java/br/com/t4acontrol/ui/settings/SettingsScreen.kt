package br.com.t4acontrol.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.t4acontrol.R
import br.com.t4acontrol.backend.T4AState
import br.com.t4acontrol.ui.MdiIcon
import br.com.t4acontrol.ui.T4AUiActions
import br.com.t4acontrol.ui.T4AUiTokens
import br.com.t4acontrol.ui.dashboard.T4ADashboardMapper
import br.com.t4acontrol.ui.pairing.pairingLabel

private val BATTERY_RECHARGE_GAP_HOURS = intArrayOf(1, 2, 4, 8)

@Composable
internal fun SettingsScreen(
    current: T4AState,
    foreground: Color,
    keepScreenOn: Boolean,
    themeMode: String,
    actions: T4AUiActions,
) {
    SettingsSection(stringResource(R.string.connection_device_section), "connection", actions = actions) {
        InfoLine(stringResource(R.string.connection_label), stringResource(if (current.connected) R.string.connected else R.string.disconnected), foreground)
        InfoLine(stringResource(R.string.device_label), current.deviceName.ifBlank { "--" }, foreground)
        InfoLine(stringResource(R.string.mac_label), current.mac.ifBlank { "--" }, foreground)
        InfoLine(stringResource(R.string.rssi_label), if (current.rssi == 0) "--" else stringResource(R.string.dbm, current.rssi), foreground)
    }

    SettingsSection(stringResource(R.string.display_section), "display", actions = actions) {
        var keepOn by remember { mutableStateOf(keepScreenOn) }
        SettingSwitch(stringResource(R.string.keep_screen_on), keepOn, foreground) {
            keepOn = it
            actions.setKeepScreenOn(it)
        }
        Text(stringResource(R.string.theme_mode), color = foreground, fontWeight = FontWeight.Bold)
        val themeValues = listOf("system", "light", "dark")
        SegmentedChoice(
            labels = listOf(stringResource(R.string.theme_system), stringResource(R.string.theme_light), stringResource(R.string.theme_dark)),
            selectedIndex = themeValues.indexOf(themeMode).coerceAtLeast(0),
        ) { index -> actions.setTheme(themeValues[index]) }
    }

    SettingsSection(stringResource(R.string.battery_section), "battery", actions = actions) {
        Text(stringResource(R.string.battery_recharge_detection), color = foreground, fontWeight = FontWeight.Bold)
        SegmentedChoice(
            labels = BATTERY_RECHARGE_GAP_HOURS.map { stringResource(R.string.battery_recharge_gap_value, it) },
            selectedIndex = batteryRechargeGapIndex(current.batteryRechargeMinGapHours),
        ) { index -> actions.setBatteryRechargeMinGapHours(BATTERY_RECHARGE_GAP_HOURS[index]) }
        Text(stringResource(R.string.battery_recharge_detection_note), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }

    SettingsSection(stringResource(R.string.integrations_section), "integrations", actions = actions) {
        Button(onClick = actions::openMqttSettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.configure_mqtt))
        }
    }

    SettingsSection(stringResource(R.string.automatic_lock), "auto_lock", actions = actions) {
        SettingSwitch(stringResource(R.string.state_active), current.autoLockEnabled, foreground, actions::setAutoLockFromSettings)
        val distanceValues = listOf("short", "medium", "long")
        val selectedDistanceIndex = distanceValues.indexOf(current.autoLockDistance).takeIf { it >= 0 } ?: 1
        val dynamicLabels = if (current.rssiCalibrationReady) {
            listOf(
                stringResource(R.string.distance_dynamic, stringResource(R.string.distance_short_name), current.rssiObservedBest, current.rssiShortMin),
                stringResource(R.string.distance_dynamic, stringResource(R.string.distance_medium_name), current.rssiShortMin!! - 1, current.rssiMediumMin),
                stringResource(R.string.distance_dynamic, stringResource(R.string.distance_long_name), current.rssiMediumMin!! - 1, current.rssiLongMin),
            )
        } else {
            listOf(
                stringResource(R.string.distance_short_name),
                stringResource(R.string.distance_medium_name),
                stringResource(R.string.distance_long_name),
            )
        }
        Text(
            if (current.rssiCalibrationReady) {
                stringResource(R.string.auto_lock_calibrated_range, current.rssiObservedBest, current.rssiObservedWorst, current.rssiStableWorst)
            } else {
                stringResource(R.string.auto_lock_calibrating)
            },
            color = foreground,
        )
        SegmentedChoice(
            labels = dynamicLabels,
            selectedIndex = selectedDistanceIndex,
        ) { index -> actions.setAutoLockDistanceFromSettings(distanceValues[index]) }
    }

    SettingsSection(stringResource(R.string.riding_preferences_section), "riding", actions = actions) {
        Text(stringResource(R.string.unit), color = foreground, fontWeight = FontWeight.Bold)
        val unitValues = listOf("km", "mile")
        val unitValue = current.dps[T4ADashboardMapper.DP_UNIT]?.toString() ?: "km"
        SegmentedChoice(
            labels = listOf(stringResource(R.string.kilometers), stringResource(R.string.miles)),
            selectedIndex = unitValues.indexOf(unitValue).coerceAtLeast(0),
        ) { index -> actions.setUnit(unitValues[index]) }

        Text(stringResource(R.string.cruise), color = foreground, fontWeight = FontWeight.Bold)
        SegmentedChoice(
            labels = listOf(stringResource(R.string.disable), stringResource(R.string.enable)),
            selectedIndex = if (dpBoolean(current.dps[T4ADashboardMapper.DP_CRUISE])) 1 else 0,
        ) { index -> actions.setCruise(index == 1) }

        Text(stringResource(R.string.start_mode), color = foreground, fontWeight = FontWeight.Bold)
        val initialPush = current.dps[T4ADashboardMapper.DP_START]?.toString() == "not_zero_start"
        SegmentedChoice(
            labels = listOf(stringResource(R.string.zero_start), stringResource(R.string.initial_push)),
            selectedIndex = if (initialPush) 1 else 0,
        ) { index -> actions.setInitialPush(index == 1) }
    }

    SettingsSection(stringResource(R.string.diagnostics_section), "diagnostics", defaultExpanded = false, actions = actions) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            current.dps.toSortedMap().forEach { (id, value) ->
                val name = current.schema[id]?.code ?: stringResource(R.string.dp_fallback, id)
                Text("$name ($id): $value", color = foreground, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }

    SettingsSection(stringResource(R.string.tuya_pairing_section), "tuya_pairing", defaultExpanded = false, actions = actions) {
        InfoLine(stringResource(R.string.tuya_account_label), current.account.ifBlank { "--" }, foreground)
        InfoLine(stringResource(R.string.tuya_home_label), current.homeName.ifBlank { "--" }, foreground)
        InfoLine(stringResource(R.string.tuya_home_id_label), if (current.homeId == 0L) "--" else current.homeId.toString(), foreground)
        InfoLine(stringResource(R.string.pairing_label), pairingLabel(current.pairing), foreground)
        if (current.pairing == T4AState.Pairing.PAIRED) {
            Button(
                onClick = actions::confirmUnpair,
                colors = ButtonDefaults.buttonColors(containerColor = T4AUiTokens.Red, contentColor = Color.White),
            ) {
                Text(stringResource(R.string.remove_pairing))
            }
        }
    }
}

@Composable
private fun SegmentedChoice(labels: List<String>, selectedIndex: Int, onSelected: (Int) -> Unit) {
    val safeSelected = selectedIndex.coerceIn(0, labels.lastIndex)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        labels.forEachIndexed { index, label ->
            SegmentedButton(
                selected = safeSelected == index,
                onClick = { onSelected(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = labels.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = T4AUiTokens.Blue,
                    activeContentColor = Color.White,
                    activeBorderColor = T4AUiTokens.Blue,
                    inactiveContainerColor = MaterialTheme.colorScheme.surface,
                    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                    inactiveBorderColor = MaterialTheme.colorScheme.outline,
                ),
                icon = {},
                label = { Text(label, fontWeight = if (safeSelected == index) FontWeight.Bold else FontWeight.Medium) },
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    key: String,
    defaultExpanded: Boolean = true,
    actions: T4AUiActions,
    content: @Composable () -> Unit,
) {
    var expanded by remember(key) { mutableStateOf(actions.sectionExpanded(key, defaultExpanded)) }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                        actions.setSectionExpanded(key, expanded)
                    }
                    .padding(horizontal = 4.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, color = T4AUiTokens.Blue, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                MdiIcon(if (expanded) "cmd-chevron-up" else "cmd-chevron-down", T4AUiTokens.Blue, 18.dp, Modifier.size(28.dp))
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, foreground: Color, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = foreground, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoLine(label: String, value: String, foreground: Color) {
    Row(Modifier.fillMaxWidth()) {
        Text("$label:", color = foreground, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.42f))
        Text(value, color = foreground, modifier = Modifier.weight(0.58f))
    }
}

private fun dpBoolean(value: Any?): Boolean =
    value == true || value?.toString()?.equals("true", ignoreCase = true) == true || value?.toString() == "1"

private fun batteryRechargeGapIndex(hours: Int): Int = BATTERY_RECHARGE_GAP_HOURS.indexOf(hours).takeIf { it >= 0 } ?: 1
