package dev.streamer.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { System, Light, Dark }

enum class AlbumOrder(val label: String) { Name("Name"), Artist("Artist"), Year("Year"), RecentlyAdded("Recently added") }

enum class ArtistOrder(val label: String) { Name("Name"), MostAlbums("Most albums") }

enum class PlaylistListOrder(val label: String) { Name("Name"), RecentlyUpdated("Recently updated") }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Non-secret user preferences. Credentials never go here. */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsDataStore

    val themeMode: Flow<ThemeMode> = store.data.map { prefs ->
        prefs[THEME_MODE]?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.System
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[THEME_MODE] = mode.name }
    }

    /** The sort chosen for this playlist, or null if the user hasn't chosen one. */
    fun playlistSort(playlistId: String): Flow<TrackSort?> = store.data.map { prefs ->
        prefs[playlistSortKey(playlistId)]?.let(TrackSort::decode)
    }

    suspend fun setPlaylistSort(playlistId: String, sort: TrackSort) {
        store.edit { it[playlistSortKey(playlistId)] = sort.encode() }
    }

    /** How album songs are ordered (one choice for all albums). */
    val albumTrackSort: Flow<TrackSort> = store.data.map { prefs ->
        prefs[ALBUM_TRACK_SORT]?.let(TrackSort::decode) ?: TrackSort.AlbumDefault
    }

    suspend fun setAlbumTrackSort(sort: TrackSort) {
        store.edit { it[ALBUM_TRACK_SORT] = sort.encode() }
    }

    private fun playlistSortKey(playlistId: String) = stringPreferencesKey("playlist_sort:$playlistId")

    /** Download only on unmetered networks (Wi-Fi). On by default. */
    val wifiOnlyDownloads: Flow<Boolean> = store.data.map { it[WIFI_ONLY_DOWNLOADS] ?: true }
    suspend fun setWifiOnlyDownloads(enabled: Boolean) = store.edit { it[WIFI_ONLY_DOWNLOADS] = enabled }

    /** No server requests at all: browse saved data and play downloads only. */
    val offlineOnly: Flow<Boolean> = store.data.map { it[OFFLINE_ONLY] ?: false }
    suspend fun setOfflineOnly(enabled: Boolean) = store.edit { it[OFFLINE_ONLY] = enabled }

    val albumOrder: Flow<AlbumOrder> = enumPref("album_order", AlbumOrder.Name)
    suspend fun setAlbumOrder(order: AlbumOrder) = setEnumPref("album_order", order)

    val artistOrder: Flow<ArtistOrder> = enumPref("artist_order", ArtistOrder.Name)
    suspend fun setArtistOrder(order: ArtistOrder) = setEnumPref("artist_order", order)

    val playlistListOrder: Flow<PlaylistListOrder> = enumPref("playlist_list_order", PlaylistListOrder.Name)
    suspend fun setPlaylistListOrder(order: PlaylistListOrder) = setEnumPref("playlist_list_order", order)

    private inline fun <reified E : Enum<E>> enumPref(name: String, default: E): Flow<E> = store.data.map { prefs ->
        prefs[stringPreferencesKey(name)]?.let { stored -> enumValues<E>().firstOrNull { it.name == stored } } ?: default
    }

    private suspend fun <E : Enum<E>> setEnumPref(name: String, value: E) {
        store.edit { it[stringPreferencesKey(name)] = value.name }
    }

    private companion object {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val ALBUM_TRACK_SORT = stringPreferencesKey("album_track_sort")
        val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
        val OFFLINE_ONLY = booleanPreferencesKey("offline_only")
    }
}
