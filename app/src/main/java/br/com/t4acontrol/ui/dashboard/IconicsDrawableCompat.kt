package br.com.t4acontrol.ui.dashboard

import android.content.res.ColorStateList
import com.mikepenz.iconics.IconicsDrawable

/**
 * Kotlin compatibility bridge for the Java-style IconicsDrawable calls used by
 * the dashboard migration. Android-Iconics 5.5.0 exposes these values as Kotlin
 * properties, so Java setter names are not directly callable from Kotlin.
 */
internal fun IconicsDrawable.setColorList(value: ColorStateList?) {
    colorList = value
}

internal fun IconicsDrawable.setSizeXPx(value: Int) {
    sizeXPx = value
}

internal fun IconicsDrawable.setSizeYPx(value: Int) {
    sizeYPx = value
}
