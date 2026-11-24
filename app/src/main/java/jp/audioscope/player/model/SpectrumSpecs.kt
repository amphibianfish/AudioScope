package jp.audioscope.player.model

enum class VisualizerMode(val labelJp: String) {
    OFF("モニターOFF（省電力）"),
    SPECTRUM("周波数スペクトラム (FFT)"),
    WAVEFORM("波形オシロスコープ"),
    DUAL("スペクトラム＋波形")
}

data class AudioVisualFrame(
    val isActive: Boolean = false,
    val sampleRateHz: Int = 44100,
    val nyquistHz: Float = 22050f,
    val waveformNormalized: FloatArray = FloatArray(0),   // -1.0f .. +1.0f
    val logBandMagnitudesDb: FloatArray = FloatArray(0),  // -96f .. 0f (32 logarithmic bands)
    val logBandCenterHz: FloatArray = FloatArray(0),      // Center frequencies of the 32 bands
    val rmsDbfs: Float = -96f,
    val peakDbfs: Float = -96f,
    val dominantFrequencyHz: Float = 0f,
    val ultrasonicEnergyDb: Float? = null                 // >20 kHz average energy when sampleRate > 44.1 kHz
)
