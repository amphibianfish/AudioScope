package jp.audioscope.player.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import jp.audioscope.player.playback.AudioPlaybackBus
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音声データを1ビットも改変せずに下流の AudioSink へ透過出力しつつ、
 * スペクトラム・波形モニターが有効なときだけデコード済みPCMをリングバッファへ取得するプロセッサー。
 * RECORD_AUDIO（マイク権限）を一切必要とせず、16bit / 24bit / 32bit / IEEE Float ハイレゾPCMに対応する。
 */
@OptIn(UnstableApi::class)
class PcmTapAudioProcessor : BaseAudioProcessor() {

    private val ringBuffer = FloatArray(FftAnalyzer.FFT_SIZE)
    private var writeCursor = 0
    private var samplesSinceLastEmit = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val enc = inputAudioFormat.encoding
        val supported = enc == C.ENCODING_PCM_16BIT ||
            enc == C.ENCODING_PCM_24BIT ||
            enc == C.ENCODING_PCM_24BIT_BIG_ENDIAN ||
            enc == C.ENCODING_PCM_32BIT ||
            enc == C.ENCODING_PCM_FLOAT
        return if (supported && inputAudioFormat.channelCount > 0) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        // モニター表示がONのときのみ読み取り専用スライスからサンプルを取得（省電力）
        if (AudioPlaybackBus.isVisualizerCaptureEnabled.value) {
            tapSamplesReadOnly(inputBuffer.slice().order(inputBuffer.order()))
        }

        // 元のPCMバイト列を一切変更せずそのまま出力バッファへコピー
        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer)
        output.flip()
    }

    private fun tapSamplesReadOnly(buf: ByteBuffer) {
        val format = inputAudioFormat
        val channels = format.channelCount.coerceAtLeast(1)
        val encoding = format.encoding
        val sampleRate = format.sampleRate.takeIf { it > 0 } ?: 44100

        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frameSize = bytesPerSample * channels
        if (frameSize <= 0) return

        // 約 25〜30 fps 間隔でFFTを更新
        val emitIntervalFrames = (sampleRate / 28).coerceAtLeast(512)

        buf.order(
            if (encoding == C.ENCODING_PCM_24BIT_BIG_ENDIAN) ByteOrder.BIG_ENDIAN
            else ByteOrder.LITTLE_ENDIAN
        )

        while (buf.remaining() >= frameSize) {
            var monoSum = 0f
            for (ch in 0 until channels) {
                val sample = when (encoding) {
                    C.ENCODING_PCM_16BIT -> buf.short.toFloat() / 32768f
                    C.ENCODING_PCM_24BIT -> {
                        val b0 = buf.get().toInt() and 0xFF
                        val b1 = buf.get().toInt() and 0xFF
                        val b2 = buf.get().toInt() // sign-extended MSB
                        val s24 = (b2 shl 16) or (b1 shl 8) or b0
                        s24.toFloat() / 8_388_608f
                    }
                    C.ENCODING_PCM_24BIT_BIG_ENDIAN -> {
                        val b2 = buf.get().toInt() // sign-extended MSB
                        val b1 = buf.get().toInt() and 0xFF
                        val b0 = buf.get().toInt() and 0xFF
                        val s24 = (b2 shl 16) or (b1 shl 8) or b0
                        s24.toFloat() / 8_388_608f
                    }
                    C.ENCODING_PCM_32BIT -> buf.int.toFloat() / 2_147_483_648f
                    C.ENCODING_PCM_FLOAT -> {
                        val f = buf.float
                        if (f.isFinite()) f.coerceIn(-1f, 1f) else 0f
                    }
                    else -> 0f
                }
                monoSum += sample
            }
            ringBuffer[writeCursor] = (monoSum / channels.toFloat()).coerceIn(-1f, 1f)
            writeCursor = (writeCursor + 1) % ringBuffer.size
            samplesSinceLastEmit++

            if (samplesSinceLastEmit >= emitIntervalFrames) {
                samplesSinceLastEmit = 0
                val orderedWindow = FloatArray(ringBuffer.size)
                val tailLen = ringBuffer.size - writeCursor
                System.arraycopy(ringBuffer, writeCursor, orderedWindow, 0, tailLen)
                if (writeCursor > 0) {
                    System.arraycopy(ringBuffer, 0, orderedWindow, tailLen, writeCursor)
                }
                AudioPlaybackBus.publishVisualWindow(orderedWindow, sampleRate)
            }
        }
    }

    override fun onFlush() {
        samplesSinceLastEmit = 0
    }

    override fun onReset() {
        writeCursor = 0
        samplesSinceLastEmit = 0
        ringBuffer.fill(0f)
    }
}
