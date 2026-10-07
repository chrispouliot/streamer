package dev.streamer.app.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.streamer.app.data.account.CredentialStore
import dev.streamer.app.data.account.KeystoreCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "cred-test").apply { deleteRecursively() }
    private val store = CredentialStore(dir, KeystoreCipher(alias = "streamer_test_key"))

    @Test
    fun roundTripsThroughKeystoreAndNeverStoresPlaintext() {
        store.save("acc-1", "pä55wörd")
        assertEquals("pä55wörd", store.load("acc-1"))
        val bytes = dir.listFiles()!!.single().readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("55w"))
        store.delete("acc-1")
        assertNull(store.load("acc-1"))
    }
}
