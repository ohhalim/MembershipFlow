"""Draft tuning diagnosis on a physical index; no alias writes or label changes."""

import asyncio
import json
import os
import subprocess
from dataclasses import asdict
from pathlib import Path

os.environ.update(
    AI_EMBEDDING_PROVIDER="sentence-transformers",
    AI_EMBEDDING_MODEL="BAAI/bge-m3",
    AI_EMBEDDING_REVISION="5617a9f61b028005a4858fdac845db406aefb181",
    HF_HUB_OFFLINE="1",
    TRANSFORMERS_OFFLINE="1",
    HF_HUB_DISABLE_PROGRESS_BARS="1",
)
from membershipflow_ai.cli import main as cli
from membershipflow_ai.evaluation.plan import retrieve_for_mode
from membershipflow_ai.evaluation.retrieval import (
    aggregate,
    cases_fingerprint,
    load_cases,
    run_cases,
    score_case,
)
from membershipflow_ai.persistence.elasticsearch_store import ElasticsearchStore
from membershipflow_ai.retrieval.elasticsearch import ElasticsearchRetriever

ROOT = Path(__file__).resolve().parent


async def main():
    path = Path("evals/retrieval/cases.draft.jsonl")
    cases = [c for c in load_cases(path) if c.split == "tuning"]
    report = {
        "status": "STARTED",
        "reviewed": False,
        "split": "tuning",
        "k": 5,
        "candidates": 20,
        "cases_sha256": cases_fingerprint(path),
        "code_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "results": {},
    }
    with (ROOT / "results.json").open("x") as f:
        json.dump(report, f)
    client = cli.elasticsearch_client()
    try:
        manifest = cli._load_validated_manifest(
            str(ROOT.parent / "2026-09-19-pinned-model-build/manifest.json")
        )
        index = manifest["physical_index"]
        store = ElasticsearchStore(client, cli.get_settings().elasticsearch_alias)
        identity = await store.index_identity(index)
        cli.check_identity(index, identity, manifest)
        await store.verify(index, set(manifest["expected_chunk_ids"]))
        report.update(physical_index=index, identity=identity)
        engine = ElasticsearchRetriever(client, index, strict_path=True)
        embeddings = cli.embedding_provider()
        for mode in ("keyword", "vector", "hybrid"):
            pools = []

            async def retrieve(question, mode=mode, pools=pools):
                hits, pool = await retrieve_for_mode(
                    mode,
                    question,
                    engine=engine,
                    embeddings=embeddings,
                    reranker=None,
                    k=5,
                    candidates=20,
                )
                pools.append(pool)
                return hits, pool

            scored = await run_cases(cases, retrieve)
            candidate_scored = [score_case(c, p) for c, p in zip(cases, pools, strict=True)]
            report["results"][mode] = {
                "top5": aggregate(scored),
                "candidate20": aggregate(candidate_scored),
                "cases": [
                    {**asdict(r), "recall": r.recall, "candidate_recall": p.recall}
                    for r, p in zip(scored, candidate_scored, strict=True)
                ],
            }
        report["status"] = "PASSED"
    except BaseException as exc:
        report.update(status="FAILED", error_type=type(exc).__name__)
        raise
    finally:
        await client.close()
        (ROOT / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(
        json.dumps(
            {m: {k: v[k] for k in ("top5", "candidate20")} for m, v in report["results"].items()},
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    asyncio.run(main())
