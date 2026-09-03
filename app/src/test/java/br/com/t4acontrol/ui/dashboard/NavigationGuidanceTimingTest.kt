package br.com.t4acontrol.ui.dashboard

import br.com.t4acontrol.backend.navigation.NavigationInstruction
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationGuidanceTimingTest {
    @Test
    fun `normal turn keeps a real ten second minimum lead at current speed`() {
        assertEquals(
            83.333,
            guidanceActivationMeters(30.0, NavigationInstruction.Maneuver.TURN_LEFT),
            0.01,
        )
        assertEquals(
            111.111,
            guidanceActivationMeters(40.0, NavigationInstruction.Maneuver.TURN_RIGHT),
            0.01,
        )
        assertEquals(
            125.0,
            guidanceActivationMeters(45.0, NavigationInstruction.Maneuver.TURN_RIGHT),
            0.01,
        )
    }

    @Test
    fun `roundabout uses twelve seconds without a fixed distance ceiling`() {
        assertEquals(
            100.0,
            guidanceActivationMeters(30.0, NavigationInstruction.Maneuver.ROUNDABOUT),
            0.01,
        )
        assertEquals(
            150.0,
            guidanceActivationMeters(45.0, NavigationInstruction.Maneuver.ROUNDABOUT),
            0.01,
        )
    }

    @Test
    fun `u turn keeps fifteen second minimum lead without a fixed distance ceiling`() {
        assertEquals(
            125.0,
            guidanceActivationMeters(30.0, NavigationInstruction.Maneuver.U_TURN),
            0.01,
        )
        assertEquals(
            187.5,
            guidanceActivationMeters(45.0, NavigationInstruction.Maneuver.U_TURN),
            0.01,
        )
    }

    @Test
    fun `unknown speed uses conservative fallback`() {
        assertEquals(
            30.0,
            guidanceActivationMeters(null, NavigationInstruction.Maneuver.TURN_LEFT),
            0.001,
        )
    }
}
