package jp.audioscope.player.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import jp.audioscope.player.data.db.FavoriteTrackEntity
import jp.audioscope.player.data.db.PlaybackHistoryEntity
import jp.audioscope.player.data.db.PlaylistEntity
import jp.audioscope.player.data.db.PlaylistTrackEntity
import jp.audioscope.player.data.db.PlaylistWithTracks
import jp.audioscope.player.di.AppContainer
import jp.audioscope.player.model.AlbumGroup
import jp.audioscope.player.model.ArtistGroup
import jp.audioscope.player.model.AudioComparisonReport
import jp.audioscope.player.model.AudioOutputSpecs
import jp.audioscope.player.model.AudioSourceSpecs
import jp.audioscope.player.model.AudioVisualFrame
import jp.audioscope.player.model.ByteUnitFormatter
import jp.audioscope.player.model.FolderGroup
import jp.audioscope.player.model.LibraryFormatStats
import jp.audioscope.player.model.LibraryTab
import jp.audioscope.player.model.NetworkTrafficSnapshot
import jp.audioscope.player.model.RepeatModeState
import jp.audioscope.player.model.TrackItem
import jp.audioscope.player.model.TrackSortOrder
import jp.audioscope.player.model.VisualizerMode
import jp.audioscope.player.playback.AudioPlaybackBus
import jp.audioscope.player.playback.ControllerPlaybackState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MainDestination(val labelJp: String) {
    LIBRARY("ライブラリ"),
    NOW_PLAYING("再生画面"),
    QUALITY_DETAIL("音質詳細"),
    LAB_COMPARE("統計・比較")
}

data class LibraryFilterState(
    val selectedTab: LibraryTab = LibraryTab.TRACKS,
    val searchQuery: String = "",
    val hiResOnly: Boolean = false,
    val losslessOnly: Boolean = false,
    val sortOrder: TrackSortOrder = TrackSortOrder.TITLE_ASC
)

data class AudioScopeUiState(
    val destination: MainDestination = MainDestination.LIBRARY,
    val hasAudioReadPermission: Boolean = false,
    val hasBluetoothConnectPermission: Boolean = false,
    val isScanningLibrary: Boolean = false,
    val allTracks: List<TrackItem> = emptyList(),
    val filteredTracks: List<TrackItem> = emptyList(),
    val albumGroups: List<AlbumGroup> = emptyList(),
    val artistGroups: List<ArtistGroup> = emptyList(),
    val folderGroups: List<FolderGroup> = emptyList(),
    val formatStats: LibraryFormatStats = LibraryFormatStats(0, 0L, 0, 0, 0, emptyList()),
    val filter: LibraryFilterState = LibraryFilterState(),
    val playlists: List<PlaylistWithTracks> = emptyList(),
    val favorites: List<FavoriteTrackEntity> = emptyList(),
    val favoriteUriSet: Set<String> = emptySet(),
    val history: List<PlaybackHistoryEntity> = emptyList(),
    val currentTrack: TrackItem? = null,
    val queueTracks: List<TrackItem> = emptyList(),
    val playback: ControllerPlaybackState = ControllerPlaybackState(),
    val sourceSpecs: AudioSourceSpecs? = null,
    val isInspectingSource: Boolean = false,
    val visualizerMode: VisualizerMode = VisualizerMode.SPECTRUM,
    val showCompactQualityBadges: Boolean = true,
    val preferUsbBitPerfect: Boolean = false,
    val compareLeftTrack: TrackItem? = null,
    val compareRightTrack: TrackItem? = null,
    val comparisonReport: AudioComparisonReport? = null,
    val isComparing: Boolean = false,
    val snackbarMessage: String? = null
)

class AudioScopeViewModel(private val container: AppContainer) : ViewModel() {

    private val trackMapByUri = LinkedHashMap<String, TrackItem>()
    private var inspectJob: Job? = null
    private var restoredInitialQueue = false

    private val _uiState = MutableStateFlow(AudioScopeUiState())
    val uiState: StateFlow<AudioScopeUiState> = _uiState.asStateFlow()

    val outputSpecs: StateFlow<AudioOutputSpecs> = container.audioOutputInspector.outputSpecs
    val trafficSnapshot: StateFlow<NetworkTrafficSnapshot> = container.networkTrafficMonitor.snapshot
    val visualFrame: StateFlow<AudioVisualFrame> = AudioPlaybackBus.visualFrame

    init {
        container.playerControllerManager.onTrackTransitioned = { mediaId ->
            handleTrackTransition(mediaId)
        }
        container.playerControllerManager.onPeriodicPositionPersist = { _, posMs ->
            viewModelScope.launch {
                container.preferencesRepository.updatePositionOnly(posMs)
            }
        }
        container.playerControllerManager.connect()
        container.audioOutputInspector.startMonitoring()
        container.networkTrafficMonitor.startSampling()

        // Observe playback controller state & playback errors
        viewModelScope.launch {
            combine(
                container.playerControllerManager.state,
                AudioPlaybackBus.playbackErrorMessage
            ) { ctrlState, errMsg ->
                ctrlState to errMsg
            }.collect { (ctrlState, errMsg) ->
                val resolvedQueue = ctrlState.queueMediaIds.mapNotNull { id -> trackMapByUri[id] }
                val activeTrack = ctrlState.currentMediaId?.let { trackMapByUri[it] } ?: _uiState.value.currentTrack
                _uiState.update { prev ->
                    prev.copy(
                        playback = ctrlState,
                        queueTracks = resolvedQueue,
                        currentTrack = activeTrack,
                        snackbarMessage = errMsg ?: prev.snackbarMessage
                    )
                }
                if (errMsg != null) {
                    AudioPlaybackBus.reportPlaybackError(null)
                }
                // Restore last played track once controller connects and library is ready
                if (ctrlState.isConnected && !restoredInitialQueue && ctrlState.queueMediaIds.isEmpty()) {
                    tryRestoreLastPlayedSession()
                }
            }
        }

        // Re-inspect source specs when live Media3 decoder Format arrives
        viewModelScope.launch {
            AudioPlaybackBus.decoderInputFormat.collect { exoFormat ->
                val cur = _uiState.value.currentTrack ?: return@collect
                if (exoFormat != null) {
                    val updated = container.audioStreamInspector.inspectTrack(cur, exoFormat)
                    _uiState.update { it.copy(sourceSpecs = updated) }
                    container.audioOutputInspector.updatePlaybackContext(
                        updated,
                        AudioPlaybackBus.audioTrackConfig.value
                    )
                }
            }
        }

        // Forward AudioTrack initialization & output route changes to Output & Traffic monitors
        viewModelScope.launch {
            combine(
                AudioPlaybackBus.audioTrackConfig,
                container.audioOutputInspector.outputSpecs
            ) { trackCfg, outSpecs ->
                trackCfg to outSpecs
            }.collect { (trackCfg, outSpecs) ->
                container.audioOutputInspector.updatePlaybackContext(_uiState.value.sourceSpecs, trackCfg)
                container.networkTrafficMonitor.updateContext(
                    mediaUri = _uiState.value.currentTrack?.contentUri,
                    routeCategory = outSpecs.routeCategory,
                    audioTrackConfig = trackCfg
                )
            }
        }

        // Observe Room Playlists, Favorites, History & DataStore Preferences
        viewModelScope.launch {
            combine(
                container.dao.observePlaylistsWithTracks(),
                container.dao.observeFavorites(),
                container.dao.observeRecentHistory(),
                container.preferencesRepository.preferencesFlow
            ) { playlists, favs, hist, prefs ->
                Quadruple(playlists, favs, hist, prefs)
            }.collect { (playlists, favs, hist, prefs) ->
                AudioPlaybackBus.isVisualizerCaptureEnabled.value = (prefs.visualizerMode != VisualizerMode.OFF)
                _uiState.update { prev ->
                    prev.copy(
                        playlists = playlists,
                        favorites = favs,
                        favoriteUriSet = favs.map { it.trackUri }.toSet(),
                        history = hist,
                        visualizerMode = prefs.visualizerMode,
                        showCompactQualityBadges = prefs.showCompactQualityBadges,
                        preferUsbBitPerfect = prefs.preferUsbBitPerfect
                    )
                }
            }
        }
    }

    fun onAppForegroundChanged(inForeground: Boolean) {
        if (inForeground) {
            container.networkTrafficMonitor.startSampling()
            container.audioOutputInspector.refresh("アプリ前面復帰")
            AudioPlaybackBus.isVisualizerCaptureEnabled.value =
                (_uiState.value.visualizerMode != VisualizerMode.OFF)
        } else {
            // バックグラウンド移行時はUIポーリング・FFT演算を休止してバッテリー消費を抑える
            container.networkTrafficMonitor.stopSampling()
            AudioPlaybackBus.isVisualizerCaptureEnabled.value = false
        }
    }

    fun updatePermissions(hasAudioPermission: Boolean, hasBluetoothPermission: Boolean) {
        val prevAudio = _uiState.value.hasAudioReadPermission
        _uiState.update {
            it.copy(
                hasAudioReadPermission = hasAudioPermission,
                hasBluetoothConnectPermission = hasBluetoothPermission
            )
        }
        container.audioOutputInspector.onBluetoothPermissionUpdated()
        if (hasAudioPermission && (!prevAudio || _uiState.value.allTracks.isEmpty())) {
            scanMediaLibrary()
        }
    }

    fun scanMediaLibrary() {
        if (_uiState.value.isScanningLibrary) return
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningLibrary = true) }
            val scanned = container.mediaLibraryRepository.scanDeviceAudioLibrary()
            scanned.forEach { trackMapByUri[it.contentUri.toString()] = it }
            rebuildLibraryViews(trackMapByUri.values.toList())
            _uiState.update { it.copy(isScanningLibrary = false) }
            if (!restoredInitialQueue && _uiState.value.playback.isConnected) {
                tryRestoreLastPlayedSession()
            }
        }
    }

    fun importSafSingleFile(uri: Uri, playImmediately: Boolean = true) {
        viewModelScope.launch {
            val item = container.mediaLibraryRepository.resolveSafDocumentUri(uri)
            if (item == null) {
                postSnackbar("選択された音声ファイルを読み込めませんでした。")
                return@launch
            }
            trackMapByUri[item.contentUri.toString()] = item
            rebuildLibraryViews(trackMapByUri.values.toList())
            if (playImmediately) {
                playTrackFromList(listOf(item), 0)
                navigateTo(MainDestination.NOW_PLAYING)
            }
        }
    }

    fun importSafFolder(treeUri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanningLibrary = true) }
            val items = container.mediaLibraryRepository.resolveSafTreeUri(treeUri)
            items.forEach { trackMapByUri[it.contentUri.toString()] = it }
            rebuildLibraryViews(trackMapByUri.values.toList())
            _uiState.update { it.copy(isScanningLibrary = false) }
            postSnackbar("フォルダーから ${items.size} 曲を読み込みました。")
        }
    }

    fun playNetworkStreamUrl(url: String, title: String?) {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://")) {
            postSnackbar("セキュリティ保護のため https:// で始まる音声URLを指定してください。")
            return
        }
        val streamTrack = container.mediaLibraryRepository.createNetworkStreamTrack(trimmed, title)
        trackMapByUri[streamTrack.contentUri.toString()] = streamTrack
        rebuildLibraryViews(trackMapByUri.values.toList())
        playTrackFromList(listOf(streamTrack), 0)
        navigateTo(MainDestination.NOW_PLAYING)
    }

    fun navigateTo(dest: MainDestination) {
        _uiState.update { it.copy(destination = dest) }
    }

    fun selectLibraryTab(tab: LibraryTab) {
        val nextFilter = _uiState.value.filter.copy(selectedTab = tab)
        applyFilter(nextFilter, _uiState.value.allTracks)
    }

    fun updateSearchQuery(query: String) {
        val nextFilter = _uiState.value.filter.copy(searchQuery = query)
        applyFilter(nextFilter, _uiState.value.allTracks)
    }

    fun toggleHiResFilter() {
        val f = _uiState.value.filter
        applyFilter(f.copy(hiResOnly = !f.hiResOnly), _uiState.value.allTracks)
    }

    fun toggleLosslessFilter() {
        val f = _uiState.value.filter
        applyFilter(f.copy(losslessOnly = !f.losslessOnly), _uiState.value.allTracks)
    }

    fun updateSortOrder(order: TrackSortOrder) {
        val f = _uiState.value.filter
        applyFilter(f.copy(sortOrder = order), _uiState.value.allTracks)
    }

    fun playTrackFromList(list: List<TrackItem>, index: Int) {
        if (list.isEmpty()) return
        list.forEach { trackMapByUri[it.contentUri.toString()] = it }
        container.playerControllerManager.setQueueAndPlay(list, index, 0L, autoPlay = true)
    }

    fun togglePlayPause() = container.playerControllerManager.togglePlayPause()
    fun seekTo(posMs: Long) = container.playerControllerManager.seekTo(posMs)
    fun skipNext() = container.playerControllerManager.skipNext()
    fun skipPrevious() = container.playerControllerManager.skipPrevious()

    fun toggleShuffle() {
        val next = !_uiState.value.playback.shuffleEnabled
        container.playerControllerManager.setShuffle(next)
        viewModelScope.launch {
            container.preferencesRepository.setShuffleAndRepeat(next, _uiState.value.playback.repeatMode)
        }
    }

    fun cycleRepeatMode() {
        val next = when (_uiState.value.playback.repeatMode) {
            RepeatModeState.OFF -> RepeatModeState.ALL
            RepeatModeState.ALL -> RepeatModeState.ONE
            RepeatModeState.ONE -> RepeatModeState.OFF
        }
        container.playerControllerManager.setRepeatMode(next)
        viewModelScope.launch {
            container.preferencesRepository.setShuffleAndRepeat(_uiState.value.playback.shuffleEnabled, next)
        }
    }

    fun jumpToQueueIndex(idx: Int) = container.playerControllerManager.jumpToQueueIndex(idx)
    fun removeFromQueue(idx: Int) = container.playerControllerManager.removeFromQueue(idx)
    fun moveQueueItem(from: Int, to: Int) = container.playerControllerManager.moveQueueItem(from, to)
    fun addToQueueEnd(track: TrackItem) {
        trackMapByUri[track.contentUri.toString()] = track
        container.playerControllerManager.addTrackToQueueEnd(track)
        postSnackbar("「${track.title}」を再生キュー末尾に追加しました。")
    }

    fun toggleFavorite(track: TrackItem) {
        viewModelScope.launch {
            val uriStr = track.contentUri.toString()
            if (container.dao.isFavorite(uriStr)) {
                container.dao.removeFavorite(uriStr)
                postSnackbar("お気に入りから解除しました。")
            } else {
                container.dao.addFavorite(
                    FavoriteTrackEntity(
                        trackUri = uriStr,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                        addedAtEpochMs = System.currentTimeMillis()
                    )
                )
                postSnackbar("お気に入りに登録しました。")
            }
        }
    }

    fun createPlaylist(name: String, initialTrack: TrackItem? = null) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val pid = container.dao.insertPlaylist(
                PlaylistEntity(name = clean, createdAtEpochMs = System.currentTimeMillis())
            )
            if (initialTrack != null) {
                addTrackToPlaylist(pid, initialTrack)
            } else {
                postSnackbar("プレイリスト「$clean」を作成しました。")
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            container.dao.deletePlaylist(playlistId)
            postSnackbar("プレイリストを削除しました。")
        }
    }

    fun addTrackToPlaylist(playlistId: Long, track: TrackItem) {
        viewModelScope.launch {
            val pos = container.dao.nextSortPosition(playlistId)
            container.dao.insertPlaylistTrack(
                PlaylistTrackEntity(
                    playlistId = playlistId,
                    trackUri = track.contentUri.toString(),
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    durationMs = track.durationMs,
                    sortPosition = pos,
                    addedAtEpochMs = System.currentTimeMillis()
                )
            )
            postSnackbar("「${track.title}」をプレイリストに追加しました。")
        }
    }

    fun removeTrackFromPlaylist(playlistId: Long, trackUri: String) {
        viewModelScope.launch {
            container.dao.removeTrackFromPlaylist(playlistId, trackUri)
        }
    }

    fun playPlaylist(playlist: PlaylistWithTracks, startIndex: Int = 0) {
        val resolved = playlist.tracks.sortedBy { it.sortPosition }.map { entry ->
            trackMapByUri[entry.trackUri] ?: TrackItem(
                id = entry.trackUri.hashCode().toLong(),
                contentUri = Uri.parse(entry.trackUri),
                title = entry.title,
                artist = entry.artist,
                album = entry.album,
                albumId = -1L,
                artworkUri = null,
                durationMs = entry.durationMs,
                sizeBytes = 0L,
                mimeType = "audio/*",
                displayName = entry.title,
                relativeFolder = "Playlist",
                displayPathOrUri = entry.trackUri,
                dateAddedSec = entry.addedAtEpochMs / 1000L
            )
        }
        playTrackFromList(resolved, startIndex)
        navigateTo(MainDestination.NOW_PLAYING)
    }

    fun clearHistory() {
        viewModelScope.launch {
            container.dao.clearHistory()
            postSnackbar("再生履歴を消去しました。")
        }
    }

    fun setVisualizerMode(mode: VisualizerMode) {
        viewModelScope.launch {
            container.preferencesRepository.setVisualizerMode(mode)
        }
    }

    fun setShowCompactQualityBadges(show: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setShowCompactBadges(show)
        }
    }

    fun toggleUsbBitPerfect(enable: Boolean) {
        viewModelScope.launch {
            container.preferencesRepository.setPreferUsbBitPerfect(enable)
            val msg = container.audioOutputInspector.applyUsbBitPerfectPreference(
                enable = enable,
                sourceSampleRateHz = _uiState.value.sourceSpecs?.sampleRateHz
            )
            postSnackbar(msg)
        }
    }

    fun resetNetworkSessionCounters() {
        container.networkTrafficMonitor.resetSessionCounters()
        postSnackbar("セッション通信量カウンターをリセットしました。")
    }

    fun selectTrackForComparison(track: TrackItem, isLeftSlot: Boolean) {
        _uiState.update { prev ->
            if (isLeftSlot) prev.copy(compareLeftTrack = track)
            else prev.copy(compareRightTrack = track)
        }
        runComparisonIfReady()
    }

    private fun runComparisonIfReady() {
        val left = _uiState.value.compareLeftTrack ?: return
        val right = _uiState.value.compareRightTrack ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isComparing = true) }
            val leftSpecs = container.audioStreamInspector.inspectTrack(left)
            val rightSpecs = container.audioStreamInspector.inspectTrack(right)
            val report = container.audioStreamInspector.compareTwoSpecs(
                leftTitle = left.title,
                left = leftSpecs,
                rightTitle = right.title,
                right = rightSpecs
            )
            _uiState.update { it.copy(comparisonReport = report, isComparing = false) }
        }
    }

    fun buildCurrentAudioShareText(): String? {
        val track = _uiState.value.currentTrack ?: return null
        val src = _uiState.value.sourceSpecs ?: return null
        val out = outputSpecs.value
        val net = trafficSnapshot.value

        val outSummary = buildString {
            appendLine("- 出力カテゴリ: ${out.routeCategory.labelJp}")
            appendLine("- 出力デバイス: ${out.activeDeviceProductName} [${out.androidDeviceTypeConstantName}]")
            appendLine("- 信号経路診断: ${out.resamplingDiagnostic}")
            append("- ビットパーフェクト状態: ${if (out.isBitPerfectGuaranteed) "有効" else "非有効"} (${out.bitPerfectStatusExplanation})")
        }
        val netSummary = buildString {
            appendLine("- 再生データ元: ${net.playbackOrigin.labelJp}")
            appendLine("- セッション受信/送信: ${ByteUnitFormatter.formatBytes(net.sessionRxBytes)} / ${ByteUnitFormatter.formatBytes(net.sessionTxBytes)}")
            append("- 現在受信速度: ${ByteUnitFormatter.formatSpeed(net.currentRxBytesPerSec)}")
        }
        return container.audioStreamInspector.formatReportForShare(
            trackTitle = track.title,
            trackArtist = track.artist,
            source = src,
            outputSummary = outSummary,
            networkSummary = netSummary
        )
    }

    fun consumeSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun postSnackbar(msg: String) {
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    private fun handleTrackTransition(mediaId: String) {
        val track = trackMapByUri[mediaId] ?: return
        _uiState.update { it.copy(currentTrack = track, isInspectingSource = true) }

        inspectJob?.cancel()
        inspectJob = viewModelScope.launch {
            val specs = container.audioStreamInspector.inspectTrack(
                track = track,
                liveExoFormat = AudioPlaybackBus.decoderInputFormat.value
            )
            _uiState.update { it.copy(sourceSpecs = specs, isInspectingSource = false) }

            container.audioOutputInspector.updatePlaybackContext(
                sourceSpecs = specs,
                audioTrackConfig = AudioPlaybackBus.audioTrackConfig.value
            )
            container.networkTrafficMonitor.updateContext(
                mediaUri = track.contentUri,
                routeCategory = outputSpecs.value.routeCategory,
                audioTrackConfig = AudioPlaybackBus.audioTrackConfig.value
            )

            // Save last played & history
            val queueIds = container.playerControllerManager.state.value.queueMediaIds
            container.preferencesRepository.saveLastPlayed(
                uri = mediaId,
                title = track.title,
                artist = track.artist,
                album = track.album,
                positionMs = container.playerControllerManager.state.value.currentPositionMs,
                queueUris = queueIds.ifEmpty { listOf(mediaId) }
            )

            container.dao.insertHistory(
                PlaybackHistoryEntity(
                    trackUri = mediaId,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    codecSummary = "${specs.containerFormat} / ${specs.formattedSampleRateKhz} / ${specs.formattedBitrate}",
                    durationMs = specs.durationMs ?: track.durationMs,
                    playedAtEpochMs = System.currentTimeMillis()
                )
            )
            container.dao.trimHistory()
        }
    }

    private fun tryRestoreLastPlayedSession() {
        viewModelScope.launch {
            val prefs = container.preferencesRepository.getSnapshot()
            container.playerControllerManager.setShuffle(prefs.shuffleEnabled)
            container.playerControllerManager.setRepeatMode(prefs.repeatMode)

            val lastUri = prefs.lastTrackUri ?: return@launch
            val queueTracks = prefs.queueUris.mapNotNull { trackMapByUri[it] }
            val fallbackTrack = trackMapByUri[lastUri]
            val targetQueue = when {
                queueTracks.isNotEmpty() -> queueTracks
                fallbackTrack != null -> listOf(fallbackTrack)
                else -> return@launch
            }
            restoredInitialQueue = true
            val startIdx = targetQueue.indexOfFirst { it.contentUri.toString() == lastUri }.coerceAtLeast(0)
            // autoPlay = false で復元し、ユーザーが再生ボタンを押した時点で再生開始（Android 17 準拠）
            container.playerControllerManager.setQueueAndPlay(
                tracks = targetQueue,
                startIndex = startIdx,
                startPositionMs = prefs.lastPositionMs,
                autoPlay = false
            )
        }
    }

    private fun rebuildLibraryViews(tracks: List<TrackItem>) {
        val repo = container.mediaLibraryRepository
        val stats = repo.computeFormatStats(tracks)
        val albums = repo.groupAlbums(tracks)
        val artists = repo.groupArtists(tracks)
        val folders = repo.groupFolders(tracks)

        // Default comparator candidates: automatically pre-select one FLAC/WAV and one MP3 if available
        val currentLeft = _uiState.value.compareLeftTrack ?: tracks.firstOrNull { it.isLikelyLossless }
        val currentRight = _uiState.value.compareRightTrack ?: tracks.firstOrNull { !it.isLikelyLossless }

        _uiState.update { prev ->
            prev.copy(
                allTracks = tracks,
                albumGroups = albums,
                artistGroups = artists,
                folderGroups = folders,
                formatStats = stats,
                compareLeftTrack = currentLeft,
                compareRightTrack = currentRight
            )
        }
        applyFilter(_uiState.value.filter, tracks)
        if (_uiState.value.comparisonReport == null && currentLeft != null && currentRight != null) {
            runComparisonIfReady()
        }
    }

    private fun applyFilter(filter: LibraryFilterState, sourceTracks: List<TrackItem>) {
        val q = filter.searchQuery.trim().lowercase()
        var list = sourceTracks.filter { track ->
            val matchesQuery = q.isEmpty() ||
                track.title.lowercase().contains(q) ||
                track.artist.lowercase().contains(q) ||
                track.album.lowercase().contains(q) ||
                track.fileExtension.lowercase().contains(q) ||
                track.relativeFolder.lowercase().contains(q)
            val matchesLossless = !filter.losslessOnly || track.isLikelyLossless
            val matchesHiRes = !filter.hiResOnly || track.isLikelyHiRes
            matchesQuery && matchesLossless && matchesHiRes
        }

        list = when (filter.sortOrder) {
            TrackSortOrder.TITLE_ASC -> list.sortedBy { it.title.lowercase() }
            TrackSortOrder.ARTIST_ASC -> list.sortedWith(compareBy({ it.artist.lowercase() }, { it.title.lowercase() }))
            TrackSortOrder.ALBUM_ASC -> list.sortedWith(compareBy({ it.album.lowercase() }, { it.trackNumber }, { it.title.lowercase() }))
            TrackSortOrder.DATE_ADDED_DESC -> list.sortedByDescending { it.dateAddedSec }
            TrackSortOrder.SIZE_DESC -> list.sortedByDescending { it.sizeBytes }
        }

        _uiState.update { prev ->
            prev.copy(filter = filter, filteredTracks = list)
        }
    }

    override fun onCleared() {
        container.audioOutputInspector.stopMonitoring()
        container.networkTrafficMonitor.stopSampling()
        container.playerControllerManager.disconnect()
        super.onCleared()
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    companion object {
        fun provideFactory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return AudioScopeViewModel(container) as T
                }
            }
    }
}
