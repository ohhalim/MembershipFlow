from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from typing import cast
from uuid import UUID

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.orm import selectinload

from membershipflow_ai.domain.documents import (
    ActiveChunk,
    EmbeddedChunk,
    SourceDocument,
    SourceType,
)
from membershipflow_ai.persistence.models import (
    DocumentChunkModel,
    DocumentSourceModel,
    DocumentVersionModel,
    IngestionRunModel,
)


@dataclass(frozen=True)
class VersionIdentity:
    content_hash: str
    parser_version: str
    pipeline_version: str
    embedding_model: str
    embedding_revision: str | None


@dataclass(frozen=True)
class ActivationResult:
    changed: bool
    chunk_count: int


class CorpusRepository:
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def is_current(self, document: SourceDocument, identity: VersionIdentity) -> bool:
        async with self._session_factory() as session:
            row = (
                await session.execute(
                    select(DocumentSourceModel, DocumentVersionModel)
                    .join(
                        DocumentVersionModel,
                        DocumentSourceModel.current_version_id == DocumentVersionModel.id,
                    )
                    .where(
                        DocumentSourceModel.source_uri == document.source_uri,
                        DocumentSourceModel.deleted_at.is_(None),
                    )
                )
            ).first()
            return bool(
                row
                and row[0].source_type == document.source_type.value
                and self._same_identity(row[1], identity)
            )

    async def activate_document(
        self,
        document: SourceDocument,
        chunks: list[EmbeddedChunk],
        *,
        corpus_version: int,
        parser_version: str,
        pipeline_version: str,
        embedding_model: str,
        embedding_revision: str | None,
    ) -> ActivationResult:
        identity = VersionIdentity(
            content_hash=document.content_hash,
            parser_version=parser_version,
            pipeline_version=pipeline_version,
            embedding_model=embedding_model,
            embedding_revision=embedding_revision,
        )
        async with self._session_factory() as session, session.begin():
            source = await self._locked_source(session, document.source_uri)
            if source is None:
                source = DocumentSourceModel(
                    source_uri=document.source_uri,
                    source_type=document.source_type.value,
                )
                session.add(source)
                await session.flush()
            elif source.source_type != document.source_type.value:
                raise ValueError(
                    f"source type changed for {document.source_uri}: "
                    f"{source.source_type} -> {document.source_type.value}"
                )

            if source.current_version_id is not None:
                current = await session.get(DocumentVersionModel, source.current_version_id)
                if current is not None and self._same_identity(current, identity):
                    source.deleted_at = None
                    return ActivationResult(changed=False, chunk_count=0)

            version = await self._find_version(session, source.id, identity)
            if version is None:
                version = DocumentVersionModel(
                    source_id=source.id,
                    content_hash=identity.content_hash,
                    parser_version=identity.parser_version,
                    pipeline_version=identity.pipeline_version,
                    embedding_model=identity.embedding_model,
                    embedding_revision=identity.embedding_revision,
                    embedding_revision_key=identity.embedding_revision or "",
                    corpus_version=corpus_version,
                )
                session.add(version)
                await session.flush()
                session.add_all(
                    DocumentChunkModel(
                        chunk_id=hashlib.sha256(
                            (
                                embedded.chunk.chunk_id
                                + json.dumps(asdict(identity), sort_keys=True)
                            ).encode()
                        ).hexdigest(),
                        version_id=version.id,
                        ordinal=embedded.chunk.ordinal,
                        path=list(embedded.chunk.path),
                        content=embedded.chunk.content,
                        line_start=embedded.chunk.line_start,
                        line_end=embedded.chunk.line_end,
                        embedding=list(embedded.embedding),
                    )
                    for embedded in chunks
                )

            source.current_version_id = version.id
            source.deleted_at = None
            return ActivationResult(changed=True, chunk_count=len(chunks))

    async def mark_removed(self, configured_source_uris: set[str]) -> int:
        async with self._session_factory() as session, session.begin():
            query = select(DocumentSourceModel).where(
                DocumentSourceModel.current_version_id.is_not(None)
            )
            sources = (await session.scalars(query)).all()
            removed = [
                source for source in sources if source.source_uri not in configured_source_uris
            ]
            now = datetime.now(UTC)
            for source in removed:
                source.current_version_id = None
                source.deleted_at = now
            return len(removed)

    async def active_chunks(self) -> list[ActiveChunk]:
        async with self._session_factory() as session:
            query = (
                select(DocumentChunkModel, DocumentVersionModel, DocumentSourceModel)
                .join(
                    DocumentVersionModel,
                    DocumentChunkModel.version_id == DocumentVersionModel.id,
                )
                .join(
                    DocumentSourceModel,
                    DocumentSourceModel.current_version_id == DocumentVersionModel.id,
                )
                .where(DocumentSourceModel.deleted_at.is_(None))
                .order_by(DocumentSourceModel.source_uri, DocumentChunkModel.ordinal)
            )
            rows = (await session.execute(query)).all()
            return [
                ActiveChunk(
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
                )
                for chunk, version, source in rows
            ]

    async def active_chunk_count(self) -> int:
        return len(await self.active_chunks())

    async def create_run(self, corpus_version: int, pipeline_version: str) -> str:
        async with self._session_factory() as session, session.begin():
            run = IngestionRunModel(
                corpus_version=corpus_version,
                pipeline_version=pipeline_version,
                status="RUNNING",
            )
            session.add(run)
            await session.flush()
            return str(run.id)

    async def finish_run(
        self,
        run_id: str,
        *,
        status: str,
        scanned_count: int,
        changed_count: int,
        unchanged_count: int,
        removed_count: int,
        chunk_count: int,
        error: str | None = None,
    ) -> None:
        async with self._session_factory() as session, session.begin():
            await session.execute(
                update(IngestionRunModel)
                .where(IngestionRunModel.id == run_id)
                .values(
                    status=status,
                    scanned_count=scanned_count,
                    changed_count=changed_count,
                    unchanged_count=unchanged_count,
                    removed_count=removed_count,
                    chunk_count=chunk_count,
                    error=error,
                    finished_at=datetime.now(UTC),
                )
            )

    @staticmethod
    async def _locked_source(session: AsyncSession, source_uri: str) -> DocumentSourceModel | None:
        query = (
            select(DocumentSourceModel)
            .where(DocumentSourceModel.source_uri == source_uri)
            .with_for_update()
        )
        return cast(DocumentSourceModel | None, await session.scalar(query))

    @staticmethod
    async def _find_version(
        session: AsyncSession,
        source_id: UUID,
        identity: VersionIdentity,
    ) -> DocumentVersionModel | None:
        query = (
            select(DocumentVersionModel)
            .where(
                DocumentVersionModel.source_id == source_id,
                DocumentVersionModel.content_hash == identity.content_hash,
                DocumentVersionModel.parser_version == identity.parser_version,
                DocumentVersionModel.pipeline_version == identity.pipeline_version,
                DocumentVersionModel.embedding_model == identity.embedding_model,
                DocumentVersionModel.embedding_revision_key == (identity.embedding_revision or ""),
            )
            .options(selectinload(DocumentVersionModel.chunks))
        )
        return cast(DocumentVersionModel | None, await session.scalar(query))

    @staticmethod
    def _same_identity(version: DocumentVersionModel, identity: VersionIdentity) -> bool:
        return (
            version.content_hash == identity.content_hash
            and version.parser_version == identity.parser_version
            and version.pipeline_version == identity.pipeline_version
            and version.embedding_model == identity.embedding_model
            and version.embedding_revision_key == (identity.embedding_revision or "")
        )
