package br.com.t4acontrol.ui.dashboard

import br.com.t4acontrol.backend.navigation.NavigationInstruction
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationGuidanceTimingTest {
    @Test
    fun `normal turn keeps at least ten second projected lead at scooter speeds`() {
        assertEquals(
            76.389,
            guidanceActivationMeters(30.0, NavigationInstruction.Maneuver.TURN_LEFT),
            0.01,
        )
        assertEquals(
            97.222,
            guidanceActivationMeters(45.0, NavigationInstruction.Maneuver.TURN_RIGHT),
            0.01,
        )
    }

    @Test
    fun `roundabout anticipates stronger speed reduction without exceeding one hundred meters`() {
        assertEquals(
            75.0,
            guidanceActivationMeters(30.0, NavigationInstruction.Maneuver.ROUNDABOUT),
            0.01,
        )
        assertEquals(
            100.0,
            guidanceActivationMeters(45.0, NavigationInstruction.Maneuver.ROUNDABOUT),
            0.01,
        )
    }

    @Test
    fun `u turn models near stop and fifteen second lead`() {
        assertEquals(
            62.5,
            guidanceActivationMeters(30.0, NavigationInstruction.Maneuver.U_TURN),
            0.01,
        )
        assertEquals(
            93.75,
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
