from __future__ import annotations

import argparse
import asyncio
import json
import logging
import os
import time
from dataclasses import asdict
from pathlib import Path
from typing import Any

from elasticsearch import AsyncElasticsearch

from membershipflow_ai.agent.contracts import AssistantAnswer
from membershipflow_ai.agent.graph import build_llm, run_agent
from membershipflow_ai.agent.tools import SpringMetricsClient
from membershipflow_ai.config.settings import get_settings
from membershipflow_ai.domain.documents import SearchHit
from membershipflow_ai.evaluation.plan import (
    RETRIEVER_MODES,
    retrieve_for_mode,
    validate_depths,
)
from membershipflow_ai.evaluation.report import write_html
from membershipflow_ai.evaluation.retrieval import (
    aggregate,
    cases_fingerprint,
    group_by,
    load_cases,
    run_cases,
)
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
from membershipflow_ai.retrieval.rerank import CrossEncoderReranker


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


async def evaluate(
    cases_path: str, k: int, retrievers: list[str], allow_draft: bool,
    html_path: str | None, candidates: int, split: str,
) -> None:
    try:
        validate_depths(k, candidates)
    except ValueError as exc:
        raise SystemExit(str(exc)) from exc

    path = Path(cases_path)
    cases = load_cases(path)
    if split != "all":
        cases = [case for case in cases if case.split == split]
    if not cases:
        raise SystemExit(f"split={split} 에 해당하는 케이스가 없다")
    unreviewed = [case.id for case in cases if not case.reviewed]
    if unreviewed and not allow_draft:
        raise SystemExit(
            f"reviewed=false 인 케이스가 있다: {unreviewed}. "
            "사람 검토 후 true 로 바꾸거나 --allow-draft 를 쓴다"
        )

    settings = get_settings()
    client = elasticsearch_client()
    started = time.monotonic()
    report: dict[str, Any] = {
        "cases_path": cases_path,
        "cases_sha256": cases_fingerprint(path),
        "split": split,
        "k": k,
        "candidates": candidates,
        "reviewed": not unreviewed,
        "results": {},
    }
    try:
        store = ElasticsearchStore(client, settings.elasticsearch_alias)
        index = await store.active_index()
        if index is None:
            raise SystemExit("no active index; run ingest+publish first")
        mapping = await client.indices.get_mapping(index=index)
        properties = mapping.body[index]["mappings"].get("properties", {})
        if "symbol_path" not in properties:
            raise SystemExit(
                f"index {index} 에 symbol_path 가 없다. 구형 색인에서는 경로 복원이 "
                "손상되어 점수가 낮게 나온다. 재색인 후 다시 실행한다"
            )
        report["physical_index"] = index
        # 평가에서는 경로 복원 실패를 낮은 점수로 흘려보내지 않고 즉시 멈춘다.
        engine = ElasticsearchRetriever(client, index, strict_path=True)
        embeddings = embedding_provider()
        report["embedding_model"] = embeddings.model_id
        report["embedding_revision"] = embeddings.revision

        reranker = (
            CrossEncoderReranker(settings.reranker_model, settings.reranker_revision)
            if any(name.endswith("+rerank") for name in retrievers)
            else None
        )
        report["reranker_model"] = reranker.model_id if reranker else None
        report["reranker_revision"] = settings.reranker_revision if reranker else None

        for name in retrievers:
            async def retrieve(
                question: str, mode: str = name
            ) -> tuple[list[SearchHit], list[SearchHit]]:
                return await retrieve_for_mode(
                    mode, question, engine=engine, embeddings=embeddings,
                    reranker=reranker, k=k, candidates=candidates,
                )

            run_started = time.monotonic()
            results = await run_cases(cases, retrieve)
            report["results"][name] = {
                "overall": aggregate(results),
                "by_split": group_by(results, "split"),
                "by_difficulty": group_by(results, "difficulty"),
                "elapsed_seconds": round(time.monotonic() - run_started, 3),
                "cases": [
                    {
                        "id": r.case.id,
                        "recall": round(r.recall, 4),
                        "all_evidence_found": r.all_evidence_found,
                        "matched_ranks": r.matched_ranks,
                        "returned": r.returned,
                        "candidates": r.candidates,
                    }
                    for r in results
                ],
                "misses": [
                    {"id": r.case.id, "question": r.case.question, "recall": r.recall}
                    for r in results
                    if r.reciprocal_rank == 0.0
                ],
            }
    finally:
        await client.close()
    report["total_elapsed_seconds"] = round(time.monotonic() - started, 3)

    if html_path:
        write_html(report, html_path)
        print(f"report written: {html_path}")
    else:
        print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))


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

    eval_parser = subcommands.add_parser("eval", help="score retrieval against an eval set")
    eval_parser.add_argument("cases", help="path to a retrieval cases jsonl file")
    eval_parser.add_argument("-k", type=int, default=10, help="top-k (default: 10)")
    eval_parser.add_argument(
        "--retriever", action="append", choices=RETRIEVER_MODES,
        help="repeatable; default runs keyword, vector, hybrid",
    )
    eval_parser.add_argument(
        "--candidates", type=int, default=30,
        help="candidate depth, applied to every retriever (default: 30)",
    )
    eval_parser.add_argument(
        "--split", choices=("tuning", "held_out", "all"), default="tuning",
        help="case split to run (default: tuning; held_out/all must be chosen explicitly)",
    )
    eval_parser.add_argument(
        "--allow-draft", action="store_true", help="run even if cases are not reviewed"
    )
    eval_parser.add_argument("--html", help="write an HTML report to this path")

    slack_parser = subcommands.add_parser("slack", help="run the Slack socket-mode bot")
    slack_parser.add_argument("-k", type=int, default=5, help="evidence chunks (default: 5)")
    slack_parser.add_argument(
        "--retriever", choices=("keyword", "hybrid"), default="keyword"
    )

    args = parser.parse_args()
    if args.command == "ingest":
        asyncio.run(ingest())
    elif args.command == "eval":
        asyncio.run(
            evaluate(
                args.cases, args.k, args.retriever or ["keyword", "vector", "hybrid"],
                args.allow_draft, args.html, args.candidates, args.split,
            )
        )
    elif args.command == "slack":
        asyncio.run(slack(args.k, args.retriever))
    elif args.command == "ask":
        asyncio.run(ask(args.question, args.k, args.retriever))
    elif args.command == "search":
        asyncio.run(search(args.query, args.k, args.retriever, args.source_type))
