package br.com.t4acontrol.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Atribuições visuais do dashboard em um único ponto.
 * Alterações de tamanho, espaçamento, raio ou tipografia devem começar aqui.
 */
object T4ADashboardTokens {
    val ScreenHorizontalPadding: Dp = 0.dp
    val CardSpacing: Dp = 10.dp
    val CardPadding: Dp = 12.dp
    val CardRadius: Dp = 16.dp
    val ControlRadius: Dp = 14.dp
    val ControlHeight: Dp = 76.dp
    val ModeHeight: Dp = 78.dp
    val SpeedGaugeHeight: Dp = 154.dp
    val BatteryHeight: Dp = 16.dp

    val SpeedFontSize = 96.sp
    val SpeedUnitFontSize = 24.sp
    val SectionTitleFontSize = 19.sp
    val MetricFontSize = 12.sp
    val ControlFontSize = 11.sp

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
    val Muted = Color(0xFF667085)
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

    MaterialTheme {
        Column(
            modifier = modifier.fillMaxWidth().background(background),
            verticalArrangement = Arrangement.spacedBy(T4ADashboardTokens.CardSpacing),
        ) {
            ConnectionCard(state, surface, outline, foreground)
            SpeedCard(state, surface, outline, foreground, darkMode)
            MetricsCard(state, surface, outline, foreground, actions)
            RidingControls(state, surface, outline, foreground, actions)
        }
    }
}

@Composable
private fun ConnectionCard(
    state: T4ADashboardState,
    surface: Color,
    outline: Color,
    foreground: Color,
) {
    DashboardCard(surface, outline) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (state.connected) "●" else "○",
                color = if (state.connected) T4ADashboardTokens.Green else T4ADashboardTokens.Red,
                fontSize = 34.sp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (state.connected) "Conectado" else "Desconectado",
                    color = if (state.connected) T4ADashboardTokens.Green else T4ADashboardTokens.Red,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(state.deviceName, color = foreground, fontSize = 14.sp)
            }
            Text(
                text = state.rssiDbm?.let { "$it dBm" } ?: "--",
                color = signalColor(state.rssiDbm),
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        BatteryIndicator(state.batteryPercent, foreground)
    }
}

@Composable
private fun BatteryIndicator(percent: Int, foreground: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("⚡", fontSize = 20.sp, color = if (percent > 20) T4ADashboardTokens.Green else T4ADashboardTokens.Red)
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier.weight(1f).height(T4ADashboardTokens.BatteryHeight),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(20) { index ->
                val threshold = (index + 1) * 5
                val active = threshold <= percent
                val activeColor = when {
                    index < 4 -> T4ADashboardTokens.Red
                    index < 10 -> T4ADashboardTokens.Amber
                    else -> T4ADashboardTokens.Green
                }
                Box(
                    Modifier.weight(1f).height(T4ADashboardTokens.BatteryHeight)
                        .background(if (active) activeColor else Color(0xFFE8EBF0), RoundedCornerShape(3.dp))
                )
            }
        }
        Spacer(Modifier.width(9.dp))
        Text("${percent.coerceIn(0, 100)}%", color = foreground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
            SpeedSegments(state.speed, darkMode, Modifier.matchParentSize())
            Text(
                text = state.ridingMode.label,
                color = modeColor(state.ridingMode),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 8.dp),
            )
            Row(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.speed.toString(), color = foreground, fontSize = T4ADashboardTokens.SpeedFontSize, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(state.speedUnit.label, color = foreground, fontSize = T4ADashboardTokens.SpeedUnitFontSize, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SpeedSegments(speed: Int, darkMode: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val left = 4.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = size.height - 28.dp.toPx()
        val width = size.width - 2 * left
        val unit = width / 50f
        val safeSpeed = speed.coerceIn(0, 50)
        repeat(50) { index ->
            val value = index + 1
            val gap = if (value % 5 == 0) 1.dp.toPx() else 0f
            val activeColor = when {
                value <= 6 -> T4ADashboardTokens.Cyan
                value <= 25 -> T4ADashboardTokens.Green
                value <= 35 -> T4ADashboardTokens.Orange
                else -> T4ADashboardTokens.Red
            }
            val inactive = if (darkMode) Color(0xFF293241) else Color(0xFFF0F2F6)
            drawRect(
                color = if (safeSpeed >= value) activeColor.copy(alpha = if (darkMode) 0.80f else 0.60f) else inactive,
                topLeft = Offset(left + index * unit, top),
                size = Size((unit - gap).coerceAtLeast(1f), bottom - top),
            )
        }
        drawRoundRect(
            color = if (darkMode) Color(0xFF526075) else Color(0xFFB8C2D3),
            topLeft = Offset(left, top),
            size = Size(width, bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
            style = Stroke(1.dp.toPx()),
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        (5..50 step 5).forEach { Text(it.toString(), color = T4ADashboardTokens.Blue, fontSize = 10.sp) }
    }
}

@Composable
private fun MetricsCard(
    state: T4ADashboardState,
    surface: Color,
    outline: Color,
    foreground: Color,
    actions: T4ADashboardActions,
) {
    DashboardCard(surface, outline) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f).clickable(onClick = actions::toggleOdometer),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.odometerLabel, color = T4ADashboardTokens.Muted, fontSize = 10.sp)
                Text(state.odometerValue, color = foreground, fontSize = T4ADashboardTokens.MetricFontSize, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.width(1.dp).height(44.dp).background(outline))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Tempo de uso", color = T4ADashboardTokens.Muted, fontSize = 10.sp)
                Text(state.usageTime, color = foreground, fontSize = T4ADashboardTokens.MetricFontSize, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RidingControls(
    state: T4ADashboardState,
    surface: Color,
    outline: Color,
    foreground: Color,
    actions: T4ADashboardActions,
) {
    DashboardCard(surface, outline) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("⌘  Pilotagem", color = T4ADashboardTokens.Blue, fontSize = T4ADashboardTokens.SectionTitleFontSize, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (state.locked) "BLOQUEADO" else "DESBLOQUEADO", color = if (state.locked) T4ADashboardTokens.Red else T4ADashboardTokens.Green, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(RidingMode.WALK, RidingMode.ECO, RidingMode.RACE, RidingMode.SPORT).forEach { mode ->
                ModeTile(mode, state.ridingMode == mode, state.modeEnabled) { actions.setRidingMode(mode) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleTile("Farol", state.lightOn, T4ADashboardTokens.Orange, state.lightEnabled, Modifier.weight(1f)) { actions.setLight(!state.lightOn) }
            ToggleTile("Partida", state.initialPushOn, T4ADashboardTokens.Green, state.initialPushEnabled, Modifier.weight(1f)) { actions.setInitialPush(!state.initialPushOn) }
            ToggleTile("Cruzeiro", state.cruiseOn, T4ADashboardTokens.Blue, state.cruiseEnabled, Modifier.weight(1f)) { actions.setCruise(!state.cruiseOn) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionTile("Desbloquear", T4ADashboardTokens.Green, state.lockEnabled, Modifier.weight(1f)) { actions.setLocked(false) }
            ActionTile("Bloquear", T4ADashboardTokens.Red, state.lockEnabled, Modifier.weight(1f)) { actions.setLocked(true) }
            ToggleTile("Automático", state.autoLockOn, T4ADashboardTokens.Blue, state.controlsEnabled, Modifier.weight(1f)) { actions.setAutoLock(!state.autoLockOn) }
        }
    }
}

@Composable
private fun RowScopeModeTilePlaceholder() = Unit

@Composable
private fun androidx.compose.foundation.layout.RowScope.ModeTile(
    mode: RidingMode,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color = modeColor(mode)
    Surface(
        modifier = Modifier.weight(1f).height(T4ADashboardTokens.ModeHeight).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(T4ADashboardTokens.ControlRadius),
        color = if (active) color.copy(alpha = 0.13f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(if (active) 2.dp else 1.dp, color),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(mode.label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("${mode.limitKmh} km/h", color = color, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ToggleTile(
    label: String,
    active: Boolean,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(T4ADashboardTokens.ControlHeight).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(T4ADashboardTokens.ControlRadius),
        color = if (active) color.copy(alpha = 0.13f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(if (active) 2.dp else 1.dp, color),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (active) "●" else "○", color = color, fontSize = 21.sp)
            Text(label, color = color, fontSize = T4ADashboardTokens.ControlFontSize, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ActionTile(
    label: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.height(T4ADashboardTokens.ControlHeight).clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(T4ADashboardTokens.ControlRadius),
        color = Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, color),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = color, fontSize = T4ADashboardTokens.ControlFontSize, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DashboardCard(surface: Color, outline: Color, content: @Composable Column.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(surface, RoundedCornerShape(T4ADashboardTokens.CardRadius))
            .border(1.dp, outline, RoundedCornerShape(T4ADashboardTokens.CardRadius))
            .padding(T4ADashboardTokens.CardPadding),
        content = content,
    )
}

private fun signalColor(rssi: Int?): Color = when {
    rssi == null -> T4ADashboardTokens.Muted
    rssi >= -40 -> T4ADashboardTokens.Green
    rssi >= -60 -> T4ADashboardTokens.Orange
    else -> T4ADashboardTokens.Red
}

private fun modeColor(mode: RidingMode): Color = when (mode) {
    RidingMode.WALK -> T4ADashboardTokens.Cyan
    RidingMode.ECO -> T4ADashboardTokens.Green
    RidingMode.RACE -> T4ADashboardTokens.Orange
    RidingMode.SPORT -> T4ADashboardTokens.Red
    RidingMode.UNKNOWN -> T4ADashboardTokens.Muted
}

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun DashboardPreview() {
    T4ADashboard(
        state = T4ADashboardState(
            connected = true,
            deviceName = "HoneyWhale T4A",
            rssiDbm = -47,
            batteryPercent = 74,
            speed = 27,
            ridingMode = RidingMode.RACE,
            lightOn = true,
            cruiseOn = true,
            odometerValue = "126,4 km",
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
        modifier = Modifier.padding(8.dp),
    )
}
