"""alias 는 검색이 읽는 지점이라 잘못된 전환은 즉시 모든 사용자에게 보인다.

publish 든 rollback 이든 전환 근거는 같다: 그 인덱스가 어떤 청크 집합을 온전히
담고 있다는 VALIDATED manifest. 인덱스 이름만으로는 그것을 알 수 없다.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from membershipflow_ai.cli import main as cli

INDEX = "mf-ai-chunks-abc123"
CHUNKS = ["c1", "c2"]


def manifest(**overrides: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "status": "VALIDATED",
        "physical_index": INDEX,
        "expected_chunk_ids": list(CHUNKS),
    }
    base.update(overrides)
    return base


class StubIndices:
    def __init__(self, owner: StubClient) -> None:
        self._owner = owner

    async def exists(self, *, index: str) -> bool:
        return index in self._owner.existing

    async def get_mapping(self, *, index: str) -> Any:
        props: dict[str, Any] = {"embedding": {"type": "dense_vector", "dims": 1024}}
        if index in self._owner.with_symbol_path:
            props["symbol_path"] = {}
        return type("Resp", (), {"body": {index: {"mappings": {"properties": props}}}})()

    async def refresh(self, *, index: str) -> None:
        return None

    async def update_aliases(self, *, actions: list[dict[str, Any]]) -> None:
        self._owner.alias_actions.append(actions)

    async def get_alias(self, *, name: str) -> Any:
        if self._owner.active is None:
            from elasticsearch import NotFoundError

            raise NotFoundError("missing", meta=None, body=None)
        return type("Resp", (), {"body": {self._owner.active: {}}})()


class StubClient:
    def __init__(
        self,
        existing: set[str],
        active: str | None,
        indexed_ids: list[str] | None = None,
        with_symbol_path: set[str] | None = None,
    ) -> None:
        self.existing = existing
        self.active = active
        self.indexed_ids = CHUNKS if indexed_ids is None else indexed_ids
        self.with_symbol_path = existing if with_symbol_path is None else with_symbol_path
        self.alias_actions: list[list[dict[str, Any]]] = []
        self.closed = False
        self.indices = StubIndices(self)

    async def count(self, *, index: str) -> Any:
        body = {"count": len(self.indexed_ids), "_shards": {"failed": 0}}
        return type("Resp", (), {"body": body})()

    async def mget(self, *, index: str, ids: list[str], **_: object) -> Any:
        docs = [{"_id": i, "found": i in self.indexed_ids} for i in ids]
        return type("Resp", (), {"body": {"docs": docs}})()

    async def close(self) -> None:
        self.closed = True


@pytest.fixture
def manifest_file(tmp_path: Path):
    def write(data: dict[str, Any]) -> str:
        path = tmp_path / "m.json"
        path.write_text(json.dumps(data, ensure_ascii=False), "utf-8")
        return str(path)

    return write


def patch_client(monkeypatch: pytest.MonkeyPatch, client: StubClient) -> StubClient:
    monkeypatch.setattr(cli, "elasticsearch_client", lambda: client)
    return client


# --- manifest 게이트: publish 와 rollback 에 같은 기준을 적용한다 ---------------


@pytest.mark.parametrize("command", ["publish", "rollback"])
async def test_failed_manifest_is_refused(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any, command: str
) -> None:
    client = patch_client(monkeypatch, StubClient({INDEX}, active=None))
    with pytest.raises(SystemExit, match="VALIDATED"):
        await getattr(cli, command)(manifest_file(manifest(status="FAILED")))
    assert client.alias_actions == []


@pytest.mark.parametrize("command", ["publish", "rollback"])
async def test_missing_manifest_file_is_refused(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, command: str
) -> None:
    """인덱스 이름만 아는 상태로는 전환하지 않는다."""
    patch_client(monkeypatch, StubClient({INDEX}, active=None))
    with pytest.raises(SystemExit, match="가 없다"):
        await getattr(cli, command)(str(tmp_path / "nope.json"))


async def test_manifest_without_chunk_ids_is_refused(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any
) -> None:
    patch_client(monkeypatch, StubClient({INDEX}, active=None))
    with pytest.raises(SystemExit, match="expected_chunk_ids"):
        await cli.publish(manifest_file(manifest(expected_chunk_ids=[])))


# --- 인덱스 상태 게이트 ------------------------------------------------------


async def test_missing_index_is_refused(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any
) -> None:
    client = patch_client(monkeypatch, StubClient(set(), active=None))
    with pytest.raises(SystemExit, match="존재하지 않는다"):
        await cli.publish(manifest_file(manifest()))
    assert client.alias_actions == []


async def test_index_without_symbol_path_is_refused(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any
) -> None:
    client = patch_client(
        monkeypatch, StubClient({INDEX}, active=None, with_symbol_path=set())
    )
    with pytest.raises(SystemExit, match="symbol_path"):
        await cli.publish(manifest_file(manifest()))
    assert client.alias_actions == []


@pytest.mark.parametrize("command", ["publish", "rollback"])
async def test_partial_index_is_refused(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any, command: str
) -> None:
    """FAILED build 가 남긴 부분 색인은 매핑이 멀쩡해 보여도 전환하지 않는다."""
    client = patch_client(
        monkeypatch,
        StubClient({INDEX}, active="mf-ai-chunks-old", indexed_ids=["c1"]),
    )
    with pytest.raises(SystemExit, match="전환 전 검증 실패"):
        await getattr(cli, command)(manifest_file(manifest()))
    assert client.alias_actions == []
    assert client.active == "mf-ai-chunks-old"


@pytest.mark.parametrize("command", ["publish", "rollback"])
async def test_empty_index_is_refused(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any, command: str
) -> None:
    client = patch_client(
        monkeypatch, StubClient({INDEX}, active="mf-ai-chunks-old", indexed_ids=[])
    )
    with pytest.raises(SystemExit, match="전환 전 검증 실패"):
        await getattr(cli, command)(manifest_file(manifest()))
    assert client.alias_actions == []


# --- 전환 동작 ---------------------------------------------------------------


async def test_already_active_index_is_not_repointed(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any, capsys: pytest.CaptureFixture[str]
) -> None:
    client = patch_client(monkeypatch, StubClient({INDEX}, active=INDEX))
    await cli.publish(manifest_file(manifest()))
    assert client.alias_actions == []
    assert "변경 없음" in capsys.readouterr().out


@pytest.mark.parametrize("command", ["publish", "rollback"])
async def test_successful_switch_is_atomic(
    monkeypatch: pytest.MonkeyPatch,
    manifest_file: Any,
    capsys: pytest.CaptureFixture[str],
    command: str,
) -> None:
    old = "mf-ai-chunks-old"
    client = patch_client(monkeypatch, StubClient({INDEX, old}, active=old))
    await getattr(cli, command)(manifest_file(manifest()))
    assert len(client.alias_actions) == 1
    actions = client.alias_actions[0]
    assert actions[0]["remove"]["index"] == old
    assert actions[1]["add"]["index"] == INDEX
    out = capsys.readouterr().out
    assert old in out and INDEX in out
    assert "삭제하지 않는다" in out


async def test_client_is_closed_on_failure(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any
) -> None:
    client = patch_client(monkeypatch, StubClient(set(), active=None))
    with pytest.raises(SystemExit):
        await cli.publish(manifest_file(manifest()))
    assert client.closed is True
