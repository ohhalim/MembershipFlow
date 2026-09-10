from __future__ import annotations

import argparse
import asyncio
import json
from dataclasses import asdict

from membershipflow_ai.config.settings import get_settings
from membershipflow_ai.ingestion.chunker import RegexTokenCounter, StructureAwareChunker
from membershipflow_ai.ingestion.embeddings import (
    DeterministicHashEmbedding,
    EmbeddingProvider,
    SentenceTransformerEmbedding,
)
from membershipflow_ai.ingestion.parsers import ParserRegistry
from membershipflow_ai.ingestion.pipeline import IngestionPipeline
from membershipflow_ai.ingestion.scanner import CorpusScanner
from membershipflow_ai.persistence.database import create_engine, create_session_factory
from membershipflow_ai.persistence.repository import CorpusRepository
from membershipflow_ai.retrieval.bm25 import Bm25ArtifactStore


def embedding_provider() -> EmbeddingProvider:
    settings = get_settings()
    if settings.embedding_provider == "fake":
        return DeterministicHashEmbedding()
    if settings.embedding_provider == "sentence-transformers":
        return SentenceTransformerEmbedding(
            settings.embedding_model, revision=settings.embedding_revision
        )
    raise ValueError(f"unsupported embedding provider: {settings.embedding_provider}")


async def ingest() -> None:
    settings = get_settings()
    engine = create_engine(settings.database_url)
    try:
        repository = CorpusRepository(create_session_factory(engine))
        pipeline = IngestionPipeline(
            scanner=CorpusScanner(settings.repository_root, settings.corpus_config),
            parsers=ParserRegistry(),
            chunker=StructureAwareChunker(RegexTokenCounter()),
            embeddings=embedding_provider(),
            repository=repository,
            bm25_store=Bm25ArtifactStore(settings.bm25_index_dir),
        )
        summary = await pipeline.run()
        print(json.dumps(asdict(summary), ensure_ascii=False, sort_keys=True))
    finally:
        await engine.dispose()


def main() -> None:
    parser = argparse.ArgumentParser(prog="membershipflow-ai")
    subcommands = parser.add_subparsers(dest="command", required=True)
    subcommands.add_parser("ingest", help="ingest the configured corpus")
    args = parser.parse_args()
    if args.command == "ingest":
        asyncio.run(ingest())
