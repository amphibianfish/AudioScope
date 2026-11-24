# AudioScope インストール手順・Windows 11 ビルド完全ガイド

対象端末：**Samsung Galaxy S26 (`SC-51G`) / Android 17 (API 37) / One UI 9**  
ビルド環境：**Windows 11 (64-bit)**

## 1. 事前準備とフォルダ展開の注意点

1. `AudioScope_v1.0.0_Android17_Complete.zip` を右クリックし、**「すべて展開」** を選択します。
2. 展開先パスには Android SDK ツールの仕様上、空白や全角文字を含まない短いパス（例：`C:\AudioScope`）を指定するとトラブルを防げます。
3. 初回ビルド時は Gradle 9.6.0・Android 17 SDK・Jetpack ライブラリを公式サーバーから取得するため、インターネット接続が必要です（約 1.5〜2.5 GB の空き容量を確保してください）。

## 2. Windows 11 での APK 生成手順（ワンクリック方式）

1. 展開した `AudioScope` フォルダ内の **`build_apk_windows.bat`** をダブルクリックします。
2. Windows SmartScreen（「Windows によって PC が保護されました」）が表示された場合は、**「詳細情報」→「実行」** をクリックします。
3. スクリプトが自動で以下の 5 ステップを実行します：
   - **[1/5]** JDK 17 以上の確認（Android Studio が入っていれば同梱 JBR を自動利用、なければポータブル版 OpenJDK 17 を `.toolchain\jdk-17` へ自動ダウンロード）
   - **[2/5]** Android SDK API 37 (`platforms;android-37` / `build-tools;36.0.0`) の確認・自動セットアップと `local.properties` の生成
   - **[3/5]** ユニットテスト (`gradlew.bat testDebugUnitTest`) の実行
   - **[4/5]** Debug APK (`gradlew.bat assembleDebug`) のコンパイル・パッケージング
   - **[5/5]** `dist\AudioScope-v1.0.0-debug.apk` への出力とフォルダ表示

## 3. Samsung Galaxy S26 (SC-51G) へのインストール手順

### 手順 A：APK ファイルを直接コピーしてインストール（一番簡単）

1. Galaxy S26 と Windows 11 PC を USB Type-C ケーブルで接続し、Galaxy S26 画面の通知から USB の用途を **「ファイル転送 / Android Auto」** に設定します。
2. PC のエクスプローラーから Galaxy S26 の `Download` フォルダへ、`dist\AudioScope-v1.0.0-debug.apk`（および動作確認用の `sample_test_audio` フォルダ内の音楽ファイル）をコピーします。
3. Galaxy S26 標準の **「マイファイル」** アプリを開き、「ダウンロード」から `AudioScope-v1.0.0-debug.apk` をタップします。
4. 初回のみ「この提供元の不明なアプリをインストールしますか？」と表示されるため、「設定」をタップして「この提供元のアプリを許可」を ON にし、**「インストール」** をタップします。
   - One UI 9 の「自動ブロッカー（Auto Blocker）」が ON になっている場合は、**設定 → セキュリティおよびプライバシー → 自動ブロッカー** を一時的に OFF にすると自作 Debug APK をインストールできます。

### 手順 B：ADB 経由でワンクリックインストール

1. Galaxy S26 で **設定 → 端末情報 → ソフトウェア情報** を開き、**「ビルド番号」を 7 回連続でタップ** して「開発者向けオプション」を有効化します。
2. **設定 → 開発者向けオプション** を開き、**「USB デバッグ」** を ON にします。
3. USB ケーブルで PC と接続し、Galaxy S26 画面に出る「USB デバッグを許可しますか？」で **「許可」** をタップします。
4. PC 側で **`install_to_galaxy_s26.bat`** をダブルクリックすると、自動でインストールとアプリ起動が完了します。

## 4. トラブルシューティング

- **ウイルス対策ソフトによるブロック**: 初回ビルド時に `.toolchain` 内へ JDK や Android SDK を展開する際、セキュリティソフトがスキャンを行うと数分かかる場合があります。完了までそのままお待ちください。
- **コマンドラインからの手動ビルド**: Android Studio や JDK 17 が導入済みの場合、PowerShell でプロジェクトフォルダを開き `.\gradlew.bat assembleDebug` を実行するだけでも `app\build\outputs\apk\debug\app-debug.apk` を生成できます。
