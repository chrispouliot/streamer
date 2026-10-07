package dev.streamer.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.streamer.app.data.SyncStatus
import dev.streamer.app.data.downloads.CollectionDownloadStatus
import dev.streamer.app.model.Artwork
import dev.streamer.app.ui.components.ArtSize
import dev.streamer.app.ui.components.ArtistArt
import dev.streamer.app.ui.components.BackBar
import dev.streamer.app.ui.components.CoverArt
import dev.streamer.app.ui.components.EmptyState
import dev.streamer.app.ui.components.PlayPauseButton
import dev.streamer.app.ui.components.Refreshable
import dev.streamer.app.ui.components.ShufflePlayButton
import dev.streamer.app.ui.components.SyncStatusText
import dev.streamer.app.ui.downloads.CollectionDownloadActions
import dev.streamer.app.ui.downloads.CollectionDownloadButton
import dev.streamer.app.ui.downloads.describe
import dev.streamer.app.ui.icons.AppIcons
import dev.streamer.app.ui.navigation.LocalFloatingPlayerHeight
import dev.streamer.app.ui.navigation.LocalShellLayout
import dev.streamer.app.ui.navigation.topBar
import dev.streamer.app.ui.theme.Dimens
import dev.streamer.app.ui.theme.playerTint

sealed interface DetailState<out T> {
    data object Loading : DetailState<Nothing>
    data object NotFound : DetailState<Nothing>
    data class Loaded<T>(val value: T) : DetailState<T>
}

/** Byline under the title: an artist link (album) or plain text (playlist owner). */
data class Byline(val text: String, val artwork: Artwork? = null, val onClick: (() -> Unit)? = null)

/** Header content shared by album and playlist screens. */
data class CollectionHeader(
    val title: String,
    val artwork: Artwork,
    val byline: Byline?,
    val meta: String,
    val description: String? = null,
)

/**
 * Artwork-led collection page. Narrow: one column with the header above the
 * tracks. Wide: artwork and metadata beside the track list.
 */
@Composable
fun CollectionScreen(
    state: DetailState<CollectionHeader>,
    onBack: () -> Unit,
    isPlayingThis: Boolean,
    hasSongs: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    emptyMessage: String,
    sync: SyncStatus = SyncStatus(),
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    downloadStatus: CollectionDownloadStatus? = null,
    downloadActions: CollectionDownloadActions? = null,
    /** Sort control shown opposite the play buttons (top of the list on wide layouts). */
    sortControl: (@Composable (Modifier) -> Unit)? = null,
    topActions: @Composable RowScope.() -> Unit = {},
    tracks: LazyListScope.(horizontalPadding: androidx.compose.ui.unit.Dp) -> Unit,
) {
    val header = when (state) {
        DetailState.Loading -> {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.topBar)) {
                BackBar(onBack)
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            return
        }
        DetailState.NotFound -> {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.topBar)) {
                BackBar(onBack)
                EmptyState("Not available", "This item could not be found on your server.")
            }
            return
        }
        is DetailState.Loaded -> state.value
    }
    val pad = LocalShellLayout.current.pagePadding
    val tint = playerTint(header.artwork)
    val gradient = Brush.verticalGradient(0f to tint, 0.45f to MaterialTheme.colorScheme.background)
    // The tint starts behind the status bar; only the content is inset.
    Refreshable(refreshing, onRefresh, Modifier.fillMaxSize().background(gradient).windowInsetsPadding(WindowInsets.topBar)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width = maxWidth
            if (width >= 760.dp) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .width((width * 0.38f).coerceIn(300.dp, 400.dp))
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = Dimens.xs, bottom = Dimens.xl + LocalFloatingPlayerHeight.current),
                    ) {
                        BackBar(onBack, actions = topActions)
                        Column(Modifier.padding(horizontal = pad - Dimens.xs)) {
                            CoverArt(header.artwork, Modifier.fillMaxWidth().aspectRatio(1f), MaterialTheme.shapes.large, "Artwork", ArtSize.Large)
                            Spacer(Modifier.height(Dimens.l))
                            HeaderText(header, sync, downloadStatus, center = false)
                            Spacer(Modifier.height(Dimens.l))
                            if (hasSongs) WidePlayButtons(isPlayingThis, onPlay, onShuffle, downloadStatus, downloadActions)
                        }
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxSize(), contentPadding = PaddingValues(top = 56.dp, bottom = Dimens.xl + LocalFloatingPlayerHeight.current, end = Dimens.s)) {
                        if (hasSongs && sortControl != null) item(key = "sort") { sortControl(Modifier.padding(start = Dimens.s)) }
                        if (!hasSongs) item { EmptyState("No songs", emptyMessage) }
                        tracks(Dimens.s)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Dimens.xl + LocalFloatingPlayerHeight.current)) {
                    item(key = "back") { BackBar(onBack, actions = topActions) }
                    item(key = "header") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = pad), horizontalAlignment = Alignment.CenterHorizontally) {
                            CoverArt(
                                header.artwork,
                                Modifier.fillMaxWidth(0.62f).widthIn(max = 300.dp).aspectRatio(1f),
                                MaterialTheme.shapes.large,
                                "Artwork",
                                ArtSize.Large,
                            )
                            Spacer(Modifier.height(Dimens.xl))
                            HeaderText(
                                header,
                                sync,
                                downloadStatus,
                                center = false,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (hasSongs) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    // Sort on the left (it shrinks first), playback buttons on the right.
                                    if (sortControl != null) sortControl(Modifier.weight(1f, fill = false)) else Spacer(Modifier)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (downloadActions != null) CollectionDownloadButton(downloadStatus, downloadActions)
                                    ShufflePlayButton(onClick = onShuffle)
                                    Spacer(Modifier.width(Dimens.s))
                                    PlayPauseButton(isPlayingThis, onPlay, size = 64.dp)
                                    }
                                }
                            }
                        }
                    }
                    if (!hasSongs) item(key = "empty") { EmptyState("No songs", emptyMessage) }
                    tracks(Dimens.s)
                }
            }
        }
    }
}

@Composable
private fun HeaderText(
    header: CollectionHeader,
    sync: SyncStatus,
    download: CollectionDownloadStatus?,
    center: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.s)) {
        Text(
            header.title,
            style = MaterialTheme.typography.headlineLarge,
            textAlign = if (center) TextAlign.Center else null,
            modifier = Modifier.semantics { heading() },
        )
        header.byline?.let { byline ->
            Row(
                Modifier
                    .heightIn(min = Dimens.minTouchTarget)
                    .then(
                        if (byline.onClick != null) {
                            Modifier.clickable(onClickLabel = "Go to artist", role = Role.Button, onClick = byline.onClick)
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = Dimens.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                byline.artwork?.let { ArtistArt(it, Modifier.size(28.dp), size = ArtSize.Small); Spacer(Modifier.width(Dimens.s)) }
                Text(byline.text, style = MaterialTheme.typography.titleSmall)
            }
        }
        if (!header.description.isNullOrBlank()) {
            Text(header.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(header.meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SyncStatusText(sync, showWhenFine = true)
        download?.let {
            Text(
                it.describe(),
                style = MaterialTheme.typography.bodySmall,
                color = if (it.failed > 0 || (it.outOfDate && !it.keepUpdated)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WidePlayButtons(
    isPlayingThis: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    downloadStatus: CollectionDownloadStatus?,
    downloadActions: CollectionDownloadActions?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.s), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onPlay, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
            Icon(if (isPlayingThis) AppIcons.Pause else Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(Dimens.s))
            Text(if (isPlayingThis) "Pause" else "Play")
        }
        // Download sits just left of Shuffle.
        if (downloadActions != null) CollectionDownloadButton(downloadStatus, downloadActions)
        FilledTonalButton(onClick = onShuffle, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
            Icon(AppIcons.Shuffle, contentDescription = null)
            Spacer(Modifier.width(Dimens.s))
            Text("Shuffle")
        }
    }
}
