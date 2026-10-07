package dev.streamer.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.streamer.app.settings.ThemeMode
import dev.streamer.app.ui.AppRoot
import dev.streamer.app.ui.theme.StreamerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as StreamerApplication).container
        setContent {
            val themeMode by container.settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val dark = when (themeMode) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            // Keep system bar icons readable when the app theme differs from the system's.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            StreamerTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    AppRoot(container)
                }
            }
        }
    }
}
