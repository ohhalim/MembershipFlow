"""Run from ai-assistant; creates one retained index, never switches an alias."""
from __future__ import annotations

import asyncio
import hashlib
import json
import math
import os
import subprocess
import time
from pathlib import Path

REVISION = "5617a9f61b028005a4858fdac845db406aefb181"
os.environ.update(
    AI_EMBEDDING_PROVIDER="sentence-transformers",
    AI_EMBEDDING_MODEL="BAAI/bge-m3",
    AI_EMBEDDING_REVISION=REVISION,
    HF_HUB_OFFLINE="1",
    TRANSFORMERS_OFFLINE="1",
    HF_HUB_DISABLE_PROGRESS_BARS="1",
)

from membershipflow_ai.cli import main as cli  # noqa: E402
from membershipflow_ai.persistence.elasticsearch_store import ElasticsearchStore  # noqa: E402
from membershipflow_ai.retrieval.elasticsearch import ElasticsearchRetriever  # noqa: E402

ROOT = Path(__file__).resolve().parent


async def main() -> None:
    report = {
        "status": "STARTED",
        "code_sha": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], text=True
        ).strip(),
        "corpus_config_sha256": hashlib.sha256(Path("config/corpus.yml").read_bytes()).hexdigest(),
        "model": "BAAI/bge-m3",
        "revision": REVISION,
        "offline": True,
        "alias_switch_executed": False,
    }
    # Exclusive reservation prevents overwriting evidence or accidentally rerunning.
    with (ROOT / "observation.json").open("x") as output:
        json.dump(report, output, indent=2)
    client = cli.elasticsearch_client()
    settings = cli.get_settings()
    store = ElasticsearchStore(client, settings.elasticsearch_alias)

    async def state() -> dict:
        response = await client.indices.get_alias(index=f"{settings.elasticsearch_alias}-*")
        return {
            name: {
                "aliases": body["aliases"],
                "count": (await client.count(index=name))["count"],
            }
            for name, body in sorted(response.body.items())
        }

    try:
        report["before"] = await state()
        started = time.monotonic()
        await cli.build(str(ROOT / "manifest.json"))
        report["build_seconds"] = round(time.monotonic() - started, 3)
        manifest = cli._load_validated_manifest(str(ROOT / "manifest.json"))
        index = manifest["physical_index"]
        report["physical_index"] = index
        identity = await store.index_identity(index)
        cli.check_identity(index, identity, manifest)
        await store.verify(index, set(manifest["expected_chunk_ids"]))
        report["identity"] = identity
        report["source_count"] = manifest["source_count"]
        report["chunk_count"] = manifest["chunk_count"]
        query = "구독의 이용 가능 조건"
        vector = cli.embedding_provider().embed_query(query)
        assert len(vector) == manifest["dimension"] and all(math.isfinite(v) for v in vector)
        assert any(v != 0 for v in vector)
        retriever = ElasticsearchRetriever(client, index, strict_path=True)
        report["searches"] = {}
        for mode, text, hits in (
            ("keyword", "isActiveAt", await retriever.keyword_search("isActiveAt", k=5)),
            ("vector", query, await retriever.vector_search(vector, k=5)),
        ):
            assert hits and all(math.isfinite(hit.score) for hit in hits)
            report["searches"][mode] = {
                "query": text,
                "hits": [{"chunk_id": hit.chunk.chunk_id, "source_uri": hit.chunk.source_uri,
                          "path": hit.chunk.path, "score": hit.score} for hit in hits],
            }
        report["after"] = await state()
        assert all(report["after"].get(k) == v for k, v in report["before"].items())
        assert set(report["after"]) - set(report["before"]) == {index}
        assert report["after"][index]["aliases"] == {}
        report["status"] = "PASSED"
    except BaseException as exc:
        report.update(status="FAILED", error_type=type(exc).__name__)
        raise
    finally:
        await client.close()
        (ROOT / "observation.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        )
    print(json.dumps({k: report[k] for k in (
        "status", "physical_index", "build_seconds", "source_count", "chunk_count"
    )}))


if __name__ == "__main__":
    asyncio.run(main())
