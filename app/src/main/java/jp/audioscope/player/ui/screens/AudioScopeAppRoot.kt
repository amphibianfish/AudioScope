package jp.audioscope.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.audioscope.player.model.VisualizerMode
import jp.audioscope.player.ui.AudioScopeViewModel
import jp.audioscope.player.ui.MainDestination
import jp.audioscope.player.ui.components.MiniPlayerBar
import jp.audioscope.player.ui.theme.ObsidianSurface

@Composable
fun AudioScopeAppRoot(
    viewModel: AudioScopeViewModel,
    onRequestAudioPermission: () -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onOpenSafFile: () -> Unit,
    onOpenSafFolder: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onShareText: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val outputSpecs by viewModel.outputSpecs.collectAsStateWithLifecycle()
    val trafficSnapshot by viewModel.trafficSnapshot.collectAsStateWithLifecycle()
    val visualFrame by viewModel.visualFrame.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            Column {
                val activeTrack = uiState.currentTrack
                if (activeTrack != null && uiState.destination != MainDestination.NOW_PLAYING) {
                    MiniPlayerBar(
                        track = activeTrack,
                        playback = uiState.playback,
                        sourceSpecs = uiState.sourceSpecs,
                        onOpenNowPlaying = { viewModel.navigateTo(MainDestination.NOW_PLAYING) },
                        onOpenQualityDetail = { viewModel.navigateTo(MainDestination.QUALITY_DETAIL) },
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onSkipNext = viewModel::skipNext
                    )
                }

                NavigationBar(containerColor = ObsidianSurface) {
                    NavigationBarItem(
                        selected = uiState.destination == MainDestination.LIBRARY,
                        onClick = { viewModel.navigateTo(MainDestination.LIBRARY) },
                        icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                        label = { Text(MainDestination.LIBRARY.labelJp) }
                    )
                    NavigationBarItem(
                        selected = uiState.destination == MainDestination.NOW_PLAYING,
                        onClick = { viewModel.navigateTo(MainDestination.NOW_PLAYING) },
                        icon = { Icon(Icons.Rounded.PlayCircle, contentDescription = null) },
                        label = { Text(MainDestination.NOW_PLAYING.labelJp) }
                    )
                    NavigationBarItem(
                        selected = uiState.destination == MainDestination.QUALITY_DETAIL,
                        onClick = { viewModel.navigateTo(MainDestination.QUALITY_DETAIL) },
                        icon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
                        label = { Text(MainDestination.QUALITY_DETAIL.labelJp) }
                    )
                    NavigationBarItem(
                        selected = uiState.destination == MainDestination.LAB_COMPARE,
                        onClick = { viewModel.navigateTo(MainDestination.LAB_COMPARE) },
                        icon = { Icon(Icons.Rounded.Analytics, contentDescription = null) },
                        label = { Text(MainDestination.LAB_COMPARE.labelJp) }
                    )
                }
            }
        }
    ) { innerPadding ->
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)

        when (uiState.destination) {
            MainDestination.LIBRARY -> LibraryScreen(
                uiState = uiState,
                onRequestAudioPermission = onRequestAudioPermission,
                onOpenSafFile = onOpenSafFile,
                onOpenSafFolder = onOpenSafFolder,
                onPlayStreamUrl = viewModel::playNetworkStreamUrl,
                onRescanLibrary = viewModel::scanMediaLibrary,
                onSelectTab = viewModel::selectLibraryTab,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onToggleHiRes = viewModel::toggleHiResFilter,
                onToggleLossless = viewModel::toggleLosslessFilter,
                onSelectSortOrder = viewModel::updateSortOrder,
                onPlayTrackFromList = { list, idx ->
                    viewModel.playTrackFromList(list, idx)
                    viewModel.navigateTo(MainDestination.NOW_PLAYING)
                },
                onAddToQueue = viewModel::addToQueueEnd,
                onToggleFavorite = viewModel::toggleFavorite,
                onCreatePlaylist = viewModel::createPlaylist,
                onDeletePlaylist = viewModel::deletePlaylist,
                onAddTrackToPlaylist = viewModel::addTrackToPlaylist,
                onPlayPlaylist = viewModel::playPlaylist,
                onClearHistory = viewModel::clearHistory,
                onSelectForCompare = { track, isLeft ->
                    viewModel.selectTrackForComparison(track, isLeft)
                    viewModel.navigateTo(MainDestination.LAB_COMPARE)
                },
                modifier = contentModifier
            )

            MainDestination.NOW_PLAYING -> NowPlayingScreen(
                uiState = uiState,
                visualFrame = visualFrame,
                onTogglePlayPause = viewModel::togglePlayPause,
                onSeekTo = viewModel::seekTo,
                onSkipPrevious = viewModel::skipPrevious,
                onSkipNext = viewModel::skipNext,
                onToggleShuffle = viewModel::toggleShuffle,
                onCycleRepeat = viewModel::cycleRepeatMode,
                onToggleFavorite = { uiState.currentTrack?.let(viewModel::toggleFavorite) },
                onToggleCompactBadges = viewModel::setShowCompactQualityBadges,
                onCycleVisualizerMode = {
                    val modes = VisualizerMode.entries
                    val next = modes[(uiState.visualizerMode.ordinal + 1) % modes.size]
                    viewModel.setVisualizerMode(next)
                },
                onOpenQualityDetail = { viewModel.navigateTo(MainDestination.QUALITY_DETAIL) },
                onShareAudioSpecs = {
                    viewModel.buildCurrentAudioShareText()?.let(onShareText)
                },
                onJumpToQueueIndex = viewModel::jumpToQueueIndex,
                onRemoveQueueItem = viewModel::removeFromQueue,
                onMoveQueueItem = viewModel::moveQueueItem,
                onGoToLibrary = { viewModel.navigateTo(MainDestination.LIBRARY) },
                modifier = contentModifier
            )

            MainDestination.QUALITY_DETAIL -> AudioQualityDetailScreen(
                uiState = uiState,
                outputSpecs = outputSpecs,
                trafficSnapshot = trafficSnapshot,
                visualFrame = visualFrame,
                onSelectVisualizerMode = viewModel::setVisualizerMode,
                onRequestBluetoothPermission = onRequestBluetoothPermission,
                onOpenDeveloperOptions = onOpenDeveloperOptions,
                onOpenBluetoothSettings = onOpenBluetoothSettings,
                onToggleUsbBitPerfect = viewModel::toggleUsbBitPerfect,
                onResetTrafficSession = viewModel::resetNetworkSessionCounters,
                onShareSpecsText = {
                    viewModel.buildCurrentAudioShareText()?.let(onShareText)
                },
                modifier = contentModifier
            )

            MainDestination.LAB_COMPARE -> AnalyticsCompareScreen(
                uiState = uiState,
                onSelectCompareTrack = viewModel::selectTrackForComparison,
                modifier = contentModifier
            )
        }
    }
}
