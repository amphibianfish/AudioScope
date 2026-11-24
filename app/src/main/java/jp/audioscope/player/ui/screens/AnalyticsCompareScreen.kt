package jp.audioscope.player.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import jp.audioscope.player.model.ByteUnitFormatter
import jp.audioscope.player.model.TrackItem
import jp.audioscope.player.ui.AudioScopeUiState
import jp.audioscope.player.ui.theme.ChampagneGold
import jp.audioscope.player.ui.theme.CobaltAccent
import jp.audioscope.player.ui.theme.LosslessCyan
import jp.audioscope.player.ui.theme.ObsidianSurfaceElevated
import jp.audioscope.player.ui.theme.ObsidianSurfaceHigh
import jp.audioscope.player.ui.theme.OutlineSubtle
import jp.audioscope.player.ui.theme.TextSecondary
import java.util.Locale

/**
 * 追加機能画面：
 * 1) 音楽ライブラリの形式別統計（FLAC / WAV / MP3 / AAC / OGG 比率・容量・ハイレゾ曲数）
 * 2) 2ファイル音質情報比較ラボ（FLAC vs MP3 等の実測仕様を並列比較）
 */
@Composable
fun AnalyticsCompareScreen(
    uiState: AudioScopeUiState,
    onSelectCompareTrack: (TrackItem, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val stats = uiState.formatStats

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxSize()
    ) {
        item(key = "stats_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "音楽ライブラリの形式別統計",
                        style = MaterialTheme.typography.titleLarge,
                        color = ChampagneGold
                    )
                    Text(
                        text = "総曲数 ${stats.totalTracks} 曲 • 合計サイズ ${ByteUnitFormatter.formatBytes(stats.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    HorizontalDivider(color = OutlineSubtle)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatBadgeBox("ロスレス曲", "${stats.losslessTracks} 曲", LosslessCyan, Modifier.weight(1f))
                        StatBadgeBox("非可逆(ロッシー)", "${stats.lossyTracks} 曲", CobaltAccent, Modifier.weight(1f))
                        StatBadgeBox("ハイレゾ判定", "${stats.hiResTracks} 曲", ChampagneGold, Modifier.weight(1f))
                    }

                    if (stats.entries.isEmpty()) {
                        Text(
                            text = "ライブラリに曲が読み込まれると、形式ごとの統計内訳が表示されます。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                        stats.entries.forEach { entry ->
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${entry.formatName} (${entry.trackCount} 曲 / Hi-Res ${entry.hiResCount} 曲)",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "%.1f%% • %s",
                                            entry.percentageOfLibrary,
                                            ByteUnitFormatter.formatBytes(entry.totalBytes)
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { (entry.percentageOfLibrary / 100f).coerceIn(0f, 1f) },
                                    color = if (entry.losslessCount > 0) LosslessCyan else CobaltAccent,
                                    trackColor = ObsidianSurfaceHigh,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "comparator_card") {
            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "FLAC vs MP3 ファイル音質情報比較ラボ",
                        style = MaterialTheme.typography.titleLarge,
                        color = LosslessCyan
                    )
                    Text(
                        text = "ライブラリ内の任意の2曲を選択し、ストリーム実測値（ビットレート・サンプルレート・ビット深度・純音声データ量・ナイキスト帯域）を並列比較します。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TrackSelectorDropdown(
                            slotLabel = "スロットA",
                            selectedTrack = uiState.compareLeftTrack,
                            allTracks = uiState.allTracks,
                            onSelect = { onSelectCompareTrack(it, true) },
                            modifier = Modifier.weight(1f)
                        )
                        TrackSelectorDropdown(
                            slotLabel = "スロットB",
                            selectedTrack = uiState.compareRightTrack,
                            allTracks = uiState.allTracks,
                            onSelect = { onSelectCompareTrack(it, false) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (uiState.isComparing) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = ChampagneGold)
                        }
                    } else {
                        val rep = uiState.comparisonReport
                        if (rep == null) {
                            Text(
                                text = "スロットAとスロットBに曲を選択すると比較表が表示されます（曲一覧の「︙」メニューからも指定できます）。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        } else {
                            HorizontalDivider(color = OutlineSubtle)
                            CompareRow("比較項目", "[A] ${rep.leftTitle}", "[B] ${rep.rightTitle}", isHeader = true)
                            CompareRow("コンテナ形式", rep.leftSpecs.containerFormat, rep.rightSpecs.containerFormat)
                            CompareRow("コーデック", rep.leftSpecs.audioCodec, rep.rightSpecs.audioCodec)
                            CompareRow("Lossless", rep.leftSpecs.losslessYesNo, rep.rightSpecs.losslessYesNo)
                            CompareRow("サンプルレート", rep.leftSpecs.formattedSampleRateKhz, rep.rightSpecs.formattedSampleRateKhz)
                            CompareRow("ビット深度", rep.leftSpecs.formattedBitDepth, rep.rightSpecs.formattedBitDepth)
                            CompareRow("チャンネル", rep.leftSpecs.channelLayoutLabel, rep.rightSpecs.channelLayoutLabel)
                            CompareRow(
                                "実測ビットレート",
                                "${rep.leftSpecs.formattedBitrate} (${rep.leftSpecs.bitrateMode.shortTag})",
                                "${rep.rightSpecs.formattedBitrate} (${rep.rightSpecs.bitrateMode.shortTag})"
                            )
                            CompareRow(
                                "音声データ量",
                                rep.leftSpecs.audioDataBytes?.let { ByteUnitFormatter.formatBytes(it) } ?: "N/A",
                                rep.rightSpecs.audioDataBytes?.let { ByteUnitFormatter.formatBytes(it) } ?: "N/A"
                            )
                            CompareRow(
                                "ファイルサイズ",
                                rep.leftSpecs.fileSizeBytes?.let { ByteUnitFormatter.formatBytes(it) } ?: "N/A",
                                rep.rightSpecs.fileSizeBytes?.let { ByteUnitFormatter.formatBytes(it) } ?: "N/A"
                            )

                            HorizontalDivider(color = OutlineSubtle)
                            Text("解析サマリー", style = MaterialTheme.typography.titleMedium, color = ChampagneGold)
                            rep.summaryPoints.forEach { pt ->
                                Text("• $pt", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBadgeBox(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(4.dp)
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun TrackSelectorDropdown(
    slotLabel: String,
    selectedTrack: TrackItem?,
    allTracks: List<TrackItem>,
    onSelect: (TrackItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = selectedTrack?.let { "$slotLabel: ${it.title} (${it.fileExtension})" } ?: "$slotLabel を選択",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            allTracks.take(60).forEach { t ->
                DropdownMenuItem(
                    text = { Text("${t.title} [${t.fileExtension}]", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onSelect(t)
                    }
                )
            }
        }
    }
}

@Composable
private fun CompareRow(
    metric: String,
    leftVal: String,
    rightVal: String,
    isHeader: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = metric,
            style = MaterialTheme.typography.labelMedium,
            color = if (isHeader) ChampagneGold else TextSecondary,
            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(0.30f)
        )
        Text(
            text = leftVal,
            style = MaterialTheme.typography.labelSmall,
            color = if (isHeader) ChampagneGold else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = rightVal,
            style = MaterialTheme.typography.labelSmall,
            color = if (isHeader) ChampagneGold else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.35f)
        )
    }
}
