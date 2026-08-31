package br.com.t4acontrol.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

internal object T4AUiTokens {
    val Blue = Color(0xFF075EF0)
    val Red = Color(0xFFE91925)
    val LightBackground = Color(0xFFF7F8FB)
    val DarkBackground = Color(0xFF0D1117)
    val LightForeground = Color(0xFF101820)
    val DarkForeground = Color(0xFFF1F5F9)
    val LightSurface = Color.White
    val DarkSurface = Color(0xFF171D26)
    val LightSurfaceVariant = Color(0xFFF8FAFD)
    val DarkSurfaceVariant = Color(0xFF202836)
    val LightMuted = Color(0xFF667085)
    val DarkMuted = Color(0xFFAAB4C3)
    val LightOutline = Color(0xFFE4E8F0)
    val DarkOutline = Color(0xFF354052)
}

internal fun t4aColorScheme(darkMode: Boolean): ColorScheme = if (darkMode) {
    darkColorScheme(
        primary = T4AUiTokens.Blue,
        onPrimary = Color.White,
        secondary = T4AUiTokens.Blue,
        tertiary = T4AUiTokens.Blue,
        background = T4AUiTokens.DarkBackground,
        onBackground = T4AUiTokens.DarkForeground,
        surface = T4AUiTokens.DarkSurface,
        onSurface = T4AUiTokens.DarkForeground,
        surfaceVariant = T4AUiTokens.DarkSurfaceVariant,
        onSurfaceVariant = T4AUiTokens.DarkMuted,
        outline = T4AUiTokens.DarkOutline,
        error = T4AUiTokens.Red,
        onError = Color.White,
    )
} else {
    lightColorScheme(
        primary = T4AUiTokens.Blue,
        onPrimary = Color.White,
        secondary = T4AUiTokens.Blue,
        tertiary = T4AUiTokens.Blue,
        background = T4AUiTokens.LightBackground,
        onBackground = T4AUiTokens.LightForeground,
        surface = T4AUiTokens.LightSurface,
        onSurface = T4AUiTokens.LightForeground,
        surfaceVariant = T4AUiTokens.LightSurfaceVariant,
        onSurfaceVariant = T4AUiTokens.LightMuted,
        outline = T4AUiTokens.LightOutline,
        error = T4AUiTokens.Red,
        onError = Color.White,
    )
}
