from __future__ import annotations

from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_", env_file=".env", extra="ignore")

    database_url: str = (
        "postgresql+asyncpg://membershipflow_ai:local-ai-password@localhost:5433/membershipflow_ai"
    )
    repository_root: Path = Path("..")
    corpus_config: Path = Path("config/corpus.yml")
    bm25_index_dir: Path = Path(".data/bm25")
    embedding_provider: str = "fake"
    embedding_model: str = "BAAI/bge-m3"
    embedding_revision: str | None = None
    reranker_model: str = "BAAI/bge-reranker-v2-m3"
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
    slack_allowed_team_ids: list[str] = Field(default_factory=list)
    slack_allowed_channel_ids: list[str] = Field(default_factory=list)


@lru_cache
def get_settings() -> Settings:
    return Settings()
