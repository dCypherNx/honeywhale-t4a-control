package br.com.t4acontrol.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
fun T4ADashboard(state: T4ADashboardState, actions: T4ADashboardActions, darkMode: Boolean, modifier: Modifier = Modifier) {
    val background = if (darkMode) T4ADashboardTokens.DarkBackground else T4ADashboardTokens.LightBackground
    val surface = if (darkMode) T4ADashboardTokens.DarkSurface else T4ADashboardTokens.LightSurface
    val outline = if (darkMode) T4ADashboardTokens.DarkOutline else T4ADashboardTokens.LightOutline
    val foreground = if (darkMode) T4ADashboardTokens.DarkForeground else T4ADashboardTokens.LightForeground
    val muted = if (darkMode) T4ADashboardTokens.DarkMuted else T4ADashboardTokens.LightMuted
    Column(modifier.fillMaxWidth().background(background), verticalArrangement = Arrangement.spacedBy(T4ADashboardTokens.CardSpacing)) {
        ConnectionCard(state, surface, outline, foreground, muted)
        SpeedCard(state, surface, outline, foreground, darkMode)
        MetricsCard(state, surface, outline, foreground, muted, actions)
        RidingControls(state, surface, outline, foreground, muted, darkMode, actions)
    }
}

@Preview(showBackground = true, widthDp = 412)
@Composable
private fun DashboardPreview() {
    T4ADashboard(
        T4ADashboardState(
            connected = true,
            deviceName = "T4A",
            rssiDbm = -48,
            batteryPercent = 76,
            batteryObservedMin = 48,
            batteryObservedMax = 100,
            speed = 23,
            speedUnit = SpeedUnit.KMH,
            ridingMode = RidingMode.ECO,
            locked = false,
            lightOn = true,
            initialPushOn = true,
            cruiseOn = false,
            autoLockOn = true,
            odometerLabel = "Odômetro total",
            odometerValue = "128,4 km",
            usageTime = "01:42:18",
            controlsEnabled = true,
            lightEnabled = true,
            initialPushEnabled = true,
            cruiseEnabled = true,
            modeEnabled = true,
            lockEnabled = true,
        ),
        NoOpT4ADashboardActions,
        false,
        Modifier.padding(16.dp),
    )
}
