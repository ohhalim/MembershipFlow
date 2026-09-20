"""실패가 가드에 걸린 것인지 크래시인지 출력만 보고 구분할 수 있어야 한다.

build 는 manifest 에 원인을 기록하므로, 출력은 조사 지점을 알려주면 된다.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from membershipflow_ai.cli import main as cli


def test_existing_manifest_is_refused_with_a_message(tmp_path: Path) -> None:
    target = tmp_path / "m.json"
    target.write_text('{"status": "VALIDATED"}', "utf-8")
    with pytest.raises(SystemExit) as excinfo:
        cli._open_new_manifest(str(target))
    message = str(excinfo.value)
    assert "이미 있다" in message
    assert "덮어쓰지 않는다" in message
    # 기존 기록이 남아 있어야 한다
    assert json.loads(target.read_text())["status"] == "VALIDATED"


def test_missing_parent_directory_is_refused_with_a_message(tmp_path: Path) -> None:
    with pytest.raises(SystemExit) as excinfo:
        cli._open_new_manifest(str(tmp_path / "nope" / "m.json"))
    assert "상위 디렉터리" in str(excinfo.value)


def test_new_path_opens_and_is_writable(tmp_path: Path) -> None:
    target = tmp_path / "m.json"
    with cli._open_new_manifest(str(target)) as handle:
        handle.write("{}")
    assert target.read_text() == "{}"


async def test_build_failure_records_manifest_and_points_at_it(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """실패해도 manifest 는 FAILED 로 남고, 메시지가 그 경로를 알려준다."""
    target = tmp_path / "m.json"

    def explode(*_: object, **__: object) -> None:
        raise ValueError("snapshot 준비 실패")

    # The failure under test must not depend on cwd or a real corpus file.
    monkeypatch.setattr(cli, "CorpusScanner", lambda *_: object())
    monkeypatch.setattr(cli, "prepare_snapshot", explode)
    monkeypatch.setattr(cli, "embedding_provider", lambda: None)

    with pytest.raises(SystemExit) as excinfo:
        await cli.build(str(target))

    message = str(excinfo.value)
    assert "build 실패" in message
    assert "ValueError" in message
    assert str(target) in message

    report = json.loads(target.read_text())
    assert report["status"] == "FAILED"
    assert report["error_type"] == "ValueError"


async def test_build_failure_keeps_original_exception_as_cause(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    def explode(*_: object, **__: object) -> None:
        raise ValueError("원인")

    # The failure under test must not depend on cwd or a real corpus file.
    monkeypatch.setattr(cli, "CorpusScanner", lambda *_: object())
    monkeypatch.setattr(cli, "prepare_snapshot", explode)
    monkeypatch.setattr(cli, "embedding_provider", lambda: None)

    with pytest.raises(SystemExit) as excinfo:
        await cli.build(str(tmp_path / "m.json"))
    assert isinstance(excinfo.value.__cause__, ValueError)
