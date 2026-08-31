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

internal fun signalIcon(rssi: Int?): String = when {
    rssi == null -> "cmd-signal-cellular-outline"
    rssi >= -40 -> "cmd-signal-cellular-3"
    rssi >= -60 -> "cmd-signal-cellular-2"
    else -> "cmd-signal-cellular-1"
}

internal fun signalColor(rssi: Int?, muted: Color): Color = when {
    rssi == null -> muted
    rssi >= -40 -> T4ADashboardTokens.Green
    rssi >= -60 -> T4ADashboardTokens.Orange
    else -> T4ADashboardTokens.Red
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
