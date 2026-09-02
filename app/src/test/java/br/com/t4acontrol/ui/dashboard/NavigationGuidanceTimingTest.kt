package br.com.t4acontrol.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationGuidanceTimingTest {
    @Test
    fun `guidance window scales with speed and remains bounded`() {
        assertEquals(70.0, guidanceActivationMeters(null), 0.001)
        assertEquals(45.0, guidanceActivationMeters(5.0), 0.001)
        assertEquals(83.333, guidanceActivationMeters(30.0), 0.01)
        assertEquals(125.0, guidanceActivationMeters(45.0), 0.001)
        assertEquals(140.0, guidanceActivationMeters(80.0), 0.001)
    }
}
