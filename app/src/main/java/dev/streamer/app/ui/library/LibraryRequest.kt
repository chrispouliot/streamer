package dev.streamer.app.ui.library

import dev.streamer.app.settings.AlbumOrder

/** A request to open Library on a particular view, e.g. from a Home "Show all". */
data class LibraryRequest(val filter: LibraryFilter, val albumOrder: AlbumOrder? = null)
