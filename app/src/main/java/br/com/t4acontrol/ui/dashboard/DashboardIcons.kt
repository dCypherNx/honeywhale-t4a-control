package br.com.t4acontrol.ui.dashboard

import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mikepenz.iconics.IconicsDrawable

@Composable
internal fun StableLockIcon(locked: Boolean, color: Color, modifier: Modifier = Modifier, lockSize: Dp) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(lockSize)) {
            val bodyLeft = size.width * 0.14f
            val bodyTop = size.height * 0.39f
            val bodyWidth = size.width * 0.72f
            val bodyHeight = size.height * 0.55f
            val strokeWidth = size.width * 0.105f

            val pivotX = size.width * 0.68f
            val shackleWidth = size.width * 0.35f
            val shackleTop = size.height * 0.06f
            val arcHeight = size.height * 0.38f
            val arcEndY = shackleTop + arcHeight / 2f

            drawRoundRect(color = color, topLeft = Offset(bodyLeft, bodyTop), size = Size(bodyWidth, bodyHeight), cornerRadius = CornerRadius(size.width * 0.09f, size.width * 0.09f))

            if (locked) {
                val leftX = pivotX - shackleWidth
                drawArc(color = color, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(leftX, shackleTop), size = Size(shackleWidth, arcHeight), style = Stroke(strokeWidth, cap = StrokeCap.Round))
                drawLine(color, Offset(leftX, arcEndY), Offset(leftX, bodyTop), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(pivotX, arcEndY), Offset(pivotX, bodyTop), strokeWidth, StrokeCap.Round)
            } else {
                val freeX = pivotX + shackleWidth
                val freeEndY = arcEndY + size.height * 0.08f
                drawArc(color = color, startAngle = 180f, sweepAngle = 180f, useCenter = false, topLeft = Offset(pivotX, shackleTop), size = Size(shackleWidth, arcHeight), style = Stroke(strokeWidth, cap = StrokeCap.Round))
                drawLine(color, Offset(pivotX, arcEndY), Offset(pivotX, bodyTop), strokeWidth, StrokeCap.Round)
                drawLine(color, Offset(freeX, arcEndY), Offset(freeX, freeEndY), strokeWidth, StrokeCap.Round)
            }
        }
    }
}

@Composable
internal fun AutoLockIcon(locked: Boolean, lockColor: Color, bluetoothColor: Color, lockSize: Dp, modifier: Modifier = Modifier, bluetoothSize: Dp = 8.dp) {
    Box(modifier, contentAlignment = Alignment.Center) {
        StableLockIcon(locked, lockColor, Modifier.matchParentSize(), lockSize)
        Box(Modifier.align(Alignment.Center).offset(x = lockSize * 0.15f, y = lockSize * 0.16f).size(bluetoothSize + 4.dp).background(Color.White, CircleShape).border(1.dp, bluetoothColor, CircleShape), contentAlignment = Alignment.Center) {
            MdiIcon("cmd-bluetooth", bluetoothColor, bluetoothSize, Modifier.size(bluetoothSize + 1.dp))
        }
    }
}

@Composable
internal fun MdiIcon(name: String, color: Color, iconSize: Dp, modifier: Modifier = Modifier, rotation: Float = 0f) {
    val argb = color.toArgb()
    AndroidView(modifier, factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE } }, update = { image ->
        image.rotation = rotation
        val pixels = (iconSize.value * image.resources.displayMetrics.density).toInt()
        image.setImageDrawable(IconicsDrawable(image.context, name).apply {
            colorList = ColorStateList.valueOf(argb)
            sizeXPx = pixels
            sizeYPx = pixels
        })
    })
}

private enum class RssiVisualBand(val filledBars: Int, val color: Color) {
    SHORT(3, T4ADashboardTokens.Green),
    MEDIUM(2, T4ADashboardTokens.Amber),
    LONG(1, T4ADashboardTokens.Red),
}

@Composable
internal fun RssiSignalIndicator(state: T4ADashboardState, muted: Color, modifier: Modifier = Modifier) {
    val band = calibratedRssiBand(state)
    val color = band?.color ?: muted
    val filledBars = band?.filledBars ?: 0

    Canvas(modifier.size(22.dp)) {
        val barWidth = size.width * 0.21f
        val gap = size.width * 0.12f
        val heights = floatArrayOf(size.height * 0.40f, size.height * 0.68f, size.height * 0.96f)
        val corner = CornerRadius(barWidth * 0.22f, barWidth * 0.22f)
        val strokeWidth = 1.4.dp.toPx()

        repeat(3) { index ->
            val height = heights[index]
            val left = index * (barWidth + gap)
            val top = size.height - height
            val style = if (index < filledBars) androidx.compose.ui.graphics.drawscope.Fill else Stroke(strokeWidth)
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barWidth, height),
                cornerRadius = corner,
                style = style,
            )
        }
    }
}

private fun calibratedRssiBand(state: T4ADashboardState): RssiVisualBand? {
    val rssi = state.rssiDbm ?: return null
    val shortMin = state.rssiShortMin ?: return null
    val mediumMin = state.rssiMediumMin ?: return null
    if (!state.rssiCalibrationReady) return null

    return when {
        rssi >= shortMin -> RssiVisualBand.SHORT
        rssi >= mediumMin -> RssiVisualBand.MEDIUM
        else -> RssiVisualBand.LONG
    }
}

internal fun modeIcon(mode: RidingMode): String = when (mode) {
    RidingMode.WALK -> "cmd-walk"
    RidingMode.ECO -> "cmd-leaf"
    RidingMode.RACE -> "cmd-flag-checkered"
    RidingMode.SPORT -> "cmd-speedometer"
    RidingMode.UNKNOWN -> "cmd-help-circle-outline"
}

internal fun modeColor(mode: RidingMode): Color = when (mode) {
    RidingMode.WALK -> T4ADashboardTokens.Cyan
    RidingMode.ECO -> T4ADashboardTokens.Green
    RidingMode.RACE -> T4ADashboardTokens.Orange
    RidingMode.SPORT -> T4ADashboardTokens.Red
    RidingMode.UNKNOWN -> T4ADashboardTokens.LightMuted
}

internal fun foregroundForInactive(darkMode: Boolean): Color = if (darkMode) T4ADashboardTokens.DarkForeground else T4ADashboardTokens.LightForeground
