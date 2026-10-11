package com.bernaferrari.kmpwright.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun KmpwrightDebugOverlay(
    controller: KmpwrightWebViewController,
    modifier: Modifier = Modifier,
    highlightRect: HighlightRect? = null
) {
    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            highlightRect?.let { rect ->
                drawRect(
                    color = Color.Red.copy(alpha = 0.5f),
                    topLeft = Offset(rect.x.toFloat(), rect.y.toFloat()),
                    size = Size(rect.width.toFloat(), rect.height.toFloat())
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            color = Color.Black.copy(alpha = 0.7f),
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = "Kmpwright",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}

data class HighlightRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double
)
