"""설정이 조용히 무시되거나, 한 단계 실패가 전부를 버리지 않아야 한다.

2026-09-29 로컬에서 실제로 띄워 보고 찾은 것들이다. 코드만 읽을 때는 보이지
않았다. 세 부류다.

1. `.env` 에 넣은 값을 앱이 못 읽음 — 키가 있는데 "없다" 고 답함
2. 가드가 빈 값이면 조용히 열림 — 막힌 줄 알았는데 안 막혀 있음
3. 한 단계 실패가 이미 성공한 작업까지 버림 — 검색 결과가 있는데 버려짐
"""

from __future__ import annotations

import socket
from typing import Any

import pytest

from membershipflow_ai.agent.contracts import Route
from membershipflow_ai.agent.graph import answer_from_evidence
from membershipflow_ai.config.settings import Settings
from membershipflow_ai.domain.documents import DocumentChunk, SearchHit, SourceType
from membershipflow_ai.observability import collector_reachable

# --- 1. .env 의 값을 실제로 읽는가 -------------------------------------------


@pytest.mark.parametrize(
    ("env_name", "field"),
    [
        ("GEMINI_API_KEY", "gemini_api_key"),
        ("SLACK_BOT_TOKEN", "slack_bot_token"),
        ("SLACK_APP_TOKEN", "slack_app_token"),
        ("TYPESAFE_API_KEY", "typesafe_api_key"),
    ],
)
def test_secret_is_read_without_ai_prefix(
    monkeypatch: pytest.MonkeyPatch, env_name: str, field: str
) -> None:
    """접두사 없는 이름으로도 읽혀야 한다.

    이 넷은 `os.environ` 으로 직접 읽던 값이라 `.env` 에 채워 넣어도 무시됐다.
    Settings 를 거치지 않으니 env_file 이 적용되지 않았다.
    """
    monkeypatch.setenv(env_name, "value-from-env")
    settings = Settings(_env_file=None)  # type: ignore[call-arg]
    assert getattr(settings, field) == "value-from-env"


def test_ai_prefixed_name_also_works(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_GEMINI_API_KEY", "prefixed")
    settings = Settings(_env_file=None)  # type: ignore[call-arg]
    assert settings.gemini_api_key == "prefixed"


def test_missing_secret_is_empty_not_error() -> None:
    """키가 없는 것은 오류가 아니다. LLM 없이도 근거 반환 경로가 있어야 한다."""
    settings = Settings(_env_file=None)  # type: ignore[call-arg]
    assert settings.gemini_api_key == ""


# --- 2. 수집기가 안 떠 있으면 트레이싱을 켜지 않는다 --------------------------


def test_unreachable_collector_is_detected() -> None:
    """닫힌 포트를 열려 있다고 보고하면 배치 전송 재시도 로그가 쌓인다."""
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        closed_port = probe.getsockname()[1]
    assert collector_reachable(f"http://127.0.0.1:{closed_port}") is False


def test_reachable_collector_is_detected() -> None:
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        port = server.getsockname()[1]
        assert collector_reachable(f"http://127.0.0.1:{port}") is True


def test_malformed_endpoint_is_not_reachable() -> None:
    assert collector_reachable("not-a-url") is False


# --- 3. 생성이 실패해도 찾아 둔 근거는 남긴다 ---------------------------------


def make_hit(index: int) -> SearchHit:
    chunk = DocumentChunk(
        chunk_id=f"c{index}",
        source_uri=f"src/Example{index}.java",
        source_type=SourceType.JAVA,
        source_hash="h",
        ordinal=index,
        path=("com.example", "Example", "method"),
        content="public void method() {}",
        line_start=index,
        line_end=index + 5,
    )
    return SearchHit(chunk=chunk, score=1.0, rank=index, retriever="hybrid")


class FailingLLM:
    """503 처럼 호출 자체가 실패하는 모델."""

    def __init__(self, exc: Exception) -> None:
        self._exc = exc

    async def ainvoke(self, messages: object) -> Any:
        raise self._exc


async def test_generation_failure_keeps_the_evidence() -> None:
    """검색은 성공했는데 생성 한 단계 때문에 전부 버리면 사용자는 빈손이 된다.

    2026-09-29 실측: Gemini 가 503 을 돌려주자 근거 5건을 찾아 놓고도 예외가
    그대로 터져 CLI 가 죽었다.
    """
    hits = [make_hit(i) for i in range(1, 4)]
    result = await answer_from_evidence(
        FailingLLM(RuntimeError("503 UNAVAILABLE")),  # type: ignore[arg-type]
        "구독 판정 기준은?",
        hits,
        "mf-ai-chunks-000001",
    )
    assert result.failure == "llm_unavailable"
    assert result.grounded is False
    # 핵심: 근거가 살아 있어야 한다
    assert len(result.citations) == 3
    assert result.hits == hits
    assert result.route is Route.KNOWLEDGE


async def test_generation_failure_does_not_leak_the_exception() -> None:
    """예외 문자열에 엔드포인트·모델명·요청 본문이 섞일 수 있다."""
    secret = "https://internal.example/v1/models/secret-model?key=abcd1234"
    result = await answer_from_evidence(
        FailingLLM(RuntimeError(secret)),  # type: ignore[arg-type]
        "질문",
        [make_hit(1)],
        "mf-ai-chunks-000001",
    )
    assert secret not in result.answer
    assert "abcd1234" not in result.answer


async def test_no_hits_is_still_reported_separately() -> None:
    """근거가 없는 것과 생성이 실패한 것은 다른 상태다."""
    result = await answer_from_evidence(None, "질문", [], "mf-ai-chunks-000001")
    assert result.citations == []
    assert result.failure != "llm_unavailable"


# --- 4. 모델 하나가 막혀도 답이 나가야 한다 -----------------------------------
#
# 과부하는 모델별로 다르게 온다. 2026-09-29 실측에서 gemini-3.8/3.7/3.5 가
# 동시에 503 인데 3.6 과 2.5 는 정상이었다. 기본 모델만 계속 두드리면 질문이
# 통째로 막힌다.


def test_fallback_models_are_chained() -> None:
    from membershipflow_ai.agent.graph import build_llm

    chained = build_llm("test-key", "primary", ["alt-1", "alt-2"])
    # with_fallbacks 는 Runnable 을 돌려준다. 단일 모델과 타입이 다르다.
    assert type(chained).__name__ == "RunnableWithFallbacks"
    assert len(chained.fallbacks) == 2


def test_no_fallback_leaves_a_plain_client() -> None:
    """폴백이 없으면 굳이 감싸지 않는다."""
    from membershipflow_ai.agent.graph import build_llm

    plain = build_llm("test-key", "primary", [])
    assert type(plain).__name__ == "ChatGoogleGenerativeAI"


def test_duplicate_of_primary_is_dropped() -> None:
    """같은 모델을 두 번 두드리는 것은 폴백이 아니다."""
    from membershipflow_ai.agent.graph import build_llm

    only_same = build_llm("test-key", "primary", ["primary"])
    assert type(only_same).__name__ == "ChatGoogleGenerativeAI"

    mixed = build_llm("test-key", "primary", ["primary", "alt", ""])
    assert len(mixed.fallbacks) == 1


def test_no_key_still_returns_none() -> None:
    from membershipflow_ai.agent.graph import build_llm

    assert build_llm("", "primary", ["alt"]) is None


def test_fallback_list_accepts_comma_separated_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """`.env` 에 사람이 자연스럽게 적는 표기를 받아야 한다."""
    monkeypatch.setenv("AI_LLM_FALLBACK_MODELS", "a-model, b-model")
    settings = Settings(_env_file=None)  # type: ignore[call-arg]
    assert settings.llm_fallback_models == ["a-model", "b-model"]


# --- 5. 로그 레벨 ------------------------------------------------------------
#
# 2026-09-29 진단에서 필요했던 것. 레벨을 못 바꾸면 "핸들러까지 안 온 것" 과
# "아예 안 받은 것" 을 구분할 수 없어 원인을 엉뚱한 데서 찾게 된다.


def test_log_level_defaults_to_info() -> None:
    """기본은 INFO. DEBUG 는 페이로드가 통째로 찍혀 운영에 두면 안 된다."""
    settings = Settings(_env_file=None)  # type: ignore[call-arg]
    assert settings.log_level == "INFO"


@pytest.mark.parametrize("level", ["debug", "Debug", "DEBUG"])
def test_log_level_is_normalized(monkeypatch: pytest.MonkeyPatch, level: str) -> None:
    monkeypatch.setenv("AI_LOG_LEVEL", level)
    settings = Settings(_env_file=None)  # type: ignore[call-arg]
    assert settings.log_level == "DEBUG"


def test_unknown_log_level_is_rejected() -> None:
    """오타가 조용히 기본값으로 넘어가면 진단하려던 로그가 안 나온다."""
    from pydantic import ValidationError as VE

    with pytest.raises(VE):
        Settings(_env_file=None, log_level="VERBOSE")  # type: ignore[call-arg]
