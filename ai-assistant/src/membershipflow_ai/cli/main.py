from __future__ import annotations

import argparse
import asyncio
import json
from dataclasses import asdict
from typing import Any

from elasticsearch import AsyncElasticsearch

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
from membershipflow_ai.persistence.elasticsearch_store import ElasticsearchStore
from membershipflow_ai.persistence.repository import CorpusRepository
from membershipflow_ai.retrieval.elasticsearch import ElasticsearchRetriever
from membershipflow_ai.retrieval.fusion import reciprocal_rank_fusion


def embedding_provider() -> EmbeddingProvider:
    settings = get_settings()
    if settings.embedding_provider == "fake":
        return DeterministicHashEmbedding()
    if settings.embedding_provider == "sentence-transformers":
        return SentenceTransformerEmbedding(
            settings.embedding_model, revision=settings.embedding_revision
        )
    raise ValueError(f"unsupported embedding provider: {settings.embedding_provider}")


def elasticsearch_client() -> AsyncElasticsearch:
    settings = get_settings()
    options: dict[str, Any] = {
        "basic_auth": (settings.elasticsearch_username, settings.elasticsearch_password),
        "request_timeout": settings.elasticsearch_request_timeout,
    }
    if settings.elasticsearch_ca_certs is not None:
        options["ca_certs"] = str(settings.elasticsearch_ca_certs)
    return AsyncElasticsearch(settings.elasticsearch_url, **options)


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
        )
        summary = await pipeline.run()
        print(json.dumps(asdict(summary), ensure_ascii=False, sort_keys=True))
    finally:
        await engine.dispose()


async def search(query: str, k: int, retriever: str, source_types: list[str] | None) -> None:
    settings = get_settings()
    client = elasticsearch_client()
    try:
        store = ElasticsearchStore(client, settings.elasticsearch_alias)
        index = await store.active_index()
        if index is None:
            raise SystemExit(
                f"no active index for alias {settings.elasticsearch_alias}; run publish first"
            )
        engine = ElasticsearchRetriever(client, index)

        hit_lists = []
        if retriever in ("hybrid", "keyword"):
            hit_lists.append(await engine.keyword_search(query, k=k, source_types=source_types))
        if retriever in ("hybrid", "vector"):
            embedding = embedding_provider().embed_query(query)
            hit_lists.append(
                await engine.vector_search(embedding, k=k, source_types=source_types)
            )
        hits = (
            reciprocal_rank_fusion(hit_lists, limit=k)
            if len(hit_lists) > 1
            else list(hit_lists[0][:k])
        )
    finally:
        await client.close()

    print(
        json.dumps(
            {
                "query": query,
                "retriever": retriever,
                "physical_index": index,
                "hits": [
                    {
                        "rank": hit.rank,
                        "score": round(hit.score, 6),
                        "retriever": hit.retriever,
                        "chunk_id": hit.chunk.chunk_id,
                        "source_path": hit.chunk.source_uri,
                        "line_start": hit.chunk.line_start,
                        "line_end": hit.chunk.line_end,
                    }
                    for hit in hits
                ],
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )


def main() -> None:
    parser = argparse.ArgumentParser(prog="membershipflow-ai")
    subcommands = parser.add_subparsers(dest="command", required=True)
    subcommands.add_parser("ingest", help="ingest the configured corpus")

    search_parser = subcommands.add_parser("search", help="search the ingested corpus")
    search_parser.add_argument("query")
    search_parser.add_argument("-k", type=int, default=5, help="number of hits (default: 5)")
    search_parser.add_argument(
        "--retriever",
        choices=("hybrid", "keyword", "vector"),
        default="hybrid",
        help="retriever to use (default: hybrid)",
    )
    search_parser.add_argument(
        "--source-type", action="append", help="filter by source type (repeatable)"
    )

    args = parser.parse_args()
    if args.command == "ingest":
        asyncio.run(ingest())
    elif args.command == "search":
        asyncio.run(search(args.query, args.k, args.retriever, args.source_type))
