package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkCinemaColorScheme = darkColorScheme(
    primary = BroadcastCrimson,
    onPrimary = CrispOffWhite,
    primaryContainer = CrimsonContainer,
    onPrimaryContainer = EmberCoral,
    secondary = EmberCoral,
    onSecondary = ObsidianBackground,
    secondaryContainer = ElevatedSlate,
    onSecondaryContainer = CrispOffWhite,
    tertiary = OnlineEmerald,
    onTertiary = ObsidianBackground,
    tertiaryContainer = OnlineEmeraldContainer,
    onTertiaryContainer = OnlineEmerald,
    background = ObsidianBackground,
    onBackground = CrispOffWhite,
    surface = StudioCharcoal,
    onSurface = CrispOffWhite,
    surfaceVariant = ElevatedSlate,
    onSurfaceVariant = MutedSlate,
    outline = HairlineBorder,
    outlineVariant = SubtleSteel,
    error = BroadcastCrimson,
    onError = CrispOffWhite,
)

private val CinemaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun LiveNexTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkCinemaColorScheme,
        typography = LiveNexTypography,
        shapes = CinemaShapes,
        content = content,
    )
}
