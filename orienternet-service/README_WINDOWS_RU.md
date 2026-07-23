# Запуск OrienterNet-сервера в Windows

Checkpoint должен лежать в корне репозитория:

```text
OrienterNav\orienternet_mgl.ckpt
```

Сервис автоматически ищет его по пути `..\orienternet_mgl.ckpt`. Также можно задать полный путь переменной `ORIENTERNET_CHECKPOINT`.

## Запуск

Из папки `orienternet-service`:

```bat
START_WINDOWS.bat
```

Скрипт поддерживает окружения `venv` и `.venv`, проверяет наличие checkpoint и запускает сервер на порту `1000`.

Проверка из второго окна:

```bat
CHECK_WINDOWS.bat
```

Полная загрузка модели на GPU:

```powershell
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:1000/v1/warmup"
```

При использовании API-ключа:

```powershell
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:1000/v1/warmup" -Headers @{"X-API-Key"="ВАШ_КЛЮЧ"}
```

`/health` возвращает `checkpoint_path`, `checkpoint_exists`, `checkpoint_source`, `model_loaded` и `device`.

## Совместимость API

`POST /v1/localize` принимает оба формата:

1. Android: файл `frame` и JSON-строка `metadata`.
2. Старый клиент: файл `image` и поля `prior_lat`, `prior_lon`, `prior_heading_deg`, `search_radius_m`.

Ответ одновременно содержит новые поля `lat`, `lon`, `heading_deg`, `processing_ms` и старые поля `latitude`, `longitude`, `yaw_degrees`, `inference_ms`.
