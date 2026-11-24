package jp.audioscope.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import jp.audioscope.player.model.RepeatModeState
import jp.audioscope.player.model.TrackItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ControllerPlaybackState(
    val isConnected: Boolean = false,
    val isPlaying: Boolean = false,
    val currentMediaId: String? = null,
    val currentTitle: String = "",
    val currentArtist: String = "",
    val currentAlbum: String = "",
    val currentArtworkUri: Uri? = null,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatModeState = RepeatModeState.OFF,
    val queueMediaIds: List<String> = emptyList(),
    val currentQueueIndex: Int = -1
)

class PlayerControllerManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var positionPollJob: Job? = null

    private val _state = MutableStateFlow(ControllerPlaybackState())
    val state: StateFlow<ControllerPlaybackState> = _state.asStateFlow()

    var onTrackTransitioned: ((mediaId: String) -> Unit)? = null
    var onPeriodicPositionPersist: ((mediaId: String, positionMs: Long) -> Unit)? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            syncStateFromController(player)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncStateFromController(mediaController ?: return)
            mediaItem?.mediaId?.takeIf { it.isNotBlank() }?.let { id ->
                onTrackTransitioned?.invoke(id)
            }
        }
    }

    fun connect() {
        if (controllerFuture != null) return
        val sessionToken = SessionToken(
            context,
            ComponentName(context, AudioScopePlaybackService::class.java)
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                val ctrl = runCatching { future.get() }.getOrNull() ?: return@addListener
                mediaController = ctrl
                ctrl.addListener(playerListener)
                syncStateFromController(ctrl)
                startPositionPolling()
            },
            MoreExecutors.directExecutor()
        )
    }

    fun disconnect() {
        positionPollJob?.cancel()
        positionPollJob = null
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        mediaController = null
    }

    fun setQueueAndPlay(tracks: List<TrackItem>, startIndex: Int, startPositionMs: Long = 0L, autoPlay: Boolean = true) {
        val ctrl = mediaController ?: return
        if (tracks.isEmpty()) return
        val safeIdx = startIndex.coerceIn(0, tracks.lastIndex)
        val items = tracks.map { it.toMediaItem() }
        AudioPlaybackBus.reportPlaybackError(null)
        ctrl.setMediaItems(items, safeIdx, startPositionMs.coerceAtLeast(0L))
        ctrl.prepare()
        ctrl.playWhenReady = autoPlay
        syncStateFromController(ctrl)
        onTrackTransitioned?.invoke(tracks[safeIdx].contentUri.toString())
    }

    fun togglePlayPause() {
        val ctrl = mediaController ?: return
        if (ctrl.isPlaying) {
            ctrl.pause()
        } else {
            if (ctrl.playbackState == Player.STATE_IDLE) ctrl.prepare()
            if (ctrl.playbackState == Player.STATE_ENDED) ctrl.seekToDefaultPosition()
            ctrl.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val ctrl = mediaController ?: return
        ctrl.seekTo(positionMs.coerceAtLeast(0L))
        syncStateFromController(ctrl)
    }

    fun skipNext() {
        val ctrl = mediaController ?: return
        if (ctrl.hasNextMediaItem()) {
            ctrl.seekToNextMediaItem()
        } else if (ctrl.mediaItemCount > 0) {
            ctrl.seekTo(0, 0L)
        }
    }

    fun skipPrevious() {
        val ctrl = mediaController ?: return
        if (ctrl.currentPosition > 3500L) {
            ctrl.seekTo(0L)
        } else if (ctrl.hasPreviousMediaItem()) {
            ctrl.seekToPreviousMediaItem()
        } else {
            ctrl.seekTo(0L)
        }
    }

    fun setShuffle(enabled: Boolean) {
        val ctrl = mediaController ?: return
        ctrl.shuffleModeEnabled = enabled
        syncStateFromController(ctrl)
    }

    fun setRepeatMode(mode: RepeatModeState) {
        val ctrl = mediaController ?: return
        ctrl.repeatMode = when (mode) {
            RepeatModeState.OFF -> Player.REPEAT_MODE_OFF
            RepeatModeState.ALL -> Player.REPEAT_MODE_ALL
            RepeatModeState.ONE -> Player.REPEAT_MODE_ONE
        }
        syncStateFromController(ctrl)
    }

    fun jumpToQueueIndex(index: Int) {
        val ctrl = mediaController ?: return
        if (index in 0 until ctrl.mediaItemCount) {
            ctrl.seekTo(index, 0L)
            ctrl.play()
        }
    }

    fun removeFromQueue(index: Int) {
        val ctrl = mediaController ?: return
        if (index in 0 until ctrl.mediaItemCount) {
            ctrl.removeMediaItem(index)
            syncStateFromController(ctrl)
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val ctrl = mediaController ?: return
        if (fromIndex in 0 until ctrl.mediaItemCount && toIndex in 0 until ctrl.mediaItemCount) {
            ctrl.moveMediaItem(fromIndex, toIndex)
            syncStateFromController(ctrl)
        }
    }

    fun addTrackToQueueEnd(track: TrackItem) {
        val ctrl = mediaController ?: return
        ctrl.addMediaItem(track.toMediaItem())
        if (ctrl.playbackState == Player.STATE_IDLE) ctrl.prepare()
        syncStateFromController(ctrl)
    }

    private fun startPositionPolling() {
        positionPollJob?.cancel()
        var tickCount = 0
        positionPollJob = scope.launch {
            while (isActive) {
                delay(250L)
                val ctrl = mediaController ?: continue
                if (ctrl.isPlaying) {
                    val pos = ctrl.currentPosition.coerceAtLeast(0L)
                    val dur = ctrl.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: _state.value.durationMs
                    _state.value = _state.value.copy(
                        currentPositionMs = pos,
                        durationMs = dur
                    )
                    tickCount++
                    if (tickCount % 20 == 0) { // Save playback position every 5s
                        val id = ctrl.currentMediaItem?.mediaId
                        if (!id.isNullOrBlank()) {
                            onPeriodicPositionPersist?.invoke(id, pos)
                        }
                    }
                }
            }
        }
    }

    private fun syncStateFromController(player: Player) {
        val current = player.currentMediaItem
        val meta = player.mediaMetadata
        val queueIds = (0 until player.mediaItemCount).map { idx ->
            player.getMediaItemAt(idx).mediaId
        }
        val repeat = when (player.repeatMode) {
            Player.REPEAT_MODE_ALL -> RepeatModeState.ALL
            Player.REPEAT_MODE_ONE -> RepeatModeState.ONE
            else -> RepeatModeState.OFF
        }
        _state.value = ControllerPlaybackState(
            isConnected = true,
            isPlaying = player.isPlaying,
            currentMediaId = current?.mediaId,
            currentTitle = meta.title?.toString() ?: "",
            currentArtist = meta.artist?.toString() ?: "",
            currentAlbum = meta.albumTitle?.toString() ?: "",
            currentArtworkUri = meta.artworkUri,
            currentPositionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L,
            shuffleEnabled = player.shuffleModeEnabled,
            repeatMode = repeat,
            queueMediaIds = queueIds,
            currentQueueIndex = player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: -1
        )
    }

    private fun TrackItem.toMediaItem(): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(artworkUri)
            .build()
        return MediaItem.Builder()
            .setMediaId(contentUri.toString())
            .setUri(contentUri)
            .setMimeType(mimeType.takeIf { it != "audio/*" })
            .setMediaMetadata(meta)
            .build()
    }
}
