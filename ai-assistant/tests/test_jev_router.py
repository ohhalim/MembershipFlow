"""Jev 분류 어댑터는 확신이 없을 때 기존 경로에 판단을 넘겨야 한다.

외부 분류기를 경로 결정에 끼워 넣으면 실패가 조용해지기 쉽다. 타임아웃이나
형식 오류를 "판단 없음"이 아니라 기본 경로(KNOWLEDGE)로 처리하면, 실행 요청이
지식 질문으로 새어 들어가도 아무 기록이 남지 않는다. 아래 테스트는 실패 종류마다
route=None 과 이유가 남고, 호출부가 기존 rule_route → Gemini 경로로 떨어지는지 본다.

네트워크는 쓰지 않는다. httpx.MockTransport 로 응답을 만든다.
"""

from __future__ import annotations

import json
from typing import Any

import httpx
import pytest
from pydantic import ValidationError

from membershipflow_ai.agent.contracts import Route
from membershipflow_ai.agent.graph import build_jev_router, classify
from membershipflow_ai.agent.jev_router import JevRouter
from membershipflow_ai.config.settings import Settings

# 2026-09-21 live 실측에서 받은 것과 같은 형태의 응답.
OK_BODY: dict[str, Any] = {
    "model": "jev-1.13.0",
    "answers": {
        "route": {
            "type": "choice",
            "choice": "KNOWLEDGE",
            "confidence": 1.0,
            "probabilities": {
                "KNOWLEDGE": 1.0,
                "METRIC": 0.0,
                "CLARIFY": 0.0,
                "OUT_OF_SCOPE": 0.0,
            },
        }
    },
    "usage": {"input_tokens": 476, "output_tokens": 56},
}


def router_for(
    handler: Any, *, api_key: str = "test-key", min_confidence: float = 0.5
) -> JevRouter:
    transport = httpx.MockTransport(handler)
    client = httpx.AsyncClient(transport=transport)
    return JevRouter(api_key=api_key, client=client, min_confidence=min_confidence)


def respond(body: dict[str, Any], status: int = 200) -> Any:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json=body)

    return handler


async def test_choice_becomes_route() -> None:
    router = router_for(respond(OK_BODY))
    decision = await router.classify("환불 처리 방식은 어떻게 구현되어 있어?")
    assert decision.route is Route.KNOWLEDGE
    assert decision.failure is None
    assert decision.confidence == 1.0
    assert decision.model == "jev-1.13.0"
    assert decision.usage == {"input_tokens": 476, "output_tokens": 56}


async def test_request_carries_key_and_choice_question() -> None:
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=OK_BODY)

    await router_for(handler).classify("환불 처리 방식은?")
    payload = json.loads(seen[0].content)
    assert seen[0].headers["Authorization"] == "Bearer test-key"
    assert payload["questions"]["route"]["type"] == "choice"
    assert set(payload["questions"]["route"]["criteria"]) == {
        "KNOWLEDGE",
        "METRIC",
        "CLARIFY",
        "OUT_OF_SCOPE",
    }


async def test_low_confidence_defers_instead_of_guessing() -> None:
    body = json.loads(json.dumps(OK_BODY))
    body["answers"]["route"]["confidence"] = 0.31
    decision = await router_for(respond(body)).classify("그거 확인해줘")
    assert decision.route is None
    assert decision.failure == "low_confidence"
    # 버려진 선택지도 남겨야 임계값을 나중에 실측으로 정할 수 있다.
    assert decision.rejected_choice == "KNOWLEDGE"
    assert decision.confidence == 0.31


async def test_timeout_is_not_a_route() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timeout", request=request)

    decision = await router_for(handler).classify("질문")
    assert decision.route is None
    assert decision.failure == "timeout"


async def test_transport_error_is_not_a_route() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    decision = await router_for(handler).classify("질문")
    assert decision.route is None
    assert decision.failure == "transport_error"


@pytest.mark.parametrize("status", [401, 422, 429, 529])
async def test_http_error_keeps_status_in_failure(status: int) -> None:
    decision = await router_for(respond({"error": "x"}, status)).classify("질문")
    assert decision.route is None
    assert decision.failure == f"http_{status}"


async def test_non_json_body_is_not_a_route() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, text="<html>gateway</html>")

    decision = await router_for(handler).classify("질문")
    assert decision.route is None
    assert decision.failure == "invalid_json"


@pytest.mark.parametrize(
    "answer",
    [
        {"type": "noul", "noul": 0.9},
        {"type": "choice", "confidence": 1.0},
        {"type": "choice", "choice": "KNOWLEDGE"},
        {"type": "choice", "choice": "KNOWLEDGE", "confidence": "high"},
    ],
)
async def test_malformed_answer_is_not_a_route(answer: dict[str, Any]) -> None:
    decision = await router_for(respond({"answers": {"route": answer}})).classify("질문")
    assert decision.route is None
    assert decision.failure == "malformed_answer"


async def test_unknown_choice_is_not_coerced() -> None:
    body = json.loads(json.dumps(OK_BODY))
    body["answers"]["route"]["choice"] = "REFUND"
    decision = await router_for(respond(body)).classify("질문")
    assert decision.route is None
    assert decision.failure == "unknown_choice"
    assert decision.rejected_choice == "REFUND"


async def test_missing_key_sends_no_request() -> None:
    def handler(request: httpx.Request) -> httpx.Response:  # pragma: no cover - 호출되면 실패
        raise AssertionError("키 없이 외부 호출을 보냈다")

    decision = await router_for(handler, api_key="").classify("질문")
    assert decision.route is None
    assert decision.failure == "missing_api_key"


# --- classify 통합: 순서와 폴백 -------------------------------------------------


class StubLLM:
    """Gemini 대신 쓰는 최소 스텁. 호출 여부를 세어 폴백을 확인한다."""

    def __init__(self, text: str = "KNOWLEDGE") -> None:
        self.calls = 0
        self._text = text

    async def ainvoke(self, messages: object) -> Any:
        self.calls += 1
        return type("R", (), {"content": self._text})()


async def test_default_path_is_unchanged_without_router() -> None:
    llm = StubLLM()
    assert await classify(llm, "환불 처리 방식은 어떻게 구현되어 있어?") is Route.OUT_OF_SCOPE  # type: ignore[arg-type]
    # rule_route 가 잡았으므로 LLM 까지 가지 않는다. 기존 동작 그대로다.
    assert llm.calls == 0


async def test_after_rules_leaves_the_rule_misroute_in_place() -> None:
    """기본 순서에서는 Jev 를 켜도 이 질문을 고치지 못한다.

    "환불"이 _OUT_OF_SCOPE_HINTS 에 있어 rule_route 가 먼저 가로챈다. 이 테스트는
    수정이 아니라 현재 한계를 고정해 두는 것이다.
    """
    router = router_for(respond(OK_BODY))
    route = await classify(None, "환불 처리 방식은 어떻게 구현되어 있어?", jev=router)
    assert route is Route.OUT_OF_SCOPE


async def test_before_rules_lets_jev_answer_first() -> None:
    router = router_for(respond(OK_BODY))
    route = await classify(
        None, "환불 처리 방식은 어떻게 구현되어 있어?", jev=router, jev_before_rules=True
    )
    assert route is Route.KNOWLEDGE


async def test_jev_failure_falls_back_to_existing_path() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timeout", request=request)

    llm = StubLLM("METRIC")
    router = router_for(handler)
    route = await classify(llm, "웹훅 중복 수신을 어떻게 처리해?", jev=router)  # type: ignore[arg-type]
    assert route is Route.METRIC
    assert llm.calls == 1


async def test_jev_failure_before_rules_still_uses_rules() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(529, json={"error": "overloaded"})

    router = router_for(handler)
    route = await classify(None, "서버 재시작해", jev=router, jev_before_rules=True)
    assert route is Route.OUT_OF_SCOPE


# --- 설정 ---------------------------------------------------------------------


def test_router_is_off_by_default() -> None:
    settings = Settings(_env_file=None)  # type: ignore[call-arg]
    assert settings.jev_routing_mode == "off"
    assert build_jev_router(settings, "test-key") == (None, False)


def test_router_needs_a_key_even_when_enabled() -> None:
    settings = Settings(_env_file=None, jev_routing_mode="after_rules")  # type: ignore[call-arg]
    assert build_jev_router(settings, "") == (None, False)


def test_before_rules_mode_is_reported() -> None:
    settings = Settings(_env_file=None, jev_routing_mode="before_rules")  # type: ignore[call-arg]
    router, before_rules = build_jev_router(settings, "test-key")
    assert isinstance(router, JevRouter)
    assert before_rules is True


def test_unknown_mode_is_rejected() -> None:
    with pytest.raises(ValidationError):
        Settings(_env_file=None, jev_routing_mode="befor_rules")  # type: ignore[call-arg]


# --- 읽기 전용 계약 ------------------------------------------------------------
#
# 분류기를 바꾸면 "어떤 경로로 가느냐"가 바뀐다. 분류가 틀렸을 때 최악이
# 무엇인지가 계약이다. 이 에이전트가 쓸 수 있는 도구는 retrieve(검색 읽기)와
# SpringMetricsClient(GET 두 개)뿐이고, 어느 경로도 쓰기 도구를 부르지 않는다.
# 아래 테스트는 Jev 가 실행 요청을 KNOWLEDGE 로 잘못 분류해도 그 성질이
# 유지되는지 고정한다.


class ToolSpy:
    def __init__(self) -> None:
        self.retrieved: list[str] = []
        self.metric_calls: list[tuple[str, str]] = []

    async def retrieve(self, query: str) -> tuple[list[Any], str]:
        self.retrieved.append(query)
        return [], "mf-ai-chunks-000001"

    async def collect_runs(self, date_value: str) -> dict[str, object]:
        self.metric_calls.append(("collect", date_value))
        return {"runs": 0}

    async def subscription_metrics(self, date_value: str) -> dict[str, object]:
        self.metric_calls.append(("subscription", date_value))
        return {"active": 0}


async def test_out_of_scope_request_touches_no_tool() -> None:
    from membershipflow_ai.agent.graph import run_agent

    spy = ToolSpy()
    result = await run_agent(
        "서버 재시작해",
        llm=None,
        retrieve=spy.retrieve,
        metrics=spy,  # type: ignore[arg-type]
    )
    assert result.route is Route.OUT_OF_SCOPE
    assert result.grounded is False
    assert spy.retrieved == []
    assert spy.metric_calls == []


async def test_jev_misroute_reaches_only_read_paths() -> None:
    """Jev 가 실행 요청을 KNOWLEDGE 로 잘못 분류한 최악의 경우.

    막아야 할 것은 "잘못된 분류"가 아니라 "잘못된 분류가 쓰기로 이어지는 것"이다.
    이 경우 검색만 일어나고 답변은 근거 없음으로 끝나야 한다.
    """
    from membershipflow_ai.agent.graph import run_agent

    spy = ToolSpy()
    router = router_for(respond(OK_BODY))  # 항상 KNOWLEDGE 를 돌려준다
    result = await run_agent(
        "데이터베이스 기록을 전부 지워줘",
        llm=None,
        retrieve=spy.retrieve,
        metrics=spy,  # type: ignore[arg-type]
        jev=router,
        jev_before_rules=True,
    )
    assert result.route is Route.KNOWLEDGE
    assert spy.retrieved == ["데이터베이스 기록을 전부 지워줘"]
    assert spy.metric_calls == []
    assert result.grounded is False


async def test_jev_failure_does_not_widen_the_scope() -> None:
    """Jev 가 죽어도 실행 요청은 기존 rule_route 가 계속 막는다."""
    from membershipflow_ai.agent.graph import run_agent

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("timeout", request=request)

    spy = ToolSpy()
    result = await run_agent(
        "서버 재시작해",
        llm=None,
        retrieve=spy.retrieve,
        metrics=spy,  # type: ignore[arg-type]
        jev=router_for(handler),
        jev_before_rules=True,
    )
    assert result.route is Route.OUT_OF_SCOPE
    assert spy.retrieved == []
    assert spy.metric_calls == []
