from __future__ import annotations

import logging
import re
from collections.abc import Awaitable, Callable, Sequence
from datetime import date as date_type
from datetime import timedelta
from typing import cast
from zoneinfo import ZoneInfo

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage

from membershipflow_ai.agent.contracts import AssistantAnswer, Citation, Route
from membershipflow_ai.agent.jev_router import JevRouter
from membershipflow_ai.agent.tools import MetricToolUnavailable, SpringMetricsClient
from membershipflow_ai.config.settings import Settings
from membershipflow_ai.domain.documents import SearchHit

logger = logging.getLogger(__name__)

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


_CITATION_PATTERN = re.compile(r"\[(\d{1,2})\]")
_REFUSAL_MARKERS = ("답할 수 없습니다", "답변할 수 없습니다", "근거를 찾지 못")


def cited_indexes(answer_text: str) -> set[int]:
    return {int(match) for match in _CITATION_PATTERN.findall(answer_text)}


def verify_citations(answer_text: str, citation_count: int) -> tuple[bool, str | None]:
    """Decide whether an answer may claim to be grounded.

    The model is asked to cite evidence by number, but nothing stops it from
    citing an index that was never provided, or from refusing while the caller
    still reports success. Both cases previously surfaced as grounded=True,
    which overstates how much the answer is backed by retrieved evidence.
    """
    referenced = cited_indexes(answer_text)
    unknown = {index for index in referenced if index < 1 or index > citation_count}
    if unknown:
        return False, f"unknown_citation:{sorted(unknown)}"
    if any(marker in answer_text for marker in _REFUSAL_MARKERS):
        return False, "insufficient_evidence"
    if not referenced:
        return False, "no_citation"
    return True, None


SERVICE_ZONE = ZoneInfo("Asia/Seoul")
_SUBSCRIPTION_HINTS = ("구독자", "구독 수", "가입자", "결제 건수", "구독")
_COLLECT_HINTS = ("수집", "collect", "배치")
_RELATIVE_DAYS = {"오늘": 0, "어제": 1, "그제": 2, "그저께": 2}


def resolve_target_date(question: str, today: date_type | None = None) -> str | None:
    """Resolve an explicit or relative date to YYYY-MM-DD in service time.

    An empty date silently became "whatever the server defaults to", so a
    question about yesterday could be answered with today's numbers.
    """
    explicit = _DATE_PATTERN.search(question)
    if explicit:
        return explicit.group(0)
    base = today or date_type.today()
    for word, delta in _RELATIVE_DAYS.items():
        if word in question:
            return (base - timedelta(days=delta)).isoformat()
    return None


def metric_kind(question: str) -> str | None:
    """Pick which read-only query answers the question, or None when unclear."""
    wants_subscription = any(hint in question for hint in _SUBSCRIPTION_HINTS)
    wants_collect = any(hint in question for hint in _COLLECT_HINTS)
    if wants_subscription and not wants_collect:
        return "subscription"
    if wants_collect and not wants_subscription:
        return "collect"
    return None


def rule_route(question: str) -> Route | None:
    """Deterministic pre-routing. Keeps obvious cases off the LLM path."""
    if any(hint in question for hint in _OUT_OF_SCOPE_HINTS):
        return Route.OUT_OF_SCOPE
    if any(hint in question for hint in _METRIC_HINTS):
        return Route.METRIC
    return None


async def classify(
    llm: BaseChatModel | None,
    question: str,
    *,
    jev: JevRouter | None = None,
    jev_before_rules: bool = False,
) -> Route:
    """질문을 네 경로 중 하나로 분류한다.

    `jev` 가 None 이면(기본값) 기존 경로 그대로다. Jev 가 판단을 내지 못하면
    (타임아웃·형식 오류·낮은 confidence) 어느 순서에서든 기존 경로로 떨어진다.
    `jev_before_rules` 는 rule_route 키워드 규칙이 설명 질문을 가로채는 문제를
    피하려는 선택지이며, 우리 질문 분포에서 검증된 설정이 아니다.
    """
    if jev is not None and jev_before_rules:
        decision = await jev.classify(question)
        if decision.route is not None:
            return decision.route
    ruled = rule_route(question)
    if ruled is not None:
        return ruled
    if jev is not None and not jev_before_rules:
        decision = await jev.classify(question)
        if decision.route is not None:
            return decision.route
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
    try:
        response = await llm.ainvoke(
            [
                SystemMessage(content=ANSWER_SYSTEM),
                HumanMessage(content=f"질문: {question}\n\n근거:\n{format_evidence(hits)}"),
            ]
        )
    except Exception:
        # 검색은 이미 성공했다. 생성 한 단계가 실패했다고 찾아 둔 근거까지 버리면
        # 사용자는 아무것도 못 받는다. 근거만이라도 돌려주고 실패를 기록한다.
        # 예외 문자열에는 모델명·엔드포인트·요청 본문이 섞일 수 있어 싣지 않는다.
        logger.exception("answer generation failed; returning evidence only")
        return AssistantAnswer(
            question=question,
            route=Route.KNOWLEDGE,
            answer=(
                "답변 생성에 실패했습니다. 아래 근거를 직접 확인해 주세요. "
                "잠시 후 다시 시도하면 될 수 있습니다."
            ),
            citations=citations,
            hits=hits,
            grounded=False,
            failure="llm_unavailable",
        )
    answer_text = message_text(response.content)
    grounded, failure = verify_citations(answer_text, len(citations))
    return AssistantAnswer(
        question=question,
        route=Route.KNOWLEDGE,
        answer=answer_text,
        citations=citations,
        hits=hits,
        grounded=grounded,
        failure=failure,
    )


async def answer_metric(
    llm: BaseChatModel | None, question: str, metrics: SpringMetricsClient
) -> AssistantAnswer:
    kind = metric_kind(question)
    if kind is None:
        return AssistantAnswer(
            question=question,
            route=Route.CLARIFY,
            answer="수집 실행과 구독 지표 중 어느 쪽을 말씀하시는지 알려 주세요.",
            grounded=False,
            failure="ambiguous_metric_target",
        )
    target_date = resolve_target_date(question)
    if target_date is None:
        return AssistantAnswer(
            question=question,
            route=Route.CLARIFY,
            answer="어느 날짜 기준인지 알려 주세요. 예: 오늘, 어제, 2026-09-10",
            grounded=False,
            failure="missing_date",
        )
    try:
        payload = (
            await metrics.subscription_metrics(target_date)
            if kind == "subscription"
            else await metrics.collect_runs(target_date)
        )
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
        answer=f"{target_date} {kind} 조회 결과: {payload}",
        grounded=True,
    )


async def run_agent(
    question: str,
    *,
    llm: BaseChatModel | None,
    retrieve: Callable[[str], Awaitable[tuple[list[SearchHit], str]]],
    metrics: SpringMetricsClient,
    jev: JevRouter | None = None,
    jev_before_rules: bool = False,
) -> AssistantAnswer:
    route = await classify(llm, question, jev=jev, jev_before_rules=jev_before_rules)
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


def build_jev_router(settings: Settings, api_key: str) -> tuple[JevRouter | None, bool]:
    """설정과 환경변수로 Jev 라우터를 만든다. 꺼져 있거나 키가 없으면 None.

    두 번째 값은 rule_route 보다 먼저 물어볼지 여부다. 키를 저장소나 Settings
    에 두지 않으려고 호출부에서 환경변수로 받아 넘긴다.
    """
    if settings.jev_routing_mode == "off" or not api_key:
        return None, False
    router = JevRouter(
        api_key=api_key,
        model=settings.jev_model,
        min_confidence=settings.jev_min_confidence,
    )
    return router, settings.jev_routing_mode == "before_rules"


LLM_MAX_RETRIES = 3
LLM_TIMEOUT_SECONDS = 30.0


def build_llm(
    api_key: str, model: str, fallback_models: Sequence[str] = ()
) -> BaseChatModel | None:
    """Gemini 클라이언트. 일시적 오류는 재시도하고, 그래도 안 되면 모델을 바꾼다.

    503(과부하)과 429(한도)는 잠시 뒤 성공하는 경우가 많아 클라이언트가 먼저
    재시도한다. 그런데 과부하는 모델별로 다르게 온다. 2026-09-29 실측에서
    3.8·3.7·3.5 가 동시에 503 인데 3.6 과 2.5 는 정상이었다. 같은 모델만 계속
    두드리면 질문이 통째로 막히므로 다음 모델로 넘어간다.

    무한정 붙잡지 않도록 타임아웃을 건다. 폴백까지 다 쓰면 호출부가 근거만
    돌려준다.
    """
    if not api_key:
        return None
    from langchain_google_genai import ChatGoogleGenerativeAI

    def client_for(name: str) -> ChatGoogleGenerativeAI:
        return ChatGoogleGenerativeAI(
            model=name,
            google_api_key=api_key,
            temperature=0.0,
            max_retries=LLM_MAX_RETRIES,
            timeout=LLM_TIMEOUT_SECONDS,
        )

    primary = client_for(model)
    # 기본 모델과 같은 이름이 폴백에 또 있으면 같은 곳을 두 번 두드릴 뿐이다.
    alternates = [client_for(name) for name in fallback_models if name and name != model]
    if not alternates:
        return cast(BaseChatModel, primary)
    return cast(BaseChatModel, primary.with_fallbacks(alternates))
