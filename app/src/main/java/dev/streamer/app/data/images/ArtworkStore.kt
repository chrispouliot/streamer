package dev.streamer.app.data.images

import dev.streamer.app.data.remote.ServerAuth
import dev.streamer.app.data.remote.SubsonicClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Permanent cover images for downloaded music, so they show offline. Separate
 * from the image loader's evictable cache.
 */
class ArtworkStore(private val dir: File, private val http: OkHttpClient, private val client: SubsonicClient) {
    private fun safe(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun file(accountId: String, coverId: String, px: Int) = File(File(dir, safe(accountId)), "${safe(coverId)}_$px.img")

    /** The stored image closest to [px] (preferring larger), if any. */
    fun best(accountId: String, coverId: String, px: Int): File? {
        val stored = SIZES.map { it to file(accountId, coverId, it) }.filter { it.second.exists() }
        if (stored.isEmpty()) return null
        return (stored.filter { it.first >= px }.minByOrNull { it.first } ?: stored.maxBy { it.first }).second
    }

    /** Downloads the covers in [SIZES] that aren't stored yet. Failures are ignored (artwork is optional). */
    suspend fun ensure(auth: ServerAuth, accountId: String, coverId: String) = withContext(Dispatchers.IO) {
        for (px in SIZES) {
            val target = file(accountId, coverId, px)
            if (target.exists()) continue
            runCatching {
                val url = client.authenticatedUrl(auth, "getCoverArt", mapOf("id" to coverId, "size" to px.toString()))
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    val type = response.header("Content-Type").orEmpty()
                    if (!response.isSuccessful || !type.startsWith("image/")) return@use
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, target.name + ".tmp")
                    tmp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
                    tmp.renameTo(target)
                }
            }
        }
    }

    /** Deletes stored covers for [accountId] except those in [keep]. */
    suspend fun keepOnly(accountId: String, keep: Set<String>) = withContext(Dispatchers.IO) {
        val keepNames = keep.map(::safe).toSet()
        File(dir, safe(accountId)).listFiles()?.forEach { f ->
            if (f.name.substringBeforeLast('_') !in keepNames) f.delete()
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) { dir.deleteRecursively() }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }

    private companion object {
        /** Small for rows, large for the player; medium requests use the large one. */
        val SIZES = listOf(160, 1000)
    }
}
