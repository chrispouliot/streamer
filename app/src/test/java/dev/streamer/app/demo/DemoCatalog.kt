package dev.streamer.app.demo

import dev.streamer.app.model.AlbumDetail
import dev.streamer.app.model.AlbumSummary
import dev.streamer.app.model.ArtistDetail
import dev.streamer.app.model.ArtistSummary
import dev.streamer.app.model.Artwork
import dev.streamer.app.model.PlaylistDetail
import dev.streamer.app.model.PlaylistEntry
import dev.streamer.app.model.PlaylistSummary
import dev.streamer.app.model.Song
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Fictional artists, albums and playlists modelled on the design mockups.
 * Test use only; not part of any APK.
 */
object DemoCatalog {
    private val artistNames = listOf(
        "Halden Vale", "Kaito Ren", "Velvet Arcade", "Mira Okafor",
        "Juno Ashby", "Odile Marsh", "Seven Pines", "The Lantern Coast",
    )

    private fun artistId(name: String) = "ar-" + name.lowercase().replace(Regex("[^a-z0-9]+"), "-")

    private class AlbumSpec(val name: String, val artist: String, val year: Int, val tracks: List<Pair<String, String>>)

    private val albumSpecs = listOf(
        AlbumSpec(
            "Low Tide Radio", "Halden Vale", 2026,
            listOf(
                "Harbor Lights" to "3:41", "Northbound" to "3:58", "Static on the Line" to "4:12",
                "Undertow" to "3:27", "Lighthouse Keeper" to "5:03", "Weathervane" to "3:15",
                "Low Tide Radio" to "4:40", "Driftwood" to "2:58", "Two Hours From the Coast" to "4:21",
                "Saltwater Tape" to "3:36", "Last Ferry" to "6:02",
            ),
        ),
        AlbumSpec(
            "Paper Satellites", "Mira Okafor", 2025,
            listOf("Orbit Song" to "3:12", "Paper Satellites" to "4:05", "Glass Door" to "3:48", "Small Hours" to "4:30", "Kites" to "2:51"),
        ),
        AlbumSpec(
            "Night Bus Hymns", "Juno Ashby", 2024,
            listOf("Route 9" to "3:33", "Sodium Light" to "4:18", "Last Stop" to "5:10", "Window Seat" to "3:02"),
        ),
        AlbumSpec(
            "Ferro", "Kaito Ren", 2026,
            listOf("Ferro" to "3:20", "Rust Bloom" to "4:44", "Magnet" to "3:01", "Iron Lung Blues" to "4:09"),
        ),
        AlbumSpec(
            "Salt & Signal", "Seven Pines", 2023,
            listOf("Signal Fire" to "3:55", "Brine" to "4:02", "Coastline" to "3:40"),
        ),
        AlbumSpec(
            "Softer Machines", "Velvet Arcade", 2026,
            listOf("Soft Machine" to "3:28", "Arcade Light" to "4:12", "Neon Rain" to "3:49", "Replay" to "2:59"),
        ),
        AlbumSpec(
            "Evergreen Static", "Odile Marsh", 2025,
            listOf("Evergreen" to "4:23", "Static Bloom" to "3:37", "Pine Needle" to "3:11"),
        ),
        AlbumSpec(
            "Copper Season", "The Lantern Coast", 2026,
            listOf("Copper" to "3:58", "Season's End" to "5:21", "Lantern" to "4:01", "Harvest Moon Radio" to "3:46"),
        ),
    )

    private fun parse(mmss: String): Duration {
        val (m, s) = mmss.split(":").map { it.toInt() }
        return (m * 60 + s).seconds
    }

    val albums: List<AlbumDetail> = albumSpecs.map { spec ->
        val id = "al-" + spec.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val art = Artwork(coverArtId = null, seed = id)
        val songs = spec.tracks.mapIndexed { i, (title, length) ->
            Song(
                id = "$id-${i + 1}",
                title = title,
                artist = if (title == "Lighthouse Keeper") "${spec.artist}, Odile Marsh" else spec.artist,
                artistId = artistId(spec.artist),
                album = spec.name,
                albumId = id,
                duration = parse(length),
                trackNumber = i + 1,
                artwork = art,
                explicit = title == "Undertow" || title == "Two Hours From the Coast",
            )
        }
        AlbumDetail(
            summary = AlbumSummary(id, spec.name, spec.artist, artistId(spec.artist), spec.year, songs.size, art),
            duration = songs.fold(Duration.ZERO) { acc, s -> acc + (s.duration ?: Duration.ZERO) },
            songs = songs,
        )
    }

    val allSongs: List<Song> = albums.flatMap { it.songs }

    val artists: List<ArtistDetail> = artistNames.map { name ->
        val own = albums.filter { it.summary.artist == name }.map { it.summary }
        ArtistDetail(
            summary = ArtistSummary(artistId(name), name, own.size, Artwork(null, artistId(name))),
            albums = own,
        )
    }

    private fun playlist(id: String, name: String, comment: String?, songs: List<Song>): PlaylistDetail {
        val entries = songs.mapIndexed { i, s -> PlaylistEntry(i, s) }
        return PlaylistDetail(
            summary = PlaylistSummary(
                id = id,
                name = name,
                owner = "demo",
                comment = comment,
                songCount = entries.size,
                duration = songs.fold(Duration.ZERO) { acc, s -> acc + (s.duration ?: Duration.ZERO) },
                artwork = Artwork(null, id),
            ),
            entries = entries,
        )
    }

    private fun songs(vararg titles: String) = titles.map { t -> allSongs.first { it.title == t } }

    val playlists: List<PlaylistDetail> = listOf(
        playlist(
            "pl-coastal", "Coastal", "Sea air and long drives",
            songs("Harbor Lights", "Signal Fire", "Coastline", "Brine", "Northbound", "Driftwood", "Last Ferry"),
        ),
        // Deliberately contains "Northbound" twice to exercise duplicate entries.
        playlist(
            "pl-late-night", "Late Night", null,
            songs("Northbound", "Sodium Light", "Neon Rain", "Route 9", "Rust Bloom", "Northbound", "Small Hours"),
        ),
        playlist(
            "pl-weekly-unknowns", "Weekly Unknowns", "Picked by hand every Monday",
            songs("Evergreen", "Copper", "Kites", "Arcade Light", "Magnet"),
        ),
        playlist(
            "pl-slow-mornings", "Slow Mornings", null,
            songs("Pine Needle", "Window Seat", "Orbit Song", "Weathervane", "Lantern", "Season's End"),
        ),
        playlist("pl-empty", "Empty Playlist", "No songs yet", emptyList()),
    )

    val recentlyPlayed: List<Song> = songs("Northbound", "Glass Door", "Neon Rain", "Signal Fire", "Copper")

    val initiallyStarred: Set<String> = songs("Northbound", "Glass Door").map { it.id }.toSet()
}
