package dev.streamer.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.streamer.app.model.Song
import dev.streamer.app.settings.TrackSort
import dev.streamer.app.settings.TrackSortField
import dev.streamer.app.ui.icons.AppIcons
import dev.streamer.app.ui.theme.Dimens
import java.time.Instant

private val byText = String.CASE_INSENSITIVE_ORDER

/**
 * Orders album/playlist items for display. [position] is the item's place in
 * the server's order. Songs without a favourite time stay after favourited ones
 * in either direction.
 */
fun <T> List<T>.sortedForTracks(sort: TrackSort, position: (T) -> Int, song: (T) -> Song): List<T> {
    fun <C : Comparable<C>> by(selector: (T) -> C?): Comparator<T> =
        if (sort.descending) compareByDescending(selector) else compareBy(selector)
    val byPosition = by(position)
    return when (sort.field) {
        TrackSortField.Added, TrackSortField.Track -> sortedWith(byPosition)
        TrackSortField.Favourited -> {
            val (dated, undated) = partition { song(it).favouritedAt != null }
            dated.sortedWith(by<Instant> { song(it).favouritedAt }.then(byPosition)) + undated.sortedWith(byPosition)
        }
        TrackSortField.Title -> sortedWith(
            (if (sort.descending) compareByDescending(byText) { song(it).title } else compareBy(byText) { song(it).title }),
        )
        TrackSortField.Artist -> {
            val artistThenAlbum = compareBy<T, String>(byText) { song(it).artist }
                .thenBy(byText) { song(it).album ?: "" }
                .thenBy { song(it).trackNumber ?: Int.MAX_VALUE }
            sortedWith(if (sort.descending) artistThenAlbum.reversed() else artistThenAlbum)
        }
        TrackSortField.Duration -> sortedWith(by { song(it).duration })
    }
}

/**
 * Compact sort control showing the field name. Opens a menu of [fields] plus
 * Ascending/Descending; picking the current field again flips the direction.
 */
@Composable
fun TrackSortControl(sort: TrackSort, fields: List<TrackSortField>, onSort: (TrackSort) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val direction = if (sort.descending) "descending" else "ascending"
    Box(modifier) {
        TextButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = Dimens.s),
            modifier = Modifier.semantics { contentDescription = "Sorted by ${sort.field.label}, $direction. Change sort" },
        ) {
            // The direction is shown in the menu only.
            Text(sort.field.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fields.forEach { field ->
                DropdownMenuItem(
                    text = { Text(field.label) },
                    onClick = {
                        open = false
                        onSort(if (field == sort.field) sort.copy(descending = !sort.descending) else TrackSort(field))
                    },
                    trailingIcon = { if (field == sort.field) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                )
            }
            HorizontalDivider()
            listOf(false to "Ascending", true to "Descending").forEach { (descending, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(if (descending) AppIcons.ArrowDownward else AppIcons.ArrowUpward, contentDescription = null) },
                    onClick = { open = false; onSort(sort.copy(descending = descending)) },
                    trailingIcon = { if (sort.descending == descending) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                )
            }
        }
    }
}
