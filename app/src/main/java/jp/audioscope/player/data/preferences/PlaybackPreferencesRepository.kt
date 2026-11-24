package jp.audioscope.player.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import jp.audioscope.player.model.RepeatModeState
import jp.audioscope.player.model.VisualizerMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.audioScopeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "audioscope_playback_prefs"
)

data class PersistedPlaybackState(
    val lastTrackUri: String? = null,
    val lastTrackTitle: String = "",
    val lastTrackArtist: String = "",
    val lastTrackAlbum: String = "",
    val lastPositionMs: Long = 0L,
    val queueUrisJoined: String = "",
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatModeState = RepeatModeState.OFF,
    val visualizerMode: VisualizerMode = VisualizerMode.SPECTRUM,
    val showCompactQualityBadges: Boolean = true,
    val preferUsbBitPerfect: Boolean = false
) {
    val queueUris: List<String>
        get() = if (queueUrisJoined.isBlank()) emptyList()
        else queueUrisJoined.split("\n").filter { it.isNotBlank() }
}

class PlaybackPreferencesRepository(private val context: Context) {

    private object Keys {
        val LAST_TRACK_URI = stringPreferencesKey("last_track_uri")
        val LAST_TRACK_TITLE = stringPreferencesKey("last_track_title")
        val LAST_TRACK_ARTIST = stringPreferencesKey("last_track_artist")
        val LAST_TRACK_ALBUM = stringPreferencesKey("last_track_album")
        val LAST_POSITION_MS = longPreferencesKey("last_position_ms")
        val QUEUE_URIS = stringPreferencesKey("queue_uris")
        val SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        val REPEAT_MODE = stringPreferencesKey("repeat_mode")
        val VISUALIZER_MODE = stringPreferencesKey("visualizer_mode")
        val SHOW_COMPACT_BADGES = booleanPreferencesKey("show_compact_badges")
        val PREFER_USB_BIT_PERFECT = booleanPreferencesKey("prefer_usb_bit_perfect")
    }

    val preferencesFlow: Flow<PersistedPlaybackState> = context.audioScopeDataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { prefs ->
            PersistedPlaybackState(
                lastTrackUri = prefs[Keys.LAST_TRACK_URI],
                lastTrackTitle = prefs[Keys.LAST_TRACK_TITLE] ?: "",
                lastTrackArtist = prefs[Keys.LAST_TRACK_ARTIST] ?: "",
                lastTrackAlbum = prefs[Keys.LAST_TRACK_ALBUM] ?: "",
                lastPositionMs = prefs[Keys.LAST_POSITION_MS] ?: 0L,
                queueUrisJoined = prefs[Keys.QUEUE_URIS] ?: "",
                shuffleEnabled = prefs[Keys.SHUFFLE_ENABLED] ?: false,
                repeatMode = runCatching {
                    RepeatModeState.valueOf(prefs[Keys.REPEAT_MODE] ?: RepeatModeState.OFF.name)
                }.getOrDefault(RepeatModeState.OFF),
                visualizerMode = runCatching {
                    VisualizerMode.valueOf(prefs[Keys.VISUALIZER_MODE] ?: VisualizerMode.SPECTRUM.name)
                }.getOrDefault(VisualizerMode.SPECTRUM),
                showCompactQualityBadges = prefs[Keys.SHOW_COMPACT_BADGES] ?: true,
                preferUsbBitPerfect = prefs[Keys.PREFER_USB_BIT_PERFECT] ?: false
            )
        }

    suspend fun getSnapshot(): PersistedPlaybackState = preferencesFlow.first()

    suspend fun saveLastPlayed(
        uri: String,
        title: String,
        artist: String,
        album: String,
        positionMs: Long,
        queueUris: List<String>
    ) {
        context.audioScopeDataStore.edit { prefs ->
            prefs[Keys.LAST_TRACK_URI] = uri
            prefs[Keys.LAST_TRACK_TITLE] = title
            prefs[Keys.LAST_TRACK_ARTIST] = artist
            prefs[Keys.LAST_TRACK_ALBUM] = album
            prefs[Keys.LAST_POSITION_MS] = positionMs.coerceAtLeast(0L)
            prefs[Keys.QUEUE_URIS] = queueUris.take(500).joinToString("\n")
        }
    }

    suspend fun updatePositionOnly(positionMs: Long) {
        context.audioScopeDataStore.edit { prefs ->
            prefs[Keys.LAST_POSITION_MS] = positionMs.coerceAtLeast(0L)
        }
    }

    suspend fun setShuffleAndRepeat(shuffle: Boolean, repeatMode: RepeatModeState) {
        context.audioScopeDataStore.edit { prefs ->
            prefs[Keys.SHUFFLE_ENABLED] = shuffle
            prefs[Keys.REPEAT_MODE] = repeatMode.name
        }
    }

    suspend fun setVisualizerMode(mode: VisualizerMode) {
        context.audioScopeDataStore.edit { prefs ->
            prefs[Keys.VISUALIZER_MODE] = mode.name
        }
    }

    suspend fun setShowCompactBadges(show: Boolean) {
        context.audioScopeDataStore.edit { prefs ->
            prefs[Keys.SHOW_COMPACT_BADGES] = show
        }
    }

    suspend fun setPreferUsbBitPerfect(enabled: Boolean) {
        context.audioScopeDataStore.edit { prefs ->
            prefs[Keys.PREFER_USB_BIT_PERFECT] = enabled
        }
    }
}
