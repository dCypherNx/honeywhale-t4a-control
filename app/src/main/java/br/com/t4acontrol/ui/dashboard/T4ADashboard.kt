package br.com.t4acontrol.ui.dashboard

import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mikepenz.iconics.IconicsDrawable

/**
 * Identidade visual do dashboard T4A.
 *
 * Estes tokens reproduzem as medidas e cores do dashboard clássico. A migração para Compose
 * deve alterar a tecnologia de renderização, nunca redesenhar o painel.
 */
object T4ADashboardTokens {
    val CardSpacing: Dp = 10.dp
    val CardPadding: Dp = 10.dp
    val CardRadius: Dp = 16.dp
    val CardElevation: Dp = 2.dp

    val ModeRadius: Dp = 12.dp
    val ToggleRadius: Dp = 14.dp
    val ActionRadius: Dp = 12.dp
    val ModeHeight: Dp = 78.dp
    val ToggleHeight: Dp = 76.dp
    val ActionHeight: Dp = 82.dp
    val SpeedGaugeHeight: Dp = 154.dp
    val BatteryHeight: Dp = 16.dp

    val SpeedFontSize = 96.sp
    val SpeedUnitFontSize = 24.sp
    val SectionTitleFontSize = 19.sp
    val ModeFontSize = 12.sp
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
fun T4ADashboard(
    state: T4ADashboardState,
    actions: T4ADashboardActions,
    darkMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val background = if (darkMode) T4ADashboardTokens.DarkBackground else T4ADashboardTokens.LightBackground
    val surface = if (darkMode) T4ADashboardTokens.DarkSurface else T4ADashboardTokens.LightSurface
    val outline = if (darkMode) T4ADashboardTokens.DarkOutline else T4ADashboardTokens.LightOutline
    val foreground = if (darkMode) T4ADashboardTokens.DarkForeground else T4ADashboardTokens.LightForeground
    val muted = if (darkMode) T4ADashboardTokens.DarkMuted else T4ADashboardTokens.LightMuted

    Column(
        modifier = modifier.fillMaxWidth().background(background),
        verticalArrangement = Arrangement.spacedBy(T4ADashboardTokens.CardSpacing),
    ) {
        ConnectionCard(state, surface, outline, foreground, muted)
        SpeedCard(state, surface, outline, foreground, darkMode)
        MetricsCard(state, surface, outline, foreground, muted, actions)
        RidingControls(state, surface, outline, foreground, muted, darkMode, actions)
    }
}

@Composable
private fun ConnectionCard(
    state: T4ADashboardState,
    surface: Color,
    outline: Color,
    foreground: Color,
    muted: Color,
) {
    DashboardCard(surface, outline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MdiIcon(
                name = "cmd-check-circle",
                color = if (state.connected) T4ADashboardTokens.Green else T4ADashboardTokens.Red,
                iconSize = 46.dp,
                modifier = Modifier.size(54.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (state.connected) "Conectado" else "Desconectado",
                    color = if (state.connected) T4ADashboardTokens.Green else T4ADashboardTokens.Red,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(state.deviceName.ifBlank { "--" }, color = foreground, fontSize = 14.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MdiIcon(
                    name = signalIcon(state.rssiDbm),
                    color = signalColor(state.rssiDbm, muted),
                    iconSize = 18.dp,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = state.rssiDbm?.let { "$it dBm" } ?: "--",
                    color = foreground,
                    fontSize = 12.sp,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        BatteryIndicator(state.batteryPercent, foreground, muted)
    }
}

@Composable
private fun BatteryIndicator(percent: Int, foreground: Color, muted: Color) {
    val safePercent = percent.coerceIn(0, 100)
    Row(verticalAlignment = Alignment.CenterVertically) {
        MdiIcon(
            name = "cmd-battery-charging",
            color = if (safePercent > 20) T4ADashboardTokens.Green else T4ADashboardTokens.Red,
            iconSize = 32.dp,
            rotation = 90f,
            modifier = Modifier.width(38.dp).height(25.dp),
        )
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.weight(1f).height(T4ADashboardTokens.BatteryHeight),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(20) { index ->
                val threshold = (index + 1) * 5
                val activeColor = when {
                    index < 4 -> T4ADashboardTokens.Red
                    index < 10 -> T4ADashboardTokens.Amber
                    else -> T4ADashboardTokens.Green
                }
                Box(
                    Modifier.weight(1f)
                        .height(T4ADashboardTokens.BatteryHeight)
                        .background(
                            if (threshold <= safePercent) activeColor else T4ADashboardTokens.EmptySegment,
                            RoundedCornerShape(3.dp),
                        )
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            "$safePercent%",
            color = if (safePercent == 0) muted else foreground,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SpeedCard(
    state: T4ADashboardState,
    surface: Color,
    outline: Color,
    foreground: Color,
    darkMode: Boolean,
) {
    DashboardCard(surface, outline) {
        Box(Modifier.fillMaxWidth().height(T4ADashboardTokens.SpeedGaugeHeight)) {
            SpeedSegments(
                speed = state.speed,
                mode = state.ridingMode,
                darkMode = darkMode,
                modifier = Modifier.matchParentSize(),
            )

            if (state.ridingMode != RidingMode.UNKNOWN) {
                MdiIcon(
                    name = modeIcon(state.ridingMode),
                    color = modeColor(state.ridingMode),
                    iconSize = 23.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 8.dp).size(28.dp),
                )
            }

            Row(
                modifier = Modifier.align(Alignment.TopCenter).height(126.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    state.speed.toString(),
                    color = foreground,
                    fontSize = T4ADashboardTokens.SpeedFontSize,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    state.speedUnit.label,
                    color = foreground,
                    fontSize = T4ADashboardTokens.SpeedUnitFontSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 12.dp, top = 32.dp),
                )
            }

            BoxWithConstraints(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(20.dp),
            ) {
                (5..45 step 5).forEach { value ->
                    val centerX = maxWidth * (value / 50f)
                    Box(
                        modifier = Modifier.offset(x = centerX - 12.dp).width(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(value.toString(), color = T4ADashboardTokens.Blue, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedSegments(
    speed: Int,
    mode: RidingMode,
    darkMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val currentModeColor = modeColor(mode)
    Canvas(modifier) {
        val left = 2.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 24.dp.toPx()
        val width = size.width - left * 2f
        val subdivision = width / 50f
        val safeSpeed = speed.coerceIn(0, 50)
        val inactive = if (darkMode) Color(0xFF293241) else Color(0xFFF0F2F6)

        repeat(50) { index ->
            val value = index + 1
            val majorGap = if (value % 5 == 0) 2.dp.toPx() else 0.6.dp.toPx()
            val activeColor = when {
                value <= 6 -> T4ADashboardTokens.Cyan
                value <= 25 -> T4ADashboardTokens.Green
                value <= 35 -> T4ADashboardTokens.Orange
                else -> T4ADashboardTokens.Red
            }
            drawRect(
                color = if (safeSpeed >= value) activeColor.copy(alpha = if (darkMode) 0.72f else 0.48f) else inactive,
                topLeft = Offset(left + index * subdivision, top),
                size = Size((subdivision - majorGap).coerceAtLeast(1f), bottom - top),
            )
        }

        for (value in 5..45 step 5) {
            val separatorX = left + width * (value / 50f)
            drawRect(
                color = Color.White,
                topLeft = Offset(separatorX - 0.5.dp.toPx(), top),
                size = Size(1.dp.toPx(), bottom - top),
            )
        }

        if (mode != RidingMode.UNKNOWN && mode.limitKmh in 1..49) {
            val markerX = left + width * (mode.limitKmh / 50f)
            drawRect(
                color = currentModeColor,
                topLeft = Offset(markerX - 1.dp.toPx(), top),
                size = Size(2.dp.toPx(), bottom - top),
            )
        }
    }
}

@Composable
private fun MetricsCard(
    state: T4ADashboardState,
    surface: Color,
    outline: Color,
    foreground: Color,
    muted: Color,
    actions: T4ADashboardActions,
) {
    DashboardCard(surface, outline) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MetricWithIcon(
                icon = if (state.odometerLabel.equals("Percurso", ignoreCase = true)) "cmd-map-marker-distance" else "cmd-speedometer",
                label = state.odometerLabel,
                value = state.odometerValue,
                foreground = foreground,
                muted = muted,
                modifier = Modifier.weight(1f).clickable(onClick = actions::toggleOdometer),
            )
            Box(Modifier.width(1.dp).height(44.dp).background(outline))
            MetricWithIcon(
                icon = "cmd-clock-outline",
                label = "Tempo de uso",
                value = state.usageTime,
                foreground = foreground,
                muted = muted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricWithIcon(
    icon: String,
    label: String,
    value: String,
    foreground: Color,
    muted: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        MdiIcon(
            name = icon,
            color = T4ADashboardTokens.Blue,
            iconSize = 23.dp,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.padding(start = 7.dp)) {
            Text(label, color = muted, fontSize = 10.sp)
            Text(
                value,
                color = if (value.isBlank()) muted else foreground,
                fontSize = T4ADashboardTokens.MetricFontSize,
            )
        }
    }
}

@Composable
private fun RidingControls(
    state: T4ADashboardState,
    surface: Color,
    outline: Color,
    foreground: Color,
    muted: Color,
    darkMode: Boolean,
    actions: T4ADashboardActions,
) {
    DashboardCard(surface, outline) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                MdiIcon(
                    name = "cmd-car-shift-pattern",
                    color = T4ADashboardTokens.Blue,
                    iconSize = 21.dp,
                    modifier = Modifier.size(25.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "Pilotagem",
                    color = T4ADashboardTokens.Blue,
                    fontSize = T4ADashboardTokens.SectionTitleFontSize,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                if (state.locked) "BLOQUEADO" else "DESBLOQUEADO",
                color = if (state.locked) T4ADashboardTokens.Red else T4ADashboardTokens.Green,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(RidingMode.WALK, RidingMode.ECO, RidingMode.RACE, RidingMode.SPORT).forEach { mode ->
                ModeTile(
                    mode = mode,
                    active = state.ridingMode == mode,
                    enabled = state.modeEnabled,
                    surface = surface,
                    muted = muted,
                    darkMode = darkMode,
                    onClick = { actions.setRidingMode(mode) },
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleTile(
                label = if (state.lightOn) "Farol\nLigado" else "Farol\nDesligado",
                icon = "cmd-car-light-dimmed",
                active = state.lightOn,
                color = T4ADashboardTokens.Orange,
                enabled = state.lightEnabled,
                surface = surface,
                muted = muted,
                darkMode = darkMode,
                modifier = Modifier.weight(1f),
            ) { actions.setLight(!state.lightOn) }
            ToggleTile(
                label = if (state.initialPushOn) "Partida\nImpulso" else "Partida\nZero",
                icon = "cmd-run",
                active = state.initialPushOn,
                color = T4ADashboardTokens.Green,
                enabled = state.initialPushEnabled,
                surface = surface,
                muted = muted,
                darkMode = darkMode,
                modifier = Modifier.weight(1f),
            ) { actions.setInitialPush(!state.initialPushOn) }
            ToggleTile(
                label = if (state.cruiseOn) "Cruzeiro\nLigado" else "Cruzeiro\nDesligado",
                icon = "cmd-car-cruise-control",
                active = state.cruiseOn,
                color = T4ADashboardTokens.Blue,
                enabled = state.cruiseEnabled,
                surface = surface,
                muted = muted,
                darkMode = darkMode,
                modifier = Modifier.weight(1f),
            ) { actions.setCruise(!state.cruiseOn) }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionTile(
                label = "Desbloquear",
                icon = "cmd-lock-open",
                color = T4ADashboardTokens.Green,
                enabled = state.lockEnabled,
                surface = surface,
                modifier = Modifier.weight(1f),
            ) { actions.setLocked(false) }
            ActionTile(
                label = "Bloquear",
                icon = "cmd-lock",
                color = T4ADashboardTokens.Red,
                enabled = state.lockEnabled,
                surface = surface,
                modifier = Modifier.weight(1f),
            ) { actions.setLocked(true) }
            ToggleTile(
                label = if (state.autoLockOn) "Automático\nAtivo" else "Automático",
                icon = "cmd-bluetooth",
                active = state.autoLockOn,
                color = T4ADashboardTokens.Blue,
                enabled = state.controlsEnabled,
                surface = surface,
                muted = muted,
                darkMode = darkMode,
                actionHeight = T4ADashboardTokens.ActionHeight,
                radius = T4ADashboardTokens.ActionRadius,
                iconSize = 22.dp,
                modifier = Modifier.weight(1f),
            ) { actions.setAutoLock(!state.autoLockOn) }
        }
    }
}

@Composable
private fun RowScope.ModeTile(
    mode: RidingMode,
    active: Boolean,
    enabled: Boolean,
    surface: Color,
    muted: Color,
    darkMode: Boolean,
    onClick: () -> Unit,
) {
    val color = modeColor(mode)
    val fill = if (active) color.copy(alpha = if (darkMode) 0.19f else 0.07f) else surface
    Column(
        modifier = Modifier.weight(1f)
            .height(T4ADashboardTokens.ModeHeight)
            .alpha(if (enabled) 1f else 0.55f)
            .background(fill, RoundedCornerShape(T4ADashboardTokens.ModeRadius))
            .border(if (active) 2.dp else 1.dp, color, RoundedCornerShape(T4ADashboardTokens.ModeRadius))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MdiIcon(
            name = modeIcon(mode),
            color = if (active) color else color.copy(alpha = 0.68f),
            iconSize = 22.dp,
            modifier = Modifier.size(29.dp),
        )
        Text(
            mode.label,
            color = if (active) color else color.copy(alpha = 0.68f),
            fontSize = T4ADashboardTokens.ModeFontSize,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "${mode.limitKmh} km/h",
            color = if (active) color else muted,
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun ToggleTile(
    label: String,
    icon: String,
    active: Boolean,
    color: Color,
    enabled: Boolean,
    surface: Color,
    muted: Color,
    darkMode: Boolean,
    modifier: Modifier = Modifier,
    actionHeight: Dp = T4ADashboardTokens.ToggleHeight,
    radius: Dp = T4ADashboardTokens.ToggleRadius,
    iconSize: Dp = 28.dp,
    onClick: () -> Unit,
) {
    val fill = if (active) color.copy(alpha = if (darkMode) 0.19f else 0.07f) else surface
    Column(
        modifier = modifier.height(actionHeight)
            .alpha(if (enabled) 1f else 0.55f)
            .background(fill, RoundedCornerShape(radius))
            .border(if (active) 2.dp else 1.dp, color, RoundedCornerShape(radius))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MdiIcon(
            name = icon,
            color = if (active) color else muted,
            iconSize = iconSize,
            modifier = Modifier.size(if (actionHeight == T4ADashboardTokens.ActionHeight) 34.dp else 40.dp),
        )
        Text(
            label,
            color = if (active) color else foregroundForInactive(darkMode),
            fontSize = if (actionHeight == T4ADashboardTokens.ActionHeight) T4ADashboardTokens.ActionFontSize else T4ADashboardTokens.ControlFontSize,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            lineHeight = 11.sp,
        )
    }
}

@Composable
private fun ActionTile(
    label: String,
    icon: String,
    color: Color,
    enabled: Boolean,
    surface: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier.height(T4ADashboardTokens.ActionHeight)
            .alpha(if (enabled) 1f else 0.55f)
            .background(surface, RoundedCornerShape(T4ADashboardTokens.ActionRadius))
            .border(1.dp, color, RoundedCornerShape(T4ADashboardTokens.ActionRadius))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MdiIcon(
            name = icon,
            color = color,
            iconSize = 22.dp,
            modifier = Modifier.size(34.dp),
        )
        Text(label, color = color, fontSize = T4ADashboardTokens.ActionFontSize)
    }
}

@Composable
private fun DashboardCard(
    surface: Color,
    outline: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .shadow(T4ADashboardTokens.CardElevation, RoundedCornerShape(T4ADashboardTokens.CardRadius))
            .background(surface, RoundedCornerShape(T4ADashboardTokens.CardRadius))
            .border(1.dp, outline, RoundedCornerShape(T4ADashboardTokens.CardRadius))
            .padding(T4ADashboardTokens.CardPadding),
        content = content,
    )
}

@Composable
private fun MdiIcon(
    name: String,
    color: Color,
    iconSize: Dp,
    modifier: Modifier = Modifier,
    rotation: Float = 0f,
) {
    val argb = color.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }
        },
        update = { image ->
            image.rotation = rotation
            image.setImageDrawable(
                IconicsDrawable(image.context, name).apply {
                    setColorList(ColorStateList.valueOf(argb))
                    setSizeXPx(image.resources.displayMetrics.density.let { (iconSize.value * it).toInt() })
                    setSizeYPx(image.resources.displayMetrics.density.let { (iconSize.value * it).toInt() })
                }
            )
        },
    )
}

private fun signalIcon(rssi: Int?): String = when {
    rssi == null -> "cmd-signal-cellular-outline"
    rssi >= -40 -> "cmd-signal-cellular-3"
    rssi >= -60 -> "cmd-signal-cellular-2"
    else -> "cmd-signal-cellular-1"
}

private fun signalColor(rssi: Int?, muted: Color): Color = when {
    rssi == null -> muted
    rssi >= -40 -> T4ADashboardTokens.Green
    rssi >= -60 -> T4ADashboardTokens.Orange
    else -> T4ADashboardTokens.Red
}

private fun modeIcon(mode: RidingMode): String = when (mode) {
    RidingMode.WALK -> "cmd-walk"
    RidingMode.ECO -> "cmd-leaf"
    RidingMode.RACE -> "cmd-flag-checkered"
    RidingMode.SPORT -> "cmd-speedometer"
    RidingMode.UNKNOWN -> "cmd-help-circle-outline"
}

private fun modeColor(mode: RidingMode): Color = when (mode) {
    RidingMode.WALK -> T4ADashboardTokens.Cyan
    RidingMode.ECO -> T4ADashboardTokens.Green
    RidingMode.RACE -> T4ADashboardTokens.Orange
    RidingMode.SPORT -> T4ADashboardTokens.Red
    RidingMode.UNKNOWN -> T4ADashboardTokens.LightMuted
}

private fun foregroundForInactive(darkMode: Boolean): Color =
    if (darkMode) T4ADashboardTokens.DarkForeground else T4ADashboardTokens.LightForeground

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun DashboardPreview() {
    T4ADashboard(
        state = T4ADashboardState(
            connected = true,
            deviceName = "T4A",
            rssiDbm = -48,
            batteryPercent = 76,
            speed = 23,
            speedUnit = SpeedUnit.KMH,
            ridingMode = RidingMode.ECO,
            locked = false,
            lightOn = true,
            initialPushOn = true,
            cruiseOn = false,
            autoLockOn = true,
            odometerLabel = "Odômetro total",
            odometerValue = "128,4 km",
            usageTime = "01:42:18",
            controlsEnabled = true,
            lightEnabled = true,
            initialPushEnabled = true,
            cruiseEnabled = true,
            modeEnabled = true,
            lockEnabled = true,
        ),
        actions = NoOpT4ADashboardActions,
        darkMode = false,
        modifier = Modifier.padding(16.dp),
    )
}
