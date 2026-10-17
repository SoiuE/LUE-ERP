import tempfile
from pathlib import Path

import numpy as np

from app.store import VectorStore


def test_exact_cosine_top_limit_and_stable_order():
    with tempfile.TemporaryDirectory() as directory:
        store = VectorStore(str(Path(directory) / "index.sqlite"), "test-v1")
        for asset_id in range(1, 16):
            store.upsert(asset_id, np.asarray([1.0, asset_id / 100.0], dtype=np.float32))
        hits = store.search(np.asarray([1.0, 0.0], dtype=np.float32), 10)
        assert len(hits) == 10
        assert hits[0]["assetId"] == 1
        assert hits[0]["score"] >= hits[-1]["score"]


def test_empty_index_delete_reset_and_backup():
    with tempfile.TemporaryDirectory() as directory:
        store = VectorStore(str(Path(directory) / "index.sqlite"), "test-v1")
        assert store.search(np.asarray([1.0, 0.0], dtype=np.float32), 10) == []
        store.upsert(7, np.asarray([0.0, 1.0], dtype=np.float32))
        store.delete(7)
        assert store.stats()["count"] == 0
        assert Path(directory, "backups", store.backup(str(Path(directory) / "backups"))).is_file()
