"""TypeSafe Jev 질문 분류 어댑터. 기본값은 비활성이며 설정으로만 켠다.

기존 경로(rule_route → Gemini)를 지우지 않는다. Jev 가 쓸 수 있는 답을 주지
못하면 항상 None 을 돌려 기존 경로로 떨어진다. 분류기이므로 어떤 실행 권한도
갖지 않는다. 라우팅 결과는 읽기 전용 답변 경로를 고르는 데만 쓰인다.
"""

from __future__ import annotations

import math
import time
from dataclasses import dataclass

import httpx

from membershipflow_ai.agent.contracts import Route

JEV_API_URL = "https://api.typesafe.ai/v1/systemone"

ROUTER_INSTRUCTIONS = (
    "MembershipFlow 읽기 전용 어시스턴트의 질문을 분류하세요. "
    "설명 요청과 실행 요청을 구분하세요."
)
# 실험(evals/experiments/jev-routing)에서 쓴 것과 같은 문구를 유지한다.
# 문구가 갈라지면 실험 기록과 제품 동작을 비교할 수 없다.
ROUTE_CRITERIA: dict[str, str] = {
    "KNOWLEDGE": (
        "코드나 문서로 답할 수 있는 사양, 판정 기준, 처리 방식 질문. "
        "현재 실측값이나 변경 실행은 불필요."
    ),
    "METRIC": "현재 또는 특정 날짜의 건수와 상태 등 실제 시스템 데이터 조회가 필요한 질문.",
    "CLARIFY": "대상이나 요청 내용이 불분명해서 추가 질문이 필요.",
    "OUT_OF_SCOPE": "읽기 전용 범위 밖. 실제 삭제, 환불, 변경, 배포 등의 실행 요청.",
}


def parse_confidence(value: object) -> float | None:
    """0..1 유한 실수만 confidence 로 인정한다. 아니면 None.

    `isinstance(value, int | float)` 만 보면 셋이 새어 들어온다.
    bool 은 int 의 서브클래스라 True 가 1.0 으로 통과하고, NaN 은 어떤 비교도
    False 라 임계값 검사를 그냥 지나가며, 1 을 넘는 값은 임계값을 무의미하게
    만든다. 확률 분포에서 나온 값이라는 계약을 여기서 지킨다.
    """
    if isinstance(value, bool) or not isinstance(value, int | float):
        return None
    number = float(value)
    if not math.isfinite(number) or not 0.0 <= number <= 1.0:
        return None
    return number


def require_threshold(value: float) -> float:
    """임계값도 같은 규칙으로 막는다. 설정을 거치지 않고 직접 만들 수 있다.

    NaN 임계값은 모든 비교가 False 라 어떤 confidence 도 통과시킨다.
    조용히 "검사 없음" 이 되는 것이 가장 나쁘다.
    """
    checked = parse_confidence(value)
    if checked is None:
        raise ValueError(f"min_confidence must be a finite number in [0, 1], got {value!r}")
    return checked


@dataclass(frozen=True)
class JevDecision:
    """한 번의 분류 시도 기록.

    `route` 가 None 이면 이 시도로는 경로를 정할 수 없다는 뜻이고, 호출부는
    기존 경로로 떨어져야 한다. 이유는 `failure` 에 남는다.
    """

    route: Route | None
    failure: str | None = None
    confidence: float | None = None
    rejected_choice: str | None = None
    model: str | None = None
    usage: dict[str, int] | None = None
    elapsed_seconds: float = 0.0


def build_request(question: str, model: str) -> dict[str, object]:
    return {
        "model": model,
        "state": question,
        "questions": {
            "route": {
                "type": "choice",
                "instructions": ROUTER_INSTRUCTIONS,
                "criteria": ROUTE_CRITERIA,
            }
        },
    }


class JevRouter:
    """Jev choice 프리미티브로 질문을 네 경로 중 하나로 분류한다.

    호출 실패, 형식 오류, 낮은 confidence 는 모두 "판단 없음"으로 취급한다.
    분류기가 애매한 입력에 억지로 답하면 잘못된 경로로 조용히 넘어가므로,
    확신이 없을 때는 기존 경로에 판단을 넘긴다.
    """

    def __init__(
        self,
        *,
        api_key: str,
        url: str = JEV_API_URL,
        model: str = "jev-latest",
        timeout_seconds: float = 5.0,
        min_confidence: float = 0.5,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._api_key = api_key
        self._url = url
        self._model = model
        self._timeout = timeout_seconds
        self._min_confidence = require_threshold(min_confidence)
        self._client = client

    async def _post(self, payload: dict[str, object]) -> httpx.Response:
        if self._client is not None:
            return await self._client.post(
                self._url,
                json=payload,
                headers={"Authorization": f"Bearer {self._api_key}"},
                timeout=self._timeout,
            )
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            return await client.post(
                self._url,
                json=payload,
                headers={"Authorization": f"Bearer {self._api_key}"},
            )

    async def classify(self, question: str) -> JevDecision:
        if not self._api_key:
            return JevDecision(route=None, failure="missing_api_key")
        start = time.perf_counter()
        try:
            response = await self._post(build_request(question, self._model))
        except httpx.TimeoutException:
            return JevDecision(
                route=None, failure="timeout", elapsed_seconds=time.perf_counter() - start
            )
        except httpx.HTTPError:
            # 예외 문자열에 Authorization 헤더가 실릴 수 있어 메시지는 싣지 않는다.
            return JevDecision(
                route=None, failure="transport_error", elapsed_seconds=time.perf_counter() - start
            )
        elapsed = time.perf_counter() - start
        if response.status_code >= 400:
            return JevDecision(
                route=None, failure=f"http_{response.status_code}", elapsed_seconds=elapsed
            )
        try:
            body = response.json()
        except ValueError:
            return JevDecision(route=None, failure="invalid_json", elapsed_seconds=elapsed)
        return self._read_answer(body, elapsed)

    def _read_answer(self, body: object, elapsed: float) -> JevDecision:
        if not isinstance(body, dict):
            return JevDecision(route=None, failure="malformed_answer", elapsed_seconds=elapsed)
        raw_model = body.get("model")
        raw_usage = body.get("usage")
        model = raw_model if isinstance(raw_model, str) else None
        usage = self._read_usage(raw_usage)

        def rejected(
            failure: str, choice: str | None = None, confidence: float | None = None
        ) -> JevDecision:
            return JevDecision(
                route=None,
                failure=failure,
                confidence=confidence,
                rejected_choice=choice,
                model=model,
                usage=usage,
                elapsed_seconds=elapsed,
            )

        answers = body.get("answers")
        answer = answers.get("route") if isinstance(answers, dict) else None
        if not isinstance(answer, dict) or answer.get("type") != "choice":
            return rejected("malformed_answer")
        choice = answer.get("choice")
        confidence = parse_confidence(answer.get("confidence"))
        if not isinstance(choice, str) or confidence is None:
            return rejected("malformed_answer")
        if choice not in ROUTE_CRITERIA:
            return rejected("unknown_choice", choice, confidence)
        if confidence < self._min_confidence:
            return rejected("low_confidence", choice, confidence)
        return JevDecision(
            route=Route(choice),
            confidence=confidence,
            model=model,
            usage=usage,
            elapsed_seconds=elapsed,
        )

    @staticmethod
    def _read_usage(raw: object) -> dict[str, int] | None:
        if not isinstance(raw, dict):
            return None
        return {key: value for key, value in raw.items() if isinstance(value, int)}
