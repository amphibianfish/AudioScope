package jp.audioscope.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.audioscope.player.model.AudioSourceSpecs
import jp.audioscope.player.model.TrackItem
import jp.audioscope.player.playback.ControllerPlaybackState
import jp.audioscope.player.ui.theme.ChampagneGold
import jp.audioscope.player.ui.theme.LosslessCyan
import jp.audioscope.player.ui.theme.ObsidianSurfaceHigh
import jp.audioscope.player.ui.theme.TextSecondary

@Composable
fun MiniPlayerBar(
    track: TrackItem,
    playback: ControllerPlaybackState,
    sourceSpecs: AudioSourceSpecs?,
    onOpenNowPlaying: () -> Unit,
    onOpenQualityDetail: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (playback.durationMs > 0L) {
        (playback.currentPositionMs.toFloat() / playback.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Surface(
        color = ObsidianSurfaceHigh,
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenNowPlaying)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { progress },
                color = ChampagneGold,
                trackColor = ObsidianSurfaceHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtworkCard(
                    artworkUri = track.artworkUri,
                    title = track.title,
                    cornerRadius = 10.dp,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val specLine = sourceSpecs?.let {
                        "${it.containerFormat} • ${it.formattedSampleRateKhz} • ${it.formattedBitrate}"
                    } ?: track.artist
                    Text(
                        text = specLine,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sourceSpecs?.isHiResSource == true) ChampagneGold else LosslessCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onOpenQualityDetail) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = "音質詳細",
                        tint = TextSecondary
                    )
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (playback.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playback.isPlaying) "一時停止" else "再生",
                        tint = ChampagneGold
                    )
                }
                IconButton(onClick = onSkipNext) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "次の曲",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
