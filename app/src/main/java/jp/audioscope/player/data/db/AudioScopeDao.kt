package jp.audioscope.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioScopeDao {

    // Playlists
    @Transaction
    @Query("SELECT * FROM playlists ORDER BY createdAtEpochMs DESC")
    fun observePlaylistsWithTracks(): Flow<List<PlaylistWithTracks>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT COALESCE(MAX(sortPosition), -1) + 1 FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun nextSortPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylistTrack(entry: PlaylistTrackEntity)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackUri = :trackUri")
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackUri: String)

    // Favorites
    @Query("SELECT * FROM favorite_tracks ORDER BY addedAtEpochMs DESC")
    fun observeFavorites(): Flow<List<FavoriteTrackEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_tracks WHERE trackUri = :trackUri)")
    suspend fun isFavorite(trackUri: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(entity: FavoriteTrackEntity)

    @Query("DELETE FROM favorite_tracks WHERE trackUri = :trackUri")
    suspend fun removeFavorite(trackUri: String)

    // Playback History
    @Query("SELECT * FROM playback_history ORDER BY playedAtEpochMs DESC LIMIT 200")
    fun observeRecentHistory(): Flow<List<PlaybackHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(entry: PlaybackHistoryEntity)

    @Query("DELETE FROM playback_history")
    suspend fun clearHistory()

    @Query(
        "DELETE FROM playback_history WHERE historyId NOT IN " +
            "(SELECT historyId FROM playback_history ORDER BY playedAtEpochMs DESC LIMIT 500)"
    )
    suspend fun trimHistory()
}
