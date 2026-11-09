"""운영 질문은 대상과 기간이 정해져야 조회할 수 있다.

이전 구현은 모든 METRIC 질문을 collect_runs 로 보내고 날짜를 빈 문자열로
넘겼다. "현재 구독자 수"가 수집 조회로 가고, "오늘"과 "어제"가 같은 요청이
되어 조회 성공과 질문에 맞는 답변을 구분하지 못했다.
"""

from __future__ import annotations

from datetime import date

import pytest

from membershipflow_ai.agent.contracts import Route
from membershipflow_ai.agent.graph import (
    answer_metric,
    metric_kind,
    resolve_target_date,
    rule_route,
)
from membershipflow_ai.agent.tools import MetricToolUnavailable

TODAY = date(2026, 9, 10)


class StubMetrics:
    def __init__(self, fail: bool = False) -> None:
        self.calls: list[tuple[str, str]] = []
        self._fail = fail

    async def collect_runs(self, date_value: str) -> dict[str, object]:
        self.calls.append(("collect", date_value))
        if self._fail:
            raise MetricToolUnavailable("조회 API 미구현 (404)")
        return {"runs": 3}

    async def subscription_metrics(self, date_value: str) -> dict[str, object]:
        self.calls.append(("subscription", date_value))
        if self._fail:
            raise MetricToolUnavailable("조회 API 미구현 (404)")
        return {"active": 12}


@pytest.mark.parametrize(
    ("question", "expected"),
    [("오늘 수집 결과는?", "2026-09-10"), ("어제 수집 결과는?", "2026-09-09")],
)
def test_relative_date_is_resolved(question: str, expected: str) -> None:
    assert resolve_target_date(question, today=TODAY) == expected


def test_explicit_date_wins() -> None:
    assert resolve_target_date("2026-08-01 수집 결과는?", today=TODAY) == "2026-08-01"


def test_missing_date_is_not_guessed() -> None:
    assert resolve_target_date("수집 결과는?", today=TODAY) is None


@pytest.mark.parametrize(
    ("question", "expected"),
    [
        ("오늘 구독자 수 알려줘", "subscription"),
        ("오늘 수집 결과는?", "collect"),
        ("오늘 수집이랑 구독자 수 다 알려줘", None),
    ],
)
def test_metric_kind(question: str, expected: str | None) -> None:
    assert metric_kind(question) == expected


async def test_subscription_question_does_not_call_collect() -> None:
    metrics = StubMetrics()
    result = await answer_metric(None, "오늘 구독자 수 알려줘", metrics)  # type: ignore[arg-type]
    assert result.route is Route.METRIC
    assert metrics.calls == [("subscription", date.today().isoformat())]
    assert result.grounded is True


async def test_ambiguous_target_asks_instead_of_querying() -> None:
    metrics = StubMetrics()
    result = await answer_metric(None, "오늘 수집이랑 구독자 수", metrics)  # type: ignore[arg-type]
    assert result.route is Route.CLARIFY
    assert result.failure == "ambiguous_metric_target"
    assert metrics.calls == []


async def test_missing_date_asks_instead_of_querying() -> None:
    metrics = StubMetrics()
    result = await answer_metric(None, "수집 결과 알려줘", metrics)  # type: ignore[arg-type]
    assert result.route is Route.CLARIFY
    assert result.failure == "missing_date"
    assert metrics.calls == []


async def test_query_failure_is_not_reported_as_grounded() -> None:
    metrics = StubMetrics(fail=True)
    result = await answer_metric(None, "오늘 수집 결과는?", metrics)  # type: ignore[arg-type]
    assert result.grounded is False
    assert result.failure == "metric_unavailable"


@pytest.mark.parametrize(
    ("question", "expected"),
    [("서버 재시작해줘", Route.OUT_OF_SCOPE), ("오늘 수집 결과는?", Route.METRIC)],
)
def test_rule_route(question: str, expected: Route) -> None:
    assert rule_route(question) is expected
