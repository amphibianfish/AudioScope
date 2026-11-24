package jp.audioscope.player.model

/**
 * 出力経路種別（AudioDeviceInfo の公開定数に基づく）
 */
enum class OutputRouteCategory(val labelJp: String) {
    BUILTIN_SPEAKER("内蔵スピーカー"),
    WIRED_HEADSET_OR_LINE("有線イヤホン / ヘッドホン / ライン出力"),
    USB_DAC_AUDIO("USB DAC / USBオーディオ機器"),
    BLUETOOTH_A2DP("Bluetooth オーディオ (Classic A2DP)"),
    BLUETOOTH_LE_AUDIO("Bluetooth LE Audio (BLE Headset / Speaker / Broadcast)"),
    BLUETOOTH_SCO("Bluetooth 通話プロファイル (SCO)"),
    OTHER_EXTERNAL("外部デジタル出力 (HDMI / Dock 等)")
}

/**
 * OS制限や公開APIの取得可否を正直に表現するラッパー
 */
sealed class ObtainableValue<out T> {
    data class Available<T>(val value: T, val sourceApi: String) : ObtainableValue<T>()
    data class RestrictedByOs(val reasonJp: String, val developerOptionsHintJp: String? = null) : ObtainableValue<Nothing>()
    data class PermissionRequired(val permissionName: String, val reasonJp: String) : ObtainableValue<Nothing>()
    data class NotApplicable(val reasonJp: String) : ObtainableValue<Nothing>()
}

/**
 * ExoPlayer AudioSink / AudioTrack が実際に開いた出力ストリーム構成
 */
data class ActiveAudioTrackConfig(
    val sampleRateHz: Int,
    val channelCount: Int,
    val pcmEncodingLabel: String,
    val bufferSizeBytes: Int,
    val isOffload: Boolean,
    val isTunneling: Boolean
)

/**
 * USB DAC 接続時の Android 14+ (API 34-37) AudioMixerAttributes 状態
 */
data class UsbMixerAttributeState(
    val usbBitPerfectSupportedByDevice: Boolean,
    val usbBitPerfectCurrentlyActive: Boolean,
    val supportedBitPerfectFormats: List<String>,
    val activeMixerBehaviorLabel: String
)

/**
 * Bluetooth 接続詳細（公開APIで取得できた事実と、OS制限で取得不可の項目を厳密に分離）
 */
data class BluetoothAudioState(
    val isBluetoothRouteActive: Boolean,
    val profileDeviceName: ObtainableValue<String>,
    val connectionStateLabel: String,
    val isA2dpStreamingPlaying: ObtainableValue<Boolean>,
    val bluetoothTransportMethod: String, // Classic A2DP / LE Audio / 未接続
    // 以下4項目は一般アプリの公開APIでは実使用値を取得できないケースがあるため ObtainableValue で厳密管理
    val activeCodec: ObtainableValue<String>,
    val activeCodecSampleRate: ObtainableValue<String>,
    val activeCodecChannelMode: ObtainableValue<String>,
    val activeCodecBitrate: ObtainableValue<String>,
    val linkQualityInfo: ObtainableValue<String>,
    // AudioDeviceInfo から返る「機器の対応能力リスト」は実使用値と混同しないよう別フィールドに分離
    val deviceReportedSampleRateCapabilitiesHz: List<Int>,
    val deviceReportedEncodingCapabilities: List<String>,
    val deviceReportedChannelCounts: List<Int>
)

data class OutputRouteSwitchEvent(
    val timestampEpochMs: Long,
    val previousRouteLabel: String,
    val currentRouteLabel: String,
    val triggerReason: String
)

/**
 * OUTPUT領域全体の状態モデル
 */
data class AudioOutputSpecs(
    val routeCategory: OutputRouteCategory,
    val activeDeviceProductName: String,
    val androidDeviceTypeConstantName: String,
    val osNativeOutputSampleRateHz: Int?,
    val osFramesPerBuffer: Int?,
    val activeAudioTrackConfig: ActiveAudioTrackConfig?,
    val isBitPerfectGuaranteed: Boolean,
    val bitPerfectStatusExplanation: String,
    val resamplingDiagnostic: String,
    val bluetoothState: BluetoothAudioState,
    val usbMixerState: UsbMixerAttributeState?,
    val deviceCapabilitiesSummary: String,
    val routeHistory: List<OutputRouteSwitchEvent> = emptyList()
)

/**
 * 規格参考シート用（現在接続中の値ではないことをUI上で明示するための静的リファレンス）
 */
data class BluetoothCodecReferenceSpec(
    val codecName: String,
    val typicalBitrateRange: String,
    val maxSampleRateAndDepth: String,
    val compressionNature: String
)

val BLUETOOTH_CODEC_REFERENCE_SPECS = listOf(
    BluetoothCodecReferenceSpec("SBC", "192 - 328 kbps", "最大 48 kHz / 16 bit", "非可逆（A2DP必須標準コーデック）"),
    BluetoothCodecReferenceSpec("AAC", "128 - 256 kbps", "最大 48 kHz / 16-24 bit", "非可逆（音響心理モデル圧縮）"),
    BluetoothCodecReferenceSpec("aptX", "352 / 384 kbps", "最大 48 kHz / 16 bit", "非可逆（ADPCM系低遅延固定比率）"),
    BluetoothCodecReferenceSpec("aptX HD", "576 kbps", "最大 48 kHz / 24 bit", "非可逆（24bit対応拡張ADPCM）"),
    BluetoothCodecReferenceSpec("aptX Adaptive", "279 - 420 kbps (可変)", "最大 96 kHz / 24 bit", "非可逆（電波状況に応じた動的ビットレート制御）"),
    BluetoothCodecReferenceSpec("aptX Lossless", "約 1,100 - 1,200 kbps (可変)", "44.1 kHz / 16 bit (ロスレス時)", "CD品質条件成立時のみ可逆、混雑時は可変ロッシーへ遷移"),
    BluetoothCodecReferenceSpec("LDAC", "330 / 660 / 990 kbps (44.1k系は 303/606/909 kbps)", "最大 96 kHz / 24 bit (32bit入力対応)", "非可逆（最大990kbpsのハイレゾワイヤレス規格）"),
    BluetoothCodecReferenceSpec("LC3 (LE Audio)", "160 - 345 kbps (構成依存)", "最大 48 kHz / 16-32 bit", "非可逆（Bluetooth LE Audio標準・低消費電力高効率）"),
    BluetoothCodecReferenceSpec("Samsung Seamless Codec (SSC)", "最大 512 kbps - 2,000+ kbps (対応Galaxy Buds限定)", "最大 96 kHz / 24 bit (UHQ時)", "Samsung独自拡張（Galaxy端末＋対応Galaxy Buds組み合わせ時）")
)
