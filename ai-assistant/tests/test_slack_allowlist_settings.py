"""allowlist 가 비면 Slack 핸들러는 모든 team·channel 을 통과시킨다.

그래서 `.env` 에 적은 값이 읽히지 않는 것은 설정 실수가 아니라 가드가
사라지는 문제다. 조용히 빈 목록이 되는 경로를 테스트로 고정한다.
"""

from __future__ import annotations

import pytest

from membershipflow_ai.config.settings import Settings

TEAM = "SLACK_ALLOWED_TEAM_IDS"
CHANNEL = "SLACK_ALLOWED_CHANNEL_IDS"


@pytest.fixture(autouse=True)
def clear_allowlist_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in (TEAM, CHANNEL, f"AI_{TEAM}", f"AI_{CHANNEL}"):
        monkeypatch.delenv(name, raising=False)


def build() -> Settings:
    return Settings(_env_file=None)


def test_unset_allowlist_is_empty() -> None:
    settings = build()
    assert settings.slack_allowed_team_ids == []
    assert settings.slack_allowed_channel_ids == []


def test_env_name_without_prefix_is_read(monkeypatch: pytest.MonkeyPatch) -> None:
    """`.env` 가 접두사 없이 적는 이름이다. 이게 무시되면 가드가 조용히 열린다."""
    monkeypatch.setenv(TEAM, "T01")
    monkeypatch.setenv(CHANNEL, "C01")
    settings = build()
    assert settings.slack_allowed_team_ids == ["T01"]
    assert settings.slack_allowed_channel_ids == ["C01"]


def test_prefixed_env_name_still_works(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(f"AI_{TEAM}", "T09")
    assert build().slack_allowed_team_ids == ["T09"]


def test_prefixed_name_wins_when_both_set(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(f"AI_{TEAM}", "TAI")
    monkeypatch.setenv(TEAM, "TPLAIN")
    assert build().slack_allowed_team_ids == ["TAI"]


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("T01,T02", ["T01", "T02"]),
        (" T01 , T02 ,", ["T01", "T02"]),
        ('["T07","T08"]', ["T07", "T08"]),
        ("T01", ["T01"]),
    ],
)
def test_comma_and_json_forms_both_parse(
    monkeypatch: pytest.MonkeyPatch, raw: str, expected: list[str]
) -> None:
    """사람이 `.env` 에 쉼표로 적는다. JSON 만 받으면 파싱 오류로 죽는다."""
    monkeypatch.setenv(TEAM, raw)
    assert build().slack_allowed_team_ids == expected


@pytest.mark.parametrize("raw", ["", "   ", ","])
def test_blank_value_is_empty_not_error(monkeypatch: pytest.MonkeyPatch, raw: str) -> None:
    """`.env` 의 `KEY=` 는 빈 문자열로 들어온다. 예외 없이 빈 목록이어야 한다."""
    monkeypatch.setenv(TEAM, raw)
    assert build().slack_allowed_team_ids == []
