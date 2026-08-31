package br.com.t4acontrol.ui.dashboard

import br.com.t4acontrol.backend.T4AState
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Único tradutor entre o snapshot de domínio e o estado visual do dashboard.
 *
 * IDs de DP ficam confinados aqui (e futuramente no controller de ações), nunca nos Composables.
 */
object T4ADashboardMapper {
    const val DP_LOCK = "1"
    const val DP_SPEED = "2"
    const val DP_BATTERY = "3"
    const val DP_TRIP = "5"
    const val DP_TIME = "6"
    const val DP_LIGHT = "8"
    const val DP_UNIT = "11"
    const val DP_TOTAL = "12"
    const val DP_CRUISE = "13"
    const val DP_LEVEL = "14"
    const val DP_START = "16"

    @JvmStatic
    fun map(state: T4AState, showTotalOdometer: Boolean): T4ADashboardState {
        val unit = if (state.dps[DP_UNIT]?.toString() == "mile") SpeedUnit.MPH else SpeedUnit.KMH
        val speedKmh = scaled(state.dps[DP_SPEED], 10.0)
        val displayedSpeed = if (unit == SpeedUnit.MPH) speedKmh * 0.621371 else speedKmh
        val odometerDp = if (showTotalOdometer) DP_TOTAL else DP_TRIP

        return T4ADashboardState(
            connected = state.connected,
            deviceName = state.deviceName.ifBlank { "--" },
            rssiDbm = state.rssi.takeUnless { it == 0 },
            batteryPercent = integerOrNull(state.dps[DP_BATTERY])?.coerceIn(0, 100),
            batteryObservedMin = state.batteryObservedMin?.coerceIn(0, 100),
            batteryObservedMax = state.batteryObservedMax?.coerceIn(0, 100),
            speed = displayedSpeed.roundToInt().coerceAtLeast(0),
            speedUnit = unit,
            ridingMode = ridingMode(state.dps[DP_LEVEL]),
            locked = !truth(state.dps[DP_LOCK]),
            lightOn = truth(state.dps[DP_LIGHT]),
            initialPushOn = state.dps[DP_START]?.toString() == "not_zero_start",
            cruiseOn = truth(state.dps[DP_CRUISE]),
            autoLockOn = state.autoLockEnabled,
            odometerLabel = if (showTotalOdometer) "Odômetro total" else "Percurso",
            odometerValue = String.format(Locale.getDefault(), "%.1f km", scaled(state.dps[odometerDp], 10.0)),
            usageTime = duration(integer(state.dps[DP_TIME])),
            controlsEnabled = state.connected,
            lightEnabled = state.connected && DP_LIGHT !in state.pendingDps,
            initialPushEnabled = state.connected && DP_START !in state.pendingDps,
            cruiseEnabled = state.connected && DP_CRUISE !in state.pendingDps,
            modeEnabled = state.connected && DP_LEVEL !in state.pendingDps,
            lockEnabled = state.connected && DP_LOCK !in state.pendingDps,
        )
    }

    private fun ridingMode(value: Any?): RidingMode = when (value?.toString()) {
        "level_0" -> RidingMode.WALK
        "level_1" -> RidingMode.ECO
        "level_2" -> RidingMode.RACE
        "level_3" -> RidingMode.SPORT
        else -> RidingMode.UNKNOWN
    }

    private fun truth(value: Any?): Boolean =
        value == true || value?.toString()?.equals("true", ignoreCase = true) == true || value?.toString() == "1"

    private fun integer(value: Any?): Int = integerOrNull(value) ?: 0

    private fun integerOrNull(value: Any?): Int? = value?.toString()?.toIntOrNull()

    private fun scaled(value: Any?, divisor: Double): Double =
        (value?.toString()?.toDoubleOrNull() ?: 0.0) / divisor

    private fun duration(seconds: Int): String {
        val safe = seconds.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d", safe / 3600, (safe % 3600) / 60, safe % 60)
    }
}
