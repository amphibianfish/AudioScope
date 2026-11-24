$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $ProjectRoot

Write-Host "[1/5] JDK 17+ の検出を確認しています..." -ForegroundColor Cyan

$StudioJbr = "C:\Program Files\Android\Android Studio\jbr"
$LocalJdkDir = Join-Path $ProjectRoot ".toolchain\jdk-17"

function Test-Jdk17($JavaHomeCandidate) {
    if ([string]::IsNullOrWhiteSpace($JavaHomeCandidate)) { return $false }
    $JavacPath = Join-Path $JavaHomeCandidate "bin\javac.exe"
    return (Test-Path $JavacPath)
}

if (Test-Jdk17 $env:JAVA_HOME) {
    Write-Host "  -> 既存の JAVA_HOME を使用します: $env:JAVA_HOME" -ForegroundColor Green
} elseif (Test-Jdk17 $StudioJbr) {
    $env:JAVA_HOME = $StudioJbr
    Write-Host "  -> Android Studio 同梱 JBR を検出しました: $StudioJbr" -ForegroundColor Green
} else {
    $CachedJdk = Get-ChildItem -Path $LocalJdkDir -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($CachedJdk -and (Test-Jdk17 $CachedJdk.FullName)) {
        $env:JAVA_HOME = $CachedJdk.FullName
        Write-Host "  -> ローカルキャッシュのポータブル JDK 17 を使用します: $($env:JAVA_HOME)" -ForegroundColor Green
    } else {
        Write-Host "  -> JDK 17 が未導入のため、Eclipse Adoptium Temurin OpenJDK 17 (ポータブルZIP) を .toolchain へ自動取得します..." -ForegroundColor Yellow
        New-Item -ItemType Directory -Force -Path $LocalJdkDir | Out-Null
        $JdkZip = Join-Path $LocalJdkDir "temurin17.zip"
        Invoke-WebRequest -Uri "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" -OutFile $JdkZip -UseBasicParsing
        Expand-Archive -Path $JdkZip -DestinationPath $LocalJdkDir -Force
        Remove-Item $JdkZip -Force
        $Extracted = Get-ChildItem -Path $LocalJdkDir -Directory | Select-Object -First 1
        $env:JAVA_HOME = $Extracted.FullName
        Write-Host "  -> ポータブル JDK 17 の準備が完了しました: $($env:JAVA_HOME)" -ForegroundColor Green
    }
}
$env:PATH = "$($env:JAVA_HOME)\bin;$($env:PATH)"

Write-Host "[2/5] Android SDK (API 37 / Build-Tools 36.0.0) を確認しています..." -ForegroundColor Cyan

$DefaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$LocalSdk = Join-Path $ProjectRoot ".toolchain\android-sdk"

if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) {
    $SdkRoot = $env:ANDROID_HOME
} elseif (Test-Path $DefaultSdk) {
    $SdkRoot = $DefaultSdk
} else {
    $SdkRoot = $LocalSdk
}

$Platform37 = Join-Path $SdkRoot "platforms\android-37"
if (-not (Test-Path $Platform37)) {
    Write-Host "  -> Android 17 SDK (platforms;android-37) をセットアップしています..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path $SdkRoot | Out-Null
    $SdkManagerBat = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
    if (-not (Test-Path $SdkManagerBat)) {
        $CmdToolsZip = Join-Path $SdkRoot "cmdline-tools.zip"
        Invoke-WebRequest -Uri "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" -OutFile $CmdToolsZip -UseBasicParsing
        $TmpExtract = Join-Path $SdkRoot "cmdline-tools-tmp"
        Expand-Archive -Path $CmdToolsZip -DestinationPath $TmpExtract -Force
        New-Item -ItemType Directory -Force -Path (Join-Path $SdkRoot "cmdline-tools\latest") | Out-Null
        Copy-Item -Path "$TmpExtract\cmdline-tools\*" -Destination (Join-Path $SdkRoot "cmdline-tools\latest") -Recurse -Force
        Remove-Item $TmpExtract -Recurse -Force
        Remove-Item $CmdToolsZip -Force
    }
    $YesInput = ("y`n" * 30)
    $YesInput | & $SdkManagerBat --sdk_root="$SdkRoot" --licenses | Out-Null
    & $SdkManagerBat --sdk_root="$SdkRoot" "platform-tools" "platforms;android-37" "build-tools;36.0.0"
}

$EscapedSdk = $SdkRoot.Replace("\", "\\").Replace(":", "\:")
Set-Content -Path (Join-Path $ProjectRoot "local.properties") -Value "sdk.dir=$EscapedSdk" -Encoding UTF8
$env:ANDROID_HOME = $SdkRoot
Write-Host "  -> local.properties を生成しました (SDK: $SdkRoot)" -ForegroundColor Green

Write-Host "[3/5] ユニットテスト (testDebugUnitTest) を実行しています..." -ForegroundColor Cyan
& "$ProjectRoot\gradlew.bat" testDebugUnitTest --no-daemon
if ($LASTEXITCODE -ne 0) {
    throw "ユニットテストでエラーが発生しました。"
}

Write-Host "[4/5] Debug APK (assembleDebug) をビルドしています..." -ForegroundColor Cyan
& "$ProjectRoot\gradlew.bat" assembleDebug --no-daemon
if ($LASTEXITCODE -ne 0) {
    throw "APKビルドでエラーが発生しました。"
}

Write-Host "[5/5] 完成した APK を dist フォルダーへ出力しています..." -ForegroundColor Cyan
$BuiltApk = Get-ChildItem -Path "$ProjectRoot\app\build\outputs\apk\debug\*.apk" | Select-Object -First 1
if (-not $BuiltApk) {
    throw "ビルド出力フォルダーに APK が見つかりませんでした。"
}

$DistDir = Join-Path $ProjectRoot "dist"
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
$TargetApk = Join-Path $DistDir "AudioScope-v1.0.0-debug.apk"
Copy-Item -Path $BuiltApk.FullName -Destination $TargetApk -Force

Write-Host ""
Write-Host "============================================================================" -ForegroundColor Green
Write-Host "  ビルド成功！インストール可能な APK が生成されました:" -ForegroundColor Green
Write-Host "  $TargetApk" -ForegroundColor Yellow
Write-Host "  Galaxy S26 (SC-51G) をUSB接続して install_to_galaxy_s26.bat を実行するか、" -ForegroundColor Cyan
Write-Host "  APKファイルをスマホ本体へコピーしてタップするとインストールできます。" -ForegroundColor Cyan
Write-Host "============================================================================" -ForegroundColor Green
Start-Process explorer.exe -ArgumentList $DistDir
