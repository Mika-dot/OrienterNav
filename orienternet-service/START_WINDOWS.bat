@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0START_WINDOWS.ps1"
if errorlevel 1 (
  echo.
  echo СЕРВЕР ЗАВЕРШИЛСЯ С ОШИБКОЙ.
  pause
  exit /b 1
)
