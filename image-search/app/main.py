from __future__ import annotations

import hmac
import os
from contextlib import asynccontextmanager
from io import BytesIO

from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile
from PIL import Image, UnidentifiedImageError

from .model import OpenClipEmbedder
from .store import VectorStore

ALLOWED_TYPES = {"image/jpeg", "image/png", "image/gif"}
MAX_BYTES = int(os.getenv("IMAGE_SEARCH_MAX_BYTES", "10485760"))
MAX_PIXELS = int(os.getenv("IMAGE_SEARCH_MAX_PIXELS", "20000000"))
MODEL_NAME = os.getenv("IMAGE_SEARCH_MODEL", "ViT-B-32")
PRETRAINED = os.getenv("IMAGE_SEARCH_PRETRAINED", "laion2b_s34b_b79k")
MODEL_VERSION = f"openclip:{MODEL_NAME}:{PRETRAINED}"


def validate_image(content: bytes, content_type: str | None) -> bytes:
    declared = (content_type or "").split(";", 1)[0].lower()
    if declared not in ALLOWED_TYPES:
        raise HTTPException(400, "only JPEG, PNG and GIF images are supported")
    if not content or len(content) > MAX_BYTES:
        raise HTTPException(400, "image is empty or exceeds the size limit")
    try:
        with Image.open(BytesIO(content)) as image:
            actual = (image.format or "").upper()
            expected = {"image/jpeg": "JPEG", "image/png": "PNG", "image/gif": "GIF"}[declared]
            if actual != expected:
                raise HTTPException(400, "image content does not match its MIME type")
            if image.width < 1 or image.height < 1 or image.width * image.height > MAX_PIXELS:
                raise HTTPException(400, "image dimensions exceed the limit")
            image.verify()
    except HTTPException:
        raise
    except (UnidentifiedImageError, OSError, ValueError) as exc:
        raise HTTPException(400, "image cannot be decoded") from exc
    return content


def create_app(embedder=None, store: VectorStore | None = None) -> FastAPI:
    state: dict[str, object] = {"embedder": embedder, "store": store, "error": None}

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        if state["embedder"] is None:
            try:
                state["embedder"] = OpenClipEmbedder(MODEL_NAME, PRETRAINED)
                state["store"] = VectorStore(os.getenv("IMAGE_SEARCH_INDEX_PATH", "/data/image-index.sqlite"), MODEL_VERSION)
            except Exception as exc:  # Keep health and clear 503 errors available when model download fails.
                state["error"] = str(exc)[:1000]
        yield

    app = FastAPI(title="LUE ERP Image Search", docs_url=None, redoc_url=None, lifespan=lifespan)

    def authorize(x_internal_token: str | None = Header(default=None)) -> None:
        expected = os.getenv("IMAGE_SEARCH_INTERNAL_TOKEN", "")
        if not expected or not x_internal_token or not hmac.compare_digest(expected, x_internal_token):
            raise HTTPException(401, "invalid internal token")

    def ready():
        if state["embedder"] is None or state["store"] is None:
            raise HTTPException(503, f"model unavailable: {state['error'] or 'not loaded'}")
        return state["embedder"], state["store"]

    @app.get("/health")
    def health():
        if state["embedder"] is None or state["store"] is None:
            raise HTTPException(503, detail={"ready": False, "error": state["error"]})
        return {"ready": True, **state["store"].stats()}

    @app.post("/internal/index", dependencies=[Depends(authorize)])
    async def index(asset_id: int, file: UploadFile = File(...)):
        model, index_store = ready()
        content = validate_image(await file.read(MAX_BYTES + 1), file.content_type)
        index_store.upsert(asset_id, model.embed(content))
        return {"assetId": asset_id, **index_store.stats()}

    @app.delete("/internal/index/{asset_id}", dependencies=[Depends(authorize)])
    def delete(asset_id: int):
        _, index_store = ready()
        index_store.delete(asset_id)
        return {"deleted": True, "assetId": asset_id}

    @app.post("/internal/search", dependencies=[Depends(authorize)])
    async def search(limit: int = 100, file: UploadFile = File(...)):
        model, index_store = ready()
        content = validate_image(await file.read(MAX_BYTES + 1), file.content_type)
        return {"items": index_store.search(model.embed(content), limit), **index_store.stats()}

    @app.post("/internal/index/reset", dependencies=[Depends(authorize)])
    def reset():
        _, index_store = ready()
        index_store.reset()
        return {"reset": True, **index_store.stats()}

    @app.post("/internal/index/backup", dependencies=[Depends(authorize)])
    def backup():
        _, index_store = ready()
        return {"filename": index_store.backup(os.getenv("IMAGE_SEARCH_BACKUP_DIR", "/backups"))}

    return app


app = create_app()
