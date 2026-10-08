package dev.streamer.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import dev.streamer.app.data.Connection
import dev.streamer.app.data.LibraryRepository
import dev.streamer.app.data.SyncStatus
import dev.streamer.app.data.SyncTarget
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Freshness and pull-to-refresh for one screen's data; owned by that screen's ViewModel. */
class SyncController(private val library: LibraryRepository, private val target: SyncTarget, private val scope: CoroutineScope) {
    val status: StateFlow<SyncStatus> = library.syncStatus(target)
        // Start with what's already known, so the status line doesn't pop in.
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), SyncStatus(connection = library.connection.value))

    private val _refreshing = MutableStateFlow(false)

    /** True only for a user-requested refresh; background refreshes stay quiet. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun refresh() {
        if (_refreshing.value) return
        scope.launch {
            _refreshing.value = true
            try {
                library.refresh(target)
            } finally {
                _refreshing.value = false
            }
        }
    }
}

/**
 * Pull-to-refresh around a screen's scrolling content. Only touch drags pull:
 * a mouse wheel or touchpad scroll has no release or fling, so the indicator
 * would stop part-way and stay there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Refreshable(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val wheelFilter = remember { WheelOverscrollFilter() }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier) {
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(wheelFilter) {
                    awaitPointerEventScope {
                        while (true) {
                            // Initial pass: seen before the list scrolls in response.
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            when (event.type) {
                                PointerEventType.Scroll -> wheelFilter.wheel = true
                                PointerEventType.Press -> wheelFilter.wheel = false
                            }
                        }
                    }
                }
                .nestedScroll(wheelFilter),
        ) { content() }
    }
}

/**
 * Sits between the list and the pull-to-refresh box. Parents see post-scroll
 * after this, so swallowing the overscroll here keeps wheel scrolling from
 * pulling the indicator; touch scrolling passes through untouched.
 */
private class WheelOverscrollFilter : NestedScrollConnection {
    var wheel = false

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (wheel) available else Offset.Zero
}

/**
 * One-line freshness note. Problems are always shown (content stays visible);
 * "Updated …" only when [showWhenFine].
 */
@Composable
fun SyncStatusText(status: SyncStatus, modifier: Modifier = Modifier, showWhenFine: Boolean = false) {
    val now = Instant.now()
    val text = when {
        // Known immediately, so these never change while the screen appears.
        status.connection != Connection.Online -> when (status.connection) {
            Connection.OfflineMode -> "Offline mode"
            Connection.NoNetwork -> "No connection"
            else -> "Can't reach your server"
        } + " · showing saved music"
        status.error != null -> "Couldn't update · showing saved music" +
            (status.lastUpdated?.let { " from ${relativeTime(it, now)}" } ?: "")
        showWhenFine && status.lastUpdated != null -> "Updated ${relativeTime(status.lastUpdated, now)}"
        else -> return
    }
    Box(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (status.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
