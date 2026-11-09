from __future__ import annotations

import httpx

from membershipflow_ai.config.settings import Settings


class MetricToolUnavailable(RuntimeError):
    """Raised when the Spring read-only query API is not reachable or not implemented.

    The V1 design forbids silent fallback: an unavailable metric source must surface
    as an explicit failure rather than an answer generated without data.
    """


class SpringMetricsClient:
    """Read-only operational queries against the Spring service."""

    def __init__(self, settings: Settings) -> None:
        self._base_url = settings.spring_base_url.rstrip("/")
        self._token = settings.service_token
        self._timeout = 5.0

    async def _get(self, path: str, params: dict[str, str] | None = None) -> dict[str, object]:
        headers = {"Authorization": f"Bearer {self._token}"} if self._token else {}
        url = f"{self._base_url}{path}"
        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                response = await client.get(url, params=params, headers=headers)
        except httpx.HTTPError as exc:
            raise MetricToolUnavailable(f"{url} 연결 실패: {exc}") from exc
        if response.status_code == 404:
            raise MetricToolUnavailable(f"{url} 미구현 (404). 조회 API 추가가 필요하다")
        if response.status_code >= 400:
            raise MetricToolUnavailable(f"{url} 실패 status={response.status_code}")
        payload = response.json()
        if not isinstance(payload, dict):
            raise MetricToolUnavailable(f"{url} 응답 형식이 object 가 아니다")
        return payload

    async def collect_runs(self, date: str) -> dict[str, object]:
        return await self._get("/admin/collect/runs", {"date": date})

    async def subscription_metrics(self, date: str) -> dict[str, object]:
        return await self._get("/admin/subscriptions/metrics", {"date": date})
