import io
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
    assert response.json()["mock"] is True


def test_authentication():
    response = client.post(
        "/v1/localize",
        files={"image": ("frame.jpg", jpeg(), "image/jpeg")},
        data={"prior_lat": "43.2389", "prior_lon": "76.8897"},
    )
    assert response.status_code == 401


def test_localize_contract():
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
    assert response.status_code == 200
    body = response.json()
    assert body["latitude"] == 43.2389
    assert body["yaw_degrees"] == 123
    assert 0 <= body["confidence"] <= 1
