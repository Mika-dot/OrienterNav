@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

set "PY="
if exist "venv\Scripts\python.exe" set "PY=%CD%\venv\Scripts\python.exe"
if not defined PY if exist ".venv\Scripts\python.exe" set "PY=%CD%\.venv\Scripts\python.exe"
if not defined PY (
  echo ОШИБКА: виртуальное окружение не найдено.
  exit /b 1
)

if not defined ORIENTERNET_PORT set "ORIENTERNET_PORT=1000"
set "BASE_URL=http://127.0.0.1:%ORIENTERNET_PORT%"

"%PY%" -c "import json,urllib.request; print(json.dumps(json.load(urllib.request.urlopen('%BASE_URL%/health', timeout=10)), ensure_ascii=False, indent=2))"
if errorlevel 1 (
  echo ОШИБКА: сервер не отвечает на %BASE_URL%/health
  exit /b 2
)

echo.
echo Для полной загрузки модели вызовите:
echo powershell -NoProfile -Command "Invoke-RestMethod -Method Post -Uri '%BASE_URL%/v1/warmup'"
