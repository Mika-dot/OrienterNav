# OrienterNav

OrienterNav is an Android navigation application based on OpenStreetMap with an optional visual localization backend powered by Facebook OrienterNet.

Unlike a conventional navigator, OrienterNav is able to verify GPS coordinates using the camera image. This makes it possible to detect GPS spoofing, temporary GPS failures, multipath effects in dense urban areas and recover navigation using visual localization.

The Android application can be used independently as a normal navigator. Visual localization is provided by an external FastAPI service running on a PC equipped with an NVIDIA GPU.

---

# Features

## Navigation

- OpenStreetMap support
- MapLibre rendering
- Free OpenFreeMap tiles
- Route planning
- Voice navigation
- Russian turn-by-turn instructions
- Address search
- Manual destination selection
- Route recalculation
- Estimated arrival time
- Distance remaining

## Camera

- CameraX integration
- Forward camera capture
- Adaptive frame rate
- Automatic frame compression
- Secure image transmission

## Visual localization

- Facebook OrienterNet integration
- Automatic OSM download
- Local OSM cache
- GPU inference
- Bayesian localization
- Route-based search prior
- Heading estimation
- Confidence estimation
- GPS verification
- GPS spoofing detection

## Backend

- FastAPI
- PyTorch CUDA inference
- Lazy model loading
- Automatic warmup
- OSM raster generation
- REST API
- API key authentication

---

# Project structure

```
OrienterNav/
│
├── app/                       Android application
├── orienternet-service/       FastAPI visual localization backend
├── docs/
├── gradle/
└── README.md
```

The project consists of two independent parts.

## Android application

Runs on the phone.

Responsible for:

- navigation
- route planning
- GPS
- camera capture
- communication with the backend
- displaying localization results

## OrienterNet Service

Runs on a Windows or Linux PC with an NVIDIA GPU.

Responsible for:

- loading the OrienterNet model
- downloading OpenStreetMap data
- rendering raster maps
- visual localization
- returning estimated latitude, longitude and heading

---

# System requirements

## Android

- Android 8.0+
- Camera
- GPS
- Internet connection

---

## Windows server

Recommended configuration:

- Windows 10/11
- Python 3.11
- NVIDIA GPU
- CUDA-enabled PyTorch
- 16 GB RAM minimum
- 12 GB GPU memory recommended

The project has been tested on:

- Windows 10 Pro
- Python 3.11
- NVIDIA RTX 5070 (12 GB)
- CUDA 12.x
- PyTorch 2.7

---

## Linux server

Ubuntu 22.04 or newer

or

Docker + NVIDIA Container Toolkit

---

# Repository dependencies

This repository uses external projects which are **not included** inside this repository.

Before starting the backend they must be installed.

## Facebook OrienterNet

https://github.com/facebookresearch/OrienterNet

Required for visual localization.

Installed as editable package:

```bash
pip install -e OrienterNet
```

---

## PerspectiveFields

https://github.com/jinlinyi/PerspectiveFields

Required by OrienterNet.

Installed as editable package:

```bash
pip install -e PerspectiveFields
```

---

# Python environment

Create a virtual environment.

```bash
python -m venv venv
```

Activate it.

Windows

```cmd
venv\Scripts\activate
```

Linux

```bash
source venv/bin/activate
```

Upgrade pip.

```bash
python -m pip install --upgrade pip
```

Install project dependencies.

```bash
pip install -r orienternet-service/requirements.txt
```

Install editable repositories.

```bash
pip install -e OrienterNet
pip install -e PerspectiveFields
```

The backend is now ready for configuration.

# Windows installation

The Windows version is the primary development and testing environment for this project.

The backend has been verified on:

- Windows 10 Pro
- Python 3.11
- CUDA 12.x
- PyTorch 2.7
- NVIDIA RTX 5070

---

## 1. Clone the repository

```bash
git clone https://github.com/<your_repository>.git
cd OrienterNav
```

---

## 2. Create Python virtual environment

```bash
python -m venv venv
```

Activate it.

```cmd
venv\Scripts\activate
```

---

## 3. Upgrade pip

```bash
python -m pip install --upgrade pip
```

---

## 4. Install Python packages

```bash
pip install -r orienternet-service/requirements.txt
```

---

## 5. Clone OrienterNet

Clone the original Facebook repository.

```bash
git clone https://github.com/facebookresearch/OrienterNet.git
```

Install it.

```bash
pip install -e OrienterNet
```

---

## 6. Clone PerspectiveFields

```bash
git clone https://github.com/jinlinyi/PerspectiveFields.git
```

Install it.

```bash
pip install -e PerspectiveFields
```

---

## 7. Configure the backend

Go to the backend directory.

```bash
cd orienternet-service
```

Copy the configuration.

Windows

```cmd
copy .env.example .env
```

Linux

```bash
cp .env.example .env
```

Edit:

```
ORIENTERNET_API_KEY=
```

Generate a random API key.

Example:

```
ORIENTERNET_API_KEY=ReplaceWithYourOwnRandomKey
```

---

## 8. Download model weights

Download the pretrained OrienterNet checkpoint.

Place it into the directory expected by the backend configuration.

The exact filename must match the value configured inside the service.

---

## 9. Start the backend

From the `orienternet-service` directory.

```bash
python -m uvicorn app.main:app --host 0.0.0.0 --port 1000
```

Expected output:

```
INFO: Started server process
INFO: Waiting for application startup.
INFO: Application startup complete.
```

During the first localization request the model will be loaded automatically.

---

## 10. Verify the server

Health endpoint

```
http://127.0.0.1:1000/health
```

Expected response

```json
{
  "status":"ok"
}
```

---

## 11. Warmup

The first inference loads the model into GPU memory.

This may take several seconds.

Subsequent requests are significantly faster because the model remains loaded.

---

# Android application

Open the project in Android Studio.

Allow Gradle synchronization.

Build the application.

```
gradlew.bat assembleDebug
```

APK location:

```
app/build/outputs/apk/debug/app-debug.apk
```

Copy the APK to the Android device.

Enable installation from unknown sources if required.

Install the application.

---

# Connecting Android and the backend

The Android application communicates with the FastAPI backend through HTTP.

The recommended method is Tailscale.

Install Tailscale on both:

- Windows PC
- Android phone

Login using the same account.

After connection the PC receives an address similar to

```
100.x.x.x
```

Example

```
100.76.107.93
```

Configure the Android application to use

```
http://100.xx.xx.xx:1000
```

instead of localhost.

Enter the same API key configured inside `.env`.

The phone and the PC no longer need to be connected to the same Wi-Fi network.

---

# First localization test

Launch the backend.

Launch the Android application.

Open the map.

Start navigation.

Enable camera localization.

The Android application will periodically send camera frames to the backend.

The backend will

- generate an OSM raster
- execute OrienterNet
- estimate position
- estimate heading
- return confidence

Successful localization returns

```json
{
    "lat": ...,
    "lon": ...,
    "heading_deg": ...,
    "confidence": ...
}
```

The Android application automatically uses these results to validate GPS measurements and improve navigation robustness.

# Linux / Docker deployment

The backend can also be deployed on Linux using Docker.

Requirements:

- Ubuntu 22.04+
- NVIDIA Driver
- Docker
- NVIDIA Container Toolkit

Go to the backend directory.

```bash
cd orienternet-service
```

Create configuration.

```bash
cp .env.example .env
```

Edit

```
ORIENTERNET_API_KEY=
```

Build and start.

```bash
docker compose up --build -d
```

Warm up the model.

```bash
curl \
-H "X-API-Key: YOUR_KEY" \
-X POST \
http://127.0.0.1:8000/v1/warmup
```

---

# REST API

## Health

```
GET /health
```

Response

```json
{
    "status":"ok"
}
```

---

## Warmup

```
POST /v1/warmup
```

Loads the model into GPU memory.

---

## Localization

```
POST /v1/localize
```

Multipart form fields:

| Parameter | Description |
|------------|-------------|
| image | Camera frame |
| prior_lat | Latitude |
| prior_lon | Longitude |
| prior_heading_deg | Heading (optional) |
| search_radius_m | Search radius |

Successful response

```json
{
    "lat":55.81695,
    "lon":37.66329,
    "heading_deg":284.06,
    "confidence":0.49,
    "backend":"orienternet",
    "processing_ms":6374,
    "sigma_meters":17.47
}
```

---

# Troubleshooting

## ModuleNotFoundError

If Python cannot import `maploc` or `perspective2d`, ensure both external repositories have been installed as editable packages.

```bash
pip install -e OrienterNet
pip install -e PerspectiveFields
```

---

## PyTorch 2.6+

PyTorch 2.6 introduced secure checkpoint loading (`weights_only=True` by default).

Older OrienterNet checkpoints are incompatible with the default loader.

The backend has already been updated to use a compatible loading method.

No additional action is required.

---

## CUDA not detected

Verify:

```bash
python -c "import torch;print(torch.cuda.is_available())"
```

Expected output

```
True
```

If the result is `False`, reinstall the CUDA-enabled PyTorch build.

---

## First request is slow

Normal behavior.

During the first request the backend

- loads the checkpoint
- initializes CUDA
- creates the neural network
- allocates GPU memory

Subsequent requests are much faster.

---

## Windows temporary file error

Older versions of the backend could fail because Windows does not allow reopening a `NamedTemporaryFile` while it is still open.

The backend has been updated to close the temporary file before inference and remove it after processing.

---

## HTTP 422

Usually indicates missing multipart parameters.

Required fields:

```
image
prior_lat
prior_lon
search_radius_m
```

---

## HTTP 401

Invalid API key.

Verify

```
ORIENTERNET_API_KEY
```

matches the key configured inside the Android application.

---

## HTTP 503

Backend failed during localization.

Typical causes:

- missing checkpoint
- CUDA initialization failure
- missing Python dependency
- incompatible model version

Consult the backend log for details.

---

# Performance

Typical inference time depends on

- GPU
- search radius
- raster resolution

Example hardware

| GPU | Approximate localization time |
|------|------------------------------:|
| RTX 5070 | 5–7 seconds |
| RTX 4090 | Faster |
| CPU only | Not recommended |

---

# Security

The backend receives camera frames from the Android device.

For deployments outside a trusted local network it is strongly recommended to:

- use Tailscale
- use HTTPS
- keep the API key private
- never expose the backend directly to the Internet without authentication

---

# License

This repository combines original project code with third-party components.

The Android application is distributed under this repository's license.

Facebook OrienterNet and its pretrained checkpoints are distributed under their respective licenses and may impose additional restrictions, particularly for commercial use.

Refer to:

- THIRD_PARTY_NOTICES.md
- LICENSE
- OrienterNet repository

for complete licensing information.

---

# Acknowledgements

This project uses and builds upon the work of:

- Facebook Research — OrienterNet
- PerspectiveFields
- OpenStreetMap contributors
- MapLibre
- OpenFreeMap
- OSRM

Special thanks to the open-source community for making high-quality geospatial and computer vision research publicly available.

