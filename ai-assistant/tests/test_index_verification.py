from __future__ import annotations

from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock

import pytest

from membershipflow_ai.persistence.elasticsearch_store import ElasticsearchStore


def client_with(ids: set[str]) -> Any:
    async def mget(*, index: str, ids: list[str], **kwargs: object) -> SimpleNamespace:
        return SimpleNamespace(body={"docs": [
            {"_id": item, "found": item in stored} for item in ids
        ]})

    stored = ids
    return SimpleNamespace(
        indices=SimpleNamespace(refresh=AsyncMock()),
        count=AsyncMock(return_value=SimpleNamespace(body={"count": len(ids)})),
        mget=AsyncMock(side_effect=mget),
    )


async def test_same_count_with_wrong_ids_is_rejected() -> None:
    client = client_with({"a", "unrelated"})
    with pytest.raises(RuntimeError, match="chunk ID mismatch"):
        await ElasticsearchStore(client, "alias").verify("build", {"a", "b"})


async def test_count_mismatch_fails_before_id_lookup() -> None:
    client = client_with({"a"})
    with pytest.raises(RuntimeError, match="chunk count mismatch"):
        await ElasticsearchStore(client, "alias").verify("build", {"a", "b"})
    client.mget.assert_not_called()


async def test_exact_set_passes_without_loading_document_bodies() -> None:
    client = client_with({"a", "b"})
    await ElasticsearchStore(client, "alias").verify("build", {"b", "a"})
    client.indices.refresh.assert_awaited_once_with(index="build")
    client.mget.assert_awaited_once_with(
        index="build", ids=["a", "b"], source=False, realtime=False,
    )


@pytest.mark.parametrize("docs", [
    [{"_id": "a", "error": {"type": "unavailable_shards_exception"}}],
    [],
    [{"_id": "a", "found": False}],
    [{"_id": "wrong", "found": True}],
    [{"_id": "a", "found": True}, {"_id": "a", "found": True}],
])
async def test_partial_or_invalid_mget_response_fails(docs: list[dict[str, Any]]) -> None:
    client = client_with({"a"})
    client.mget = AsyncMock(return_value=SimpleNamespace(body={"docs": docs}))
    with pytest.raises(RuntimeError, match="chunk ID mismatch"):
        await ElasticsearchStore(client, "alias").verify("build", {"a"})


async def test_failed_count_shard_is_rejected() -> None:
    client = client_with({"a"})
    client.count.return_value = SimpleNamespace(body={"count": 1, "_shards": {"failed": 1}})
    with pytest.raises(RuntimeError, match="failed shards"):
        await ElasticsearchStore(client, "alias").verify("build", {"a"})
    client.mget.assert_not_called()


async def test_large_id_set_is_verified_in_batches() -> None:
    ids = {f"id-{i:04}" for i in range(1001)}
    client = client_with(ids)
    await ElasticsearchStore(client, "alias").verify("build", ids)
    batches = [call.kwargs["ids"] for call in client.mget.await_args_list]
    assert [len(batch) for batch in batches] == [500, 500, 1]
    assert {item for batch in batches for item in batch} == ids
