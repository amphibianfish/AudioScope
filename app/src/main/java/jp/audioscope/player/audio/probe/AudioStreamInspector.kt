package jp.audioscope.player.audio.probe

import android.content.Context
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.Format
import jp.audioscope.player.model.AudioComparisonReport
import jp.audioscope.player.model.AudioSourceSpecs
import jp.audioscope.player.model.BitrateControlMode
import jp.audioscope.player.model.ByteUnitFormatter
import jp.audioscope.player.model.LosslessClassification
import jp.audioscope.player.model.TrackItem
import jp.audioscope.player.model.ValueProvenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import kotlin.math.roundToInt

/**
 * 1) BinaryAudioHeaderParser（バイナリストリーム直接解析）
 * 2) MediaExtractor / MediaMetadataRetriever（Android標準デコーダーメタデータ）
 * 3) Media3 ExoPlayer 実行時デコーダー Format
 * を統合し、推定値と実測値を混同しない正確な AudioSourceSpecs を構築する。
 */
class AudioStreamInspector(private val context: Context) {

    suspend fun inspectTrack(
        track: TrackItem,
        liveExoFormat: Format? = null
    ): AudioSourceSpecs = withContext(Dispatchers.IO) {
        val uri = track.contentUri
        val scheme = uri.scheme?.lowercase() ?: ""
        val isNetwork = track.isNetworkStream || scheme == "http" || scheme == "https"

        val warnings = mutableListOf<String>()
        var fileSizeBytes: Long? = track.sizeBytes.takeIf { it > 0L }

        // 1. ローカルURIから先頭512KBと末尾64KBを読み取りバイナリ解析
        var rawProbe = RawBinaryProbeResult()
        if (!isNetwork) {
            runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val declaredLen = afd.length.takeIf { it > 0L }
                    if (fileSizeBytes == null && declaredLen != null) {
                        fileSizeBytes = declaredLen
                    }
                    FileInputStream(afd.fileDescriptor).use { fis ->
                        val ch = fis.channel
                        val actualSize = ch.size().takeIf { it >= 0L }
                        if (actualSize != null) fileSizeBytes = actualSize

                        val headSize = (actualSize ?: 524_288L).coerceAtMost(524_288L).toInt()
                        val headBuf = ByteArray(headSize)
                        var readTotal = 0
                        while (readTotal < headSize) {
                            val r = fis.read(headBuf, readTotal, headSize - readTotal)
                            if (r <= 0) break
                            readTotal += r
                        }
                        val trimmedHead = if (readTotal == headSize) headBuf else headBuf.copyOf(readTotal)

                        var tailBuf = ByteArray(0)
                        if (actualSize != null && actualSize > 65_536L) {
                            val tailLen = 65_536
                            tailBuf = ByteArray(tailLen)
                            ch.position(actualSize - tailLen)
                            var tr = 0
                            while (tr < tailLen) {
                                val r = fis.read(tailBuf, tr, tailLen - tr)
                                if (r <= 0) break
                                tr += r
                            }
                            if (tr < tailLen) tailBuf = tailBuf.copyOf(tr)
                        }
                        rawProbe = BinaryAudioHeaderParser.parse(trimmedHead, tailBuf, fileSizeBytes)
                    }
                }
            }.onFailure {
                // ContentProvider がシーク非対応の場合は InputStream の先頭のみ読む
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { ins ->
                        val headBuf = ByteArray(262_144)
                        var readTotal = 0
                        while (readTotal < headBuf.size) {
                            val r = ins.read(headBuf, readTotal, headBuf.size - readTotal)
                            if (r <= 0) break
                            readTotal += r
                        }
                        rawProbe = BinaryAudioHeaderParser.parse(headBuf.copyOf(readTotal), ByteArray(0), fileSizeBytes)
                    }
                }.onFailure { err ->
                    warnings.add("ファイルストリーム直接読み取り失敗: ${err.javaClass.simpleName}")
                }
            }

            if (fileSizeBytes == null) {
                runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst() && !c.isNull(0)) fileSizeBytes = c.getLong(0)
                    }
                }
            }
        } else {
            warnings.add("ネットワークストリームのため、ファイル全体走査は行わずMedia3デコーダー実測情報を優先表示しています。")
        }

        warnings.addAll(rawProbe.warnings)

        // 2. Android MediaExtractor によるトラックフォーマット補完
        var extractorMime: String? = null
        var extractorSampleRate: Int? = null
        var extractorChannels: Int? = null
        var extractorBitrateBps: Int? = null
        var extractorPcmEncoding: Int? = null
        var extractorBitsPerSample: Int? = null
        var extractorDurationUs: Long? = null

        if (!isNetwork) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        extractorMime = mime
                        if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            extractorSampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            extractorChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            extractorBitrateBps = fmt.getInteger(MediaFormat.KEY_BIT_RATE)
                        }
                        if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            extractorPcmEncoding = fmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && fmt.containsKey("bits-per-sample")) {
                            extractorBitsPerSample = runCatching { fmt.getInteger("bits-per-sample") }.getOrNull()
                        }
                        if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                            extractorDurationUs = fmt.getLong(MediaFormat.KEY_DURATION)
                        }
                        break
                    }
                }
            } catch (_: Throwable) {
                // MediaExtractor 非対応コンテナでも rawProbe / Media3 で継続
            } finally {
                runCatching { extractor.release() }
            }
        }

        // 3. MediaMetadataRetriever による標準メタデータタグ補完
        val combinedMetadata = linkedMapOf<String, String>()
        var retrieverDurationMs: Long? = null
        var retrieverBitrateBps: Int? = null
        var retrieverBitsPerSample: Int? = null
        var retrieverSamplerate: Int? = null

        if (!isNetwork) {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(context, uri)
                fun extractMeta(key: Int, label: String) {
                    mmr.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }?.let {
                        combinedMetadata[label] = it
                    }
                }
                extractMeta(MediaMetadataRetriever.METADATA_KEY_TITLE, "Title")
                extractMeta(MediaMetadataRetriever.METADATA_KEY_ARTIST, "Artist")
                extractMeta(MediaMetadataRetriever.METADATA_KEY_ALBUM, "Album")
                extractMeta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, "AlbumArtist")
                extractMeta(MediaMetadataRetriever.METADATA_KEY_COMPOSER, "Composer")
                extractMeta(MediaMetadataRetriever.METADATA_KEY_GENRE, "Genre")
                extractMeta(MediaMetadataRetriever.METADATA_KEY_YEAR, "Year")
                extractMeta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER, "Track")
                extractMeta(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER, "Disc")
                extractMeta(MediaMetadataRetriever.METADATA_KEY_WRITER, "Writer")
                extractMeta(MediaMetadataRetriever.METADATA_KEY_AUTHOR, "Author")
                extractMeta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE, "OS MimeType")

                retrieverDurationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                retrieverBitrateBps = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retrieverBitsPerSample = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull()
                    retrieverSamplerate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                }
            } catch (_: Throwable) {
                // Ignore corrupt tag blocks safely
            } finally {
                runCatching { mmr.release() }
            }
        }

        // バイナリ解析で取得したVorbisComment/ID3/RIFFタグをマージ
        for ((k, v) in rawProbe.parsedTags) {
            if (!combinedMetadata.containsKey(k)) {
                combinedMetadata[k] = v
            } else if (combinedMetadata[k] != v) {
                combinedMetadata["Stream:$k"] = v
            }
        }

        // 4. ExoPlayer 実行時 Format の統合
        val exoSampleMime = liveExoFormat?.sampleMimeType
        val exoSampleRate = liveExoFormat?.sampleRate?.takeIf { it != Format.NO_VALUE && it > 0 }
        val exoChannels = liveExoFormat?.channelCount?.takeIf { it != Format.NO_VALUE && it > 0 }
        val exoAvgBitrateKbps = liveExoFormat?.averageBitrate?.takeIf { it != Format.NO_VALUE && it > 0 }?.let { it / 1000 }
        val exoBitrateKbps = liveExoFormat?.bitrate?.takeIf { it != Format.NO_VALUE && it > 0 }?.let { it / 1000 }
        val exoPcmBits = when (liveExoFormat?.pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT -> 32
            else -> null
        }

        // 最終フィールド決定（優先順位: バイナリ実測 > MediaExtractor > Media3 Format > MediaMetadataRetriever）
        val effectiveMime = extractorMime ?: exoSampleMime ?: track.mimeType
        val finalContainer = rawProbe.detectedFormat
            ?: liveExoFormat?.containerMimeType?.substringAfter('/')?.uppercase()
            ?: track.fileExtension.ifBlank { effectiveMime.substringAfter('/').uppercase() }

        val finalCodec = rawProbe.detectedCodec ?: mapMimeToCodecName(effectiveMime, liveExoFormat?.codecs)

        val finalLossless = if (rawProbe.lossless != LosslessClassification.UNKNOWN) {
            rawProbe.lossless
        } else {
            classifyLosslessByMime(effectiveMime, finalContainer)
        }

        val finalCompression = rawProbe.compressionSummary ?: when (finalLossless) {
            LosslessClassification.UNCOMPRESSED_LOSSLESS -> "非圧縮（PCM）"
            LosslessClassification.COMPRESSED_LOSSLESS -> "可逆圧縮（ロスレス）"
            LosslessClassification.LOSSY -> "非可逆圧縮（ロッシー）"
            LosslessClassification.UNKNOWN -> "不明"
        }

        val finalSampleRate = rawProbe.sampleRateHz ?: extractorSampleRate ?: exoSampleRate ?: retrieverSamplerate ?: track.mediaStoreSampleRateHz
        val sampleRateProv = when {
            rawProbe.sampleRateHz != null -> rawProbe.sampleRateProvenance
            extractorSampleRate != null || exoSampleRate != null -> ValueProvenance.DECODER_REPORTED
            finalSampleRate != null -> ValueProvenance.HEADER_DECLARED
            else -> ValueProvenance.UNAVAILABLE
        }

        val extractorEncodingBits = when (extractorPcmEncoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 8
            AudioFormat.ENCODING_PCM_16BIT -> 16
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32
            else -> null
        }

        val isLosslessSource = finalLossless == LosslessClassification.COMPRESSED_LOSSLESS ||
            finalLossless == LosslessClassification.UNCOMPRESSED_LOSSLESS

        val finalBitDepth: Int? = if (isLosslessSource) {
            rawProbe.bitDepth ?: extractorBitsPerSample ?: extractorEncodingBits ?: exoPcmBits ?: retrieverBitsPerSample ?: track.mediaStoreBitsPerSample
        } else {
            null // 非可逆音源に対してデコーダー出力の16bit等を元ファイルのビット深度として偽装しない
        }

        val finalBitDepthNote = when {
            rawProbe.bitDepth != null -> rawProbe.bitDepthNote
            isLosslessSource && finalBitDepth != null -> "$finalBitDepth bit (デコーダー/MediaFormat報告値)"
            !isLosslessSource -> "非可逆圧縮のため固定ビット深度なし（デコード時PCM出力）"
            else -> "取得不可"
        }

        val finalChannels = rawProbe.channelCount ?: extractorChannels ?: exoChannels
        val finalChannelLayout = rawProbe.channelLayout ?: BinaryAudioHeaderParser.formatChannelLayout(finalChannels)

        val finalDurationMs = rawProbe.durationMs
            ?: extractorDurationUs?.let { it / 1000L }
            ?: retrieverDurationMs
            ?: track.durationMs.takeIf { it > 0L }

        val fallbackBitrateKbps = (extractorBitrateBps ?: retrieverBitrateBps ?: track.mediaStoreBitrateBps)
            ?.takeIf { it > 0 }?.let { (it / 1000.0).roundToInt() }
            ?: exoAvgBitrateKbps
            ?: exoBitrateKbps

        val finalBitrateKbps = rawProbe.measuredBitrateKbps ?: fallbackBitrateKbps
        val finalBitrateProv = when {
            rawProbe.measuredBitrateKbps != null -> rawProbe.bitrateProvenance
            extractorBitrateBps != null || exoAvgBitrateKbps != null -> ValueProvenance.DECODER_REPORTED
            fallbackBitrateKbps != null -> ValueProvenance.HEADER_DECLARED
            else -> ValueProvenance.UNAVAILABLE
        }

        val finalBitrateMode = if (rawProbe.bitrateMode != BitrateControlMode.UNKNOWN) {
            rawProbe.bitrateMode
        } else if (fallbackBitrateKbps != null) {
            BitrateControlMode.NOMINAL_HEADER
        } else {
            BitrateControlMode.UNKNOWN
        }

        val compressionRatioPct = if (
            finalBitDepth != null && finalSampleRate != null && finalChannels != null &&
            finalDurationMs != null && finalDurationMs > 0L && (rawProbe.audioDataBytes ?: fileSizeBytes) != null
        ) {
            val uncompressedBytes = (finalSampleRate.toDouble() * finalChannels * (finalBitDepth / 8.0)) * (finalDurationMs / 1000.0)
            val actualBytes = (rawProbe.audioDataBytes ?: fileSizeBytes)!!.toDouble()
            if (uncompressedBytes > 0.0) (actualBytes / uncompressedBytes) * 100.0 else null
        } else null

        val decoderSummary = liveExoFormat?.let { fmt ->
            buildString {
                append("Media3 sampleMime=${fmt.sampleMimeType ?: "N/A"}")
                if (fmt.codecs != null) append(", codecs=${fmt.codecs}")
                if (fmt.sampleRate != Format.NO_VALUE) append(", sr=${fmt.sampleRate}Hz")
                if (fmt.channelCount != Format.NO_VALUE) append(", ch=${fmt.channelCount}")
            }
        }

        AudioSourceSpecs(
            containerFormat = finalContainer,
            audioCodec = finalCodec,
            compressionSummary = finalCompression,
            losslessClassification = finalLossless,
            bitrateKbps = finalBitrateKbps,
            bitrateProvenance = finalBitrateProv,
            bitrateMode = finalBitrateMode,
            nominalBitrateKbps = rawProbe.nominalBitrateKbps,
            sampleRateHz = finalSampleRate,
            originalInputSampleRateHz = rawProbe.originalInputSampleRateHz,
            sampleRateProvenance = sampleRateProv,
            bitDepth = finalBitDepth,
            bitDepthNote = finalBitDepthNote,
            channelCount = finalChannels,
            channelLayoutLabel = finalChannelLayout,
            durationMs = finalDurationMs,
            totalSamples = rawProbe.totalSamples,
            fileSizeBytes = fileSizeBytes,
            audioDataBytes = rawProbe.audioDataBytes,
            metadataOverheadBytes = rawProbe.metadataOverheadBytes,
            compressionRatioPercent = compressionRatioPct,
            pathOrUri = track.displayPathOrUri.ifBlank { uri.toString() },
            encoderOrToolInfo = rawProbe.encoderInfo ?: combinedMetadata["Encoder"] ?: combinedMetadata["Software"],
            streamMd5Hex = rawProbe.streamMd5Hex,
            embeddedMetadata = combinedMetadata,
            decoderRuntimeInfo = decoderSummary,
            parseWarnings = warnings.distinct()
        )
    }

    /**
     * FLAC と MP3 などの2ファイルの音質仕様を客観的数値で比較する。
     */
    fun compareTwoSpecs(
        leftTitle: String,
        left: AudioSourceSpecs,
        rightTitle: String,
        right: AudioSourceSpecs
    ): AudioComparisonReport {
        val points = mutableListOf<String>()
        points.add("圧縮性質: 「$leftTitle」は ${left.losslessClassification.labelJp}、「$rightTitle」は ${right.losslessClassification.labelJp} です。")

        if (left.sampleRateHz != null && right.sampleRateHz != null) {
            val leftNyquist = left.sampleRateHz / 2000.0
            val rightNyquist = right.sampleRateHz / 2000.0
            points.add(
                String.format(
                    java.util.Locale.US,
                    "帯域上限（ナイキスト周波数）: %.2f kHz vs %.2f kHz",
                    leftNyquist,
                    rightNyquist
                )
            )
        }
        if (left.bitrateKbps != null && right.bitrateKbps != null && right.bitrateKbps > 0) {
            val ratio = left.bitrateKbps.toDouble() / right.bitrateKbps.toDouble()
            points.add(
                String.format(
                    java.util.Locale.US,
                    "ビットレート比: %,d kbps (%s) vs %,d kbps (%s) [約 %.2f 倍]",
                    left.bitrateKbps,
                    left.bitrateMode.shortTag,
                    right.bitrateKbps,
                    right.bitrateMode.shortTag,
                    ratio
                )
            )
        }
        val leftSize = left.audioDataBytes ?: left.fileSizeBytes
        val rightSize = right.audioDataBytes ?: right.fileSizeBytes
        if (leftSize != null && rightSize != null && rightSize > 0L) {
            val diffBytes = leftSize - rightSize
            points.add(
                "音声データ量差: ${ByteUnitFormatter.formatBytes(leftSize)} vs ${ByteUnitFormatter.formatBytes(rightSize)} " +
                    "(差分 ${if (diffBytes >= 0) "+" else "-"}${ByteUnitFormatter.formatBytes(kotlin.math.abs(diffBytes))})"
            )
        }
        return AudioComparisonReport(
            leftTitle = leftTitle,
            rightTitle = rightTitle,
            leftSpecs = left,
            rightSpecs = right,
            summaryPoints = points
        )
    }

    /**
     * 音質情報のテキスト共有用フォーマッター
     */
    fun formatReportForShare(
        trackTitle: String,
        trackArtist: String,
        source: AudioSourceSpecs,
        outputSummary: String,
        networkSummary: String
    ): String = buildString {
        appendLine("=== AudioScope 音質診断レポート ===")
        appendLine("曲名: $trackTitle")
        appendLine("アーティスト: $trackArtist")
        appendLine()
        appendLine("[SOURCE: 元ファイル仕様]")
        appendLine("- ファイル形式: ${source.containerFormat}")
        appendLine("- 音声コーデック: ${source.audioCodec}")
        appendLine("- 圧縮方式: ${source.compressionSummary}")
        appendLine("- Lossless: ${source.losslessYesNo}")
        appendLine("- サンプリング周波数: ${source.formattedSampleRateKhz} (${source.sampleRateProvenance.labelJp})")
        appendLine("- ビット深度: ${source.bitDepthNote}")
        appendLine("- チャンネル: ${source.channelLayoutLabel}")
        appendLine("- ビットレート: ${source.formattedBitrate} [${source.bitrateMode.labelJp} / ${source.bitrateProvenance.labelJp}]")
        source.fileSizeBytes?.let { appendLine("- ファイルサイズ: ${ByteUnitFormatter.formatBytes(it)}") }
        source.audioDataBytes?.let { appendLine("- 音声データサイズ: ${ByteUnitFormatter.formatBytes(it)}") }
        appendLine("- URI/パス: ${source.pathOrUri}")
        appendLine()
        appendLine("[OUTPUT: 出力経路]")
        appendLine(outputSummary)
        appendLine()
        appendLine("[NETWORK: 通信量モニター]")
        appendLine(networkSummary)
    }

    private fun mapMimeToCodecName(mime: String, codecs: String?): String = when (mime.lowercase()) {
        "audio/flac", "application/x-flac" -> "FLAC"
        "audio/mpeg" -> "MPEG Audio Layer III (MP3)"
        "audio/raw", "audio/wav", "audio/x-wav" -> "Linear PCM"
        "audio/mp4a-latm", "audio/aac" -> codecs?.let { "AAC ($it)" } ?: "AAC"
        "audio/alac" -> "ALAC (Apple Lossless)"
        "audio/vorbis", "audio/ogg" -> "Ogg Vorbis"
        "audio/opus" -> "Opus"
        else -> codecs ?: mime
    }

    private fun classifyLosslessByMime(mime: String, container: String): LosslessClassification {
        val m = mime.lowercase()
        val c = container.uppercase()
        return when {
            m == "audio/raw" || c.startsWith("WAV") -> LosslessClassification.UNCOMPRESSED_LOSSLESS
            m.contains("flac") || m.contains("alac") || c == "FLAC" || c == "ALAC" -> LosslessClassification.COMPRESSED_LOSSLESS
            m.contains("mpeg") || m.contains("mp4a") || m.contains("aac") || m.contains("vorbis") || m.contains("opus") ->
                LosslessClassification.LOSSY
            else -> LosslessClassification.UNKNOWN
        }
    }
}
