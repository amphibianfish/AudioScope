package jp.audioscope.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.audioscope.player.model.AudioVisualFrame
import jp.audioscope.player.model.LosslessClassification
import jp.audioscope.player.model.RepeatModeState
import jp.audioscope.player.model.VisualizerMode
import jp.audioscope.player.ui.AudioScopeUiState
import jp.audioscope.player.ui.components.AlbumArtworkCard
import jp.audioscope.player.ui.components.AudioQualityCompactStrip
import jp.audioscope.player.ui.components.SpectrumWaveformVisualizer
import jp.audioscope.player.ui.theme.ChampagneGold
import jp.audioscope.player.ui.theme.LosslessCyan
import jp.audioscope.player.ui.theme.ObsidianSurfaceElevated
import jp.audioscope.player.ui.theme.ObsidianSurfaceHigh
import jp.audioscope.player.ui.theme.TextSecondary

/**
 * 再生画面（要件7の厳密な配置順を遵守）：
 * 1. アルバムアート（＋スペクトラム/波形切替表示）
 * 2. 曲名・アーティスト名
 * 3. 音質情報のコンパクト表示
 * 4. 再生シークバー
 * 5. 再生操作ボタン
 * 6. 音質詳細画面を開くボタン
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    uiState: AudioScopeUiState,
    visualFrame: AudioVisualFrame,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipPrevious: () -> Unit,
    onSkipNext: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleCompactBadges: (Boolean) -> Unit,
    onCycleVisualizerMode: () -> Unit,
    onOpenQualityDetail: () -> Unit,
    onShareAudioSpecs: () -> Unit,
    onJumpToQueueIndex: (Int) -> Unit,
    onRemoveQueueItem: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onGoToLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    val track = uiState.currentTrack
    var showQueueSheet by remember { mutableStateOf(false) }
    var isUserDraggingSeek by remember { mutableStateOf(false) }
    var dragSeekFraction by remember { mutableFloatStateOf(0f) }

    if (track == null) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "再生中の楽曲がありません",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "ライブラリから曲を選択するか、ファイルを開いて再生を開始してください。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(onClick = onGoToLibrary) {
                    Text("音楽ライブラリを開く")
                }
            }
        }
        return
    }

    val specs = uiState.sourceSpecs
    val isLossless = specs?.losslessClassification == LosslessClassification.COMPRESSED_LOSSLESS ||
        specs?.losslessClassification == LosslessClassification.UNCOMPRESSED_LOSSLESS
    val isFav = uiState.favoriteUriSet.contains(track.contentUri.toString())

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Utility Row (Visualizer Mode & Queue & Share)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onCycleVisualizerMode,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Rounded.Equalizer, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(uiState.visualizerMode.labelJp, style = MaterialTheme.typography.labelSmall)
            }
            Row {
                IconButton(onClick = { onToggleCompactBadges(!uiState.showCompactQualityBadges) }) {
                    Icon(
                        imageVector = if (uiState.showCompactQualityBadges) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                        contentDescription = "コンパクト音質表示切替",
                        tint = TextSecondary
                    )
                }
                IconButton(onClick = onShareAudioSpecs) {
                    Icon(Icons.Rounded.Share, contentDescription = "音質情報を共有", tint = TextSecondary)
                }
                IconButton(onClick = { showQueueSheet = true }) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "再生キュー", tint = ChampagneGold)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // -----------------------------------------------------------------
        // 1. アルバムアート (Galaxy S26 6.3インチ画面で大きく美しく表示)
        // -----------------------------------------------------------------
        AlbumArtworkCard(
            artworkUri = track.artworkUri,
            title = track.title,
            cornerRadius = 24.dp,
            isHiRes = specs?.isHiResSource == true,
            isLossless = isLossless,
            modifier = Modifier
                .widthIn(max = 330.dp)
                .fillMaxWidth(0.86f)
                .aspectRatio(1f)
        )

        if (uiState.visualizerMode != VisualizerMode.OFF) {
            Spacer(modifier = Modifier.height(12.dp))
            SpectrumWaveformVisualizer(
                frame = visualFrame,
                mode = uiState.visualizerMode,
                canvasHeight = 84.dp
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // -----------------------------------------------------------------
        // 2. 曲名・アーティスト名
        // -----------------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${track.artist}  •  ${track.album}",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "お気に入り",
                    tint = if (isFav) ChampagneGold else TextSecondary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // -----------------------------------------------------------------
        // 3. 音質情報のコンパクト表示
        // -----------------------------------------------------------------
        if (uiState.showCompactQualityBadges) {
            AudioQualityCompactStrip(
                specs = specs,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        // -----------------------------------------------------------------
        // 4. 再生シークバー
        // -----------------------------------------------------------------
        val totalDurationMs = uiState.playback.durationMs.takeIf { it > 0L }
            ?: specs?.durationMs
            ?: track.durationMs
        val currentFraction = if (isUserDraggingSeek) {
            dragSeekFraction
        } else if (totalDurationMs > 0L) {
            (uiState.playback.currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
        } else 0f

        Column(modifier = Modifier.fillMaxWidth()) {
            Slider(
                value = currentFraction,
                onValueChange = { frac ->
                    isUserDraggingSeek = true
                    dragSeekFraction = frac
                },
                onValueChangeFinished = {
                    val targetMs = (dragSeekFraction * totalDurationMs).toLong()
                    onSeekTo(targetMs)
                    isUserDraggingSeek = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = ChampagneGold,
                    activeTrackColor = ChampagneGold,
                    inactiveTrackColor = ObsidianSurfaceHigh
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val shownPosMs = if (isUserDraggingSeek) {
                    (dragSeekFraction * totalDurationMs).toLong()
                } else {
                    uiState.playback.currentPositionMs
                }
                Text(
                    text = formatDurationMmSs(shownPosMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Text(
                    text = formatDurationMmSs(totalDurationMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // -----------------------------------------------------------------
        // 5. 再生操作ボタン
        // -----------------------------------------------------------------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleShuffle) {
                Icon(
                    imageVector = Icons.Rounded.Shuffle,
                    contentDescription = "シャッフル",
                    tint = if (uiState.playback.shuffleEnabled) ChampagneGold else TextSecondary
                )
            }

            IconButton(
                onClick = onSkipPrevious,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "前の曲",
                    modifier = Modifier.size(36.dp)
                )
            }

            FilledIconButton(
                onClick = onTogglePlayPause,
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = ChampagneGold,
                    contentColor = Color(0xFF1A1405)
                ),
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    imageVector = if (uiState.playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (uiState.playback.isPlaying) "一時停止" else "再生",
                    modifier = Modifier.size(40.dp)
                )
            }

            IconButton(
                onClick = onSkipNext,
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "次の曲",
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(onClick = onCycleRepeat) {
                Icon(
                    imageVector = if (uiState.playback.repeatMode == RepeatModeState.ONE) {
                        Icons.Rounded.RepeatOne
                    } else {
                        Icons.Rounded.Repeat
                    },
                    contentDescription = uiState.playback.repeatMode.labelJp,
                    tint = if (uiState.playback.repeatMode != RepeatModeState.OFF) ChampagneGold else TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(22.dp))

        // -----------------------------------------------------------------
        // 6. 音質詳細画面を開くボタン
        // -----------------------------------------------------------------
        Button(
            onClick = onOpenQualityDetail,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ObsidianSurfaceHigh,
                contentColor = ChampagneGold
            ),
            contentPadding = PaddingValues(vertical = 14.dp, horizontal = 18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = null)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "音質詳細モニターを開く (SOURCE / OUTPUT / NETWORK)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Queue Management Modal Bottom Sheet
    if (showQueueSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQueueSheet = false },
            containerColor = ObsidianSurfaceElevated
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "再生キュー (${uiState.queueTracks.size} 曲)",
                    style = MaterialTheme.typography.titleLarge,
                    color = ChampagneGold
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                ) {
                    itemsIndexed(uiState.queueTracks) { idx, qTrack ->
                        val isCurrent = idx == uiState.playback.currentQueueIndex
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) ObsidianSurfaceHigh else ObsidianSurfaceElevated
                            ),
                            onClick = { onJumpToQueueIndex(idx) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${idx + 1}. ${qTrack.title}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isCurrent) ChampagneGold else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${qTrack.artist} • ${qTrack.fileExtension}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LosslessCyan
                                    )
                                }
                                if (idx > 0) {
                                    IconButton(onClick = { onMoveQueueItem(idx, idx - 1) }) {
                                        Icon(Icons.Rounded.ArrowUpward, contentDescription = "上へ移動", modifier = Modifier.size(18.dp))
                                    }
                                }
                                if (idx < uiState.queueTracks.lastIndex) {
                                    IconButton(onClick = { onMoveQueueItem(idx, idx + 1) }) {
                                        Icon(Icons.Rounded.ArrowDownward, contentDescription = "下へ移動", modifier = Modifier.size(18.dp))
                                    }
                                }
                                IconButton(onClick = { onRemoveQueueItem(idx) }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "キューから削除", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
