from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from typing import Any

from elasticsearch import AsyncElasticsearch, NotFoundError

from membershipflow_ai.domain.documents import ActiveChunk

SCHEMA_REVISION = "2"


def index_settings() -> dict[str, Any]:
    return {
        "number_of_shards": 1,
        "number_of_replicas": 0,
        "analysis": {
            "analyzer": {
                "symbol_analyzer": {
                    "tokenizer": "standard",
                    "filter": ["word_delimiter_symbol", "lowercase"],
                }
            },
            "filter": {
                "word_delimiter_symbol": {
                    "type": "word_delimiter_graph",
                    "generate_word_parts": True,
                    "generate_number_parts": True,
                    "split_on_case_change": True,
                    "preserve_original": True,
                }
            },
        },
    }


def index_mappings(dimension: int) -> dict[str, Any]:
    """Document contract from AI-ELASTICSEARCH-IMPLEMENTATION-PLAN.md section 2."""
    return {
        "dynamic": "strict",
        "properties": {
            "chunk_id": {"type": "keyword"},
            # body: Nori 기본. std 하위 필드로 기본 분석기와 평가셋 비교가 가능하다.
            "body": {
                "type": "text",
                "analyzer": "nori",
                "fields": {"std": {"type": "text", "analyzer": "standard"}},
            },
            "title": {"type": "text", "analyzer": "nori"},
            # symbol: 정확 필터용 keyword + camelCase 분해 검색용 하위 필드
            "symbol": {
                "type": "keyword",
                "fields": {"text": {"type": "text", "analyzer": "symbol_analyzer"}},
            },
            # symbol 은 "." 으로 이어붙인 표시용 문자열이라 경로를 되돌릴 수 없다.
            # 섹션 제목이나 패키지명에 "." 이 들어가면 분해 결과가 원본과 달라진다.
            # 경로는 배열로 따로 보관해 원본 그대로 복원한다.
            "symbol_path": {"type": "keyword"},
            "source_path": {"type": "keyword"},
            "source_type": {"type": "keyword"},
            "source_hash": {"type": "keyword"},
            "corpus_version": {"type": "keyword"},
            "line_start": {"type": "integer"},
            "line_end": {"type": "integer"},
            # HNSW 는 지연 측정 후 별도 실험. baseline 은 script_score 정확 검색이다.
            "embedding": {"type": "dense_vector", "dims": dimension, "index": False},
        },
    }


@dataclass(frozen=True)
class BuildManifest:
    build_id: str
    physical_index: str
    corpus_fingerprint: str
    schema_revision: str
    embedding_model: str
    embedding_revision: str | None
    dimension: int
    source_count: int
    chunk_count: int


@dataclass
class BulkFailure:
    chunk_id: str
    reason: str


@dataclass
class IndexReport:
    manifest: BuildManifest
    failures: list[BulkFailure] = field(default_factory=list)


class ElasticsearchStore:
    def __init__(self, client: AsyncElasticsearch, alias: str) -> None:
        self._client = client
        self._alias = alias

    @staticmethod
    def corpus_fingerprint(chunks: list[ActiveChunk]) -> str:
        payload = "\n".join(sorted(f"{c.chunk_id}:{c.source_hash}" for c in chunks))
        return hashlib.sha256(payload.encode()).hexdigest()

    def build_id(
        self, chunks: list[ActiveChunk], embedding_model: str, revision: str | None, dimension: int
    ) -> str:
        seed = "|".join(
            [
                self.corpus_fingerprint(chunks),
                SCHEMA_REVISION,
                embedding_model,
                revision or "",
                str(dimension),
            ]
        )
        return hashlib.sha256(seed.encode()).hexdigest()[:16]

    def physical_index(self, build_id: str) -> str:
        return f"{self._alias}-{build_id}"

    async def active_index(self) -> str | None:
        try:
            response = await self._client.indices.get_alias(name=self._alias)
        except NotFoundError:
            return None
        names = sorted(str(name) for name in response.body)
        if len(names) != 1:
            raise RuntimeError(f"alias {self._alias} must point to exactly one index: {names}")
        return names[0]

    async def create(self, index: str, dimension: int) -> None:
        await self._client.indices.create(
            index=index,
            settings=index_settings(),
            mappings=index_mappings(dimension),
        )

    async def bulk_index(
        self, index: str, chunks: list[ActiveChunk], corpus_version: str
    ) -> list[BulkFailure]:
        operations: list[dict[str, Any]] = []
        for chunk in chunks:
            operations.append({"index": {"_index": index, "_id": chunk.chunk_id}})
            operations.append(
                {
                    "chunk_id": chunk.chunk_id,
                    "body": chunk.content,
                    "title": chunk.path[-1] if chunk.path else chunk.source_uri,
                    "symbol": ".".join(chunk.path) if chunk.path else chunk.source_uri,
                    "symbol_path": list(chunk.path),
                    "source_path": chunk.source_uri,
                    "source_type": str(chunk.source_type),
                    "source_hash": chunk.source_hash,
                    "corpus_version": corpus_version,
                    "line_start": chunk.line_start,
                    "line_end": chunk.line_end,
                    "embedding": list(chunk.embedding),
                }
            )
        if not operations:
            return []
        response = await self._client.bulk(operations=operations, refresh=True)
        failures: list[BulkFailure] = []
        for item in response.body.get("items", []):
            result = item.get("index", {})
            if result.get("error") is not None:
                failures.append(
                    BulkFailure(chunk_id=result.get("_id", "?"), reason=str(result["error"]))
                )
        return failures

    async def verify(self, index: str, expected_chunk_ids: set[str]) -> None:
        await self._client.indices.refresh(index=index)
        response = (await self._client.count(index=index)).body
        if response.get("_shards", {}).get("failed", 0):
            raise RuntimeError("chunk verification failed: count has failed shards")
        count = response["count"]
        if count != len(expected_chunk_ids):
            raise RuntimeError(
                f"chunk count mismatch: indexed={count} expected={len(expected_chunk_ids)}"
            )
        # Equal counts alone allow missing chunks to be replaced by unrelated IDs.
        # This verifies an immutable build index; callers must prevent concurrent writes.
        expected = sorted(expected_chunk_ids)
        for start in range(0, len(expected), 500):
            batch = expected[start : start + 500]
            response = (await self._client.mget(
                index=index, ids=batch, source=False, realtime=False,
            )).body
            docs = response.get("docs", [])
            found = {
                doc.get("_id") for doc in docs
                if doc.get("found") is True and not doc.get("error")
            }
            if len(docs) != len(batch) or found != set(batch):
                missing = sorted(set(batch) - found)
                raise RuntimeError(f"chunk ID mismatch: missing={missing}")

    async def publish(self, index: str) -> str:
        """Switch the read alias to `index`. Returns the previous index, if any."""
        previous = await self.active_index()
        if previous == index:
            return previous
        actions: list[dict[str, Any]] = []
        if previous is not None:
            actions.append({"remove": {"index": previous, "alias": self._alias}})
        actions.append({"add": {"index": index, "alias": self._alias}})
        await self._client.indices.update_aliases(actions=actions)
        return previous or ""
