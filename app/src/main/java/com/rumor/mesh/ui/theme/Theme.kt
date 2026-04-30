package com.rumor.mesh.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Muted, low-light-friendly palette — appropriate for field use
private val DarkColors = darkColorScheme(
    primary          = Color(0xFF4CAF82),
    onPrimary        = Color(0xFF003820),
    primaryContainer = Color(0xFF00522E),
    onPrimaryContainer = Color(0xFF70FBAE),
    secondary        = Color(0xFF8ECDB8),
    onSecondary      = Color(0xFF003829),
    background       = Color(0xFF101410),
    onBackground     = Color(0xFFD8E8DC),
    surface          = Color(0xFF181C18),
    onSurface        = Color(0xFFD8E8DC),
    error            = Color(0xFFCF6679),
    onError          = Color(0xFF640021),
)

private val LightColors = lightColorScheme(
    primary          = Color(0xFF006C42),
    onPrimary        = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF95F7C2),
    onPrimaryContainer = Color(0xFF002112),
    secondary        = Color(0xFF4D7B68),
    onSecondary      = Color(0xFFFFFFFF),
    background       = Color(0xFFF5FAF6),
    onBackground     = Color(0xFF1A1C1A),
    surface          = Color(0xFFF5FAF6),
    onSurface        = Color(0xFF1A1C1A),
)

@Composable
fun RumorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

// Status indicator colours
val OnlineGreen   = Color(0xFF4CAF82)
val RecentlyAmber = Color(0xFFFFC107)
val AwayGrey      = Color(0xFF607060)
