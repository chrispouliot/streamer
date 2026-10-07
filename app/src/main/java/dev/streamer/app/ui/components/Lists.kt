package dev.streamer.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.streamer.app.data.Connection
import dev.streamer.app.model.Artwork
import dev.streamer.app.model.Song
import dev.streamer.app.ui.downloads.DownloadsUi
import dev.streamer.app.ui.icons.AppIcons
import dev.streamer.app.ui.navigation.LocalConnection
import dev.streamer.app.ui.theme.Dimens

/** Per-song menu actions. A null callback hides that entry. */
data class SongActions(
    val onPlayNext: ((Song) -> Unit)? = null,
    val onAddToQueue: ((Song) -> Unit)? = null,
    val onOpenAlbum: ((Song) -> Unit)? = null,
    val onOpenArtist: ((Song) -> Unit)? = null,
    /** Shows the downloaded indicator and Download/Remove download entries. */
    val downloads: DownloadsUi? = null,
)

enum class SongLeading { Number, Artwork, None }

@Composable
fun SongRow(
    song: Song,
    /** Null for rows that do nothing when tapped (e.g. the queue's current song). */
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    leading: SongLeading = SongLeading.Artwork,
    number: Int? = song.trackNumber,
    isCurrent: Boolean = false,
    showArtist: Boolean = true,
    actions: SongActions = SongActions(),
    horizontalPadding: Dp = Dimens.pagePaddingCompact,
) {
    // Without the server, songs that aren't downloaded won't play: show them dimmed.
    val unavailable = LocalConnection.current != Connection.Online && actions.downloads?.isDownloaded(song) == false
    Row(
        modifier
            .fillMaxWidth()
            .then(if (unavailable) Modifier.alpha(0.4f).semantics { stateDescription = "Not available offline" } else Modifier)
            .then(if (isCurrent) Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (isCurrent) Modifier.semantics { stateDescription = "Now playing" } else Modifier)
            .heightIn(min = 64.dp)
            .padding(start = horizontalPadding, end = Dimens.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (leading) {
            SongLeading.Number -> Box(Modifier.widthIn(min = 32.dp).padding(end = Dimens.xs), contentAlignment = Alignment.CenterStart) {
                if (isCurrent) {
                    Icon(AppIcons.GraphicEq, contentDescription = "Now playing", modifier = Modifier.size(20.dp))
                } else {
                    Text(
                        number?.toString() ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SongLeading.Artwork -> {
                CoverArt(song.artwork, Modifier.size(Dimens.rowArt), shape = MaterialTheme.shapes.small, size = ArtSize.Small)
                Spacer(Modifier.width(Dimens.m))
            }
            SongLeading.None -> Unit
        }
        Column(Modifier.weight(1f).padding(vertical = Dimens.s)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showArtist || song.explicit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (song.explicit) ExplicitBadge()
                    if (showArtist) {
                        Text(
                            song.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (actions.downloads?.isDownloaded(song) == true) {
            Icon(
                AppIcons.DownloadDone,
                contentDescription = "Downloaded",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Dimens.s).size(16.dp),
            )
        }
        song.duration?.let {
            Text(
                it.formatClock(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Dimens.s),
            )
        }
        SongMenu(song, actions)
    }
}

@Composable
fun ExplicitBadge() {
    Box(
        Modifier
            .padding(end = 6.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.onSurfaceVariant)
            .padding(horizontal = 4.dp),
    ) {
        Text(
            "E",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.semantics { contentDescription = "Explicit" },
        )
    }
}

@Composable
fun SongMenu(song: Song, actions: SongActions) {
    val items = buildList {
        actions.onPlayNext?.let { add("Play next" to it) }
        actions.onAddToQueue?.let { add("Add to queue" to it) }
        if (song.albumId != null) actions.onOpenAlbum?.let { add("Go to album" to it) }
        if (song.artistId != null) actions.onOpenArtist?.let { add("Go to artist" to it) }
        actions.downloads?.let { d ->
            if (d.isDownloaded(song)) add("Remove download" to d::remove) else add("Download" to d::download)
        }
    }
    if (items.isEmpty()) {
        Spacer(Modifier.width(Dimens.m))
        return
    }
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${song.title}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action(song) })
            }
        }
    }
}

/** Generic two-line row with artwork, used for albums, artists and playlists in lists. */
@Composable
fun MediaRow(
    title: String,
    subtitle: String?,
    artwork: Artwork,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    circular: Boolean = false,
    horizontalPadding: Dp = Dimens.pagePaddingCompact,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = Dimens.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (circular) ArtistArt(artwork, Modifier.size(64.dp), ArtSize.Small) else CoverArt(artwork, Modifier.size(64.dp), MaterialTheme.shapes.small, size = ArtSize.Small)
        Spacer(Modifier.width(Dimens.l))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Artwork-led card for horizontal shelves and grids. */
@Composable
fun MediaCard(
    title: String,
    subtitle: String?,
    artwork: Artwork,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    circular: Boolean = false,
) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(bottom = Dimens.s),
    ) {
        val artModifier = Modifier.fillMaxWidth().aspectRatio(1f)
        if (circular) ArtistArt(artwork, artModifier) else CoverArt(artwork, artModifier, MaterialTheme.shapes.medium)
        Spacer(Modifier.size(Dimens.s))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (circular) TextAlign.Center else null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (circular) TextAlign.Center else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Compact shortcut tile from the Home mockup: artwork on the left, title on the right. */
@Composable
fun ShortcutTile(title: String, artwork: Artwork, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverArt(artwork, Modifier.size(64.dp), shape = RectangleShape, size = ArtSize.Small)
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Dimens.m),
            )
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    /** Spoken label for the action, e.g. "Show all playlists". */
    actionDescription: String? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = Dimens.minTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = Dimens.s),
                modifier = if (actionDescription != null) Modifier.semantics { contentDescription = actionDescription } else Modifier,
            ) {
                Text(actionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(Dimens.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.s),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
