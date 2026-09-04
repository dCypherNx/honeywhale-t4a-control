package br.com.t4acontrol.ui.dashboard

import br.com.t4acontrol.backend.navigation.GeoPoint
import br.com.t4acontrol.backend.navigation.GuidanceEngine
import br.com.t4acontrol.backend.navigation.NavigationInstruction
import br.com.t4acontrol.backend.navigation.NavigationState
import br.com.t4acontrol.backend.navigation.Route
import br.com.t4acontrol.backend.navigation.RouteLeg
import br.com.t4acontrol.backend.navigation.RoutingProfile
import br.com.t4acontrol.backend.navigation.Waypoint
import org.junit.Assert.assertSame
import org.junit.Test

class NavigationGuidanceTimingTest {
    @Test
    fun `active maneuver is not hidden by discarded fixed time horizon`() {
        val route = route()
        val turn = route.legs[0].instructions[0]
        val state = NavigationState.of(
            NavigationState.Status.NAVIGATING,
            route,
            0,
            0,
            turn,
            500.0,
        )

        val slow = GuidanceEngine.present(route, state, true, 5.0, null)
        val fast = GuidanceEngine.present(route, state, true, 45.0, null)

        assertSame(turn, slow.instruction)
        assertSame(turn, fast.instruction)
    }

    private fun route(): Route {
        val origin = Waypoint("o", "origin", 0.0, 0.0, Waypoint.Role.ORIGIN)
        val destination = Waypoint("d", "destination", 0.0, 0.01, Waypoint.Role.DESTINATION)
        val turn = NavigationInstruction(
            "turn",
            NavigationInstruction.Maneuver.TURN_RIGHT,
            "turn",
            0.0,
            0.005,
            500.0,
            500.0,
        )
        val arrive = NavigationInstruction(
            "arrive",
            NavigationInstruction.Maneuver.ARRIVE,
            "arrive",
            0.0,
            0.01,
            1000.0,
            0.0,
        )
        return Route(
            "route",
            "test",
            "ref",
            RoutingProfile.BICYCLE,
            listOf(origin, destination),
            listOf(
                RouteLeg(
                    origin,
                    destination,
                    listOf(turn, arrive),
                    listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01)),
                ),
            ),
        )
    }
}
