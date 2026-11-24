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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import jp.audioscope.player.model.ByteUnitFormatter
import jp.audioscope.player.model.TrafficSamplePoint
import jp.audioscope.player.ui.theme.LosslessCyan
import jp.audioscope.player.ui.theme.ObsidianBackground
import jp.audioscope.player.ui.theme.OutlineSubtle
import jp.audioscope.player.ui.theme.TextSecondary
import jp.audioscope.player.ui.theme.WarningAmber
import kotlin.math.max

@Composable
fun NetworkTrafficChart(
    samples: List<TrafficSamplePoint>,
    peakRxBytesPerSec: Long,
    modifier: Modifier = Modifier
) {
    val maxScale = max(
        1024L,
        samples.maxOfOrNull { max(it.rxBytesPerSec, it.txBytesPerSec) } ?: 1024L
    )

    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ObsidianBackground)
            .border(1.dp, OutlineSubtle, shape)
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "直近60秒の通信速度推移（水色: 受信RX / 橙: 送信TX）",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                text = "最大 scale: ${ByteUnitFormatter.formatSpeed(maxScale)}",
                style = MaterialTheme.typography.labelSmall,
                color = LosslessCyan
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val w = size.width
            val h = size.height

            for (i in 1..3) {
                val y = h * (i / 4f)
                drawLine(
                    color = OutlineSubtle.copy(alpha = 0.5f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
            }

            if (samples.size >= 2) {
                val rxPath = Path()
                val rxFill = Path()
                val txPath = Path()
                val denom = (samples.size - 1).coerceAtLeast(1).toFloat()

                samples.forEachIndexed { idx, pt ->
                    val x = (idx / denom) * w
                    val rxY = h - ((pt.rxBytesPerSec.toFloat() / maxScale.toFloat()).coerceIn(0f, 1f) * (h - 6f))
                    val txY = h - ((pt.txBytesPerSec.toFloat() / maxScale.toFloat()).coerceIn(0f, 1f) * (h - 6f))
                    if (idx == 0) {
                        rxPath.moveTo(x, rxY)
                        rxFill.moveTo(x, h)
                        rxFill.lineTo(x, rxY)
                        txPath.moveTo(x, txY)
                    } else {
                        rxPath.lineTo(x, rxY)
                        rxFill.lineTo(x, rxY)
                        txPath.lineTo(x, txY)
                    }
                    if (idx == samples.lastIndex) {
                        rxFill.lineTo(x, h)
                        rxFill.close()
                    }
                }

                drawPath(
                    path = rxFill,
                    brush = Brush.verticalGradient(
                        listOf(LosslessCyan.copy(alpha = 0.28f), LosslessCyan.copy(alpha = 0.01f))
                    )
                )
                drawPath(
                    path = rxPath,
                    color = LosslessCyan,
                    style = Stroke(width = 2.6f, cap = StrokeCap.Round)
                )
                drawPath(
                    path = txPath,
                    color = WarningAmber,
                    style = Stroke(width = 2.0f, cap = StrokeCap.Round)
                )
            } else {
                // Baseline zero line
                drawLine(
                    color = LosslessCyan.copy(alpha = 0.5f),
                    start = Offset(0f, h - 2f),
                    end = Offset(w, h - 2f),
                    strokeWidth = 2f
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "60秒前",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                text = "セッション最大受信速度: ${ByteUnitFormatter.formatSpeed(peakRxBytesPerSec)}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Text(
                text = "現在",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}
