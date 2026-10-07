package dev.streamer.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

/** What a request needs to authenticate. Built at use time; never persisted or logged. */
data class ServerAuth(
    val baseUrl: HttpUrl,
    val username: String,
    val password: String,
    /** The user explicitly allowed unencrypted HTTP for this server. */
    val allowInsecureHttp: Boolean,
)

/**
 * Typed client for the Subsonic/OpenSubsonic API under `<base>/rest`.
 * Validates both the HTTP status and the response envelope (HTTP 200 can
 * still carry an API error) and maps failures to [ApiError].
 */
class SubsonicClient(
    private val http: OkHttpClient,
    private val clientName: String = "Streamer",
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** Authenticated URL for endpoints fetched by other components (cover art, streaming). */
    fun authenticatedUrl(auth: ServerAuth, endpoint: String, params: Map<String, String> = emptyMap()): HttpUrl {
        if (!auth.baseUrl.isHttps && !auth.allowInsecureHttp) throw ApiError.InsecureNotAllowed()
        val salt = TokenAuth.newSalt()
        return ServerUrl.endpoint(auth.baseUrl, endpoint).apply {
            addQueryParameter("u", auth.username)
            addQueryParameter("t", TokenAuth.token(auth.password, salt))
            addQueryParameter("s", salt)
            addQueryParameter("v", API_VERSION)
            addQueryParameter("c", clientName)
            addQueryParameter("f", "json")
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    }

    suspend fun ping(auth: ServerAuth): ServerInfo {
        val envelope = request(auth, "ping", emptyMap())
        fun str(key: String) = (envelope[key] as? JsonPrimitive)?.contentOrNull
        return ServerInfo(
            type = str("type"),
            serverVersion = str("serverVersion"),
            apiVersion = str("version"),
            openSubsonic = (envelope["openSubsonic"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }

    suspend fun openSubsonicExtensions(auth: ServerAuth): List<OpenSubsonicExtensionDto> =
        get(auth, "getOpenSubsonicExtensions", emptyMap(), "openSubsonicExtensions", ListSerializer(OpenSubsonicExtensionDto.serializer()))

    suspend fun playlists(auth: ServerAuth): List<PlaylistDto> =
        get(auth, "getPlaylists", emptyMap(), "playlists", PlaylistsDto.serializer()).playlist

    suspend fun playlist(auth: ServerAuth, id: String): PlaylistDto =
        get(auth, "getPlaylist", mapOf("id" to id), "playlist", PlaylistDto.serializer())

    suspend fun albumList(auth: ServerAuth, type: String, size: Int, offset: Int): List<AlbumDto> = get(
        auth,
        "getAlbumList2",
        mapOf("type" to type, "size" to size.toString(), "offset" to offset.toString()),
        "albumList2",
        AlbumListDto.serializer(),
    ).album

    suspend fun album(auth: ServerAuth, id: String): AlbumDto =
        get(auth, "getAlbum", mapOf("id" to id), "album", AlbumDto.serializer())

    suspend fun artists(auth: ServerAuth): List<ArtistDto> =
        get(auth, "getArtists", emptyMap(), "artists", ArtistsDto.serializer()).index.flatMap { it.artist }

    suspend fun artist(auth: ServerAuth, id: String): ArtistDto =
        get(auth, "getArtist", mapOf("id" to id), "artist", ArtistDto.serializer())

    suspend fun search(
        auth: ServerAuth,
        query: String,
        artistCount: Int,
        albumCount: Int,
        songCount: Int,
        songOffset: Int = 0,
    ): SearchResultDto = get(
        auth,
        "search3",
        mapOf(
            "query" to query,
            "artistCount" to artistCount.toString(),
            "albumCount" to albumCount.toString(),
            "songCount" to songCount.toString(),
            "songOffset" to songOffset.toString(),
        ),
        "searchResult3",
        SearchResultDto.serializer(),
    )

    suspend fun starred(auth: ServerAuth): SearchResultDto =
        get(auth, "getStarred2", emptyMap(), "starred2", SearchResultDto.serializer())

    suspend fun setStarred(auth: ServerAuth, songId: String, starred: Boolean) {
        request(auth, if (starred) "star" else "unstar", mapOf("id" to songId))
    }

    private suspend fun <T> get(
        auth: ServerAuth,
        endpoint: String,
        params: Map<String, String>,
        field: String,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val envelope = request(auth, endpoint, params)
        // Large responses (pages of hundreds of songs): decode off the main thread.
        return withContext(Dispatchers.Default) {
            // Servers omit empty containers; decode them from an empty object so defaults apply.
            val element: JsonElement = envelope[field] ?: JsonObject(emptyMap())
            try {
                json.decodeFromJsonElement(deserializer, element)
            } catch (e: IllegalArgumentException) {
                throw ApiError.Malformed(e)
            }
        }
    }

    /** Executes a request and returns the validated `subsonic-response` object. */
    private suspend fun request(auth: ServerAuth, endpoint: String, params: Map<String, String>): JsonObject {
        val url = authenticatedUrl(auth, endpoint, params)
        val body = withContext(Dispatchers.IO) {
            val response = try {
                http.newCall(Request.Builder().url(url).get().build()).await()
            } catch (e: IOException) {
                throw mapIoException(e)
            }
            response.use { r ->
                if (!r.isSuccessful) throw ApiError.Http(r.code)
                try {
                    r.body.string()
                } catch (e: IOException) {
                    throw mapIoException(e)
                }
            }
        }
        val envelope = withContext(Dispatchers.Default) {
            try {
                json.parseToJsonElement(body).jsonObject["subsonic-response"]?.jsonObject
            } catch (e: IllegalArgumentException) {
                null
            }
        } ?: throw ApiError.Malformed(null)
        if (envelope["status"]?.jsonPrimitive?.contentOrNull != "ok") {
            val error = (envelope["error"] as? JsonObject)
            val code = error?.get("code")?.jsonPrimitive?.intOrNull ?: 0
            val message = error?.get("message")?.jsonPrimitive?.contentOrNull
            throw mapApiError(code, message)
        }
        return envelope
    }

    companion object {
        const val API_VERSION = "1.16.1"

        fun mapApiError(code: Int, message: String?): ApiError = when (code) {
            40, 41, 42, 43, 44 -> ApiError.Auth(code, message)
            50 -> ApiError.Permission(message)
            70 -> ApiError.NotFound(message)
            20, 30 -> ApiError.Incompatible(code, message)
            else -> ApiError.Server(code, message)
        }

        fun mapIoException(e: IOException): ApiError = when (e) {
            is SocketTimeoutException -> ApiError.Timeout(e)
            is SSLException -> ApiError.Tls(e)
            is UnknownHostException, is ConnectException, is NoRouteToHostException -> ApiError.Unreachable(e)
            // Android's network security policy rejecting cleartext HTTP.
            is UnknownServiceException -> ApiError.InsecureNotAllowed()
            is InterruptedIOException -> ApiError.Timeout(e)
            else -> ApiError.Unreachable(e)
        }
    }
}

/** Retries transient failures with bounded backoff. Auth and other errors fail immediately. */
suspend fun <T> retryTransient(attempts: Int = 3, block: suspend () -> T): T {
    var delayMs = 500L
    repeat(attempts - 1) {
        try {
            return block()
        } catch (e: ApiError) {
            if (!e.isTransient) throw e
        }
        delay(delayMs.milliseconds)
        delayMs *= 3
    }
    return block()
}

/** Suspends until the call completes; cancelling the coroutine cancels the call. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        },
    )
}
