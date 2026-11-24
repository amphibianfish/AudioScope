package jp.audioscope.player.playback

import androidx.media3.common.Format
import jp.audioscope.player.audio.dsp.FftAnalyzer
import jp.audioscope.player.model.ActiveAudioTrackConfig
import jp.audioscope.player.model.AudioVisualFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * MediaSessionService と UI ViewModel 間で、Contextリークを起こさずに
 * デコーダー入力Format・AudioTrack実構成・FFTフレームを共有する軽量イベントバス。
 */
object AudioPlaybackBus {
    private val dspScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _decoderInputFormat = MutableStateFlow<Format?>(null)
    val decoderInputFormat: StateFlow<Format?> = _decoderInputFormat.asStateFlow()

    private val _audioTrackConfig = MutableStateFlow<ActiveAudioTrackConfig?>(null)
    val audioTrackConfig: StateFlow<ActiveAudioTrackConfig?> = _audioTrackConfig.asStateFlow()

    private val _visualFrame = MutableStateFlow(AudioVisualFrame(isActive = false))
    val visualFrame: StateFlow<AudioVisualFrame> = _visualFrame.asStateFlow()

    private val _playbackErrorMessage = MutableStateFlow<String?>(null)
    val playbackErrorMessage: StateFlow<String?> = _playbackErrorMessage.asStateFlow()

    val isVisualizerCaptureEnabled = MutableStateFlow(true)

    fun updateDecoderFormat(format: Format?) {
        _decoderInputFormat.value = format
    }

    fun updateAudioTrackConfig(config: ActiveAudioTrackConfig?) {
        _audioTrackConfig.value = config
    }

    fun reportPlaybackError(message: String?) {
        _playbackErrorMessage.value = message
    }

    fun publishVisualWindow(pcmWindow: FloatArray, sampleRateHz: Int) {
        if (!isVisualizerCaptureEnabled.value) return
        dspScope.launch {
            _visualFrame.value = FftAnalyzer.analyze(pcmWindow, sampleRateHz)
        }
    }

    fun clearVisuals() {
        _visualFrame.value = AudioVisualFrame(isActive = false)
    }
}
