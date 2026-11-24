@echo off
@rem ##########################################################################
@rem  AudioScope Gradle Wrapper for Windows 11
@rem  Automatically downloads official gradle-wrapper.jar on first run if absent.
@rem ##########################################################################

if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_HOME=%DIRNAME%
set WRAPPER_JAR=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

if not exist "%WRAPPER_JAR%" (
    echo [AudioScope] gradle-wrapper.jar が見つからないため、Gradle公式リポジトリから初回取得しています...
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
      "$ProgressPreference='SilentlyContinue'; New-Item -ItemType Directory -Force -Path '%APP_HOME%\gradle\wrapper' | Out-Null; Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v9.6.0/gradle/wrapper/gradle-wrapper.jar' -OutFile '%WRAPPER_JAR%' -UseBasicParsing"
    if not exist "%WRAPPER_JAR%" (
        powershell -NoProfile -ExecutionPolicy Bypass -Command ^
          "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/android/nowinandroid/main/gradle/wrapper/gradle-wrapper.jar' -OutFile '%WRAPPER_JAR%' -UseBasicParsing"
    )
)

if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    goto findJavaFromJavaHome
)

echo.
echo [エラー] JAVA_HOME が未設定で、PATH または Android Studio 内にも Java 17 以上が見つかりません。
echo 同梱の build_apk_windows.bat を実行すると、JDK 17 と Android SDK を自動準備してAPKをビルドできます。
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto execute

echo [エラー] JAVA_HOME のパスが無効です: %JAVA_HOME%
goto fail

:execute
"%JAVA_EXE%" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
if %ERRORLEVEL% neq 0 goto fail
goto end

:fail
exit /b 1

:end
if "%OS%"=="Windows_NT" endlocal
