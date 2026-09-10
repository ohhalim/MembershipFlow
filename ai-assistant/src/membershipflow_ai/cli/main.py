from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
from dataclasses import asdict
from typing import Any

from elasticsearch import AsyncElasticsearch

from membershipflow_ai.agent.contracts import AssistantAnswer
from membershipflow_ai.agent.graph import build_llm, run_agent
from membershipflow_ai.agent.tools import SpringMetricsClient
from membershipflow_ai.config.settings import get_settings
from membershipflow_ai.domain.documents import SearchHit
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


async def ask(question: str, k: int, retriever: str) -> None:
    settings = get_settings()
    client = elasticsearch_client()
    try:
        store = ElasticsearchStore(client, settings.elasticsearch_alias)
        index = await store.active_index()
        if index is None:
            raise SystemExit(
                f"no active index for alias {settings.elasticsearch_alias}; run ingest+publish"
            )
        engine = ElasticsearchRetriever(client, index)

        async def retrieve(query: str) -> tuple[list[SearchHit], str]:
            keyword = await engine.keyword_search(query, k=k)
            if retriever == "keyword":
                return keyword[:k], index
            embedding = embedding_provider().embed_query(query)
            vector = await engine.vector_search(embedding, k=k)
            return reciprocal_rank_fusion([keyword, vector], limit=k), index

        llm = build_llm(os.environ.get("GEMINI_API_KEY", ""), settings.llm_model)
        result = await run_agent(
            question,
            llm=llm,
            retrieve=retrieve,
            metrics=SpringMetricsClient(settings),
        )
    finally:
        await client.close()

    print(f"[route] {result.route}   [grounded] {result.grounded}")
    if result.failure:
        print(f"[failure] {result.failure}")
    print()
    print(result.answer)
    if result.citations:
        print("\n근거:")
        for number, citation in enumerate(result.citations, start=1):
            print(f"  [{number}] {citation.source_path}:{citation.line_start}-{citation.line_end}")


async def slack(k: int, retriever: str) -> None:
    from membershipflow_ai.interfaces.slack_app import run_slack

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    settings = get_settings()
    client = elasticsearch_client()
    try:
        store = ElasticsearchStore(client, settings.elasticsearch_alias)
        index = await store.active_index()
        if index is None:
            raise SystemExit(
                f"no active index for alias {settings.elasticsearch_alias}; run ingest+publish"
            )
        engine = ElasticsearchRetriever(client, index)
        llm = build_llm(os.environ.get("GEMINI_API_KEY", ""), settings.llm_model)
        metrics = SpringMetricsClient(settings)

        async def retrieve(query: str) -> tuple[list[SearchHit], str]:
            keyword = await engine.keyword_search(query, k=k)
            if retriever == "keyword":
                return keyword[:k], index
            embedding = embedding_provider().embed_query(query)
            vector = await engine.vector_search(embedding, k=k)
            return reciprocal_rank_fusion([keyword, vector], limit=k), index

        async def answer_question(question: str) -> AssistantAnswer:
            return await run_agent(question, llm=llm, retrieve=retrieve, metrics=metrics)

        await run_slack(settings, answer_question)
    finally:
        await client.close()


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

    ask_parser = subcommands.add_parser("ask", help="answer a question with cited evidence")
    ask_parser.add_argument("question")
    ask_parser.add_argument("-k", type=int, default=5, help="evidence chunks (default: 5)")
    ask_parser.add_argument(
        "--retriever",
        choices=("keyword", "hybrid"),
        default="keyword",
        help="evidence retriever (default: keyword; vector is fake-embedding only)",
    )

    slack_parser = subcommands.add_parser("slack", help="run the Slack socket-mode bot")
    slack_parser.add_argument("-k", type=int, default=5, help="evidence chunks (default: 5)")
    slack_parser.add_argument(
        "--retriever", choices=("keyword", "hybrid"), default="keyword"
    )

    args = parser.parse_args()
    if args.command == "ingest":
        asyncio.run(ingest())
    elif args.command == "slack":
        asyncio.run(slack(args.k, args.retriever))
    elif args.command == "ask":
        asyncio.run(ask(args.question, args.k, args.retriever))
    elif args.command == "search":
        asyncio.run(search(args.query, args.k, args.retriever, args.source_type))
