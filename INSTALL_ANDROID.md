# Сборка и установка Android-приложения

## Требования

- Android Studio;
- Android SDK 35;
- JDK 17;
- Android 8.0+ на телефоне.

## Сборка из командной строки

Откройте PowerShell в корне репозитория:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

Готовый APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Сборка в Android Studio

1. Откройте корень `OrienterNav` как проект.
2. Дождитесь Gradle Sync.
3. Выберите `Build > Build APK(s)`.
4. Установите созданный `app-debug.apk` на телефон.

## Первый запуск

Разрешите приложению:

- камеру;
- точную геолокацию;
- сетевой доступ.

В настройках приложения (`⚙`) задайте:

```text
Server URL: http://TAILSCALE_IP_ПК:1000
API key: значение ORIENTERNET_API_KEY из orienternet-service\.env
```

Интервал кадров задаётся в миллисекундах. Для первого теста используйте `1000`.

## Сценарий проверки

1. Запустите сервер и выполните `CHECK_WINDOWS.bat warmup`.
2. Откройте приложение.
3. Установите старт через GPS или вручную.
4. Долгим нажатием на карте выберите цель.
5. Нажмите `Поехали`.
6. Убедитесь, что статус сервера меняется с `не подключён` на `orienternet` или `mock`.

## GitHub Actions

Workflow `.github/workflows/ci.yml` запускает unit-тесты и Gradle-сборку. При успехе APK публикуется как artifact `OSMLocNav-debug-apk`; при ошибке Gradle-лог публикуется как `OSMLocNav-gradle-log`.
