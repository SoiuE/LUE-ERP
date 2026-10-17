import tempfile
from io import BytesIO
from pathlib import Path

import numpy as np
from fastapi.testclient import TestClient
from PIL import Image

from app.main import create_app
from app.store import VectorStore


class FakeEmbedder:
    version = "fake-v1"
    def embed(self, content: bytes) -> np.ndarray:
        with Image.open(BytesIO(content)) as image:
            red, green, blue = np.asarray(image.convert("RGB"), dtype=np.float32).mean(axis=(0, 1))
        return np.asarray([red, green, blue], dtype=np.float32)


def image(color: tuple[int, int, int]) -> bytes:
    output = BytesIO()
    Image.new("RGB", (8, 8), color).save(output, "PNG")
    return output.getvalue()


def test_internal_auth_real_embedding_flow_and_reset(monkeypatch):
    monkeypatch.setenv("IMAGE_SEARCH_INTERNAL_TOKEN", "test-token")
    with tempfile.TemporaryDirectory() as directory:
        store = VectorStore(str(Path(directory) / "index.sqlite"), "fake-v1")
        with TestClient(create_app(FakeEmbedder(), store)) as client:
            assert client.post("/internal/index?asset_id=1", files={"file": ("red.png", image((255, 0, 0)), "image/png")}).status_code == 401
            headers = {"X-Internal-Token": "test-token"}
            client.post("/internal/index?asset_id=1", headers=headers, files={"file": ("red.png", image((255, 0, 0)), "image/png")}).raise_for_status()
            client.post("/internal/index?asset_id=2", headers=headers, files={"file": ("green.png", image((0, 255, 0)), "image/png")}).raise_for_status()
            response = client.post("/internal/search?limit=10", headers=headers, files={"file": ("query.png", image((250, 5, 0)), "image/png")})
            assert response.status_code == 200
            assert response.json()["items"][0]["assetId"] == 1
            client.post("/internal/index/reset", headers=headers).raise_for_status()
            assert store.stats()["count"] == 0
