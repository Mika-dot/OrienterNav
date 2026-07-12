from __future__ import annotations

import hmac
import math
import os
import tempfile
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import numpy as np
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import BaseModel, Field


MAX_IMAGE_BYTES = int(os.getenv("MAX_IMAGE_BYTES", str(12 * 1024 * 1024)))
NUM_ROTATIONS = int(os.getenv("ORIENTERNET_ROTATIONS", "128"))
CACHE_DIR = Path(os.getenv("ORIENTERNET_CACHE", "/cache"))
API_KEY = os.getenv("ORIENTERNET_API_KEY", "")
MOCK = os.getenv("ORIENTERNET_MOCK", "0") == "1"


class LocalizationResponse(BaseModel):
    latitude: float
    longitude: float
    yaw_degrees: float
    confidence: float = Field(ge=0.0, le=1.0)
    sigma_meters: float = Field(ge=0.0)
    inference_ms: int
    rotations: int


class HealthResponse(BaseModel):
    status: str
    model_loaded: bool
    device: str
    mock: bool


@dataclass
class TileBundle:
    projection: object
    tiler: object
    half_size_m: float


class Engine:
    def __init__(self) -> None:
        self._demo = None
        self._device = "not-loaded"
        self._lock = threading.Lock()
        self._tiles: dict[tuple[int, int, int], TileBundle] = {}

    @property
    def loaded(self) -> bool:
        return self._demo is not None or MOCK

    @property
    def device(self) -> str:
        return "mock" if MOCK else self._device

    def load(self) -> None:
        if self.loaded:
            return
        with self._lock:
            if self.loaded:
                return
            from maploc.demo import Demo
            import torch

            device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
            self._demo = Demo(num_rotations=NUM_ROTATIONS, device=device)
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
            return LocalizationResponse(
                latitude=prior_lat,
                longitude=prior_lon,
                yaw_degrees=prior_heading_deg or 0.0,
                confidence=0.92,
                sigma_meters=6.0,
                inference_ms=int((time.perf_counter() - started) * 1000),
                rotations=NUM_ROTATIONS,
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
            sigma = self._spatial_sigma(probability, uv, self._demo.config.model.pixel_per_meter)
            confidence = float(np.clip(math.exp(-sigma / 25.0), 0.0, 1.0))
        return LocalizationResponse(
            latitude=float(latlon[0]),
            longitude=float(latlon[1]),
            yaw_degrees=float(yaw),
            confidence=confidence,
            sigma_meters=float(sigma),
            inference_ms=int((time.perf_counter() - started) * 1000),
            rotations=NUM_ROTATIONS,
        )

    def _tile_bundle(self, lat: float, lon: float, radius: int) -> TileBundle:
        # Roughly 100 m grid: neighboring frames reuse the same OSM download/raster.
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
    version="0.1.0",
    description="Thin authenticated API around Meta OrienterNet (CC-BY-NC).",
)


def authenticate(x_api_key: Optional[str] = Header(default=None)) -> None:
    if API_KEY and not hmac.compare_digest(x_api_key or "", API_KEY):
        raise HTTPException(status_code=401, detail="Invalid API key")


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(
        status="ok" if engine.loaded else "model loads on first request",
        model_loaded=engine.loaded,
        device=engine.device,
        mock=MOCK,
    )


@app.post("/v1/warmup", dependencies=[Depends(authenticate)], response_model=HealthResponse)
def warmup() -> HealthResponse:
    engine.load()
    return health()


@app.post("/v1/localize", dependencies=[Depends(authenticate)], response_model=LocalizationResponse)
def localize(
    image: UploadFile = File(...),
    prior_lat: float = Form(..., ge=-90, le=90),
    prior_lon: float = Form(..., ge=-180, le=180),
    search_radius_m: int = Form(128, ge=64, le=256),
    prior_heading_deg: Optional[float] = Form(None, ge=0, le=360),
) -> LocalizationResponse:
    if image.content_type not in {"image/jpeg", "image/png", "image/webp"}:
        raise HTTPException(status_code=415, detail="JPEG, PNG or WebP required")
    payload = image.file.read(MAX_IMAGE_BYTES + 1)
    if len(payload) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Image is too large")
    suffix = ".png" if image.content_type == "image/png" else ".jpg"
    with tempfile.NamedTemporaryFile(suffix=suffix) as tmp:
        tmp.write(payload)
        tmp.flush()
        try:
            return engine.localize(
                tmp.name, prior_lat, prior_lon, search_radius_m, prior_heading_deg
            )
        except Exception as error:
            raise HTTPException(status_code=422, detail=str(error)) from error
