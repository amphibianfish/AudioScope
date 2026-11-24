package jp.audioscope.player.audio.output

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import jp.audioscope.player.model.ActiveAudioTrackConfig
import jp.audioscope.player.model.AudioOutputSpecs
import jp.audioscope.player.model.AudioSourceSpecs
import jp.audioscope.player.model.BluetoothAudioState
import jp.audioscope.player.model.ObtainableValue
import jp.audioscope.player.model.OutputRouteCategory
import jp.audioscope.player.model.OutputRouteSwitchEvent
import jp.audioscope.player.model.UsbMixerAttributeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 公開APIのみを用いて実際の出力経路・OSミキサー属性・Bluetooth接続状態・USB DACビットパーフェクト可否を検査する。
 * 非公開APIや @SystemApi (BluetoothA2dp.getCodecStatus 等) の不正呼び出しやダミー値の表示を一切行わない。
 */
class AudioOutputInspector(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var a2dpProxy: BluetoothA2dp? = null
    private var latestSourceSpecs: AudioSourceSpecs? = null
    private var latestAudioTrackConfig: ActiveAudioTrackConfig? = null
    private var lastRouteLabel: String? = null
    private val routeSwitchLog = ArrayDeque<OutputRouteSwitchEvent>(25)

    private val _outputSpecs = MutableStateFlow(buildSnapshot(triggerReason = "初期化"))
    val outputSpecs: StateFlow<AudioOutputSpecs> = _outputSpecs.asStateFlow()

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refresh("出力デバイス接続検知")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refresh("出力デバイス切断検知")
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> refresh("Bluetooth A2DP接続状態変化")
                BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED -> refresh("Bluetooth A2DP再生状態変化")
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> refresh("オーディオ出力切断 (Becoming Noisy)")
            }
        }
    }

    private var isStarted = false

    fun startMonitoring() {
        if (isStarted) return
        isStarted = true
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)

        val filter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        }
        ContextCompat.registerReceiver(
            context,
            bluetoothReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        connectBluetoothProxyIfPermitted()
        refresh("監視開始")
    }

    fun stopMonitoring() {
        if (!isStarted) return
        isStarted = false
        runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        runCatching { context.unregisterReceiver(bluetoothReceiver) }
        a2dpProxy?.let { proxy ->
            runCatching { bluetoothManager?.adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy) }
        }
        a2dpProxy = null
    }

    fun onBluetoothPermissionUpdated() {
        connectBluetoothProxyIfPermitted()
        refresh("Bluetooth権限状態更新")
    }

    fun updatePlaybackContext(
        sourceSpecs: AudioSourceSpecs?,
        audioTrackConfig: ActiveAudioTrackConfig?
    ) {
        latestSourceSpecs = sourceSpecs
        latestAudioTrackConfig = audioTrackConfig
        refresh("再生ストリーム更新")
    }

    /**
     * Android 14+ (API 34-37) の公開API AudioManager.setPreferredMixerAttributes を用いて
     * 対応USB DACに対するビットパーフェクト出力要求を切り替え、実際の適用結果を検証する。
     */
    fun applyUsbBitPerfectPreference(enable: Boolean, sourceSampleRateHz: Int?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return "Android 14未満のため AudioMixerAttributes API は非対応です。"
        }
        val usbDevice = findActivePrimaryOutputDevice()?.takeIf { isUsbDeviceType(it.type) }
            ?: return "現在USB DAC/USBオーディオ機器が接続されていません。"

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        return try {
            if (!enable) {
                audioManager.clearPreferredMixerAttributes(attrs, usbDevice)
                refresh("USBビットパーフェクト優先解除")
                "USBミキサー優先設定を解除し、標準OSミキサーへ戻しました。"
            } else {
                val supported = audioManager.getSupportedMixerAttributes(usbDevice)
                val bitPerfectCandidates = supported.filter {
                    it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                }
                if (bitPerfectCandidates.isEmpty()) {
                    refresh("USBビットパーフェクト非対応確認")
                    return "接続中のUSB機器または端末HALは MIXER_BEHAVIOR_BIT_PERFECT をサポートしていません。"
                }
                val chosen = bitPerfectCandidates.firstOrNull {
                    sourceSampleRateHz != null && it.format.sampleRate == sourceSampleRateHz
                } ?: bitPerfectCandidates.first()

                val ok = audioManager.setPreferredMixerAttributes(attrs, usbDevice, chosen)
                refresh("USBビットパーフェクト設定要求")
                if (ok) {
                    "USBビットパーフェクト属性 (${chosen.format.sampleRate} Hz) をシステムへ要求しました。"
                } else {
                    "OSによりUSBビットパーフェクト属性の適用が拒否されました。"
                }
            }
        } catch (t: Throwable) {
            "USBミキサー設定中にエラーが発生しました: ${t.javaClass.simpleName}"
        }
    }

    fun refresh(triggerReason: String = "手動更新") {
        _outputSpecs.value = buildSnapshot(triggerReason)
    }

    private fun connectBluetoothProxyIfPermitted() {
        if (!hasBluetoothConnectPermission() || a2dpProxy != null) return
        val adapter: BluetoothAdapter = bluetoothManager?.adapter ?: return
        runCatching {
            adapter.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dpProxy = proxy as? BluetoothA2dp
                            refresh("Bluetooth A2DPプロファイル接続")
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        if (profile == BluetoothProfile.A2DP) {
                            a2dpProxy = null
                            refresh("Bluetooth A2DPプロファイル切断")
                        }
                    }
                },
                BluetoothProfile.A2DP
            )
        }
    }

    private fun findActivePrimaryOutputDevice(): AudioDeviceInfo? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
        if (outputs.isEmpty()) return null
        // 優先度: USB DAC > Bluetooth LE Audio / A2DP > 有線ヘッドホン > 外部HDMI > 内蔵スピーカー
        return outputs.minByOrNull { routePriority(it.type) }
    }

    private fun buildSnapshot(triggerReason: String): AudioOutputSpecs {
        val primaryDevice = findActivePrimaryOutputDevice()
        val routeCat = classifyRouteCategory(primaryDevice?.type)
        val typeConstName = describeDeviceTypeConstant(primaryDevice?.type)
        val productName = primaryDevice?.productName?.toString()?.takeIf { it.isNotBlank() }
            ?: routeCat.labelJp

        val currentRouteSummary = "${routeCat.labelJp} ($productName)"
        if (lastRouteLabel != null && lastRouteLabel != currentRouteSummary) {
            if (routeSwitchLog.size >= 20) routeSwitchLog.removeLast()
            routeSwitchLog.addFirst(
                OutputRouteSwitchEvent(
                    timestampEpochMs = System.currentTimeMillis(),
                    previousRouteLabel = lastRouteLabel!!,
                    currentRouteLabel = currentRouteSummary,
                    triggerReason = triggerReason
                )
            )
        }
        lastRouteLabel = currentRouteSummary

        val osSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        val osFramesPerBuf = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()

        val reportedRates = primaryDevice?.sampleRates?.toList()?.sorted() ?: emptyList()
        val reportedEncodings = primaryDevice?.encodings?.map { describeAudioFormatEncoding(it) }?.distinct() ?: emptyList()
        val reportedChannels = primaryDevice?.channelCounts?.toList()?.sorted() ?: emptyList()

        val usbMixerState = if (primaryDevice != null && isUsbDeviceType(primaryDevice.type)) {
            inspectUsbMixerAttributes(primaryDevice)
        } else null

        val btState = inspectBluetoothState(
            routeCategory = routeCat,
            primaryDevice = primaryDevice,
            reportedRates = reportedRates,
            reportedEncodings = reportedEncodings,
            reportedChannels = reportedChannels
        )

        val isBitPerfect = usbMixerState?.usbBitPerfectCurrentlyActive == true
        val bitPerfectExplanation = when {
            isBitPerfect ->
                "有効：Android 14+ AudioMixerAttributes (MIXER_BEHAVIOR_BIT_PERFECT) がUSB DACに対してアクティブです。OSミキサーと音量処理をバイパスしています。"
            routeCat == OutputRouteCategory.BLUETOOTH_A2DP || routeCat == OutputRouteCategory.BLUETOOTH_LE_AUDIO || routeCat == OutputRouteCategory.BLUETOOTH_SCO ->
                "非成立（Bluetoothワイヤレス伝送）：Bluetooth出力ではOSオーディオスタックおよびワイヤレスコーデック再符号化を経由するため、元ファイルのビットパーフェクト伝送にはなりません。"
            routeCat == OutputRouteCategory.USB_DAC_AUDIO ->
                "非有効（OSミキサー経由）：USB DACへ出力中ですが MIXER_BEHAVIOR_BIT_PERFECT が未適用または機器非対応のため、Androidシステムミキサーを経由しています。"
            else ->
                "非有効（Android標準ミキサー経由）：内蔵スピーカー・アナログ有線経路ではAndroid AudioFlingerの共通ミキサー（ネイティブ ${osSampleRate ?: 48000} Hz）を経由して出力されます。"
        }

        val srcSr = latestSourceSpecs?.sampleRateHz
        val trackSr = latestAudioTrackConfig?.sampleRateHz
        val resamplingDiag = buildString {
            if (srcSr != null) append("元ファイル: ${srcSr} Hz") else append("元ファイル: 未選択")
            append(" → ")
            if (trackSr != null) append("AudioTrack入力: ${trackSr} Hz (${latestAudioTrackConfig?.pcmEncodingLabel})")
            else append("AudioTrack: 待機中")
            append(" → ")
            if (isBitPerfect) {
                append("USB Bit-Perfect直接出力")
            } else {
                append("OSミキサー基準: ${osSampleRate ?: 48000} Hz")
                if (srcSr != null && osSampleRate != null && srcSr != osSampleRate) {
                    append("（※サンプルレート変換が発生している可能性あり）")
                }
            }
        }

        val capSummary = buildString {
            append("対応サンプルレート通知値: ")
            append(if (reportedRates.isEmpty()) "任意（OSミキサー管理）" else reportedRates.joinToString(", ") { "${it}Hz" })
            append(" / 対応エンコーディング通知値: ")
            append(if (reportedEncodings.isEmpty()) "OS標準" else reportedEncodings.joinToString(", "))
        }

        return AudioOutputSpecs(
            routeCategory = routeCat,
            activeDeviceProductName = productName,
            androidDeviceTypeConstantName = typeConstName,
            osNativeOutputSampleRateHz = osSampleRate,
            osFramesPerBuffer = osFramesPerBuf,
            activeAudioTrackConfig = latestAudioTrackConfig,
            isBitPerfectGuaranteed = isBitPerfect,
            bitPerfectStatusExplanation = bitPerfectExplanation,
            resamplingDiagnostic = resamplingDiag,
            bluetoothState = btState,
            usbMixerState = usbMixerState,
            deviceCapabilitiesSummary = capSummary,
            routeHistory = routeSwitchLog.toList()
        )
    }

    @SuppressLint("MissingPermission")
    private fun inspectBluetoothState(
        routeCategory: OutputRouteCategory,
        primaryDevice: AudioDeviceInfo?,
        reportedRates: List<Int>,
        reportedEncodings: List<String>,
        reportedChannels: List<Int>
    ): BluetoothAudioState {
        val isBtRoute = routeCategory == OutputRouteCategory.BLUETOOTH_A2DP ||
            routeCategory == OutputRouteCategory.BLUETOOTH_LE_AUDIO ||
            routeCategory == OutputRouteCategory.BLUETOOTH_SCO

        val transportMethod = when (routeCategory) {
            OutputRouteCategory.BLUETOOTH_A2DP -> "Bluetooth Classic Audio (A2DP)"
            OutputRouteCategory.BLUETOOTH_LE_AUDIO -> "Bluetooth LE Audio (BLE Unicast / Broadcast)"
            OutputRouteCategory.BLUETOOTH_SCO -> "Bluetooth Hands-Free / SCO"
            else -> "Bluetooth未出力（現在の再生先: ${routeCategory.labelJp}）"
        }

        val hasBtPerm = hasBluetoothConnectPermission()
        var connectedBtDevice: BluetoothDevice? = null
        var a2dpPlaying: ObtainableValue<Boolean> = ObtainableValue.NotApplicable("A2DPプロファイル未使用")
        var connStateLabel = if (isBtRoute) "接続中（AudioManager経路検出）" else "未接続または待機中"

        if (hasBtPerm) {
            val proxy = a2dpProxy
            if (proxy != null) {
                runCatching {
                    val devices = proxy.connectedDevices
                    connectedBtDevice = devices.firstOrNull()
                    if (connectedBtDevice != null) {
                        val st = proxy.getConnectionState(connectedBtDevice)
                        connStateLabel = when (st) {
                            BluetoothProfile.STATE_CONNECTED -> "接続済み (A2DP STATE_CONNECTED)"
                            BluetoothProfile.STATE_CONNECTING -> "接続処理中"
                            BluetoothProfile.STATE_DISCONNECTING -> "切断処理中"
                            else -> "未接続"
                        }
                        a2dpPlaying = ObtainableValue.Available(
                            proxy.isA2dpPlaying(connectedBtDevice),
                            "BluetoothA2dp.isA2dpPlaying()"
                        )
                    } else if (!isBtRoute) {
                        connStateLabel = "A2DP接続機器なし"
                    }
                }
            }
        }

        val deviceNameVal: ObtainableValue<String> = when {
            !isBtRoute && connectedBtDevice == null ->
                ObtainableValue.NotApplicable("現在Bluetoothオーディオ機器へ出力していません")
            hasBtPerm && connectedBtDevice != null -> {
                val nm = runCatching { connectedBtDevice!!.name }.getOrNull()
                    ?: primaryDevice?.productName?.toString()
                    ?: "名称不明のBluetooth機器"
                ObtainableValue.Available(nm, "BluetoothDevice.getName() / AudioDeviceInfo")
            }
            !hasBtPerm && isBtRoute -> {
                val fallbackProduct = primaryDevice?.productName?.toString()?.takeIf { it.isNotBlank() }
                if (fallbackProduct != null) {
                    ObtainableValue.Available(
                        "$fallbackProduct（※詳細プロファイル取得にはBLUETOOTH_CONNECT権限が必要）",
                        "AudioDeviceInfo.getProductName()"
                    )
                } else {
                    ObtainableValue.PermissionRequired(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        "「付近のデバイス」権限が未許可のためBluetooth機器名を取得できません。"
                    )
                }
            }
            isBtRoute && primaryDevice != null ->
                ObtainableValue.Available(
                    primaryDevice.productName?.toString()?.ifBlank { "Bluetoothオーディオ機器" } ?: "Bluetoothオーディオ機器",
                    "AudioDeviceInfo.getProductName()"
                )
            else -> ObtainableValue.NotApplicable("Bluetooth未接続")
        }

        val devOptGuide = "Galaxy S26 (One UI 9 / Android 17) の「設定 → 開発者向けオプション → Bluetoothオーディオコーデック / サンプリング周波数 / ビット深度 / LDAC再生音質」から実際のネゴシエーション値を確認できます。"

        return if (!isBtRoute) {
            BluetoothAudioState(
                isBluetoothRouteActive = false,
                profileDeviceName = deviceNameVal,
                connectionStateLabel = connStateLabel,
                isA2dpStreamingPlaying = a2dpPlaying,
                bluetoothTransportMethod = transportMethod,
                activeCodec = ObtainableValue.NotApplicable("現在Bluetooth出力ではありません"),
                activeCodecSampleRate = ObtainableValue.NotApplicable("現在Bluetooth出力ではありません"),
                activeCodecChannelMode = ObtainableValue.NotApplicable("現在Bluetooth出力ではありません"),
                activeCodecBitrate = ObtainableValue.NotApplicable("現在Bluetooth出力ではありません"),
                linkQualityInfo = ObtainableValue.NotApplicable("現在Bluetooth出力ではありません"),
                deviceReportedSampleRateCapabilitiesHz = emptyList(),
                deviceReportedEncodingCapabilities = emptyList(),
                deviceReportedChannelCounts = emptyList()
            )
        } else {
            val codecRestrictionMsg = if (routeCategory == OutputRouteCategory.BLUETOOTH_LE_AUDIO) {
                "取得不可（OSによる制限：LE Audio経路であることは公開APIで判別できますが、使用中コーデック[LC3等]を返す getCodecStatus() は @SystemApi / BLUETOOTH_PRIVILEGED 権限限定のため一般アプリからは取得できません）"
            } else {
                "取得不可（OSによる制限：BluetoothA2dp.getCodecStatus() はシステム署名権限 BLUETOOTH_PRIVILEGED が必要な @SystemApi のため、一般アプリからは SBC/AAC/aptX/LDAC 等の実使用コーデックを取得できません）"
            }

            BluetoothAudioState(
                isBluetoothRouteActive = true,
                profileDeviceName = deviceNameVal,
                connectionStateLabel = connStateLabel,
                isA2dpStreamingPlaying = a2dpPlaying,
                bluetoothTransportMethod = transportMethod,
                activeCodec = ObtainableValue.RestrictedByOs(codecRestrictionMsg, devOptGuide),
                activeCodecSampleRate = ObtainableValue.RestrictedByOs(
                    "取得不可（OSによる制限：実使用中のBluetoothサンプルレートは @SystemApi 限定です。下の「機器対応レート一覧」は機器の対応能力であり実使用値ではありません）",
                    devOptGuide
                ),
                activeCodecChannelMode = ObtainableValue.RestrictedByOs(
                    "取得不可（OSによる制限：実使用中のBluetoothチャンネルモード[Mono/Stereo/Joint Stereo]は公開APIで取得できません）",
                    devOptGuide
                ),
                activeCodecBitrate = ObtainableValue.RestrictedByOs(
                    "取得不可（OSによる制限：LDAC 990/660/330kbps や aptX Adaptive 等のリアルタイム符号化ビットレートは公開APIに存在しません）",
                    devOptGuide
                ),
                linkQualityInfo = ObtainableValue.RestrictedByOs(
                    "取得不可（OSによる制限：A2DP/LE Audio接続中のリアルタイムRSSI・パケット再送率・バッファアンダーラン数は一般アプリに非公開です）",
                    devOptGuide
                ),
                deviceReportedSampleRateCapabilitiesHz = reportedRates,
                deviceReportedEncodingCapabilities = reportedEncodings,
                deviceReportedChannelCounts = reportedChannels
            )
        }
    }

    private fun inspectUsbMixerAttributes(usbDevice: AudioDeviceInfo): UsbMixerAttributeState? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return UsbMixerAttributeState(
                usbBitPerfectSupportedByDevice = false,
                usbBitPerfectCurrentlyActive = false,
                supportedBitPerfectFormats = emptyList(),
                activeMixerBehaviorLabel = "MIXER_BEHAVIOR_DEFAULT (Android 14未満)"
            )
        }
        return runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val supported = audioManager.getSupportedMixerAttributes(usbDevice)
            val bpList = supported.filter {
                it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            }.map {
                "${it.format.sampleRate} Hz / ${describeAudioFormatEncoding(it.format.encoding)}"
            }.distinct()

            val preferred = audioManager.getPreferredMixerAttributes(attrs, usbDevice)
            val isBpActive = preferred?.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
            val behaviorLabel = if (isBpActive) {
                "MIXER_BEHAVIOR_BIT_PERFECT (${preferred?.format?.sampleRate} Hz / ${describeAudioFormatEncoding(preferred?.format?.encoding ?: 0)})"
            } else {
                "MIXER_BEHAVIOR_DEFAULT（Android標準ミキサー経由）"
            }
            UsbMixerAttributeState(
                usbBitPerfectSupportedByDevice = bpList.isNotEmpty(),
                usbBitPerfectCurrentlyActive = isBpActive,
                supportedBitPerfectFormats = bpList,
                activeMixerBehaviorLabel = behaviorLabel
            )
        }.getOrNull()
    }

    fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    companion object {
        fun classifyRouteCategory(deviceType: Int?): OutputRouteCategory {
            if (deviceType == null) return OutputRouteCategory.BUILTIN_SPEAKER
            return when (deviceType) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> OutputRouteCategory.BLUETOOTH_A2DP
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputRouteCategory.BLUETOOTH_LE_AUDIO
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> OutputRouteCategory.BLUETOOTH_SCO
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputRouteCategory.USB_DAC_AUDIO
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
                AudioDeviceInfo.TYPE_AUX_LINE -> OutputRouteCategory.WIRED_HEADSET_OR_LINE
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> OutputRouteCategory.BUILTIN_SPEAKER
                else -> OutputRouteCategory.OTHER_EXTERNAL
            }
        }

        private fun isUsbDeviceType(type: Int): Boolean =
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                type == AudioDeviceInfo.TYPE_USB_ACCESSORY

        private fun routePriority(type: Int): Int = when (type) {
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 1
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> 2
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 3
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> 4
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> 5
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 6
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 9
            else -> 10
        }

        fun describeDeviceTypeConstant(type: Int?): String = when (type) {
            null -> "DEFAULT_OUTPUT"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "TYPE_BUILTIN_SPEAKER"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "TYPE_WIRED_HEADPHONES"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "TYPE_WIRED_HEADSET"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "TYPE_BLUETOOTH_A2DP"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "TYPE_BLUETOOTH_SCO"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "TYPE_BLE_HEADSET"
            AudioDeviceInfo.TYPE_BLE_SPEAKER -> "TYPE_BLE_SPEAKER"
            AudioDeviceInfo.TYPE_BLE_BROADCAST -> "TYPE_BLE_BROADCAST"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "TYPE_USB_HEADSET"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "TYPE_USB_DEVICE"
            AudioDeviceInfo.TYPE_HDMI -> "TYPE_HDMI"
            AudioDeviceInfo.TYPE_HDMI_EARC -> "TYPE_HDMI_EARC"
            else -> "TYPE_${type}"
        }

        fun describeAudioFormatEncoding(encoding: Int): String = when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit Packed"
            AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM 32-bit Float"
            AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
            AudioFormat.ENCODING_AAC_LC -> "AAC-LC"
            AudioFormat.ENCODING_MP3 -> "MP3"
            AudioFormat.ENCODING_OPUS -> "Opus"
            else -> "Encoding($encoding)"
        }
    }
}
