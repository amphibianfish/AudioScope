package jp.audioscope.player.audio.probe

import jp.audioscope.player.model.BitrateControlMode
import jp.audioscope.player.model.LosslessClassification
import jp.audioscope.player.model.ValueProvenance
import kotlin.math.roundToInt

/**
 * OSのMediaStoreや不完全なタグに依存せず、音声ファイルの先頭・末尾バイナリストリームを直接解析する純粋Kotlinパーサー。
 * 不正・破損・ゼロバイト・途中で切断されたファイルに対しても例外を外部へ漏らさず安全にフォールバックする。
 */
data class RawBinaryProbeResult(
    val detectedFormat: String? = null,
    val detectedCodec: String? = null,
    val compressionSummary: String? = null,
    val lossless: LosslessClassification = LosslessClassification.UNKNOWN,
    val sampleRateHz: Int? = null,
    val originalInputSampleRateHz: Int? = null,
    val sampleRateProvenance: ValueProvenance = ValueProvenance.UNAVAILABLE,
    val bitDepth: Int? = null,
    val bitDepthNote: String = "取得不可",
    val channelCount: Int? = null,
    val channelLayout: String? = null,
    val totalSamples: Long? = null,
    val durationMs: Long? = null,
    val audioDataBytes: Long? = null,
    val metadataOverheadBytes: Long? = null,
    val measuredBitrateKbps: Int? = null,
    val nominalBitrateKbps: Int? = null,
    val bitrateProvenance: ValueProvenance = ValueProvenance.UNAVAILABLE,
    val bitrateMode: BitrateControlMode = BitrateControlMode.UNKNOWN,
    val encoderInfo: String? = null,
    val streamMd5Hex: String? = null,
    val parsedTags: Map<String, String> = emptyMap(),
    val warnings: List<String> = emptyList()
)

object BinaryAudioHeaderParser {

    fun parse(
        headBytes: ByteArray,
        tailBytes: ByteArray = ByteArray(0),
        totalFileSize: Long? = null
    ): RawBinaryProbeResult {
        if (headBytes.size < 4) {
            return RawBinaryProbeResult(
                warnings = listOf("ファイルサイズが4バイト未満のためバイナリヘッダーを解析できません。")
            )
        }
        return try {
            when {
                isFlac(headBytes) -> parseFlac(headBytes, totalFileSize)
                isRiffWave(headBytes) -> parseWave(headBytes, totalFileSize)
                isOgg(headBytes) -> parseOgg(headBytes, tailBytes, totalFileSize)
                isIsoBmff(headBytes) -> parseMp4M4a(headBytes, tailBytes, totalFileSize)
                isAdtsAac(headBytes, skipId3v2Offset(headBytes)) ->
                    parseAdtsAac(headBytes, skipId3v2Offset(headBytes), tailBytes, totalFileSize)
                isMp3OrId3(headBytes) -> parseMp3(headBytes, tailBytes, totalFileSize)
                else -> RawBinaryProbeResult(
                    warnings = listOf("既知のコンテナ署名（FLAC/WAV/OGG/MP4/MP3/ADTS）と一致しませんでした。")
                )
            }
        } catch (t: Throwable) {
            RawBinaryProbeResult(
                warnings = listOf("ストリーム解析中に破損データを検知し安全に中断しました: ${t.javaClass.simpleName}")
            )
        }
    }

    // =========================================================================
    // 1. FLAC ("fLaC" + Metadata Blocks + STREAMINFO + VORBIS_COMMENT)
    // =========================================================================
    private fun isFlac(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 'f'.code.toByte() && b[1] == 'L'.code.toByte() &&
            b[2] == 'a'.code.toByte() && b[3] == 'C'.code.toByte()

    private fun parseFlac(b: ByteArray, totalFileSize: Long?): RawBinaryProbeResult {
        val warnings = mutableListOf<String>()
        val tags = linkedMapOf<String, String>()
        var offset = 4
        var sampleRate: Int? = null
        var channels: Int? = null
        var bitsPerSample: Int? = null
        var totalSamples: Long? = null
        var md5Hex: String? = null
        var vendor: String? = null
        var reachedLastBlock = false

        while (offset + 4 <= b.size) {
            val headerByte = b[offset].toInt() and 0xFF
            val isLast = (headerByte and 0x80) != 0
            val blockType = headerByte and 0x7F
            val blockLen = readUInt24BE(b, offset + 1)
            offset += 4

            if (blockType == 0 && blockLen >= 34 && offset + 34 <= b.size) {
                val b10 = b[offset + 10].toLong() and 0xFF
                val b11 = b[offset + 11].toLong() and 0xFF
                val b12 = b[offset + 12].toLong() and 0xFF
                val b13 = b[offset + 13].toLong() and 0xFF
                val b14 = b[offset + 14].toLong() and 0xFF
                val b15 = b[offset + 15].toLong() and 0xFF
                val b16 = b[offset + 16].toLong() and 0xFF
                val b17 = b[offset + 17].toLong() and 0xFF

                sampleRate = ((b10 shl 12) or (b11 shl 4) or (b12 ushr 4)).toInt()
                channels = (((b12 ushr 1) and 0x07) + 1).toInt()
                bitsPerSample = ((((b12 and 0x01) shl 4) or (b13 ushr 4)) + 1).toInt()
                totalSamples = ((b13 and 0x0F) shl 32) or (b14 shl 24) or (b15 shl 16) or (b16 shl 8) or b17

                val md5Bytes = b.copyOfRange(offset + 18, offset + 34)
                if (md5Bytes.any { it.toInt() != 0 }) {
                    md5Hex = md5Bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                }
            } else if (blockType == 4 && offset + blockLen <= b.size) {
                parseVorbisCommentBlock(b, offset, blockLen, tags)?.let { vendor = it }
            } else if (blockType == 127) {
                warnings.add("不正なFLACメタデータブロック型 (127) を検出しました。")
                break
            }

            if (offset + blockLen > b.size) {
                offset += blockLen
                reachedLastBlock = isLast
                break
            }
            offset += blockLen
            if (isLast) {
                reachedLastBlock = true
                break
            }
        }

        val audioOffset = if (reachedLastBlock) offset.toLong() else null
        val audioBytes = if (totalFileSize != null && audioOffset != null && totalFileSize > audioOffset) {
            totalFileSize - audioOffset
        } else null
        val overheadBytes = if (totalFileSize != null && audioBytes != null) {
            totalFileSize - audioBytes
        } else null

        val durationMs = if (sampleRate != null && sampleRate > 0 && totalSamples != null && totalSamples > 0) {
            (totalSamples * 1000L) / sampleRate.toLong()
        } else null

        val measuredKbps = if (audioBytes != null && durationMs != null && durationMs > 0) {
            ((audioBytes * 8.0) / durationMs.toDouble()).roundToInt()
        } else if (totalFileSize != null && durationMs != null && durationMs > 0) {
            warnings.add("埋め込み画像が大きいためファイル全体サイズから平均ビットレートを算出しました。")
            ((totalFileSize * 8.0) / durationMs.toDouble()).roundToInt()
        } else null

        return RawBinaryProbeResult(
            detectedFormat = "FLAC",
            detectedCodec = "FLAC (Free Lossless Audio Codec)",
            compressionSummary = "可逆圧縮（ロスレス・エントロピー符号化）",
            lossless = LosslessClassification.COMPRESSED_LOSSLESS,
            sampleRateHz = sampleRate,
            sampleRateProvenance = if (sampleRate != null) ValueProvenance.STREAM_MEASURED else ValueProvenance.UNAVAILABLE,
            bitDepth = bitsPerSample,
            bitDepthNote = bitsPerSample?.let { "$it bit (FLAC STREAMINFO実測)" } ?: "取得不可",
            channelCount = channels,
            channelLayout = formatChannelLayout(channels),
            totalSamples = totalSamples,
            durationMs = durationMs,
            audioDataBytes = audioBytes,
            metadataOverheadBytes = overheadBytes,
            measuredBitrateKbps = measuredKbps,
            bitrateProvenance = if (measuredKbps != null) ValueProvenance.STREAM_MEASURED else ValueProvenance.UNAVAILABLE,
            bitrateMode = BitrateControlMode.VBR_LOSSLESS,
            encoderInfo = vendor,
            streamMd5Hex = md5Hex,
            parsedTags = tags,
            warnings = warnings
        )
    }

    // =========================================================================
    // 2. WAV / RIFF / RF64 / WAVEFORMATEXTENSIBLE
    // =========================================================================
    private fun isRiffWave(b: ByteArray): Boolean {
        if (b.size < 12) return false
        val magic = ascii(b, 0, 4)
        val form = ascii(b, 8, 4)
        return (magic == "RIFF" || magic == "RF64") && form == "WAVE"
    }

    private fun parseWave(b: ByteArray, totalFileSize: Long?): RawBinaryProbeResult {
        val warnings = mutableListOf<String>()
        val tags = linkedMapOf<String, String>()
        val isRf64 = ascii(b, 0, 4) == "RF64"
        var offset = 12
        var formatTag = 0
        var channels: Int? = null
        var sampleRate: Int? = null
        var byteRate: Long? = null
        var bitsPerSample: Int? = null
        var validBitsPerSample: Int? = null
        var extensibleSubFormat: Int? = null
        var dataChunkBytes: Long? = null
        var rf64DataSize: Long? = null

        while (offset + 8 <= b.size) {
            val chunkId = ascii(b, offset, 4)
            val chunkSize = readUInt32LE(b, offset + 4)
            val payloadStart = offset + 8

            when (chunkId) {
                "ds64" -> {
                    if (payloadStart + 16 <= b.size) {
                        rf64DataSize = readInt64LE(b, payloadStart + 8)
                    }
                }
                "fmt " -> {
                    if (payloadStart + 16 <= b.size) {
                        formatTag = readUInt16LE(b, payloadStart)
                        channels = readUInt16LE(b, payloadStart + 2)
                        sampleRate = readUInt32LE(b, payloadStart + 4).toInt()
                        byteRate = readUInt32LE(b, payloadStart + 8)
                        bitsPerSample = readUInt16LE(b, payloadStart + 14)

                        if (formatTag == 0xFFFE && chunkSize >= 40 && payloadStart + 40 <= b.size) {
                            validBitsPerSample = readUInt16LE(b, payloadStart + 18).takeIf { it > 0 }
                            extensibleSubFormat = readUInt16LE(b, payloadStart + 24)
                        }
                    }
                }
                "LIST" -> {
                    if (payloadStart + 4 <= b.size && chunkSize >= 4) {
                        val listEnd = (payloadStart + chunkSize.toInt()).coerceAtMost(b.size)
                        if (ascii(b, payloadStart, 4) == "INFO") {
                            parseRiffInfoTags(b, payloadStart + 4, listEnd, tags)
                        }
                    }
                }
                "data" -> {
                    dataChunkBytes = if (chunkSize == 0xFFFFFFFFL && rf64DataSize != null) {
                        rf64DataSize
                    } else if (totalFileSize != null && chunkSize > totalFileSize) {
                        warnings.add("WAV dataチャンク宣言値がファイル実サイズを超過していたため実サイズで補正しました。")
                        (totalFileSize - payloadStart.toLong()).coerceAtLeast(0L)
                    } else {
                        chunkSize
                    }
                    break
                }
            }

            val paddedSize = chunkSize + (chunkSize and 1L)
            val next = payloadStart.toLong() + paddedSize
            if (next <= offset || next > Int.MAX_VALUE) break
            offset = next.toInt()
        }

        val effectiveFormat = extensibleSubFormat ?: formatTag
        val effectiveBitDepth = validBitsPerSample ?: bitsPerSample

        val (codecName, compressionDesc, losslessClass) = when (effectiveFormat) {
            0x0001 -> Triple(
                if (formatTag == 0xFFFE) "Linear PCM (WAVEFORMATEXTENSIBLE)" else "Linear PCM",
                "非圧縮（リニアPCM）",
                LosslessClassification.UNCOMPRESSED_LOSSLESS
            )
            0x0003 -> Triple(
                "IEEE 754 Float PCM",
                "非圧縮（浮動小数点PCM）",
                LosslessClassification.UNCOMPRESSED_LOSSLESS
            )
            0x0006 -> Triple("ITU-T G.711 A-law", "非可逆対数圧伸 (A-law)", LosslessClassification.LOSSY)
            0x0007 -> Triple("ITU-T G.711 μ-law", "非可逆対数圧伸 (μ-law)", LosslessClassification.LOSSY)
            0x0002, 0x0011 -> Triple("ADPCM", "非可逆適応差分PCM (ADPCM)", LosslessClassification.LOSSY)
            else -> Triple("WAV Format 0x${effectiveFormat.toString(16).uppercase()}", "WAV格納ストリーム", LosslessClassification.UNKNOWN)
        }

        val bytesPerFrame = if (channels != null && bitsPerSample != null && channels > 0 && bitsPerSample > 0) {
            channels * ((bitsPerSample + 7) / 8)
        } else 0
        val totalSamples = if (dataChunkBytes != null && bytesPerFrame > 0) {
            dataChunkBytes / bytesPerFrame.toLong()
        } else null
        val durationMs = if (totalSamples != null && sampleRate != null && sampleRate > 0) {
            (totalSamples * 1000L) / sampleRate.toLong()
        } else if (dataChunkBytes != null && byteRate != null && byteRate > 0) {
            (dataChunkBytes * 1000L) / byteRate
        } else null

        val exactKbps = if (sampleRate != null && channels != null && bitsPerSample != null) {
            ((sampleRate.toLong() * channels.toLong() * bitsPerSample.toLong()) / 1000.0).roundToInt()
        } else byteRate?.let { ((it * 8.0) / 1000.0).roundToInt() }

        val overhead = if (totalFileSize != null && dataChunkBytes != null && totalFileSize >= dataChunkBytes) {
            totalFileSize - dataChunkBytes
        } else null

        return RawBinaryProbeResult(
            detectedFormat = if (isRf64) "WAV (RF64)" else "WAV (RIFF)",
            detectedCodec = codecName,
            compressionSummary = compressionDesc,
            lossless = losslessClass,
            sampleRateHz = sampleRate,
            sampleRateProvenance = if (sampleRate != null) ValueProvenance.STREAM_MEASURED else ValueProvenance.UNAVAILABLE,
            bitDepth = effectiveBitDepth,
            bitDepthNote = if (validBitsPerSample != null && validBitsPerSample != bitsPerSample) {
                "$validBitsPerSample bit有効 / ${bitsPerSample}bitコンテナ (EXTENSIBLE実測)"
            } else {
                effectiveBitDepth?.let { "$it bit (fmtチャンク実測)" } ?: "取得不可"
            },
            channelCount = channels,
            channelLayout = formatChannelLayout(channels),
            totalSamples = totalSamples,
            durationMs = durationMs,
            audioDataBytes = dataChunkBytes,
            metadataOverheadBytes = overhead,
            measuredBitrateKbps = exactKbps,
            bitrateProvenance = if (exactKbps != null) ValueProvenance.STREAM_MEASURED else ValueProvenance.UNAVAILABLE,
            bitrateMode = BitrateControlMode.UNCOMPRESSED_CBR,
            parsedTags = tags,
            warnings = warnings
        )
    }

    private fun parseRiffInfoTags(b: ByteArray, start: Int, end: Int, out: MutableMap<String, String>) {
        var pos = start
        while (pos + 8 <= end) {
            val id = ascii(b, pos, 4)
            val sz = readUInt32LE(b, pos + 4).toInt()
            val valStart = pos + 8
            if (sz < 0 || valStart + sz > end) break
            val raw = String(b, valStart, sz, Charsets.UTF_8).trimEnd('\u0000', ' ')
            if (raw.isNotBlank()) {
                val key = when (id) {
                    "INAM" -> "Title"
                    "IART" -> "Artist"
                    "IPRD" -> "Album"
                    "ICRD" -> "Date"
                    "IGNR" -> "Genre"
                    "ISFT" -> "Software"
                    else -> "RIFF:$id"
                }
                out[key] = raw
            }
            pos = valStart + sz + (sz and 1)
        }
    }

    // =========================================================================
    // 3. MP3 (ID3v2 + MPEG Frame Sync + Xing/Info/LAME/VBRI + Multi-Frame Scan)
    // =========================================================================
    private fun skipId3v2Offset(b: ByteArray): Int {
        if (b.size < 10) return 0
        if (b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()) {
            val flags = b[5].toInt() and 0xFF
            val size = ((b[6].toInt() and 0x7F) shl 21) or
                ((b[7].toInt() and 0x7F) shl 14) or
                ((b[8].toInt() and 0x7F) shl 7) or
                (b[9].toInt() and 0x7F)
            val footer = if ((flags and 0x10) != 0) 10 else 0
            return 10 + size + footer
        }
        return 0
    }

    private fun detectTrailingTagSize(tail: ByteArray): Int {
        if (tail.isEmpty()) return 0
        var trailing = 0
        if (tail.size >= 128) {
            val id3v1Pos = tail.size - 128
            if (ascii(tail, id3v1Pos, 3) == "TAG") {
                trailing += 128
            }
        }
        val searchEnd = tail.size - trailing
        if (searchEnd >= 32) {
            val apeFooterPos = searchEnd - 32
            if (ascii(tail, apeFooterPos, 8) == "APETAGEX") {
                val tagSize = readUInt32LE(tail, apeFooterPos + 12).toInt()
                val flags = readUInt32LE(tail, apeFooterPos + 20)
                val hasHeader = (flags and (1L shl 31)) != 0L
                trailing += tagSize + if (hasHeader) 32 else 0
            }
        }
        return trailing
    }

    private fun isMp3OrId3(b: ByteArray): Boolean {
        val start = skipId3v2Offset(b)
        if (start > 0 && start >= b.size) return true
        return findFirstMpegFrame(b, start.coerceAtMost(b.size)) != null
    }

    private data class MpegFrameHeader(
        val offset: Int,
        val versionLabel: String,
        val isMpeg1: Boolean,
        val isMpeg25: Boolean,
        val layer: Int,
        val bitrateKbps: Int,
        val sampleRateHz: Int,
        val padding: Int,
        val channelModeBits: Int,
        val channelCount: Int,
        val channelModeLabel: String,
        val samplesPerFrame: Int,
        val frameLengthBytes: Int
    )

    private val MPEG1_L3_BITRATES = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val MPEG2_L3_BITRATES = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val MPEG1_L2_BITRATES = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
    private val MPEG1_L1_BITRATES = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)

    private fun parseMpegHeaderAt(b: ByteArray, pos: Int): MpegFrameHeader? {
        if (pos + 4 > b.size) return null
        val b0 = b[pos].toInt() and 0xFF
        val b1 = b[pos + 1].toInt() and 0xFF
        val b2 = b[pos + 2].toInt() and 0xFF
        val b3 = b[pos + 3].toInt() and 0xFF

        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        val versionBits = (b1 ushr 3) and 0x03
        val layerBits = (b1 ushr 1) and 0x03
        if (versionBits == 1 || layerBits == 0) return null
        val layer = 4 - layerBits
        if (layer !in 1..3) return null

        val bitrateIdx = (b2 ushr 4) and 0x0F
        val srIdx = (b2 ushr 2) and 0x03
        val padding = (b2 ushr 1) and 0x01
        if (bitrateIdx == 0 || bitrateIdx == 15 || srIdx == 3) return null

        val isMpeg1 = versionBits == 3
        val isMpeg25 = versionBits == 0
        val versionLabel = when (versionBits) {
            3 -> "MPEG-1"
            2 -> "MPEG-2"
            0 -> "MPEG-2.5"
            else -> "MPEG"
        }

        val baseSr = when (srIdx) {
            0 -> 44100
            1 -> 48000
            2 -> 32000
            else -> return null
        }
        val sampleRate = when (versionBits) {
            3 -> baseSr
            2 -> baseSr / 2
            0 -> baseSr / 4
            else -> return null
        }

        val bitrateKbps = when {
            isMpeg1 && layer == 3 -> MPEG1_L3_BITRATES[bitrateIdx]
            !isMpeg1 && layer == 3 -> MPEG2_L3_BITRATES[bitrateIdx]
            isMpeg1 && layer == 2 -> MPEG1_L2_BITRATES[bitrateIdx]
            isMpeg1 && layer == 1 -> MPEG1_L1_BITRATES[bitrateIdx]
            else -> MPEG2_L3_BITRATES[bitrateIdx]
        }
        if (bitrateKbps <= 0) return null

        val samplesPerFrame = when (layer) {
            1 -> 384
            2 -> 1152
            else -> if (isMpeg1) 1152 else 576
        }

        val frameLength = if (layer == 1) {
            ((12 * bitrateKbps * 1000 / sampleRate) + padding) * 4
        } else {
            val slotFactor = if (layer == 3 && !isMpeg1) 72 else 144
            (slotFactor * bitrateKbps * 1000 / sampleRate) + padding
        }
        if (frameLength < 24) return null

        val chBits = (b3 ushr 6) and 0x03
        val chCount = if (chBits == 3) 1 else 2
        val chLabel = when (chBits) {
            0 -> "Stereo"
            1 -> "Joint Stereo"
            2 -> "Dual Channel"
            else -> "Mono"
        }

        return MpegFrameHeader(
            offset = pos,
            versionLabel = versionLabel,
            isMpeg1 = isMpeg1,
            isMpeg25 = isMpeg25,
            layer = layer,
            bitrateKbps = bitrateKbps,
            sampleRateHz = sampleRate,
            padding = padding,
            channelModeBits = chBits,
            channelCount = chCount,
            channelModeLabel = chLabel,
            samplesPerFrame = samplesPerFrame,
            frameLengthBytes = frameLength
        )
    }

    private fun findFirstMpegFrame(b: ByteArray, startOffset: Int): MpegFrameHeader? {
        val maxSearch = (startOffset + 131072).coerceAtMost(b.size - 4)
        var i = startOffset.coerceAtLeast(0)
        while (i <= maxSearch) {
            if ((b[i].toInt() and 0xFF) == 0xFF && (b[i + 1].toInt() and 0xE0) == 0xE0) {
                val candidate = parseMpegHeaderAt(b, i)
                if (candidate != null) {
                    val nextPos = i + candidate.frameLengthBytes
                    if (nextPos + 4 > b.size) return candidate
                    val nextCandidate = parseMpegHeaderAt(b, nextPos)
                    if (nextCandidate != null &&
                        nextCandidate.sampleRateHz == candidate.sampleRateHz &&
                        nextCandidate.layer == candidate.layer
                    ) {
                        return candidate
                    }
                }
            }
            i++
        }
        return null
    }

    private fun parseMp3(b: ByteArray, tail: ByteArray, totalFileSize: Long?): RawBinaryProbeResult {
        val warnings = mutableListOf<String>()
        val tags = linkedMapOf<String, String>()
        val id3End = skipId3v2Offset(b)
        if (id3End > 0) {
            parseId3v2BasicFrames(b, id3End.coerceAtMost(b.size), tags)
        }
        val trailingBytes = detectTrailingTagSize(tail)

        if (id3End >= b.size) {
            warnings.add("埋め込みアートワーク等のID3v2タグが大きく、先頭バッファを超えるためMediaExtractor結果と併用します。")
            return RawBinaryProbeResult(
                detectedFormat = "MP3",
                detectedCodec = "MPEG Audio Layer III",
                compressionSummary = "非可逆圧縮 (MP3)",
                lossless = LosslessClassification.LOSSY,
                bitDepth = null,
                bitDepthNote = "非可逆圧縮（周波数領域量子化のため固定ビット深度なし）",
                metadataOverheadBytes = (id3End + trailingBytes).toLong(),
                audioDataBytes = totalFileSize?.let { (it - id3End - trailingBytes).coerceAtLeast(0L) },
                parsedTags = tags,
                warnings = warnings
            )
        }

        val firstFrame = findFirstMpegFrame(b, id3End)
            ?: return RawBinaryProbeResult(
                detectedFormat = "MP3",
                detectedCodec = "MPEG Audio",
                lossless = LosslessClassification.LOSSY,
                warnings = listOf("有効なMPEGオーディオフレーム同期ワードが見つかりませんでした。")
            )

        val audioStart = firstFrame.offset
        val audioBytesFromFile = totalFileSize?.let { (it - audioStart - trailingBytes).coerceAtLeast(0L) }
        val overheadBytes = totalFileSize?.let { (audioStart + trailingBytes).toLong().coerceAtMost(it) }

        val sideInfoSize = if (firstFrame.isMpeg1) {
            if (firstFrame.channelCount == 1) 17 else 32
        } else {
            if (firstFrame.channelCount == 1) 9 else 17
        }
        val xingPos = firstFrame.offset + 4 + sideInfoSize
        var xingFrames: Long? = null
        var xingBytes: Long? = null
        var headerTagType: String? = null
        var encoderTag: String? = null
        var lameVbrMethod: Int? = null

        if (xingPos + 16 <= b.size) {
            val magic = ascii(b, xingPos, 4)
            if (magic == "Xing" || magic == "Info") {
                headerTagType = magic
                val flags = readUInt32BE(b, xingPos + 4).toInt()
                var cursor = xingPos + 8
                if ((flags and 0x01) != 0 && cursor + 4 <= b.size) {
                    xingFrames = readUInt32BE(b, cursor)
                    cursor += 4
                }
                if ((flags and 0x02) != 0 && cursor + 4 <= b.size) {
                    xingBytes = readUInt32BE(b, cursor)
                    cursor += 4
                }
                if ((flags and 0x04) != 0) cursor += 100
                if ((flags and 0x08) != 0) cursor += 4

                if (cursor + 10 <= b.size) {
                    val encRaw = ascii(b, cursor, 9).trim()
                    if (encRaw.isNotEmpty() && encRaw.all { it.code in 32..126 }) {
                        encoderTag = encRaw
                        lameVbrMethod = b[cursor + 9].toInt() and 0x0F
                    }
                }
            }
        }

        val vbriPos = firstFrame.offset + 36
        if (headerTagType == null && vbriPos + 26 <= b.size && ascii(b, vbriPos, 4) == "VBRI") {
            headerTagType = "VBRI"
            xingBytes = readUInt32BE(b, vbriPos + 10)
            xingFrames = readUInt32BE(b, vbriPos + 14)
        }

        val frameBitrates = mutableListOf<Int>()
        var scanPos = firstFrame.offset
        var scannedCount = 0
        while (scannedCount < 120 && scanPos + 4 <= b.size) {
            val fh = parseMpegHeaderAt(b, scanPos) ?: break
            if (!(scannedCount == 0 && headerTagType != null)) {
                frameBitrates.add(fh.bitrateKbps)
            }
            scanPos += fh.frameLengthBytes
            scannedCount++
        }
        val distinctBitrates = frameBitrates.distinct()

        val bitrateMode = when {
            lameVbrMethod in listOf(1, 8) || headerTagType == "Info" -> BitrateControlMode.CBR_HEADER
            lameVbrMethod in listOf(2, 9) -> BitrateControlMode.ABR_HEADER
            lameVbrMethod in listOf(3, 4, 5, 6) || headerTagType == "Xing" || headerTagType == "VBRI" ->
                BitrateControlMode.VBR_XING_VBRI
            distinctBitrates.size > 1 -> BitrateControlMode.VBR_FRAME_DETECTED
            distinctBitrates.size == 1 && frameBitrates.size >= 3 -> BitrateControlMode.CBR_VERIFIED
            else -> BitrateControlMode.NOMINAL_HEADER
        }

        val effectiveAudioBytes = xingBytes ?: audioBytesFromFile
        val totalSamples = xingFrames?.let { it * firstFrame.samplesPerFrame.toLong() }
        val durationMs = if (totalSamples != null && firstFrame.sampleRateHz > 0) {
            (totalSamples * 1000L) / firstFrame.sampleRateHz.toLong()
        } else if (
            (bitrateMode == BitrateControlMode.CBR_VERIFIED || bitrateMode == BitrateControlMode.CBR_HEADER) &&
            effectiveAudioBytes != null && firstFrame.bitrateKbps > 0
        ) {
            val cbrKbps = distinctBitrates.firstOrNull() ?: firstFrame.bitrateKbps
            (effectiveAudioBytes * 8L) / cbrKbps.toLong()
        } else null

        val measuredBitrateKbps = when {
            bitrateMode == BitrateControlMode.CBR_VERIFIED || bitrateMode == BitrateControlMode.CBR_HEADER ->
                distinctBitrates.firstOrNull() ?: firstFrame.bitrateKbps
            effectiveAudioBytes != null && durationMs != null && durationMs > 0 ->
                ((effectiveAudioBytes * 8.0) / durationMs.toDouble()).roundToInt()
            distinctBitrates.size > 1 ->
                frameBitrates.average().roundToInt().also {
                    warnings.add("Xingヘッダー非搭載のVBRファイルのため、先頭${frameBitrates.size}フレームの実測平均を表示しています。")
                }
            else -> firstFrame.bitrateKbps
        }

        val layerRoman = when (firstFrame.layer) {
            1 -> "Layer I"
            2 -> "Layer II"
            else -> "Layer III"
        }

        return RawBinaryProbeResult(
            detectedFormat = "MP3",
            detectedCodec = "${firstFrame.versionLabel} Audio $layerRoman",
            compressionSummary = "非可逆圧縮（聴覚心理モデル・MDCT量子化）",
            lossless = LosslessClassification.LOSSY,
            sampleRateHz = firstFrame.sampleRateHz,
            sampleRateProvenance = ValueProvenance.STREAM_MEASURED,
            bitDepth = null,
            bitDepthNote = "非可逆圧縮のため固定ビット深度なし（デコード時に16/24/Float PCMへ展開）",
            channelCount = firstFrame.channelCount,
            channelLayout = firstFrame.channelModeLabel,
            totalSamples = totalSamples,
            durationMs = durationMs,
            audioDataBytes = effectiveAudioBytes,
            metadataOverheadBytes = overheadBytes,
            measuredBitrateKbps = measuredBitrateKbps,
            bitrateProvenance = ValueProvenance.STREAM_MEASURED,
            bitrateMode = bitrateMode,
            encoderInfo = encoderTag,
            parsedTags = tags,
            warnings = warnings
        )
    }

    private fun parseId3v2BasicFrames(b: ByteArray, tagEnd: Int, out: MutableMap<String, String>) {
        if (tagEnd < 14) return
        val versionMajor = b[3].toInt() and 0xFF
        if (versionMajor !in 3..4) return
        val flags = b[5].toInt() and 0xFF
        var pos = 10
        if ((flags and 0x40) != 0 && pos + 4 <= tagEnd) {
            val extSize = if (versionMajor == 4) readSynchsafeInt(b, pos) else readUInt32BE(b, pos).toInt()
            pos += extSize.coerceAtLeast(4)
        }
        while (pos + 10 <= tagEnd) {
            val frameId = ascii(b, pos, 4)
            if (frameId.any { it !in 'A'..'Z' && it !in '0'..'9' }) break
            val frameSize = if (versionMajor == 4) {
                readSynchsafeInt(b, pos + 4)
            } else {
                readUInt32BE(b, pos + 4).toInt()
            }
            if (frameSize <= 0 || pos + 10 + frameSize > tagEnd) break
            if (frameId.startsWith("T") && frameId != "TXXX" && frameSize >= 2) {
                val enc = b[pos + 10].toInt() and 0xFF
                val strBytes = b.copyOfRange(pos + 11, pos + 10 + frameSize)
                val charset = when (enc) {
                    0 -> Charsets.ISO_8859_1
                    1 -> Charsets.UTF_16
                    2 -> Charsets.UTF_16BE
                    else -> Charsets.UTF_8
                }
                val text = runCatching { String(strBytes, charset).trim('\u0000', ' ') }.getOrNull()
                if (!text.isNullOrBlank()) {
                    val label = when (frameId) {
                        "TIT2" -> "Title"
                        "TPE1" -> "Artist"
                        "TPE2" -> "AlbumArtist"
                        "TALB" -> "Album"
                        "TCON" -> "Genre"
                        "TYER", "TDRC" -> "Year"
                        "TRCK" -> "Track"
                        "TSSE" -> "Encoder"
                        else -> "ID3:$frameId"
                    }
                    out[label] = text
                }
            }
            pos += 10 + frameSize
        }
    }

    // =========================================================================
    // 4. OGG Container ("OggS") -> Vorbis & Opus
    // =========================================================================
    private fun isOgg(b: ByteArray): Boolean = b.size >= 4 && ascii(b, 0, 4) == "OggS"

    private fun parseOgg(b: ByteArray, tail: ByteArray, totalFileSize: Long?): RawBinaryProbeResult {
        val warnings = mutableListOf<String>()
        val tags = linkedMapOf<String, String>()
        if (b.size < 28) return RawBinaryProbeResult(detectedFormat = "OGG", warnings = listOf("OGGページヘッダーが不完全です。"))

        val segCount = b[26].toInt() and 0xFF
        val firstPayloadOffset = 27 + segCount
        if (firstPayloadOffset >= b.size) {
            return RawBinaryProbeResult(detectedFormat = "OGG", warnings = listOf("OGGセグメントテーブルが破損しています。"))
        }
        var firstPagePayloadLen = 0
        for (i in 0 until segCount) {
            firstPagePayloadLen += b[27 + i].toInt() and 0xFF
        }

        val lastGranule = extractLastOggGranule(if (tail.isNotEmpty()) tail else b)

        if (firstPayloadOffset + 19 <= b.size && ascii(b, firstPayloadOffset, 8) == "OpusHead") {
            val channels = b[firstPayloadOffset + 9].toInt() and 0xFF
            val preSkip = readUInt16LE(b, firstPayloadOffset + 10)
            val inputSampleRate = readUInt32LE(b, firstPayloadOffset + 12).toInt()

            var secondPagePos = firstPayloadOffset + firstPagePayloadLen
            var vendor: String? = null
            if (secondPagePos + 27 < b.size && ascii(b, secondPagePos, 4) == "OggS") {
                val seg2 = b[secondPagePos + 26].toInt() and 0xFF
                val pay2 = secondPagePos + 27 + seg2
                var len2 = 0
                for (i in 0 until seg2) {
                    if (secondPagePos + 27 + i < b.size) len2 += b[secondPagePos + 27 + i].toInt() and 0xFF
                }
                if (pay2 + 8 <= b.size && ascii(b, pay2, 8) == "OpusTags") {
                    vendor = parseVorbisCommentBlock(b, pay2 + 8, (len2 - 8).coerceAtLeast(0), tags)
                }
                secondPagePos = pay2 + len2
            }

            val effectiveSamples = lastGranule?.let { (it - preSkip.toLong()).coerceAtLeast(0L) }
            val durationMs = effectiveSamples?.let { (it * 1000L) / 48_000L }
            val audioBytes = totalFileSize?.let { (it - secondPagePos.toLong()).coerceAtLeast(0L) }
            val measuredKbps = if (audioBytes != null && durationMs != null && durationMs > 0) {
                ((audioBytes * 8.0) / durationMs.toDouble()).roundToInt()
            } else null

            return RawBinaryProbeResult(
                detectedFormat = "OGG (Opus)",
                detectedCodec = "Opus (RFC 6716 / 7845)",
                compressionSummary = "非可逆圧縮（SILK + CELT ハイブリッド符号化）",
                lossless = LosslessClassification.LOSSY,
                sampleRateHz = 48_000,
                originalInputSampleRateHz = inputSampleRate.takeIf { it > 0 },
                sampleRateProvenance = ValueProvenance.STREAM_MEASURED,
                bitDepth = null,
                bitDepthNote = "非可逆圧縮のため固定ビット深度なし（Opus標準48kHzデコード）",
                channelCount = channels,
                channelLayout = formatChannelLayout(channels),
                totalSamples = effectiveSamples,
                durationMs = durationMs,
                audioDataBytes = audioBytes,
                metadataOverheadBytes = totalFileSize?.let { audioBytes?.let { ab -> it - ab } },
                measuredBitrateKbps = measuredKbps,
                bitrateProvenance = if (measuredKbps != null) ValueProvenance.STREAM_MEASURED else ValueProvenance.UNAVAILABLE,
                bitrateMode = BitrateControlMode.VBR_FRAME_DETECTED,
                encoderInfo = vendor,
                parsedTags = tags,
                warnings = warnings
            )
        }

        if (firstPayloadOffset + 30 <= b.size &&
            (b[firstPayloadOffset].toInt() and 0xFF) == 1 &&
            ascii(b, firstPayloadOffset + 1, 6) == "vorbis"
        ) {
            val channels = b[firstPayloadOffset + 11].toInt() and 0xFF
            val sampleRate = readUInt32LE(b, firstPayloadOffset + 12).toInt()
            val brMax = readInt32LE(b, firstPayloadOffset + 16)
            val brNominal = readInt32LE(b, firstPayloadOffset + 20)
            val brMin = readInt32LE(b, firstPayloadOffset + 24)

            var secondPagePos = firstPayloadOffset + firstPagePayloadLen
            var vendor: String? = null
            if (secondPagePos + 27 < b.size && ascii(b, secondPagePos, 4) == "OggS") {
                val seg2 = b[secondPagePos + 26].toInt() and 0xFF
                val pay2 = secondPagePos + 27 + seg2
                var len2 = 0
                for (i in 0 until seg2) {
                    if (secondPagePos + 27 + i < b.size) len2 += b[secondPagePos + 27 + i].toInt() and 0xFF
                }
                if (pay2 + 7 <= b.size && (b[pay2].toInt() and 0xFF) == 3 && ascii(b, pay2 + 1, 6) == "vorbis") {
                    vendor = parseVorbisCommentBlock(b, pay2 + 7, (len2 - 7).coerceAtLeast(0), tags)
                }
            }

            val durationMs = if (lastGranule != null && sampleRate > 0) {
                (lastGranule * 1000L) / sampleRate.toLong()
            } else null
            val audioBytes = totalFileSize
            val measuredKbps = if (audioBytes != null && durationMs != null && durationMs > 0) {
                ((audioBytes * 8.0) / durationMs.toDouble()).roundToInt()
            } else null
            val nominalKbps = if (brNominal > 0) (brNominal / 1000.0).roundToInt() else null

            val mode = if (brMax > 0 && brMin > 0 && brMax == brMin) {
                BitrateControlMode.CBR_HEADER
            } else {
                BitrateControlMode.VBR_FRAME_DETECTED
            }

            return RawBinaryProbeResult(
                detectedFormat = "OGG (Vorbis)",
                detectedCodec = "Ogg Vorbis",
                compressionSummary = "非可逆圧縮（MDCT・ベクトル量子化）",
                lossless = LosslessClassification.LOSSY,
                sampleRateHz = sampleRate,
                sampleRateProvenance = ValueProvenance.STREAM_MEASURED,
                bitDepth = null,
                bitDepthNote = "非可逆圧縮のため固定ビット深度なし",
                channelCount = channels,
                channelLayout = formatChannelLayout(channels),
                totalSamples = lastGranule,
                durationMs = durationMs,
                audioDataBytes = audioBytes,
                measuredBitrateKbps = measuredKbps ?: nominalKbps,
                nominalBitrateKbps = nominalKbps,
                bitrateProvenance = if (measuredKbps != null) ValueProvenance.STREAM_MEASURED else ValueProvenance.HEADER_DECLARED,
                bitrateMode = mode,
                encoderInfo = vendor,
                parsedTags = tags,
                warnings = warnings
            )
        }

        return RawBinaryProbeResult(detectedFormat = "OGG", warnings = listOf("未対応のOGG内部ストリームです。"))
    }

    private fun extractLastOggGranule(buf: ByteArray): Long? {
        for (i in buf.size - 14 downTo 0) {
            if (buf[i] == 'O'.code.toByte() &&
                buf[i + 1] == 'g'.code.toByte() &&
                buf[i + 2] == 'g'.code.toByte() &&
                buf[i + 3] == 'S'.code.toByte()
            ) {
                val gran = readInt64LE(buf, i + 6)
                if (gran > 0L) return gran
            }
        }
        return null
    }

    // =========================================================================
    // 5. MP4 / M4A ISO BMFF ("ftyp" -> AAC / ALAC / FLAC, supports moov at head OR tail!)
    // =========================================================================
    private fun isIsoBmff(b: ByteArray): Boolean =
        b.size >= 12 && ascii(b, 4, 4) == "ftyp"

    private fun parseMp4M4a(head: ByteArray, tail: ByteArray, totalFileSize: Long?): RawBinaryProbeResult {
        val majorBrand = ascii(head, 8, 4).trim()
        var codecFourCc: String? = null
        var sampleRate: Int? = null
        var channels: Int? = null
        var bitDepth: Int? = null
        var aacAudioObjectType: Int? = null
        var mdhdTimescale: Long? = null
        var mdhdDuration: Long? = null
        var mdatPayloadBytes: Long? = null
        var stszConstantSampleSize: Int? = null
        var stszSampleCount: Int? = null

        fun walkBoxes(buf: ByteArray, start: Int, end: Int, depth: Int) {
            if (depth > 10) return
            var pos = start
            while (pos + 8 <= end && pos + 8 <= buf.size) {
                val size32 = readUInt32BE(buf, pos)
                val type = ascii(buf, pos + 4, 4)
                var headerLen = 8
                val boxSize = when {
                    size32 == 1L -> {
                        if (pos + 16 > buf.size) return
                        headerLen = 16
                        readInt64BE(buf, pos + 8)
                    }
                    size32 == 0L -> (totalFileSize ?: buf.size.toLong()) - pos.toLong()
                    else -> size32
                }
                if (boxSize < headerLen) return
                val boxEndLong = pos.toLong() + boxSize
                val clampedEnd = boxEndLong.coerceAtMost(buf.size.toLong()).toInt()

                when (type) {
                    "moov", "trak", "mdia", "minf", "stbl", "udta" -> {
                        walkBoxes(buf, pos + headerLen, clampedEnd, depth + 1)
                    }
                    "mdat" -> {
                        if (buf === head) {
                            mdatPayloadBytes = (mdatPayloadBytes ?: 0L) + (boxSize - headerLen).coerceAtLeast(0L)
                        }
                    }
                    "mdhd" -> {
                        val p = pos + headerLen
                        if (p + 20 <= clampedEnd) {
                            val ver = buf[p].toInt() and 0xFF
                            if (ver == 1 && p + 32 <= clampedEnd) {
                                mdhdTimescale = readUInt32BE(buf, p + 20)
                                mdhdDuration = readInt64BE(buf, p + 24)
                            } else if (ver == 0 && p + 20 <= clampedEnd) {
                                mdhdTimescale = readUInt32BE(buf, p + 12)
                                mdhdDuration = readUInt32BE(buf, p + 16)
                            }
                        }
                    }
                    "stsd" -> {
                        val entryPos = pos + headerLen + 8
                        if (entryPos + 36 <= clampedEnd) {
                            val entrySize = readUInt32BE(buf, entryPos).toInt()
                            val entryType = ascii(buf, entryPos + 4, 4)
                            codecFourCc = entryType
                            channels = readUInt16BE(buf, entryPos + 24)
                            val stsdBits = readUInt16BE(buf, entryPos + 26)
                            sampleRate = (readUInt32BE(buf, entryPos + 32) ushr 16).toInt()

                            val subStart = entryPos + 36
                            val subEnd = (entryPos + entrySize).coerceAtMost(clampedEnd)
                            if (entryType == "alac" && subStart + 36 <= subEnd) {
                                var sp = subStart
                                while (sp + 8 <= subEnd) {
                                    val csz = readUInt32BE(buf, sp).toInt()
                                    val ctype = ascii(buf, sp + 4, 4)
                                    if (ctype == "alac" && sp + 36 <= subEnd) {
                                        bitDepth = buf[sp + 17].toInt() and 0xFF
                                        val alacCh = buf[sp + 21].toInt() and 0xFF
                                        val alacSr = readUInt32BE(buf, sp + 32).toInt()
                                        if (alacCh > 0) channels = alacCh
                                        if (alacSr > 0) sampleRate = alacSr
                                        break
                                    }
                                    if (csz < 8) break
                                    sp += csz
                                }
                            } else if (entryType == "mp4a") {
                                aacAudioObjectType = extractEsdsAudioObjectType(buf, subStart, subEnd)
                            } else if (stsdBits > 0) {
                                bitDepth = stsdBits
                            }
                        }
                    }
                    "stsz" -> {
                        val p = pos + headerLen + 4
                        if (p + 8 <= clampedEnd) {
                            stszConstantSampleSize = readUInt32BE(buf, p).toInt()
                            stszSampleCount = readUInt32BE(buf, p + 4).toInt()
                        }
                    }
                }
                if (boxEndLong > buf.size || boxEndLong <= pos) break
                pos = boxEndLong.toInt()
            }
        }

        walkBoxes(head, 0, head.size, 0)

        // If moov was written after a large mdat box (non-faststart M4A/ALAC), locate moov in tailBytes!
        if (codecFourCc == null && tail.size >= 16) {
            for (i in 4..(tail.size - 8)) {
                if (tail[i] == 'm'.code.toByte() &&
                    tail[i + 1] == 'o'.code.toByte() &&
                    tail[i + 2] == 'o'.code.toByte() &&
                    tail[i + 3] == 'v'.code.toByte()
                ) {
                    val moovStart = i - 4
                    val moovLen = readUInt32BE(tail, moovStart)
                    if (moovLen in 16..tail.size.toLong()) {
                        walkBoxes(tail, moovStart, tail.size, 0)
                        break
                    }
                }
            }
        }

        if ((sampleRate == null || sampleRate == 0) && mdhdTimescale != null && mdhdTimescale!! > 0) {
            sampleRate = mdhdTimescale!!.toInt()
        }

        val durationMs = if (mdhdTimescale != null && mdhdTimescale!! > 0 && mdhdDuration != null && mdhdDuration!! > 0) {
            (mdhdDuration!! * 1000L) / mdhdTimescale!!
        } else null

        val measuredKbps = if (mdatPayloadBytes != null && durationMs != null && durationMs > 0) {
            ((mdatPayloadBytes!! * 8.0) / durationMs.toDouble()).roundToInt()
        } else null

        val isAlac = codecFourCc == "alac"
        val isFlacInMp4 = codecFourCc == "fLaC"
        val codecLabel = when (codecFourCc) {
            "alac" -> "ALAC (Apple Lossless)"
            "fLaC" -> "FLAC (in MP4)"
            "Opus" -> "Opus (in MP4)"
            "mp4a" -> when (aacAudioObjectType) {
                2 -> "AAC-LC (Low Complexity)"
                5 -> "HE-AAC (SBR)"
                29 -> "HE-AAC v2 (SBR + PS)"
                else -> "AAC (MPEG-4 Audio)"
            }
            else -> codecFourCc ?: "MP4 Audio"
        }

        val losslessClass = if (isAlac || isFlacInMp4) {
            LosslessClassification.COMPRESSED_LOSSLESS
        } else {
            LosslessClassification.LOSSY
        }

        val brMode = when {
            isAlac || isFlacInMp4 -> BitrateControlMode.VBR_LOSSLESS
            stszConstantSampleSize != null && stszConstantSampleSize!! > 0 -> BitrateControlMode.CBR_VERIFIED
            stszConstantSampleSize == 0 && (stszSampleCount ?: 0) > 1 -> BitrateControlMode.VBR_FRAME_DETECTED
            else -> BitrateControlMode.UNKNOWN
        }

        return RawBinaryProbeResult(
            detectedFormat = "MP4 / M4A ($majorBrand)",
            detectedCodec = codecLabel,
            compressionSummary = if (isAlac || isFlacInMp4) "可逆圧縮（ロスレス）" else "非可逆圧縮（AAC/MP4）",
            lossless = losslessClass,
            sampleRateHz = sampleRate,
            sampleRateProvenance = if (sampleRate != null) ValueProvenance.STREAM_MEASURED else ValueProvenance.UNAVAILABLE,
            bitDepth = if (isAlac || isFlacInMp4) bitDepth else null,
            bitDepthNote = if (isAlac || isFlacInMp4) {
                bitDepth?.let { "$it bit (ALAC/MP4 stsd実測)" } ?: "取得不可"
            } else {
                "非可逆圧縮のため固定ビット深度なし"
            },
            channelCount = channels,
            channelLayout = formatChannelLayout(channels),
            durationMs = durationMs,
            audioDataBytes = mdatPayloadBytes,
            metadataOverheadBytes = if (totalFileSize != null && mdatPayloadBytes != null && totalFileSize >= mdatPayloadBytes!!) {
                totalFileSize - mdatPayloadBytes!!
            } else null,
            measuredBitrateKbps = measuredKbps,
            bitrateProvenance = if (measuredKbps != null) ValueProvenance.STREAM_MEASURED else ValueProvenance.UNAVAILABLE,
            bitrateMode = brMode
        )
    }

    private fun extractEsdsAudioObjectType(b: ByteArray, start: Int, end: Int): Int? {
        for (i in start..(end - 8)) {
            if (ascii(b, i + 4, 4) == "esds") {
                val boxEnd = (i + readUInt32BE(b, i).toInt()).coerceAtMost(end)
                for (j in (i + 12) until (boxEnd - 2)) {
                    if ((b[j].toInt() and 0xFF) == 0x05) {
                        var k = j + 1
                        while (k < boxEnd && (b[k].toInt() and 0x80) != 0) k++
                        if (k + 1 < boxEnd) {
                            val firstAscByte = b[k + 1].toInt() and 0xFF
                            return (firstAscByte ushr 3) and 0x1F
                        }
                    }
                }
            }
        }
        return null
    }

    // =========================================================================
    // 6. Raw ADTS AAC ("0xFFFx")
    // =========================================================================
    private val ADTS_SAMPLE_RATES = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    )

    private fun isAdtsAac(b: ByteArray, offset: Int): Boolean {
        if (offset + 7 > b.size) return false
        val b0 = b[offset].toInt() and 0xFF
        val b1 = b[offset + 1].toInt() and 0xFF
        if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) return false
        val layerBits = (b1 ushr 1) and 0x03
        return layerBits == 0
    }

    private fun parseAdtsAac(b: ByteArray, offset: Int, tail: ByteArray, totalFileSize: Long?): RawBinaryProbeResult {
        val profileObj = ((b[offset + 2].toInt() ushr 6) and 0x03) + 1
        val srIdx = (b[offset + 2].toInt() ushr 2) and 0x0F
        val chCfg = ((b[offset + 2].toInt() and 0x01) shl 2) or ((b[offset + 3].toInt() ushr 6) and 0x03)
        val sampleRate = ADTS_SAMPLE_RATES.getOrNull(srIdx)
        val channels = if (chCfg == 7) 8 else chCfg.takeIf { it > 0 }

        val frameSizes = mutableListOf<Int>()
        var pos = offset
        while (frameSizes.size < 120 && pos + 7 <= b.size && isAdtsAac(b, pos)) {
            val frameLen = ((b[pos + 3].toInt() and 0x03) shl 11) or
                ((b[pos + 4].toInt() and 0xFF) shl 3) or
                ((b[pos + 5].toInt() ushr 5) and 0x07)
            if (frameLen < 7) break
            frameSizes.add(frameLen)
            pos += frameLen
        }

        val brMode = if (frameSizes.distinct().size <= 1 && frameSizes.size >= 3) {
            BitrateControlMode.CBR_VERIFIED
        } else {
            BitrateControlMode.VBR_FRAME_DETECTED
        }

        val avgFrameBytes = if (frameSizes.isNotEmpty()) frameSizes.average() else null
        val kbps = if (avgFrameBytes != null && sampleRate != null) {
            ((avgFrameBytes * 8.0 * sampleRate.toDouble()) / (1024.0 * 1000.0)).roundToInt()
        } else null

        val trailing = detectTrailingTagSize(tail)
        val audioBytes = totalFileSize?.let { (it - offset - trailing).coerceAtLeast(0L) }
        val durationMs = if (audioBytes != null && kbps != null && kbps > 0) {
            (audioBytes * 8L) / kbps.toLong()
        } else null

        val codecName = when (profileObj) {
            1 -> "AAC Main (ADTS)"
            2 -> "AAC-LC (ADTS)"
            3 -> "AAC-SSR (ADTS)"
            else -> "AAC-LTP (ADTS)"
        }

        return RawBinaryProbeResult(
            detectedFormat = "AAC (ADTS)",
            detectedCodec = codecName,
            compressionSummary = "非可逆圧縮 (ADTS AACストリーム)",
            lossless = LosslessClassification.LOSSY,
            sampleRateHz = sampleRate,
            sampleRateProvenance = if (sampleRate != null) ValueProvenance.STREAM_MEASURED else ValueProvenance.UNAVAILABLE,
            bitDepth = null,
            bitDepthNote = "非可逆圧縮のため固定ビット深度なし",
            channelCount = channels,
            channelLayout = formatChannelLayout(channels),
            durationMs = durationMs,
            audioDataBytes = audioBytes,
            metadataOverheadBytes = (offset + trailing).toLong(),
            measuredBitrateKbps = kbps,
            bitrateProvenance = if (kbps != null) ValueProvenance.STREAM_MEASURED else ValueProvenance.UNAVAILABLE,
            bitrateMode = brMode
        )
    }

    private fun parseVorbisCommentBlock(
        b: ByteArray,
        start: Int,
        length: Int,
        outTags: MutableMap<String, String>
    ): String? {
        val end = (start + length).coerceAtMost(b.size)
        if (start + 8 > end) return null
        val vendorLen = readUInt32LE(b, start).toInt()
        var pos = start + 4
        if (vendorLen < 0 || pos + vendorLen > end) return null
        val vendor = String(b, pos, vendorLen, Charsets.UTF_8)
        pos += vendorLen
        if (pos + 4 > end) return vendor
        val count = readUInt32LE(b, pos).toInt().coerceAtMost(200)
        pos += 4
        for (i in 0 until count) {
            if (pos + 4 > end) break
            val itemLen = readUInt32LE(b, pos).toInt()
            pos += 4
            if (itemLen < 0 || pos + itemLen > end) break
            val raw = String(b, pos, itemLen, Charsets.UTF_8)
            pos += itemLen
            val eq = raw.indexOf('=')
            if (eq > 0) {
                val k = raw.substring(0, eq).trim()
                val v = raw.substring(eq + 1).trim()
                if (v.isNotEmpty()) outTags[k] = v
            }
        }
        return vendor.ifBlank { null }
    }

    fun formatChannelLayout(channels: Int?): String = when (channels) {
        null -> "取得不可"
        1 -> "Mono (1ch)"
        2 -> "Stereo (2ch)"
        4 -> "Quadraphonic (4ch)"
        6 -> "5.1 Surround (6ch)"
        8 -> "7.1 Surround (8ch)"
        else -> "${channels}ch"
    }

    private fun ascii(b: ByteArray, offset: Int, len: Int): String {
        if (offset < 0 || offset + len > b.size) return ""
        return String(b, offset, len, Charsets.ISO_8859_1)
    }

    private fun readUInt16LE(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun readUInt16BE(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun readUInt24BE(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() and 0xFF)

    private fun readUInt32LE(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFFL) or
            ((b[o + 1].toLong() and 0xFFL) shl 8) or
            ((b[o + 2].toLong() and 0xFFL) shl 16) or
            ((b[o + 3].toLong() and 0xFFL) shl 24)

    private fun readInt32LE(b: ByteArray, o: Int): Int = readUInt32LE(b, o).toInt()

    private fun readUInt32BE(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFFL) shl 24) or
            ((b[o + 1].toLong() and 0xFFL) shl 16) or
            ((b[o + 2].toLong() and 0xFFL) shl 8) or
            (b[o + 3].toLong() and 0xFFL)

    private fun readInt64LE(b: ByteArray, o: Int): Long =
        readUInt32LE(b, o) or (readUInt32LE(b, o + 4) shl 32)

    private fun readInt64BE(b: ByteArray, o: Int): Long =
        (readUInt32BE(b, o) shl 32) or readUInt32BE(b, o + 4)

    private fun readSynchsafeInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or
            ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or
            (b[o + 3].toInt() and 0x7F)
}
