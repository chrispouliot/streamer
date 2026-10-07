package dev.streamer.app.data.repository

import dev.streamer.app.data.local.AlbumEntity
import dev.streamer.app.data.local.ArtistEntity
import dev.streamer.app.data.local.PlaylistEntity
import dev.streamer.app.data.local.SongEntity
import dev.streamer.app.data.remote.AlbumDto
import dev.streamer.app.data.remote.ArtistDto
import dev.streamer.app.data.remote.ChildDto
import dev.streamer.app.data.remote.PlaylistDto
import dev.streamer.app.model.AlbumSummary
import dev.streamer.app.model.ArtistSummary
import dev.streamer.app.model.Artwork
import dev.streamer.app.model.PlaylistSummary
import dev.streamer.app.model.Song
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.time.Duration.Companion.seconds

// Server DTO -> cache entity. Missing values stay unknown (null) rather than invented.

fun ChildDto.toEntity(acc: String) = SongEntity(
    accountId = acc,
    id = id,
    title = title ?: "Untitled",
    artist = artist,
    artistId = artistId,
    album = album,
    albumId = albumId,
    durationSec = duration,
    track = track,
    disc = discNumber,
    year = year,
    coverArt = coverArt,
    explicit = explicitStatus == "explicit",
    starred = starred != null,
    suffix = suffix,
    contentType = contentType,
    bitRate = bitRate,
    starredAt = starred,
)

fun AlbumDto.toEntity(acc: String) = AlbumEntity(
    accountId = acc,
    id = id,
    name = name ?: title ?: "Untitled album",
    artist = artist,
    artistId = artistId,
    year = year,
    songCount = songCount,
    durationSec = duration,
    coverArt = coverArt,
    created = created,
)

fun ArtistDto.toEntity(acc: String) = ArtistEntity(acc, id, name ?: "Unknown artist", albumCount, coverArt)

fun PlaylistDto.toEntity(acc: String) = PlaylistEntity(
    accountId = acc,
    id = id,
    name = name ?: "Untitled playlist",
    comment = comment,
    owner = owner,
    isPublic = isPublic,
    songCount = songCount,
    durationSec = duration,
    coverArt = coverArt,
    changed = changed,
    removedRemotely = false,
)

/** Songs only; directory entries from folder-based responses are skipped. */
fun List<ChildDto>.songEntities(acc: String) = filterNot { it.isDir }.map { it.toEntity(acc) }

// Cache entity -> UI model.

private const val UNKNOWN_ARTIST = "Unknown artist"

fun SongEntity.toModel() = Song(
    id = id,
    title = title,
    artist = artist ?: UNKNOWN_ARTIST,
    artistId = artistId,
    album = album,
    albumId = albumId,
    duration = durationSec?.seconds,
    trackNumber = track,
    // Songs on one album share fallback colours.
    artwork = Artwork(coverArt, seed = albumId ?: id, accountId = accountId),
    explicit = explicit,
    favouritedAt = starredAt?.let(::parseServerTime),
)

/** Server timestamps are RFC 3339 / ISO-8601; unknown formats stay unknown. */
fun parseServerTime(value: String): Instant? =
    runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()

fun AlbumEntity.toModel() = AlbumSummary(
    id = id,
    name = name,
    artist = artist ?: UNKNOWN_ARTIST,
    artistId = artistId,
    year = year,
    songCount = songCount,
    artwork = Artwork(coverArt, seed = id, accountId = accountId),
    addedAt = created?.let(::parseServerTime),
)

fun ArtistEntity.toModel() = ArtistSummary(id, name, albumCount, Artwork(coverArt, seed = id, accountId = accountId))

fun PlaylistEntity.toModel() = PlaylistSummary(
    id = id,
    name = name,
    owner = owner,
    comment = comment,
    songCount = songCount,
    duration = durationSec?.seconds,
    artwork = Artwork(coverArt, seed = id, accountId = accountId),
    changedAt = changed?.let(::parseServerTime),
)
