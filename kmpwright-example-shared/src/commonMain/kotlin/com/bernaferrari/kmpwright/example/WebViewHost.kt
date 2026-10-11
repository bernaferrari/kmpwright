package com.bernaferrari.kmpwright.example.shared

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bernaferrari.kmpwright.core.PlatformContext

@Composable
expect fun KmpwrightWebViewHost(
    modifier: Modifier = Modifier,
    platformContext: PlatformContext
)
