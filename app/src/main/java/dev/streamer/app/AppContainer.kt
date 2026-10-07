package dev.streamer.app

import android.app.Application
import androidx.compose.runtime.staticCompositionLocalOf
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.data.UserMessages
import dev.streamer.app.data.account.AccountRepository
import dev.streamer.app.data.account.CredentialStore
import dev.streamer.app.data.account.KeystoreCipher
import dev.streamer.app.data.local.AppDatabase
import dev.streamer.app.data.remote.SubsonicClient
import dev.streamer.app.data.repository.NavidromeLibraryRepository
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** App-scoped dependencies, built once by [StreamerApplication]. */
class AppContainer(
    val library: LibraryRepository,
    val player: PlayerController,
    val settings: SettingsRepository,
    val accounts: AccountRepository,
    val messages: UserMessages,
    val http: OkHttpClient,
    val subsonic: SubsonicClient,
    /** Application-lifetime scope for app-wide background work. */
    val scope: CoroutineScope,
)

fun createAppContainer(app: Application): AppContainer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // No logging interceptor: request URLs carry auth tokens.
    val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val subsonic = SubsonicClient(http)
    val db = AppDatabase.create(app)
    val accounts = AccountRepository(
        db.accounts(),
        CredentialStore(File(app.noBackupFilesDir, "credentials"), KeystoreCipher()),
        subsonic,
        scope,
    )
    val messages = UserMessages()
    return AppContainer(
        library = NavidromeLibraryRepository(accounts, db.library(), subsonic, messages, scope),
        player = createPlayerController(scope),
        settings = SettingsRepository(app),
        accounts = accounts,
        messages = messages,
        http = http,
        subsonic = subsonic,
        scope = scope,
    )
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
