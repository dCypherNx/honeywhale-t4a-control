package br.com.t4acontrol.ui

import android.content.res.ColorStateList
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mikepenz.iconics.IconicsDrawable

@Composable
internal fun MdiIcon(name: String, color: Color, iconSize: Dp, modifier: Modifier = Modifier) {
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
                },
            )
        },
    )
}
