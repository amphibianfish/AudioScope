package jp.audioscope.player.model

enum class PlaybackDataOrigin(val labelJp: String, val detailJp: String) {
    LOCAL_STORAGE(
        "ローカル再生（端末内ストレージ）",
        "端末内の音声ファイル（MediaStore / SAF）を直接読み込んでいます。音楽再生に伴うインターネット通信は発生しません。"
    ),
    NETWORK_STREAM(
        "ネットワーク再生（HTTP/HTTPSストリーミング）",
        "ネットワーク経由で音声ストリームを受信して再生しています。バッファリングに応じた受信通信量が発生します。"
    ),
    IDLE(
        "待機中（未再生）",
        "現在再生中のストリームはありません。"
    )
}

data class TrafficSamplePoint(
    val elapsedSec: Int,
    val rxBytesPerSec: Long,
    val txBytesPerSec: Long
)

data class NetworkTrafficSnapshot(
    val isTrafficStatsSupported: Boolean,
    val playbackOrigin: PlaybackDataOrigin,
    val uidTotalRxBytesSinceBoot: Long,
    val uidTotalTxBytesSinceBoot: Long,
    val sessionRxBytes: Long,
    val sessionTxBytes: Long,
    val currentRxBytesPerSec: Long,
    val currentTxBytesPerSec: Long,
    val peakRxBytesPerSec: Long,
    val historySamples: List<TrafficSamplePoint>,
    // Bluetooth 転送情報（実通信量はOS制限で取得不可、PCM理論値のみ「推定」として分離提示）
    val isBluetoothOutputActive: Boolean,
    val actualBluetoothBytesStatus: String,
    val estimatedUncompressedPcmBitrateKbps: Int?,
    val bluetoothEstimationNote: String,
    val countingScopeExplanationJp: String
) {
    val sessionTotalBytes: Long
        get() = (sessionRxBytes.coerceAtLeast(0L)) + (sessionTxBytes.coerceAtLeast(0L))
}

object ByteUnitFormatter {
    fun formatBytes(bytes: Long): String {
        if (bytes < 0L) return "取得不可 (OS非対応)"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(java.util.Locale.US, "%.2f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(java.util.Locale.US, "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(java.util.Locale.US, "%.3f GB", gb)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec < 0L) return "取得不可"
        return "${formatBytes(bytesPerSec)}/s"
    }
}
