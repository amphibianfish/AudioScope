package jp.audioscope.player.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val playlistId: Long = 0L,
    val name: String,
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackUri"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["playlistId"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["playlistId"]), Index(value = ["trackUri"])]
)
data class PlaylistTrackEntity(
    val playlistId: Long,
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sortPosition: Int,
    val addedAtEpochMs: Long
)

data class PlaylistWithTracks(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "playlistId",
        entityColumn = "playlistId"
    )
    val tracks: List<PlaylistTrackEntity>
)

@Entity(tableName = "favorite_tracks")
data class FavoriteTrackEntity(
    @PrimaryKey
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val addedAtEpochMs: Long
)

@Entity(
    tableName = "playback_history",
    indices = [Index(value = ["playedAtEpochMs"])]
)
data class PlaybackHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val historyId: Long = 0L,
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val codecSummary: String,
    val durationMs: Long,
    val playedAtEpochMs: Long
)
