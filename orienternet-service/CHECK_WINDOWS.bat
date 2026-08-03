@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

if /I "%~1"=="warmup" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0CHECK_WINDOWS.ps1" -Warmup
) else (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0CHECK_WINDOWS.ps1"
)

if errorlevel 1 (
  echo.
  echo ПРОВЕРКА ЗАВЕРШИЛАСЬ С ОШИБКОЙ.
  pause
  exit /b 1
)
