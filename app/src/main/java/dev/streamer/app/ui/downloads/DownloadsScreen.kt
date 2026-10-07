package dev.streamer.app.ui.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.streamer.app.ui.components.BackBar
import dev.streamer.app.ui.components.EmptyState
import dev.streamer.app.ui.components.ScreenTitle
import dev.streamer.app.ui.navigation.AppNavigator
import dev.streamer.app.ui.navigation.LocalShellLayout

/** Placeholder until offline downloads are implemented (Phase 5). */
@Composable
fun DownloadsRoute(navigator: AppNavigator) {
    val layout = LocalShellLayout.current
    Column(Modifier.fillMaxSize()) {
        if (!layout.usesRail) BackBar(navigator::back)
        ScreenTitle("Downloads", layout.pagePadding)
        EmptyState("No downloads", "Downloading music for offline playback isn't available in this build yet.")
    }
}
