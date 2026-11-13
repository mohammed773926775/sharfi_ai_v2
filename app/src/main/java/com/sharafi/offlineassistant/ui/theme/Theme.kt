package com.sharafi.offlineassistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SharafiBackgroundDark = Color(0xFF070B10)
val SharafiSurfaceDark = Color(0xFF101824)
val SharafiCardDark = Color(0xFF162232)
val SharafiCyanPrimary = Color(0xFF00E5FF)
val SharafiTurquoiseSecondary = Color(0xFF1DE9B6)
val SharafiCyanDim = Color(0xFF00838F)
val SharafiTextPrimary = Color(0xFFE6F7FF)
val SharafiTextSecondary = Color(0xFF8AB4C9)
val SharafiErrorRed = Color(0xFFFF5252)

private val SharafiDarkColorScheme = darkColorScheme(
    primary = SharafiCyanPrimary,
    onPrimary = Color(0xFF00252B),
    primaryContainer = SharafiCyanDim,
    onPrimaryContainer = SharafiTextPrimary,
    secondary = SharafiTurquoiseSecondary,
    onSecondary = Color(0xFF00261D),
    background = SharafiBackgroundDark,
    onBackground = SharafiTextPrimary,
    surface = SharafiSurfaceDark,
    onSurface = SharafiTextPrimary,
    surfaceVariant = SharafiCardDark,
    onSurfaceVariant = SharafiTextSecondary,
    error = SharafiErrorRed
)

@Composable
fun SharafiAiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SharafiDarkColorScheme,
        content = content
    )
}
