package dev.streamer.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.streamer.app.data.account.AccountRepository
import dev.streamer.app.data.account.ConnectResult
import dev.streamer.app.data.account.CredentialStore
import dev.streamer.app.data.account.SecretCipher
import dev.streamer.app.data.account.SessionState
import dev.streamer.app.data.local.AppDatabase
import dev.streamer.app.data.local.SyncStateEntity
import dev.streamer.app.data.remote.SubsonicClient
import dev.streamer.app.data.repository.NavidromeLibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Repository + Room + a mock Navidrome server, on device. */
@RunWith(AndroidJUnit4::class)
class LibraryRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var accounts: AccountRepository
    private lateinit var repo: NavidromeLibraryRepository

    /** Per-endpoint JSON bodies (inside subsonic-response); null means "fail with HTTP 500". */
    @Volatile private var responses: Map<String, String?> = emptyMap()
    @Volatile private var authFails = false

    private object PlainCipher : SecretCipher {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(blob: ByteArray) = blob
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val endpoint = request.url.pathSegments.last()
                if (authFails) {
                    return ok("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong username or password"}}}""")
                }
                if (endpoint == "ping") return ok(envelope(""))
                if (endpoint !in responses) return ok(envelope(""))
                val body = responses[endpoint] ?: return MockResponse.Builder().code(500).build()
                return ok(envelope(body))
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val client = SubsonicClient(OkHttpClient())
        val dir = File(context.cacheDir, "repo-test-creds").apply { deleteRecursively() }
        accounts = AccountRepository(db.accounts(), CredentialStore(dir, PlainCipher), client, scope)
        repo = NavidromeLibraryRepository(accounts, db.library(), client, UserMessages(), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        server.close()
    }

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
    private fun envelope(fields: String) =
        """{"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome","serverVersion":"0.60.0"$fields}}"""

    private suspend fun <T> Flow<T>.firstMatching(predicate: (T) -> Boolean): T = withTimeout(10_000) { first(predicate) }

    private suspend fun connect() {
        withTimeout(10_000) { accounts.state.first { it != SessionState.Loading } }
        val result = accounts.connect(server.url("/navidrome/").toString(), "chris", "secret", allowInsecureHttp = true)
        assertTrue(result.toString(), result is ConnectResult.Success)
    }

    @Test
    fun playlistsLoadFromServerAndSurviveAFailedRefresh() = runBlocking {
        responses = mapOf("getPlaylists" to ""","playlists":{"playlist":[{"id":"p1","name":"Road trip","songCount":2}]}""")
        connect()
        assertEquals(listOf("Road trip"), repo.playlists.firstMatching { it.isNotEmpty() }.map { it.name })

        // Server now fails: once the refresh (with its retries) has failed, the cached snapshot remains.
        responses = mapOf("getPlaylists" to null)
        db.library().upsertSyncState(SyncStateEntity(currentAccountId(), "playlists", 0))
        val before = server.requestCount
        repo.playlists.firstMatching { true }
        withTimeout(10_000) { while (server.requestCount < before + 3) delay(50) }
        delay(200)
        assertEquals(listOf("Road trip"), db.library().observePlaylists(currentAccountId()).first().map { it.name })
    }

    @Test
    fun playlistDetailPreservesDuplicateEntries() = runBlocking {
        responses = mapOf(
            "getPlaylist" to ""","playlist":{"id":"p1","name":"Dupes","entry":[{"id":"s1","title":"A"},{"id":"s2","title":"B"},{"id":"s1","title":"A"}]}""",
        )
        connect()
        val detail = repo.playlist("p1").firstMatching { it != null }!!
        assertEquals(listOf("A", "B", "A"), detail.entries.map { it.song.title })
        assertEquals(listOf(0, 1, 2), detail.entries.map { it.position })
    }

    @Test
    fun twoThousandSongPlaylistLoadsFully() = runBlocking {
        val entries = (0 until 2000).joinToString(",") { i -> """{"id":"s$i","title":"Song $i","duration":180}""" }
        responses = mapOf("getPlaylist" to ""","playlist":{"id":"liked","name":"Liked","songCount":2000,"entry":[$entries]}""")
        connect()
        val detail = repo.playlist("liked").firstMatching { it != null && it.entries.isNotEmpty() }!!
        assertEquals(2000, detail.entries.size)
        assertEquals((0 until 2000).toList(), detail.entries.map { it.position })
        assertEquals("Song 1999", detail.entries.last().song.title)
    }

    @Test
    fun albumDetailIncludesArtistArtworkFetchedOnDemand() = runBlocking {
        responses = mapOf(
            "getAlbum" to ""","album":{"id":"al1","name":"Album","artist":"Band","artistId":"ar1","song":[{"id":"s1","title":"One"}]}""",
            "getArtist" to ""","artist":{"id":"ar1","name":"Band","coverArt":"ar-ar1_0","albumCount":1,"album":[]}""",
        )
        connect()
        val detail = repo.album("al1").firstMatching { it?.artistArtwork?.coverArtId != null }!!
        assertEquals("ar-ar1_0", detail.artistArtwork!!.coverArtId)
    }

    @Test
    fun forcedRefreshReportsStatusAndKeepsDataOnFailure() = runBlocking {
        responses = mapOf("getPlaylists" to ""","playlists":{"playlist":[{"id":"p1","name":"Kept"}]}""")
        connect()
        assertEquals(null, repo.refresh(SyncTarget.Playlists))
        val ok = repo.syncStatus(SyncTarget.Playlists).firstMatching { it.lastUpdated != null }
        assertEquals(null, ok.error)

        responses = mapOf("getPlaylists" to null) // HTTP 500 on every retry.
        val message = repo.refresh(SyncTarget.Playlists)
        assertTrue(message != null)
        val failed = repo.syncStatus(SyncTarget.Playlists).firstMatching { it.error != null }
        assertEquals(ok.lastUpdated, failed.lastUpdated) // Last good refresh time kept.
        assertEquals(listOf("Kept"), repo.playlists.firstMatching { true }.map { it.name })
    }

    @Test
    fun rewrittenPlaylistIsRefetchedWhenTheListShowsItChanged() = runBlocking {
        responses = mapOf(
            "getPlaylists" to ""","playlists":{"playlist":[{"id":"daily","name":"Daily","songCount":1,"changed":"2026-10-05T06:00:00Z"}]}""",
            "getPlaylist" to ""","playlist":{"id":"daily","name":"Daily","changed":"2026-10-05T06:00:00Z","entry":[{"id":"old","title":"Yesterday"}]}""",
        )
        connect()
        repo.refresh(SyncTarget.Playlists)
        assertEquals(listOf("Yesterday"), repo.playlist("daily").firstMatching { it != null && it.entries.isNotEmpty() }!!.entries.map { it.song.title })

        // A server-side script replaces the songs.
        responses = mapOf(
            "getPlaylists" to ""","playlists":{"playlist":[{"id":"daily","name":"Daily","songCount":2,"changed":"2026-10-06T06:00:00Z"}]}""",
            "getPlaylist" to ""","playlist":{"id":"daily","name":"Daily","changed":"2026-10-06T06:00:00Z","entry":[{"id":"n1","title":"Today 1"},{"id":"n2","title":"Today 2"}]}""",
        )
        repo.refresh(SyncTarget.Playlists) // Only the list; the detail is refetched in the background.
        val updated = db.library().observePlaylistEntries(currentAccountId(), "daily").firstMatching { it.size == 2 }
        assertEquals(listOf("Today 1", "Today 2"), updated.map { it.song.title })
    }

    @Test
    fun unavailablePlaylistEmitsNullOnceTheAttemptFails() = runBlocking {
        responses = mapOf("getPlaylist" to null) // HTTP 500 for every attempt.
        connect()
        assertEquals(null, repo.playlist("nope").firstMatching { true })
    }

    @Test
    fun rejectedCredentialsStopRequestsButKeepCache() = runBlocking {
        responses = mapOf("getPlaylists" to ""","playlists":{"playlist":[{"id":"p1","name":"Kept"}]}""")
        connect()
        repo.playlists.firstMatching { it.isNotEmpty() }

        authFails = true
        db.library().upsertSyncState(SyncStateEntity(currentAccountId(), "albums", 0))
        repo.albums.firstMatching { true } // Triggers a refresh that is rejected.
        val state = accounts.state.firstMatching { (it as? SessionState.Active)?.reauthReason != null }
        assertTrue((state as SessionState.Active).reauthReason!!.contains("password"))
        val before = server.requestCount
        assertEquals(listOf("Kept"), repo.playlists.firstMatching { true }.map { it.name })
        assertEquals("No requests while awaiting reauthentication", before, server.requestCount)
    }

    @Test
    fun reconnectingSameAccountKeepsCachedData() = runBlocking {
        responses = mapOf("getPlaylists" to ""","playlists":{"playlist":[{"id":"p1","name":"Kept"}]}""")
        connect()
        val id = currentAccountId()
        repo.playlists.firstMatching { it.isNotEmpty() }
        connect()
        assertEquals(id, currentAccountId())
        assertEquals(1, db.library().observePlaylists(id).first().size)
    }

    private fun currentAccountId() = (accounts.state.value as SessionState.Active).account.id
}
