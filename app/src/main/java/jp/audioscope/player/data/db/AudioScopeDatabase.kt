package jp.audioscope.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        FavoriteTrackEntity::class,
        PlaybackHistoryEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AudioScopeDatabase : RoomDatabase() {
    abstract fun audioScopeDao(): AudioScopeDao

    companion object {
        @Volatile
        private var instance: AudioScopeDatabase? = null

        fun getInstance(context: Context): AudioScopeDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AudioScopeDatabase::class.java,
                    "audioscope_library.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
