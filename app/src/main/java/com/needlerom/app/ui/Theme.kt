package com.needlerom.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

/** A game-engine editor look: slate panels, blue accents, compact text. */
private val Scheme = darkColorScheme(
    primary = Color(0xFF6BB3F0),
    onPrimary = Color(0xFF06223A),
    primaryContainer = Color(0xFF2B4A66),
    onPrimaryContainer = Color(0xFFD3E8FF),
    secondary = Color(0xFFFFB454),
    onSecondary = Color(0xFF3A2200),
    secondaryContainer = Color(0xFF5A3D12),
    onSecondaryContainer = Color(0xFFFFE0B8),
    tertiary = Color(0xFF8EE08A),
    background = Color(0xFF1D2229),
    onBackground = Color(0xFFDDE2E8),
    surface = Color(0xFF1D2229),
    onSurface = Color(0xFFDDE2E8),
    surfaceVariant = Color(0xFF2C333D),
    onSurfaceVariant = Color(0xFFA9B2BD),
    surfaceContainerLowest = Color(0xFF14181D),
    surfaceContainerLow = Color(0xFF191E24),
    surfaceContainer = Color(0xFF232931),
    surfaceContainerHigh = Color(0xFF2B323B),
    surfaceContainerHighest = Color(0xFF353D47),
    outline = Color(0xFF6B7682),
    outlineVariant = Color(0xFF3A424C),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF7A1A14),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val Compact = Typography().let { t ->
    t.copy(
        bodyLarge = t.bodyLarge.copy(fontSize = 15.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp),
        bodySmall = t.bodySmall.copy(fontSize = 12.sp),
        titleLarge = t.titleLarge.copy(fontSize = 18.sp),
        labelSmall = t.labelSmall.copy(fontSize = 11.sp),
    )
}

@Composable
fun NeedleTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Scheme, typography = Compact, content = content)
