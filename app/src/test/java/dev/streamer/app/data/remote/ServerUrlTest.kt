package dev.streamer.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    private fun base(input: String) = (ServerUrl.normalize(input) as ServerUrlResult.Valid).base.toString()

    @Test
    fun defaultsToHttpsAndAddsTrailingSlash() {
        assertEquals("https://music.example.com/", base("music.example.com"))
        assertEquals("https://music.example.com/", base("  https://music.example.com  "))
    }

    @Test
    fun keepsPortsAndHttp() {
        assertEquals("http://100.101.102.103:4533/", base("http://100.101.102.103:4533"))
    }

    @Test
    fun keepsReverseProxySubPath() {
        assertEquals("https://example.com/navidrome/", base("https://example.com/navidrome"))
        assertEquals("https://example.com/a/b/", base("https://example.com/a/b/"))
    }

    @Test
    fun dropsPastedApiAndWebUiSuffixes() {
        assertEquals("https://example.com/navidrome/", base("https://example.com/navidrome/rest"))
        assertEquals("https://example.com/navidrome/", base("https://example.com/navidrome/rest/ping.view?u=x&p=y"))
        assertEquals("https://example.com/", base("https://example.com/app/#/album/all"))
    }

    @Test
    fun endpointPreservesSubPathWithoutDuplicateRest() {
        val b = (ServerUrl.normalize("https://example.com/navidrome/rest") as ServerUrlResult.Valid).base
        assertEquals("https://example.com/navidrome/rest/ping", ServerUrl.endpoint(b, "ping").build().toString())
        val root = (ServerUrl.normalize("https://example.com") as ServerUrlResult.Valid).base
        assertEquals("https://example.com/rest/ping", ServerUrl.endpoint(root, "ping").build().toString())
    }

    @Test
    fun rejectsEmptyGarbageAndEmbeddedCredentials() {
        assertTrue(ServerUrl.normalize("  ") is ServerUrlResult.Invalid)
        assertTrue(ServerUrl.normalize("ftp://example.com") is ServerUrlResult.Invalid)
        assertTrue(ServerUrl.normalize("https://user:pw@example.com") is ServerUrlResult.Invalid)
    }

    @Test
    fun tokenMatchesSubsonicSpecExample() {
        // From the Subsonic API documentation: password "sesame", salt "c19b2d".
        assertEquals("26719a1196d2a940705a59634eb18eab", TokenAuth.token("sesame", "c19b2d"))
    }

    @Test
    fun saltsAreFresh() {
        assertTrue(TokenAuth.newSalt() != TokenAuth.newSalt())
    }
}
