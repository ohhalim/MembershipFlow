"""Check the registered handler without Slack network calls."""
from unittest.mock import AsyncMock

import pytest

from membershipflow_ai.agent.contracts import AssistantAnswer, Route
from membershipflow_ai.config.settings import Settings
from membershipflow_ai.interfaces import slack_app


class FakeApp:
    def __init__(self, **kwargs):
        self.handler = None

    def event(self, name):
        def register(handler):
            self.handler = handler
            return handler
        return register


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ('teams', 'channels', 'context', 'event_team', 'channel', 'accepted'),
    [
        (['T1'], ['C1'], {'team_id': 'T1'}, None, 'C1', True),
        (['T1'], [], {'team_id': 'T2'}, 'T1', 'C1', False),
        (['T1'], [], {}, 'T1', 'C1', False),
        (['T1'], ['C1'], {'team_id': 'T1'}, None, 'C2', False),
        ([], [], {}, None, 'C1', True),
    ],
)
async def test_mention_authorization(
    monkeypatch, teams, channels, context, event_team, channel, accepted,
):
    monkeypatch.setenv('SLACK_BOT_TOKEN', 'test-only')
    monkeypatch.setenv('SLACK_APP_TOKEN', 'test-only')
    monkeypatch.setattr(slack_app, 'AsyncApp', FakeApp)
    settings = Settings(
        _env_file=None,
        slack_allowed_team_ids=teams,
        slack_allowed_channel_ids=channels,
    )
    answer = AsyncMock(return_value=AssistantAnswer(
        question='질문', route=Route.KNOWLEDGE, answer='응답', grounded=False,
    ))
    say = AsyncMock()
    app, _ = slack_app.build_slack_app(settings, answer)
    event = {'type': 'app_mention', 'text': '<@U123> 질문',
             'channel': channel, 'ts': '1.0', 'thread_ts': '0.5'}
    if event_team is not None:
        event['team'] = event_team
    await app.handler(event=event, context=context, say=say)
    if accepted:
        answer.assert_awaited_once_with('질문')
        assert say.await_count == 1
        assert say.call_args.kwargs['thread_ts'] == '0.5'
    else:
        answer.assert_not_awaited()
        say.assert_not_awaited()
