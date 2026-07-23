import io
import json
import os

os.environ["ORIENTERNET_MOCK"] = "1"
os.environ["ORIENTERNET_API_KEY"] = "test-key"

from fastapi.testclient import TestClient
from PIL import Image

from app.main import app

client = TestClient(app)


def jpeg() -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (64, 48), "gray").save(buffer, format="JPEG")
    return buffer.getvalue()


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["mock"] is True
    assert "checkpoint_exists" in body
    assert "checkpoint_source" in body


def test_authentication():
    response = client.post(
        "/v1/localize",
        files={"image": ("frame.jpg", jpeg(), "image/jpeg")},
        data={"prior_lat": "43.2389", "prior_lon": "76.8897"},
    )
    assert response.status_code == 401


def test_legacy_contract():
    response = client.post(
        "/v1/localize",
        headers={"X-API-Key": "test-key"},
        files={"image": ("frame.jpg", jpeg(), "image/jpeg")},
        data={
            "prior_lat": "43.2389",
            "prior_lon": "76.8897",
            "prior_heading_deg": "123",
            "search_radius_m": "128",
        },
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["latitude"] == 43.2389
    assert body["yaw_degrees"] == 123
    assert body["lat"] == 43.2389
    assert body["heading_deg"] == 123
    assert body["backend"] == "mock"


def test_android_frame_metadata_contract():
    metadata = {
        "prior_lat": 55.7501,
        "prior_lon": 37.6202,
        "heading_deg": 361.5,
        "speed_mps": 5.0,
        "route_corridor": [[55.75, 37.62]],
    }
    response = client.post(
        "/v1/localize",
        headers={"X-API-Key": "test-key"},
        files={"frame": ("frame.jpg", jpeg(), "image/jpeg")},
        data={"metadata": json.dumps(metadata)},
    )
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["lat"] == metadata["prior_lat"]
    assert body["lon"] == metadata["prior_lon"]
    assert body["heading_deg"] == 1.5
    assert body["accepted"] is True


def test_missing_coordinates_returns_clear_400():
    response = client.post(
        "/v1/localize",
        headers={"X-API-Key": "test-key"},
        files={"frame": ("frame.jpg", jpeg(), "image/jpeg")},
        data={"metadata": "{}"},
    )
    assert response.status_code == 400
    assert "prior_lat/prior_lon" in response.text


def test_invalid_metadata_returns_400():
    response = client.post(
        "/v1/localize",
        headers={"X-API-Key": "test-key"},
        files={"frame": ("frame.jpg", jpeg(), "image/jpeg")},
        data={"metadata": "{"},
    )
    assert response.status_code == 400
