package jp.audioscope.player

import jp.audioscope.player.audio.dsp.FftAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FftAnalyzerTest {

    @Test
    fun analyze1kHzSineWave_detectsDominantFrequencyAccurately() {
        val sampleRate = 48_000
        val targetHz = 1000.0
        val pcm = FloatArray(FftAnalyzer.FFT_SIZE) { i ->
            sin(2.0 * PI * targetHz * i / sampleRate).toFloat()
        }

        val frame = FftAnalyzer.analyze(pcm, sampleRate)
        assertTrue(frame.isActive)
        // Bin resolution at 48kHz / 2048 = 23.4375 Hz -> within +/- 25 Hz
        assertEquals(1000f, frame.dominantFrequencyHz, 30f)
        assertTrue(frame.peakDbfs > -0.5f)
        assertTrue(frame.rmsDbfs in -3.5f..-2.5f)
        // 48kHz > 44.1kHz so ultrasonic field is computed and should be near floor for pure 1kHz tone
        assertNotNull(frame.ultrasonicEnergyDb)
        assertTrue(frame.ultrasonicEnergyDb!! < -60f)
    }

    @Test
    fun analyze96kHzUltrasonic30kHzTone_detectsHighResUltrasonicEnergy() {
        val sampleRate = 96_000
        val targetHz = 30_000.0
        val pcm = FloatArray(FftAnalyzer.FFT_SIZE) { i ->
            (0.5 * sin(2.0 * PI * targetHz * i / sampleRate)).toFloat()
        }

        val frame = FftAnalyzer.analyze(pcm, sampleRate)
        assertEquals(30_000f, frame.dominantFrequencyHz, 60f)
        assertNotNull(frame.ultrasonicEnergyDb)
        assertTrue(frame.ultrasonicEnergyDb!! > -35f)
    }

    @Test
    fun analyze44100HzSilence_returnsFloorAndNoUltrasonicMetric() {
        val pcm = FloatArray(FftAnalyzer.FFT_SIZE) { 0f }
        val frame = FftAnalyzer.analyze(pcm, 44_100)
        assertEquals(-96f, frame.rmsDbfs, 0.01f)
        assertEquals(0f, frame.dominantFrequencyHz, 0.01f)
        assertNull(frame.ultrasonicEnergyDb)
    }
}
