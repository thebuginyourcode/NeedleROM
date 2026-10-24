package com.needlerom.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFFC9B8FF),
    onPrimary = Color(0xFF2B1F4A),
    primaryContainer = Color(0xFF433468),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFFF8FAF),
    onSecondary = Color(0xFF4A0A22),
    secondaryContainer = Color(0xFF6B1F3C),
    onSecondaryContainer = Color(0xFFFFD9E2),
    tertiary = Color(0xFF7FD8C2),
    background = Color(0xFF14121C),
    onBackground = Color(0xFFE7E1F0),
    surface = Color(0xFF14121C),
    onSurface = Color(0xFFE7E1F0),
    surfaceVariant = Color(0xFF2A2638),
    onSurfaceVariant = Color(0xFFCAC3DA),
    surfaceContainer = Color(0xFF1E1B29),
    surfaceContainerHigh = Color(0xFF282435),
    error = Color(0xFFFFB4AB),
)

private val Light = lightColorScheme(
    primary = Color(0xFF5B4790),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF1A0B47),
    secondary = Color(0xFFB0285A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9E2),
    onSecondaryContainer = Color(0xFF3E0019),
    tertiary = Color(0xFF006B5A),
    background = Color(0xFFFCF8FF),
    surface = Color(0xFFFCF8FF),
    surfaceVariant = Color(0xFFE6E0F0),
    surfaceContainer = Color(0xFFF2ECF8),
    surfaceContainerHigh = Color(0xFFEBE5F3),
)

@Composable
fun NeedleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
