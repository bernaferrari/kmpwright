package com.bernaferrari.kmpwright.example

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.material3.dynamicColorScheme

/** Kotlin violet seeds the whole scheme, so every platform looks the same. */
private val Violet = Color(0xFF7F52FF)

private val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun KmpwrightTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = remember(dark) {
        dynamicColorScheme(
            primary = Violet,
            isDark = dark,
            style = PaletteStyle.TonalSpot,
            specVersion = ColorSpec.SpecVersion.SPEC_2025,
        )
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        shapes = ExpressiveShapes,
        content = content,
    )
}
