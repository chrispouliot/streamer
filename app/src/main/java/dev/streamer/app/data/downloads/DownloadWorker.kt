package dev.streamer.app.data.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.streamer.app.StreamerApplication
import dev.streamer.app.data.account.SessionState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background download work when the app isn't in the foreground: finishes
 * pending downloads once an allowed network is available, and (periodically)
 * refreshes Keep-updated playlists and downloads their new songs.
 * Each run is bounded; unfinished work retries later.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as StreamerApplication).container
        if (container.policy.offlineOnly.value) return Result.success()
        if (container.accounts.state.first { it != SessionState.Loading } !is SessionState.Active) return Result.success()
        val downloads = container.downloads
        if (inputData.getBoolean(KEY_REFRESH, false)) downloads.refreshKeepUpdated()
        downloads.resumeInBackground()
        val deadline = System.currentTimeMillis() + RUN_LIMIT_MS
        while (downloads.hasPending() && System.currentTimeMillis() < deadline) delay(5_000)
        return if (downloads.hasPending()) Result.retry() else Result.success()
    }

    companion object {
        private const val KEY_REFRESH = "refresh"
        private const val RUN_LIMIT_MS = 9 * 60_000L
        private const val PERIODIC = "keep-updated"
        private const val RESUME = "resume-downloads"

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Keep-updated playlists are checked every few hours on an allowed network. */
        fun schedulePeriodic(context: Context, wifiOnly: Boolean) {
            val request = PeriodicWorkRequestBuilder<DownloadWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints(wifiOnly))
                .setInputData(workDataOf(KEY_REFRESH to true))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Makes sure pending downloads finish even if the app leaves the foreground. */
        fun ensureResume(context: Context, wifiOnly: Boolean) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>().setConstraints(constraints(wifiOnly)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(RESUME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
