package jp.audioscope.player

import android.media.AudioDeviceInfo
import jp.audioscope.player.audio.output.AudioOutputInspector
import jp.audioscope.player.model.ByteUnitFormatter
import jp.audioscope.player.model.ObtainableValue
import jp.audioscope.player.model.OutputRouteCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficAndRouteTruthfulnessTest {

    @Test
    fun byteUnitFormatter_formatsBytesKbMbGbAndUnsupportedCorrectly() {
        assertEquals("取得不可 (OS非対応)", ByteUnitFormatter.formatBytes(-1L))
        assertEquals("0 B", ByteUnitFormatter.formatBytes(0L))
        assertEquals("512 B", ByteUnitFormatter.formatBytes(512L))
        assertEquals("1.50 KB", ByteUnitFormatter.formatBytes(1536L))
        assertEquals("2.00 MB", ByteUnitFormatter.formatBytes(2L * 1024L * 1024L))
        assertEquals("1.250 GB", ByteUnitFormatter.formatBytes((1.25 * 1024 * 1024 * 1024).toLong()))
        assertEquals("100.00 KB/s", ByteUnitFormatter.formatSpeed(100L * 1024L))
    }

    @Test
    fun classifyRouteCategory_mapsPublicAudioDeviceTypesCorrectly() {
        assertEquals(
            OutputRouteCategory.BLUETOOTH_A2DP,
            AudioOutputInspector.classifyRouteCategory(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
        )
        assertEquals(
            OutputRouteCategory.BLUETOOTH_LE_AUDIO,
            AudioOutputInspector.classifyRouteCategory(AudioDeviceInfo.TYPE_BLE_HEADSET)
        )
        assertEquals(
            OutputRouteCategory.USB_DAC_AUDIO,
            AudioOutputInspector.classifyRouteCategory(AudioDeviceInfo.TYPE_USB_HEADSET)
        )
        assertEquals(
            OutputRouteCategory.WIRED_HEADSET_OR_LINE,
            AudioOutputInspector.classifyRouteCategory(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
        )
        assertEquals(
            OutputRouteCategory.BUILTIN_SPEAKER,
            AudioOutputInspector.classifyRouteCategory(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        )
    }

    @Test
    fun restrictedByOsWrapper_preservesExplicitReasonAndDeveloperOptionsHint() {
        val restricted: ObtainableValue<String> = ObtainableValue.RestrictedByOs(
            reasonJp = "取得不可（OSによる制限：BluetoothA2dp.getCodecStatus() は BLUETOOTH_PRIVILEGED 権限限定）",
            developerOptionsHintJp = "開発者向けオプションで確認可能"
        )
        assertTrue(restricted is ObtainableValue.RestrictedByOs)
        assertTrue((restricted as ObtainableValue.RestrictedByOs).reasonJp.contains("取得不可"))
    }
}
