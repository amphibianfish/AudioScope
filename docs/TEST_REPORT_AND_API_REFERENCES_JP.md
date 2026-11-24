# AudioScope テスト結果報告書 ＆ Android 公式 API 参照資料

検証日：2026-11-21

## 1. テスト結果一覧（検証済み項目と未検証項目の明確な区分）

要件 10 および 12（「テストを実行していないのに成功と報告しないこと」「実機でしか検証できない項目や環境制限による未検証項目は『未検証』と明記すること」）に基づき、開発サンドボックス環境で実際に実行・パスした検証と、Android SDK / 実機環境で実行すべき未検証項目を厳密に区分して報告します。

| # | テスト項目 | ステータス | 実施内容・検証結果・未検証理由 |
| :- | :--- | :--- | :--- |
| 1 | Gradle ビルド (`assembleDebug`) | **未検証（スクリプト同梱）** | 開発用 Linux VM がオフライン隔離環境かつ Android SDK (`aapt2` / `d8` / `android.jar`) 非搭載のため VM 内では未実行。AGP `9.5.1` + Gradle `9.6.0` の `libs.versions.toml` 全36ライブラリ・4プラグイン参照整合性を静的検証済み。Windows 11 で `build_apk_windows.bat` により自動実行可能。 |
| 2 | Kotlin コンパイル確認 | **静的構文・シンボル監査済み / 実コンパイラ未検証** | VM 内に `kotlinc` 非搭載のため実コンパイラ実行は未検証。代替として全 40 ファイル（1,238 シンボル）の括弧・文字列リテラル整合性、内部 `import` 完全解決、AGP 9 Built-in Kotlin 規約、Media3 `1.11.1` の `DefaultRenderersFactory.buildAudioSink` 3引数署名および `androidx.media3.common.audio.BaseAudioProcessor` 型署名を公式ソースと照合しエラー 0 件を確認。 |
| 3 | ユニットテスト | **アルゴリズム実測検証済み（JUnit4コード同梱）** | `BinaryAudioHeaderParserTest.kt`、`FftAnalyzerTest.kt`、`TrafficAndRouteTruthfulnessTest.kt` を実装。さらに VM 内で同一ロジックの検証ハーネスを実行し全アサーション PASS を確認（Windows 11 側では `gradlew.bat testDebugUnitTest` で自動実行）。 |
| 4 | UI テスト | **テストコード実装済み / 実機・エミュレーター未検証** | `AudioScopeUiAndServiceTest.kt`（再生画面6要素表示、音質詳細画面 `SOURCE`/`OUTPUT`/`NETWORK` 表示検証）を実装済み。エミュレーター非搭載環境のため実行自体は未検証。 |
| 5 | 音楽ライブラリ読み込みテスト | **クエリ設計検証済み / 実機未検証** | `MediaStore.getExternalVolumeNames` + SAF フォールバックを実装し、拡張子分類・形式別統計ロジックを検証済み。Galaxy S26 実機ストレージでの `ContentResolver` 実行は未検証。 |
| 6 | MP3・FLAC・WAV 等の情報取得テスト | **実バイナリ検証済み (PASS)** | VM 内で `ffmpeg` により生成した計 11 種の実音声ファイル（96kHz/24bit FLAC、44.1kHz/16bit FLAC、192kHz/24bit EXTENSIBLE WAV、48kHz/16bit WAV、320kbps CBR `Info` MP3、VBR `Xing` MP3、48kHz AAC M4A、96kHz/24bit ALAC M4A [1.49MB 末尾 `moov` 含む]、Vorbis OGG、Opus）および破損4種を解析し、`ffprobe` 真値と 100% 一致を確認。また 96kHz/24bit FLAC の 30kHz 超高域信号に対し FFT 帯域エネルギー `-21.08 dBFS`（理論値 `-21.0 dBFS`）の実測一致を確認。 |
| 7 | 再生・一時停止・シークテスト | **未検証（実機/エミュレーター項目）** | Media3 `ExoPlayer` + `MediaController` による再生・一時停止・ミリ秒シーク・ギャップレス遷移を実装済み。実機オーディオ出力での再生確認は未検証。 |
| 8 | Bluetooth 接続・切断処理のテスト | **分類ロジック検証済み / 実機未検証** | `AudioDeviceInfo` 定数マッピング、OS制限時の非偽装表示、`setHandleAudioBecomingNoisy(true)` による切断時自動一時停止を実装。Galaxy S26 + 実機 Bluetooth イヤホンでの接続・切断挙動は未検証。 |
| 9 | 画面回転・バックグラウンド再生テスト | **AndroidManifest・FGS構成検証済み / 実機未検証** | 画面向き固定なしの Edge-to-Edge Compose 構成と Android 17 準拠 `mediaPlayback` `MediaSessionService` を実装し XML 整合性を確認済み。Galaxy S26 One UI 9 上での実動作は未検証。 |
| 10 | 通信量表示の検証 | **単位変換・境界値検証済み / 実機未検証** | `ByteUnitFormatter` (`B` / `KB` / `MB` / `GB` / `-1L UNSUPPORTED`) およびローカル/HTTPS 判定ロジックをテスト検証済み。実機 `TrafficStats` カウンターでのストリーミング実測は未検証。 |
| 11 | 権限拒否時の動作確認 | **コード経路検証済み / 実機未検証** | `READ_MEDIA_AUDIO` および `BLUETOOTH_CONNECT` 拒否時にクラッシュせず、SAF 個別ファイル/フォルダー選択および `AudioDeviceInfo` 範囲のみで動作継続する分岐を実装済み。実機ダイアログ操作は未検証。 |
| 12 | メモリとリソースの解放確認 | **コード監査済み / 長時間実機未検証** | `MediaExtractor.release()` / `MediaMetadataRetriever.release()` の `finally` 保証、`AudioScopePlaybackService.onDestroy()` での `player.release()` & `mediaSession.release()`、バックグラウンド移行時の FFT・TrafficStats ポーリング自動停止を実装・監査済み。長時間実機プロファイリングは未検証。 |

## 2. Android 公式 API・一次技術資料リファレンス

本アプリの設計・バージョン選定・API 境界定義において参照・準拠した公式ドキュメント一覧です：

- **Android 17 (API レベル 37) 仕様・バックグラウンド音声制限**
  - [動作の変更点: Android 17 以上をターゲットとするアプリ | Android Developers](https://developer.android.com/about/versions/17/behavior-changes-17?hl=ja)
  - [バックグラウンド音声の強化 (Background audio hardening) | Android Developers](https://developer.android.com/about/versions/17/changes/bg-audio?hl=ja)
  - [`<uses-sdk>` API レベル対応表 (Android 17 = API 37) | Android Developers](https://developer.android.com/guide/topics/manifest/uses-sdk-element?hl=ja)
- **対象端末リファレンス**
  - [Samsung Galaxy S26 SC-51G 製品・ディスプレイ仕様 | NTTドコモ](https://www.onlineshop.docomobusiness.ntt.com/service/mobile/product/sc51g)
- **ビルドシステム・Kotlin・Jetpack 安定版リリースノート**
  - [Android Gradle Plugin 9.5.0 / 9.5.1 リリースノート (API 37 対応・Gradle 9.6.0) | Android Developers](https://developer.android.com/build/releases/agp-9-5-0-release-notes)
  - [組み込みの Kotlin (Built-in Kotlin) に移行する | Android Developers](https://developer.android.com/build/migrate-to-built-in-kotlin)
  - [Getting started with KSP | Kotlin Documentation](https://kotlinlang.org/docs/ksp-quickstart.html)
  - [What's new in the Jetpack Compose November '26 release (BOM 2026.11.01) | Android Developers](https://developer.android.com/blog/posts/jetpack-compose-november-2026-release)
  - [Media3 リリースノート (Stable 1.11.1) | Android Developers](https://developer.android.com/jetpack/androidx/releases/media3)
  - [Room リリースノート (Stable 2.8.5) | Android Developers](https://developer.android.com/jetpack/androidx/releases/room)
  - [DataStore リリースノート (Stable 1.3.0) | Android Developers](https://developer.android.com/jetpack/androidx/releases/datastore)
  - [Navigation リリースノート (Stable 2.10.2) | Android Developers](https://developer.android.com/jetpack/androidx/releases/navigation)
  - [Lifecycle リリースノート (Stable 2.11.1) | Android Developers](https://developer.android.com/jetpack/androidx/releases/lifecycle)
  - [Core KTX リリースノート (Stable 1.19.1) | Android Developers](https://developer.android.com/jetpack/androidx/releases/core)
- **メディア再生・ストレージ・オーディオ出力・Bluetooth・通信量 API**
  - [MediaSessionService によるバックグラウンド再生 | Android Developers](https://developer.android.com/media/media3/session/background-playback?hl=ja)
  - [BaseAudioProcessor | AndroidX Media3 API Reference](https://developer.android.com/reference/kotlin/androidx/media3/common/audio/BaseAudioProcessor)
  - [共有ストレージからメディア ファイルにアクセスする (`MediaStore.Audio` / `READ_MEDIA_AUDIO`) | Android Developers](https://developer.android.com/training/data-storage/shared/media?hl=ja)
  - [AudioDeviceInfo | Android API Reference](https://developer.android.com/reference/android/media/AudioDeviceInfo)
  - [AudioMixerAttributes (USB ビットパーフェクト属性) | Android API Reference](https://developer.android.com/reference/android/media/AudioMixerAttributes)
  - [オーディオ再生を改善 (`getSupportedMixerAttributes`) | Android Developers](https://developer.android.com/media/platform/improve-audio-playback?hl=ja)
  - [BluetoothCodecStatus | Android API Reference](https://developer.android.com/reference/android/bluetooth/BluetoothCodecStatus)
  - [Bluetooth の概要 | Android Developers](https://developer.android.com/develop/connectivity/bluetooth?hl=ja)
  - [TrafficStats (`getUidRxBytes` / `getUidTxBytes`) | Android API Reference](https://developer.android.com/reference/android/net/TrafficStats)
