package dev.streamer.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Neutral palette from the mockups: near-black dark surfaces, off-white light
// surfaces with pale grey cards, monochrome primary actions. Colour comes from
// artwork tint, not from the scheme.

internal val DarkColors = darkColorScheme(
    primary = Color(0xFFF2F2F0),
    onPrimary = Color(0xFF111112),
    primaryContainer = Color(0xFF3A3A3D),
    onPrimaryContainer = Color(0xFFF2F2F0),
    secondary = Color(0xFFC8C8CC),
    onSecondary = Color(0xFF111112),
    secondaryContainer = Color(0xFF3A3A3D),
    onSecondaryContainer = Color(0xFFF2F2F0),
    background = Color(0xFF0E0E0F),
    onBackground = Color(0xFFF2F2F0),
    surface = Color(0xFF0E0E0F),
    onSurface = Color(0xFFF2F2F0),
    surfaceVariant = Color(0xFF232325),
    onSurfaceVariant = Color(0xFFA3A3A8),
    surfaceContainerLowest = Color(0xFF09090A),
    surfaceContainerLow = Color(0xFF141415),
    surfaceContainer = Color(0xFF1B1B1D),
    surfaceContainerHigh = Color(0xFF232325),
    surfaceContainerHighest = Color(0xFF2C2C2F),
    outline = Color(0xFF5A5A5F),
    outlineVariant = Color(0xFF333336),
    inverseSurface = Color(0xFFF2F2F0),
    inverseOnSurface = Color(0xFF141415),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFF141415),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2E2E0),
    onPrimaryContainer = Color(0xFF141415),
    secondary = Color(0xFF4A4A4F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE2E2E0),
    onSecondaryContainer = Color(0xFF141415),
    background = Color(0xFFF8F8F6),
    onBackground = Color(0xFF141415),
    surface = Color(0xFFF8F8F6),
    onSurface = Color(0xFF141415),
    surfaceVariant = Color(0xFFEAEAE8),
    onSurfaceVariant = Color(0xFF5E5E63),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F2F0),
    surfaceContainer = Color(0xFFEEEEEC),
    surfaceContainerHigh = Color(0xFFE8E8E6),
    surfaceContainerHighest = Color(0xFFE2E2E0),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFD4D4D2),
    inverseSurface = Color(0xFF2C2C2F),
    inverseOnSurface = Color(0xFFF2F2F0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)
