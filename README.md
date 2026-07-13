# OrienterNav

Android car navigator on OpenStreetMap with optional OrienterNet visual
localization for checking GPS failures and spoofing. The ordinary navigator is
usable after compiling the APK; visual correction additionally requires the
included GPU service.

## Implemented

- MapLibre map with free OpenFreeMap OSM tiles, no API key;
- GPS start, manually entered start, address search, and destination by map tap;
- driving route, route polyline, distance/time, Russian turn instructions and
  text-to-speech;
- forward CameraX capture with adaptive frequency;
- authenticated OrienterNet API, OSM raster cache, lazy model loading and a
  Docker GPU configuration;
- GPS/vision integrity state machine: trusted, suspected, confirmed spoof,
  visual-only and degraded;
- route-based visual prior that does not follow suspicious GPS blindly;
- unit tests for fusion logic and API contract; GitHub Actions builds the APK.

## Build the APK on Windows

### Easiest: GitHub Actions

1. Open the repository's **Actions** tab and select the latest **CI** run for
   `main`.
2. Wait until both jobs are green.
3. In the run page's **Artifacts** section, download
   `OrienterNav-debug-apk` and unpack the ZIP.
4. Copy `app-debug.apk` to the phone, allow installation from the browser or
   file manager when Android asks, and open the APK.

If Actions are disabled for the private repository, enable them in
**Settings → Actions → General**, then run the **CI** workflow again.

### Build locally

1. Install Android Studio with Android SDK 35 and JDK 17.
2. Clone/download this repository, open its root as a project, and wait for
   Gradle sync.
3. Open the terminal in the project root and run
   `gradlew.bat testDebugUnitTest assembleDebug`.
4. Install `app\build\outputs\apk\debug\app-debug.apk` on Android 8.0+.

No Google Maps key is required. The default routing endpoint is the public OSRM
demo, appropriate for personal/testing use. All endpoints can be replaced in
the in-app **Настройки** dialog. For reliable offline or continuous use,
self-host OSRM/Nominatim and point the app to them.

## Start visual localization

The original OrienterNet evaluation requires roughly 11 GB of GPU memory at its
documented settings. It is a Python/PyTorch research model, so the repository
runs it beside the phone instead of pretending that it is a practical in-APK
model.

On a Linux machine with an NVIDIA GPU, Docker and NVIDIA Container Toolkit:

```bash
cd orienternet-service
cp .env.example .env
# change the key in .env
docker compose up --build -d
curl -H "X-API-Key: YOUR_KEY" -X POST http://127.0.0.1:8000/v1/warmup
```

Expose port 8000 through an HTTPS reverse proxy or a personal HTTPS tunnel
(for example, Tailscale Serve), then enter its `https://...` address and the
same API key in the application. Android intentionally blocks generic cleartext
LAN HTTP because it would expose both the API key and camera frames. HTTP is
allowed only for emulator development through `localhost`/`10.0.2.2`. Mount the
phone so the rear camera faces forward with a clear, stable view.

For API-only testing without a GPU:

```bash
cd orienternet-service
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
ORIENTERNET_MOCK=1 ORIENTERNET_API_KEY=test uvicorn app.main:app --port 8000
pytest -q
```

Mock mode verifies transport and UI integration only; it does not perform
visual localization.

## Accuracy and limitations

Visual localization is used only when its posterior is sharp and several frames
agree. This greatly reduces jumps, but it is not a guarantee. OrienterNet is
local: it needs an approximate search prior. A kilometer-scale spoof already
active before the app has a trusted/manual origin cannot be solved from a
single frame. See [architecture and trust model](docs/ARCHITECTURE.md) and the
[API contract](docs/API.md).

OrienterNet code and pretrained weights are CC BY-NC and therefore suitable for
this personal/non-commercial project, not a commercial product without separate
permission. See [third-party notices](THIRD_PARTY_NOTICES.md).
