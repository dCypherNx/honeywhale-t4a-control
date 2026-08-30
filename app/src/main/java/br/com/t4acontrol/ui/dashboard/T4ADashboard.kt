package br.com.t4acontrol.ui.dashboard

import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import br.com.t4acontrol.R
import com.mikepenz.iconics.IconicsDrawable

object T4ADashboardTokens {
    val CardSpacing: Dp = 10.dp
    val CardPadding: Dp = 10.dp
    val CardRadius: Dp = 16.dp
    val CardElevation: Dp = 2.dp
    val ModeRadius: Dp = 12.dp
    val ToggleRadius: Dp = 14.dp
    val ActionRadius: Dp = 12.dp
    val ModeHeight: Dp = 76.dp
    val ToggleHeight: Dp = 76.dp
    val ActionHeight: Dp = 82.dp
    val SpeedGaugeHeight: Dp = 154.dp
    val BatteryHeight: Dp = 16.dp
    val SpeedFontSize = 104.sp
    val SpeedUnitFontSize = 24.sp
    val SectionTitleFontSize = 19.sp
    val ModeFontSize = 12.sp
    val ModeSpeedFontSize = 11.sp
    val ControlFontSize = 10.sp
    val ActionFontSize = 11.sp
    val MetricFontSize = 11.sp
    val Navy = Color(0xFF00133D)
    val Blue = Color(0xFF075EF0)
    val Green = Color(0xFF00A529)
    val Red = Color(0xFFE91925)
    val Cyan = Color(0xFF08A6B7)
    val Orange = Color(0xFFFF8A00)
    val Amber = Color(0xFFFFB300)
    val DarkScaleBlue = Color(0xFF8AB4FF)
    val LightBackground = Color(0xFFF7F8FB)
    val DarkBackground = Color(0xFF0D1117)
    val LightSurface = Color.White
    val DarkSurface = Color(0xFF171D26)
    val LightOutline = Color(0xFFE4E8F0)
    val DarkOutline = Color(0xFF354052)
    val LightForeground = Color(0xFF101820)
    val DarkForeground = Color(0xFFF1F5F9)
    val LightMuted = Color(0xFF667085)
    val DarkMuted = Color(0xFFAAB4C3)
    val EmptySegment = Color(0xFFE8EBF0)
}

@Composable
fun T4ADashboard(state: T4ADashboardState, actions: T4ADashboardActions, darkMode: Boolean, modifier: Modifier = Modifier) {
    val background = if (darkMode) T4ADashboardTokens.DarkBackground else T4ADashboardTokens.LightBackground
    val surface = if (darkMode) T4ADashboardTokens.DarkSurface else T4ADashboardTokens.LightSurface
    val outline = if (darkMode) T4ADashboardTokens.DarkOutline else T4ADashboardTokens.LightOutline
    val foreground = if (darkMode) T4ADashboardTokens.DarkForeground else T4ADashboardTokens.LightForeground
    val muted = if (darkMode) T4ADashboardTokens.DarkMuted else T4ADashboardTokens.LightMuted
    Column(modifier.fillMaxWidth().background(background), verticalArrangement = Arrangement.spacedBy(T4ADashboardTokens.CardSpacing)) {
        ConnectionCard(state, surface, outline, foreground, muted)
        SpeedCard(state, surface, outline, foreground, darkMode)
        MetricsCard(state, surface, outline, foreground, muted, actions)
        RidingControls(state, surface, outline, foreground, muted, darkMode, actions)
    }
}

@Composable
private fun ConnectionCard(state: T4ADashboardState, surface: Color, outline: Color, foreground: Color, muted: Color) {
    DashboardCard(surface, outline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MdiIcon("cmd-check-circle", if (state.connected) T4ADashboardTokens.Green else T4ADashboardTokens.Red, 46.dp, Modifier.size(54.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (state.connected) R.string.connected else R.string.disconnected), color = if (state.connected) T4ADashboardTokens.Green else T4ADashboardTokens.Red, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.device, state.deviceName.ifBlank { "--" }), color = foreground, fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LockStatusIndicator(state)
                Spacer(Modifier.width(8.dp))
                MdiIcon(signalIcon(state.rssiDbm), signalColor(state.rssiDbm, muted), 18.dp, Modifier.size(22.dp))
                Spacer(Modifier.width(5.dp))
                Text(state.rssiDbm?.let { stringResource(R.string.dbm, it) } ?: "--", color = foreground, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(7.dp))
        BatteryIndicator(state.batteryPercent, foreground, muted)
    }
}

@Composable
private fun LockStatusIndicator(state: T4ADashboardState) {
    val lockColor = if (state.locked) T4ADashboardTokens.Red else T4ADashboardTokens.Green
    val lockIcon = if (state.locked) "cmd-lock" else "cmd-lock-open-variant"
    if (state.autoLockOn) {
        AutoLockIcon(
            lockIcon = lockIcon,
            lockColor = lockColor,
            bluetoothColor = T4ADashboardTokens.Blue,
            lockSize = 27.dp,
            modifier = Modifier.size(33.dp),
            bluetoothSize = 10.5.dp,
            badgeOffsetY = (-3).dp,
        )
    } else {
        MdiIcon(lockIcon, lockColor, 18.dp, Modifier.size(22.dp))
    }
}

@Composable
private fun AutoLockIcon(
    lockIcon: String,
    lockColor: Color,
    bluetoothColor: Color,
    lockSize: Dp,
    modifier: Modifier = Modifier,
    bluetoothSize: Dp = 13.dp,
    badgeOffsetY: Dp = (-5).dp,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        MdiIcon(lockIcon, lockColor, lockSize, Modifier.matchParentSize())
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 1.dp, y = badgeOffsetY)
                .size(bluetoothSize + 7.dp)
                .background(Color.White, CircleShape)
                .border(1.dp, bluetoothColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MdiIcon("cmd-bluetooth", bluetoothColor, bluetoothSize, Modifier.size(bluetoothSize + 2.dp))
        }
    }
}

@Composable
private fun BatteryIndicator(percent: Int, foreground: Color, muted: Color) {
    val safePercent = percent.coerceIn(0, 100)
    Row(verticalAlignment = Alignment.CenterVertically) {
        MdiIcon("cmd-battery-charging", if (safePercent > 20) T4ADashboardTokens.Green else T4ADashboardTokens.Red, 32.dp, Modifier.width(38.dp).height(25.dp), 90f)
        Spacer(Modifier.width(8.dp))
        Row(Modifier.weight(1f).height(T4ADashboardTokens.BatteryHeight), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(20) { index ->
                val threshold = (index + 1) * 5
                val activeColor = when { index < 4 -> T4ADashboardTokens.Red; index < 10 -> T4ADashboardTokens.Amber; else -> T4ADashboardTokens.Green }
                Box(Modifier.weight(1f).height(T4ADashboardTokens.BatteryHeight).background(if (threshold <= safePercent) activeColor else T4ADashboardTokens.EmptySegment, RoundedCornerShape(3.dp)))
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(stringResource(R.string.percent, safePercent), color = if (safePercent == 0) muted else foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SpeedCard(state: T4ADashboardState, surface: Color, outline: Color, foreground: Color, darkMode: Boolean) {
    val speedTextColor = if (darkMode) Color.White else foreground
    val scaleTextColor = if (darkMode) T4ADashboardTokens.DarkScaleBlue else T4ADashboardTokens.Blue
    DashboardCard(surface, outline) {
        Box(Modifier.fillMaxWidth().height(T4ADashboardTokens.SpeedGaugeHeight)) {
            SpeedSegments(state.speed, state.ridingMode, darkMode, Modifier.matchParentSize())
            if (state.ridingMode != RidingMode.UNKNOWN) {
                BoxWithConstraints(Modifier.matchParentSize()) {
                    val centerX = maxWidth * 0.975f - 11.dp
                    MdiIcon(
                        modeIcon(state.ridingMode),
                        modeColor(state.ridingMode),
                        23.dp,
                        Modifier.offset(x = centerX - 14.dp, y = 6.dp).size(28.dp),
                    )
                }
            }
            Row(Modifier.align(Alignment.TopCenter).height(130.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.speed_value, state.speed), color = speedTextColor, fontSize = T4ADashboardTokens.SpeedFontSize, fontWeight = FontWeight.Bold)
                Text(stringResource(if (state.speedUnit == SpeedUnit.MPH) R.string.metric_mph else R.string.metric_kmh), color = speedTextColor, fontSize = T4ADashboardTokens.SpeedUnitFontSize, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, top = 34.dp))
            }
            BoxWithConstraints(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(20.dp)) {
                (5..45 step 5).forEach { value ->
                    val centerX = maxWidth * (value / 50f)
                    Box(Modifier.offset(x = centerX - 12.dp).width(24.dp), contentAlignment = Alignment.Center) {
                        Text(value.toString(), color = scaleTextColor, fontSize = 11.sp, fontWeight = if (darkMode) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedSegments(speed: Int, mode: RidingMode, darkMode: Boolean, modifier: Modifier = Modifier) {
    val currentModeColor = modeColor(mode)
    Canvas(modifier) {
        val left = 2.dp.toPx(); val top = 8.dp.toPx(); val bottom = size.height - 24.dp.toPx(); val width = size.width - left * 2f
        val subdivision = width / 50f; val safeSpeed = speed.coerceIn(0, 50); val inactive = if (darkMode) Color(0xFF293241) else Color(0xFFF0F2F6)
        repeat(50) { index ->
            val value = index + 1; val majorGap = if (value % 5 == 0) 2.dp.toPx() else 0.6.dp.toPx()
            val activeColor = when { value <= 6 -> T4ADashboardTokens.Cyan; value <= 25 -> T4ADashboardTokens.Green; value <= 35 -> T4ADashboardTokens.Orange; else -> T4ADashboardTokens.Red }
            drawRect(if (safeSpeed >= value) activeColor.copy(alpha = if (darkMode) 0.72f else 0.48f) else inactive, Offset(left + index * subdivision, top), Size((subdivision - majorGap).coerceAtLeast(1f), bottom - top))
        }
        for (value in 5..45 step 5) { val x = left + width * (value / 50f); drawRect(Color.White, Offset(x - 0.5.dp.toPx(), top), Size(1.dp.toPx(), bottom - top)) }
        if (mode != RidingMode.UNKNOWN && mode.limitKmh in 1..49) { val x = left + width * (mode.limitKmh / 50f); drawRect(currentModeColor, Offset(x - 1.dp.toPx(), top), Size(2.dp.toPx(), bottom - top)) }
    }
}

@Composable
private fun MetricsCard(state: T4ADashboardState, surface: Color, outline: Color, foreground: Color, muted: Color, actions: T4ADashboardActions) {
    val partial = state.odometerLabel.equals("Percurso", ignoreCase = true) || state.odometerLabel.contains("parcial", ignoreCase = true)
    DashboardCard(surface, outline) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MetricWithIcon(if (partial) "cmd-map-marker-distance" else "cmd-speedometer", localizedMetricLabel(if (partial) R.string.trip_odometer else R.string.total_odometer), state.odometerValue, foreground, muted, Modifier.weight(1f).clickable(onClick = actions::toggleOdometer))
            Box(Modifier.width(1.dp).height(44.dp).background(outline))
            MetricWithIcon("cmd-clock-outline", localizedMetricLabel(R.string.usage_time), state.usageTime, foreground, muted, Modifier.weight(1f))
        }
    }
}

@Composable
private fun localizedMetricLabel(@StringRes id: Int): String = stringResource(id, "").substringBefore('\n').trim()

@Composable
private fun MetricWithIcon(icon: String, label: String, value: String, foreground: Color, muted: Color, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        MdiIcon(icon, T4ADashboardTokens.Blue, 23.dp, Modifier.size(28.dp))
        Column(Modifier.padding(start = 7.dp)) {
            Text(label, color = muted, fontSize = 10.sp)
            Text(value, color = if (value.isBlank()) muted else foreground, fontSize = T4ADashboardTokens.MetricFontSize)
        }
    }
}

@Composable
private fun RidingControls(state: T4ADashboardState, surface: Color, outline: Color, foreground: Color, muted: Color, darkMode: Boolean, actions: T4ADashboardActions) {
    DashboardCard(surface, outline) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(RidingMode.WALK, RidingMode.ECO, RidingMode.RACE, RidingMode.SPORT).forEach { mode -> ModeTile(mode, state.ridingMode == mode, state.modeEnabled, surface, muted, darkMode) { actions.setRidingMode(mode) } }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleTile(stringResource(R.string.light), stringResource(if (state.lightOn) R.string.state_on else R.string.state_off), "cmd-car-light-dimmed", state.lightOn, T4ADashboardTokens.Orange, state.lightEnabled, surface, muted, darkMode, Modifier.weight(1f)) { actions.setLight(!state.lightOn) }
            ToggleTile(stringResource(R.string.start_mode), stringResource(if (state.initialPushOn) R.string.state_kick else R.string.state_zero), "cmd-run", state.initialPushOn, T4ADashboardTokens.Green, state.initialPushEnabled, surface, muted, darkMode, Modifier.weight(1f)) { actions.setInitialPush(!state.initialPushOn) }
            ToggleTile(stringResource(R.string.cruise), stringResource(if (state.cruiseOn) R.string.state_on else R.string.state_off), "cmd-car-cruise-control", state.cruiseOn, T4ADashboardTokens.Blue, state.cruiseEnabled, surface, muted, darkMode, Modifier.weight(1f), iconOffsetX = (-3).dp) { actions.setCruise(!state.cruiseOn) }
            ConsolidatedLockControl(state, surface, muted, darkMode, actions, Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsolidatedLockControl(state: T4ADashboardState, surface: Color, muted: Color, darkMode: Boolean, actions: T4ADashboardActions, modifier: Modifier = Modifier) {
    var menuExpanded by remember { mutableStateOf(false) }
    val enabled = state.controlsEnabled && (state.autoLockOn || state.lockEnabled)
    val color = when { state.autoLockOn -> T4ADashboardTokens.Blue; state.locked -> T4ADashboardTokens.Red; else -> T4ADashboardTokens.Green }
    val lockColor = if (state.locked) T4ADashboardTokens.Red else T4ADashboardTokens.Green
    val lockIcon = if (state.locked) "cmd-lock" else "cmd-lock-open-variant"
    val stateLabel = when {
        state.autoLockOn -> stringResource(R.string.automatic)
        state.locked -> stringResource(R.string.state_on)
        else -> stringResource(R.string.state_off)
    }
    val fill = color.copy(alpha = if (darkMode) 0.19f else 0.07f)

    Box(modifier.height(T4ADashboardTokens.ToggleHeight)) {
        Column(
            Modifier.fillMaxWidth().height(T4ADashboardTokens.ToggleHeight).alpha(if (enabled) 1f else 0.55f)
                .background(fill, RoundedCornerShape(T4ADashboardTokens.ToggleRadius))
                .border(2.dp, color, RoundedCornerShape(T4ADashboardTokens.ToggleRadius))
                .combinedClickable(
                    enabled = enabled,
                    onClick = { if (!state.autoLockOn && state.lockEnabled) actions.setLocked(!state.locked) },
                    onLongClick = { menuExpanded = true },
                ).padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (state.autoLockOn) {
                AutoLockIcon(
                    lockIcon = lockIcon,
                    lockColor = if (enabled) lockColor else muted,
                    bluetoothColor = if (enabled) T4ADashboardTokens.Blue else muted,
                    lockSize = 28.dp,
                    modifier = Modifier.size(40.dp),
                    bluetoothSize = 13.dp,
                    badgeOffsetY = (-5).dp,
                )
            } else {
                MdiIcon(lockIcon, if (enabled) lockColor else muted, 28.dp, Modifier.size(40.dp))
            }
            ControlText(
                title = stringResource(R.string.lock_control),
                state = stateLabel,
                color = if (enabled) color else foregroundForInactive(darkMode),
                bold = true,
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.lock), color = T4ADashboardTokens.Red) }, onClick = { menuExpanded = false; actions.setAutoLock(false); actions.setLocked(true) }, enabled = state.lockEnabled)
            DropdownMenuItem(text = { Text(stringResource(R.string.unlock), color = T4ADashboardTokens.Green) }, onClick = { menuExpanded = false; actions.setAutoLock(false); actions.setLocked(false) }, enabled = state.lockEnabled)
            DropdownMenuItem(text = { Text(stringResource(R.string.automatic_lock), color = T4ADashboardTokens.Blue) }, onClick = { menuExpanded = false; actions.setAutoLock(true) }, enabled = state.controlsEnabled)
        }
    }
}

@Composable
private fun RowScope.ModeTile(mode: RidingMode, active: Boolean, enabled: Boolean, surface: Color, muted: Color, darkMode: Boolean, onClick: () -> Unit) {
    val color = modeColor(mode)
    val fill = if (active) color.copy(alpha = if (darkMode) 0.19f else 0.07f) else surface
    val iconSize = when (mode) {
        RidingMode.WALK -> 30.dp
        RidingMode.ECO -> 30.dp
        RidingMode.RACE -> 29.dp
        RidingMode.SPORT -> 31.dp
        RidingMode.UNKNOWN -> 28.dp
    }
    Column(
        Modifier.weight(1f).height(T4ADashboardTokens.ModeHeight).alpha(if (enabled) 1f else 0.55f)
            .background(fill, RoundedCornerShape(T4ADashboardTokens.ModeRadius))
            .border(if (active) 2.dp else 1.dp, color, RoundedCornerShape(T4ADashboardTokens.ModeRadius))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            MdiIcon(modeIcon(mode), if (active) color else color.copy(alpha = 0.68f), iconSize, Modifier.size(36.dp))
        }
        Text(
            stringResource(modeLabelRes(mode)),
            color = if (active) color else color.copy(alpha = 0.68f),
            fontSize = T4ADashboardTokens.ModeFontSize,
            fontWeight = FontWeight.Bold,
            lineHeight = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.speed_limit, "", mode.limitKmh).substringAfter('\n'),
            color = if (active) color else muted,
            fontSize = T4ADashboardTokens.ModeSpeedFontSize,
            lineHeight = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@StringRes
private fun modeLabelRes(mode: RidingMode): Int = when (mode) { RidingMode.WALK -> R.string.mode_walk; RidingMode.ECO -> R.string.mode_eco; RidingMode.RACE -> R.string.mode_race; RidingMode.SPORT -> R.string.mode_sport; RidingMode.UNKNOWN -> R.string.unknown }

@Composable
private fun ToggleTile(title: String, stateLabel: String, icon: String, active: Boolean, color: Color, enabled: Boolean, surface: Color, muted: Color, darkMode: Boolean, modifier: Modifier = Modifier, actionHeight: Dp = T4ADashboardTokens.ToggleHeight, radius: Dp = T4ADashboardTokens.ToggleRadius, iconSize: Dp = 28.dp, iconOffsetX: Dp = 0.dp, onClick: () -> Unit) {
    val fill = if (active) color.copy(alpha = if (darkMode) 0.19f else 0.07f) else surface
    Column(modifier.height(actionHeight).alpha(if (enabled) 1f else 0.55f).background(fill, RoundedCornerShape(radius)).border(if (active) 2.dp else 1.dp, color, RoundedCornerShape(radius)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 5.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        MdiIcon(icon, if (active) color else muted, iconSize, Modifier.offset(x = iconOffsetX).size(if (actionHeight == T4ADashboardTokens.ActionHeight) 34.dp else 40.dp))
        ControlText(
            title = title,
            state = stateLabel,
            color = if (active) color else foregroundForInactive(darkMode),
            bold = active,
            fontSize = if (actionHeight == T4ADashboardTokens.ActionHeight) T4ADashboardTokens.ActionFontSize else T4ADashboardTokens.ControlFontSize,
        )
    }
}

@Composable
private fun ControlText(title: String, state: String, color: Color, bold: Boolean, fontSize: androidx.compose.ui.unit.TextUnit = T4ADashboardTokens.ControlFontSize) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Text(title, color = color, fontSize = fontSize, fontWeight = weight, lineHeight = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(state, color = color, fontSize = fontSize, fontWeight = weight, lineHeight = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DashboardCard(surface: Color, outline: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().shadow(T4ADashboardTokens.CardElevation, RoundedCornerShape(T4ADashboardTokens.CardRadius)).background(surface, RoundedCornerShape(T4ADashboardTokens.CardRadius)).border(1.dp, outline, RoundedCornerShape(T4ADashboardTokens.CardRadius)).padding(T4ADashboardTokens.CardPadding), content = content)
}

@Composable
private fun MdiIcon(name: String, color: Color, iconSize: Dp, modifier: Modifier = Modifier, rotation: Float = 0f) {
    val argb = color.toArgb()
    AndroidView(modifier, factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE } }, update = { image ->
        image.rotation = rotation; val pixels = (iconSize.value * image.resources.displayMetrics.density).toInt()
        image.setImageDrawable(IconicsDrawable(image.context, name).apply { colorList = ColorStateList.valueOf(argb); sizeXPx = pixels; sizeYPx = pixels })
    })
}

private fun signalIcon(rssi: Int?): String = when { rssi == null -> "cmd-signal-cellular-outline"; rssi >= -40 -> "cmd-signal-cellular-3"; rssi >= -60 -> "cmd-signal-cellular-2"; else -> "cmd-signal-cellular-1" }
private fun signalColor(rssi: Int?, muted: Color): Color = when { rssi == null -> muted; rssi >= -40 -> T4ADashboardTokens.Green; rssi >= -60 -> T4ADashboardTokens.Orange; else -> T4ADashboardTokens.Red }
private fun modeIcon(mode: RidingMode): String = when (mode) { RidingMode.WALK -> "cmd-walk"; RidingMode.ECO -> "cmd-leaf"; RidingMode.RACE -> "cmd-flag-checkered"; RidingMode.SPORT -> "cmd-speedometer"; RidingMode.UNKNOWN -> "cmd-help-circle-outline" }
private fun modeColor(mode: RidingMode): Color = when (mode) { RidingMode.WALK -> T4ADashboardTokens.Cyan; RidingMode.ECO -> T4ADashboardTokens.Green; RidingMode.RACE -> T4ADashboardTokens.Orange; RidingMode.SPORT -> T4ADashboardTokens.Red; RidingMode.UNKNOWN -> T4ADashboardTokens.LightMuted }
private fun foregroundForInactive(darkMode: Boolean): Color = if (darkMode) T4ADashboardTokens.DarkForeground else T4ADashboardTokens.LightForeground

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun DashboardPreview() {
    T4ADashboard(T4ADashboardState(connected = true, deviceName = "T4A", rssiDbm = -48, batteryPercent = 76, speed = 23, speedUnit = SpeedUnit.KMH, ridingMode = RidingMode.ECO, locked = false, lightOn = true, initialPushOn = true, cruiseOn = false, autoLockOn = true, odometerLabel = "Odômetro total", odometerValue = "128,4 km", usageTime = "01:42:18", controlsEnabled = true, lightEnabled = true, initialPushEnabled = true, cruiseEnabled = true, modeEnabled = true, lockEnabled = true), NoOpT4ADashboardActions, false, Modifier.padding(16.dp))
}