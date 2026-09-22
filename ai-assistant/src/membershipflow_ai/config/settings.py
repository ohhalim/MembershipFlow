from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Annotated

from pydantic import AliasChoices, Field, field_validator
from pydantic_settings import BaseSettings, NoDecode, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_", env_file=".env", extra="ignore")

    database_url: str = (
        "postgresql+asyncpg://membershipflow_ai:local-ai-password@localhost:5433/membershipflow_ai"
    )
    repository_root: Path = Path("..")
    corpus_config: Path = Path("config/corpus.yml")
    embedding_provider: str = "fake"
    embedding_model: str = "BAAI/bge-m3"
    embedding_revision: str | None = None
    reranker_model: str = "BAAI/bge-reranker-v2-m3"
    reranker_revision: str | None = None
    llm_model: str = "gemini-3.7-flash"
    elasticsearch_url: str = "http://localhost:9208"
    elasticsearch_username: str = "elastic"
    elasticsearch_password: str = "local-only-dev-password"
    elasticsearch_ca_certs: Path | None = None
    elasticsearch_alias: str = "mf-ai-chunks"
    elasticsearch_request_timeout: float = 10.0
    spring_base_url: str = "http://localhost:8081"
    service_token: str = ""
    trace_content_enabled: bool = False
    # Jev 분류는 기본 꺼짐이다. off 면 rule_route → Gemini 라는 기존 동작 그대로다.
    # after_rules: rule_route 가 먼저 판정하고, 남은 질문만 Jev 가 본다.
    # before_rules: Jev 가 먼저 보고, 판단이 없을 때만 rule_route 로 내려간다.
    #   rule_route 의 키워드 규칙이 설명 질문을 가로채는 사례(예: "환불 처리 방식은
    #   어떻게 구현되어 있어?" → OUT_OF_SCOPE)가 관측됐고, before_rules 는 그 순서를
    #   뒤집는 선택지다. 어느 쪽이 나은지는 아직 실측으로 확인되지 않았다.
    jev_routing_mode: str = "off"
    # 응답의 실제 모델은 jev-1.13.0 처럼 따로 돌아온다. 요청 모델만 설정한다.
    jev_model: str = "jev-latest"
    # 공급사 문서의 예시 임계값(0.5 미만이면 추측하지 말고 되묻기)을 그대로 뒀다.
    # 우리 질문 분포에서 측정한 값이 아니다. 2026-09-21 기록 12건 중 1건이
    # 0.42 로 이 선 아래였다.
    jev_min_confidence: float = 0.5
    # `.env` 는 이 둘을 접두사 없이 적는다. Slack 토큰도 접두사 없이 읽으므로
    # 그 쪽이 자연스럽다. env_prefix 만 믿으면 `.env` 에 채워 넣은 값이 조용히
    # 무시되고, 빈 목록은 "제한 없음" 으로 동작해 가드가 사라진 줄도 모르게 된다.
    slack_allowed_team_ids: Annotated[list[str], NoDecode] = Field(
        default_factory=list,
        validation_alias=AliasChoices("AI_SLACK_ALLOWED_TEAM_IDS", "SLACK_ALLOWED_TEAM_IDS"),
    )
    slack_allowed_channel_ids: Annotated[list[str], NoDecode] = Field(
        default_factory=list,
        validation_alias=AliasChoices("AI_SLACK_ALLOWED_CHANNEL_IDS", "SLACK_ALLOWED_CHANNEL_IDS"),
    )


    @field_validator("jev_routing_mode")
    @classmethod
    def _known_routing_mode(cls, value: str) -> str:
        """오타를 조용히 '꺼짐'으로 넘기지 않는다.

        `AI_JEV_ROUTING_MODE=befor_rules` 같은 오타를 받아주면 켰다고 믿는
        설정이 실제로는 기존 경로만 돌아 차이를 오해하게 된다.
        """
        allowed = {"off", "after_rules", "before_rules"}
        if value not in allowed:
            raise ValueError(f"jev_routing_mode must be one of {sorted(allowed)}, got {value!r}")
        return value

    @field_validator(
        "embedding_revision", "reranker_revision", "elasticsearch_ca_certs", mode="before"
    )
    @classmethod
    def _blank_to_none(cls, value: object) -> object:
        """`.env` 의 `KEY=` 는 None 이 아니라 빈 문자열로 들어온다.

        빈 문자열을 그대로 넘기면 HuggingFace 가 '' 라는 리비전을 찾다가 실패한다.
        """
        if isinstance(value, str) and not value.strip():
            return None
        return value

    @field_validator("slack_allowed_team_ids", "slack_allowed_channel_ids", mode="before")
    @classmethod
    def _split_ids(cls, value: object) -> object:
        """`T01,T02` 처럼 쉼표로 적은 값을 받는다.

        기본 동작은 JSON 배열만 받아들여서 `.env` 에 사람이 자연스럽게 적은
        `KEY=T01,T02` 가 파싱 오류로 죽는다. JSON 배열도 계속 허용한다.
        """
        if value is None:
            return []
        if not isinstance(value, str):
            return value
        text = value.strip()
        if not text:
            return []
        if text.startswith("["):
            return json.loads(text)
        return [part.strip() for part in text.split(",") if part.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
