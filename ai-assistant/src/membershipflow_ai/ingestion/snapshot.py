from __future__ import annotations

import math
from dataclasses import asdict
from typing import Any

from membershipflow_ai.domain.documents import ActiveChunk
from membershipflow_ai.ingestion.chunker import RegexTokenCounter, StructureAwareChunker
from membershipflow_ai.ingestion.embeddings import EmbeddingProvider
from membershipflow_ai.ingestion.parsers import ParserRegistry
from membershipflow_ai.ingestion.scanner import CorpusScanner

SNAPSHOT_REVISION = "1"


def prepare_snapshot(
    scanner: CorpusScanner, embeddings: EmbeddingProvider,
) -> tuple[list[ActiveChunk], dict[str, Any]]:
    """Read and validate the whole corpus before touching an index.

    The in-memory document contents are the build input. Files changing after scan
    do not alter this build; the manifest records the hashes actually read.
    """
    documents = scanner.scan()
    if not documents:
        raise ValueError("empty corpus is not a build")
    parser = ParserRegistry()
    chunker = StructureAwareChunker(RegexTokenCounter())
    pending = []
    for document in documents:
        chunks = chunker.chunk(document, parser.parse(document))
        if not chunks:
            raise ValueError(f"source produced no chunks: {document.source_uri}")
        pending.extend(chunks)
    if len({chunk.chunk_id for chunk in pending}) != len(pending):
        raise ValueError("duplicate chunk IDs")
    if embeddings.dimension <= 0:
        raise ValueError("embedding dimension must be positive")

    active: list[ActiveChunk] = []
    # Bound embedding requests; all sources have already passed parsing.
    for start in range(0, len(pending), 32):
        batch = pending[start : start + 32]
        vectors = embeddings.embed_documents([chunk.content for chunk in batch])
        if len(vectors) != len(batch):
            raise ValueError("embedding result count does not match chunk count")
        for chunk, vector in zip(batch, vectors, strict=True):
            if len(vector) != embeddings.dimension or not all(math.isfinite(v) for v in vector):
                raise ValueError(f"invalid embedding: {chunk.chunk_id}")
            if not any(v != 0 for v in vector):
                raise ValueError(f"zero embedding: {chunk.chunk_id}")
            active.append(ActiveChunk(**asdict(chunk), embedding=tuple(vector)))
    return active, {
        "snapshot_revision": SNAPSHOT_REVISION,
        "corpus_version": scanner.corpus_version,
        "parser_revision": "1",
        "chunker": {"token_counter": "regex", "chunk_size": 512, "overlap": 64},
        "sources": [
            {"source_uri": doc.source_uri, "source_hash": doc.content_hash}
            for doc in documents
        ],
        "expected_chunk_ids": sorted(chunk.chunk_id for chunk in active),
        "embedding_model": embeddings.model_id,
        "embedding_revision": embeddings.revision,
        "dimension": embeddings.dimension,
        "source_count": len(documents),
        "chunk_count": len(active),
    }
