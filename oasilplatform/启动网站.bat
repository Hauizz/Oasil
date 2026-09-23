@echo off
title Start Spring AI Chat Demo
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-site.ps1" %*
if errorlevel 1 (
  echo.
  echo [FAILED] Startup did not complete. See messages above.
  pause
)
