"""질의 재작성이 ret-001 유형을 건지는지 잰다.

원인 진단(#387)에서 효과가 있었던 레버는 하나뿐이었다. 질문을 같은 언어로
구체적인 상황 서술로 바꾸면 ret-001 정답이 85위에서 10위로 올라왔다.
후보 깊이(#389)와 source_type 쿼터는 둘 다 듣지 않았다.

그 레버를 사람 손이 아니라 LLM 재작성으로 자동화했을 때도 같은 효과가 나는지
본다. 비교는 세 가지다.

- original: 원문 질의 (기준)
- rewritten: 재작성 질의만
- fused: 원문과 재작성을 RRF 로 합침

재작성은 LLM 호출이 추가되므로 비용도 같이 잰다. 재작성 결과를 results.json 에
그대로 남겨 사람이 검토할 수 있게 한다.

읽기 전용: alias·라벨·인덱스 변경 없음. 제품 기본값도 바꾸지 않는다.
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

from membershipflow_ai.agent.graph import build_llm
from membershipflow_ai.cli import main as cli
from membershipflow_ai.evaluation.retrieval import (
    aggregate,
    anchor_matches,
    cases_fingerprint,
    load_cases,
    score_case,
)
from membershipflow_ai.persistence.elasticsearch_store import ElasticsearchStore
from membershipflow_ai.retrieval.elasticsearch import ElasticsearchRetriever
from membershipflow_ai.retrieval.fusion import reciprocal_rank_fusion
from membershipflow_ai.retrieval.rerank import CrossEncoderReranker

ROOT = Path(__file__).resolve().parent
K = 5
POOL = 20
FULL = 317

# 재작성 지침은 진단에서 관측한 것만 반영한다.
# 문서 청크와 겹치는 일반 어휘를 피하고 상황을 구체적으로 쓰게 한다.
PROMPT = """너는 코드베이스 검색을 돕는다. 아래 질문을 검색용으로 한 번 다시 쓴다.

규칙
- 한국어로만 쓴다. 영어 단어나 식별자를 새로 지어내지 않는다
- 질문에 없던 사실을 추가하지 않는다. 의미를 바꾸지 않는다
- '판정 기준', '어떻게 되나', '무엇인가' 같은 일반적인 표현 대신
  묻고 있는 상황을 구체적으로 서술한다
- 한 문장으로 쓴다. 설명이나 따옴표 없이 문장만 출력한다

질문: {question}"""


def extract_text(content: object) -> str:
    """응답 본문에서 사람이 읽는 문장만 꺼낸다.

    `content` 는 문자열일 수도 있고 블록 리스트일 수도 있다. 블록에는 thinking
    signature 같은 게 섞여 있어서 그대로 str() 하면 base64 덩어리가 질의로
    들어간다. 실제로 첫 실행에서 그렇게 됐다.
    """
    if isinstance(content, str):
        parts = [content]
    elif isinstance(content, list):
        parts = [
            block["text"]
            for block in content
            if isinstance(block, dict) and block.get("type") == "text" and block.get("text")
        ]
    else:
        parts = []
    text = " ".join(part.strip() for part in parts if part).strip()
    return text.splitlines()[0].strip() if text else ""


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
        "candidates": POOL,
        "variants": ["original", "rewritten", "fused"],
        "rerank_variants": ["original+rerank", "rewritten+rerank", "fused+rerank"],
        "cases_sha256": cases_fingerprint(path),
        "code_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "rewrites": [],
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

        settings = cli.get_settings()
        report.update(physical_index=index, identity=identity, doc_count=count)

        # 재작성은 한 번 만들어 고정해 둔다. 검색 쪽을 LLM 호출 없이 재현하기
        # 위해서고, 무료 할당량이 하루 20건이라 반복 호출이 불가능해서이기도 하다.
        cached_path = ROOT / "rewrites.json"
        cached = json.loads(cached_path.read_text()) if cached_path.exists() else None

        engine = ElasticsearchRetriever(client, index, strict_path=True)
        embeddings = cli.embedding_provider()
        reranker = CrossEncoderReranker(settings.reranker_model, settings.reranker_revision)

        # 1) 재작성
        if cached:
            rewrites = dict(cached["rewrites"])
            report["rewrite_source"] = "rewrites.json (고정본)"
            report["llm_model"] = cached["generated_by"]
            report["rewrite_seconds_per_question"] = cached[
                "observed_rewrite_seconds_per_question"
            ]
        else:
            from dotenv import load_dotenv

            load_dotenv(".env")
            llm = build_llm(os.environ.get("GEMINI_API_KEY", ""), settings.llm_model)
            if llm is None:
                raise RuntimeError("GEMINI_API_KEY 가 없어 재작성을 할 수 없다")
            started = time.perf_counter()
            rewrites = {}
            for case in cases:
                reply = await llm.ainvoke(PROMPT.format(question=case.question))
                text = extract_text(getattr(reply, "content", ""))
                if not text:
                    raise RuntimeError(f"{case.id}: 재작성 결과가 비었다")
                rewrites[case.id] = text
            report["rewrite_source"] = "LLM 신규 호출"
            report["llm_model"] = settings.llm_model
            report["rewrite_seconds_per_question"] = round(
                (time.perf_counter() - started) / len(cases), 3
            )
        for case in cases:
            report["rewrites"].append(
                {"id": case.id, "original": case.question, "rewritten": rewrites[case.id]}
            )

        # 2) 세 변형 비교
        pools_by_variant: dict[str, list] = {}
        for variant in ("original", "rewritten", "fused"):
            topk, pool_scored, per_case = [], [], []
            started = time.perf_counter()
            for case in cases:
                if variant == "original":
                    pool = await engine.vector_search(
                        embeddings.embed_query(case.question), k=POOL
                    )
                elif variant == "rewritten":
                    pool = await engine.vector_search(
                        embeddings.embed_query(rewrites[case.id]), k=POOL
                    )
                else:
                    a = await engine.vector_search(embeddings.embed_query(case.question), k=POOL)
                    b = await engine.vector_search(
                        embeddings.embed_query(rewrites[case.id]), k=POOL
                    )
                    pool = reciprocal_rank_fusion([a, b], limit=POOL)
                pools_by_variant.setdefault(variant, []).append(pool)
                hits = pool[:K]
                topk.append(score_case(case, hits))
                pool_scored.append(score_case(case, pool))
                per_case.append({
                    "id": case.id,
                    "pool_rank": ranks_for(case, pool),
                    "final_rank": ranks_for(case, hits),
                })
            elapsed = time.perf_counter() - started
            report["results"][variant] = {
                "topk": aggregate(topk),
                "pool": aggregate(pool_scored),
                "retrieval_seconds_per_question": round(elapsed / len(cases), 3),
                "cases": per_case,
            }
        # 3) 같은 후보 풀을 리랭커로 재정렬한다. 후보는 그대로라 차이는 재정렬뿐이다.
        for variant, pools in pools_by_variant.items():
            topk, per_case = [], []
            started = time.perf_counter()
            for case, pool in zip(cases, pools, strict=True):
                hits = reranker.rerank(case.question, pool, limit=K)
                topk.append(score_case(case, hits))
                per_case.append({
                    "id": case.id,
                    "pool_rank": ranks_for(case, pool),
                    "final_rank": ranks_for(case, hits),
                })
            elapsed = time.perf_counter() - started
            report["results"][f"{variant}+rerank"] = {
                "topk": aggregate(topk),
                "pool": report["results"][variant]["pool"],
                "rerank_seconds_per_question": round(elapsed / len(cases), 3),
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

    head = f"{'variant':12s} {'recall@5':>9s} {'pool@20':>8s} {'MRR':>7s} {'all_ev':>7s}"
    print(head)
    print("-" * len(head))
    for name, v in report["results"].items():
        print(
            f"{name:12s} {v['topk']['recall_at_k']:>9.2f} {v['pool']['recall_at_k']:>8.2f} "
            f"{v['topk']['mrr']:>7.4f} {v['topk']['all_evidence_rate']:>7.2f}"
        )
    print(f"\n재작성 LLM 호출: {report['rewrite_seconds_per_question']} 초/질문")


if __name__ == "__main__":
    asyncio.run(main())
