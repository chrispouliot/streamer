package dev.streamer.app.ui.navigation

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import dev.streamer.app.ui.library.LibraryRequest

enum class TopLevel(val label: String, val route: Any) {
    Home("Home", Routes.Home),
    Search("Search", Routes.Search),
    Library("Library", Routes.Library),
    Downloads("Downloads", Routes.Downloads),
    Settings("Settings", Routes.Settings),
}

/** Full player shown as a sheet above the app, so dismissing it reveals the page underneath. */
enum class PlayerSheet { Player, Queue }

/**
 * Navigation actions for screens, so they never touch the NavController or
 * the player sheet state directly. Opening any page closes the player sheet.
 */
class AppNavigator(private val nav: NavHostController, private val sheet: MutableState<PlayerSheet?>) {
    /**
     * Home always returns to the Home page itself; re-selecting the current tab
     * returns to that tab's main page; switching to another tab restores where
     * the user was in it.
     */
    fun selectTopLevel(destination: TopLevel, reselected: Boolean = false) {
        closePlayer()
        when {
            destination == TopLevel.Home -> nav.popBackStack(Routes.Home, inclusive = false)
            reselected -> nav.popBackStack(destination.route, inclusive = false)
            else -> nav.navigate(destination.route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    fun openAlbum(id: String) = navigate(Routes.Album(id))
    fun openArtist(id: String) = navigate(Routes.Artist(id))
    fun openPlaylist(id: String) = navigate(Routes.Playlist(id))
    fun openSettings() = navigate(Routes.Settings, singleTop = true)
    fun openDownloads() = navigate(Routes.Downloads, singleTop = true)
    fun openSearch() = selectTopLevel(TopLevel.Search)

    /** Pending view for Library to show next (consumed by Library). */
    var libraryRequest by mutableStateOf<LibraryRequest?>(null)

    fun openLibrary(request: LibraryRequest) {
        libraryRequest = request
        selectTopLevel(TopLevel.Library)
    }
    fun openReconnect() = navigate(Routes.Connect, singleTop = true)

    fun openNowPlaying() {
        sheet.value = PlayerSheet.Player
    }

    fun openQueue() {
        sheet.value = PlayerSheet.Queue
    }

    fun closePlayer() {
        sheet.value = null
    }

    /** Queue → player → page underneath → previous page. */
    fun back() {
        when (sheet.value) {
            PlayerSheet.Queue -> sheet.value = PlayerSheet.Player
            PlayerSheet.Player -> sheet.value = null
            null -> nav.popBackStack()
        }
    }

    private fun navigate(route: Any, singleTop: Boolean = false) {
        closePlayer()
        nav.navigate(route) { launchSingleTop = singleTop }
    }
}
