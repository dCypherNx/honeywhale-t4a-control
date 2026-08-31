package br.com.t4acontrol.ui.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.t4acontrol.R

@Composable
internal fun MetricsCard(state: T4ADashboardState, surface: Color, outline: Color, foreground: Color, muted: Color, actions: T4ADashboardActions) {
    val partial = state.odometerLabel.equals("Percurso", ignoreCase = true) || state.odometerLabel.contains("parcial", ignoreCase = true)
    DashboardCard(surface, outline) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MetricWithIcon(
                if (partial) "cmd-map-marker-distance" else "cmd-speedometer",
                localizedMetricLabel(if (partial) R.string.trip_odometer else R.string.total_odometer),
                state.odometerValue,
                foreground,
                muted,
                Modifier.weight(1f).clickable(onClick = actions::toggleOdometer),
            )
            Box(Modifier.width(1.dp).height(44.dp).background(outline))
            MetricWithIcon(
                "cmd-clock-outline",
                localizedMetricLabel(R.string.usage_time),
                state.usageTime,
                foreground,
                muted,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun localizedMetricLabel(@StringRes id: Int): String = stringResource(id, "").substringBefore('\n').trim()

@Composable
private fun MetricWithIcon(icon: String, label: String, value: String, foreground: Color, muted: Color, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        MdiIcon(icon, T4ADashboardTokens.Blue, 23.dp, Modifier.size(28.dp))
        Column(Modifier.padding(start = 7.dp)) {
            Text(label, color = muted, fontSize = 10.sp)
            Text(value, color = if (value.isBlank()) muted else foreground, fontSize = T4ADashboardTokens.MetricFontSize)
        }
    }
}
