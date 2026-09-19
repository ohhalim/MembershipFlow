"""ES 적재 중 실패 검증. 제품 코드를 바꾸지 않는 독립 스크립트.

실제 cli.build 를 실행하되, ElasticsearchStore.bulk_index 를 wrapper 로 감싸
원본 bound 함수에 넘기기 직전 청크 한 건의 embedding 길이만 매핑 차원보다
1 짧게 만든다. bulk 호출과 응답은 실제 ES 가 수행하며 실패 응답을 mock 하지
않는다. snapshot 검증을 통과한 뒤 ES 호출 경계에서 의도적으로 손상시킨
인공 장애다.
"""

from __future__ import annotations

import asyncio
import json
import os
import sys
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from membershipflow_ai.cli import main as cli
from membershipflow_ai.persistence.elasticsearch_store import ElasticsearchStore

CORRUPT_AT = 1  # 두 번째 청크만 손상. N>=2 보장 확인용


async def observe(label: str, out: dict) -> None:
    client = cli.elasticsearch_client()
    try:
        alias = await ElasticsearchStore(
            client, cli.get_settings().elasticsearch_alias
        ).active_index()
        cat = await client.cat.indices(index="mf-*", h="index,docs.count", format="json")
        out[label] = {
            "alias_points_to": alias,
            "indices": sorted(
                (row["index"], row["docs.count"]) for row in cat.body
             ),
        }
    finally:
        await client.close()


async def run(manifest_path: str, evidence_path: str) -> int:
    evidence: dict = {"corrupt_index_position": CORRUPT_AT}
    await observe("before", evidence)

    original = ElasticsearchStore.bulk_index
    captured: dict = {}

    async def wrapper(self, index, chunks, corpus_version):
        captured["index"] = index
        captured["chunk_count"] = len(chunks)
        if len(chunks) <= CORRUPT_AT:
            raise RuntimeError(f"need > {CORRUPT_AT} chunks, got {len(chunks)}")
        damaged = list(chunks)
        victim = damaged[CORRUPT_AT]
        captured["corrupted_chunk_id"] = victim.chunk_id
        captured["original_dim"] = len(victim.embedding)
        damaged[CORRUPT_AT] = replace(victim, embedding=victim.embedding[:-1])
        captured["corrupted_dim"] = len(damaged[CORRUPT_AT].embedding)
        # 원본 함수가 실제 ES bulk 를 수행한다. 응답을 만들지 않는다.
        failures = await original(self, index, damaged, corpus_version)
        captured["failures"] = [
            {"chunk_id": f.chunk_id, "reason": f.reason[:300]} for f in failures
        ]
        return failures

    exit_code = 0
    try:
        with patch.object(ElasticsearchStore, "bulk_index", wrapper):
            await cli.build(manifest_path)
        evidence["build_outcome"] = "completed"  # 기대와 다름
    except Exception as exc:
        exit_code = 1
        evidence["build_outcome"] = "raised"
        evidence["exception_type"] = type(exc).__name__
        evidence["exception_message"] = str(exc)[:300]

    evidence["bulk_observation"] = captured
    evidence["manifest"] = json.loads(Path(manifest_path).read_text())

    # build 실패 시 verify 는 호출되지 않는다. 종료 후 별도로 확인한다.
    index = captured.get("index")
    if index:
        client = cli.elasticsearch_client()
        try:
            store = ElasticsearchStore(client, cli.get_settings().elasticsearch_alias)
            await client.indices.refresh(index=index)
            count = (await client.count(index=index)).body["count"]
            evidence["post_build_index_count"] = count
            expected = set(evidence["manifest"].get("expected_chunk_ids", []))
            try:
                await store.verify(index, expected)
                evidence["post_build_verify"] = "passed (기대와 다름)"
            except Exception as exc:
                evidence["post_build_verify"] = f"rejected: {type(exc).__name__}"
                evidence["post_build_verify_message"] = str(exc)[:300]
        finally:
            await client.close()

    await observe("after", evidence)
    Path(evidence_path).write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2, sort_keys=True), "utf-8"
    )
    return exit_code


if __name__ == "__main__":
    os.environ.setdefault("HF_HUB_OFFLINE", "1")
    sys.exit(asyncio.run(run(sys.argv[1], sys.argv[2])))
