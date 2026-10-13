package com.bernaferrari.kmpwright.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import com.bernaferrari.kmpwright.core.Rectangle

/**
 * Draws element rectangles (for example from `Locator.boundingBox()`) over a
 * [KmpwrightWebView]. Place it in the same `Box` as the WebView with the same
 * size so both share an origin.
 *
 * Rectangles are CSS pixels. [cssPixelScale] is dp per CSS pixel: 1 for pages
 * with `width=device-width` and no pinch zoom; multiply by the page zoom
 * (`visualViewport.scale`) otherwise.
 */
@Composable
public fun KmpwrightDebugOverlay(
    highlights: List<Rectangle>,
    modifier: Modifier = Modifier,
    cssPixelScale: Float = 1f,
    color: Color = Color.Red
) {
    Layout(
        content = {},
        modifier = modifier.drawBehind {
            val pxPerCss = density * cssPixelScale
            for (rect in highlights) {
                val topLeft = Offset((rect.x * pxPerCss).toFloat(), (rect.y * pxPerCss).toFloat())
                val size = Size((rect.width * pxPerCss).toFloat(), (rect.height * pxPerCss).toFloat())
                drawRect(color.copy(alpha = 0.25f), topLeft, size)
                drawRect(color, topLeft, size, style = Stroke(width = density))
            }
        }
    ) { _, constraints ->
        layout(constraints.maxWidth, constraints.maxHeight) {}
    }
}
