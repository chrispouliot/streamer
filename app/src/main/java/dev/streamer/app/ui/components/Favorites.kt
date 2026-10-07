package dev.streamer.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.streamer.app.LocalAppContainer
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.model.Song

/** Snapshot of the starred songs plus the action to change one. */
class Favorites(private val ids: Set<String>, private val library: LibraryRepository?) {
    fun isStarred(song: Song): Boolean = song.id in ids
    fun toggle(song: Song) {
        library?.setSongStarred(song.id, !isStarred(song))
    }

    companion object {
        /** For previews and tests. */
        val None = Favorites(emptySet(), null)
    }
}

@Composable
fun rememberFavorites(): Favorites {
    val library = LocalAppContainer.current.library
    val ids by library.starredSongIds.collectAsStateWithLifecycle(initialValue = emptySet())
    return remember(ids, library) { Favorites(ids, library) }
}

@Composable
fun FavoriteButton(song: Song, favorites: Favorites, modifier: Modifier = Modifier) {
    val starred = favorites.isStarred(song)
    IconToggleButton(checked = starred, onCheckedChange = { favorites.toggle(song) }, modifier = modifier) {
        Icon(
            if (starred) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (starred) "Remove from favourites" else "Add to favourites",
        )
    }
}
