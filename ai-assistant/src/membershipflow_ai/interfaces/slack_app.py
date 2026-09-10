from __future__ import annotations

import logging
import os
import re
from collections import OrderedDict
from collections.abc import Awaitable, Callable
from typing import Any

from slack_bolt.adapter.socket_mode.async_handler import AsyncSocketModeHandler
from slack_bolt.async_app import AsyncApp

from membershipflow_ai.agent.contracts import AssistantAnswer
from membershipflow_ai.config.settings import Settings

logger = logging.getLogger(__name__)

_MENTION_PATTERN = re.compile(r"<@[UW][A-Z0-9]+>")
_DEDUPE_LIMIT = 512


class EventDeduplicator:
    """Slack redelivers events on ack timeout; answer each question once."""

    def __init__(self, limit: int = _DEDUPE_LIMIT) -> None:
        self._seen: OrderedDict[str, None] = OrderedDict()
        self._limit = limit

    def seen(self, event_key: str) -> bool:
        if event_key in self._seen:
            return True
        self._seen[event_key] = None
        if len(self._seen) > self._limit:
            self._seen.popitem(last=False)
        return False


def strip_mentions(text: str) -> str:
    return _MENTION_PATTERN.sub("", text).strip()


def format_answer(answer: AssistantAnswer) -> str:
    lines = [answer.answer.strip() or "(빈 응답)"]
    if answer.citations:
        lines.append("")
        lines.append("*근거*")
        for number, citation in enumerate(answer.citations, start=1):
            lines.append(
                f"  [{number}] `{citation.source_path}:{citation.line_start}-{citation.line_end}`"
            )
    footer = f"_route: {answer.route} · grounded: {answer.grounded}_"
    if answer.failure:
        footer += f" · _failure: {answer.failure}_"
    lines.extend(["", footer])
    return "\n".join(lines)


def build_slack_app(
    settings: Settings,
    answer_question: Callable[[str], Awaitable[AssistantAnswer]],
) -> tuple[AsyncApp, str]:
    bot_token = os.environ.get("SLACK_BOT_TOKEN", "")
    app_token = os.environ.get("SLACK_APP_TOKEN", "")
    if not bot_token or not app_token:
        raise SystemExit("SLACK_BOT_TOKEN 과 SLACK_APP_TOKEN 이 모두 필요하다")

    app = AsyncApp(token=bot_token)
    deduper = EventDeduplicator()
    allowed_teams = set(settings.slack_allowed_team_ids)
    allowed_channels = set(settings.slack_allowed_channel_ids)

    @app.event("app_mention")
    async def handle_mention(event: dict[str, Any], say: Any) -> None:
        team_id = str(event.get("team") or "")
        channel_id = str(event.get("channel") or "")
        if allowed_teams and team_id not in allowed_teams:
            logger.warning("rejected mention from team %s", team_id)
            return
        if allowed_channels and channel_id not in allowed_channels:
            logger.warning("rejected mention in channel %s", channel_id)
            return

        event_key = str(event.get("client_msg_id") or event.get("ts") or "")
        if event_key and deduper.seen(event_key):
            logger.info("duplicate event %s ignored", event_key)
            return

        question = strip_mentions(str(event.get("text") or ""))
        thread_ts = event.get("thread_ts") or event.get("ts")
        if not question:
            await say(text="질문을 함께 적어 주세요.", thread_ts=thread_ts)
            return

        try:
            answer = await answer_question(question)
        except Exception as exc:
            logger.exception("answer failed")
            await say(text=f"처리 중 오류가 발생했습니다: {exc}", thread_ts=thread_ts)
            return
        await say(text=format_answer(answer), thread_ts=thread_ts)

    return app, app_token


async def run_slack(
    settings: Settings,
    answer_question: Callable[[str], Awaitable[AssistantAnswer]],
) -> None:
    app, app_token = build_slack_app(settings, answer_question)
    handler = AsyncSocketModeHandler(app, app_token)
    logger.info("slack socket mode starting")
    await handler.start_async()  # type: ignore[no-untyped-call]
