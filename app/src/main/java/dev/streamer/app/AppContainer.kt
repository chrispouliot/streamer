package dev.streamer.app

import android.app.Application
import androidx.annotation.OptIn
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.util.UnstableApi
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.data.NetworkPolicy
import dev.streamer.app.data.ServerProbe
import dev.streamer.app.data.UserMessages
import dev.streamer.app.data.account.AccountRepository
import dev.streamer.app.data.account.CredentialStore
import dev.streamer.app.data.account.KeystoreCipher
import dev.streamer.app.data.downloads.DownloadRepository
import dev.streamer.app.data.downloads.DownloadWorker
import dev.streamer.app.data.downloads.StreamerDownloadService
import dev.streamer.app.data.images.ArtworkPalette
import dev.streamer.app.data.images.ArtworkStore
import dev.streamer.app.data.local.AppDatabase
import dev.streamer.app.data.remote.SubsonicClient
import dev.streamer.app.data.repository.NavidromeLibraryRepository
import dev.streamer.app.playback.MediaPlayerController
import dev.streamer.app.playback.MediaResolver
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.playback.QueueStore
import dev.streamer.app.settings.SettingsRepository
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** App-scoped dependencies, built once by [StreamerApplication]. */
@OptIn(UnstableApi::class) // MediaResolver uses unstable Media3 types.
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
    val artworkPalette: ArtworkPalette,
    val policy: NetworkPolicy,
    val mediaResolver: MediaResolver,
    val artworkStore: ArtworkStore,
    val downloads: DownloadRepository,
    val serverProbe: ServerProbe,
)

@OptIn(UnstableApi::class) // Media3 download and data-source APIs.
fun createAppContainer(app: Application): AppContainer {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // No logging interceptor: request URLs carry auth tokens.
    val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val subsonic = SubsonicClient(http)
    val db = AppDatabase.create(app)
    val settings = SettingsRepository(app)
    val policy = NetworkPolicy(app, settings, scope)
    val accounts = AccountRepository(
        db.accounts(),
        CredentialStore(File(app.noBackupFilesDir, "credentials"), KeystoreCipher()),
        subsonic,
        scope,
    )
    val messages = UserMessages()
    val library = NavidromeLibraryRepository(
        accounts,
        db.library(),
        subsonic,
        messages,
        scope,
        offlineOnly = policy.offlineOnly,
        connection = policy.connection,
        onServerReachable = policy::reportServerReachable,
    )
    val resolver = MediaResolver(accounts, subsonic, policy)
    val probeHttp = http.newBuilder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    val serverProbe = ServerProbe(accounts, SubsonicClient(probeHttp), policy, scope)
    val artworkStore = ArtworkStore(File(app.filesDir, "artwork"), http, subsonic)
    val downloads = DownloadRepository(
        context = app,
        dao = db.library(),
        accounts = accounts,
        library = library,
        resolver = resolver,
        http = http,
        artwork = artworkStore,
        policy = policy,
        scope = scope,
        serviceClass = StreamerDownloadService::class.java,
        onPending = { DownloadWorker.ensureResume(app, policy.wifiOnlyDownloads.value) },
    )
    scope.launch { policy.wifiOnlyDownloads.collect { DownloadWorker.schedulePeriodic(app, it) } }
    val player = MediaPlayerController(
        context = app,
        scope = scope,
        accounts = accounts,
        store = QueueStore(File(app.filesDir, "queue.json")),
        onPlayed = library::recordPlayed,
        messages = messages,
        connection = policy.connection,
        isDownloaded = downloads::isDownloaded,
        onServerUnreachable = { policy.reportServerReachable(false) },
    )
    return AppContainer(
        library = library,
        player = player,
        settings = settings,
        accounts = accounts,
        messages = messages,
        http = http,
        subsonic = subsonic,
        scope = scope,
        artworkPalette = ArtworkPalette(app),
        policy = policy,
        mediaResolver = resolver,
        artworkStore = artworkStore,
        downloads = downloads,
        serverProbe = serverProbe,
    )
}

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
