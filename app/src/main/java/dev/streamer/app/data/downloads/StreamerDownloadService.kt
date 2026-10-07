package dev.streamer.app.data.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dev.streamer.app.MainActivity
import dev.streamer.app.R
import dev.streamer.app.StreamerApplication

/**
 * Runs downloads in the foreground with a progress notification. Restarting
 * downloads later (e.g. when Wi-Fi returns) is handled by [DownloadWorker].
 */
@UnstableApi
class StreamerDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.downloads_channel_name,
    R.string.downloads_channel_description,
) {
    override fun getDownloadManager(): DownloadManager = (application as StreamerApplication).container.downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return DownloadNotificationHelper(this, CHANNEL_ID)
            .buildProgressNotification(this, R.drawable.ic_stat_download, open, null, downloads, notMetRequirements)
    }

    private companion object {
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "downloads"
    }
}
