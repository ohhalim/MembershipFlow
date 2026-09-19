"""alias 는 검색이 읽는 지점이라 잘못된 전환은 즉시 모든 사용자에게 보인다.

chunk id 와 개수는 모델이 달라도 일치하므로, 벡터 공간이 바뀐 것을 잡지 못한다.
인덱스에 기록된 모델 신원까지 대조한 뒤에만 전환한다.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from membershipflow_ai.cli import main as cli

INDEX = "mf-ai-chunks-abc123"
CHUNKS = ["c1", "c2"]
IDENTITY = {
    "schema_revision": "2",
    "embedding_model": "fake-sha256-v1",
    "embedding_revision": "1",
    "dimension": 1024,
    "snapshot_revision": "1",
}


def manifest(**overrides: Any) -> dict[str, Any]:
    base: dict[str, Any] = {
        "status": "VALIDATED",
        "physical_index": INDEX,
        "expected_chunk_ids": list(CHUNKS),
        **IDENTITY,
    }
    base.update(overrides)
    return base


class StubIndices:
    def __init__(self, owner: StubClient) -> None:
        self._owner = owner

    async def exists(self, *, index: str) -> bool:
        return index in self._owner.existing

    async def get_mapping(self, *, index: str) -> Any:
        props: dict[str, Any] = {}
        if index in self._owner.with_symbol_path:
            props["symbol_path"] = {}
        props["embedding"] = {"type": "dense_vector", "dims": self._owner.mapping_dims}
        mappings: dict[str, Any] = {"properties": props}
        meta = self._owner.meta.get(index)
        if meta is not None:
            mappings["_meta"] = meta
        return type("Resp", (), {"body": {index: {"mappings": mappings}}})()

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
        meta: dict[str, dict[str, Any]] | None = None,
        mapping_dims: int = 1024,
    ) -> None:
        self.existing = existing
        self.active = active
        self.indexed_ids = CHUNKS if indexed_ids is None else indexed_ids
        self.with_symbol_path = existing if with_symbol_path is None else with_symbol_path
        self.meta = {name: dict(IDENTITY) for name in existing} if meta is None else meta
        self.mapping_dims = mapping_dims
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


@pytest.fixture(autouse=True)
def fake_embedding_settings(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_EMBEDDING_PROVIDER", "fake")
    cli.get_settings.cache_clear()
    yield
    cli.get_settings.cache_clear()


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


# --- manifest 게이트 ---------------------------------------------------------


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


async def test_index_without_identity_is_refused(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any
) -> None:
    """기록이 없는 과거 인덱스를 안전하다고 추정하지 않는다."""
    client = patch_client(monkeypatch, StubClient({INDEX}, active=None, meta={}))
    with pytest.raises(SystemExit, match="모델 신원 기록이 없다"):
        await cli.publish(manifest_file(manifest()))
    assert client.alias_actions == []


@pytest.mark.parametrize("command", ["publish", "rollback"])
async def test_partial_index_is_refused(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any, command: str
) -> None:
    """FAILED build 가 남긴 부분 색인은 매핑이 맞아도 전환하지 않는다."""
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


# --- 모델 신원 게이트 --------------------------------------------------------


async def test_same_dimension_different_model_is_refused(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any
) -> None:
    """차원이 같아도 벡터 공간이 다르면 검색이 조용히 어긋난다."""
    other = {**IDENTITY, "embedding_model": "BAAI/bge-m3"}
    client = patch_client(
        monkeypatch, StubClient({INDEX}, active=None, meta={INDEX: other})
    )
    with pytest.raises(SystemExit, match="벡터 공간이 달라"):
        await cli.publish(manifest_file(manifest(embedding_model="BAAI/bge-m3")))
    assert client.alias_actions == []


async def test_dimension_mismatch_with_settings_is_refused(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any
) -> None:
    other = {**IDENTITY, "dimension": 768}
    client = patch_client(
        monkeypatch,
        StubClient({INDEX}, active=None, meta={INDEX: other}, mapping_dims=768),
    )
    with pytest.raises(SystemExit, match="vector 검색이 실패한다"):
        await cli.publish(manifest_file(manifest(dimension=768)))
    assert client.alias_actions == []


async def test_revision_mismatch_is_refused(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any
) -> None:
    other = {**IDENTITY, "embedding_revision": "2"}
    client = patch_client(
        monkeypatch, StubClient({INDEX}, active=None, meta={INDEX: other})
    )
    with pytest.raises(SystemExit, match="revision"):
        await cli.publish(manifest_file(manifest(embedding_revision="2")))
    assert client.alias_actions == []


async def test_null_revision_is_refused(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any, tmp_path: Path
) -> None:
    """revision 이 없으면 어떤 가중치인지 증명할 수 없다."""
    monkeypatch.setenv("AI_EMBEDDING_PROVIDER", "sentence-transformers")
    monkeypatch.setenv("AI_EMBEDDING_MODEL", "BAAI/bge-m3")
    monkeypatch.setenv("AI_EMBEDDING_REVISION", "")
    cli.get_settings.cache_clear()
    other = {**IDENTITY, "embedding_model": "BAAI/bge-m3", "embedding_revision": None}
    client = patch_client(
        monkeypatch, StubClient({INDEX}, active=None, meta={INDEX: other})
    )
    data = manifest(embedding_model="BAAI/bge-m3", embedding_revision=None)
    with pytest.raises(SystemExit, match="revision 이 기록되지 않았다"):
        await cli.publish(manifest_file(data))
    assert client.alias_actions == []


async def test_manifest_index_identity_mismatch_is_refused(
    monkeypatch: pytest.MonkeyPatch, manifest_file: Any
) -> None:
    other = {**IDENTITY, "schema_revision": "1"}
    client = patch_client(
        monkeypatch, StubClient({INDEX}, active=None, meta={INDEX: other})
    )
    with pytest.raises(SystemExit, match="schema_revision 가 manifest 와 다르다"):
        await cli.publish(manifest_file(manifest()))
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


def test_fake_identity_matches_the_real_provider() -> None:
    """expected_identity 의 fake 값이 실제 구현과 어긋나면 검사가 무의미해진다."""
    from membershipflow_ai.ingestion.embeddings import DeterministicHashEmbedding

    provider = DeterministicHashEmbedding()
    wanted = cli.expected_identity()
    assert wanted["embedding_model"] == provider.model_id
    assert wanted["embedding_revision"] == provider.revision
    assert wanted["dimension"] == provider.dimension
