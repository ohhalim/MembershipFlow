from __future__ import annotations

from dataclasses import dataclass

from membershipflow_ai.domain.documents import EmbeddedChunk
from membershipflow_ai.ingestion.chunker import StructureAwareChunker
from membershipflow_ai.ingestion.embeddings import EmbeddingProvider
from membershipflow_ai.ingestion.parsers import ParserRegistry
from membershipflow_ai.ingestion.scanner import CorpusScanner
from membershipflow_ai.persistence.repository import CorpusRepository, VersionIdentity


@dataclass(frozen=True)
class IngestionSummary:
    run_id: str
    scanned_count: int
    changed_count: int
    unchanged_count: int
    removed_count: int
    chunk_count: int


class IngestionPipeline:
    def __init__(
        self,
        *,
        scanner: CorpusScanner,
        parsers: ParserRegistry,
        chunker: StructureAwareChunker,
        embeddings: EmbeddingProvider,
        repository: CorpusRepository,
        parser_version: str = "1",
        pipeline_version: str = "1",
    ) -> None:
        self._scanner = scanner
        self._parsers = parsers
        self._chunker = chunker
        self._embeddings = embeddings
        self._repository = repository
        self._parser_version = parser_version
        self._pipeline_version = pipeline_version

    async def run(self) -> IngestionSummary:
        run_id = await self._repository.create_run(
            self._scanner.corpus_version, self._pipeline_version
        )
        scanned_count = 0
        changed_count = 0
        unchanged_count = 0
        removed_count = 0
        chunk_count = 0
        try:
            documents = self._scanner.scan()
            scanned_count = len(documents)
            for document in documents:
                identity = VersionIdentity(
                    document.content_hash,
                    self._parser_version,
                    self._pipeline_version,
                    self._embeddings.model_id,
                    self._embeddings.revision,
                )
                if await self._repository.is_current(document, identity):
                    unchanged_count += 1
                    continue
                sections = self._parsers.parse(document)
                chunks = self._chunker.chunk(document, sections)
                vectors = self._embeddings.embed_documents([chunk.content for chunk in chunks])
                if len(vectors) != len(chunks):
                    raise RuntimeError("embedding result count does not match chunk count")
                embedded = [
                    EmbeddedChunk(chunk=chunk, embedding=tuple(vector))
                    for chunk, vector in zip(chunks, vectors, strict=True)
                ]
                result = await self._repository.activate_document(
                    document,
                    embedded,
                    corpus_version=self._scanner.corpus_version,
                    parser_version=self._parser_version,
                    pipeline_version=self._pipeline_version,
                    embedding_model=self._embeddings.model_id,
                    embedding_revision=self._embeddings.revision,
                )
                if result.changed:
                    changed_count += 1
                    chunk_count += result.chunk_count
                else:
                    unchanged_count += 1

            configured = {spec.source_uri for spec in self._scanner.specs}
            removed_count = await self._repository.mark_removed(configured)
            await self._repository.finish_run(
                run_id,
                status="SUCCEEDED",
                scanned_count=scanned_count,
                changed_count=changed_count,
                unchanged_count=unchanged_count,
                removed_count=removed_count,
                chunk_count=chunk_count,
            )
            return IngestionSummary(
                run_id=run_id,
                scanned_count=scanned_count,
                changed_count=changed_count,
                unchanged_count=unchanged_count,
                removed_count=removed_count,
                chunk_count=chunk_count,
            )
        except Exception as exc:
            await self._repository.finish_run(
                run_id,
                status="FAILED",
                scanned_count=scanned_count,
                changed_count=changed_count,
                unchanged_count=unchanged_count,
                removed_count=removed_count,
                chunk_count=chunk_count,
                error=f"{type(exc).__name__}: {exc}",
            )
            raise
