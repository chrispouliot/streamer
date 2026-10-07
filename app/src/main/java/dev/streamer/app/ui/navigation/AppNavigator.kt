package dev.streamer.app.ui.navigation

import androidx.compose.runtime.MutableState
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

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
    fun selectTopLevel(destination: TopLevel) {
        closePlayer()
        nav.navigate(destination.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openAlbum(id: String) = navigate(Routes.Album(id))
    fun openArtist(id: String) = navigate(Routes.Artist(id))
    fun openPlaylist(id: String) = navigate(Routes.Playlist(id))
    fun openSettings() = navigate(Routes.Settings, singleTop = true)
    fun openDownloads() = navigate(Routes.Downloads, singleTop = true)
    fun openSearch() = selectTopLevel(TopLevel.Search)

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
