"""리랭커에게 더 깊은 후보를 주면 ret-001 을 건지는지 잰다.

앞선 관측 세 개가 이 실험을 가리킨다.

1. 전체 깊이 진단: ret-001 정답은 vector 85위. 후보 20 밖이라 리랭커가 볼 수 없다
2. 동일 깊이 리랭커 재측정: 후보 20에서 recall@5 0.55 -> 0.70, 상한은 pool 0.90
3. ret-001 원인 진단: 정답이 java 청크 중 13위이고, 그 위 12개와 점수가
   0.4719 대 0.4751 로 거의 붙어 있다. bi-encoder 가 이 띠 안에서 구분하지 못한다

띠 안에서 구분하는 것이 cross-encoder 가 하는 일이다. 그러려면 후보에 들어와야
한다. 그래서 바꾸는 변수는 후보 깊이 하나다.

후보 깊이별 상한(전체 깊이 진단 기준): @20 0.90, @50 0.90, @100 1.00.
따라서 depth 100 에서만 ret-001 이 리랭커의 사정권에 들어온다.

읽기 전용: alias·라벨·인덱스 변경 없음.
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
DEPTHS = (20, 50, 100)
K = 5
FULL = 317


def ranks_for(case, hits):
    out = {}
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
        "depths": list(DEPTHS),
        "note": "바꾸는 변수는 후보 깊이 하나. 모드는 vector / vector+rerank 고정",
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
        count = (await client.count(index=index)).body["count"]
        if count != FULL:
            raise RuntimeError(f"count {count} != {FULL}")
        report.update(physical_index=index, identity=identity, doc_count=count)

        engine = ElasticsearchRetriever(client, index, strict_path=True)
        embeddings = cli.embedding_provider()
        s = cli.get_settings()
        reranker = CrossEncoderReranker(s.reranker_model, s.reranker_revision)

        for depth in DEPTHS:
            for mode in ("vector", "vector+rerank"):
                started = time.perf_counter()
                topk, pool_scored, per_case = [], [], []
                for case in cases:
                    hits, pool = await retrieve_for_mode(
                        mode, case.question, engine=engine, embeddings=embeddings,
                        reranker=reranker, k=K, candidates=depth,
                    )
                    topk.append(score_case(case, hits))
                    pool_scored.append(score_case(case, pool))
                    per_case.append({
                        "id": case.id,
                        "pool_rank": ranks_for(case, pool),
                        "final_rank": ranks_for(case, hits),
                    })
                elapsed = time.perf_counter() - started
                report["results"][f"{mode}@{depth}"] = {
                    "topk": aggregate(topk),
                    "pool": aggregate(pool_scored),
                    "seconds_per_question": round(elapsed / len(cases), 3),
                    "cases": per_case,
                }
        report["status"] = "PASSED"
    except BaseException as exc:
        report.update(status="FAILED", error_type=type(exc).__name__)
        raise
    finally:
        await client.close()
        (ROOT / "results.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        )

    header = (
        f"{'mode@depth':22s} {'recall@5':>9s} {'pool':>7s} "
        f"{'MRR':>7s} {'all_ev':>7s} {'sec/q':>7s}"
    )
    print(header)
    print("-" * len(header))
    for name, v in report["results"].items():
        print(
            f"{name:22s} {v['topk']['recall_at_k']:>9.2f} "
            f"{v['pool']['recall_at_k']:>7.2f} {v['topk']['mrr']:>7.4f} "
            f"{v['topk']['all_evidence_rate']:>7.2f} {v['seconds_per_question']:>7.2f}"
        )


if __name__ == "__main__":
    asyncio.run(main())
