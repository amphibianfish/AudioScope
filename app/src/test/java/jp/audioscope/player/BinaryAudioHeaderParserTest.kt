package jp.audioscope.player

import jp.audioscope.player.audio.probe.BinaryAudioHeaderParser
import jp.audioscope.player.model.BitrateControlMode
import jp.audioscope.player.model.LosslessClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BinaryAudioHeaderParserTest {

    @Test
    fun parseFlac96kHz24Bit_extractsExactStreamInfoAndLosslessBitrate() {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.US_ASCII))
        // Last-metadata-block flag (0x80) | block type 0 (STREAMINFO), length = 34
        out.write(byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x22))

        val streamInfo = ByteArray(34)
        // sampleRate = 96000 (0x17700), channels = 2 (ch-1 = 1), bps = 24 (bps-1 = 23 = 0x17), totalSamples = 960000 (10 sec)
        val sr = 96_000L
        val chMinus1 = 1L
        val bpsMinus1 = 23L
        val totalSamples = 960_000L
        val packed64 = (sr shl 44) or (chMinus1 shl 41) or (bpsMinus1 shl 36) or totalSamples
        val bb = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(packed64)
        System.arraycopy(bb.array(), 0, streamInfo, 10, 8)
        for (i in 18 until 34) streamInfo[i] = (i - 17).toByte()
        out.write(streamInfo)
        out.write(ByteArray(128)) // dummy audio frame start

        val totalFileSize = 42L + 3_877_500L // ~3,102 kbps over 10s
        val res = BinaryAudioHeaderParser.parse(out.toByteArray(), ByteArray(0), totalFileSize)

        assertEquals("FLAC", res.detectedFormat)
        assertEquals(LosslessClassification.COMPRESSED_LOSSLESS, res.lossless)
        assertEquals(96_000, res.sampleRateHz)
        assertEquals(24, res.bitDepth)
        assertEquals(2, res.channelCount)
        assertEquals(10_000L, res.durationMs)
        assertEquals(3_877_500L, res.audioDataBytes)
        assertEquals(3102, res.measuredBitrateKbps)
        assertEquals(BitrateControlMode.VBR_LOSSLESS, res.bitrateMode)
        assertNotNull(res.streamMd5Hex)
    }

    @Test
    fun parseWavExtensible24Bit192kHz_extractsExactPcmSpecs() {
        val dataBytes = 192_000 * 6 * 2 // 2 seconds of 192kHz / 24bit / 2ch
        val header = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(60 + dataBytes)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(40)
            putShort(0xFFFE.toShort()) // WAVE_FORMAT_EXTENSIBLE
            putShort(2)                // Stereo
            putInt(192_000)            // 192 kHz
            putInt(192_000 * 6)        // byteRate
            putShort(6)                // blockAlign
            putShort(24)               // container bits
            putShort(22)               // cbSize
            putShort(24)               // wValidBitsPerSample
            putInt(0x03)               // dwChannelMask (FL | FR)
            putShort(0x0001)           // SubFormat GUID first 2 bytes = PCM
            put(ByteArray(14))
            put("data".toByteArray())
            putInt(dataBytes)
        }.array()

        val res = BinaryAudioHeaderParser.parse(header, ByteArray(0), (68 + dataBytes).toLong())
        assertEquals("WAV (RIFF)", res.detectedFormat)
        assertEquals(LosslessClassification.UNCOMPRESSED_LOSSLESS, res.lossless)
        assertEquals(192_000, res.sampleRateHz)
        assertEquals(24, res.bitDepth)
        assertEquals(2, res.channelCount)
        assertEquals(2000L, res.durationMs)
        assertEquals(9216, res.measuredBitrateKbps)
    }

    @Test
    fun parseMp3XingVbrVsCbr_distinguishesModesWithoutFakingBitDepth() {
        // Build MPEG-1 Layer III 44.1kHz Stereo frame with Xing VBR header
        // Header: 0xFF 0xFB 0x90 0x00 -> MPEG1, Layer III, 128kbps index(9), 44100Hz(0), Stereo(0) -> frameLen = 417
        val frameLen = 417
        val buf = ByteArray(frameLen * 2)
        buf[0] = 0xFF.toByte()
        buf[1] = 0xFB.toByte()
        buf[2] = 0x90.toByte()
        buf[3] = 0x00.toByte()
        // Side info for MPEG1 Stereo is 32 bytes -> Xing at offset 36
        val xingOffset = 36
        "Xing".toByteArray().copyInto(buf, xingOffset)
        ByteBuffer.wrap(buf, xingOffset + 4, 12).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(0x03)       // Frames + Bytes flags
            putInt(1000)       // 1000 frames (~26.122 s)
            putInt(800_000)    // 800,000 bytes (~245 kbps VBR)
        }
        "LAME3.100".toByteArray().copyInto(buf, xingOffset + 16)
        buf[xingOffset + 25] = 0x04 // VBR method 4 (mtrh)

        // Second frame header at offset 417
        buf[frameLen] = 0xFF.toByte()
        buf[frameLen + 1] = 0xFB.toByte()
        buf[frameLen + 2] = 0xB0.toByte() // 192 kbps
        buf[frameLen + 3] = 0x00.toByte()

        val vbrRes = BinaryAudioHeaderParser.parse(buf, ByteArray(0), 800_500L)
        assertEquals("MP3", vbrRes.detectedFormat)
        assertEquals(LosslessClassification.LOSSY, vbrRes.lossless)
        assertNull(vbrRes.bitDepth) // MP3 has no fixed bit depth!
        assertEquals(44_100, vbrRes.sampleRateHz)
        assertEquals(BitrateControlMode.VBR_XING_VBRI, vbrRes.bitrateMode)
        assertEquals("LAME3.100", vbrRes.encoderInfo)
        assertTrue(vbrRes.measuredBitrateKbps in 244..246)
    }

    @Test
    fun parseCorruptedAndEmptyInputs_neverThrowsExceptions() {
        val emptyRes = BinaryAudioHeaderParser.parse(ByteArray(0), ByteArray(0), 0L)
        assertTrue(emptyRes.warnings.isNotEmpty())

        val truncatedFlac = "fLaC\u0000\u0000\u0022".toByteArray(Charsets.ISO_8859_1)
        val truncRes = BinaryAudioHeaderParser.parse(truncatedFlac, ByteArray(0), 7L)
        assertEquals("FLAC", truncRes.detectedFormat)
        assertNull(truncRes.sampleRateHz)

        val randomGarbage = ByteArray(1024) { (it * 73 + 19).toByte() }
        val garbageRes = BinaryAudioHeaderParser.parse(randomGarbage, ByteArray(0), 1024L)
        assertNotNull(garbageRes)
    }
}
