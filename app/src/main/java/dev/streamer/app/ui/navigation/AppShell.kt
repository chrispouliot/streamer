package dev.streamer.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.streamer.app.LocalAppContainer
import dev.streamer.app.data.Connection
import dev.streamer.app.data.SyncTarget
import dev.streamer.app.data.account.SessionState
import dev.streamer.app.playback.PlayerController
import dev.streamer.app.ui.account.ConnectRoute
import dev.streamer.app.ui.components.rememberFavorites
import dev.streamer.app.ui.detail.AlbumRoute
import dev.streamer.app.ui.detail.ArtistRoute
import dev.streamer.app.ui.detail.PlaylistRoute
import dev.streamer.app.ui.downloads.DownloadsRoute
import dev.streamer.app.ui.home.HomeRoute
import dev.streamer.app.ui.icons.AppIcons
import dev.streamer.app.ui.library.LibraryRoute
import dev.streamer.app.ui.player.CollapsedTarget
import dev.streamer.app.ui.player.MiniPlayer
import dev.streamer.app.ui.player.MiniPlayerCard
import dev.streamer.app.ui.player.PlayerOverlay
import dev.streamer.app.ui.player.PlayerPane
import dev.streamer.app.ui.player.PlayerPanePanel
import dev.streamer.app.ui.player.rememberSheetMotion
import dev.streamer.app.ui.search.SearchRoute
import dev.streamer.app.ui.settings.SettingsRoute
import dev.streamer.app.ui.theme.Dimens
import dev.streamer.app.ui.theme.playerTint
import kotlinx.coroutines.launch

private const val EnterMillis = 150
private const val ExitMillis = 90

private val TopLevel.icon: ImageVector
    get() = when (this) {
        TopLevel.Home -> Icons.Filled.Home
        TopLevel.Search -> Icons.Filled.Search
        TopLevel.Library -> AppIcons.LibraryMusic
        TopLevel.Downloads -> AppIcons.Download
        TopLevel.Settings -> Icons.Filled.Settings
    }

/**
 * Adaptive app frame. The NavHost keeps the same position in the composition
 * for every layout, so resizing or rotating never recreates navigation, and
 * all player surfaces observe the single [player].
 */
@Composable
fun AppShell(player: PlayerController) {
    // A Surface (not a bare background) so text and icons default to
    // onBackground; without it they fall back to black in the dark theme.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AppFrame(player)
    }
}

@Composable
private fun AppFrame(player: PlayerController) {
    val navController = rememberNavController()
    val sheetState = rememberSaveable { mutableStateOf<PlayerSheet?>(null) }
    val sheet = sheetState.value
    val navigator = remember(navController, sheetState) { AppNavigator(navController, sheetState) }
    val playerState by player.state.collectAsStateWithLifecycle()
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    var selected by rememberSaveable { mutableStateOf(TopLevel.Home) }
    LaunchedEffect(destination) {
        TopLevel.entries.firstOrNull { destination?.hasRoute(it.route::class) == true }?.let { selected = it }
    }
    val hasPlayer = playerState.hasQueue
    LaunchedEffect(hasPlayer) { if (!hasPlayer) navigator.closePlayer() }
    val favorites = rememberFavorites()
    val container = LocalAppContainer.current
    val session by container.accounts.state.collectAsStateWithLifecycle()
    val reauthReason = (session as? SessionState.Active)?.reauthReason
    val connection by container.policy.connection.collectAsStateWithLifecycle()
    // Re-check the server whenever the app comes back to the foreground.
    LifecycleResumeEffect(container) {
        container.serverProbe.probe()
        onPauseOrDispose { }
    }
    val bannerScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(container.messages) {
        container.messages.messages.collect { snackbar.showSnackbar(it, withDismissAction = true) }
    }
    var railWidthPx by remember { mutableIntStateOf(0) }
    var miniHeightPx by remember { mutableIntStateOf(0) }
    // Where the player sheet collapses to: the mini-player, or the pane on wide windows.
    var miniBounds by remember { mutableStateOf<Rect?>(null) }
    var paneBounds by remember { mutableStateOf<Rect?>(null) }
    val sheetMotion = rememberSheetMotion()
    // The real mini-player/pane is hidden while the sheet shows, since the sheet
    // draws its own copy as it morphs. Read in the draw phase only.
    val hiddenWhileSheetShows = Modifier.graphicsLayer { alpha = if (sheetMotion.progress < 1f) 0f else 1f }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = ShellLayout.forWindowWidth(maxWidth)
        val sheetOpen = sheet != null
        // Stays composed under the open sheet (hidden) so its bounds stay current.
        val showPane = layout.playerPaneFits && hasPlayer
        val showMini = !layout.playerPaneFits && hasPlayer
        val showBar = !layout.usesRail
        val showRail = layout.usesRail

        // Top is handled per page (see WindowInsets.topBar).
        var sides: WindowInsetsSides? = null
        fun add(side: WindowInsetsSides) { sides = sides?.plus(side) ?: side }
        if (!showRail) add(WindowInsetsSides.Start)
        if (!showPane) add(WindowInsetsSides.End)
        if (!showBar) add(WindowInsetsSides.Bottom)
        val contentInsets = sides?.let { Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(it)) } ?: Modifier

        CompositionLocalProvider(LocalShellLayout provides layout, LocalConnection provides connection) {
            Row(Modifier.fillMaxSize()) {
                if (showRail) AppRail(selected, { navigator.selectTopLevel(it, reselected = it == selected) }, Modifier.onSizeChanged { railWidthPx = it.width })
                Column(Modifier.weight(1f)) {
                    // The mini-player floats over the bottom of the content; pages
                    // scroll underneath it and pad their ends by its height.
                    Box(Modifier.weight(1f).then(contentInsets)) {
                        val floatingHeight = if (showMini) with(LocalDensity.current) { miniHeightPx.toDp() } else 0.dp
                        Column(Modifier.fillMaxSize()) {
                            val bannerShown = connection != Connection.Online || reauthReason != null
                            val bannerInsets = Modifier.windowInsetsPadding(WindowInsets.topBar)
                            when (connection) {
                                Connection.OfflineMode -> ConnectionBanner(
                                    "Offline mode · only downloaded music plays",
                                    "Go online",
                                    { bannerScope.launch { container.settings.setOfflineOnly(false) } },
                                    bannerInsets,
                                )
                                Connection.NoNetwork -> ConnectionBanner("No connection · only downloaded music plays", null, null, bannerInsets)
                                Connection.ServerUnreachable -> ConnectionBanner(
                                    "Can't reach your server · only downloaded music plays",
                                    "Retry",
                                    {
                                        container.serverProbe.probe()
                                        bannerScope.launch { container.library.refresh(SyncTarget.Home) }
                                    },
                                    bannerInsets,
                                )
                                Connection.Online -> Unit
                            }
                            if (connection == Connection.Online && reauthReason != null) {
                                ReauthBanner(reauthReason, navigator::openReconnect, Modifier.windowInsetsPadding(WindowInsets.topBar))
                            }
                            // With a banner on top, pages below must not pad for the status bar again.
                            val topConsumed = if (bannerShown) Modifier.consumeWindowInsets(WindowInsets.topBar) else Modifier
                            Box(Modifier.weight(1f).then(topConsumed)) {
                                CompositionLocalProvider(LocalFloatingPlayerHeight provides floatingHeight) {
                                    AppNavHost(navController, navigator, player)
                                }
                            }
                        }
                        SnackbarHost(
                            snackbar,
                            Modifier.align(Alignment.BottomCenter).padding(bottom = floatingHeight),
                        )
                        if (showMini) {
                            MiniPlayer(
                                playerState,
                                player,
                                favorites,
                                onOpen = navigator::openNowPlaying,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .onSizeChanged { miniHeightPx = it.height },
                                cardModifier = Modifier
                                    .onGloballyPositioned { miniBounds = it.boundsInRoot() }
                                    .then(hiddenWhileSheetShows),
                            )
                        }
                    }
                    if (showBar) AppBottomBar(selected) { navigator.selectTopLevel(it, reselected = it == selected) }
                }
                if (showPane) PlayerPane(
                        playerState,
                        player,
                        favorites,
                        onExpand = navigator::openNowPlaying,
                        panelModifier = Modifier
                            .onGloballyPositioned { paneBounds = it.boundsInRoot() }
                            .then(hiddenWhileSheetShows),
                    )
            }

            // Wide windows keep the rail visible beside the expanded player.
            if (hasPlayer) {
                val sheetStart = if (layout.playerPaneFits) with(LocalDensity.current) { railWidthPx.toDp() } else 0.dp
                // Keep showing the last sheet while it collapses (plain holder, not state).
                val lastSheet = remember { arrayOf(PlayerSheet.Player) }
                if (sheet != null) lastSheet[0] = sheet
                val tint = playerTint(playerState.current?.song?.artwork)
                val collapsed = if (layout.playerPaneFits) {
                    paneBounds?.let { bounds ->
                        CollapsedTarget(bounds, MaterialTheme.colorScheme.surfaceContainer, Dimens.paneCorner) {
                            PlayerPanePanel(playerState, player, favorites, onExpand = {}, modifier = Modifier.fillMaxSize())
                        }
                    }
                } else {
                    miniBounds?.let { bounds ->
                        CollapsedTarget(bounds, tint, Dimens.miniPlayerCorner) {
                            MiniPlayerCard(playerState, player, favorites, onOpen = {}, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                PlayerOverlay(
                    open = sheetOpen,
                    sheet = lastSheet[0],
                    motion = sheetMotion,
                    collapsed = collapsed,
                    state = playerState,
                    player = player,
                    favorites = favorites,
                    navigator = navigator,
                    modifier = Modifier.padding(start = sheetStart),
                )
            }
        }
    }
    // Composed after the NavHost so it takes precedence over its back handling.
    BackHandler(enabled = sheet != null) { navigator.back() }
}

@Composable
private fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    navigator: AppNavigator,
    player: PlayerController,
) {
    // Short fades everywhere. The library defaults are 700ms fades, and its
    // default predictive-back animation scales the outgoing page down.
    NavHost(
        navController,
        startDestination = Routes.Home,
        enterTransition = { fadeIn(tween(EnterMillis, delayMillis = ExitMillis)) },
        exitTransition = { fadeOut(tween(ExitMillis)) },
        popEnterTransition = { fadeIn(tween(EnterMillis, delayMillis = ExitMillis)) },
        popExitTransition = { fadeOut(tween(ExitMillis)) },
        predictivePopEnterTransition = { fadeIn(tween(EnterMillis)) },
        predictivePopExitTransition = { fadeOut(tween(ExitMillis)) },
    ) {
        composable<Routes.Home> { TopInset { HomeRoute(navigator, player) } }
        composable<Routes.Search> { TopInset { SearchRoute(navigator, player) } }
        composable<Routes.Library> { TopInset { LibraryRoute(navigator, player) } }
        composable<Routes.Downloads> { TopInset { DownloadsRoute(navigator, player) } }
        composable<Routes.Settings> { TopInset { SettingsRoute(navigator) } }
        // Album, artist and playlist pages draw their tinted header behind the status bar.
        composable<Routes.Album> { AlbumRoute(it.toRoute<Routes.Album>().id, navigator, player) }
        composable<Routes.Artist> { ArtistRoute(it.toRoute<Routes.Artist>().id, navigator, player) }
        composable<Routes.Playlist> { PlaylistRoute(it.toRoute<Routes.Playlist>().id, navigator, player) }
        composable<Routes.Connect> {
            val session by LocalAppContainer.current.accounts.state.collectAsStateWithLifecycle()
            ConnectRoute(
                reconnect = (session as? SessionState.Active)?.account,
                onConnected = navigator::back,
                onBack = navigator::back,
            )
        }
    }
}

@Composable
private fun AppBottomBar(selected: TopLevel, onSelect: (TopLevel) -> Unit) {
    NavigationBar {
        listOf(TopLevel.Home, TopLevel.Search, TopLevel.Library).forEach { item ->
            NavigationBarItem(
                selected = item == selected,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
    }
}

@Composable
private fun AppRail(selected: TopLevel, onSelect: (TopLevel) -> Unit, modifier: Modifier = Modifier) {
    NavigationRail(modifier, containerColor = MaterialTheme.colorScheme.background) {
        listOf(TopLevel.Home, TopLevel.Search, TopLevel.Library, TopLevel.Downloads).forEach { item ->
            NavigationRailItem(
                selected = item == selected,
                onClick = { onSelect(item) },
                icon = { Icon(item.icon, contentDescription = null) },
                label = { Text(item.label) },
            )
        }
        Spacer(Modifier.weight(1f))
        NavigationRailItem(
            selected = selected == TopLevel.Settings,
            onClick = { onSelect(TopLevel.Settings) },
            icon = { Icon(TopLevel.Settings.icon, contentDescription = null) },
            label = { Text(TopLevel.Settings.label) },
        )
    }
}

/** Pads a plain page below the status bar. */
@Composable
private fun TopInset(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.topBar)) { content() }
}

@Composable
private fun ReauthBanner(reason: String, onReconnect: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(
            modifier.fillMaxWidth().padding(start = Dimens.l, end = Dimens.s, top = Dimens.xs, bottom = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Signed out of your server. $reason Showing saved music.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReconnect) { Text("Reconnect") }
        }
    }
}

@Composable
private fun ConnectionBanner(text: String, actionLabel: String?, onAction: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(
            modifier.fillMaxWidth().heightIn(min = Dimens.minTouchTarget).padding(start = Dimens.l, end = Dimens.s, top = Dimens.xs, bottom = Dimens.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(AppIcons.CloudOff, contentDescription = null)
            Spacer(Modifier.width(Dimens.m))
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
