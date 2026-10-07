package dev.streamer.app.data.remote

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SubsonicClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder().readTimeout(500, TimeUnit.MILLISECONDS).build()
    private val client = SubsonicClient(http)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun auth(allowHttp: Boolean = true) =
        ServerAuth(server.url("/music/"), "chris", "secret", allowInsecureHttp = allowHttp)

    private fun ok(body: String = "") = MockResponse.Builder().code(200).body(
        """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.60.0","openSubsonic":true$body}}""",
    ).build()

    private suspend inline fun <reified E : ApiError> expect(crossinline block: suspend () -> Unit): E {
        try {
            block()
        } catch (e: ApiError) {
            if (e is E) return e
            fail("Expected ${E::class.simpleName} but got ${e::class.simpleName}: ${e.message}")
        }
        fail("Expected ${E::class.simpleName}")
        throw AssertionError()
    }

    @Test
    fun pingSendsTokenAuthUnderSubPathAndReadsServerInfo() = runTest {
        server.enqueue(ok())
        val info = client.ping(auth())
        assertEquals("navidrome", info.type)
        assertEquals("0.60.0", info.serverVersion)
        assertTrue(info.openSubsonic)

        val request = server.takeRequest()
        val url = request.url
        assertEquals("/music/rest/ping", url.encodedPath)
        assertEquals("chris", url.queryParameter("u"))
        assertEquals(TokenAuth.token("secret", url.queryParameter("s")!!), url.queryParameter("t"))
        assertEquals("json", url.queryParameter("f"))
        assertEquals("Streamer", url.queryParameter("c"))
        assertNull("Password must never be sent", url.queryParameter("p"))
        assertFalse(url.toString().contains("secret"))
    }

    @Test
    fun apiErrorInsideHttp200IsMapped() = runTest {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"subsonic-response":{"status":"failed","version":"1.16.1","error":{"code":40,"message":"Wrong username or password"}}}""",
            ).build(),
        )
        val e = expect<ApiError.Auth> { client.ping(auth()) }
        assertEquals(40, e.code)
    }

    @Test
    fun notFoundAndPermissionCodes() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"subsonic-response":{"status":"failed","error":{"code":70}}}""").build())
        expect<ApiError.NotFound> { client.playlist(auth(), "missing") }
        server.enqueue(MockResponse.Builder().code(200).body("""{"subsonic-response":{"status":"failed","error":{"code":50}}}""").build())
        expect<ApiError.Permission> { client.playlists(auth()) }
    }

    @Test
    fun httpErrorsAreMapped() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(404, expect<ApiError.Http> { client.ping(auth()) }.status)
        server.enqueue(MockResponse.Builder().code(503).build())
        assertTrue(expect<ApiError.Http> { client.ping(auth()) }.isTransient)
    }

    @Test
    fun malformedBodyIsMapped() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("<html>proxy login</html>").build())
        expect<ApiError.Malformed> { client.ping(auth()) }
    }

    @Test
    fun timeoutIsTransient() = runTest {
        server.enqueue(ok().newBuilder().bodyDelay(2, TimeUnit.SECONDS).build())
        assertTrue(expect<ApiError.Timeout> { client.ping(auth()) }.isTransient)
    }

    @Test
    fun httpWithoutConsentIsRefusedBeforeAnyRequest() = runTest {
        expect<ApiError.InsecureNotAllowed> { client.ping(auth(allowHttp = false)) }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun playlistKeepsOrderDuplicatesAndToleratesMissingFields() = runTest {
        server.enqueue(
            ok(
                ""","playlist":{"id":"p1","name":"Mix","entry":[
                {"id":"s1","title":"One","duration":200,"starred":"2026-01-01T00:00:00Z","futureField":{"x":1}},
                {"id":"s2"},
                {"id":"s1","title":"One"}]}""",
            ),
        )
        val p = client.playlist(auth(), "p1")
        assertEquals(listOf("s1", "s2", "s1"), p.entry.map { it.id })
        assertEquals(200, p.entry[0].duration)
        assertNull(p.entry[1].title)
        assertNull(p.owner)
    }

    @Test
    fun largePlaylistDecodesCompletelyInOrder() = runTest {
        // getPlaylist has no paging in the Subsonic API: the whole playlist arrives at once.
        val entries = (0 until 2500).joinToString(",") { i ->
            """{"id":"s${i % 2400}","title":"Song $i","artist":"Artist","duration":200,"albumId":"al${i / 12}"}"""
        }
        server.enqueue(ok(""","playlist":{"id":"liked","name":"Liked","songCount":2500,"entry":[$entries]}"""))
        val p = client.playlist(auth(), "liked")
        assertEquals(2500, p.entry.size)
        assertEquals("Song 0", p.entry.first().title)
        assertEquals("Song 2499", p.entry.last().title)
        // Songs 2400-2499 repeat earlier IDs: duplicates are kept as separate entries.
        assertEquals(p.entry[0].id, p.entry[2400].id)
    }

    @Test
    fun emptyContainersDecodeToEmptyLists() = runTest {
        server.enqueue(ok(""","playlists":{}"""))
        assertTrue(client.playlists(auth()).isEmpty())
        server.enqueue(ok()) // Field omitted entirely.
        assertTrue(client.starred(auth()).song.isEmpty())
    }

    @Test
    fun artistsAreFlattenedFromIndex() = runTest {
        server.enqueue(
            ok(""","artists":{"ignoredArticles":"The","index":[{"name":"A","artist":[{"id":"a1","name":"Abba"}]},{"name":"B","artist":[{"id":"b1","name":"Beck","albumCount":3}]}]}"""),
        )
        assertEquals(listOf("a1", "b1"), client.artists(auth()).map { it.id })
    }

    @Test
    fun retryTransientRetriesOnlyTransientErrors() = runTest {
        var calls = 0
        val result = retryTransient {
            calls++
            if (calls < 3) throw ApiError.Timeout(null)
            "done"
        }
        assertEquals("done", result)
        assertEquals(3, calls)

        calls = 0
        try {
            retryTransient { calls++; throw ApiError.Auth(40, null) }
        } catch (_: ApiError.Auth) {
        }
        assertEquals(1, calls)
    }
}
