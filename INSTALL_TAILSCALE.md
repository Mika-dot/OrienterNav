# Подключение Android к OrienterNet через Tailscale

Tailscale позволяет телефону обращаться к серверу без проброса порта в Интернет и без общей Wi-Fi сети.

## 1. Установить Tailscale

Установите Tailscale на:

- Windows-ПК с OrienterNet;
- Android-телефон.

Войдите в одну tailnet.

## 2. Узнать адрес ПК

На Windows:

```powershell
tailscale status
tailscale ip -4
```

Адрес имеет вид:

```text
100.x.x.x
```

## 3. Запустить сервер на всех интерфейсах

В `orienternet-service\.env`:

```dotenv
ORIENTERNET_HOST=0.0.0.0
ORIENTERNET_PORT=1000
```

Запуск:

```bat
START_WINDOWS.bat
```

## 4. Проверить Windows Firewall

Если телефон не видит сервер, разрешите входящие TCP-подключения к порту 1000 для приватной сети/Tailscale. Не открывайте порт на домашнем роутере.

Локальная проверка на ПК:

```powershell
curl.exe http://127.0.0.1:1000/health
```

Проверка по Tailscale-адресу на ПК:

```powershell
curl.exe http://100.x.x.x:1000/health
```

## 5. Настроить Android

В приложении нажмите `⚙` и укажите:

```text
Server URL: http://100.x.x.x:1000
API key: значение ORIENTERNET_API_KEY из .env
```

Откройте на телефоне в браузере:

```text
http://100.x.x.x:1000/health
```

Если JSON открывается, сетевое соединение работает.

## Частые ошибки

### `localhost` не работает

На телефоне `localhost` и `127.0.0.1` означают сам телефон. Используйте Tailscale IPv4 компьютера.

### Сервер доступен на ПК, но недоступен на телефоне

Проверьте:

- оба устройства онлайн в Tailscale;
- сервер запущен с `ORIENTERNET_HOST=0.0.0.0`;
- Windows Firewall не блокирует Python/порт 1000;
- адрес введён с `http://` и портом `:1000`.

### HTTP 401

API-ключ в Android не совпадает с `ORIENTERNET_API_KEY`.

## Безопасность

- Не публикуйте FastAPI-сервис напрямую в Интернет.
- Используйте длинный случайный API-ключ.
- Не коммитьте рабочий `.env`.
- Не добавляйте checkpoint в Git: `*.ckpt` исключён через `.gitignore`.
