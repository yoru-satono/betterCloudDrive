package com.betterclouddrive.mobile.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.betterclouddrive.mobile.core.platform.ThemeMode

private val Accent = Color(0xFF00D4AA)
private val BackgroundDark = Color(0xFF0A0E17)
private val SurfaceDark = Color(0xFF141C2B)
private val ElevatedDark = Color(0xFF1A2332)
private val TextDark = Color(0xFFE8EDF2)
private val TextDarkSecondary = Color(0xFF8899AA)
private val ErrorDark = Color(0xFFFF4757)

private val BackgroundLight = Color(0xFFF7F8FA)
private val SurfaceLight = Color(0xFFFFFFFF)
private val ElevatedLight = Color(0xFFEEF1F4)
private val TextLight = Color(0xFF18212D)
private val TextLightSecondary = Color(0xFF526071)
private val PrimaryLight = Color(0xFF007A65)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = BackgroundDark,
    secondary = Color(0xFF4A9EFF),
    background = BackgroundDark,
    onBackground = TextDark,
    surface = SurfaceDark,
    onSurface = TextDark,
    surfaceVariant = ElevatedDark,
    onSurfaceVariant = TextDarkSecondary,
    error = ErrorDark,
)

private val LightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = SurfaceLight,
    primaryContainer = Color(0xFFC2F2E7),
    onPrimaryContainer = Color(0xFF00382F),
    secondary = Color(0xFF2563A6),
    background = BackgroundLight,
    onBackground = TextLight,
    surface = SurfaceLight,
    onSurface = TextLight,
    surfaceVariant = ElevatedLight,
    onSurfaceVariant = TextLightSecondary,
    error = Color(0xFFB3261E),
)

@Composable
fun BetterCloudDriveTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = resolveDarkTheme(mode, isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}

internal fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
