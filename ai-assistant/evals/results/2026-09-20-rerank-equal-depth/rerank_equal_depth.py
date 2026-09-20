"""Re-measure the reranker with the candidate depth held equal across modes.

CORRECTIONS.md #2 retracted the 2026-09-11 reranker claim because `+rerank`
modes drew 30 candidates while the others drew k(=10): rerank effect and
candidate depth were mixed. That entry records the equal-depth re-measurement
as 미수행. This is that measurement.

`retrieve_for_mode` now takes one `candidates` for every mode, so the pool is
built identically and only the final ordering differs. The script asserts that
the pool a `+rerank` mode saw is byte-identical to its base mode's pool, which
is what makes "the reranker caused this" a statement about one variable.

Ceiling: a reranker only reorders the pool. With candidates=20 the best any
`+rerank` mode can reach at k=5 is its base mode's recall over those 20.
Both numbers are reported so the effect is read against its actual ceiling.

Read-only: no alias write, no label change, no index write.
"""

import asyncio
import json
import os
import subprocess
import time
from pathlib import Path

os.environ.update(
    AI_EMBEDDING_PROVIDER="sentence-transformers",
    AI_EMBEDDING_MODEL="BAAI/bge-m3",
    AI_EMBEDDING_REVISION="5617a9f61b028005a4858fdac845db406aefb181",
    AI_RERANKER_MODEL="BAAI/bge-reranker-v2-m3",
    # CORRECTIONS.md #5 는 reranker_revision 이 None 인 상태를 남겨 뒀다.
    # None 으로 재면 어떤 가중치로 잰 것인지 기록에 남지 않는다.
    AI_RERANKER_REVISION="953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e",
    HF_HUB_OFFLINE="1",
    TRANSFORMERS_OFFLINE="1",
    HF_HUB_DISABLE_PROGRESS_BARS="1",
)

from membershipflow_ai.cli import main as cli
from membershipflow_ai.evaluation.plan import retrieve_for_mode
from membershipflow_ai.evaluation.retrieval import (
    aggregate,
    anchor_matches,
    cases_fingerprint,
    load_cases,
    score_case,
)
from membershipflow_ai.persistence.elasticsearch_store import ElasticsearchStore
from membershipflow_ai.retrieval.elasticsearch import ElasticsearchRetriever
from membershipflow_ai.retrieval.rerank import CrossEncoderReranker

ROOT = Path(__file__).resolve().parent
EMBEDDING_REVISION = os.environ["AI_EMBEDDING_REVISION"]
RERANKER_REVISION = os.environ["AI_RERANKER_REVISION"]
MODES = ("keyword", "vector", "hybrid", "vector+rerank", "hybrid+rerank")
K = 5
CANDIDATES = 20
FULL = 317


def ranks_for(case, hits) -> dict[str, int | None]:
    out: dict[str, int | None] = {}
    for exp in case.expected_sources:
        key = f"{exp.source_uri}#{exp.anchor}"
        out[key] = None
        for h in hits:
            if h.chunk.source_uri == exp.source_uri and anchor_matches(exp.anchor, h.chunk.path):
                out[key] = h.rank
                break
    return out


async def main() -> None:
    path = Path("evals/retrieval/cases.draft.jsonl")
    cases = [c for c in load_cases(path) if c.split == "tuning"]
    report: dict = {
        "status": "STARTED",
        "reviewed": False,
        "split": "tuning",
        "k": K,
        "candidates": CANDIDATES,
        "modes": list(MODES),
        "note": "모든 모드가 같은 candidates 를 쓴다. "
                "후보 깊이 차이를 재정렬 효과로 읽지 않기 위해서다",
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
        count = (await client.count(index=index)).body["count"]
        if count != FULL:
            raise RuntimeError(f"count {count} != {FULL}")
        if identity["embedding_revision"] != EMBEDDING_REVISION:
            raise RuntimeError("인덱스 embedding revision 이 이 실행의 고정값과 다르다")

        engine = ElasticsearchRetriever(client, index, strict_path=True)
        embeddings = cli.embedding_provider()
        reranker = CrossEncoderReranker(
            cli.get_settings().reranker_model, cli.get_settings().reranker_revision
        )
        report.update(
            physical_index=index,
            identity=identity,
            doc_count=count,
            embedding_model="BAAI/bge-m3",
            embedding_revision=EMBEDDING_REVISION,
            reranker_model=reranker.model_id,
            reranker_revision=RERANKER_REVISION,
        )

        pools_by_mode: dict[str, list[list[str]]] = {}
        for mode in MODES:
            started = time.perf_counter()
            topk_results, pool_results, per_case = [], [], []
            pool_ids: list[list[str]] = []
            for case in cases:
                hits, pool = await retrieve_for_mode(
                    mode, case.question, engine=engine, embeddings=embeddings,
                    reranker=reranker, k=K, candidates=CANDIDATES,
                )
                topk_results.append(score_case(case, hits))
                pool_results.append(score_case(case, pool))
                pool_ids.append([h.chunk.chunk_id for h in pool])
                per_case.append({
                    "id": case.id,
                    "pool_rank": ranks_for(case, pool),
                    "final_rank": ranks_for(case, hits),
                    "top5": [
                        {"rank": h.rank, "chunk_id": h.chunk.chunk_id,
                         "source_uri": h.chunk.source_uri, "path": list(h.chunk.path),
                         "score": round(h.score, 6)}
                        for h in hits
                    ],
                })
            elapsed = time.perf_counter() - started
            pools_by_mode[mode] = pool_ids
            report["results"][mode] = {
                "topk": aggregate(topk_results),
                "pool": aggregate(pool_results),
                "elapsed_seconds": round(elapsed, 3),
                "seconds_per_question": round(elapsed / len(cases), 3),
                "cases": per_case,
            }

        # +rerank 가 본 후보가 base 와 같아야 차이를 재정렬 탓으로 돌릴 수 있다.
        report["pool_identical_to_base"] = {
            m: pools_by_mode[m] == pools_by_mode[m.removesuffix("+rerank")]
            for m in MODES if m.endswith("+rerank")
        }
        if not all(report["pool_identical_to_base"].values()):
            raise RuntimeError(f"후보 풀이 base 와 다르다: {report['pool_identical_to_base']}")
        report["status"] = "PASSED"
    except BaseException as exc:
        report.update(status="FAILED", error_type=type(exc).__name__)
        raise
    finally:
        await client.close()
        (ROOT / "results.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        )

    print(json.dumps({
        m: {
            "recall@5": v["topk"]["recall_at_k"],
            "pool_recall@20": v["pool"]["recall_at_k"],
            "mrr": v["topk"]["mrr"],
            "all_evidence": v["topk"]["all_evidence_rate"],
            "sec/q": v["seconds_per_question"],
        } for m, v in report["results"].items()
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    asyncio.run(main())
