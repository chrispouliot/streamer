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
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)], // v2: song.starredAt
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun library(): LibraryDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "library.db").build()
    }
}
