from __future__ import annotations

import logging
import os
import socket
from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from typing import TYPE_CHECKING, Any
from urllib.parse import urlparse

if TYPE_CHECKING:
    from membershipflow_ai.domain.documents import SearchHit

logger = logging.getLogger(__name__)

DEFAULT_PROJECT = "membershipflow-ai"
_tracer: Any | None = None


COLLECTOR_PROBE_TIMEOUT = 1.0


def collector_reachable(endpoint: str, timeout: float = COLLECTOR_PROBE_TIMEOUT) -> bool:
    """수집기 host:port 에 TCP 로 붙어 보고 바로 끊는다.

    HTTP 요청이 아니라 연결만 본다. 수집기가 어떤 경로를 여는지와 무관하게
    "떠 있는가" 만 판단하면 되고, 기동을 오래 붙잡지 않아야 한다.
    """
    parsed = urlparse(endpoint)
    host = parsed.hostname
    if host is None:
        return False
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def setup_tracing(project_name: str | None = None) -> bool:
    """Send OpenInference traces to a local collector when one is configured.

    Opt-in through PHOENIX_COLLECTOR_ENDPOINT so a normal run never depends on
    a collector being up. Returns whether tracing was enabled.
    """
    global _tracer
    endpoint = os.environ.get("PHOENIX_COLLECTOR_ENDPOINT")
    if not endpoint:
        return False
    try:
        from openinference.instrumentation.langchain import LangChainInstrumentor
        from phoenix.otel import register
    except ModuleNotFoundError:
        logger.warning("tracing requested but phoenix packages are not installed")
        return False

    if not collector_reachable(endpoint):
        # 엔드포인트가 설정돼 있다는 것과 수집기가 떠 있다는 것은 다르다.
        # 확인 없이 켜면 배치 전송이 실패할 때마다 재시도 로그가 쌓여, 정작
        # 봐야 할 애플리케이션 로그가 묻힌다. 관측은 선택이므로 끄고 넘어간다.
        logger.warning("tracing collector unreachable, continuing without tracing: %s", endpoint)
        return False

    provider = register(
        project_name=project_name or os.environ.get("PHOENIX_PROJECT_NAME", DEFAULT_PROJECT),
        endpoint=f"{endpoint.rstrip('/')}/v1/traces",
        auto_instrument=False,
        batch=True,
    )
    LangChainInstrumentor().instrument(tracer_provider=provider, skip_dep_check=True)
    _tracer = provider.get_tracer("membershipflow_ai")
    logger.info("tracing enabled -> %s", endpoint)
    return True


@contextmanager
def retriever_span(name: str, query: str, index: str) -> Iterator[list[Any]]:
    """Record one retrieval as an OpenInference RETRIEVER span.

    Elasticsearch is not a LangChain component, so the instrumentor never sees
    it. Without this the trace shows an LLM call with no visible evidence of
    where its context came from.
    """
    sink: list[Any] = []
    if _tracer is None:
        yield sink
        return
    with _tracer.start_as_current_span(name) as span:
        span.set_attribute("openinference.span.kind", "RETRIEVER")
        span.set_attribute("input.value", query)
        span.set_attribute("retrieval.index", index)
        yield sink
        hits: Sequence[SearchHit] = sink
        span.set_attribute("retrieval.document_count", len(hits))
        for position, hit in enumerate(hits):
            prefix = f"retrieval.documents.{position}.document"
            span.set_attribute(f"{prefix}.id", hit.chunk.chunk_id)
            span.set_attribute(f"{prefix}.score", hit.score)
            span.set_attribute(f"{prefix}.content", hit.chunk.content[:2000])
            span.set_attribute(
                f"{prefix}.metadata",
                f'{{"source":"{hit.chunk.source_uri}",'
                f'"lines":"{hit.chunk.line_start}-{hit.chunk.line_end}",'
                f'"retriever":"{hit.retriever}"}}',
            )
