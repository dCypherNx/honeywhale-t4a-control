package br.com.t4acontrol.ui.dashboard

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.t4acontrol.R

@Composable
internal fun RidingControls(state: T4ADashboardState, surface: Color, outline: Color, foreground: Color, muted: Color, darkMode: Boolean, actions: T4ADashboardActions) {
    var lightMenuExpanded by remember { mutableStateOf(false) }

    DashboardCard(surface, outline) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(RidingMode.WALK, RidingMode.ECO, RidingMode.RACE, RidingMode.SPORT).forEach { mode ->
                ModeTile(mode, state.ridingMode == mode, state.modeEnabled, surface, muted, darkMode) { actions.setRidingMode(mode) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) {
                ToggleTile(
                    stringResource(R.string.light),
                    stringResource(if (state.lightOn) R.string.state_on else R.string.state_off),
                    "cmd-car-light-dimmed",
                    state.lightOn,
                    T4ADashboardTokens.Orange,
                    state.lightEnabled,
                    surface,
                    muted,
                    darkMode,
                    Modifier.fillMaxWidth(),
                    automaticBadge = state.autoLightOn,
                    onLongClick = { lightMenuExpanded = true },
                ) { actions.setLight(!state.lightOn) }
                DropdownMenu(expanded = lightMenuExpanded, onDismissRequest = { lightMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.auto_light_turn_on), color = T4ADashboardTokens.Orange) },
                        onClick = { lightMenuExpanded = false; actions.setLight(true) },
                        enabled = state.lightEnabled,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.auto_light_turn_off)) },
                        onClick = { lightMenuExpanded = false; actions.setLight(false) },
                        enabled = state.lightEnabled,
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (state.autoLightOn) R.string.auto_light_disable_automatic
                                    else R.string.auto_light_enable_automatic,
                                ),
                                color = T4ADashboardTokens.Blue,
                            )
                        },
                        onClick = {
                            lightMenuExpanded = false
                            actions.setAutoLightEnabled(!state.autoLightOn)
                        },
                        enabled = state.controlsEnabled || state.autoLightOn,
                    )
                }
            }
            ToggleTile(stringResource(R.string.start_mode), stringResource(if (state.initialPushOn) R.string.state_kick else R.string.state_zero), "cmd-run", state.initialPushOn, T4ADashboardTokens.Green, state.initialPushEnabled, surface, muted, darkMode, Modifier.weight(1f)) { actions.setInitialPush(!state.initialPushOn) }
            ToggleTile(stringResource(R.string.cruise), stringResource(if (state.cruiseOn) R.string.state_on else R.string.state_off), "cmd-car-cruise-control", state.cruiseOn, T4ADashboardTokens.Blue, state.cruiseEnabled, surface, muted, darkMode, Modifier.weight(1f), iconOffsetX = (-3).dp) { actions.setCruise(!state.cruiseOn) }
            ConsolidatedLockControl(state, surface, muted, darkMode, actions, Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsolidatedLockControl(state: T4ADashboardState, surface: Color, muted: Color, darkMode: Boolean, actions: T4ADashboardActions, modifier: Modifier = Modifier) {
    var menuExpanded by remember { mutableStateOf(false) }
    val enabled = state.controlsEnabled && (state.autoLockOn || state.lockEnabled)
    val color = when {
        state.autoLockOn -> T4ADashboardTokens.Blue
        state.locked -> T4ADashboardTokens.Red
        else -> T4ADashboardTokens.Green
    }
    val lockColor = if (state.locked) T4ADashboardTokens.Red else T4ADashboardTokens.Green
    val stateLabel = when {
        state.autoLockOn -> stringResource(R.string.automatic)
        state.locked -> stringResource(R.string.state_on)
        else -> stringResource(R.string.state_off)
    }
    val fill = color.copy(alpha = if (darkMode) 0.19f else 0.07f)

    Box(modifier.height(T4ADashboardTokens.ToggleHeight)) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(T4ADashboardTokens.ToggleHeight)
                .alpha(if (enabled) 1f else 0.55f)
                .background(fill, RoundedCornerShape(T4ADashboardTokens.ToggleRadius))
                .border(2.dp, color, RoundedCornerShape(T4ADashboardTokens.ToggleRadius))
                .combinedClickable(
                    enabled = enabled,
                    onClick = { if (!state.autoLockOn && state.lockEnabled) actions.setLocked(!state.locked) },
                    onLongClick = { menuExpanded = true },
                )
                .padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (state.autoLockOn) {
                AutoLockIcon(state.locked, if (enabled) lockColor else muted, if (enabled) T4ADashboardTokens.Blue else muted, T4ADashboardTokens.SecondRowIconSize, Modifier.size(40.dp), 8.dp)
            } else {
                StableLockIcon(state.locked, if (enabled) lockColor else muted, Modifier.size(40.dp), T4ADashboardTokens.SecondRowIconSize)
            }
            ControlText(stringResource(R.string.lock_control), stateLabel, if (enabled) color else foregroundForInactive(darkMode), true)
        }
        if (state.autoLockOn) {
            AutomaticBadge(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 4.dp),
            )
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.lock), color = T4ADashboardTokens.Red) }, onClick = { menuExpanded = false; actions.setAutoLock(false); actions.setLocked(true) }, enabled = state.lockEnabled)
            DropdownMenuItem(text = { Text(stringResource(R.string.unlock), color = T4ADashboardTokens.Green) }, onClick = { menuExpanded = false; actions.setAutoLock(false); actions.setLocked(false) }, enabled = state.lockEnabled)
            DropdownMenuItem(text = { Text(stringResource(R.string.automatic_lock), color = T4ADashboardTokens.Blue) }, onClick = { menuExpanded = false; actions.setAutoLock(true) }, enabled = state.controlsEnabled)
        }
    }
}

@Composable
private fun RowScope.ModeTile(mode: RidingMode, active: Boolean, enabled: Boolean, surface: Color, muted: Color, darkMode: Boolean, onClick: () -> Unit) {
    val color = modeColor(mode)
    val fill = if (active) color.copy(alpha = if (darkMode) 0.19f else 0.07f) else surface
    val iconSize = when (mode) {
        RidingMode.WALK -> 30.dp
        RidingMode.ECO -> 30.dp
        RidingMode.RACE -> 29.dp
        RidingMode.SPORT -> 31.dp
        RidingMode.UNKNOWN -> 28.dp
    }
    Column(
        Modifier
            .weight(1f)
            .height(T4ADashboardTokens.ModeHeight)
            .alpha(if (enabled) 1f else 0.55f)
            .background(fill, RoundedCornerShape(T4ADashboardTokens.ModeRadius))
            .border(if (active) 2.dp else 1.dp, color, RoundedCornerShape(T4ADashboardTokens.ModeRadius))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            MdiIcon(modeIcon(mode), if (active) color else color.copy(alpha = 0.68f), iconSize, Modifier.size(36.dp))
        }
        Text(stringResource(modeLabelRes(mode)), color = if (active) color else color.copy(alpha = 0.68f), fontSize = T4ADashboardTokens.ModeFontSize, fontWeight = FontWeight.Bold, lineHeight = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.speed_limit, "", mode.limitKmh).substringAfter('\n'), color = if (active) color else muted, fontSize = T4ADashboardTokens.ModeSpeedFontSize, lineHeight = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@StringRes
private fun modeLabelRes(mode: RidingMode): Int = when (mode) {
    RidingMode.WALK -> R.string.mode_walk
    RidingMode.ECO -> R.string.mode_eco
    RidingMode.RACE -> R.string.mode_race
    RidingMode.SPORT -> R.string.mode_sport
    RidingMode.UNKNOWN -> R.string.unknown
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToggleTile(
    title: String,
    stateLabel: String,
    icon: String,
    active: Boolean,
    color: Color,
    enabled: Boolean,
    surface: Color,
    muted: Color,
    darkMode: Boolean,
    modifier: Modifier = Modifier,
    actionHeight: Dp = T4ADashboardTokens.ToggleHeight,
    radius: Dp = T4ADashboardTokens.ToggleRadius,
    iconSize: Dp = T4ADashboardTokens.SecondRowIconSize,
    iconOffsetX: Dp = 0.dp,
    automaticBadge: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val fill = if (active) color.copy(alpha = if (darkMode) 0.19f else 0.07f) else surface
    val clickModifier = if (onLongClick == null) {
        Modifier.clickable(enabled = enabled, onClick = onClick)
    } else {
        Modifier.combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
    }
    Box(modifier.height(actionHeight)) {
        Column(
            Modifier
                .fillMaxSize()
                .alpha(if (enabled) 1f else 0.55f)
                .background(fill, RoundedCornerShape(radius))
                .border(if (active) 2.dp else 1.dp, color, RoundedCornerShape(radius))
                .then(clickModifier)
                .padding(horizontal = 5.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            MdiIcon(icon, if (active) color else muted, iconSize, Modifier.offset(x = iconOffsetX).size(if (actionHeight == T4ADashboardTokens.ActionHeight) 34.dp else 40.dp))
            ControlText(title, stateLabel, if (active) color else foregroundForInactive(darkMode), active, if (actionHeight == T4ADashboardTokens.ActionHeight) T4ADashboardTokens.ActionFontSize else T4ADashboardTokens.ControlFontSize)
        }
        if (automaticBadge) {
            AutomaticBadge(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-4).dp, y = 4.dp),
            )
        }
    }
}

@Composable
private fun AutomaticBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(16.dp)
            .background(T4ADashboardTokens.Blue, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("A", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp)
    }
}

@Composable
private fun ControlText(title: String, state: String, color: Color, bold: Boolean, fontSize: androidx.compose.ui.unit.TextUnit = T4ADashboardTokens.ControlFontSize) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Text(title, color = color, fontSize = fontSize, fontWeight = weight, lineHeight = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Text(state, color = color, fontSize = fontSize, fontWeight = weight, lineHeight = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}
