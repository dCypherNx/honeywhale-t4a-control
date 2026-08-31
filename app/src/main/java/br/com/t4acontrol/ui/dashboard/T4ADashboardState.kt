package br.com.t4acontrol.ui.dashboard

import androidx.compose.runtime.Immutable

/**
 * Estado exclusivamente visual do painel principal.
 *
 * Nenhum Composable deve interpretar IDs de DP, valores Tuya ou detalhes do transporte.
 * Toda tradução do estado bruto do T4A deve acontecer antes de chegar aqui.
 */
@Immutable
data class T4ADashboardState(
    val connected: Boolean = false,
    val deviceName: String = "--",
    val rssiDbm: Int? = null,
    val batteryPercent: Int = 0,
    val batteryObservedMin: Int? = null,
    val batteryObservedMax: Int? = null,
    val speed: Int = 0,
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val ridingMode: RidingMode = RidingMode.UNKNOWN,
    val locked: Boolean = false,
    val lightOn: Boolean = false,
    val initialPushOn: Boolean = false,
    val cruiseOn: Boolean = false,
    val autoLockOn: Boolean = false,
    val odometerLabel: String = "Odômetro total",
    val odometerValue: String = "0,0 km",
    val usageTime: String = "00:00:00",
    val controlsEnabled: Boolean = false,
    val lightEnabled: Boolean = false,
    val initialPushEnabled: Boolean = false,
    val cruiseEnabled: Boolean = false,
    val modeEnabled: Boolean = false,
    val lockEnabled: Boolean = false,
)

enum class SpeedUnit(val label: String) {
    KMH("km/h"),
    MPH("mph"),
}

enum class RidingMode(val label: String, val limitKmh: Int) {
    WALK("Caminhada", 6),
    ECO("Eco", 25),
    RACE("Race", 35),
    SPORT("Sport", 45),
    UNKNOWN("--", 0),
}

/** Ações absolutas emitidas pelo painel. A UI nunca envia toggle implícito ao domínio. */
interface T4ADashboardActions {
    fun setRidingMode(mode: RidingMode)
    fun setLight(enabled: Boolean)
    fun setInitialPush(enabled: Boolean)
    fun setCruise(enabled: Boolean)
    fun setLocked(locked: Boolean)
    fun setAutoLock(enabled: Boolean)
    fun toggleOdometer()
}

object NoOpT4ADashboardActions : T4ADashboardActions {
    override fun setRidingMode(mode: RidingMode) = Unit
    override fun setLight(enabled: Boolean) = Unit
    override fun setInitialPush(enabled: Boolean) = Unit
    override fun setCruise(enabled: Boolean) = Unit
    override fun setLocked(locked: Boolean) = Unit
    override fun setAutoLock(enabled: Boolean) = Unit
    override fun toggleOdometer() = Unit
}
