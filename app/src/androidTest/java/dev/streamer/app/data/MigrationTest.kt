package dev.streamer.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.streamer.app.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun v1ToLatestKeepsSongsAddsStarredAtAndHistory() {
        helper.createDatabase("migration-test", 1).apply {
            execSQL("INSERT INTO account (id, baseUrl, username, allowInsecureHttp, serverType, serverVersion, openSubsonic, active) VALUES ('a', 'https://x/', 'u', 0, NULL, NULL, 0, 1)")
            execSQL(
                "INSERT INTO song (accountId, id, title, artist, artistId, album, albumId, durationSec, track, disc, year, coverArt, explicit, starred, suffix, contentType, bitRate) " +
                    "VALUES ('a', 's1', 'Kept', NULL, NULL, NULL, NULL, 100, NULL, NULL, NULL, NULL, 0, 1, NULL, NULL, NULL)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate("migration-test", 3, true)
        db.query("SELECT title, starred, starredAt FROM song WHERE id = 's1'").use { c ->
            c.moveToFirst()
            assertEquals("Kept", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertEquals(true, c.isNull(2))
        }
        db.query("SELECT COUNT(*) FROM play_history").use { c ->
            c.moveToFirst()
            assertEquals(0, c.getInt(0))
        }
    }
}
