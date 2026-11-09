"""검색 결과의 경로는 색인 전 경로와 같아야 한다.

경로를 "." 으로 이어붙여 저장하고 "." 으로 분해해 복원하면,
섹션 제목이나 패키지명에 "." 이 들어간 순간 원본과 달라진다.
평가 채점이 anchor 를 경로로 맞추기 때문에 이 손상은 검색 실패로 오인된다.
"""

from __future__ import annotations

import pytest

from membershipflow_ai.retrieval.elasticsearch import _hit_to_chunk

BASE = {
    "chunk_id": "c1",
    "source_path": "docs/DEPLOYMENT.md",
    "source_type": "markdown",
    "source_hash": "h",
    "body": "",
    "line_start": 68,
    "line_end": 85,
}


@pytest.mark.parametrize(
    "path",
    [
        ["배포 가이드", "5. SSL 인증서 발급"],
        ["com.membershipflow.subscription.entity", "Subscription", "isActiveAt"],
        ["배포 가이드", "8. Google OAuth2 Redirect URI 추가"],
        ["단일 섹션"],
    ],
)
def test_symbol_path_survives_round_trip(path: list[str]) -> None:
    source = {**BASE, "symbol": ".".join(path), "symbol_path": path}
    assert list(_hit_to_chunk(source).path) == path


def test_dotted_title_is_corrupted_without_symbol_path() -> None:
    # symbol 만으로 복원하면 깨진다는 사실 자체를 고정한다.
    path = ["배포 가이드", "5. SSL 인증서 발급"]
    source = {**BASE, "symbol": ".".join(path)}
    assert list(_hit_to_chunk(source).path) != path


def test_falls_back_to_symbol_when_path_missing() -> None:
    source = {**BASE, "symbol": "A.B"}
    assert list(_hit_to_chunk(source).path) == ["A", "B"]
