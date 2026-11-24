package jp.audioscope.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import jp.audioscope.player.model.AudioVisualFrame
import jp.audioscope.player.model.VisualizerMode
import jp.audioscope.player.ui.theme.ChampagneGold
import jp.audioscope.player.ui.theme.CobaltAccent
import jp.audioscope.player.ui.theme.ErrorCoral
import jp.audioscope.player.ui.theme.LosslessCyan
import jp.audioscope.player.ui.theme.ObsidianSurfaceElevated
import jp.audioscope.player.ui.theme.OutlineSubtle
import jp.audioscope.player.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun SpectrumWaveformVisualizer(
    frame: AudioVisualFrame,
    mode: VisualizerMode,
    modifier: Modifier = Modifier,
    canvasHeight: Dp = 132.dp
) {
    if (mode == VisualizerMode.OFF) return

    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ObsidianSurfaceElevated)
            .border(1.dp, OutlineSubtle, shape)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (mode) {
                    VisualizerMode.SPECTRUM -> "リアルタイムFFTスペクトラム (20Hz - ${(frame.nyquistHz / 1000f).toInt()}kHz)"
                    VisualizerMode.WAVEFORM -> "リアルタイムPCM波形オシロスコープ"
                    VisualizerMode.DUAL -> "FFTスペクトラム & PCM波形モニター"
                    VisualizerMode.OFF -> ""
                },
                style = MaterialTheme.typography.labelMedium,
                color = ChampagneGold
            )
            Text(
                text = String.format(
                    Locale.US,
                    "RMS %.1f dBFS / Peak %.1f dBFS",
                    frame.rmsDbfs,
                    frame.peakDbfs
                ),
                style = MaterialTheme.typography.labelSmall,
                color = if (frame.peakDbfs > -0.3f) ErrorCoral else TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(canvasHeight)
        ) {
            val w = size.width
            val h = size.height

            // Subtle dBFS horizontal gridlines
            for (g in 1..3) {
                val gy = h * (g / 4f)
                drawLine(
                    color = OutlineSubtle.copy(alpha = 0.55f),
                    start = Offset(0f, gy),
                    end = Offset(w, gy),
                    strokeWidth = 1f
                )
            }

            val drawSpec = mode == VisualizerMode.SPECTRUM || mode == VisualizerMode.DUAL
            val drawWave = mode == VisualizerMode.WAVEFORM || mode == VisualizerMode.DUAL

            if (drawSpec && frame.logBandMagnitudesDb.isNotEmpty()) {
                val bands = frame.logBandMagnitudesDb
                val centers = frame.logBandCenterHz
                val count = bands.size
                val slotW = w / count.toFloat()
                val barW = (slotW * 0.72f).coerceAtLeast(2f)
                val specAreaHeight = if (mode == VisualizerMode.DUAL) h * 0.62f else h
                val specBottom = h

                for (i in 0 until count) {
                    val db = bands[i].coerceIn(-90f, 0f)
                    val norm = ((db + 90f) / 90f).coerceIn(0.02f, 1f)
                    val barH = specAreaHeight * norm
                    val isUltrasonic = centers.getOrElse(i) { 0f } >= 20_000f
                    val barColor = if (isUltrasonic) ChampagneGold else LosslessCyan

                    drawRoundRect(
                        color = barColor.copy(alpha = if (mode == VisualizerMode.DUAL) 0.78f else 0.92f),
                        topLeft = Offset(i * slotW + (slotW - barW) * 0.5f, specBottom - barH),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(3f, 3f)
                    )
                }
            }

            if (drawWave && frame.waveformNormalized.size >= 2) {
                val pts = frame.waveformNormalized
                val waveCenterY = if (mode == VisualizerMode.DUAL) h * 0.22f else h * 0.5f
                val waveAmp = if (mode == VisualizerMode.DUAL) h * 0.18f else h * 0.44f
                val path = Path()
                for (i in pts.indices) {
                    val x = (i.toFloat() / (pts.size - 1).coerceAtLeast(1)) * w
                    val y = waveCenterY - pts[i] * waveAmp
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = if (mode == VisualizerMode.DUAL) CobaltAccent else ChampagneGold,
                    style = Stroke(width = 2.4f, cap = StrokeCap.Round)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (frame.dominantFrequencyHz > 0f) {
                    String.format(Locale.US, "ピーク周波数: %.0f Hz", frame.dominantFrequencyHz)
                } else {
                    "ピーク周波数: -- Hz (無音/停止中)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            frame.ultrasonicEnergyDb?.let { usDb ->
                Text(
                    text = String.format(Locale.US, "20kHz超高域エネルギー: %.1f dBFS", usDb),
                    style = MaterialTheme.typography.labelSmall,
                    color = ChampagneGold
                )
            } ?: Text(
                text = "金色の帯域バー = 20kHz超ハイレゾ領域",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}
