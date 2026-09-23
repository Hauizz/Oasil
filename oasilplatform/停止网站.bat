@echo off
title Stop Spring AI Chat Demo
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-site.ps1" %*
echo.
pause
