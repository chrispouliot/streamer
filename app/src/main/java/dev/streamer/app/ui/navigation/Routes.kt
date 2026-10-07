package dev.streamer.app.ui.navigation

import kotlinx.serialization.Serializable

object Routes {
    @Serializable data object Home
    @Serializable data object Search
    @Serializable data object Library
    @Serializable data object Downloads
    @Serializable data object Settings
    @Serializable data class Album(val id: String)
    @Serializable data class Artist(val id: String)
    @Serializable data class Playlist(val id: String)
    @Serializable data object Connect
}
