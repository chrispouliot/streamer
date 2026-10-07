package dev.streamer.app.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        SongEntity::class,
        AlbumEntity::class,
        AlbumSongEntity::class,
        NewestAlbumEntity::class,
        ArtistEntity::class,
        PlaylistEntity::class,
        PlaylistEntryEntity::class,
        SyncStateEntity::class,
        PlayHistoryEntity::class,
        RecentCollectionEntity::class,
        DownloadRefEntity::class,
        DownloadedCollectionEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2), // v2: song.starredAt
        AutoMigration(from = 2, to = 3), // v3: play_history
        AutoMigration(from = 3, to = 4), // v4: recent_collection
        AutoMigration(from = 4, to = 5), // v5: album.created
        AutoMigration(from = 5, to = 6), // v6: download_ref, downloaded_collection
        AutoMigration(from = 6, to = 7), // v7: downloaded_collection.stopped
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun library(): LibraryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "library.db").build()
    }
}
