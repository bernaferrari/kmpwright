package com.bernaferrari.kmpwright.test

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.bernaferrari.kmpwright.compose.KmpwrightWebView
import com.bernaferrari.kmpwright.compose.rememberKmpwrightController
import com.bernaferrari.kmpwright.core.KmpwrightConfig
import com.bernaferrari.kmpwright.core.DebugConfig

class KmpwrightTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val controller = rememberKmpwrightController(
                KmpwrightConfig(
                    allowedOriginRules = setOf("https://appassets.androidplatform.net"),
                    debug = DebugConfig(
                        enabled = true,
                        showHighlight = false,
                        logJsCommands = true,
                        exportTraceOnFailure = true,
                        showDebugOverlay = true
                    )
                )
            )

            TestScreen(controller)
        }
    }
}

@Composable
fun TestScreen(controller: com.bernaferrari.kmpwright.compose.KmpwrightWebViewController) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            KmpwrightWebView(
                controller = controller,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
