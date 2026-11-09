"""Rank every tuning case's expected chunk in the full vector ranking.

The shallow run could only report "not in top 20", which does not distinguish a
chunk sitting at rank 21 from one at rank 300. The whole corpus is 317 chunks,
so one search per question at depth 317 turns every None into a number.

Depth cutoffs are computed by slicing that single ranking. Re-searching per
depth would let a different query embedding or shard state change the answer
between cutoffs, which would no longer be one experiment.

Read-only: no alias write, no label change, no index write.
"""

import asyncio
import json
import os
import subprocess
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
from membershipflow_ai.evaluation.retrieval import (
    anchor_matches,
    cases_fingerprint,
    load_cases,
)
from membershipflow_ai.persistence.elasticsearch_store import ElasticsearchStore
from membershipflow_ai.retrieval.elasticsearch import ElasticsearchRetriever

ROOT = Path(__file__).resolve().parent
DEPTHS = (5, 20, 50, 100)
FULL = 317
SHALLOW = ROOT.parent / "2026-09-19-pinned-tuning" / "results.json"


def expected_key(source: str, anchor: str) -> str:
    return f"{source}#{anchor}"


async def main() -> None:
    path = Path("evals/retrieval/cases.draft.jsonl")
    cases = [c for c in load_cases(path) if c.split == "tuning"]
    report: dict = {
        "status": "STARTED",
        "reviewed": False,
        "split": "tuning",
        "mode": "vector",
        "depth": FULL,
        "depths_reported": list(DEPTHS),
        "note": "단일 랭킹을 잘라 깊이별 수치를 계산한다. 깊이마다 재검색하지 않는다",
        "cases_sha256": cases_fingerprint(path),
        "code_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "cases": [],
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
            raise RuntimeError(f"count {count} != {FULL}; 이 진단의 전제가 깨졌다")
        report.update(physical_index=index, identity=identity, doc_count=count)

        engine = ElasticsearchRetriever(client, index, strict_path=True)
        embeddings = cli.embedding_provider()
        shallow = json.loads(SHALLOW.read_text())
        # 다른 질문 집합이나 다른 인덱스와 비교하면 "재현" 이라는 말이 성립하지 않는다.
        for field, mine in (
            ("cases_sha256", report["cases_sha256"]),
            ("physical_index", index),
        ):
            theirs = shallow.get(field)
            if theirs != mine:
                raise RuntimeError(f"얕은 실행과 {field} 가 다르다: {mine!r} != {theirs!r}")
        report["compared_against"] = {
            "path": str(SHALLOW.relative_to(Path.cwd())) if SHALLOW.is_relative_to(Path.cwd())
            else str(SHALLOW),
            "cases_sha256_match": True,
            "physical_index_match": True,
            "embedding_revision_match": shallow["identity"]["embedding_revision"]
            == identity["embedding_revision"],
        }
        shallow_by_id = {c["case"]["id"]: c for c in shallow["results"]["vector"]["cases"]}

        for case in cases:
            hits = await engine.vector_search(embeddings.embed_query(case.question), k=FULL)
            if len(hits) != FULL:
                raise RuntimeError(f"{case.id}: {len(hits)} hits, {FULL} 기대")

            ranks: dict[str, int | None] = {}
            for exp in case.expected_sources:
                key = expected_key(exp.source_uri, exp.anchor)
                ranks[key] = None
                for h in hits:
                    if h.chunk.source_uri == exp.source_uri and anchor_matches(
                        exp.anchor, h.chunk.path
                    ):
                        ranks[key] = h.rank
                        break

            recall = {
                f"@{d}": sum(1 for r in ranks.values() if r is not None and r <= d) / len(ranks)
                for d in DEPTHS
            }
            recall[f"@{FULL}"] = sum(1 for r in ranks.values() if r is not None) / len(ranks)

            scores = [h.score for h in hits]
            # 동점이면 ES 정렬이 임의 순서라 경계에서 순위가 흔들릴 수 있다.
            boundary_ties = {
                f"@{d}": scores[d - 1] == scores[d] for d in DEPTHS if d < len(scores)
            }
            expected_ties = {
                key: (
                    sum(1 for s in scores if s == scores[r - 1])
                    if r is not None
                    else None
                )
                for key, r in ranks.items()
            }

            top20_now = [h.chunk.chunk_id for h in hits[:20]]
            prev = shallow_by_id.get(case.id)
            prev20 = [c["chunk_id"] for c in prev["candidates"]] if prev else []
            report["cases"].append(
                {
                    "id": case.id,
                    "question": case.question,
                    "difficulty": case.difficulty,
                    "expected_ranks": ranks,
                    "recall_at_depth": recall,
                    "distinct_scores": len(set(scores)),
                    "boundary_ties": boundary_ties,
                    "score_ties_at_expected_rank": expected_ties,
                    "reproduces_shallow_top20": top20_now == prev20,
                    # matched_ranks 는 top5 를 자른 결과다. top20 이 아니다.
                    "shallow_top5_rank_of_expected": (
                        dict(prev["matched_ranks"]) if prev else None
                    ),
                    "ranking": [
                        {
                            "rank": h.rank,
                            "chunk_id": h.chunk.chunk_id,
                            "source_uri": h.chunk.source_uri,
                            "path": list(h.chunk.path),
                            "score": round(h.score, 6),
                        }
                        for h in hits
                    ],
                }
            )
        report["status"] = "PASSED"
    except BaseException as exc:
        report.update(status="FAILED", error_type=type(exc).__name__)
        raise
    finally:
        await client.close()
        (ROOT / "results.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        )

    agg = {
        f"@{d}": round(
            sum(c["recall_at_depth"][f"@{d}"] for c in report["cases"]) / len(report["cases"]), 4
        )
        for d in (*DEPTHS, FULL)
    }
    same = all(c["reproduces_shallow_top20"] for c in report["cases"])
    print(json.dumps({"recall_at_depth": agg, "reproduces_shallow": same}, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
