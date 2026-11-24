@echo off
chcp 65001 >nul
title AudioScope - Windows 11 ワンクリック Debug APK ビルドツール
echo ============================================================================
echo   AudioScope v1.0.0 - Windows 11 自動環境構築 ＆ Debug APK ビルドスクリプト
echo ============================================================================
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build_apk_windows.ps1"
echo.
pause
