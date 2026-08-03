@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0INSTALL_WINDOWS.ps1"
if errorlevel 1 (
  echo.
  echo УСТАНОВКА ЗАВЕРШИЛАСЬ С ОШИБКОЙ.
  pause
  exit /b 1
)

echo.
echo Установка завершена успешно.
pause
