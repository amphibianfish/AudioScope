package jp.audioscope.player.audio.dsp

import jp.audioscope.player.model.AudioVisualFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 実際のデコード済みPCMサンプルに対する 2048点 Hann窓 Radix-2 Cooley-Tukey FFT および波形・RMS/Peak解析器。
 * 外部ネイティブライブラリ不要で動作し、ハイレゾ音源 (>44.1kHz) の 20kHz 超領域エネルギーも実測する。
 */
object FftAnalyzer {

    const val FFT_SIZE = 2048
    const val WAVEFORM_POINTS = 128
    const val BAND_COUNT = 32
    private const val MIN_DB = -96f
    private const val HANN_ENBW_BINS = 1.5 // Equivalent Noise Bandwidth of Hann window in bins

    private val hannWindow = FloatArray(FFT_SIZE) { i ->
        (0.5 * (1.0 - cos((2.0 * PI * i) / (FFT_SIZE - 1)))).toFloat()
    }

    fun analyze(pcmMonoWindow: FloatArray, sampleRateHz: Int): AudioVisualFrame {
        if (pcmMonoWindow.isEmpty() || sampleRateHz <= 0) {
            return AudioVisualFrame(isActive = false, sampleRateHz = sampleRateHz.coerceAtLeast(44100))
        }

        val n = FFT_SIZE
        val real = FloatArray(n)
        val imag = FloatArray(n)

        var sumSq = 0.0
        var peakAbs = 0f
        val copyLen = min(pcmMonoWindow.size, n)
        val startIdx = pcmMonoWindow.size - copyLen

        for (i in 0 until copyLen) {
            val s = pcmMonoWindow[startIdx + i].coerceIn(-1f, 1f)
            sumSq += (s * s).toDouble()
            val absS = kotlin.math.abs(s)
            if (absS > peakAbs) peakAbs = absS
            real[i] = s * hannWindow[i]
        }

        val rms = if (copyLen > 0) sqrt(sumSq / copyLen).toFloat() else 0f
        val rmsDb = amplitudeToDbfs(rms)
        val peakDb = amplitudeToDbfs(peakAbs)

        val waveform = FloatArray(WAVEFORM_POINTS)
        val step = max(1, copyLen / WAVEFORM_POINTS)
        for (b in 0 until WAVEFORM_POINTS) {
            val from = startIdx + (b * copyLen) / WAVEFORM_POINTS
            val to = min(pcmMonoWindow.size, from + step)
            var extreme = 0f
            for (k in from until to) {
                val v = pcmMonoWindow[k]
                if (kotlin.math.abs(v) > kotlin.math.abs(extreme)) extreme = v
            }
            waveform[b] = extreme.coerceIn(-1f, 1f)
        }

        fftRadix2(real, imag)

        val halfN = n / 2
        val binHz = sampleRateHz.toFloat() / n.toFloat()
        val nyquist = sampleRateHz / 2f

        val normFactor = (n / 4.0).toFloat()
        val magDb = FloatArray(halfN)
        var maxMag = -1f
        var dominantBin = 1

        for (k in 0 until halfN) {
            val mag = sqrt(real[k] * real[k] + imag[k] * imag[k]) / normFactor
            magDb[k] = amplitudeToDbfs(mag)
            if (k >= 1 && mag > maxMag) {
                maxMag = mag
                dominantBin = k
            }
        }

        val dominantHz = if (peakDb > -80f) dominantBin * binHz else 0f

        val fMin = 20.0
        val fMax = max(4000.0, min(nyquist.toDouble(), 48_000.0))
        val logMin = ln(fMin)
        val logMax = ln(fMax)

        val bandDb = FloatArray(BAND_COUNT) { MIN_DB }
        val bandCenters = FloatArray(BAND_COUNT)

        for (b in 0 until BAND_COUNT) {
            val fLow = exp(logMin + (logMax - logMin) * (b.toDouble() / BAND_COUNT))
            val fHigh = exp(logMin + (logMax - logMin) * ((b + 1).toDouble() / BAND_COUNT))
            bandCenters[b] = sqrt(fLow * fHigh).toFloat()

            val binStart = max(1, (fLow / binHz).toInt())
            val binEnd = min(halfN - 1, max(binStart, (fHigh / binHz).toInt()))
            var localPeakDb = MIN_DB
            for (k in binStart..binEnd) {
                if (magDb[k] > localPeakDb) localPeakDb = magDb[k]
            }
            bandDb[b] = localPeakDb
        }

        // Parseval band-integrated ultrasonic (>20.5 kHz) energy normalized by Hann ENBW (1.5 bins)
        val ultrasonicDb: Float? = if (sampleRateHz > 44_100) {
            val startBin20k = ((20_500f / binHz).toInt()).coerceIn(1, halfN - 1)
            val endBin = (halfN - 1).coerceAtLeast(startBin20k)
            var sumPower = 0.0
            for (k in startBin20k..endBin) {
                val mag = sqrt(real[k] * real[k] + imag[k] * imag[k]) / normFactor
                sumPower += (mag * mag).toDouble()
            }
            amplitudeToDbfs(sqrt(sumPower / HANN_ENBW_BINS).toFloat())
        } else null

        return AudioVisualFrame(
            isActive = true,
            sampleRateHz = sampleRateHz,
            nyquistHz = nyquist,
            waveformNormalized = waveform,
            logBandMagnitudesDb = bandDb,
            logBandCenterHz = bandCenters,
            rmsDbfs = rmsDb,
            peakDbfs = peakDb,
            dominantFrequencyHz = dominantHz,
            ultrasonicEnergyDb = ultrasonicDb
        )
    }

    fun amplitudeToDbfs(amplitude: Float): Float {
        if (amplitude <= 1e-5f) return MIN_DB
        return (20.0 * log10(amplitude.toDouble())).toFloat().coerceIn(MIN_DB, 0f)
    }

    private fun fftRadix2(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val halfLen = len shr 1
            val angle = (-2.0 * PI) / len.toDouble()
            val wLenR = cos(angle).toFloat()
            val wLenI = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wR = 1f
                var wI = 0f
                for (k in 0 until halfLen) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val vR = real[i + k + halfLen] * wR - imag[i + k + halfLen] * wI
                    val vI = real[i + k + halfLen] * wI + imag[i + k + halfLen] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[i + k + halfLen] = uR - vR
                    imag[i + k + halfLen] = uI - vI
                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }
}
