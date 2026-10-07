package dev.streamer.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { System, Light, Dark }

/**
 * How playlist songs are listed. Navidrome records playlist order but not when
 * a song was added; songs are appended, so newest first is reverse order.
 * Favourite timestamps are exact, which suits rule-based (smart) playlists.
 */
enum class PlaylistSort(val label: String) {
    RecentlyAdded("Recently added"),
    RecentlyFavourited("Recently favourited"),
    PlaylistOrder("Playlist order"),
    Title("Title"),
    Artist("Artist"),
}

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
    fun playlistSort(playlistId: String): Flow<PlaylistSort?> = store.data.map { prefs ->
        prefs[playlistSortKey(playlistId)]?.let { stored -> PlaylistSort.entries.firstOrNull { it.name == stored } }
    }

    suspend fun setPlaylistSort(playlistId: String, sort: PlaylistSort) {
        store.edit { it[playlistSortKey(playlistId)] = sort.name }
    }

    private fun playlistSortKey(playlistId: String) = stringPreferencesKey("playlist_sort:$playlistId")

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
    }
}
