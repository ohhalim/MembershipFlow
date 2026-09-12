from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace
from typing import Any
from unittest.mock import AsyncMock

import pytest

from membershipflow_ai.cli import main as cli
from membershipflow_ai.ingestion.embeddings import DeterministicHashEmbedding
from membershipflow_ai.ingestion.scanner import CorpusScanner
from membershipflow_ai.ingestion.snapshot import prepare_snapshot


@pytest.fixture
def corpus(tmp_path: Path) -> tuple[Path, Path]:
    (tmp_path / "doc.md").write_text("# Topic\nA reproducible example.\n", "utf-8")
    config = tmp_path / "corpus.yml"
    config.write_text(
        "version: 1\nsources:\n  - path: doc.md\n    type: markdown\n"
        "limits:\n  max_file_bytes: 4096\n", "utf-8",
    )
    return tmp_path, config


def test_snapshot_is_repeatable_and_preserves_source_identity(corpus: tuple[Path, Path]) -> None:
    scanner = CorpusScanner(*corpus)
    first, metadata = prepare_snapshot(scanner, DeterministicHashEmbedding(8))
    second, other = prepare_snapshot(scanner, DeterministicHashEmbedding(8))
    assert first == second and metadata == other
    assert metadata["source_count"] == 1
    assert metadata["expected_chunk_ids"] == [first[0].chunk_id]
    assert metadata["sources"][0]["source_hash"] == first[0].source_hash


@pytest.mark.parametrize("vectors", [[], [[1.0]], [[float("nan")] * 8], [[0.0] * 8]])
def test_bad_embedding_prevents_build(
    corpus: tuple[Path, Path], vectors: list[list[float]]
) -> None:
    class BadEmbedding(DeterministicHashEmbedding):
        def embed_documents(self, texts: list[str]) -> list[list[float]]:
            return vectors

    with pytest.raises(ValueError, match="embedding"):
        prepare_snapshot(CorpusScanner(*corpus), BadEmbedding(8))


def test_missing_source_prevents_embedding(corpus: tuple[Path, Path]) -> None:
    class UnexpectedEmbedding(DeterministicHashEmbedding):
        def embed_documents(self, texts: list[str]) -> list[list[float]]:
            pytest.fail("must read all sources before embedding")

    (corpus[0] / "doc.md").unlink()
    with pytest.raises(FileNotFoundError):
        prepare_snapshot(CorpusScanner(*corpus), UnexpectedEmbedding(8))


def patch_build(monkeypatch: pytest.MonkeyPatch, corpus: tuple[Path, Path]) -> Any:
    store = SimpleNamespace(
        physical_index=lambda suffix: f"isolated-{suffix}",
        corpus_fingerprint=lambda chunks: "fingerprint",
        create=AsyncMock(), bulk_index=AsyncMock(return_value=[]),
        verify=AsyncMock(), publish=AsyncMock(),
    )
    client = SimpleNamespace(close=AsyncMock())
    store.client = client
    monkeypatch.setattr(cli, "get_settings", lambda: SimpleNamespace(
        repository_root=corpus[0], corpus_config=corpus[1], elasticsearch_alias="alias",
    ))
    monkeypatch.setattr(cli, "embedding_provider", lambda: DeterministicHashEmbedding(8))
    monkeypatch.setattr(cli, "elasticsearch_client", lambda: client)
    monkeypatch.setattr(cli, "ElasticsearchStore", lambda *args: store)
    return store


async def test_build_saves_manifest_without_publishing(
    monkeypatch: pytest.MonkeyPatch, corpus: tuple[Path, Path], tmp_path: Path,
) -> None:
    store = patch_build(monkeypatch, corpus)
    path = tmp_path / "manifest.json"
    await cli.build(str(path))
    report = json.loads(path.read_text("utf-8"))
    assert report["status"] == "VALIDATED"
    assert report["embedding_model"] == "fake-sha256-v1"
    store.verify.assert_awaited_once_with(
        report["physical_index"], set(report["expected_chunk_ids"]),
    )
    store.publish.assert_not_called()
    store.client.close.assert_awaited_once()


@pytest.mark.parametrize("stage", ["create", "bulk_index", "verify"])
async def test_failed_build_is_recorded_and_never_published(
    monkeypatch: pytest.MonkeyPatch, corpus: tuple[Path, Path], tmp_path: Path, stage: str,
) -> None:
    store = patch_build(monkeypatch, corpus)
    getattr(store, stage).side_effect = RuntimeError("simulated failure")
    path = tmp_path / "manifest.json"
    with pytest.raises(RuntimeError, match="simulated failure"):
        await cli.build(str(path))
    report = json.loads(path.read_text("utf-8"))
    assert report["status"] == "FAILED"
    assert report["physical_index"].startswith("isolated-")
    store.publish.assert_not_called()
    store.client.close.assert_awaited_once()


async def test_bulk_item_failure_blocks_validation(
    monkeypatch: pytest.MonkeyPatch, corpus: tuple[Path, Path], tmp_path: Path,
) -> None:
    store = patch_build(monkeypatch, corpus)
    store.bulk_index.return_value = [object()]
    with pytest.raises(RuntimeError, match="bulk indexing failed"):
        await cli.build(str(tmp_path / "manifest.json"))
    store.verify.assert_not_called()
    store.publish.assert_not_called()


async def test_manifest_is_never_overwritten(
    monkeypatch: pytest.MonkeyPatch, corpus: tuple[Path, Path], tmp_path: Path,
) -> None:
    store = patch_build(monkeypatch, corpus)
    path = tmp_path / "manifest.json"
    path.write_text("previous evidence", "utf-8")
    with pytest.raises(FileExistsError):
        await cli.build(str(path))
    assert path.read_text("utf-8") == "previous evidence"
    store.create.assert_not_called()


async def test_repeated_builds_use_separate_indexes(
    monkeypatch: pytest.MonkeyPatch, corpus: tuple[Path, Path], tmp_path: Path,
) -> None:
    store = patch_build(monkeypatch, corpus)
    await cli.build(str(tmp_path / "first.json"))
    await cli.build(str(tmp_path / "second.json"))
    indexes = [call.args[0] for call in store.create.await_args_list]
    assert len(set(indexes)) == 2
    store.publish.assert_not_called()
