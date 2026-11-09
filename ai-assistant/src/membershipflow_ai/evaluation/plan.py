from __future__ import annotations

from typing import Protocol

from membershipflow_ai.domain.documents import SearchHit
from membershipflow_ai.retrieval.fusion import reciprocal_rank_fusion
from membershipflow_ai.retrieval.rerank import Reranker

RETRIEVER_MODES = ("keyword", "vector", "hybrid", "vector+rerank", "hybrid+rerank")


class SearchEngine(Protocol):
    async def keyword_search(
        self, query: str, *, k: int, source_types: list[str] | None = None
    ) -> list[SearchHit]: ...

    async def vector_search(
        self, embedding: list[float], *, k: int, source_types: list[str] | None = None
    ) -> list[SearchHit]: ...


class QueryEmbedder(Protocol):
    def embed_query(self, text: str) -> list[float]: ...


def validate_depths(k: int, candidates: int) -> None:
    if not 1 <= k <= candidates:
        raise ValueError(f"candidates({candidates}) >= k({k}) >= 1 이어야 한다")


async def retrieve_for_mode(
    mode: str,
    question: str,
    *,
    engine: SearchEngine,
    embeddings: QueryEmbedder,
    reranker: Reranker | None,
    k: int,
    candidates: int,
) -> tuple[list[SearchHit], list[SearchHit]]:
    """Build the candidate pool for one retriever mode, then cut it to k.

    Two properties matter for the comparison to mean anything:
    the candidate depth is the same for every mode, so reranking is measured
    against reranking and not against a deeper pool; and each mode issues only
    the searches it actually uses, so elapsed time is not inflated by a search
    whose results are discarded.
    """
    if mode not in RETRIEVER_MODES:
        raise ValueError(f"unknown retriever mode: {mode}")
    validate_depths(k, candidates)
    base = mode.removesuffix("+rerank")

    if base == "keyword":
        pool = (await engine.keyword_search(question, k=candidates))[:candidates]
    elif base == "vector":
        embedding = embeddings.embed_query(question)
        pool = (await engine.vector_search(embedding, k=candidates))[:candidates]
    else:
        keyword = await engine.keyword_search(question, k=candidates)
        vector = await engine.vector_search(embeddings.embed_query(question), k=candidates)
        pool = reciprocal_rank_fusion([keyword, vector], limit=candidates)

    if mode.endswith("+rerank"):
        if reranker is None:
            raise ValueError(f"{mode} requires a reranker")
        return reranker.rerank(question, pool, limit=k), list(pool)
    return pool[:k], list(pool)
