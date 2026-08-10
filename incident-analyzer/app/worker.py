import asyncio
import os
import socket
import sys
from uuid import uuid4

from google.genai import errors
from pydantic import ValidationError

from app.collectors.loki import LokiClient
from app.config import Settings, get_settings
from app.llm import GeminiClient, LlmClient, insufficient_evidence_analysis
from app.persistence.database import SessionFactory
from app.persistence.repositories import AnalysisJobRepository


def build_worker_id() -> str:
    return f"{socket.gethostname()}-{os.getpid()}-{uuid4().hex[:8]}"[:128]


async def run_once(
    settings: Settings,
    worker_id: str | None = None,
    loki_client: LokiClient | None = None,
    llm_client: LlmClient | None = None,
) -> bool:
    repository = AnalysisJobRepository()
    owner = worker_id or build_worker_id()
    with SessionFactory() as session:
        claimed = repository.claim_next(session, owner, settings.job_lease_seconds)
    if claimed is None:
        return False

    try:
        collector = loki_client or LokiClient(
            settings.loki_base_url,
            settings.loki_query_timeout_seconds,
            settings.loki_query_limit,
        )
        evidence = await collector.collect(claimed.started_at)
        useful = any(
            item.status == "OK" and item.count > 0 for item in evidence.log_evidence
        )
        if useful:
            if llm_client is None and (
                settings.gemini_api_key is None or settings.llm_model is None
            ):
                raise ValueError("Gemini configuration is missing")
            analyzer = llm_client or GeminiClient(
                settings.gemini_api_key.get_secret_value(),
                settings.llm_model,
                settings.llm_timeout_seconds,
                settings.llm_max_output_tokens,
            )
            analysis = await analyzer.analyze(evidence)
        else:
            analysis = insufficient_evidence_analysis(evidence)
        with SessionFactory() as session:
            repository.complete(session, claimed, owner, evidence, analysis)
        return True
    except (ValidationError, ValueError):
        with SessionFactory() as session:
            repository.fail(
                session,
                claimed,
                owner,
                "INVALID_ANALYSIS",
                False,
                settings.job_max_attempts,
            )
        return True
    except (TimeoutError, errors.APIError, OSError):
        with SessionFactory() as session:
            repository.fail(
                session,
                claimed,
                owner,
                "DEPENDENCY_UNAVAILABLE",
                True,
                settings.job_max_attempts,
            )
        return True


async def run_forever() -> None:
    settings = get_settings()
    worker_id = build_worker_id()
    while True:
        processed = await run_once(settings, worker_id)
        if not processed:
            await asyncio.sleep(2)


def main() -> int:
    if len(sys.argv) > 1 and sys.argv[1] == "once":
        asyncio.run(run_once(get_settings()))
        return 0
    asyncio.run(run_forever())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
