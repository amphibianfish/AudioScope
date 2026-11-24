package jp.audioscope.player.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import jp.audioscope.player.model.AudioSourceSpecs
import jp.audioscope.player.model.LosslessClassification
import jp.audioscope.player.ui.theme.ChampagneGold
import jp.audioscope.player.ui.theme.ChampagneGoldDim
import jp.audioscope.player.ui.theme.LosslessCyan
import jp.audioscope.player.ui.theme.LosslessCyanDim
import jp.audioscope.player.ui.theme.ObsidianSurfaceElevated
import jp.audioscope.player.ui.theme.ObsidianSurfaceHigh
import jp.audioscope.player.ui.theme.OutlineSubtle
import jp.audioscope.player.ui.theme.TextSecondary

@Composable
fun AlbumArtworkCard(
    artworkUri: Uri?,
    title: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    isHiRes: Boolean = false,
    isLossless: Boolean = false
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(ObsidianSurfaceHigh, ObsidianSurfaceElevated)
                )
            )
            .border(1.dp, OutlineSubtle, shape),
        contentAlignment = Alignment.Center
    ) {
        if (artworkUri != null) {
            SubcomposeAsyncImage(
                model = artworkUri,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = { DefaultAcousticDiscPlaceholder() },
                loading = { DefaultAcousticDiscPlaceholder() }
            )
        } else {
            DefaultAcousticDiscPlaceholder()
        }

        if (isHiRes || isLossless) {
            Surface(
                color = if (isHiRes) ChampagneGold else LosslessCyan,
                contentColor = Color(0xFF14161D),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
            ) {
                Text(
                    text = if (isHiRes) "HI-RES" else "LOSSLESS",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun DefaultAcousticDiscPlaceholder() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Icon(
            imageVector = Icons.Rounded.Album,
            contentDescription = null,
            tint = ChampagneGold.copy(alpha = 0.72f),
            modifier = Modifier.size(64.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioQualityCompactStrip(
    specs: AudioSourceSpecs?,
    modifier: Modifier = Modifier
) {
    if (specs == null) {
        Row(modifier = modifier) {
            QualityPill(text = "音質解析待機中", accent = TextSecondary, bg = ObsidianSurfaceElevated)
        }
        return
    }

    val isLossless = specs.losslessClassification == LosslessClassification.COMPRESSED_LOSSLESS ||
        specs.losslessClassification == LosslessClassification.UNCOMPRESSED_LOSSLESS

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (specs.isHiResSource) {
            QualityPill(text = "Hi-Res", accent = ChampagneGold, bg = ChampagneGoldDim)
        }
        QualityPill(
            text = specs.containerFormat,
            accent = if (isLossless) LosslessCyan else MaterialTheme.colorScheme.onSurface,
            bg = if (isLossless) LosslessCyanDim else ObsidianSurfaceHigh
        )
        QualityPill(
            text = specs.formattedSampleRateKhz,
            accent = MaterialTheme.colorScheme.onSurface,
            bg = ObsidianSurfaceElevated
        )
        specs.bitDepth?.let { bd ->
            QualityPill(
                text = "$bd bit",
                accent = if (bd >= 24) ChampagneGold else MaterialTheme.colorScheme.onSurface,
                bg = if (bd >= 24) ChampagneGoldDim else ObsidianSurfaceElevated
            )
        }
        QualityPill(
            text = "${specs.formattedBitrate} (${specs.bitrateMode.shortTag})",
            accent = MaterialTheme.colorScheme.onSurface,
            bg = ObsidianSurfaceElevated
        )
        QualityPill(
            text = specs.losslessClassification.badgeText,
            accent = if (isLossless) LosslessCyan else TextSecondary,
            bg = if (isLossless) LosslessCyanDim else ObsidianSurfaceElevated
        )
    }
}

@Composable
fun QualityPill(
    text: String,
    accent: Color,
    bg: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.border(0.8.dp, accent.copy(alpha = 0.38f), RoundedCornerShape(8.dp))
    ) {
        Text(
            text = text,
            color = accent,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
