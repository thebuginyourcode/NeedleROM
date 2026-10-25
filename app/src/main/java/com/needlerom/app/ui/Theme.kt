package com.needlerom.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** An engine-style dark theme with Game Boy green accents. */
private val Scheme = darkColorScheme(
    primary = Color(0xFF9BE07A),
    onPrimary = Color(0xFF0D1F08),
    primaryContainer = Color(0xFF2C4A22),
    onPrimaryContainer = Color(0xFFD2F5C2),
    secondary = Color(0xFFFF8FA8),
    onSecondary = Color(0xFF3D0A18),
    secondaryContainer = Color(0xFF5C1F31),
    onSecondaryContainer = Color(0xFFFFD9E1),
    tertiary = Color(0xFF8BC8FF),
    background = Color(0xFF121016),
    onBackground = Color(0xFFE6E1E8),
    surface = Color(0xFF121016),
    onSurface = Color(0xFFE6E1E8),
    surfaceVariant = Color(0xFF2A2730),
    onSurfaceVariant = Color(0xFFC6C0CC),
    surfaceContainerLowest = Color(0xFF0C0B0F),
    surfaceContainerLow = Color(0xFF17151B),
    surfaceContainer = Color(0xFF1C1A21),
    surfaceContainerHigh = Color(0xFF26232C),
    surfaceContainerHighest = Color(0xFF312D37),
    outline = Color(0xFF8E8896),
    outlineVariant = Color(0xFF433F4A),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun NeedleTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, content = content)
