package jp.audioscope.player

import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.audioscope.player.model.AudioOutputSpecs
import jp.audioscope.player.model.AudioSourceSpecs
import jp.audioscope.player.model.AudioVisualFrame
import jp.audioscope.player.model.BitrateControlMode
import jp.audioscope.player.model.BluetoothAudioState
import jp.audioscope.player.model.LosslessClassification
import jp.audioscope.player.model.NetworkTrafficSnapshot
import jp.audioscope.player.model.ObtainableValue
import jp.audioscope.player.model.OutputRouteCategory
import jp.audioscope.player.model.PlaybackDataOrigin
import jp.audioscope.player.model.TrackItem
import jp.audioscope.player.model.ValueProvenance
import jp.audioscope.player.model.VisualizerMode
import jp.audioscope.player.ui.AudioScopeUiState
import jp.audioscope.player.ui.screens.AudioQualityDetailScreen
import jp.audioscope.player.ui.screens.NowPlayingScreen
import jp.audioscope.player.ui.theme.AudioScopeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioScopeUiAndServiceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleFlacSpecs = AudioSourceSpecs(
        containerFormat = "FLAC",
        audioCodec = "FLAC (Free Lossless Audio Codec)",
        compressionSummary = "可逆圧縮（ロスレス）",
        losslessClassification = LosslessClassification.COMPRESSED_LOSSLESS,
        bitrateKbps = 3102,
        bitrateProvenance = ValueProvenance.STREAM_MEASURED,
        bitrateMode = BitrateControlMode.VBR_LOSSLESS,
        sampleRateHz = 96_000,
        sampleRateProvenance = ValueProvenance.STREAM_MEASURED,
        bitDepth = 24,
        bitDepthNote = "24 bit (FLAC STREAMINFO実測)",
        channelCount = 2,
        channelLayoutLabel = "Stereo (2ch)",
        durationMs = 240_000L,
        fileSizeBytes = 93_060_000L,
        audioDataBytes = 93_050_000L,
        pathOrUri = "Music/HiRes/Sample96k24b.flac"
    )

    @Test
    fun nowPlayingScreen_displaysTrackSpecsAndQualityDetailButton() {
        val sampleTrack = TrackItem(
            id = 1L,
            contentUri = Uri.parse("content://media/external/audio/media/1"),
            title = "Acoustic Horizon",
            artist = "Tokyo Studio Ensemble",
            album = "Precision Sessions",
            albumId = 10L,
            artworkUri = null,
            durationMs = 240_000L,
            sizeBytes = 93_060_000L,
            mimeType = "audio/flac",
            displayName = "Sample96k24b.flac",
            relativeFolder = "Music/HiRes",
            displayPathOrUri = "Music/HiRes/Sample96k24b.flac",
            dateAddedSec = 1_700_000_000L
        )

        val state = AudioScopeUiState(
            currentTrack = sampleTrack,
            sourceSpecs = sampleFlacSpecs,
            visualizerMode = VisualizerMode.OFF
        )

        composeTestRule.setContent {
            AudioScopeTheme {
                NowPlayingScreen(
                    uiState = state,
                    visualFrame = AudioVisualFrame(isActive = false),
                    onTogglePlayPause = {},
                    onSeekTo = {},
                    onSkipPrevious = {},
                    onSkipNext = {},
                    onToggleShuffle = {},
                    onCycleRepeat = {},
                    onToggleFavorite = {},
                    onToggleCompactBadges = {},
                    onCycleVisualizerMode = {},
                    onOpenQualityDetail = {},
                    onShareAudioSpecs = {},
                    onJumpToQueueIndex = {},
                    onRemoveQueueItem = {},
                    onMoveQueueItem = { _, _ -> },
                    onGoToLibrary = {}
                )
            }
        }

        composeTestRule.onNodeWithText("Acoustic Horizon").assertIsDisplayed()
        composeTestRule.onNodeWithText("96 kHz").assertIsDisplayed()
        composeTestRule.onNodeWithText("24 bit").assertIsDisplayed()
        composeTestRule.onNodeWithText("音質詳細モニターを開く (SOURCE / OUTPUT / NETWORK)").assertIsDisplayed()
    }

    @Test
    fun audioQualityDetailScreen_displaysSourceOutputNetworkSections() {
        val output = AudioOutputSpecs(
            routeCategory = OutputRouteCategory.BLUETOOTH_A2DP,
            activeDeviceProductName = "Wireless Earbuds",
            androidDeviceTypeConstantName = "TYPE_BLUETOOTH_A2DP",
            osNativeOutputSampleRateHz = 48_000,
            osFramesPerBuffer = 256,
            activeAudioTrackConfig = null,
            isBitPerfectGuaranteed = false,
            bitPerfectStatusExplanation = "非成立（Bluetoothワイヤレス伝送）",
            resamplingDiagnostic = "元ファイル: 96000 Hz → OSミキサー基準: 48000 Hz",
            bluetoothState = BluetoothAudioState(
                isBluetoothRouteActive = true,
                profileDeviceName = ObtainableValue.Available("Wireless Earbuds", "AudioDeviceInfo"),
                connectionStateLabel = "接続済み",
                isA2dpStreamingPlaying = ObtainableValue.Available(true, "BluetoothA2dp.isA2dpPlaying()"),
                bluetoothTransportMethod = "Bluetooth Classic Audio (A2DP)",
                activeCodec = ObtainableValue.RestrictedByOs("取得不可（OSによる制限）"),
                activeCodecSampleRate = ObtainableValue.RestrictedByOs("取得不可（OSによる制限）"),
                activeCodecChannelMode = ObtainableValue.RestrictedByOs("取得不可（OSによる制限）"),
                activeCodecBitrate = ObtainableValue.RestrictedByOs("取得不可（OSによる制限）"),
                linkQualityInfo = ObtainableValue.RestrictedByOs("取得不可（OSによる制限）"),
                deviceReportedSampleRateCapabilitiesHz = listOf(44100, 48000, 96000),
                deviceReportedEncodingCapabilities = listOf("PCM 16-bit", "PCM 24-bit Packed"),
                deviceReportedChannelCounts = listOf(2)
            ),
            usbMixerState = null,
            deviceCapabilitiesSummary = "44100Hz, 48000Hz, 96000Hz"
        )

        val traffic = NetworkTrafficSnapshot(
            isTrafficStatsSupported = true,
            playbackOrigin = PlaybackDataOrigin.LOCAL_STORAGE,
            uidTotalRxBytesSinceBoot = 0L,
            uidTotalTxBytesSinceBoot = 0L,
            sessionRxBytes = 0L,
            sessionTxBytes = 0L,
            currentRxBytesPerSec = 0L,
            currentTxBytesPerSec = 0L,
            peakRxBytesPerSec = 0L,
            historySamples = emptyList(),
            isBluetoothOutputActive = true,
            actualBluetoothBytesStatus = "取得不可（OSによる制限）",
            estimatedUncompressedPcmBitrateKbps = 1536,
            bluetoothEstimationNote = "【推定】理論値",
            countingScopeExplanationJp = "TrafficStats説明"
        )

        composeTestRule.setContent {
            AudioScopeTheme {
                AudioQualityDetailScreen(
                    uiState = AudioScopeUiState(sourceSpecs = sampleFlacSpecs, visualizerMode = VisualizerMode.OFF),
                    outputSpecs = output,
                    trafficSnapshot = traffic,
                    visualFrame = AudioVisualFrame(isActive = false),
                    onSelectVisualizerMode = {},
                    onRequestBluetoothPermission = {},
                    onOpenDeveloperOptions = {},
                    onOpenBluetoothSettings = {},
                    onToggleUsbBitPerfect = {},
                    onResetTrafficSession = {},
                    onShareSpecsText = {}
                )
            }
        }

        composeTestRule.onNodeWithText("SOURCE").assertIsDisplayed()
        composeTestRule.onNodeWithText("OUTPUT").assertIsDisplayed()
        composeTestRule.onNodeWithText("NETWORK").assertIsDisplayed()
    }
}
