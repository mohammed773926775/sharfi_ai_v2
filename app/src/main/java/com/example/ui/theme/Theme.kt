package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SharafiDarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = OnCyanPrimary,
    primaryContainer = CyanPrimaryContainer,
    onPrimaryContainer = CyanPrimary,
    secondary = TurquoiseSecondary,
    onSecondary = OnTurquoiseSecondary,
    secondaryContainer = TurquoiseSecondaryContainer,
    onSecondaryContainer = TurquoiseSecondary,
    tertiary = EmeraldSuccess,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianSurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = ObsidianCardBorder,
    error = CoralError,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = SharafiDarkColorScheme,
        typography = Typography,
        content = content
    )
}
