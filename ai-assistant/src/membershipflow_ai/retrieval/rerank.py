from __future__ import annotations

from collections.abc import Sequence
from dataclasses import replace
from typing import Protocol, cast

from membershipflow_ai.domain.documents import SearchHit


class Reranker(Protocol):
    @property
    def model_id(self) -> str: ...

    def rerank(self, query: str, hits: Sequence[SearchHit], *, limit: int) -> list[SearchHit]: ...


class CrossEncoderReranker:
    """Re-score candidates with a cross-encoder.

    Bi-encoder retrieval embeds the question and the chunk separately, so it can
    only compare them through their vectors. A cross-encoder reads the pair
    together, which is what recovers cases where the question and the code share
    meaning but no words.
    """

    def __init__(self, model_id: str, revision: str | None = None) -> None:
        from sentence_transformers import CrossEncoder

        self._model = CrossEncoder(model_id, revision=revision)
        self._model_id = model_id

    @property
    def model_id(self) -> str:
        return self._model_id

    def rerank(self, query: str, hits: Sequence[SearchHit], *, limit: int) -> list[SearchHit]:
        if not hits or limit <= 0:
            return []
        scores = cast(
            "list[float]",
            self._model.predict([(query, hit.chunk.content) for hit in hits]).tolist(),
        )
        ordered = sorted(
            zip(hits, scores, strict=True),
            key=lambda pair: (-pair[1], pair[0].chunk.chunk_id),
        )
        return [
            replace(hit, score=float(score), rank=position + 1, retriever=f"rerank:{hit.retriever}")
            for position, (hit, score) in enumerate(ordered[:limit])
        ]
