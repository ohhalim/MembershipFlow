from __future__ import annotations

from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from membershipflow_ai.domain.documents import ActiveChunk, SearchHit, SourceType
from membershipflow_ai.persistence.models import (
    DocumentChunkModel,
    DocumentSourceModel,
    DocumentVersionModel,
)


class VectorRetriever:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def search(
        self,
        query_embedding: list[float],
        *,
        k: int = 10,
        exact: bool = False,
    ) -> list[SearchHit]:
        if k <= 0:
            return []
        distance = DocumentChunkModel.embedding.cosine_distance(query_embedding)
        async with self._session_factory() as session, session.begin():
            if exact:
                await session.execute(text("SET LOCAL enable_indexscan = off"))
                await session.execute(text("SET LOCAL enable_bitmapscan = off"))
            query = (
                select(
                    DocumentChunkModel,
                    DocumentVersionModel,
                    DocumentSourceModel,
                    distance.label("distance"),
                )
                .join(
                    DocumentVersionModel, DocumentChunkModel.version_id == DocumentVersionModel.id
                )
                .join(
                    DocumentSourceModel,
                    DocumentSourceModel.current_version_id == DocumentVersionModel.id,
                )
                .where(DocumentSourceModel.deleted_at.is_(None))
                .order_by(distance)
                .limit(k)
            )
            rows = (await session.execute(query)).all()
        return [
            SearchHit(
                chunk=ActiveChunk(
                    chunk_id=chunk.chunk_id,
                    source_uri=source.source_uri,
                    source_type=SourceType(source.source_type),
                    source_hash=version.content_hash,
                    ordinal=chunk.ordinal,
                    path=tuple(chunk.path),
                    content=chunk.content,
                    line_start=chunk.line_start,
                    line_end=chunk.line_end,
                    embedding=tuple(float(value) for value in chunk.embedding),
                ),
                score=1.0 - float(distance_value),
                rank=index + 1,
                retriever="vector_exact" if exact else "vector_index_allowed",
            )
            for index, (chunk, version, source, distance_value) in enumerate(rows)
        ]
