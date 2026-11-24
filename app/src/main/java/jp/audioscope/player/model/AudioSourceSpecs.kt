package jp.audioscope.player.model

/**
 * 数値の取得根拠（実測値とヘッダー公称値・推定値を厳密に区別する）
 */
enum class ValueProvenance(val labelJp: String) {
    STREAM_MEASURED("ストリーム実測値"),
    HEADER_DECLARED("ヘッダー記録値"),
    DECODER_REPORTED("デコーダー報告値"),
    UNAVAILABLE("取得不可")
}

enum class LosslessClassification(val labelJp: String, val badgeText: String) {
    UNCOMPRESSED_LOSSLESS("非圧縮（リニアPCM / IEEE Float）", "Lossless (PCM)"),
    COMPRESSED_LOSSLESS("可逆圧縮（ロスレス）", "Lossless: Yes"),
    LOSSY("非可逆圧縮（ロッシー）", "Lossless: No"),
    UNKNOWN("判定不能（ストリーム情報不足）", "Lossless: Unknown")
}

enum class BitrateControlMode(val labelJp: String, val shortTag: String) {
    UNCOMPRESSED_CBR("固定ビットレート（非圧縮PCM計算値）", "PCM Fixed"),
    CBR_VERIFIED("CBR（複数フレーム実測一致）", "CBR"),
    CBR_HEADER("CBR（LAME/Infoヘッダー明記）", "CBR"),
    VBR_XING_VBRI("VBR（Xing/VBRIヘッダー実測）", "VBR"),
    VBR_FRAME_DETECTED("VBR（複数フレーム走査で変動検知）", "VBR"),
    VBR_LOSSLESS("VBR（可逆圧縮フレーム可変・実測平均）", "VBR (Lossless)"),
    ABR_HEADER("ABR（平均ビットレート制御・LAME明記）", "ABR"),
    NOMINAL_HEADER("公称ビットレート（コンテナ/ヘッダー記載値）", "ヘッダー公称値"),
    UNKNOWN("不明（フレーム情報未検出）", "不明")
}

/**
 * 再生中または検査対象ファイルの完全な音質仕様モデル（SOURCE領域）
 */
data class AudioSourceSpecs(
    val containerFormat: String,                  // FLAC / MP3 / WAV / MP4 (M4A) / OGG / ADTS 等
    val audioCodec: String,                       // FLAC / MPEG-1 Audio Layer III / Linear PCM / IEEE Float PCM / AAC-LC / ALAC / Vorbis / Opus 等
    val compressionSummary: String,               // 可逆圧縮 (FLAC) / 非圧縮 (PCM) / 非可逆圧縮 (MP3) 等
    val losslessClassification: LosslessClassification,
    val bitrateKbps: Int?,                        // kbps
    val bitrateProvenance: ValueProvenance,
    val bitrateMode: BitrateControlMode,
    val nominalBitrateKbps: Int? = null,          // Vorbis公称値やLAMEターゲット値がある場合
    val sampleRateHz: Int?,                       // Hz (UIで kHz 表示変換)
    val originalInputSampleRateHz: Int? = null,   // Opusの元サンプリング周波数 (OpusHead記録値)
    val sampleRateProvenance: ValueProvenance,
    val bitDepth: Int?,                           // 16 / 24 / 32 bit (非可逆圧縮でビット深度概念がない場合は null)
    val bitDepthNote: String,                     // 例: "24 bit (STREAMINFO実測)" / "非可逆圧縮のため固定ビット深度なし（デコード時PCM出力）"
    val channelCount: Int?,                       // 1 / 2 / 6 / 8
    val channelLayoutLabel: String,               // Mono / Stereo / Joint Stereo / 5.1ch 等
    val durationMs: Long?,                        // 再生時間 (ms)
    val totalSamples: Long? = null,               // 総サンプル数（FLAC/WAV/Ogg等で取得できた場合）
    val fileSizeBytes: Long?,                     // ファイル全体サイズ
    val audioDataBytes: Long?,                    // タグ・コンテナヘッダーを除いた純粋な音声ストリームサイズ
    val metadataOverheadBytes: Long? = null,      // ID3v2 / VorbisComment / MP4 moov 等のサイズ
    val compressionRatioPercent: Double? = null,  // 非圧縮PCM比のサイズ比率 (%)
    val pathOrUri: String,                        // ファイルパスまたは Content URI / Stream URL
    val encoderOrToolInfo: String? = null,        // LAME3.100 / Lavf / Vorbis vendor string 等
    val streamMd5Hex: String? = null,             // FLAC STREAMINFO 128-bit MD5
    val embeddedMetadata: Map<String, String> = emptyMap(),
    val decoderRuntimeInfo: String? = null,       // Media3 ExoPlayer 実デコーダー名・入力Format
    val parseWarnings: List<String> = emptyList()
) {
    val isHiResSource: Boolean
        get() {
            val sr = sampleRateHz ?: 0
            val bd = bitDepth ?: 0
            val lossless = losslessClassification == LosslessClassification.COMPRESSED_LOSSLESS ||
                losslessClassification == LosslessClassification.UNCOMPRESSED_LOSSLESS
            return lossless && (sr > 48_000 || bd > 16)
        }

    val formattedSampleRateKhz: String
        get() = sampleRateHz?.let { hz ->
            if (hz % 1000 == 0) "${hz / 1000} kHz"
            else String.format(java.util.Locale.US, "%.1f kHz", hz / 1000.0)
        } ?: "取得不可"

    val formattedBitDepth: String
        get() = bitDepth?.let { "$it bit" } ?: "該当なし（非可逆）"

    val formattedBitrate: String
        get() = bitrateKbps?.let { kbps ->
            String.format(java.util.Locale.US, "%,d kbps", kbps)
        } ?: "取得不可"

    val losslessYesNo: String
        get() = when (losslessClassification) {
            LosslessClassification.UNCOMPRESSED_LOSSLESS,
            LosslessClassification.COMPRESSED_LOSSLESS -> "Yes"
            LosslessClassification.LOSSY -> "No"
            LosslessClassification.UNKNOWN -> "Unknown"
        }
}

data class FormatStatEntry(
    val formatName: String,
    val trackCount: Int,
    val totalBytes: Long,
    val percentageOfLibrary: Float,
    val losslessCount: Int,
    val hiResCount: Int
)

data class LibraryFormatStats(
    val totalTracks: Int,
    val totalBytes: Long,
    val losslessTracks: Int,
    val lossyTracks: Int,
    val hiResTracks: Int,
    val entries: List<FormatStatEntry>
)

data class AudioComparisonReport(
    val leftTitle: String,
    val rightTitle: String,
    val leftSpecs: AudioSourceSpecs,
    val rightSpecs: AudioSourceSpecs,
    val summaryPoints: List<String>
)
