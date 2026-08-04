# OrienterNav / OSMLocNav

Android-навигатор на OpenStreetMap с внешним сервером визуальной локализации Facebook OrienterNet.

> Новая тестовая ветка `agent/osmlocnav-v0.3` содержит двумерную GPS/INS/OrienterNet оценку положения, автоматическое перестроение маршрута, фильтрацию плохих кадров и обновлённый интерфейс. Полный пошаговый запуск для новичка: [START_HERE_V0_3_RU.md](START_HERE_V0_3_RU.md).

Обычная навигация, построение маршрута и отображение карты работают на телефоне. Для визуального уточнения координат, оценки направления и проверки GPS используется отдельный FastAPI-сервер на Windows или Linux с NVIDIA GPU.

## Что находится в репозитории

```text
OrienterNav/
├─ app/                         Android-приложение
├─ orienternet-service/         FastAPI-сервер OrienterNet
├─ docs/                        Архитектура и API
├─ gradle/                      Gradle wrapper
├─ README.md                    Основная инструкция
└─ orienternet_mgl.ckpt         Модель, если положить её в корень
```

Внешние исследовательские проекты не включены в репозиторий и устанавливаются отдельно:

- Facebook Research OrienterNet (`maploc`)
- PerspectiveFields (`perspective2d`)

Windows-установщик умеет сам клонировать и подключать оба проекта.

---

# Быстрый запуск на Windows

Проверенная конфигурация:

- Windows 10/11 x64
- Python 3.11
- NVIDIA GPU
- CUDA-совместимый драйвер
- PyTorch с CUDA
- 16 ГБ ОЗУ или больше
- около 12 ГБ VRAM рекомендуется для стандартной модели

## 1. Клонировать нужную ветку

```powershell
git clone -b agent/osmlocnav-v0.3 https://github.com/Mika-dot/OrienterNav.git
cd OrienterNav\orienternet-service
```

## 2. Запустить автоматическую установку

```bat
INSTALL_WINDOWS.bat
```

Скрипт:

- создаёт `.venv` на Python 3.11;
- обновляет pip/setuptools/wheel;
- ставит FastAPI и зависимости сервиса;
- ставит CUDA-сборку PyTorch;
- ставит полный runtime OrienterNet;
- клонирует `OrienterNet` и `PerspectiveFields` в корень проекта;
- устанавливает `maploc` и `perspective2d` в editable-режиме;
- создаёт `.env` из `.env.example`;
- проверяет импорты, CUDA и GPU.

По умолчанию PyTorch ставится из официального индекса CUDA 12.8. Для другого индекса запустите PowerShell-скрипт вручную:

```powershell
powershell -ExecutionPolicy Bypass -File .\INSTALL_WINDOWS.ps1 `
  -TorchIndex https://download.pytorch.org/whl/cu128
```

## 3. Положить checkpoint

Рекомендуемый путь:

```text
OrienterNav\orienternet_mgl.ckpt
```

Сервис также проверяет:

```text
OrienterNav\orienternet-service\orienternet_mgl.ckpt
OrienterNet\experiments\orienternet_mgl.ckpt
```

Либо укажите полный путь в `orienternet-service\.env`:

```dotenv
ORIENTERNET_CHECKPOINT=C:\Models\orienternet_mgl.ckpt
```

Если checkpoint не найден, оригинальный OrienterNet может попытаться скачать модель при первой загрузке.

## 4. Настроить `.env`

Откройте:

```text
orienternet-service\.env
```

Минимально замените API-ключ:

```dotenv
ORIENTERNET_API_KEY=replace-with-your-own-long-random-key
ORIENTERNET_PORT=1000
ORIENTERNET_HOST=0.0.0.0
```

Этот же API-ключ затем вводится в настройках Android-приложения.

## 5. Запустить сервер

```bat
START_WINDOWS.bat
```

Скрипт загружает переменные из `.env`, проверяет окружение и запускает:

```text
http://0.0.0.0:1000
```

Проверка локально:

```text
http://127.0.0.1:1000/health
```

## 6. Проверить сервер

В новом окне:

```bat
CHECK_WINDOWS.bat
```

Полная загрузка модели на GPU:

```bat
CHECK_WINDOWS.bat warmup
```

При успешном warmup поле `device` должно быть `cuda`.

---

# Ручная установка сервера

Используйте этот путь, если автоматический установщик завершился ошибкой.

```powershell
cd OrienterNav\orienternet-service
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip setuptools wheel
python -m pip install -r requirements.txt -c constraints.txt
python -m pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu128
python -m pip install -r requirements-orienternet.txt -c constraints.txt
cd ..
git clone https://github.com/facebookresearch/OrienterNet.git
git clone https://github.com/jinlinyi/PerspectiveFields.git
.\orienternet-service\.venv\Scripts\python.exe -m pip install -e .\PerspectiveFields --no-deps
.\orienternet-service\.venv\Scripts\python.exe -m pip install -e .\OrienterNet --no-deps
cd orienternet-service
copy .env.example .env
python VERIFY_WINDOWS.py
```

## Почему PyTorch ставится отдельно

`torch`, `torchvision` и `torchaudio` не закреплены в обычном `requirements.txt`, потому что требуемая сборка зависит от GPU, драйвера и CUDA. Это также не позволяет pip случайно заменить рабочую CUDA-сборку CPU-вариантом.

## Почему OpenSfM не устанавливается

OpenSfM не требуется для обычного inference OrienterNet. На Windows его сборка часто требует Visual Studio C++ Build Tools и NMake, поэтому он намеренно исключён из стандартной установки.

---

# Сборка Android APK

Требования:

- Android Studio с Android SDK 35;
- JDK 17;
- Android 8.0 или новее.

Из корня репозитория:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Также APK собирается GitHub Actions в workflow `CI` и публикуется как artifact `OSMLocNav-debug-apk`, если Gradle-сборка прошла успешно.

## Первый запуск Android

1. Установите APK.
2. Разрешите камеру и геолокацию.
3. Откройте кнопку настроек `⚙`.
4. Введите адрес сервера и API-ключ.
5. Выберите старт через GPS или вручную.
6. Долгим нажатием выберите точку назначения.
7. Нажмите `Поехали`.

---

# Соединение телефона и сервера через Tailscale

Рекомендуемый вариант — Tailscale на ПК и Android.

1. Установите Tailscale на обеих машинах.
2. Войдите в один аккаунт или одну tailnet.
3. Узнайте Tailscale IPv4 ПК:

```powershell
tailscale ip -4
```

Пример:

```text
100.76.107.93
```

4. Убедитесь, что сервер запущен на `0.0.0.0:1000`.
5. В настройках Android укажите:

```text
http://100.76.107.93:1000
```

`localhost` и `127.0.0.1` на телефоне указывают на сам телефон, а не на ПК.

Проверить доступность с телефона можно, открыв в браузере:

```text
http://TAILSCALE_IP_ПК:1000/health
```

Не публикуйте порт 1000 напрямую в Интернет. Используйте Tailscale, VPN или защищённый reverse proxy.

---

# Проверка API вручную

## Health

```powershell
curl.exe http://127.0.0.1:1000/health
```

## Warmup

Без API-ключа:

```powershell
curl.exe -X POST http://127.0.0.1:1000/v1/warmup
```

С API-ключом:

```powershell
curl.exe -X POST http://127.0.0.1:1000/v1/warmup `
  -H "X-API-Key: YOUR_KEY"
```

## Локализация изображения

```powershell
curl.exe -X POST http://127.0.0.1:1000/v1/localize `
  -H "X-API-Key: YOUR_KEY" `
  -F "image=@C:\path\photo.jpg" `
  -F "prior_lat=55.816949" `
  -F "prior_lon=37.663309" `
  -F "search_radius_m=128"
```

Пример успешного ответа:

```json
{
  "lat": 55.81695284615412,
  "lon": 37.66329112732796,
  "heading_deg": 284.0625,
  "confidence": 0.4971306538079562,
  "backend": "orienternet",
  "processing_ms": 6374,
  "sigma_meters": 17.47
}
```

`POST /v1/localize` поддерживает оба контракта:

- Android: `frame` + JSON-строка `metadata`;
- старый клиент: `image`, `prior_lat`, `prior_lon`, `search_radius_m`, `prior_heading_deg`.

---

# Исправления совместимости, уже включённые в сервер

## PyTorch 2.6 и новее

Новые версии PyTorch по умолчанию загружают checkpoint с `weights_only=True`. Старый Lightning checkpoint OrienterNet требует доверенной полной загрузки. Сервер временно вызывает `torch.load(..., weights_only=False)` только для этого checkpoint.

Используйте только официальный или самостоятельно проверенный checkpoint. Полная pickle-загрузка чужого файла небезопасна.

## TemporaryFile в Windows

Windows не позволяет сторонней библиотеке повторно открыть активный `NamedTemporaryFile`. Сервер теперь:

1. создаёт файл с `delete=False`;
2. закрывает его до запуска OrienterNet;
3. удаляет в блоке `finally`.

## Автоматический поиск checkpoint

`/health` возвращает:

- `checkpoint_path`;
- `checkpoint_exists`;
- `checkpoint_source`;
- `model_loaded`;
- `device`.

## Кэш OSM

OSM-данные сохраняются в `orienternet-service\cache`, а соседние запросы повторно используют уже подготовленные тайлы.

---

# Частые ошибки

## `ModuleNotFoundError: maploc`

```powershell
.\.venv\Scripts\python.exe -m pip install -e ..\OrienterNet --no-deps
```

## `ModuleNotFoundError: perspective2d`

```powershell
.\.venv\Scripts\python.exe -m pip install -e ..\PerspectiveFields --no-deps
```

## CUDA недоступна

```powershell
.\.venv\Scripts\python.exe -c "import torch; print(torch.__version__); print(torch.version.cuda); print(torch.cuda.is_available()); print(torch.cuda.get_device_name(0) if torch.cuda.is_available() else 'CPU')"
```

Если вывод `False`, проверьте драйвер NVIDIA и переустановите CUDA-сборку PyTorch.

## HTTP 401

API-ключ в Android не совпадает с `ORIENTERNET_API_KEY` в `.env`.

## HTTP 400 или 422

Проверьте обязательные координаты, изображение и тип multipart-полей.

## HTTP 503

Ошибка внутри inference. Смотрите текст `detail` и консоль сервера. Частые причины:

- checkpoint отсутствует или повреждён;
- не установлена библиотека;
- CUDA не работает;
- OSM-запрос недоступен;
- недостаточно памяти GPU.

## Первый запрос медленный

Это нормально: загружается checkpoint, инициализируется CUDA и создаются OSM-тайлы. Используйте `CHECK_WINDOWS.bat warmup` до начала работы.

---

# Файлы зависимостей

- `orienternet-service/requirements.txt` — FastAPI, тесты и лёгкий mock-runtime;
- `orienternet-service/requirements-orienternet.txt` — полный runtime модели;
- `orienternet-service/constraints.txt` — ограничения совместимости без принудительной установки PyTorch;
- `orienternet-service/INSTALL_WINDOWS.ps1` — автоматическая установка;
- `orienternet-service/VERIFY_WINDOWS.py` — проверка библиотек, CUDA и checkpoint;
- `orienternet-service/START_WINDOWS.ps1` — загрузка `.env` и запуск сервера;
- `orienternet-service/CHECK_WINDOWS.ps1` — health/warmup.

---

# Тестовый mock-режим

Для проверки API без модели и GPU:

```powershell
$env:ORIENTERNET_MOCK="1"
$env:ORIENTERNET_API_KEY="test-key"
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 1000
```

Mock-режим проверяет транспорт и контракт API, но не выполняет визуальную локализацию.

---

# Лицензии

OrienterNav использует сторонние компоненты с отдельными лицензиями. OrienterNet и pretrained weights могут иметь ограничения на коммерческое применение. Перед распространением или коммерческим использованием проверьте:

- `LICENSE`;
- `THIRD_PARTY_NOTICES.md`;
- лицензии OrienterNet и PerspectiveFields.
