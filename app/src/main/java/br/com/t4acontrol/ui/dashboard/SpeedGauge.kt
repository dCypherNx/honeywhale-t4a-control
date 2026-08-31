package br.com.t4acontrol.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.t4acontrol.R

@Composable
internal fun SpeedCard(state: T4ADashboardState, surface: Color, outline: Color, foreground: Color, darkMode: Boolean) {
    val speedTextColor = if (darkMode) Color.White else foreground
    val scaleTextColor = if (darkMode) T4ADashboardTokens.DarkScaleBlue else T4ADashboardTokens.Blue
    DashboardCard(surface, outline) {
        Box(Modifier.fillMaxWidth().height(T4ADashboardTokens.SpeedGaugeHeight)) {
            SpeedSegments(state.speed, state.ridingMode, darkMode, Modifier.matchParentSize())
            if (state.ridingMode != RidingMode.UNKNOWN) {
                BoxWithConstraints(Modifier.matchParentSize()) {
                    val centerX = maxWidth * 0.975f - 11.dp
                    MdiIcon(modeIcon(state.ridingMode), modeColor(state.ridingMode), 23.dp, Modifier.offset(x = centerX - 14.dp, y = 6.dp).size(28.dp))
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
internal fun SpeedSegments(speed: Int, mode: RidingMode, darkMode: Boolean, modifier: Modifier = Modifier) {
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
            drawRect(if (safeSpeed >= value) activeColor.copy(alpha = if (darkMode) 0.72f else 0.48f) else inactive, Offset(left + index * subdivision, top), Size((subdivision - majorGap).coerceAtLeast(1f), bottom - top))
        }
        for (value in 5..45 step 5) {
            val x = left + width * (value / 50f)
            drawRect(Color.White, Offset(x - 0.5.dp.toPx(), top), Size(1.dp.toPx(), bottom - top))
        }
        if (mode != RidingMode.UNKNOWN && mode.limitKmh in 1..49) {
            val x = left + width * (mode.limitKmh / 50f)
            drawRect(currentModeColor, Offset(x - 1.dp.toPx(), top), Size(2.dp.toPx(), bottom - top))
        }
    }
}
