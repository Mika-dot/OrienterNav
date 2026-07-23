@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

set "PY="
if exist "venv\Scripts\python.exe" set "PY=%CD%\venv\Scripts\python.exe"
if not defined PY if exist ".venv\Scripts\python.exe" set "PY=%CD%\.venv\Scripts\python.exe"
if not defined PY (
  echo ОШИБКА: не найден venv\Scripts\python.exe или .venv\Scripts\python.exe
  exit /b 1
)

if not defined ORIENTERNET_CHECKPOINT set "ORIENTERNET_CHECKPOINT=%CD%\..\orienternet_mgl.ckpt"
if not exist "%ORIENTERNET_CHECKPOINT%" (
  echo ОШИБКА: не найден checkpoint:
  echo %ORIENTERNET_CHECKPOINT%
  exit /b 2
)

if not defined ORIENTERNET_CACHE set "ORIENTERNET_CACHE=%CD%\cache"
if not defined ORIENTERNET_PORT set "ORIENTERNET_PORT=1000"

"%PY%" -c "from app.main import resolve_checkpoint; p,s=resolve_checkpoint(); assert p and p.is_file(), 'checkpoint not found'; print('Checkpoint:', p); print('Source:', s)"
if errorlevel 1 exit /b 3

echo Сервер: http://0.0.0.0:%ORIENTERNET_PORT%
echo Проверка: http://127.0.0.1:%ORIENTERNET_PORT%/health
"%PY%" -m uvicorn app.main:app --host 0.0.0.0 --port %ORIENTERNET_PORT%
