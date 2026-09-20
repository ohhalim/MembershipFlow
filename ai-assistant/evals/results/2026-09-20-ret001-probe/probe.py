"""ret-001 의 정답 청크가 왜 vector 85위인지 후보 원인을 가른다.

전체 깊이 진단(2026-09-20-vector-full-depth)에서 tuning 10건 중 ret-001 만
후보 20 밖이었고 순위는 85/317 이었다. 원인은 미확정으로 남겨 뒀다.

같은 정답 청크를 겨냥한 질문을 한 번에 하나씩만 바꿔 순위를 다시 잰다.
바뀐 것이 하나뿐이라야 순위 변화를 그 요인 탓으로 읽을 수 있다.

읽기 전용: alias·라벨·인덱스 변경 없음. 평가셋도 수정하지 않는다.
여기 질문들은 평가셋에 넣지 않는 진단용 문자열이다.
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
from membershipflow_ai.persistence.elasticsearch_store import ElasticsearchStore
from membershipflow_ai.retrieval.elasticsearch import ElasticsearchRetriever

ROOT = Path(__file__).resolve().parent
TARGET = "5f12d365d8d5529140f8548674c5fa82ef5ac283815747b2c6c3847b1c65b7e2"
FULL = 317

# (이름, 질문, 기준 대비 무엇을 바꿨는지)
VARIANTS = [
    ("baseline",   "이용 가능한 구독의 판정 기준은?", "ret-001 원문"),
    ("longer",     "구독이 이용 가능한지 판정하는 조건은 무엇이고 어떤 상태일 때 이용 가능한가요?",
                   "길이만 늘림. 영문·코드 토큰 없음"),
    ("code_hint",  "이용 가능한 구독의 판정 기준은? 메서드 구현 코드",
                   "코드라는 한국어 신호만 추가. 영문 심볼 없음"),
    ("state_token","구독 상태가 ACTIVE 이거나 CANCELLED 일 때 이용 가능 판정 기준은?",
                   "청크에 실제로 있는 영문 상태 토큰 추가"),
    ("field_token","이용 가능한 구독의 판정 기준은? nextBillingAt 비교",
                   "청크에 있는 영문 필드명 추가"),
    ("symbol",     "isActiveAt 이용 가능한 구독의 판정 기준은?",
                   "정답 심볼명 추가 (양성 대조)"),
    ("ret008",
     "구독 상태가 CANCELLED이고 이용 종료 시점이 아직 남았다면 이용 가능 판정은 어떻게 돼?",
     "같은 정답을 겨냥한 평가셋 문항 (기지값 8위)"),
    # 아래 둘은 영문 토큰 없이 한국어만 쓰되, 문서 청크와 겹치는 일반 어휘
    # (구독·판정·기준)를 피한다. 경쟁이 원인인지 표현이 원인인지 가른다.
    ("ko_distinct", "해지했는데 아직 남은 기간이면 계속 쓸 수 있나?",
                   "한국어만. 일반 어휘 회피, 구체적 상황 서술"),
    ("ko_logic",   "취소된 뒤에도 다음 청구일 전까지는 쓸 수 있는지 어떻게 따지나?",
                   "한국어만. 청크의 논리 구조를 그대로 서술"),
]


async def main() -> None:
    report: dict = {
        "status": "STARTED",
        "purpose": "ret-001 의 후보 누락 원인 후보 구분",
        "target_chunk_id": TARGET,
        "depth": FULL,
        "code_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "variants": [],
    }
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
        for name, question, changed in VARIANTS:
            hits = await engine.vector_search(embeddings.embed_query(question), k=FULL)
            rank = next((h.rank for h in hits if h.chunk.chunk_id == TARGET), None)
            top = hits[0]
            java_in_20 = sum(1 for h in hits[:20] if h.chunk.source_uri.endswith(".java"))
            report["variants"].append({
                "name": name,
                "question": question,
                "changed": changed,
                "target_rank": rank,
                "java_chunks_in_top20": java_in_20,
                "top1": {"source_uri": top.chunk.source_uri, "path": list(top.chunk.path)},
            })
        report["status"] = "PASSED"
    except BaseException as exc:
        report.update(status="FAILED", error_type=type(exc).__name__)
        raise
    finally:
        await client.close()
        (ROOT / "results.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        )

    print(f"{'variant':12s} {'rank':>5s} {'java@20':>8s}  changed")
    print("-" * 78)
    for v in report["variants"]:
        print(
            f"{v['name']:12s} {v['target_rank']!s:>5s} "
            f"{v['java_chunks_in_top20']:>8d}  {v['changed']}"
        )


if __name__ == "__main__":
    asyncio.run(main())
