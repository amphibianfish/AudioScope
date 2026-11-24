# AudioScope v1.0.0 — Android 17 高音質・音質可視化音楽プレーヤー

**AudioScope** は、Samsung Galaxy S26（`SC-51G` / Android 17 [API 37] / One UI 9）での利用に最適化された、音質情報の正確な実測表示と使いやすさを両立する完全オリジナルの Android 音楽プレーヤーです。

Sony Xperia 標準ミュージックアプリのような「音楽中心で迷わないシンプルな導線」を参考にしつつ、独自設計のラグジュアリー・ダーク UI（Jetpack Compose + Material 3）と、**推定値・ダミー値を一切使わないバイナリストリーム直接解析・出力経路診断・通信量モニター**を搭載しています。

## 特長と設計ポリシー

1. **SOURCE（元ファイル実測解析）**: `BinaryAudioHeaderParser` により FLAC (`STREAMINFO` / `VORBIS_COMMENT` / 128-bit MD5)、WAV (`RIFF` / `RF64` / `WAVEFORMATEXTENSIBLE`)、MP3 (`ID3v2` / `Xing` / `Info` / `LAME` VBR方式 / `VBRI` / 120フレーム実測走査)、MP4/M4A (`AAC-LC` / `HE-AAC` / `ALAC` 先頭・末尾 `moov` 両対応)、ADTS AAC、OGG (`Vorbis` / `Opus`) を直接解析し、`CBR` と `VBR`、実測値とヘッダー公称値、純音声データサイズとファイル全体サイズを厳密に区別します。
2. **OUTPUT（出力経路・Bluetooth・USB DAC 診断）**: [Android 公開 API](https://developer.android.com/reference/android/media/AudioDeviceInfo) のみを使用し、非公開 API や `@SystemApi`（`BLUETOOTH_PRIVILEGED` 権限が必要な `BluetoothA2dp.getCodecStatus()` 等）で保護された実使用 Bluetooth コーデック・リンク速度は偽装せず **「取得不可（OSによる制限）」** と明示し、ワンタップで Android の「開発者向けオプション」を開く導線を提供します。また USB DAC 接続時は Android 14〜17 の [`AudioMixerAttributes`](https://developer.android.com/reference/android/media/AudioMixerAttributes) による `MIXER_BEHAVIOR_BIT_PERFECT` 状態検査と優先要求に対応します。
3. **NETWORK（通信量・転送速度モニター）**: [`TrafficStats.getUidRxBytes` / `getUidTxBytes`](https://developer.android.com/reference/android/net/TrafficStats) による 1 秒周期のリアルタイム送受信速度・累積通信量（`B / KB / MB / GB` 自動切替）・直近 60 秒グラフを表示します。Bluetooth の実送信パケット数が公開 API で取得できない事実を明記し、AudioTrack 入力仕様からの計算値には必ず **「【推定・PCM理論値】」** と明記します。
4. **Android 17 バックグラウンド音声強化への準拠**: [Android 17 のバックグラウンド音声の強化](https://developer.android.com/about/versions/17/changes/bg-audio?hl=ja)（WIU 機能を持つ `mediaPlayback` Foreground Service 要件）に対し、[Jetpack Media3 `MediaSessionService`](https://developer.android.com/media/media3/session/background-playback?hl=ja) と `MediaButtonReceiver` で準拠しています。
5. **マイク権限不要・無改変の FFT スペクトラム＆波形表示**: `RECORD_AUDIO` 権限を要求せず、Media3 の [`BaseAudioProcessor`](https://developer.android.com/reference/kotlin/androidx/media3/common/audio/BaseAudioProcessor) でデコード済み PCM（16/24/32bit・Float）を 1 ビットも改変せずに透過タップし、2048 点 Hann 窓 FFT と 20 kHz 超ハイレゾ帯域エネルギーをリアルタイム描画します。

## 重要：Debug APK の生成について（実行環境制限の明示）

本開発を実行したクラウド検証環境（ネットワーク遮断 Linux サンドボックス）には Android SDK (`android.jar` / `aapt2` / `d8`) および Gradle / Kotlin コンパイラが導入されていないため、**ZIP 内にビルド済み `.apk` バイナリ自体は同梱していません**。

その代わり、プログラミング初心者の方でも **Windows 11 パソコンでダブルクリック 1 回で Debug APK を自動生成・インストールできるスクリプト** と **PC へのツール導入すら不要な GitHub Actions 自動 APK ビルド設定** を完備しています。

### 方法 A：Windows 11 でワンクリック APK 生成（推奨・初心者向け）

1. 本 ZIP を Windows 11 の任意のフォルダー（例：`C:\AudioScope` ※日本語やスペースを含まないパスを推奨）に展開します。
2. フォルダー内の **`build_apk_windows.bat`** をダブルクリックします。
   - PC 内に Android Studio または JDK 17 / Android SDK があれば自動検出し、未導入の場合はポータブル版 OpenJDK 17（Adoptium Temurin）および Android SDK Command-Line Tools（`platforms;android-37` / `build-tools;36.0.0`）を `.toolchain\` 内に自動取得してビルドを実行します（初回のみインターネット接続が必要です）。
3. 完了すると `dist\AudioScope-v1.0.0-debug.apk` が自動生成され、エクスプローラーで `dist` フォルダーが開きます。
4. Galaxy S26 (`SC-51G`) へインストールするには：
   - **スマホ単体で入れる場合**: `dist\AudioScope-v1.0.0-debug.apk` を USB ケーブルや Quick Share・Google ドライブ経由で Galaxy S26 にコピーし、「マイファイル」アプリからタップしてインストールします（「不明なアプリのインストール」の確認が出た場合は許可します）。
   - **USB デバッグで自動インストールする場合**: Galaxy S26 の USB デバッグを ON にして PC に接続し、**`install_to_galaxy_s26.bat`** をダブルクリックします。

### 方法 B：Android Studio で開いてビルド・実機実行する場合

1. [Android Studio](https://developer.android.com/studio/releases)（2026.2 Rabbit 以降推奨）を起動し、**Open** から展開した `AudioScope` フォルダーを選択します。
2. Gradle Sync が自動完了したら、メニューの **Build > Build App Bundle(s) / APK(s) > Build APK(s)** をクリックすると `app/build/outputs/apk/debug/app-debug.apk` が生成されます。

### 方法 C：GitHub Actions でクラウド自動ビルドする場合（PC への SDK 導入ゼロ）

1. 展開した `AudioScope` フォルダー一式をご自身の GitHub リポジトリへ Push します。
2. `.github/workflows/build-apk.yml` が自動起動し、GitHub のクラウド上でユニットテストと APK ビルドが実行され、**Actions > Artifacts** から `AudioScope-v1.0.0-debug-apk` をダウンロードできます。

## 同梱ドキュメント・テスト音源

- `docs/INSTALL_AND_BUILD_GUIDE_JP.md` — Windows 11 ビルド手順・Galaxy S26 インストール手順詳細
- `docs/USER_MANUAL_AND_FEATURES_JP.md` — 使用方法・実装機能一覧・既知の制限事項
- `docs/TEST_REPORT_AND_API_REFERENCES_JP.md` — テスト結果報告書・Android 公式 API 参照資料一覧
- `sample_test_audio/` — 実機動作確認用のリファレンス音源 4 種（`96kHz/24bit Hi-Res FLAC [30kHz超高域テスト信号入り]`、`48kHz/16bit WAV`、`320kbps CBR MP3`、`VBR Xing MP3`）
