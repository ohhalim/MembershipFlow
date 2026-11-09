"""후보 선정은 production 함수를 그대로 호출해 검증한다.

테스트 안에 retrieve 를 복제하면 production 이 옛 분기로 회귀해도 통과한다.
"""

from __future__ import annotations

import pytest

from membershipflow_ai.domain.documents import ActiveChunk, SearchHit, SourceType
from membershipflow_ai.evaluation.plan import retrieve_for_mode, validate_depths


def make_hit(rank: int, name: str) -> SearchHit:
    return SearchHit(
        chunk=ActiveChunk(
            chunk_id=f"{name}-{rank}", source_uri="S.java", source_type=SourceType.JAVA,
            source_hash="h", ordinal=0, path=("pkg", "S", name), content="body",
            line_start=1, line_end=2, embedding=(),
        ),
        score=1.0 / rank, rank=rank, retriever=name,
    )


class SpyEngine:
    def __init__(self) -> None:
        self.keyword_k: list[int] = []
        self.vector_k: list[int] = []

    async def keyword_search(self, query: str, *, k: int, **_: object) -> list[SearchHit]:
        self.keyword_k.append(k)
        return [make_hit(i + 1, "kw") for i in range(k)]

    async def vector_search(
        self, embedding: list[float], *, k: int, **_: object
    ) -> list[SearchHit]:
        self.vector_k.append(k)
        return [make_hit(i + 1, "vec") for i in range(k)]


class SpyEmbedder:
    def __init__(self) -> None:
        self.calls = 0

    def embed_query(self, text: str) -> list[float]:
        self.calls += 1
        return [0.0]


class SpyReranker:
    def __init__(self) -> None:
        self.pool_sizes: list[int] = []

    @property
    def model_id(self) -> str:
        return "spy-reranker"

    def rerank(self, query: str, hits: list[SearchHit], *, limit: int) -> list[SearchHit]:
        self.pool_sizes.append(len(hits))
        return list(hits)[:limit]


async def run(mode: str, *, k: int = 10, candidates: int = 30):
    engine, embedder, reranker = SpyEngine(), SpyEmbedder(), SpyReranker()
    hits, pool = await retrieve_for_mode(
        mode, "질문", engine=engine, embeddings=embedder,
        reranker=reranker, k=k, candidates=candidates,
    )
    return hits, pool, engine, embedder, reranker


@pytest.mark.parametrize(
    "mode", ["keyword", "vector", "hybrid", "vector+rerank", "hybrid+rerank"]
)
async def test_candidate_depth_is_identical_across_modes(mode: str) -> None:
    hits, pool, engine, _, reranker = await run(mode)
    assert len(pool) == 30
    assert len(hits) == 10
    for depth in engine.keyword_k + engine.vector_k:
        assert depth == 30
    if mode.endswith("+rerank"):
        assert reranker.pool_sizes == [30]


@pytest.mark.parametrize("mode", ["vector", "vector+rerank"])
async def test_vector_mode_does_not_run_keyword_search(mode: str) -> None:
    """keyword 결과를 버리면서 시간만 쓰면 모드별 시간 비교가 무의미해진다."""
    _, _, engine, embedder, _ = await run(mode)
    assert engine.keyword_k == []
    assert engine.vector_k == [30]
    assert embedder.calls == 1


@pytest.mark.parametrize("mode", ["keyword"])
async def test_keyword_mode_does_not_embed(mode: str) -> None:
    _, _, engine, embedder, _ = await run(mode)
    assert engine.vector_k == []
    assert embedder.calls == 0


@pytest.mark.parametrize("mode", ["hybrid", "hybrid+rerank"])
async def test_hybrid_runs_both_searches(mode: str) -> None:
    _, _, engine, embedder, _ = await run(mode)
    assert engine.keyword_k == [30]
    assert engine.vector_k == [30]
    assert embedder.calls == 1


async def test_rerank_requires_a_reranker() -> None:
    engine, embedder = SpyEngine(), SpyEmbedder()
    with pytest.raises(ValueError, match="requires a reranker"):
        await retrieve_for_mode(
            "vector+rerank", "q", engine=engine, embeddings=embedder,
            reranker=None, k=5, candidates=10,
        )


async def test_unknown_mode_is_rejected() -> None:
    engine, embedder = SpyEngine(), SpyEmbedder()
    with pytest.raises(ValueError, match="unknown retriever mode"):
        await retrieve_for_mode(
            "bm25", "q", engine=engine, embeddings=embedder,
            reranker=None, k=5, candidates=10,
        )


@pytest.mark.parametrize(("k", "candidates"), [(10, 5), (0, 10), (-1, 10)])
def test_invalid_depths_are_rejected(k: int, candidates: int) -> None:
    with pytest.raises(ValueError, match="이어야 한다"):
        validate_depths(k, candidates)
