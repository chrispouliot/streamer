package dev.streamer.app.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.streamer.app.MainActivity
import dev.streamer.app.StreamerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns the single ExoPlayer and its media session. Media notification,
 * lock-screen, headset and Bluetooth controls all act on this player; the app
 * UI reaches it through [MediaPlayerController].
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val container = (application as StreamerApplication).container
        val resolver = container.mediaResolver
        // Songs with a download play from the store (topped up from the original
        // file if incomplete; never written to); others stream.
        val withDownload = CacheDataSource.Factory()
            .setCache(container.downloads.cache)
            .setCacheKeyFactory(MediaResolver.originalKeyFactory)
            .setCacheWriteDataSinkFactory(null)
            .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(OkHttpDataSource.Factory(container.http), resolver::original))
        val streaming = ResolvingDataSource.Factory(OkHttpDataSource.Factory(container.http), resolver::stream)
        val dataSources = SelectingDataSource.Factory(withDownload, streaming, container.downloads::hasDownload)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // Pause when headphones are unplugged.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.setShuffleOrder(QueueShuffleOrder(IntArray(0)))
        player.addListener(
            object : Player.Listener {
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    // Turning shuffle on keeps the current song and shuffles the rest.
                    if (shuffleModeEnabled) {
                        player.setShuffleOrder(QueueShuffleOrder.startingWith(player.mediaItemCount, player.currentMediaItemIndex))
                    }
                }
            },
        )
        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback)
            .setBitmapLoader(CacheBitmapLoader(CoverBitmapLoader(this, scope)))
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep playing in the background when swiped away; otherwise stop.
        if (!isPlaybackOngoing) pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private object SessionCallback : MediaSession.Callback {
        // Controllers can't send playable URIs; restore ours and drop anything else.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(mediaItems.mapNotNull { it.withPlayableUri() })
    }
}
