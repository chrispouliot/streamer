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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.streamer.app.playback.PlayerController
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

        var sides = WindowInsetsSides.Top
        if (!showRail) sides += WindowInsetsSides.Start
        if (!showPane) sides += WindowInsetsSides.End
        if (!showBar) sides += WindowInsetsSides.Bottom

        CompositionLocalProvider(LocalShellLayout provides layout) {
            Row(Modifier.fillMaxSize()) {
                if (showRail) AppRail(selected, navigator::selectTopLevel, Modifier.onSizeChanged { railWidthPx = it.width })
                Column(Modifier.weight(1f)) {
                    // The mini-player floats over the bottom of the content; pages
                    // scroll underneath it and pad their ends by its height.
                    Box(Modifier.weight(1f).windowInsetsPadding(WindowInsets.safeDrawing.only(sides))) {
                        val floatingHeight = if (showMini) with(LocalDensity.current) { miniHeightPx.toDp() } else 0.dp
                        CompositionLocalProvider(LocalFloatingPlayerHeight provides floatingHeight) {
                            AppNavHost(navController, navigator, player)
                        }
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
                    if (showBar) AppBottomBar(selected, navigator::selectTopLevel)
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
                val tint = playerState.current?.song?.artwork?.let { playerTint(it) }
                val collapsed = if (layout.playerPaneFits) {
                    paneBounds?.let { bounds ->
                        CollapsedTarget(bounds, MaterialTheme.colorScheme.surfaceContainer, Dimens.paneCorner) {
                            PlayerPanePanel(playerState, player, favorites, onExpand = {}, modifier = Modifier.fillMaxSize())
                        }
                    }
                } else {
                    miniBounds?.let { bounds ->
                        CollapsedTarget(bounds, tint ?: MaterialTheme.colorScheme.surfaceContainer, Dimens.miniPlayerCorner) {
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
        composable<Routes.Home> { HomeRoute(navigator, player) }
        composable<Routes.Search> { SearchRoute(navigator, player) }
        composable<Routes.Library> { LibraryRoute(navigator, player) }
        composable<Routes.Downloads> { DownloadsRoute(navigator) }
        composable<Routes.Settings> { SettingsRoute(navigator) }
        composable<Routes.Album> { AlbumRoute(it.toRoute<Routes.Album>().id, navigator, player) }
        composable<Routes.Artist> { ArtistRoute(it.toRoute<Routes.Artist>().id, navigator, player) }
        composable<Routes.Playlist> { PlaylistRoute(it.toRoute<Routes.Playlist>().id, navigator, player) }
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
