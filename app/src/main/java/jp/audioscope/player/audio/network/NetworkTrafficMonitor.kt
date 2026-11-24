package jp.audioscope.player.audio.network

import android.net.TrafficStats
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import jp.audioscope.player.model.ActiveAudioTrackConfig
import jp.audioscope.player.model.NetworkTrafficSnapshot
import jp.audioscope.player.model.OutputRouteCategory
import jp.audioscope.player.model.PlaybackDataOrigin
import jp.audioscope.player.model.TrafficSamplePoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Android 公式 API `TrafficStats.getUidRxBytes(uid)` / `getUidTxBytes(uid)` を使用した通信量・転送速度モニター。
 * 架空のBluetoothリアルタイム通信量は一切生成せず、実測不可項目とPCM理論推定値を厳密に区別する。
 */
class NetworkTrafficMonitor {

    private val appUid = Process.myUid()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null

    private var sessionBaselineRx: Long = readUidRx()
    private var sessionBaselineTx: Long = readUidTx()
    private var prevRxBytes: Long = sessionBaselineRx
    private var prevTxBytes: Long = sessionBaselineTx
    private var prevElapsedMs: Long = SystemClock.elapsedRealtime()
    private var peakRxSpeed: Long = 0L
    private var sampleCounter: Int = 0
    private val historyBuffer = ArrayDeque<TrafficSamplePoint>(MAX_HISTORY_POINTS)

    private var currentMediaUri: Uri? = null
    private var currentRouteCategory: OutputRouteCategory = OutputRouteCategory.BUILTIN_SPEAKER
    private var currentAudioTrackConfig: ActiveAudioTrackConfig? = null

    private val _snapshot = MutableStateFlow(createInitialSnapshot())
    val snapshot: StateFlow<NetworkTrafficSnapshot> = _snapshot.asStateFlow()

    fun startSampling() {
        if (pollingJob?.isActive == true) return
        prevRxBytes = readUidRx()
        prevTxBytes = readUidTx()
        prevElapsedMs = SystemClock.elapsedRealtime()

        pollingJob = scope.launch {
            while (isActive) {
                delay(1000L)
                sampleOnce()
            }
        }
    }

    fun stopSampling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun resetSessionCounters() {
        sessionBaselineRx = readUidRx()
        sessionBaselineTx = readUidTx()
        prevRxBytes = sessionBaselineRx
        prevTxBytes = sessionBaselineTx
        prevElapsedMs = SystemClock.elapsedRealtime()
        peakRxSpeed = 0L
        sampleCounter = 0
        synchronized(historyBuffer) {
            historyBuffer.clear()
        }
        sampleOnce()
    }

    fun updateContext(
        mediaUri: Uri?,
        routeCategory: OutputRouteCategory,
        audioTrackConfig: ActiveAudioTrackConfig?
    ) {
        currentMediaUri = mediaUri
        currentRouteCategory = routeCategory
        currentAudioTrackConfig = audioTrackConfig
        sampleOnce()
    }

    private fun sampleOnce() {
        val nowRx = readUidRx()
        val nowTx = readUidTx()
        val nowMs = SystemClock.elapsedRealtime()

        val supported = nowRx != TrafficStats.UNSUPPORTED.toLong() && nowTx != TrafficStats.UNSUPPORTED.toLong()
        val dtMs = (nowMs - prevElapsedMs).coerceAtLeast(1L)

        val rxPerSec = if (supported && prevRxBytes >= 0 && nowRx >= prevRxBytes && nowMs > prevElapsedMs) {
            ((nowRx - prevRxBytes) * 1000L) / dtMs
        } else 0L

        val txPerSec = if (supported && prevTxBytes >= 0 && nowTx >= prevTxBytes && nowMs > prevElapsedMs) {
            ((nowTx - prevTxBytes) * 1000L) / dtMs
        } else 0L

        if (nowMs - prevElapsedMs >= 500L) {
            prevRxBytes = nowRx
            prevTxBytes = nowTx
            prevElapsedMs = nowMs
            if (rxPerSec > peakRxSpeed) peakRxSpeed = rxPerSec
            sampleCounter++
            synchronized(historyBuffer) {
                if (historyBuffer.size >= MAX_HISTORY_POINTS) historyBuffer.removeFirst()
                historyBuffer.addLast(
                    TrafficSamplePoint(
                        elapsedSec = sampleCounter,
                        rxBytesPerSec = rxPerSec,
                        txBytesPerSec = txPerSec
                    )
                )
            }
        }

        val sessionRx = if (supported && sessionBaselineRx >= 0) (nowRx - sessionBaselineRx).coerceAtLeast(0L) else -1L
        val sessionTx = if (supported && sessionBaselineTx >= 0) (nowTx - sessionBaselineTx).coerceAtLeast(0L) else -1L

        val origin = classifyPlaybackOrigin(currentMediaUri)
        val isBt = currentRouteCategory == OutputRouteCategory.BLUETOOTH_A2DP ||
            currentRouteCategory == OutputRouteCategory.BLUETOOTH_LE_AUDIO ||
            currentRouteCategory == OutputRouteCategory.BLUETOOTH_SCO

        val pcmEstimatedKbps = if (isBt && currentAudioTrackConfig != null) {
            val sr = currentAudioTrackConfig!!.sampleRateHz
            val ch = currentAudioTrackConfig!!.channelCount
            val bits = when {
                currentAudioTrackConfig!!.pcmEncodingLabel.contains("24") -> 24
                currentAudioTrackConfig!!.pcmEncodingLabel.contains("32") -> 32
                else -> 16
            }
            ((sr.toLong() * ch.toLong() * bits.toLong()) / 1000.0).roundToInt()
        } else null

        val historyCopy = synchronized(historyBuffer) { historyBuffer.toList() }

        _snapshot.value = NetworkTrafficSnapshot(
            isTrafficStatsSupported = supported,
            playbackOrigin = origin,
            uidTotalRxBytesSinceBoot = nowRx,
            uidTotalTxBytesSinceBoot = nowTx,
            sessionRxBytes = sessionRx,
            sessionTxBytes = sessionTx,
            currentRxBytesPerSec = rxPerSec,
            currentTxBytesPerSec = txPerSec,
            peakRxBytesPerSec = peakRxSpeed,
            historySamples = historyCopy,
            isBluetoothOutputActive = isBt,
            actualBluetoothBytesStatus = if (isBt) {
                "取得不可（OSによる制限：Android公開APIの TrafficStats はIPネットワーク[Wi-Fi/モバイルデータ]のみを計測対象とし、Bluetooth HCI/L2CAP層の音声パケット数・実送信バイト数は取得できません）"
            } else {
                "Bluetooth音声未出力"
            },
            estimatedUncompressedPcmBitrateKbps = pcmEstimatedKbps,
            bluetoothEstimationNote = if (isBt) {
                "【推定】上記の数値は AudioTrack から Bluetooth サブシステムへ渡される圧縮前リニアPCMの理論データレートです。実際に電波で送信される転送速度は OS 側で選択されたコーデック（例: SBC 約328kbps / AAC 約256kbps / LDAC 330・660・990kbps）に再符号化された値となります。"
            } else {
                "Bluetooth出力に切り替えると、AudioTrack入力仕様に基づく圧縮前PCM理論レート（推定）と公開API制限の説明が表示されます。"
            },
            countingScopeExplanationJp = "計測方法と精度：Android公式API TrafficStats.getUidRxBytes / getUidTxBytes により、本アプリのLinux UIDに紐づくカーネルIPネットワーク層（TCP/UDPペイロードおよびトランスポートヘッダーを含む）の送受信バイト数を1秒周期で差分計測しています。端末再起動時にOS起動累計値はリセットされ、ローカルファイル（content://）再生時は原則 0 B/s となります。"
        )
    }

    private fun readUidRx(): Long = runCatching { TrafficStats.getUidRxBytes(appUid) }.getOrDefault(-1L)
    private fun readUidTx(): Long = runCatching { TrafficStats.getUidTxBytes(appUid) }.getOrDefault(-1L)

    companion object {
        const val MAX_HISTORY_POINTS = 60

        fun classifyPlaybackOrigin(uri: Uri?): PlaybackDataOrigin {
            if (uri == null) return PlaybackDataOrigin.IDLE
            return when (uri.scheme?.lowercase()) {
                "http", "https", "rtsp", "rtmp" -> PlaybackDataOrigin.NETWORK_STREAM
                else -> PlaybackDataOrigin.LOCAL_STORAGE
            }
        }
    }

    private fun createInitialSnapshot(): NetworkTrafficSnapshot {
        return NetworkTrafficSnapshot(
            isTrafficStatsSupported = true,
            playbackOrigin = PlaybackDataOrigin.IDLE,
            uidTotalRxBytesSinceBoot = sessionBaselineRx,
            uidTotalTxBytesSinceBoot = sessionBaselineTx,
            sessionRxBytes = 0L,
            sessionTxBytes = 0L,
            currentRxBytesPerSec = 0L,
            currentTxBytesPerSec = 0L,
            peakRxBytesPerSec = 0L,
            historySamples = emptyList(),
            isBluetoothOutputActive = false,
            actualBluetoothBytesStatus = "Bluetooth音声未出力",
            estimatedUncompressedPcmBitrateKbps = null,
            bluetoothEstimationNote = "",
            countingScopeExplanationJp = "Android公式API TrafficStats.getUidRxBytes / getUidTxBytes によるUID単位のIP通信量実測値です。"
        )
    }
}
