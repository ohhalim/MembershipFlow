"""Actual build and alias roundtrip; retains indexes, removes only the test alias."""

import asyncio
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ALIAS = "mf-ai-verify-pinned-20260919"
os.environ.update(
    AI_EMBEDDING_PROVIDER="sentence-transformers",
    AI_EMBEDDING_MODEL="BAAI/bge-m3",
    AI_EMBEDDING_REVISION="5617a9f61b028005a4858fdac845db406aefb181",
    AI_ELASTICSEARCH_ALIAS=ALIAS,
    HF_HUB_OFFLINE="1",
    TRANSFORMERS_OFFLINE="1",
    HF_HUB_DISABLE_PROGRESS_BARS="1",
)
from membershipflow_ai.cli import main as cli  # noqa: E402
from membershipflow_ai.persistence.elasticsearch_store import ElasticsearchStore  # noqa: E402
from membershipflow_ai.retrieval.elasticsearch import ElasticsearchRetriever  # noqa: E402


async def main():
    report = {
        "status": "STARTED",
        "alias": ALIAS,
        "steps": [],
        "code_sha": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
    }
    with (ROOT / "observation.json").open("x") as f:
        json.dump(report, f)
    client = cli.elasticsearch_client()
    store = ElasticsearchStore(client, ALIAS)
    owned = False

    async def protected_state():
        response = await client.indices.get_alias(index="mf-ai-chunks-*")
        return {
            name: {"aliases": body["aliases"], "count": (await client.count(index=name))["count"]}
            for name, body in sorted(response.body.items())
        }

    async def step(label, action, manifest, expected):
        await action(str(manifest))
        active = await store.active_index()
        assert active == expected, (label, active, expected)
        hits = await ElasticsearchRetriever(client, active, strict_path=True).vector_search(
            vector, k=3
        )
        assert len(hits) == 3
        report["steps"].append(
            {
                "step": label,
                "active_index": active,
                "vector_hit_ids": [h.chunk.chunk_id for h in hits],
            }
        )

    try:
        assert await store.active_index() is None, "test alias already exists; stop"
        report["before"] = await protected_state()
        previous = ROOT.parent / "2026-09-19-pinned-model-build/manifest.json"
        a = cli._load_validated_manifest(str(previous))
        following = ROOT / "manifest-B.json"
        await cli.build(str(following))
        b = cli._load_validated_manifest(str(following))
        assert a["physical_index"] != b["physical_index"]
        assert a["expected_chunk_ids"] == b["expected_chunk_ids"]
        report["indexes"] = {"A": a["physical_index"], "B": b["physical_index"]}
        vector = cli.embedding_provider().embed_query("구독의 이용 가능 조건")
        owned = True
        await step("initial A", cli.publish, previous, a["physical_index"])
        await step("A no-op", cli.publish, previous, a["physical_index"])
        await step("A to B", cli.publish, following, b["physical_index"])
        await step("rollback B to A", cli.rollback, previous, a["physical_index"])
        for manifest in (a, b):
            await store.verify(manifest["physical_index"], set(manifest["expected_chunk_ids"]))
        report["status"] = "PASSED"
    except BaseException as exc:
        report.update(status="FAILED", error_type=type(exc).__name__)
        raise
    finally:
        try:
            if owned:
                active = await store.active_index()
                if active is not None:
                    assert active in report["indexes"].values(), "unexpected test alias target"
                    result = await client.indices.delete_alias(index=active, name=ALIAS)
                    report["cleanup_acknowledged"] = result["acknowledged"]
                assert await store.active_index() is None
            report["after"] = await protected_state()
            assert report["before"] == report["after"], "protected indexes changed"
        except BaseException as exc:
            report.update(status="FAILED", cleanup_error_type=type(exc).__name__)
            raise
        finally:
            await client.close()
            (ROOT / "observation.json").write_text(
                json.dumps(report, ensure_ascii=False, indent=2) + "\n"
            )
    print(
        json.dumps(
            {
                "status": report["status"],
                "steps": len(report["steps"]),
                "indexes": report["indexes"],
            }
        )
    )


if __name__ == "__main__":
    asyncio.run(main())
