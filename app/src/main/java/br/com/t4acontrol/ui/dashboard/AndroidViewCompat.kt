package br.com.t4acontrol.ui.dashboard

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Compatibility overload for dashboard interop calls that keep Modifier as the first argument.
 * Delegates to the official Compose AndroidView API using named parameters.
 */
@Composable
fun <T : View> AndroidView(
    modifier: Modifier,
    factory: (android.content.Context) -> T,
    update: (T) -> Unit = {},
) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = factory,
        modifier = modifier,
        update = update,
    )
}
