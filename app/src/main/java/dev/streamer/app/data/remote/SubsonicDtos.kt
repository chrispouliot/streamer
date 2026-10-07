package dev.streamer.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Subsonic/OpenSubsonic JSON shapes. Everything except IDs is optional:
// servers omit absent values, and unknown fields are ignored.

@Serializable
data class ChildDto(
    val id: String,
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val coverArt: String? = null,
    /** Seconds. */
    val duration: Int? = null,
    val suffix: String? = null,
    val contentType: String? = null,
    val bitRate: Int? = null,
    /** Timestamp when starred; absent when not starred. */
    val starred: String? = null,
    /** OpenSubsonic: "explicit", "clean" or "". */
    val explicitStatus: String? = null,
    val isDir: Boolean = false,
)

@Serializable
data class AlbumDto(
    val id: String,
    val name: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val year: Int? = null,
    val created: String? = null,
    val starred: String? = null,
    val song: List<ChildDto> = emptyList(),
)

@Serializable
data class ArtistDto(
    val id: String,
    val name: String? = null,
    val coverArt: String? = null,
    val albumCount: Int? = null,
    val starred: String? = null,
    val album: List<AlbumDto> = emptyList(),
)

@Serializable
data class ArtistIndexDto(val name: String? = null, val artist: List<ArtistDto> = emptyList())

@Serializable
data class ArtistsDto(val index: List<ArtistIndexDto> = emptyList())

@Serializable
data class PlaylistDto(
    val id: String,
    val name: String? = null,
    val comment: String? = null,
    val owner: String? = null,
    @SerialName("public") val isPublic: Boolean? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val created: String? = null,
    val changed: String? = null,
    val coverArt: String? = null,
    val entry: List<ChildDto> = emptyList(),
)

@Serializable
data class PlaylistsDto(val playlist: List<PlaylistDto> = emptyList())

@Serializable
data class AlbumListDto(val album: List<AlbumDto> = emptyList())

@Serializable
data class SearchResultDto(
    val artist: List<ArtistDto> = emptyList(),
    val album: List<AlbumDto> = emptyList(),
    val song: List<ChildDto> = emptyList(),
)

@Serializable
data class OpenSubsonicExtensionDto(val name: String, val versions: List<Int> = emptyList())

/** Envelope details useful to record about a server. */
data class ServerInfo(
    val type: String?,
    val serverVersion: String?,
    val apiVersion: String?,
    val openSubsonic: Boolean,
)
