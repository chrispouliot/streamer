package dev.streamer.app

import android.app.Application
import dev.streamer.app.demo.DemoLibraryRepository
import dev.streamer.app.demo.DemoPlayerController
import dev.streamer.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

// Debug builds use fictional demo data and a silent in-memory player so the
// UI can be exercised before the Navidrome client and Media3 player exist.
// Nothing in this source set is compiled into release builds.
fun createAppContainer(app: Application): AppContainer = AppContainer(
    library = DemoLibraryRepository(),
    player = DemoPlayerController(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)),
    settings = SettingsRepository(app),
)
