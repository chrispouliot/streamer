package dev.streamer.app.ui.player

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.playback.PlayerState
import dev.streamer.app.playback.QueueItem
import dev.streamer.app.ui.components.SongRow
import dev.streamer.app.ui.icons.AppIcons
import dev.streamer.app.ui.theme.Dimens
import dev.streamer.app.ui.theme.OverlineStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Current entry plus upcoming entries. Upcoming entries can be dragged by their
 * handle to reorder (not while shuffled) and have move/remove actions in their
 * menu, which also serve keyboard and accessibility users.
 */
@Composable
fun QueueList(
    state: PlayerState,
    player: PlayerController,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = Dimens.l),
    horizontalPadding: Dp = Dimens.pagePaddingCompact,
    /** Whether to list the current song above the upcoming ones. */
    showCurrent: Boolean = true,
    /** Items placed above the queue in the same scrolling list. */
    header: LazyListScope.() -> Unit = {},
) {
    val current = state.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val drag = remember(listState, scope) { QueueDragState(listState, scope) }
    drag.latest = state.upNext
    val upNext = drag.displayed(state.upNext)
    val canReorder = !state.shuffle
    LazyColumn(modifier, state = listState, contentPadding = contentPadding) {
        header()
        if (showCurrent && current != null) {
            item(key = "now-header") { QueueHeading("NOW PLAYING", horizontalPadding) }
            item(key = current.occurrenceId) {
                SongRow(current.song, onClick = null, isCurrent = true, horizontalPadding = horizontalPadding)
            }
        }
        item(key = "next-header") {
            val from = state.source?.title
            QueueHeading(if (from != null) "NEXT FROM ${from.uppercase()}" else "NEXT UP", horizontalPadding)
        }
        if (upNext.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing queued after this song.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = horizontalPadding, vertical = Dimens.s),
                )
            }
        }
        itemsIndexed(upNext, key = { _, item -> item.occurrenceId }) { index, item ->
            val dragging = drag.draggingId == item.occurrenceId
            val rowModifier = if (dragging) {
                Modifier.zIndex(1f).graphicsLayer { translationY = drag.draggedTranslation() }
            } else {
                Modifier.animateItem()
            }
            // Lifted look while dragging; transparent otherwise so the list's background shows.
            Surface(
                rowModifier,
                color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                shape = MaterialTheme.shapes.medium,
                shadowElevation = if (dragging) 4.dp else 0.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SongRow(
                        item.song,
                        onClick = { player.skipTo(item.occurrenceId) },
                        modifier = Modifier.weight(1f),
                        horizontalPadding = horizontalPadding,
                        // Queue actions replace the song menu here.
                    )
                    QueueItemMenu(item, isFirst = index == 0, isLast = index == upNext.lastIndex, canReorder = canReorder, player)
                    if (canReorder) {
                        Box(
                            Modifier
                                .size(Dimens.minTouchTarget)
                                .pointerInput(item.occurrenceId) {
                                    detectDragGestures(
                                        onDragStart = { drag.start(item.occurrenceId) },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            drag.dragBy(amount.y)
                                        },
                                        onDragEnd = { drag.end(player) },
                                        onDragCancel = { drag.cancel() },
                                    )
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            // Mouse/touch only; the menu's move actions cover other input.
                            Icon(AppIcons.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    // A finished drag shows its local order until the player reports the move.
    LaunchedEffect(state.upNext) { drag.playerUpdated() }
}

/**
 * Drag-to-reorder for the upcoming entries. While dragging, the list shows a
 * local order and the player is told once, on release, so a drag never waits
 * on the player or fights its updates.
 */
private class QueueDragState(private val listState: LazyListState, private val scope: CoroutineScope) {
    /** The player's current upcoming entries (read by gesture callbacks, so not captured). */
    var latest: List<QueueItem> = emptyList()

    var draggingId by mutableStateOf<Long?>(null)
        private set

    /** Local order while dragging, and after release until the player catches up. */
    private var order by mutableStateOf<List<QueueItem>?>(null)
    private var startIndex = -1
    private var initialOffset = 0
    private var delta by mutableFloatStateOf(0f)
    private var autoScroll: Job? = null
    private var expiry: Job? = null

    fun displayed(upNext: List<QueueItem>): List<QueueItem> = order ?: upNext

    fun start(id: Long) {
        val upNext = order ?: latest
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        expiry?.cancel()
        order = upNext
        startIndex = upNext.indexOfFirst { it.occurrenceId == id }
        draggingId = id
        initialOffset = info.offset
        delta = 0f
    }

    /** Translation that keeps the dragged row under the pointer as its slot moves. */
    fun draggedTranslation(): Float {
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingId } ?: return 0f
        return initialOffset + delta - info.offset
    }

    fun dragBy(dy: Float) {
        delta += dy
        swapIfCrossed()
        updateAutoScroll()
    }

    private fun swapIfCrossed() {
        val id = draggingId ?: return
        val current = order ?: return
        val visible = listState.layoutInfo.visibleItemsInfo
        val dragged = visible.firstOrNull { it.key == id } ?: return
        val top = initialOffset + delta
        val bottom = top + dragged.size
        val ids = current.mapTo(HashSet()) { it.occurrenceId }
        // The queue row whose middle the dragged row's leading edge has passed.
        val target = visible.firstOrNull { item ->
            item.key != id && item.key in ids && if (delta > 0) {
                item.index > dragged.index && bottom > item.offset + item.size / 2
            } else {
                item.index < dragged.index && top < item.offset + item.size / 2
            }
        } ?: return
        val from = current.indexOfFirst { it.occurrenceId == id }
        val to = current.indexOfFirst { it.occurrenceId == target.key }
        // Moving the first visible row would otherwise scroll the list with it.
        val first = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        order = current.toMutableList().apply { add(to, removeAt(from)) }
        if (dragged.index == first || target.index == first) {
            scope.launch { listState.scrollToItem(first, firstOffset) }
        }
    }

    /** Scrolls while the dragged row is held near the top or bottom of the list. */
    private fun updateAutoScroll() {
        val info = listState.layoutInfo
        val dragged = info.visibleItemsInfo.firstOrNull { it.key == draggingId }
        val top = initialOffset + delta
        val edge = (dragged?.size ?: 0).coerceAtLeast(1)
        val speed = when {
            dragged == null -> 0f
            top + dragged.size > info.viewportEndOffset - edge / 2 -> 12f
            top < info.viewportStartOffset + edge / 2 -> -12f
            else -> 0f
        }
        if (speed == 0f) {
            autoScroll?.cancel()
            autoScroll = null
        } else if (autoScroll == null) {
            autoScroll = scope.launch {
                while (isActive && draggingId != null) {
                    val scrolled = listState.scrollBy(speed)
                    if (scrolled == 0f) break
                    // The row stays under the pointer; rows scroll past it.
                    swapIfCrossed()
                    delay(16)
                }
                autoScroll = null
            }
        }
    }

    fun end(player: PlayerController) {
        val id = draggingId
        val to = order?.indexOfFirst { it.occurrenceId == id } ?: -1
        stopDragging()
        if (id != null && startIndex >= 0 && to >= 0 && to != startIndex) {
            player.moveUpcoming(id, to - startIndex)
            // Fall back to the player's order if it never reports the move.
            expiry = scope.launch {
                delay(2_000)
                order = null
            }
        } else {
            order = null
        }
    }

    fun cancel() {
        stopDragging()
        order = null
    }

    fun playerUpdated() {
        if (draggingId == null) {
            expiry?.cancel()
            order = null
        }
    }

    private fun stopDragging() {
        autoScroll?.cancel()
        autoScroll = null
        draggingId = null
        delta = 0f
    }
}

@Composable
private fun QueueHeading(text: String, horizontalPadding: Dp) {
    Text(
        text,
        style = OverlineStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = horizontalPadding, end = horizontalPadding, top = Dimens.l, bottom = Dimens.s)
            .semantics { heading() },
    )
}

@Composable
private fun QueueItemMenu(item: QueueItem, isFirst: Boolean, isLast: Boolean, canReorder: Boolean, player: PlayerController) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Queue options for ${item.song.title}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Play now") }, onClick = { open = false; player.skipTo(item.occurrenceId) })
            if (canReorder && !isFirst) {
                DropdownMenuItem(text = { Text("Move to top") }, onClick = { open = false; player.moveUpcoming(item.occurrenceId, Int.MIN_VALUE / 2) })
                DropdownMenuItem(text = { Text("Move up") }, onClick = { open = false; player.moveUpcoming(item.occurrenceId, -1) })
            }
            if (canReorder && !isLast) {
                DropdownMenuItem(text = { Text("Move down") }, onClick = { open = false; player.moveUpcoming(item.occurrenceId, 1) })
            }
            DropdownMenuItem(text = { Text("Remove from queue") }, onClick = { open = false; player.remove(item.occurrenceId) })
        }
    }
}
