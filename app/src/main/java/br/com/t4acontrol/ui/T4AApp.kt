package br.com.t4acontrol.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.t4acontrol.R
import br.com.t4acontrol.backend.T4AState
import br.com.t4acontrol.ui.dashboard.T4ADashboard
import br.com.t4acontrol.ui.dashboard.T4ADashboardMapper
import br.com.t4acontrol.ui.diagnostics.DashboardEvents
import br.com.t4acontrol.ui.diagnostics.RawLogCard
import br.com.t4acontrol.ui.login.LoginScreen
import br.com.t4acontrol.ui.pairing.PairingScreen
import br.com.t4acontrol.ui.settings.SettingsScreen
import java.util.Locale

@Composable
internal fun T4AApp(
    current: T4AState?,
    settings: Boolean,
    showTotalOdometer: Boolean,
    keepScreenOn: Boolean,
    themeMode: String,
    eventHistory: List<String>,
    rawHistory: List<String>,
    rawLogFilter: String,
    darkMode: Boolean,
    actions: T4AUiActions,
) {
    val background = if (darkMode) T4AUiTokens.DarkBackground else T4AUiTokens.LightBackground
    val foreground = if (darkMode) T4AUiTokens.DarkForeground else T4AUiTokens.LightForeground
    MaterialTheme(colorScheme = t4aColorScheme(darkMode)) {
        BackHandler(enabled = settings) { actions.setSettingsVisible(false) }
        Box(
            Modifier
                .fillMaxSize()
                .background(background)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Header(settings, foreground, actions)
                if (current == null) {
                    Text(stringResource(R.string.session_initializing), color = foreground)
                    return@Column
                }
                if (!current.authenticated) {
                    LoginScreen(foreground, actions::login)
                    return@Column
                }
                if (current.pairing != T4AState.Pairing.PAIRED) {
                    Text(current.message, color = foreground)
                    PairingScreen(current, foreground, actions::pair, actions::scan)
                    DashboardEvents(eventHistory, foreground, actions)
                    return@Column
                }
                if (settings) {
                    SettingsScreen(
                        current = current,
                        foreground = foreground,
                        keepScreenOn = keepScreenOn,
                        themeMode = themeMode,
                        actions = actions,
                    )
                    RawLogCard(rawHistory, rawLogFilter, foreground, actions)
                } else {
                    T4ADashboard(
                        state = T4ADashboardMapper.map(current, showTotalOdometer),
                        actions = actions,
                        darkMode = darkMode,
                    )
                    DashboardEvents(eventHistory, foreground, actions)
                }
            }
        }
    }
}

@Composable
private fun Header(settings: Boolean, foreground: Color, actions: T4AUiActions) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (settings) stringResource(R.string.settings) else stringResource(R.string.app_name).uppercase(Locale.ROOT),
            color = foreground,
            fontSize = if (settings) 26.sp else 25.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier.size(40.dp).clickable { actions.setSettingsVisible(!settings) },
            contentAlignment = Alignment.Center,
        ) {
            MdiIcon(
                if (settings) "cmd-arrow-left" else "cmd-cog",
                T4AUiTokens.Blue,
                if (settings) 28.dp else 27.dp,
                Modifier.size(32.dp),
            )
        }
    }
}
