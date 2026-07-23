from __future__ import annotations

import hmac
import json
import math
import os
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional

import numpy as np
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field

SERVICE_DIR = Path(__file__).resolve().parents[1]
REPOSITORY_DIR = SERVICE_DIR.parent
MAX_IMAGE_BYTES = int(os.getenv("MAX_IMAGE_BYTES", str(12 * 1024 * 1024)))
NUM_ROTATIONS = int(os.getenv("ORIENTERNET_ROTATIONS", "128"))
DEFAULT_SEARCH_RADIUS_M = int(os.getenv("ORIENTERNET_SEARCH_RADIUS_M", "128"))
CACHE_DIR = Path(os.getenv("ORIENTERNET_CACHE", str(SERVICE_DIR / "cache"))).expanduser().resolve()
API_KEY = os.getenv("ORIENTERNET_API_KEY", "")
MOCK = os.getenv("ORIENTERNET_MOCK", "0") == "1"
CHECKPOINT_ENV = os.getenv("ORIENTERNET_CHECKPOINT", "").strip()


class LocalizationResponse(BaseModel):
    # Новый контракт Android.
    lat: float
    lon: float
    heading_deg: float
    confidence: float = Field(ge=0.0, le=1.0)
    backend: str
    processing_ms: int
    vehicle_count: int = 0
    accepted: bool = True
    message: str = ""

    # Старый контракт сервиса — оставлен для обратной совместимости.
    latitude: float
    longitude: float
    yaw_degrees: float
    sigma_meters: float = Field(ge=0.0)
    inference_ms: int
    rotations: int


class HealthResponse(BaseModel):
    status: str
    model_loaded: bool
    device: str
    mock: bool
    checkpoint_path: Optional[str]
    checkpoint_exists: bool
    checkpoint_source: str


@dataclass
class TileBundle:
    projection: object
    tiler: object
    half_size_m: float


@dataclass(frozen=True)
class RequestData:
    upload: UploadFile
    prior_lat: float
    prior_lon: float
    search_radius_m: int
    prior_heading_deg: Optional[float]


def _candidate_checkpoints() -> list[tuple[Path, str]]:
    candidates: list[tuple[Path, str]] = []
    if CHECKPOINT_ENV:
        candidates.append((Path(CHECKPOINT_ENV).expanduser(), "environment"))
    candidates.extend(
        [
            (REPOSITORY_DIR / "orienternet_mgl.ckpt", "repository-root"),
            (SERVICE_DIR / "orienternet_mgl.ckpt", "service-directory"),
        ]
    )
    try:
        import maploc

        package_root = Path(maploc.__file__).resolve().parent.parent
        candidates.append(
            (package_root / "experiments" / "orienternet_mgl.ckpt", "maploc-experiments")
        )
    except Exception:
        pass
    return candidates


def resolve_checkpoint() -> tuple[Optional[Path], str]:
    seen: set[str] = set()
    for candidate, source in _candidate_checkpoints():
        resolved = candidate.resolve()
        key = str(resolved).casefold()
        if key in seen:
            continue
        seen.add(key)
        if resolved.is_file():
            return resolved, source
    return None, "automatic-download"


class Engine:
    def __init__(self) -> None:
        self._demo = None
        self._device = "not-loaded"
        self._lock = threading.RLock()
        self._tiles: dict[tuple[int, int, int], TileBundle] = {}
        self._checkpoint_path, self._checkpoint_source = resolve_checkpoint()

    @property
    def loaded(self) -> bool:
        return self._demo is not None or MOCK

    @property
    def device(self) -> str:
        return "mock" if MOCK else self._device

    @property
    def checkpoint_path(self) -> Optional[Path]:
        return self._checkpoint_path

    @property
    def checkpoint_source(self) -> str:
        return self._checkpoint_source

    def load(self) -> None:
        if self.loaded:
            return
        with self._lock:
            if self.loaded:
                return
            from maploc.demo import Demo
            import torch

            device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
            checkpoint, source = resolve_checkpoint()
            if checkpoint is not None:
                demo = Demo(
                    experiment_or_path=str(checkpoint),
                    num_rotations=NUM_ROTATIONS,
                    device=device,
                )
                self._checkpoint_path = checkpoint
                self._checkpoint_source = source
            else:
                # Оригинальный OrienterNet сам скачивает orienternet_mgl.ckpt.
                demo = Demo(
                    experiment_or_path="OrienterNet_MGL",
                    num_rotations=NUM_ROTATIONS,
                    device=device,
                )
                self._checkpoint_path, discovered_source = resolve_checkpoint()
                self._checkpoint_source = (
                    discovered_source
                    if self._checkpoint_path is not None
                    else "automatic-download"
                )
            self._demo = demo
            self._device = str(device)

    def localize(
        self,
        image_path: str,
        prior_lat: float,
        prior_lon: float,
        search_radius_m: int,
        prior_heading_deg: Optional[float],
    ) -> LocalizationResponse:
        started = time.perf_counter()
        if MOCK:
            return self._response(
                lat=prior_lat,
                lon=prior_lon,
                yaw=prior_heading_deg or 0.0,
                confidence=0.92,
                sigma=6.0,
                started=started,
                backend="mock",
                accepted=True,
                message="Тестовый режим: нейросеть не запускалась.",
            )

        self.load()
        assert self._demo is not None
        with self._lock:
            image, camera, gravity, _, _ = self._demo.read_input_image(
                image_path,
                prior_latlon=(prior_lat, prior_lon),
                tile_size_meters=search_radius_m,
            )
            bundle = self._tile_bundle(prior_lat, prior_lon, search_radius_m)
            from maploc.utils.geo import BoundaryBox

            center = bundle.projection.project(np.array([prior_lat, prior_lon]))
            query_bbox = BoundaryBox(center, center) + search_radius_m
            canvas = bundle.tiler.query(query_bbox)
            uv, yaw, probability, _, _ = self._demo.localize(
                image, camera, canvas, gravity=gravity
            )
            latlon = bundle.projection.unproject(canvas.to_xy(uv))
            sigma = self._spatial_sigma(
                probability, uv, self._demo.config.model.pixel_per_meter
            )
            confidence = float(np.clip(math.exp(-sigma / 25.0), 0.0, 1.0))

        return self._response(
            lat=float(latlon[0]),
            lon=float(latlon[1]),
            yaw=float(yaw),
            confidence=confidence,
            sigma=float(sigma),
            started=started,
            backend="orienternet",
            accepted=True,
            message="",
        )

    def _response(
        self,
        *,
        lat: float,
        lon: float,
        yaw: float,
        confidence: float,
        sigma: float,
        started: float,
        backend: str,
        accepted: bool,
        message: str,
    ) -> LocalizationResponse:
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        return LocalizationResponse(
            lat=lat,
            lon=lon,
            heading_deg=yaw,
            confidence=confidence,
            backend=backend,
            processing_ms=elapsed_ms,
            vehicle_count=0,
            accepted=accepted,
            message=message,
            latitude=lat,
            longitude=lon,
            yaw_degrees=yaw,
            sigma_meters=sigma,
            inference_ms=elapsed_ms,
            rotations=NUM_ROTATIONS,
        )

    def _tile_bundle(self, lat: float, lon: float, radius: int) -> TileBundle:
        grid_lat = round(lat, 3)
        grid_lon = round(lon, 3)
        bucket = int(math.ceil(radius / 64.0) * 64)
        key = (int(grid_lat * 1000), int(grid_lon * 1000), bucket)
        cached = self._tiles.get(key)
        if cached is not None:
            return cached

        from maploc.osm.tiling import TileManager
        from maploc.utils.geo import BoundaryBox, Projection

        projection = Projection(grid_lat, grid_lon)
        center = projection.project(np.array([grid_lat, grid_lon]))
        half_size = bucket + 120
        bbox = BoundaryBox(center, center) + half_size
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        osm_path = CACHE_DIR / f"osm_{key[0]}_{key[1]}_{bucket}.json"
        tiler = TileManager.from_bbox(
            projection,
            bbox + 10,
            self._demo.config.data.pixel_per_meter,
            path=osm_path,
        )
        bundle = TileBundle(projection, tiler, half_size)
        if len(self._tiles) >= 24:
            self._tiles.pop(next(iter(self._tiles)))
        self._tiles[key] = bundle
        return bundle

    @staticmethod
    def _spatial_sigma(probability, uv, ppm: float) -> float:
        import torch

        pxy = probability.sum(-1).double()
        total = pxy.sum().clamp_min(1e-12)
        pxy = pxy / total
        height, width = pxy.shape
        yy, xx = torch.meshgrid(
            torch.arange(height, dtype=torch.double),
            torch.arange(width, dtype=torch.double),
            indexing="ij",
        )
        du2 = (xx - float(uv[0])) ** 2
        dv2 = (yy - float(uv[1])) ** 2
        rms_pixels = torch.sqrt(((du2 + dv2) * pxy.cpu()).sum()).item()
        return max(1.0, min(200.0, rms_pixels / float(ppm)))


engine = Engine()
app = FastAPI(
    title="OrienterNav Visual Localization",
    version="0.2.0",
    description="Совместимый API Android-клиента и оригинального OrienterNet.",
)


def authenticate(x_api_key: Optional[str] = Header(default=None)) -> None:
    if API_KEY and not hmac.compare_digest(x_api_key or "", API_KEY):
        raise HTTPException(status_code=401, detail="Неверный API-ключ")


def _number(value: Any, name: str) -> float:
    try:
        return float(value)
    except (TypeError, ValueError) as error:
        raise HTTPException(status_code=400, detail=f"Некорректное поле {name}") from error


def _bounded(value: float, name: str, minimum: float, maximum: float) -> float:
    if not minimum <= value <= maximum:
        raise HTTPException(
            status_code=400,
            detail=f"Поле {name} должно быть от {minimum} до {maximum}",
        )
    return value


def _parse_request(
    *,
    frame: Optional[UploadFile],
    image: Optional[UploadFile],
    metadata: Optional[str],
    prior_lat: Optional[float],
    prior_lon: Optional[float],
    search_radius_m: Optional[int],
    prior_heading_deg: Optional[float],
) -> RequestData:
    upload = frame or image
    if upload is None:
        raise HTTPException(status_code=400, detail="Не передано изображение frame/image")

    meta: dict[str, Any] = {}
    if metadata:
        try:
            decoded = json.loads(metadata)
        except json.JSONDecodeError as error:
            raise HTTPException(
                status_code=400, detail="metadata содержит некорректный JSON"
            ) from error
        if not isinstance(decoded, dict):
            raise HTTPException(status_code=400, detail="metadata должен быть JSON-объектом")
        meta = decoded

    raw_lat = prior_lat if prior_lat is not None else meta.get("prior_lat")
    raw_lon = prior_lon if prior_lon is not None else meta.get("prior_lon")
    if raw_lat is None or raw_lon is None:
        raise HTTPException(status_code=400, detail="Не переданы prior_lat/prior_lon")

    lat = _bounded(_number(raw_lat, "prior_lat"), "prior_lat", -90.0, 90.0)
    lon = _bounded(_number(raw_lon, "prior_lon"), "prior_lon", -180.0, 180.0)

    raw_radius = (
        search_radius_m
        if search_radius_m is not None
        else meta.get("search_radius_m", DEFAULT_SEARCH_RADIUS_M)
    )
    radius = int(_number(raw_radius, "search_radius_m"))
    if radius < 64 or radius > 256:
        raise HTTPException(
            status_code=400, detail="search_radius_m должен быть от 64 до 256"
        )

    raw_heading = (
        prior_heading_deg
        if prior_heading_deg is not None
        else meta.get("heading_deg", meta.get("prior_heading_deg"))
    )
    heading: Optional[float]
    if raw_heading is None:
        heading = None
    else:
        heading = _number(raw_heading, "heading_deg") % 360.0

    return RequestData(upload, lat, lon, radius, heading)


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    checkpoint = engine.checkpoint_path
    return HealthResponse(
        status="ok" if engine.loaded else "model loads on first request",
        model_loaded=engine.loaded,
        device=engine.device,
        mock=MOCK,
        checkpoint_path=str(checkpoint) if checkpoint is not None else None,
        checkpoint_exists=bool(checkpoint and checkpoint.is_file()),
        checkpoint_source=engine.checkpoint_source,
    )


@app.post("/v1/warmup", dependencies=[Depends(authenticate)], response_model=HealthResponse)
def warmup() -> HealthResponse:
    try:
        engine.load()
    except Exception as error:
        raise HTTPException(
            status_code=503, detail=f"Не удалось загрузить модель: {error}"
        ) from error
    return health()


@app.post(
    "/v1/localize",
    dependencies=[Depends(authenticate)],
    response_model=LocalizationResponse,
)
def localize(
    frame: Optional[UploadFile] = File(default=None),
    metadata: Optional[str] = Form(default=None),
    image: Optional[UploadFile] = File(default=None),
    prior_lat: Optional[float] = Form(default=None),
    prior_lon: Optional[float] = Form(default=None),
    search_radius_m: Optional[int] = Form(default=None),
    prior_heading_deg: Optional[float] = Form(default=None),
) -> LocalizationResponse:
    request = _parse_request(
        frame=frame,
        image=image,
        metadata=metadata,
        prior_lat=prior_lat,
        prior_lon=prior_lon,
        search_radius_m=search_radius_m,
        prior_heading_deg=prior_heading_deg,
    )
    content_type = request.upload.content_type or "application/octet-stream"
    if content_type not in {"image/jpeg", "image/png", "image/webp"}:
        raise HTTPException(status_code=415, detail="Нужен JPEG, PNG или WebP")
    payload = request.upload.file.read(MAX_IMAGE_BYTES + 1)
    if not payload:
        raise HTTPException(status_code=400, detail="Получено пустое изображение")
    if len(payload) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Изображение слишком большое")

    suffix = ".png" if content_type == "image/png" else ".jpg"
    with tempfile.NamedTemporaryFile(suffix=suffix) as tmp:
        tmp.write(payload)
        tmp.flush()
        try:
            return engine.localize(
                tmp.name,
                request.prior_lat,
                request.prior_lon,
                request.search_radius_m,
                request.prior_heading_deg,
            )
        except HTTPException:
            raise
        except Exception as error:
            raise HTTPException(
                status_code=503, detail=f"Ошибка локализации: {error}"
            ) from error
