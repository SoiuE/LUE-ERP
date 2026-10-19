from __future__ import annotations

import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path

import numpy as np


class VectorStore:
    """Small single-process exact cosine index with SQLite durability."""

    def __init__(self, path: str, model_version: str):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.model_version = model_version
        self._lock = threading.RLock()
        self._connection = sqlite3.connect(self.path, check_same_thread=False)
        self._connection.execute("PRAGMA journal_mode=WAL")
        self._connection.execute("PRAGMA synchronous=FULL")
        self._connection.execute(
            "CREATE TABLE IF NOT EXISTS vectors (asset_id INTEGER PRIMARY KEY, model_version TEXT NOT NULL, dimension INTEGER NOT NULL, vector BLOB NOT NULL, updated_at TEXT NOT NULL)"
        )
        self._connection.commit()
        self._ids = np.empty((0,), dtype=np.int64)
        self._matrix = np.empty((0, 0), dtype=np.float32)
        self._reload()

    def _reload(self) -> None:
        rows = self._connection.execute(
            "SELECT asset_id,dimension,vector FROM vectors WHERE model_version=? ORDER BY asset_id", (self.model_version,)
        ).fetchall()
        if not rows:
            self._ids = np.empty((0,), dtype=np.int64)
            self._matrix = np.empty((0, 0), dtype=np.float32)
            return
        dimensions = {int(row[1]) for row in rows}
        if len(dimensions) != 1:
            raise RuntimeError("index contains inconsistent vector dimensions")
        dimension = dimensions.pop()
        self._ids = np.asarray([int(row[0]) for row in rows], dtype=np.int64)
        self._matrix = np.vstack([np.frombuffer(row[2], dtype=np.float32, count=dimension) for row in rows])

    @staticmethod
    def _normalized(vector: np.ndarray) -> np.ndarray:
        value = np.asarray(vector, dtype=np.float32).reshape(-1)
        norm = float(np.linalg.norm(value))
        if not np.isfinite(norm) or norm <= 0:
            raise ValueError("embedding is empty or invalid")
        return value / norm

    def upsert(self, asset_id: int, vector: np.ndarray) -> None:
        value = self._normalized(vector)
        with self._lock:
            if self._matrix.size and self._matrix.shape[1] != value.size:
                raise ValueError("embedding dimension does not match index")
            self._connection.execute(
                "INSERT INTO vectors(asset_id,model_version,dimension,vector,updated_at) VALUES (?,?,?,?,?) "
                "ON CONFLICT(asset_id) DO UPDATE SET model_version=excluded.model_version,dimension=excluded.dimension,vector=excluded.vector,updated_at=excluded.updated_at",
                (asset_id, self.model_version, value.size, value.tobytes(), datetime.now(timezone.utc).isoformat()),
            )
            self._connection.commit()
            position = int(np.searchsorted(self._ids, asset_id))
            if position < self._ids.size and int(self._ids[position]) == asset_id:
                self._matrix[position] = value
            elif self._ids.size == 0:
                self._ids = np.asarray([asset_id], dtype=np.int64)
                self._matrix = value.reshape(1, -1)
            else:
                self._ids = np.insert(self._ids, position, asset_id)
                self._matrix = np.insert(self._matrix, position, value, axis=0)

    def delete(self, asset_id: int) -> None:
        with self._lock:
            self._connection.execute("DELETE FROM vectors WHERE asset_id=?", (asset_id,))
            self._connection.commit()
            matches = np.flatnonzero(self._ids == asset_id)
            if matches.size:
                self._ids = np.delete(self._ids, matches[0])
                self._matrix = np.delete(self._matrix, matches[0], axis=0)
                if self._ids.size == 0:
                    self._matrix = np.empty((0, 0), dtype=np.float32)

    def reset(self) -> None:
        with self._lock:
            self._connection.execute("DELETE FROM vectors")
            self._connection.commit()
            self._ids = np.empty((0,), dtype=np.int64)
            self._matrix = np.empty((0, 0), dtype=np.float32)

    def search(self, vector: np.ndarray, limit: int) -> list[dict[str, float | int]]:
        query = self._normalized(vector)
        with self._lock:
            if self._ids.size == 0:
                return []
            if self._matrix.shape[1] != query.size:
                raise ValueError("query embedding dimension does not match index")
            scores = self._matrix @ query
            order = np.argsort(-scores, kind="stable")[: max(1, min(limit, 200))]
            return [{"assetId": int(self._ids[index]), "score": float(scores[index])} for index in order]

    def stats(self) -> dict[str, int | str]:
        with self._lock:
            return {"count": int(self._ids.size), "dimension": int(self._matrix.shape[1]) if self._matrix.ndim == 2 and self._matrix.size else 0, "modelVersion": self.model_version}

    def backup(self, directory: str) -> str:
        target_dir = Path(directory)
        target_dir.mkdir(parents=True, exist_ok=True)
        target = target_dir / f"image-index-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}.sqlite"
        with self._lock, sqlite3.connect(target) as destination:
            self._connection.backup(destination)
        return target.name
