# Visual service API

## `GET /health`

Returns service readiness and selected device. The model loads lazily on the
first inference or through `/v1/warmup`.

## `POST /v1/warmup`

Loads weights before a trip. Send `X-API-Key` when configured.

## `POST /v1/localize`

Multipart fields:

| Field | Type | Meaning |
|---|---:|---|
| `image` | JPEG/PNG/WebP | Forward-facing camera frame, max 12 MiB |
| `prior_lat` | float | Coarse search center latitude |
| `prior_lon` | float | Coarse search center longitude |
| `search_radius_m` | int | 64–256 m |
| `prior_heading_deg` | float | Optional 0–360° hint; currently diagnostic |

Response:

```json
{
  "latitude": 43.2389,
  "longitude": 76.8897,
  "yaw_degrees": 122.4,
  "confidence": 0.71,
  "sigma_meters": 8.4,
  "inference_ms": 2100,
  "rotations": 128
}
```

`confidence` is derived conservatively from spatial posterior spread. The
Android client still requires temporal consistency and does not treat this
number as a proof of correctness.
