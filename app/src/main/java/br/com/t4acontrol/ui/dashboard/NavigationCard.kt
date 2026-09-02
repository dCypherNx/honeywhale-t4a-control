package br.com.t4acontrol.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.t4acontrol.backend.navigation.NavigationInstruction
import br.com.t4acontrol.backend.navigation.NavigationState
import br.com.t4acontrol.ui.MdiIcon
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun NavigationCard(
    state: NavigationState,
    surface: Color,
    outline: Color,
    foreground: Color,
    muted: Color,
) {
    if (state.status == NavigationState.Status.NO_ROUTE) return
    val instruction = state.instruction
    DashboardCard(surface = surface, outline = outline) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MdiIcon(
                name = maneuverIcon(instruction?.maneuver, state.status),
                color = if (state.status == NavigationState.Status.OFF_ROUTE) T4ADashboardTokens.Red else T4ADashboardTokens.Blue,
                iconSize = 40.dp,
                modifier = Modifier.size(48.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = navigationTitle(state),
                    color = foreground,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                val detail = instruction?.text?.takeIf { it.isNotBlank() }
                if (detail != null) {
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

private fun navigationTitle(state: NavigationState): String = when (state.status) {
    NavigationState.Status.READY -> "Rota pronta"
    NavigationState.Status.POSITION_UNAVAILABLE -> "Aguardando GPS"
    NavigationState.Status.OFF_ROUTE -> "Fora da rota"
    NavigationState.Status.WAYPOINT_REACHED -> "Ponto intermediário"
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

private fun maneuverIcon(
    maneuver: NavigationInstruction.Maneuver?,
    status: NavigationState.Status,
): String {
    if (status == NavigationState.Status.OFF_ROUTE) return "cmd-map-marker-alert"
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
