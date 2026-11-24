$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ApkPath = Join-Path $ProjectRoot "dist\AudioScope-v1.0.0-debug.apk"

if (-not (Test-Path $ApkPath)) {
    Write-Host "[情報] dist\AudioScope-v1.0.0-debug.apk が未生成のため、先にビルドを実行します..." -ForegroundColor Yellow
    & "$PSScriptRoot\build_apk_windows.ps1"
}

$AdbCandidates = @(
    "adb.exe",
    (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
    (Join-Path $ProjectRoot ".toolchain\android-sdk\platform-tools\adb.exe")
)
$AdbExe = $AdbCandidates | Where-Object { Get-Command $_ -ErrorAction SilentlyContinue } | Select-Object -First 1
if (-not $AdbExe) {
    Write-Host "[エラー] adb.exe が見つかりません。スマホへ dist\AudioScope-v1.0.0-debug.apk を直接コピーしてタップでもインストール可能です。" -ForegroundColor Red
    exit 1
}

Write-Host "[ADB] 接続中の Android 端末 (Galaxy S26 SC-51G 等) を確認しています..." -ForegroundColor Cyan
& $AdbExe devices
& $AdbExe install -r $ApkPath
if ($LASTEXITCODE -eq 0) {
    Write-Host "[完了] AudioScope のインストールが完了しました！アプリの起動を試みます..." -ForegroundColor Green
    & $AdbExe shell am start -n "jp.audioscope.player.debug/jp.audioscope.player.ui.MainActivity"
} else {
    Write-Host "[注意] ADBインストールに失敗しました。Galaxy S26 の「設定 → 開発者向けオプション → USBデバッグ」がONで、画面上の許可ダイアログを承認しているか確認してください。" -ForegroundColor Yellow
}
