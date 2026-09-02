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

private const val GUIDANCE_MIN_SECONDS = 10.0
private const val GUIDANCE_MAX_SECONDS = 30.0
private const val GUIDANCE_MIN_METERS = 15.0
private const val GUIDANCE_MAX_METERS = 75.0
private const val GUIDANCE_DEFAULT_METERS = 30.0

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NavigationCard(
    surface: Color,
    outline: Color,
    foreground: Color,
    muted: Color,
    waypointsVisible: Boolean,
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
    val presentation = timedNavigationPresentation(
        navigationPresentation(route, state, waypointsVisible),
        runtime.guidanceSpeedKmh(),
    )
    val instruction = presentation.instruction
    val debugRouteUrl = if (BuildConfig.DEBUG) plannedRouteDebugUrl(route) else null
    DashboardCard(surface = surface, outline = outline) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NavigationManeuverIcon(
                instruction = instruction,
                status = presentation.status,
                debugRouteUrl = debugRouteUrl,
                context = context,
                onPause = runtime::pause,
                onResume = runtime::resume,
                onEnd = { runtime.end(context) },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = navigationTitle(presentation.status, instruction),
                    color = foreground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                val detail = instruction?.text?.takeIf { it.isNotBlank() }
                if (
                    detail != null &&
                    presentation.status != NavigationState.Status.RECALCULATING &&
                    presentation.status != NavigationState.Status.PAUSED &&
                    instruction.maneuver != NavigationInstruction.Maneuver.WAYPOINT
                ) {
                    Text(detail, color = muted, fontSize = 12.sp, maxLines = 1)
                }
            }
            if (presentation.status != NavigationState.Status.PAUSED) {
                presentation.distanceMeters?.let { distance ->
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
}

private data class NavigationPresentation(
    val status: NavigationState.Status,
    val instruction: NavigationInstruction?,
    val distanceMeters: Double?,
)

private fun navigationPresentation(route: Route?, state: NavigationState, waypointsVisible: Boolean): NavigationPresentation {
    if (state.status == NavigationState.Status.PAUSED || waypointsVisible || route == null) {
        return NavigationPresentation(state.status, state.instruction, state.distanceToInstructionMeters)
    }

    val hidesWaypoint = state.status == NavigationState.Status.WAYPOINT_REACHED ||
        state.instruction?.maneuver == NavigationInstruction.Maneuver.WAYPOINT
    if (!hidesWaypoint) return NavigationPresentation(state.status, state.instruction, state.distanceToInstructionMeters)

    val next = nextVisibleInstruction(route, state.legIndex, state.instructionIndex)
        ?: return NavigationPresentation(NavigationState.Status.NAVIGATING, null, null)
    val baseDistance = state.distanceToInstructionMeters ?: 0.0
    val extraDistance = if (next.instruction.routeOffsetMeters.isFinite()) next.instruction.routeOffsetMeters else 0.0
    return NavigationPresentation(
        NavigationState.Status.NAVIGATING,
        next.instruction,
        (baseDistance + extraDistance).coerceAtLeast(0.0),
    )
}

/**
 * The directional command is activated from GPS speed instead of a fixed distance. Ten seconds is
 * the normal target. At very low speed the time window can grow, up to 30 seconds, to avoid a
 * warning closer than 15 m. At higher speed the command is never exposed farther than 75 m.
 */
private fun timedNavigationPresentation(
    presentation: NavigationPresentation,
    gpsSpeedKmh: Double?,
): NavigationPresentation {
    if (presentation.status != NavigationState.Status.NAVIGATING) return presentation
    val maneuver = presentation.instruction?.maneuver ?: return presentation
    if (!isDirectionalManeuver(maneuver)) return presentation
    val distance = presentation.distanceMeters ?: return presentation
    if (distance <= guidanceActivationMeters(gpsSpeedKmh)) return presentation

    return NavigationPresentation(
        NavigationState.Status.NAVIGATING,
        null,
        distance,
    )
}

internal fun guidanceLeadSeconds(gpsSpeedKmh: Double?): Double {
    if (gpsSpeedKmh == null || !gpsSpeedKmh.isFinite() || gpsSpeedKmh <= 0.0) {
        return GUIDANCE_MIN_SECONDS
    }
    val metersPerSecond = gpsSpeedKmh / 3.6
    val secondsNeededForMinimumDistance = GUIDANCE_MIN_METERS / metersPerSecond
    return secondsNeededForMinimumDistance.coerceIn(GUIDANCE_MIN_SECONDS, GUIDANCE_MAX_SECONDS)
}

internal fun guidanceActivationMeters(gpsSpeedKmh: Double?): Double {
    if (gpsSpeedKmh == null || !gpsSpeedKmh.isFinite() || gpsSpeedKmh <= 1.0) {
        return GUIDANCE_DEFAULT_METERS
    }
    val metersPerSecond = gpsSpeedKmh / 3.6
    return (metersPerSecond * guidanceLeadSeconds(gpsSpeedKmh))
        .coerceIn(GUIDANCE_MIN_METERS, GUIDANCE_MAX_METERS)
}

private fun isDirectionalManeuver(maneuver: NavigationInstruction.Maneuver): Boolean = when (maneuver) {
    NavigationInstruction.Maneuver.TURN_LEFT,
    NavigationInstruction.Maneuver.TURN_RIGHT,
    NavigationInstruction.Maneuver.U_TURN,
    NavigationInstruction.Maneuver.ROUNDABOUT -> true
    else -> false
}

private data class VisibleInstruction(val instruction: NavigationInstruction)

private fun nextVisibleInstruction(route: Route, legIndex: Int, instructionIndex: Int): VisibleInstruction? {
    for (leg in legIndex.coerceAtLeast(0) until route.legs.size) {
        val instructions = route.legs[leg].instructions
        val start = if (leg == legIndex) (instructionIndex + 1).coerceAtLeast(0) else 0
        for (index in start until instructions.size) {
            val instruction = instructions[index]
            if (
                instruction.maneuver != NavigationInstruction.Maneuver.WAYPOINT &&
                instruction.maneuver != NavigationInstruction.Maneuver.START
            ) return VisibleInstruction(instruction)
        }
    }
    return null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavigationManeuverIcon(
    instruction: NavigationInstruction?,
    status: NavigationState.Status,
    debugRouteUrl: String?,
    context: Context,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEnd: () -> Unit,
) {
    var actionMenuExpanded by remember { mutableStateOf(false) }
    var debugMenuExpanded by remember { mutableStateOf(false) }
    val interactionModifier = Modifier
        .size(48.dp)
        .combinedClickable(
            onClick = { actionMenuExpanded = true },
            onLongClick = {
                if (debugRouteUrl != null) debugMenuExpanded = true
                else actionMenuExpanded = true
            },
        )

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
        DropdownMenu(
            expanded = actionMenuExpanded,
            onDismissRequest = { actionMenuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(if (status == NavigationState.Status.PAUSED) "Retomar navegação" else "Pausar navegação") },
                onClick = {
                    actionMenuExpanded = false
                    if (status == NavigationState.Status.PAUSED) onResume() else onPause()
                },
            )
            DropdownMenuItem(
                text = { Text("Encerrar navegação") },
                onClick = {
                    actionMenuExpanded = false
                    onEnd()
                },
            )
        }
        if (debugRouteUrl != null) {
            DropdownMenu(
                expanded = debugMenuExpanded,
                onDismissRequest = { debugMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Abrir rota planejada") },
                    onClick = {
                        debugMenuExpanded = false
                        openPlannedRoute(context, debugRouteUrl)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Copiar link da rota") },
                    onClick = {
                        debugMenuExpanded = false
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

private fun plannedRouteDebugUrl(route: Route?): String? {
    if (route == null || route.source != "osrm") return null
    val points = route.legs
        .flatMap { it.geometry }
        .fold(mutableListOf<br.com.t4acontrol.backend.navigation.GeoPoint>()) { result, point ->
            val last = result.lastOrNull()
            if (last == null || last.latitude != point.latitude || last.longitude != point.longitude) result.add(point)
            result
        }
    if (points.size < 2) return null
    val coordinates = points.joinToString(separator = ",") { point -> "[${point.longitude},${point.latitude}]" }
    val geoJson = "{\"type\":\"LineString\",\"coordinates\":[$coordinates]}"
    return "https://geojson.io/#data=data:application/json,${Uri.encode(geoJson)}"
}

private fun navigationTitle(status: NavigationState.Status, instruction: NavigationInstruction?): String = when (status) {
    NavigationState.Status.READY -> "Rota pronta"
    NavigationState.Status.PAUSED -> "Navegação pausada"
    NavigationState.Status.POSITION_UNAVAILABLE -> "Aguardando GPS"
    NavigationState.Status.OFF_ROUTE -> "Recalculando rota…"
    NavigationState.Status.RECALCULATING -> "Recalculando rota…"
    NavigationState.Status.WAYPOINT_REACHED -> "Parada alcançada"
    NavigationState.Status.ARRIVED -> "Destino alcançado"
    NavigationState.Status.NAVIGATING -> when (instruction?.maneuver) {
        NavigationInstruction.Maneuver.TURN_LEFT -> "Vire à esquerda"
        NavigationInstruction.Maneuver.TURN_RIGHT -> "Vire à direita"
        NavigationInstruction.Maneuver.U_TURN -> "Faça o retorno"
        NavigationInstruction.Maneuver.ROUNDABOUT -> "Rotatória"
        NavigationInstruction.Maneuver.WAYPOINT -> "Chegue à parada"
        NavigationInstruction.Maneuver.ARRIVE -> "Chegue ao destino"
        NavigationInstruction.Maneuver.START -> "Inicie a rota"
        else -> "Siga em frente"
    }
    else -> "Navegação"
}

private fun maneuverIcon(maneuver: NavigationInstruction.Maneuver?, status: NavigationState.Status): String {
    if (status == NavigationState.Status.OFF_ROUTE || status == NavigationState.Status.RECALCULATING) return "cmd-map-marker-alert"
    if (status == NavigationState.Status.PAUSED) return "cmd-pause-circle-outline"
    if (status == NavigationState.Status.ARRIVED) return "cmd-map-marker-check"
    return when (maneuver) {
        NavigationInstruction.Maneuver.TURN_LEFT -> "cmd-arrow-left-top"
        NavigationInstruction.Maneuver.TURN_RIGHT -> "cmd-arrow-right-top"
        NavigationInstruction.Maneuver.U_TURN -> "cmd-arrow-u-left-top"
        NavigationInstruction.Maneuver.ROUNDABOUT -> "cmd-rotate-right"
        NavigationInstruction.Maneuver.WAYPOINT -> "cmd-map-marker-radius"
        NavigationInstruction.Maneuver.ARRIVE -> "cmd-map-marker"
        NavigationInstruction.Maneuver.START -> "cmd-navigation-variant"
        else -> "cmd-arrow-up"
    }
}

private fun formatDistance(meters: Double): String {
    if (meters < 1000.0) return "${meters.roundToInt()} m"
    return String.format(Locale("pt", "BR"), "%.1f km", meters / 1000.0)
}
