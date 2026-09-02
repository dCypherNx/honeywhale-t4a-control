package br.com.t4acontrol.ui.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.t4acontrol.BuildConfig
import br.com.t4acontrol.backend.navigation.NavigationInstruction
import br.com.t4acontrol.backend.navigation.NavigationState
import br.com.t4acontrol.backend.navigation.Route
import br.com.t4acontrol.navigation.NavigationRuntime
import br.com.t4acontrol.ui.MdiIcon
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NavigationCard(
    surface: Color,
    outline: Color,
    foreground: Color,
    muted: Color,
) {
    val context = LocalContext.current
    val runtime = NavigationRuntime.get()
    var route by remember { mutableStateOf(runtime.route()) }
    var state by remember { mutableStateOf(runtime.state()) }
    DisposableEffect(Unit) {
        runtime.ensureRestored(context)
        val listener = NavigationRuntime.Listener { nextRoute, nextState ->
            route = nextRoute
            state = nextState
        }
        runtime.addListener(listener)
        onDispose { runtime.removeListener(listener) }
    }

    if (state.status == NavigationState.Status.NO_ROUTE) return
    val instruction = state.instruction
    val debugRouteUrl = if (BuildConfig.DEBUG) plannedRouteDebugUrl(route) else null
    DashboardCard(surface = surface, outline = outline) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NavigationManeuverIcon(
                instruction = instruction,
                status = state.status,
                debugRouteUrl = debugRouteUrl,
                context = context,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = navigationTitle(state),
                    color = foreground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                val detail = instruction?.text?.takeIf { it.isNotBlank() }
                if (detail != null && state.status != NavigationState.Status.RECALCULATING) {
                    Text(detail, color = muted, fontSize = 12.sp, maxLines = 1)
                }
            }
            state.distanceToInstructionMeters?.let { distance ->
                Text(
                    text = formatDistance(distance),
                    color = foreground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavigationManeuverIcon(
    instruction: NavigationInstruction?,
    status: NavigationState.Status,
    debugRouteUrl: String?,
    context: Context,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val interactionModifier = if (debugRouteUrl != null) {
        Modifier
            .size(48.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = { menuExpanded = true },
            )
    } else {
        Modifier.size(48.dp)
    }

    Box {
        MdiIcon(
            name = maneuverIcon(instruction?.maneuver, status),
            color = if (
                status == NavigationState.Status.OFF_ROUTE ||
                status == NavigationState.Status.RECALCULATING
            ) T4ADashboardTokens.Red else T4ADashboardTokens.Blue,
            iconSize = 40.dp,
            modifier = interactionModifier,
        )
        if (debugRouteUrl != null) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Abrir rota planejada") },
                    onClick = {
                        menuExpanded = false
                        openPlannedRoute(context, debugRouteUrl)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Copiar link da rota") },
                    onClick = {
                        menuExpanded = false
                        copyPlannedRoute(context, debugRouteUrl)
                    },
                )
            }
        }
    }
}

private fun openPlannedRoute(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Exception) {
        Toast.makeText(context, "Não foi possível abrir a rota", Toast.LENGTH_SHORT).show()
    }
}

private fun copyPlannedRoute(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Rota OSRM", url))
    Toast.makeText(context, "Link da rota copiado", Toast.LENGTH_SHORT).show()
}

/**
 * Builds a diagnostic browser URL from the exact geometry already returned by OSRM.
 * GeoJSON.io renders the LineString directly; it does not calculate a new route.
 */
private fun plannedRouteDebugUrl(route: Route?): String? {
    if (route == null || route.source != "osrm") return null

    val points = route.legs
        .flatMap { it.geometry }
        .fold(mutableListOf<br.com.t4acontrol.backend.navigation.GeoPoint>()) { result, point ->
            val last = result.lastOrNull()
            if (last == null || last.latitude != point.latitude || last.longitude != point.longitude) {
                result.add(point)
            }
            result
        }
    if (points.size < 2) return null

    val coordinates = points.joinToString(separator = ",") { point ->
        "[${point.longitude},${point.latitude}]"
    }
    val geoJson = "{\"type\":\"LineString\",\"coordinates\":[$coordinates]}"
    return "https://geojson.io/#data=data:application/json,${Uri.encode(geoJson)}"
}

private fun navigationTitle(state: NavigationState): String = when (state.status) {
    NavigationState.Status.READY -> "Rota pronta"
    NavigationState.Status.POSITION_UNAVAILABLE -> "Aguardando GPS"
    NavigationState.Status.OFF_ROUTE -> "Fora da rota"
    NavigationState.Status.RECALCULATING -> "Recalculando rota…"
    NavigationState.Status.WAYPOINT_REACHED -> "Parada alcançada"
    NavigationState.Status.ARRIVED -> "Destino alcançado"
    NavigationState.Status.NAVIGATING -> when (state.instruction?.maneuver) {
        NavigationInstruction.Maneuver.TURN_LEFT -> "Vire à esquerda"
        NavigationInstruction.Maneuver.TURN_RIGHT -> "Vire à direita"
        NavigationInstruction.Maneuver.U_TURN -> "Faça o retorno"
        NavigationInstruction.Maneuver.ROUNDABOUT -> "Rotatória"
        NavigationInstruction.Maneuver.ARRIVE -> "Chegue ao destino"
        NavigationInstruction.Maneuver.START -> "Inicie a rota"
        else -> "Siga em frente"
    }
    else -> "Navegação"
}

private fun maneuverIcon(maneuver: NavigationInstruction.Maneuver?, status: NavigationState.Status): String {
    if (status == NavigationState.Status.OFF_ROUTE || status == NavigationState.Status.RECALCULATING) return "cmd-map-marker-alert"
    if (status == NavigationState.Status.ARRIVED) return "cmd-map-marker-check"
    return when (maneuver) {
        NavigationInstruction.Maneuver.TURN_LEFT -> "cmd-arrow-left-top"
        NavigationInstruction.Maneuver.TURN_RIGHT -> "cmd-arrow-right-top"
        NavigationInstruction.Maneuver.U_TURN -> "cmd-arrow-u-left-top"
        NavigationInstruction.Maneuver.ROUNDABOUT -> "cmd-rotate-right"
        NavigationInstruction.Maneuver.ARRIVE -> "cmd-map-marker"
        NavigationInstruction.Maneuver.START -> "cmd-navigation-variant"
        else -> "cmd-arrow-up"
    }
}

private fun formatDistance(meters: Double): String {
    if (meters < 1000.0) return "${meters.roundToInt()} m"
    return String.format(Locale("pt", "BR"), "%.1f km", meters / 1000.0)
}
