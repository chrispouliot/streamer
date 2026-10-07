package dev.streamer.app.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface ServerUrlResult {
    /** [base] always ends with "/" and keeps any reverse-proxy sub-path. */
    data class Valid(val base: HttpUrl) : ServerUrlResult {
        val isHttps: Boolean get() = base.isHttps
    }

    data class Invalid(val reason: String) : ServerUrlResult
}

object ServerUrl {
    /**
     * Normalises a user-entered server address. Keeps sub-paths (for servers
     * behind a reverse proxy), defaults to https when no scheme is given, and
     * drops a pasted `/rest` API or `/app` web-UI suffix, query and fragment.
     */
    fun normalize(input: String): ServerUrlResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return ServerUrlResult.Invalid("Enter your server address.")
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val url = withScheme.toHttpUrlOrNull()
            ?: return ServerUrlResult.Invalid("That doesn't look like a web address. Example: https://music.example.com")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return ServerUrlResult.Invalid("Remove the username or password from the address; enter them below instead.")
        }
        var segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.lastOrNull()?.lowercase()?.removeSuffix(".view") in API_PAGES) segments = segments.dropLast(1)
        if (segments.lastOrNull() == "rest") segments = segments.dropLast(1)
        if (segments.lastOrNull() == "app") segments = segments.dropLast(1)
        val builder = url.newBuilder().query(null).fragment(null).encodedPath("/")
        segments.forEach { builder.addPathSegment(it) }
        if (segments.isNotEmpty()) builder.addPathSegment("") // Trailing slash.
        return ServerUrlResult.Valid(builder.build())
    }

    /** `<base>rest/<endpoint>`, keeping the base's sub-path. */
    fun endpoint(base: HttpUrl, endpoint: String): HttpUrl.Builder =
        base.newBuilder().addPathSegment("rest").addPathSegment(endpoint)

    private val API_PAGES = setOf("ping", "index.html")
}
