package jp.audioscope.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.audioscope.player.model.AudioOutputSpecs
import jp.audioscope.player.model.AudioVisualFrame
import jp.audioscope.player.model.BLUETOOTH_CODEC_REFERENCE_SPECS
import jp.audioscope.player.model.ByteUnitFormatter
import jp.audioscope.player.model.NetworkTrafficSnapshot
import jp.audioscope.player.model.ObtainableValue
import jp.audioscope.player.model.OutputRouteCategory
import jp.audioscope.player.model.PlaybackDataOrigin
import jp.audioscope.player.model.VisualizerMode
import jp.audioscope.player.ui.AudioScopeUiState
import jp.audioscope.player.ui.components.NetworkTrafficChart
import jp.audioscope.player.ui.components.SpectrumWaveformVisualizer
import jp.audioscope.player.ui.theme.ChampagneGold
import jp.audioscope.player.ui.theme.ChampagneGoldDim
import jp.audioscope.player.ui.theme.CobaltAccent
import jp.audioscope.player.ui.theme.EmeraldOk
import jp.audioscope.player.ui.theme.LosslessCyan
import jp.audioscope.player.ui.theme.LosslessCyanDim
import jp.audioscope.player.ui.theme.ObsidianBackground
import jp.audioscope.player.ui.theme.ObsidianSurfaceElevated
import jp.audioscope.player.ui.theme.ObsidianSurfaceHigh
import jp.audioscope.player.ui.theme.OutlineSubtle
import jp.audioscope.player.ui.theme.TextSecondary
import jp.audioscope.player.ui.theme.WarningAmber
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 音質詳細画面（要件7に準拠し、SOURCE / OUTPUT / NETWORK の3領域を明確に分離表示）
 * 取得不可の項目は偽装せず、「取得不可」「OSによる制限」と理由・確認方法を表示する。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AudioQualityDetailScreen(
    uiState: AudioScopeUiState,
    outputSpecs: AudioOutputSpecs,
    trafficSnapshot: NetworkTrafficSnapshot,
    visualFrame: AudioVisualFrame,
    onSelectVisualizerMode: (VisualizerMode) -> Unit,
    onRequestBluetoothPermission: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onToggleUsbBitPerfect: (Boolean) -> Unit,
    onResetTrafficSession: () -> Unit,
    onShareSpecsText: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showMetadataAll by remember { mutableStateOf(false) }
    var showCodecReferenceTable by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // Section Jump Bar + Share
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = true,
                    onClick = { coroutineScope.launch { listState.animateScrollToItem(0) } },
                    label = { Text("SOURCE") }
                )
                FilterChip(
                    selected = true,
                    onClick = { coroutineScope.launch { listState.animateScrollToItem(2) } },
                    label = { Text("OUTPUT") }
                )
                FilterChip(
                    selected = true,
                    onClick = { coroutineScope.launch { listState.animateScrollToItem(4) } },
                    label = { Text("NETWORK") }
                )
            }
            OutlinedButton(
                onClick = onShareSpecsText,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Rounded.Share, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("共有", style = MaterialTheme.typography.labelMedium)
            }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // =================================================================
            // SECTION 1: SOURCE (元の音楽ファイルの情報)
            // =================================================================
            item(key = "section_source_header") {
                SectionBanner(
                    tag = "SOURCE",
                    title = "元の音楽ファイル・音声ストリーム実測仕様",
                    icon = Icons.Rounded.GraphicEq,
                    accent = ChampagneGold
                )
            }

            item(key = "section_source_body") {
                val src = uiState.sourceSpecs
                if (src == null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "現在選択されている楽曲がありません。曲を再生するとストリーム解析結果が表示されます。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Top Quick Summary Card (Matches User Request Section 2 Example)
                        Card(
                            colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceHigh),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, ChampagneGold.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "${uiState.currentTrack?.title ?: ""} — 主要スペック概要",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ChampagneGold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                SpecKeyValueRow("Codec", src.audioCodec, highlightColor = LosslessCyan)
                                SpecKeyValueRow("Sample Rate", src.formattedSampleRateKhz)
                                SpecKeyValueRow("Bit Depth", src.formattedBitDepth)
                                SpecKeyValueRow("Channels", src.channelLayoutLabel)
                                SpecKeyValueRow("Bitrate", "${src.formattedBitrate} (${src.bitrateMode.shortTag})")
                                SpecKeyValueRow(
                                    "Lossless",
                                    src.losslessYesNo,
                                    highlightColor = if (src.losslessYesNo == "Yes") EmeraldOk else WarningAmber
                                )
                            }
                        }

                        // Visualizer Mode Switcher + Live Spectrum/Waveform
                        Card(
                            colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "リアルタイム音響信号解析（デコード済みPCM直接タップ）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    VisualizerMode.entries.forEach { m ->
                                        FilterChip(
                                            selected = uiState.visualizerMode == m,
                                            onClick = { onSelectVisualizerMode(m) },
                                            label = { Text(m.labelJp, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                                if (uiState.visualizerMode != VisualizerMode.OFF) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    SpectrumWaveformVisualizer(
                                        frame = visualFrame,
                                        mode = uiState.visualizerMode,
                                        canvasHeight = 136.dp
                                    )
                                }
                            }
                        }

                        // Full Technical File Details Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("詳細ストリーム解析値", style = MaterialTheme.typography.titleMedium)
                                HorizontalDivider(color = OutlineSubtle)
                                SpecKeyValueRow("ファイル形式（コンテナ）", src.containerFormat)
                                SpecKeyValueRow("音声コーデック", src.audioCodec)
                                SpecKeyValueRow("圧縮方式", src.compressionSummary)
                                SpecKeyValueRow("可逆・非可逆判定", src.losslessClassification.labelJp)
                                SpecKeyValueRow("ビットレート制御方式", src.bitrateMode.labelJp)
                                SpecKeyValueRow(
                                    "実効ビットレート",
                                    "${src.formattedBitrate} [${src.bitrateProvenance.labelJp}]"
                                )
                                src.nominalBitrateKbps?.let { nom ->
                                    SpecKeyValueRow("ヘッダー公称ビットレート", "$nom kbps (参考公称値)")
                                }
                                SpecKeyValueRow(
                                    "サンプリング周波数",
                                    "${src.formattedSampleRateKhz} (${src.sampleRateHz ?: 0} Hz / ${src.sampleRateProvenance.labelJp})"
                                )
                                src.originalInputSampleRateHz?.let { origSr ->
                                    SpecKeyValueRow("Opus元録音サンプルレート", "$origSr Hz (OpusHead記録値)")
                                }
                                SpecKeyValueRow("ビット深度", src.bitDepthNote)
                                SpecKeyValueRow("チャンネル数・構成", src.channelLayoutLabel)
                                SpecKeyValueRow(
                                    "再生時間",
                                    src.durationMs?.let { "${formatDurationMmSs(it)} (${it} ms)" } ?: "取得不可"
                                )
                                src.totalSamples?.let { ts ->
                                    SpecKeyValueRow("総サンプルフレーム数", String.format(Locale.US, "%,d samples", ts))
                                }
                                SpecKeyValueRow(
                                    "ファイル全体サイズ",
                                    src.fileSizeBytes?.let { "${ByteUnitFormatter.formatBytes(it)} (${String.format(Locale.US, "%,d", it)} B)" }
                                        ?: "ネットワークストリームのため未確定"
                                )
                                SpecKeyValueRow(
                                    "音声データサイズ（純ペイロード）",
                                    src.audioDataBytes?.let { "${ByteUnitFormatter.formatBytes(it)} (${String.format(Locale.US, "%,d", it)} B)" }
                                        ?: "コンテナ仕様上未分離"
                                )
                                src.metadataOverheadBytes?.let { ov ->
                                    SpecKeyValueRow("タグ・ヘッダー領域サイズ", ByteUnitFormatter.formatBytes(ov))
                                }
                                src.compressionRatioPercent?.let { pct ->
                                    SpecKeyValueRow(
                                        "対リニアPCMサイズ比率",
                                        String.format(Locale.US, "%.1f %% (圧縮削減率 %.1f %%)", pct, (100.0 - pct).coerceAtLeast(0.0))
                                    )
                                }
                                src.encoderOrToolInfo?.let { enc ->
                                    SpecKeyValueRow("エンコーダー / ツール", enc)
                                }
                                src.streamMd5Hex?.let { md5 ->
                                    SpecKeyValueRow("FLAC 音声MD5署名", md5)
                                }
                                src.decoderRuntimeInfo?.let { dec ->
                                    SpecKeyValueRow("Media3 実行時デコーダー入力", dec)
                                }
                                SpecKeyValueRow("ファイルパス / URI", src.pathOrUri)

                                if (src.parseWarnings.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    src.parseWarnings.forEach { w ->
                                        Text("※ $w", style = MaterialTheme.typography.labelSmall, color = WarningAmber)
                                    }
                                }

                                // Embedded Metadata Section
                                Spacer(modifier = Modifier.height(6.dp))
                                HorizontalDivider(color = OutlineSubtle)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "ファイル内メタデータ (${src.embeddedMetadata.size} 項目)",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    TextButton(onClick = { showMetadataAll = !showMetadataAll }) {
                                        Text(if (showMetadataAll) "折りたたむ" else "すべて表示")
                                    }
                                }
                                if (src.embeddedMetadata.isEmpty()) {
                                    Text(
                                        text = "埋め込みメタデータタグは存在しません（ストリーム直接解析のみで表示中）。",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary
                                    )
                                } else {
                                    val entries = if (showMetadataAll) {
                                        src.embeddedMetadata.entries.toList()
                                    } else {
                                        src.embeddedMetadata.entries.take(6)
                                    }
                                    entries.forEach { (k, v) ->
                                        SpecKeyValueRow(k, v)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // SECTION 2: OUTPUT (実際の出力先・OSミキサー・Bluetooth接続情報)
            // =================================================================
            item(key = "section_output_header") {
                SectionBanner(
                    tag = "OUTPUT",
                    title = "実際の出力経路・OSミキサー・Bluetooth診断",
                    icon = Icons.Rounded.Speaker,
                    accent = LosslessCyan
                )
            }

            item(key = "section_output_body") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Signal Path & Bit-Perfect Honesty Card
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, LosslessCyan.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("現在の再生出力先", style = MaterialTheme.typography.titleMedium, color = LosslessCyan)
                                Surface(
                                    color = if (outputSpecs.isBitPerfectGuaranteed) ChampagneGoldDim else ObsidianSurfaceHigh,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (outputSpecs.isBitPerfectGuaranteed) "BIT-PERFECT ACTIVE" else "OS MIXER ROUTE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (outputSpecs.isBitPerfectGuaranteed) ChampagneGold else TextSecondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            SpecKeyValueRow("再生先カテゴリ", outputSpecs.routeCategory.labelJp, highlightColor = ChampagneGold)
                            SpecKeyValueRow("出力デバイス製品名", outputSpecs.activeDeviceProductName)
                            SpecKeyValueRow("Android デバイス種別定数", outputSpecs.androidDeviceTypeConstantName)
                            outputSpecs.activeAudioTrackConfig?.let { atc ->
                                SpecKeyValueRow(
                                    "ExoPlayer AudioTrack実出力",
                                    "${atc.sampleRateHz} Hz / ${atc.pcmEncodingLabel} / ${atc.channelCount}ch (Buffer ${ByteUnitFormatter.formatBytes(atc.bufferSizeBytes.toLong())})"
                                )
                            }
                            SpecKeyValueRow(
                                "OSネイティブミキサー基準",
                                "${outputSpecs.osNativeOutputSampleRateHz ?: 48000} Hz (バッファ ${outputSpecs.osFramesPerBuffer ?: 0} frames)"
                            )
                            SpecKeyValueRow("信号経路・リサンプル診断", outputSpecs.resamplingDiagnostic)
                            Text(
                                text = outputSpecs.bitPerfectStatusExplanation,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (outputSpecs.isBitPerfectGuaranteed) EmeraldOk else TextSecondary
                            )

                            // USB DAC Bit-Perfect Switch (Android 14+ API 34-37)
                            if (outputSpecs.routeCategory == OutputRouteCategory.USB_DAC_AUDIO) {
                                HorizontalDivider(color = OutlineSubtle)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("USB DAC ビットパーフェクト優先モード", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            text = outputSpecs.usbMixerState?.activeMixerBehaviorLabel ?: "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                    Switch(
                                        checked = uiState.preferUsbBitPerfect,
                                        onCheckedChange = onToggleUsbBitPerfect
                                    )
                                }
                            }
                        }
                    }

                    // Bluetooth Audio Detailed Card (Zero Fabrication Policy)
                    val bt = outputSpecs.bluetoothState
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Bluetooth, contentDescription = null, tint = CobaltAccent)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Bluetooth接続・コーデック検査（公開API実測）", style = MaterialTheme.typography.titleMedium)
                            }
                            HorizontalDivider(color = OutlineSubtle)

                            ObtainableRow("接続機器名", bt.profileDeviceName)
                            SpecKeyValueRow("接続状態", bt.connectionStateLabel)
                            SpecKeyValueRow("再生先", outputSpecs.routeCategory.labelJp)
                            SpecKeyValueRow("Bluetoothオーディオ方式", bt.bluetoothTransportMethod)
                            when (val st = bt.isA2dpStreamingPlaying) {
                                is ObtainableValue.Available -> SpecKeyValueRow(
                                    "A2DPストリーミング状態",
                                    if (st.value) "再生ストリーム送信中 (${st.sourceApi})" else "待機中 (${st.sourceApi})"
                                )
                                is ObtainableValue.NotApplicable -> SpecKeyValueRow("A2DPストリーミング状態", st.reasonJp)
                                is ObtainableValue.PermissionRequired -> SpecKeyValueRow("A2DPストリーミング状態", st.reasonJp, WarningAmber)
                                is ObtainableValue.RestrictedByOs -> SpecKeyValueRow("A2DPストリーミング状態", st.reasonJp, WarningAmber)
                            }

                            ObtainableRow("現在のBluetoothコーデック（実使用値）", bt.activeCodec)
                            ObtainableRow("コーデックのサンプリング周波数（実使用値）", bt.activeCodecSampleRate)
                            ObtainableRow("コーデックのチャンネル構成（実使用値）", bt.activeCodecChannelMode)
                            ObtainableRow("コーデックのビットレート（実使用値）", bt.activeCodecBitrate)
                            ObtainableRow("接続品質（RSSI・再送率）", bt.linkQualityInfo)

                            if (!uiState.hasBluetoothConnectPermission) {
                                OutlinedButton(
                                    onClick = onRequestBluetoothPermission,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("「付近のデバイス (BLUETOOTH_CONNECT)」権限を許可して接続機器名を取得")
                                }
                            }

                            // Device Capabilities (Explicitly separated from active negotiated codec!)
                            if (bt.isBluetoothRouteActive) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    color = ObsidianBackground,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = "【参考】AudioDeviceInfoが通知する出力機器の対応能力（※現在使用中の値ではありません）",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = ChampagneGold
                                        )
                                        Text(
                                            text = "対応サンプルレート一覧: " +
                                                (bt.deviceReportedSampleRateCapabilitiesHz.takeIf { it.isNotEmpty() }
                                                    ?.joinToString(", ") { "${it} Hz" } ?: "OSミキサー管理（個別指定なし）"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                        Text(
                                            text = "対応エンコーディング一覧: " +
                                                (bt.deviceReportedEncodingCapabilities.takeIf { it.isNotEmpty() }
                                                    ?.joinToString(", ") ?: "OS標準"),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }

                            // Developer Options Verification Guidance for Galaxy S26 (One UI 9 / Android 17)
                            Spacer(modifier = Modifier.height(4.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = LosslessCyanDim.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Build, contentDescription = null, tint = LosslessCyan)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Galaxy S26 (Android 17 / One UI 9) で実使用コーデックを確認する方法",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = LosslessCyan
                                        )
                                    }
                                    Text(
                                        text = "Androidのセキュリティ仕様により、SBC / AAC / aptX / LDAC / LC3等の実使用コーデック取得API（BluetoothA2dp.getCodecStatus）はシステムアプリ専用権限（BLUETOOTH_PRIVILEGED）で保護されています。以下のボタンからAndroid標準の「開発者向けオプション」または「Bluetooth設定」を開くと、偽装のないOS公式の現在値を確認できます。\n" +
                                            "※未有効の場合：設定 → 端末情報 → ソフトウェア情報 →「ビルド番号」を7回連続タップで有効化できます。",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = onOpenDeveloperOptions, modifier = Modifier.weight(1f)) {
                                            Text("開発者向けオプションを開く", style = MaterialTheme.typography.labelSmall)
                                        }
                                        OutlinedButton(onClick = onOpenBluetoothSettings, modifier = Modifier.weight(1f)) {
                                            Text("Bluetooth設定を開く", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }

                            // Collapsible Codec Reference Sheet
                            TextButton(onClick = { showCodecReferenceTable = !showCodecReferenceTable }) {
                                Text(
                                    if (showCodecReferenceTable) "▲ Bluetoothコーデック規格参考表を閉じる"
                                    else "▼ Bluetoothコーデック規格参考表を表示（※規格値であり現在の接続値ではありません）"
                                )
                            }
                            if (showCodecReferenceTable) {
                                BLUETOOTH_CODEC_REFERENCE_SPECS.forEach { ref ->
                                    Surface(
                                        color = ObsidianBackground,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text(
                                                text = "${ref.codecName}  [${ref.typicalBitrateRange}]",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = ChampagneGold
                                            )
                                            Text(
                                                text = "${ref.maxSampleRateAndDepth} • ${ref.compressionNature}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }
                            }

                            // Output Device Switch History
                            if (outputSpecs.routeHistory.isNotEmpty()) {
                                HorizontalDivider(color = OutlineSubtle)
                                Text("出力デバイス切り替え検知ログ", style = MaterialTheme.typography.labelMedium, color = ChampagneGold)
                                val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.JAPAN) }
                                outputSpecs.routeHistory.take(5).forEach { ev ->
                                    Text(
                                        text = "${timeFmt.format(Date(ev.timestampEpochMs))} [${ev.triggerReason}] ${ev.previousRouteLabel} → ${ev.currentRouteLabel}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // SECTION 3: NETWORK (通信量・転送速度モニター・Bluetooth理論値区別)
            // =================================================================
            item(key = "section_network_header") {
                SectionBanner(
                    tag = "NETWORK",
                    title = "アプリ通信量・転送速度モニター (TrafficStats)",
                    icon = Icons.Rounded.NetworkCheck,
                    accent = CobaltAccent
                )
            }

            item(key = "section_network_body") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ObsidianSurfaceElevated),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    color = if (trafficSnapshot.playbackOrigin == PlaybackDataOrigin.NETWORK_STREAM) {
                                        WarningAmber.copy(alpha = 0.2f)
                                    } else {
                                        LosslessCyanDim
                                    },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = trafficSnapshot.playbackOrigin.labelJp,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (trafficSnapshot.playbackOrigin == PlaybackDataOrigin.NETWORK_STREAM) {
                                            WarningAmber
                                        } else {
                                            LosslessCyan
                                        },
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                                OutlinedButton(
                                    onClick = onResetTrafficSession,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("セッションリセット", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            Text(
                                text = trafficSnapshot.playbackOrigin.detailJp,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )

                            HorizontalDivider(color = OutlineSubtle)
                            SpecKeyValueRow(
                                "現在の受信速度 (RX)",
                                ByteUnitFormatter.formatSpeed(trafficSnapshot.currentRxBytesPerSec),
                                highlightColor = LosslessCyan
                            )
                            SpecKeyValueRow(
                                "現在の送信速度 (TX)",
                                ByteUnitFormatter.formatSpeed(trafficSnapshot.currentTxBytesPerSec)
                            )
                            SpecKeyValueRow(
                                "セッション内の累積通信量 (RX + TX)",
                                ByteUnitFormatter.formatBytes(trafficSnapshot.sessionTotalBytes),
                                highlightColor = ChampagneGold
                            )
                            SpecKeyValueRow(
                                "セッション受信 / 送信内訳",
                                "受信 ${ByteUnitFormatter.formatBytes(trafficSnapshot.sessionRxBytes)}  /  送信 ${ByteUnitFormatter.formatBytes(trafficSnapshot.sessionTxBytes)}"
                            )
                            SpecKeyValueRow(
                                "アプリの受信データ量（端末起動累計）",
                                ByteUnitFormatter.formatBytes(trafficSnapshot.uidTotalRxBytesSinceBoot)
                            )
                            SpecKeyValueRow(
                                "アプリの送信データ量（端末起動累計）",
                                ByteUnitFormatter.formatBytes(trafficSnapshot.uidTotalTxBytesSinceBoot)
                            )

                            NetworkTrafficChart(
                                samples = trafficSnapshot.historySamples,
                                peakRxBytesPerSec = trafficSnapshot.peakRxBytesPerSec
                            )

                            // Bluetooth Transfer Rate Strict Distinction Card
                            HorizontalDivider(color = OutlineSubtle)
                            Text(
                                text = "Bluetooth転送情報（実通信量と理論値の区別）",
                                style = MaterialTheme.typography.titleMedium,
                                color = CobaltAccent
                            )
                            SpecKeyValueRow(
                                "Bluetooth実送信バイト数・パケット数",
                                trafficSnapshot.actualBluetoothBytesStatus,
                                highlightColor = WarningAmber
                            )
                            SpecKeyValueRow(
                                "圧縮前PCMベースライン転送レート",
                                trafficSnapshot.estimatedUncompressedPcmBitrateKbps?.let {
                                    "【推定・PCM理論値】 ${String.format(Locale.US, "%,d", it)} kbps (${ByteUnitFormatter.formatSpeed((it * 1000L) / 8L)})"
                                } ?: "Bluetooth音声未出力または停止中"
                            )
                            if (trafficSnapshot.bluetoothEstimationNote.isNotBlank()) {
                                Text(
                                    text = trafficSnapshot.bluetoothEstimationNote,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }

                            HorizontalDivider(color = OutlineSubtle)
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(Icons.Rounded.Info, contentDescription = null, tint = TextSecondary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = trafficSnapshot.countingScopeExplanationJp,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionBanner(
    tag: String,
    title: String,
    icon: ImageVector,
    accent: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianSurfaceHigh, RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = accent)
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
fun SpecKeyValueRow(
    label: String,
    value: String,
    highlightColor: Color? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.weight(0.42f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = highlightColor ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.58f)
        )
    }
}

@Composable
private fun ObtainableRow(
    label: String,
    obtainable: ObtainableValue<String>
) {
    when (obtainable) {
        is ObtainableValue.Available -> SpecKeyValueRow(
            label = label,
            value = "${obtainable.value} [${obtainable.sourceApi}]",
            highlightColor = EmeraldOk
        )
        is ObtainableValue.RestrictedByOs -> SpecKeyValueRow(
            label = label,
            value = obtainable.reasonJp,
            highlightColor = WarningAmber
        )
        is ObtainableValue.PermissionRequired -> SpecKeyValueRow(
            label = label,
            value = obtainable.reasonJp,
            highlightColor = WarningAmber
        )
        is ObtainableValue.NotApplicable -> SpecKeyValueRow(
            label = label,
            value = obtainable.reasonJp,
            highlightColor = TextSecondary
        )
    }
}
