"""색인 문서에 symbol_path 가 없으면 평가는 멈춰야 한다.

symbol fallback 은 비어있지 않은 경로를 만들어내므로, "경로가 비었는지"만
확인하는 검사는 손상된 경로를 통과시킨다. 그러면 검색에 성공한 근거가
실패로 집계되어 점수만 낮아지고 원인은 드러나지 않는다.
"""

from __future__ import annotations

import pytest

from membershipflow_ai.retrieval.elasticsearch import CorruptedPathError, _hit_to_chunk

SOURCE = {
    "chunk_id": "c1",
    "source_path": "docs/DEPLOYMENT.md",
    "source_type": "markdown",
    "source_hash": "h",
    "body": "",
    "line_start": 68,
    "line_end": 85,
    "symbol": "배포 가이드.5. SSL 인증서 발급",
}


def test_strict_mode_rejects_missing_symbol_path() -> None:
    with pytest.raises(CorruptedPathError, match="symbol_path"):
        _hit_to_chunk(SOURCE, strict_path=True)


@pytest.mark.parametrize("bad", ["배포 가이드.5. SSL 인증서 발급", None, 42, {"a": 1}])
def test_strict_mode_rejects_non_array_symbol_path(bad: object) -> None:
    with pytest.raises(CorruptedPathError):
        _hit_to_chunk({**SOURCE, "symbol_path": bad}, strict_path=True)


def test_strict_mode_accepts_array() -> None:
    path = ["배포 가이드", "5. SSL 인증서 발급"]
    chunk = _hit_to_chunk({**SOURCE, "symbol_path": path}, strict_path=True)
    assert list(chunk.path) == path


def test_lenient_mode_keeps_serving_fallback() -> None:
    """서비스 경로는 구형 색인에서도 계속 답해야 한다."""
    chunk = _hit_to_chunk(SOURCE, strict_path=False)
    assert list(chunk.path) == ["배포 가이드", "5", " SSL 인증서 발급"]


def test_fallback_path_is_not_empty_so_emptiness_check_is_insufficient() -> None:
    # 이 사실이 strict 모드가 필요한 이유다.
    assert _hit_to_chunk(SOURCE, strict_path=False).path
