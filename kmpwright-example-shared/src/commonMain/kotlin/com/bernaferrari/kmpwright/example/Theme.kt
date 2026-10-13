package com.bernaferrari.kmpwright.example

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Brand scheme generated from seed #6750A4 with a teal tertiary, used when the
// platform has no dynamic color (iOS, Android < 12).
private val LightColors = lightColorScheme(
    primary = Color(0xFF5B4FC4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5DEFF),
    onPrimaryContainer = Color(0xFF16035E),
    secondary = Color(0xFF7A4E86),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD6FF),
    onSecondaryContainer = Color(0xFF30093F),
    tertiary = Color(0xFF00696B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9CF1F2),
    onTertiaryContainer = Color(0xFF002020),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFCF8FF),
    onBackground = Color(0xFF1C1B22),
    surface = Color(0xFFFCF8FF),
    onSurface = Color(0xFF1C1B22),
    surfaceVariant = Color(0xFFE5E0EF),
    onSurfaceVariant = Color(0xFF48454F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF6F2FC),
    surfaceContainer = Color(0xFFF0ECF6),
    surfaceContainerHigh = Color(0xFFEAE6F1),
    surfaceContainerHighest = Color(0xFFE5E1EB),
    outline = Color(0xFF79757F),
    outlineVariant = Color(0xFFCAC4D0),
    inverseSurface = Color(0xFF312F37),
    inverseOnSurface = Color(0xFFF3EFF9),
    inversePrimary = Color(0xFFC8BFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC8BFFF),
    onPrimary = Color(0xFF2B1A93),
    primaryContainer = Color(0xFF4335AB),
    onPrimaryContainer = Color(0xFFE5DEFF),
    secondary = Color(0xFFEBB4F4),
    onSecondary = Color(0xFF481E55),
    secondaryContainer = Color(0xFF61366D),
    onSecondaryContainer = Color(0xFFFFD6FF),
    tertiary = Color(0xFF80D4D5),
    onTertiary = Color(0xFF003738),
    tertiaryContainer = Color(0xFF004F51),
    onTertiaryContainer = Color(0xFF9CF1F2),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF14131A),
    onBackground = Color(0xFFE5E1EB),
    surface = Color(0xFF14131A),
    onSurface = Color(0xFFE5E1EB),
    surfaceVariant = Color(0xFF48454F),
    onSurfaceVariant = Color(0xFFC9C4D0),
    surfaceContainerLowest = Color(0xFF0F0E15),
    surfaceContainerLow = Color(0xFF1C1B22),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2931),
    surfaceContainerHighest = Color(0xFF36343C),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF48454F),
    inverseSurface = Color(0xFFE5E1EB),
    inverseOnSurface = Color(0xFF312F37),
    inversePrimary = Color(0xFF5B4FC4),
)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Platform dynamic color (Material You on Android 12+); null when unavailable. */
@Composable
internal expect fun dynamicColorScheme(dark: Boolean): ColorScheme?

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KmpwrightTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = (if (dynamicColor) dynamicColorScheme(dark) else null) ?: if (dark) DarkColors else LightColors
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = ExpressiveShapes,
        content = content,
    )
}
