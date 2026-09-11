"""cli.evaluate 를 외부 서비스 없이 그대로 호출한다.

split 기본값, 구형 mapping 거부, 후보 깊이, provenance 는 production 함수가
실제로 그렇게 동작할 때만 의미가 있다. 테스트가 로직을 복제하면 회귀를 놓친다.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from membershipflow_ai.cli import main as cli
from membershipflow_ai.domain.documents import ActiveChunk, SearchHit, SourceType

CASES = [
    {
        "id": "ret-001", "question": "튜닝 질문", "split": "tuning",
        "difficulty": "lexical",
        "expected_sources": [{"source_uri": "S.java", "anchor": "S > hit"}],
        "reviewed": True,
    },
    {
        "id": "ret-002", "question": "홀드아웃 질문", "split": "held_out",
        "difficulty": "lexical",
        "expected_sources": [{"source_uri": "S.java", "anchor": "S > hit"}],
        "reviewed": True,
    },
]


def make_hit(rank: int, name: str = "hit") -> SearchHit:
    return SearchHit(
        chunk=ActiveChunk(
            chunk_id=f"c{rank}", source_uri="S.java", source_type=SourceType.JAVA,
            source_hash="h", ordinal=0, path=("pkg", "S", name), content="body",
            line_start=1, line_end=2, embedding=(),
        ),
        score=1.0, rank=rank, retriever="stub",
    )


class StubStore:
    def __init__(self, *_: object, **__: object) -> None:
        pass

    async def active_index(self) -> str:
        return "mf-ai-chunks-test"


class StubClient:
    def __init__(self, properties: dict[str, Any]) -> None:
        self._properties = properties
        self.closed = False
        self.indices = self

    async def get_mapping(self, *, index: str) -> Any:
        body = {index: {"mappings": {"properties": self._properties}}}
        return type("Resp", (), {"body": body})()

    async def close(self) -> None:
        self.closed = True


class StubEmbedder:
    model_id = "stub-embed"
    revision = None

    def embed_query(self, text: str) -> list[float]:
        return [0.0]


@pytest.fixture
def cases_file(tmp_path: Path) -> Path:
    path = tmp_path / "cases.jsonl"
    path.write_text("\n".join(json.dumps(c, ensure_ascii=False) for c in CASES), "utf-8")
    return path


def patch_cli(
    monkeypatch: pytest.MonkeyPatch,
    properties: dict[str, Any] | None = None,
    depths: list[int] | None = None,
) -> StubClient:
    client = StubClient(properties if properties is not None else {"symbol_path": {}})

    class StubEngine:
        def __init__(self, *_: object, **kwargs: object) -> None:
            self.strict_path = kwargs.get("strict_path")

        async def keyword_search(self, query: str, *, k: int, **_: object) -> list[SearchHit]:
            if depths is not None:
                depths.append(k)
            return [make_hit(i + 1) for i in range(k)]

        async def vector_search(
            self, embedding: list[float], *, k: int, **_: object
        ) -> list[SearchHit]:
            if depths is not None:
                depths.append(k)
            return [make_hit(i + 1) for i in range(k)]

    monkeypatch.setattr(cli, "elasticsearch_client", lambda: client)
    monkeypatch.setattr(cli, "ElasticsearchStore", StubStore)
    monkeypatch.setattr(cli, "ElasticsearchRetriever", StubEngine)
    monkeypatch.setattr(cli, "embedding_provider", StubEmbedder)
    return client


async def test_default_split_runs_tuning_only(
    monkeypatch: pytest.MonkeyPatch, cases_file: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    patch_cli(monkeypatch)
    await cli.evaluate(str(cases_file), 2, ["keyword"], False, None, 5, "tuning")
    report = json.loads(capsys.readouterr().out)
    assert report["split"] == "tuning"
    assert report["results"]["keyword"]["overall"]["cases"] == 1
    assert [c["id"] for c in report["results"]["keyword"]["cases"]] == ["ret-001"]


async def test_held_out_must_be_chosen_explicitly(
    monkeypatch: pytest.MonkeyPatch, cases_file: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    patch_cli(monkeypatch)
    await cli.evaluate(str(cases_file), 2, ["keyword"], False, None, 5, "held_out")
    report = json.loads(capsys.readouterr().out)
    assert [c["id"] for c in report["results"]["keyword"]["cases"]] == ["ret-002"]


async def test_old_index_without_symbol_path_is_refused(
    monkeypatch: pytest.MonkeyPatch, cases_file: Path
) -> None:
    patch_cli(monkeypatch, properties={"symbol": {}})
    with pytest.raises(SystemExit, match="symbol_path"):
        await cli.evaluate(str(cases_file), 2, ["keyword"], False, None, 5, "tuning")


async def test_candidates_must_not_be_smaller_than_k(
    monkeypatch: pytest.MonkeyPatch, cases_file: Path
) -> None:
    patch_cli(monkeypatch)
    with pytest.raises(SystemExit, match="이어야 한다"):
        await cli.evaluate(str(cases_file), 10, ["keyword"], False, None, 5, "tuning")


async def test_every_mode_requests_the_same_candidate_depth(
    monkeypatch: pytest.MonkeyPatch, cases_file: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    depths: list[int] = []
    patch_cli(monkeypatch, depths=depths)
    await cli.evaluate(
        str(cases_file), 2, ["keyword", "vector", "hybrid"], False, None, 7, "tuning"
    )
    capsys.readouterr()
    assert depths and all(depth == 7 for depth in depths)


async def test_report_records_provenance(
    monkeypatch: pytest.MonkeyPatch, cases_file: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    patch_cli(monkeypatch)
    await cli.evaluate(str(cases_file), 2, ["keyword"], False, None, 5, "tuning")
    report = json.loads(capsys.readouterr().out)
    assert report["embedding_model"] == "stub-embed"
    assert report["candidates"] == 5
    assert len(report["cases_sha256"]) == 64
    assert report["reranker_model"] is None
    case = report["results"]["keyword"]["cases"][0]
    assert case["matched_ranks"] and case["returned"] and case["candidates"]
    assert "content" not in case["returned"][0]


async def test_unreviewed_cases_are_refused_without_allow_draft(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    path = tmp_path / "draft.jsonl"
    path.write_text(json.dumps({**CASES[0], "reviewed": False}, ensure_ascii=False), "utf-8")
    patch_cli(monkeypatch)
    with pytest.raises(SystemExit, match="reviewed=false"):
        await cli.evaluate(str(path), 2, ["keyword"], False, None, 5, "tuning")


async def test_evaluation_uses_strict_path_retriever(
    monkeypatch: pytest.MonkeyPatch, cases_file: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    seen: list[object] = []

    class RecordingEngine:
        def __init__(self, *_: object, **kwargs: object) -> None:
            seen.append(kwargs.get("strict_path"))

        async def keyword_search(self, query: str, *, k: int, **_: object) -> list[SearchHit]:
            return [make_hit(i + 1) for i in range(k)]

        async def vector_search(
            self, embedding: list[float], *, k: int, **_: object
        ) -> list[SearchHit]:
            return [make_hit(i + 1) for i in range(k)]

    patch_cli(monkeypatch)
    monkeypatch.setattr(cli, "ElasticsearchRetriever", RecordingEngine)
    await cli.evaluate(str(cases_file), 2, ["keyword"], False, None, 5, "tuning")
    capsys.readouterr()
    assert seen == [True]
