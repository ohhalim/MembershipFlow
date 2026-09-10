from __future__ import annotations

import hashlib
from pathlib import Path

import yaml
from pydantic import BaseModel, ConfigDict, Field

from membershipflow_ai.domain.documents import SourceDocument, SourceSpec, SourceType


class CorpusSourceConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    path: str
    type: SourceType


class CorpusLimitsConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    max_file_bytes: int = Field(gt=0, le=10 * 1024 * 1024)
    encoding: str = "utf-8"


class CorpusConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: int = Field(ge=1)
    sources: list[CorpusSourceConfig] = Field(min_length=1)
    limits: CorpusLimitsConfig


class CorpusScanner:
    def __init__(self, repository_root: Path, config_file: Path) -> None:
        self._repository_root = repository_root.resolve()
        self._config_file = config_file.resolve()
        self._config = self._load_config()

    @property
    def specs(self) -> tuple[SourceSpec, ...]:
        return tuple(
            SourceSpec(source_uri=source.path, source_type=source.type)
            for source in self._config.sources
        )

    @property
    def corpus_version(self) -> int:
        return self._config.version

    def scan(self) -> list[SourceDocument]:
        seen: set[str] = set()
        documents: list[SourceDocument] = []
        for spec in self.specs:
            if spec.source_uri in seen:
                raise ValueError(f"duplicate corpus source: {spec.source_uri}")
            seen.add(spec.source_uri)
            documents.append(self._read(spec))
        return documents

    def _load_config(self) -> CorpusConfig:
        raw = yaml.safe_load(self._config_file.read_text(encoding="utf-8"))
        return CorpusConfig.model_validate(raw)

    def _read(self, spec: SourceSpec) -> SourceDocument:
        source_file = (self._repository_root / spec.source_uri).resolve()
        try:
            source_file.relative_to(self._repository_root)
        except ValueError as exc:
            raise ValueError(f"corpus source escapes repository root: {spec.source_uri}") from exc
        if not source_file.is_file():
            raise FileNotFoundError(f"corpus source not found: {spec.source_uri}")
        size = source_file.stat().st_size
        if size > self._config.limits.max_file_bytes:
            raise ValueError(
                f"corpus source exceeds max_file_bytes: {spec.source_uri} ({size} bytes)"
            )
        content = source_file.read_text(encoding=self._config.limits.encoding)
        content_hash = hashlib.sha256(content.encode(self._config.limits.encoding)).hexdigest()
        return SourceDocument(
            source_uri=spec.source_uri,
            source_type=spec.source_type,
            content=content,
            content_hash=content_hash,
        )
