from __future__ import annotations

from typing import Any

from elasticsearch import AsyncElasticsearch

from membershipflow_ai.domain.documents import ActiveChunk, SearchHit, SourceType


class CorruptedPathError(RuntimeError):
    """A document came back without a usable symbol_path.

    The symbol fallback still produces a non-empty path, so a missing field is
    invisible downstream: evaluation would score a correct hit as a miss and
    report a lower number instead of failing. Evaluation opts into strict mode
    to turn that into an explicit failure; the serving path keeps the fallback.
    """


def _hit_to_chunk(source: dict[str, Any], *, strict_path: bool = False) -> ActiveChunk:
    # symbol_path 가 원본 경로다. symbol 은 "." 으로 이어붙인 표시용이라
    # 섹션 제목에 "." 이 있으면 분해 결과가 원본과 달라진다.
    raw_path = source.get("symbol_path")
    if strict_path and not isinstance(raw_path, list):
        raise CorruptedPathError(
            f"symbol_path 누락 또는 배열 아님: chunk_id={source.get('chunk_id')!r} "
            f"type={type(raw_path).__name__}"
        )
    symbol = source.get("symbol") or ""
    return ActiveChunk(
        chunk_id=source["chunk_id"],
        source_uri=source["source_path"],
        source_type=SourceType(source["source_type"]),
        source_hash=source["source_hash"],
        ordinal=0,
        path=(
            tuple(str(part) for part in raw_path)
            if isinstance(raw_path, list)
            else tuple(part for part in symbol.split(".") if part)
        ),
        content=source["body"],
        line_start=source["line_start"],
        line_end=source["line_end"],
        embedding=(),
    )


class ElasticsearchRetriever:
    """Keyword and vector retrieval pinned to one physical index per request."""

    def __init__(
        self, client: AsyncElasticsearch, index: str, *, strict_path: bool = False
    ) -> None:
        self._client = client
        self._index = index
        self._strict_path = strict_path

    @property
    def index(self) -> str:
        return self._index

    async def keyword_search(
        self, query: str, *, k: int, source_types: list[str] | None = None
    ) -> list[SearchHit]:
        if k <= 0:
            return []
        must: dict[str, Any] = {
            "multi_match": {
                "query": query,
                "fields": ["body^1.0", "title^2.0", "symbol.text^1.5"],
            }
        }
        body: dict[str, Any] = {"bool": {"must": [must]}}
        if source_types:
            body["bool"]["filter"] = [{"terms": {"source_type": source_types}}]
        response = await self._client.search(index=self._index, size=k, query=body)
        self._raise_on_partial(response.body)
        return [
            SearchHit(
                chunk=_hit_to_chunk(hit["_source"], strict_path=self._strict_path),
                score=float(hit["_score"]),
                rank=index + 1,
                retriever="es_keyword",
            )
            for index, hit in enumerate(response.body["hits"]["hits"])
        ]

    async def vector_search(
        self, embedding: list[float], *, k: int, source_types: list[str] | None = None
    ) -> list[SearchHit]:
        if k <= 0:
            return []
        inner: dict[str, Any] = {"match_all": {}}
        if source_types:
            inner = {"bool": {"filter": [{"terms": {"source_type": source_types}}]}}
        # embedding 은 index=false 이므로 HNSW 가 아닌 정확 검색이다.
        query = {
            "script_score": {
                "query": inner,
                "script": {
                    "source": "cosineSimilarity(params.query_vector, 'embedding') + 1.0",
                    "params": {"query_vector": embedding},
                },
            }
        }
        response = await self._client.search(index=self._index, size=k, query=query)
        self._raise_on_partial(response.body)
        return [
            SearchHit(
                chunk=_hit_to_chunk(hit["_source"], strict_path=self._strict_path),
                score=float(hit["_score"]) - 1.0,
                rank=index + 1,
                retriever="es_vector_exact",
            )
            for index, hit in enumerate(response.body["hits"]["hits"])
        ]

    @staticmethod
    def _raise_on_partial(body: dict[str, Any]) -> None:
        """Timeout or shard failure must never be reported as a normal result."""
        if body.get("timed_out"):
            raise RuntimeError("elasticsearch search timed out")
        shards = body.get("_shards", {})
        if shards.get("failed"):
            raise RuntimeError(f"elasticsearch shard failure: {shards}")
