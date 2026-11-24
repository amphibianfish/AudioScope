@echo off
chcp 65001 >nul
title AudioScope - Galaxy S26 (SC-51G) ワンクリックADBインストーラー
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install_to_device.ps1"
echo.
pause
