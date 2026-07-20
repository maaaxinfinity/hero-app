package io.hero.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Monochrome "ink on paper" identity. The H mark and the particle field are the
// only visual accent — every role tone is a neutral so no MD3 baseline purple
// leaks into containers, tints, or selection states.

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A1A1A),
    onPrimary = Color(0xFFFAFAFA),
    primaryContainer = Color(0xFFE4E4E4),
    onPrimaryContainer = Color(0xFF1A1A1A),
    secondary = Color(0xFF5F5F5F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEAEAEA),
    onSecondaryContainer = Color(0xFF1A1A1A),
    tertiary = Color(0xFF5F5F5F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEAEAEA),
    onTertiaryContainer = Color(0xFF1A1A1A),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEFEFEF),
    onSurfaceVariant = Color(0xFF5F5F5F),
    surfaceTint = Color(0xFF1A1A1A),
    outline = Color(0xFFDADADA),
    outlineVariant = Color(0xFFECECEC),
    inverseSurface = Color(0xFF2A2A2A),
    inverseOnSurface = Color(0xFFF2F2F2),
    inversePrimary = Color(0xFFC8C8C8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFEDEDED),
    onPrimary = Color(0xFF121212),
    primaryContainer = Color(0xFF2E2E2E),
    onPrimaryContainer = Color(0xFFEDEDED),
    secondary = Color(0xFFA0A0A0),
    onSecondary = Color(0xFF121212),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFFA0A0A0),
    onTertiary = Color(0xFF121212),
    tertiaryContainer = Color(0xFF2A2A2A),
    onTertiaryContainer = Color(0xFFEDEDED),
    background = Color(0xFF121212),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFA0A0A0),
    surfaceTint = Color(0xFFEDEDED),
    outline = Color(0xFF333333),
    outlineVariant = Color(0xFF242424),
    inverseSurface = Color(0xFFEDEDED),
    inverseOnSurface = Color(0xFF1A1A1A),
    inversePrimary = Color(0xFF4A4A4A),
)

private val HeroShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun HeroTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = HeroShapes,
        typography = Typography(),
        content = content,
    )
}
