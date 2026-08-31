package br.com.t4acontrol.ui.dashboard

import br.com.t4acontrol.backend.T4AState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class T4ADashboardMapperTest {
    @Test
    fun mapsCompleteConnectedState() {
        Locale.setDefault(Locale.US)
        val state = state(
            connected = true,
            rssi = -61,
            dps = mapOf(
                "1" to false,
                "2" to 327,
                "3" to 78,
                "5" to 124,
                "6" to 3661,
                "8" to true,
                "11" to "km",
                "12" to 12345,
                "13" to "1",
                "14" to "level_2",
                "16" to "not_zero_start",
            ),
            autoLock = true,
            batteryMin = 31,
            batteryMax = 92,
        )

        val mapped = T4ADashboardMapper.map(state, showTotalOdometer = true)

        assertTrue(mapped.connected)
        assertEquals("T4A Test", mapped.deviceName)
        assertEquals(-61, mapped.rssiDbm)
        assertEquals(78, mapped.batteryPercent)
        assertEquals(31, mapped.batteryObservedMin)
        assertEquals(92, mapped.batteryObservedMax)
        assertEquals(33, mapped.speed)
        assertEquals(SpeedUnit.KMH, mapped.speedUnit)
        assertEquals(RidingMode.RACE, mapped.ridingMode)
        assertTrue(mapped.locked)
        assertTrue(mapped.lightOn)
        assertTrue(mapped.initialPushOn)
        assertTrue(mapped.cruiseOn)
        assertTrue(mapped.autoLockOn)
        assertEquals("Odômetro total", mapped.odometerLabel)
        assertEquals("1234.5 km", mapped.odometerValue)
        assertEquals("01:01:01", mapped.usageTime)
        assertTrue(mapped.controlsEnabled)
    }

    @Test
    fun mapsMilesTripAndPendingControls() {
        Locale.setDefault(Locale.US)
        val state = state(
            connected = true,
            dps = mapOf("2" to 100, "5" to 25, "11" to "mile", "14" to "level_1"),
            pending = setOf("8", "13", "14", "16", "1"),
        )

        val mapped = T4ADashboardMapper.map(state, showTotalOdometer = false)

        assertEquals(6, mapped.speed)
        assertEquals(SpeedUnit.MPH, mapped.speedUnit)
        assertEquals(RidingMode.ECO, mapped.ridingMode)
        assertEquals("Percurso", mapped.odometerLabel)
        assertEquals("2.5 km", mapped.odometerValue)
        assertFalse(mapped.lightEnabled)
        assertFalse(mapped.cruiseEnabled)
        assertFalse(mapped.modeEnabled)
        assertFalse(mapped.initialPushEnabled)
        assertFalse(mapped.lockEnabled)
    }

    @Test
    fun missingAndInvalidValuesMapToSafeDefaults() {
        val mapped = T4ADashboardMapper.map(
            state(connected = false, deviceName = "", rssi = 0, dps = mapOf("3" to 999, "6" to -5, "14" to "invalid")),
            showTotalOdometer = true,
        )

        assertEquals("--", mapped.deviceName)
        assertNull(mapped.rssiDbm)
        assertEquals(100, mapped.batteryPercent)
        assertEquals(0, mapped.speed)
        assertEquals(RidingMode.UNKNOWN, mapped.ridingMode)
        assertEquals("00:00:00", mapped.usageTime)
        assertFalse(mapped.controlsEnabled)
        assertFalse(mapped.lightEnabled)
    }

    private fun state(
        connected: Boolean,
        deviceName: String = "T4A Test",
        rssi: Int = -70,
        dps: Map<String, Any> = emptyMap(),
        pending: Set<String> = emptySet(),
        autoLock: Boolean = false,
        batteryMin: Int? = null,
        batteryMax: Int? = null,
    ) = T4AState(
        true,
        "account",
        1L,
        "home",
        T4AState.Pairing.PAIRED,
        connected,
        deviceName,
        "AA:BB:CC:DD:EE:FF",
        rssi,
        dps,
        emptyMap(),
        pending,
        autoLock,
        "medium",
        batteryMin,
        batteryMax,
        null,
        1,
        null,
        null,
        "",
    )
}
