package br.com.t4acontrol.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.t4acontrol.R

@Composable
internal fun ConnectionCard(state: T4ADashboardState, surface: Color, outline: Color, foreground: Color, muted: Color) {
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
                RssiSignalIndicator(state, muted, Modifier.size(22.dp))
                Spacer(Modifier.width(5.dp))
                Text(state.rssiDbm?.let { stringResource(R.string.dbm, it) } ?: "--", color = foreground, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(7.dp))
        BatteryIndicator(state.batteryPercent, state.batteryObservedMin, state.batteryObservedMax, foreground, muted)
    }
}

@Composable
private fun LockStatusIndicator(state: T4ADashboardState) {
    val lockColor = if (state.locked) T4ADashboardTokens.Red else T4ADashboardTokens.Green
    if (state.autoLockOn) {
        AutoLockIcon(state.locked, lockColor, T4ADashboardTokens.Blue, 27.dp, Modifier.size(33.dp), 7.dp)
    } else {
        StableLockIcon(state.locked, lockColor, Modifier.size(33.dp), 27.dp)
    }
}

@Composable
internal fun BatteryIndicator(percent: Int?, observedMin: Int?, observedMax: Int?, foreground: Color, muted: Color) {
    val safePercent = percent?.coerceIn(0, 100)
    val safeMin = observedMin?.coerceIn(0, 100)
    val safeMax = observedMax?.coerceIn(0, 100)
    BoxWithConstraints(Modifier.fillMaxWidth().height(34.dp)) {
        val iconWidth = 38.dp
        val iconToBarGap = 8.dp
        val barToValueGap = 9.dp
        val valueWidth = 40.dp
        val barStart = iconWidth + iconToBarGap
        val barWidth = (maxWidth - barStart - barToValueGap - valueWidth).coerceAtLeast(1.dp)

        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            MdiIcon(
                "cmd-battery-charging",
                when {
                    safePercent == null -> muted
                    safePercent > 20 -> T4ADashboardTokens.Green
                    else -> T4ADashboardTokens.Red
                },
                32.dp,
                Modifier.width(iconWidth).height(25.dp),
                90f,
            )
            Spacer(Modifier.width(iconToBarGap))
            Box(Modifier.weight(1f).height(T4ADashboardTokens.BatteryHeight)) {
                Row(
                    Modifier.fillMaxWidth().height(T4ADashboardTokens.BatteryHeight),
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
                            Modifier
                                .weight(1f)
                                .height(T4ADashboardTokens.BatteryHeight)
                                .background(
                                    if (safePercent != null && threshold <= safePercent) activeColor else T4ADashboardTokens.EmptySegment,
                                    RoundedCornerShape(3.dp),
                                ),
                        )
                    }
                }
            }
            Spacer(Modifier.width(barToValueGap))
            Box(Modifier.width(valueWidth).height(T4ADashboardTokens.BatteryHeight), contentAlignment = Alignment.BottomEnd) {
                Text(
                    safePercent?.let { stringResource(R.string.percent, it) } ?: "--",
                    color = if (safePercent == null) muted else foreground,
                    fontSize = 15.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
            }
        }

        Box(Modifier.offset(x = barStart).width(barWidth).height(34.dp)) {
            safeMin?.let { BatteryRangeMarker(it, true, barWidth, Modifier.matchParentSize()) }
            safeMax?.let { BatteryRangeMarker(it, false, barWidth, Modifier.matchParentSize()) }
        }
    }
}

@Composable
private fun BatteryRangeMarker(value: Int, minimum: Boolean, barWidth: Dp, modifier: Modifier = Modifier) {
    val safeValue = value.coerceIn(0, 100)
    val markerX = barWidth * (safeValue / 100f)
    val labelWidth = 50.dp
    val labelX = if (minimum) markerX - labelWidth - 5.dp else markerX + 5.dp

    Canvas(modifier) {
        val x = size.width * (safeValue / 100f)
        val circleY = 6.dp.toPx()
        val radius = 3.dp.toPx()
        val stroke = 1.25.dp.toPx()
        drawCircle(Color.Black, radius = radius, center = Offset(x, circleY), style = Stroke(stroke))
        drawLine(Color.Black, Offset(x, circleY + radius + 1.dp.toPx()), Offset(x, size.height), stroke, StrokeCap.Round)
    }

    Text(
        text = (if (minimum) "MIN " else "MAX ") + safeValue + "%",
        color = Color.Black,
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 9.sp,
        textAlign = if (minimum) TextAlign.End else TextAlign.Start,
        maxLines = 1,
        modifier = Modifier.offset(x = labelX, y = 1.dp).width(labelWidth),
    )
}
