from __future__ import annotations

import re
from collections.abc import Awaitable, Callable
from typing import cast

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage

from membershipflow_ai.agent.contracts import AssistantAnswer, Citation, Route
from membershipflow_ai.agent.tools import MetricToolUnavailable, SpringMetricsClient
from membershipflow_ai.domain.documents import SearchHit

_METRIC_HINTS = ("오늘", "어제", "지금", "현재", "몇 건", "몇 명", "수집 결과", "실패한 수집")
_OUT_OF_SCOPE_HINTS = ("배포해", "결제 취소해", "환불", "삭제해", "재시작")
_DATE_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}")

ROUTER_SYSTEM = """너는 MembershipFlow 내부 어시스턴트의 질문 분류기다.
아래 넷 중 하나만 대문자로 출력한다. 다른 말은 절대 붙이지 않는다.

KNOWLEDGE: 코드나 문서를 찾아보면 답할 수 있는 질문 (판정 기준, 처리 방식, 설계)
METRIC: 지금 시스템의 실제 수치가 있어야 답할 수 있는 질문 (오늘 수집 결과, 현재 구독자 수)
CLARIFY: 대상이 불분명해서 되물어야 하는 질문 ("그거 왜 실패했어")
OUT_OF_SCOPE: 읽기 전용 범위를 벗어난 요청 (배포, 결제 변경, 삭제)"""

ANSWER_SYSTEM = """너는 MembershipFlow 내부 개발·운영 어시스턴트다.

규칙:
- 아래 제공된 근거만 사용한다. 근거에 없는 내용은 추측하지 않는다.
- 근거가 답하기에 부족하면 "제공된 근거로는 답할 수 없습니다"라고 말하고,
  무엇을 더 확인해야 하는지 알려준다.
- 답변에서 사실을 말할 때마다 [1] [2] 처럼 근거 번호를 붙인다.
- 한국어로 간결하게 답한다."""


def message_text(content: object) -> str:
    """Flatten LangChain message content to plain text.

    Newer chat models return a list of typed content blocks; only the text
    blocks belong in an answer. Signatures and tool payloads are dropped.
    """
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts = []
        for block in content:
            if isinstance(block, str):
                parts.append(block)
            elif isinstance(block, dict) and block.get("type") == "text":
                parts.append(str(block.get("text", "")))
        return "\n".join(part for part in parts if part).strip()
    return str(content).strip()


def rule_route(question: str) -> Route | None:
    """Deterministic pre-routing. Keeps obvious cases off the LLM path."""
    if any(hint in question for hint in _OUT_OF_SCOPE_HINTS):
        return Route.OUT_OF_SCOPE
    if any(hint in question for hint in _METRIC_HINTS):
        return Route.METRIC
    return None


async def classify(llm: BaseChatModel | None, question: str) -> Route:
    ruled = rule_route(question)
    if ruled is not None:
        return ruled
    if llm is None:
        return Route.KNOWLEDGE
    response = await llm.ainvoke(
        [SystemMessage(content=ROUTER_SYSTEM), HumanMessage(content=question)]
    )
    text = message_text(response.content).upper()
    for route in Route:
        if route.value in text:
            return route
    return Route.KNOWLEDGE


def format_evidence(hits: list[SearchHit]) -> str:
    blocks = []
    for index, hit in enumerate(hits, start=1):
        chunk = hit.chunk
        blocks.append(
            f"[{index}] {chunk.source_uri}:{chunk.line_start}-{chunk.line_end}\n{chunk.content}"
        )
    return "\n\n".join(blocks)


async def answer_from_evidence(
    llm: BaseChatModel | None, question: str, hits: list[SearchHit], physical_index: str
) -> AssistantAnswer:
    citations = [
        Citation(
            source_path=hit.chunk.source_uri,
            line_start=hit.chunk.line_start,
            line_end=hit.chunk.line_end,
            chunk_id=hit.chunk.chunk_id,
            physical_index=physical_index,
        )
        for hit in hits
    ]
    if not hits:
        return AssistantAnswer(
            question=question,
            route=Route.KNOWLEDGE,
            answer="관련 근거를 찾지 못했습니다. 질문을 더 구체적으로 적어 주세요.",
            citations=[],
            hits=[],
            grounded=False,
        )
    if llm is None:
        return AssistantAnswer(
            question=question,
            route=Route.KNOWLEDGE,
            answer=(
                "LLM 미설정: GEMINI_API_KEY 가 없어 답변을 생성할 수 없습니다. 근거만 반환합니다."
            ),
            citations=citations,
            hits=hits,
            grounded=False,
            failure="llm_unconfigured",
        )
    response = await llm.ainvoke(
        [
            SystemMessage(content=ANSWER_SYSTEM),
            HumanMessage(content=f"질문: {question}\n\n근거:\n{format_evidence(hits)}"),
        ]
    )
    return AssistantAnswer(
        question=question,
        route=Route.KNOWLEDGE,
        answer=message_text(response.content),
        citations=citations,
        hits=hits,
        grounded=True,
    )


async def answer_metric(
    llm: BaseChatModel | None, question: str, metrics: SpringMetricsClient
) -> AssistantAnswer:
    match = _DATE_PATTERN.search(question)
    date = match.group(0) if match else ""
    try:
        payload = await metrics.collect_runs(date)
    except MetricToolUnavailable as exc:
        return AssistantAnswer(
            question=question,
            route=Route.METRIC,
            answer=f"실시간 조회를 사용할 수 없습니다: {exc}",
            grounded=False,
            failure="metric_unavailable",
        )
    return AssistantAnswer(
        question=question,
        route=Route.METRIC,
        answer=f"수집 조회 결과: {payload}",
        grounded=True,
    )


async def run_agent(
    question: str,
    *,
    llm: BaseChatModel | None,
    retrieve: Callable[[str], Awaitable[tuple[list[SearchHit], str]]],
    metrics: SpringMetricsClient,
) -> AssistantAnswer:
    route = await classify(llm, question)
    if route is Route.OUT_OF_SCOPE:
        return AssistantAnswer(
            question=question,
            route=route,
            answer="읽기 전용 범위를 벗어난 요청이라 수행하지 않습니다.",
            grounded=False,
        )
    if route is Route.CLARIFY:
        return AssistantAnswer(
            question=question,
            route=route,
            answer="대상이 불분명합니다. 어떤 작업·기간·소스를 말씀하시는지 알려 주세요.",
            grounded=False,
        )
    if route is Route.METRIC:
        return await answer_metric(llm, question, metrics)
    hits, physical_index = await retrieve(question)
    result = await answer_from_evidence(llm, question, hits, physical_index)
    result.route = Route.KNOWLEDGE
    return result


def build_llm(api_key: str, model: str) -> BaseChatModel | None:
    if not api_key:
        return None
    from langchain_google_genai import ChatGoogleGenerativeAI

    client = ChatGoogleGenerativeAI(model=model, google_api_key=api_key, temperature=0.0)
    return cast(BaseChatModel, client)
