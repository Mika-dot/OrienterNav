# OrienterNet-сервер: Windows

Эта инструкция описывает проверенный запуск сервера из ветки `osmlocnav-v0.2-demo`.

## Требования

- Windows 10/11 x64;
- Python 3.11;
- Git for Windows;
- NVIDIA GPU и актуальный драйвер;
- доступ в Интернет для pip, GitHub и OSM.

## Автоматическая установка

Из папки `orienternet-service`:

```bat
INSTALL_WINDOWS.bat
```

Установщик создаёт `.venv`, ставит CUDA PyTorch, зависимости OrienterNet, клонирует `OrienterNet` и `PerspectiveFields`, создаёт `.env` и запускает диагностику.

## Checkpoint

Рекомендуемый путь:

```text
OrienterNav\orienternet_mgl.ckpt
```

Допустимые альтернативы:

```text
OrienterNav\orienternet-service\orienternet_mgl.ckpt
OrienterNav\OrienterNet\experiments\orienternet_mgl.ckpt
```

Или укажите путь в `.env`:

```dotenv
ORIENTERNET_CHECKPOINT=C:\Models\orienternet_mgl.ckpt
```

## Настройка `.env`

```dotenv
ORIENTERNET_API_KEY=replace-with-your-own-long-random-key
ORIENTERNET_HOST=0.0.0.0
ORIENTERNET_PORT=1000
ORIENTERNET_ROTATIONS=128
ORIENTERNET_SEARCH_RADIUS_M=128
ORIENTERNET_CACHE=cache
```

`START_WINDOWS.ps1` автоматически читает `.env`.

## Запуск

```bat
START_WINDOWS.bat
```

Проверка:

```bat
CHECK_WINDOWS.bat
```

Загрузка модели на GPU:

```bat
CHECK_WINDOWS.bat warmup
```

## Диагностика окружения

```powershell
.\.venv\Scripts\python.exe VERIFY_WINDOWS.py
```

Скрипт проверяет:

- FastAPI и uvicorn;
- torch/torchvision;
- CUDA и имя GPU;
- `maploc`;
- `perspective2d`;
- OSM/геопространственные библиотеки;
- checkpoint.

## Проверка локализации

```powershell
curl.exe -X POST http://127.0.0.1:1000/v1/localize `
  -H "X-API-Key: YOUR_KEY" `
  -F "image=@C:\path\photo.jpg" `
  -F "prior_lat=55.816949" `
  -F "prior_lon=37.663309" `
  -F "search_radius_m=128"
```

## Совместимость API

`POST /v1/localize` принимает оба формата:

1. Android: `frame` и JSON-строка `metadata`.
2. Старый клиент: `image`, `prior_lat`, `prior_lon`, `prior_heading_deg`, `search_radius_m`.

Ответ содержит одновременно новые и старые поля для обратной совместимости.

## Исправления Windows

В сервер уже включены:

- закрытие временного изображения перед повторным открытием OrienterNet;
- удаление временного файла в `finally`;
- совместимость старого Lightning checkpoint с PyTorch 2.6+;
- автоматический поиск checkpoint;
- кэширование OSM-тайлов;
- подробный `/health`.

## Подключение Android через Tailscale

На ПК:

```powershell
tailscale ip -4
```

В Android введите:

```text
http://TAILSCALE_IP_ПК:1000
```

и тот же API-ключ из `.env`.

`127.0.0.1` и `localhost` на телефоне не ведут на ПК.
