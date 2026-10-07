package dev.streamer.app

import androidx.compose.runtime.staticCompositionLocalOf
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.settings.SettingsRepository

/**
 * App-scoped dependencies. Built once by [StreamerApplication] through the
 * build-type-specific `createAppContainer` (debug: demo data; release: empty).
 */
class AppContainer(
    val library: LibraryRepository,
    val player: PlayerController,
    val settings: SettingsRepository,
)

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
