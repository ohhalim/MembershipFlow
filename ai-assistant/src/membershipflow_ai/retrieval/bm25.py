from __future__ import annotations

import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path
from typing import Any, cast

import bm25s  # type: ignore[import-untyped]

from membershipflow_ai.domain.documents import ActiveChunk, SearchHit


class Bm25ArtifactStore:
    _manifest_name = "current.json"

    def __init__(self, index_root: Path) -> None:
        self._index_root = index_root

    def build(self, chunks: list[ActiveChunk]) -> str:
        fingerprint = self._fingerprint(chunks)
        destination = self._index_root / fingerprint
        self._index_root.mkdir(parents=True, exist_ok=True)

        if not destination.is_dir():
            temporary = Path(tempfile.mkdtemp(prefix=f".{fingerprint}-", dir=self._index_root))
            try:
                if not chunks:
                    (temporary / "empty").touch()
                    os.replace(temporary, destination)
                    self._write_manifest(fingerprint)
                    return fingerprint
                tokenized = bm25s.tokenize(
                    [chunk.content for chunk in chunks],
                    stopwords=None,
                    return_ids=False,
                    show_progress=False,
                )
                retriever = bm25s.BM25(corpus=[chunk.chunk_id for chunk in chunks])
                retriever.index(tokenized, show_progress=False)
                retriever.save(temporary, corpus=[chunk.chunk_id for chunk in chunks])
                os.replace(temporary, destination)
            except Exception:
                shutil.rmtree(temporary, ignore_errors=True)
                raise

        self._write_manifest(fingerprint)
        return fingerprint

    def load(self) -> tuple[str, Any]:
        manifest = self._read_manifest()
        fingerprint = cast(str, manifest["fingerprint"])
        artifact_dir = self._index_root / fingerprint
        if not artifact_dir.is_dir():
            raise FileNotFoundError(f"BM25 artifact missing: {fingerprint}")
        return fingerprint, bm25s.BM25.load(
            artifact_dir,
            load_corpus=True,
            mmap=True,
            show_progress=False,
        )

    def search(self, query: str, chunks_by_id: dict[str, ActiveChunk], k: int) -> list[SearchHit]:
        if k <= 0 or not chunks_by_id:
            return []
        manifest = self._read_manifest()
        if manifest["fingerprint"] != self._fingerprint(list(chunks_by_id.values())):
            raise RuntimeError("BM25 corpus version mismatch; rerun ingestion before searching")
        _, retriever = self.load()
        query_tokens = bm25s.tokenize(
            [query], stopwords=None, return_ids=False, show_progress=False
        )
        result = retriever.retrieve(
            query_tokens,
            k=min(k, len(chunks_by_id)),
            show_progress=False,
        )
        document_ids = cast(list[list[str]], result.documents.tolist())
        scores = cast(list[list[float]], result.scores.tolist())
        hits: list[SearchHit] = []
        for index, (chunk_id, score) in enumerate(zip(document_ids[0], scores[0], strict=True)):
            chunk = chunks_by_id.get(chunk_id)
            if chunk is None:
                continue
            hits.append(
                SearchHit(chunk=chunk, score=float(score), rank=index + 1, retriever="bm25")
            )
        return hits

    def _write_manifest(self, fingerprint: str) -> None:
        payload = json.dumps({"fingerprint": fingerprint}, sort_keys=True)
        descriptor, name = tempfile.mkstemp(prefix=".current-", dir=self._index_root)
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
                handle.write(payload)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(name, self._index_root / self._manifest_name)
        except Exception:
            Path(name).unlink(missing_ok=True)
            raise

    def _read_manifest(self) -> dict[str, object]:
        manifest_file = self._index_root / self._manifest_name
        return cast(dict[str, object], json.loads(manifest_file.read_text(encoding="utf-8")))

    @staticmethod
    def _fingerprint(chunks: list[ActiveChunk]) -> str:
        chunk_ids = "\n".join(sorted(chunk.chunk_id for chunk in chunks))
        return hashlib.sha256(chunk_ids.encode()).hexdigest()
