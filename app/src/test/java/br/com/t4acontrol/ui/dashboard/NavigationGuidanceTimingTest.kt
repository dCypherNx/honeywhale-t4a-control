package br.com.t4acontrol.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationGuidanceTimingTest {
    @Test
    fun `guidance window respects time and distance bounds`() {
        assertEquals(30.0, guidanceActivationMeters(null), 0.001)

        assertEquals(27.0, guidanceLeadSeconds(2.0), 0.001)
        assertEquals(15.0, guidanceActivationMeters(2.0), 0.001)

        assertEquals(10.8, guidanceLeadSeconds(5.0), 0.001)
        assertEquals(15.0, guidanceActivationMeters(5.0), 0.001)

        assertEquals(10.0, guidanceLeadSeconds(10.0), 0.001)
        assertEquals(27.778, guidanceActivationMeters(10.0), 0.01)

        assertEquals(10.0, guidanceLeadSeconds(30.0), 0.001)
        assertEquals(75.0, guidanceActivationMeters(30.0), 0.001)

        assertEquals(10.0, guidanceLeadSeconds(45.0), 0.001)
        assertEquals(75.0, guidanceActivationMeters(45.0), 0.001)
    }
}
