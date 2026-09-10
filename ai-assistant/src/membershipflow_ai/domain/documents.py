from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class SourceType(StrEnum):
    MARKDOWN = "markdown"
    JAVA = "java"


@dataclass(frozen=True)
class SourceSpec:
    source_uri: str
    source_type: SourceType


@dataclass(frozen=True)
class SourceDocument:
    source_uri: str
    source_type: SourceType
    content: str
    content_hash: str


@dataclass(frozen=True)
class ParsedSection:
    path: tuple[str, ...]
    content: str
    line_start: int
    line_end: int


@dataclass(frozen=True)
class DocumentChunk:
    chunk_id: str
    source_uri: str
    source_type: SourceType
    source_hash: str
    ordinal: int
    path: tuple[str, ...]
    content: str
    line_start: int
    line_end: int


@dataclass(frozen=True)
class EmbeddedChunk:
    chunk: DocumentChunk
    embedding: tuple[float, ...]


@dataclass(frozen=True)
class ActiveChunk:
    chunk_id: str
    source_uri: str
    source_type: SourceType
    source_hash: str
    ordinal: int
    path: tuple[str, ...]
    content: str
    line_start: int
    line_end: int
    embedding: tuple[float, ...]


@dataclass(frozen=True)
class SearchHit:
    chunk: ActiveChunk
    score: float
    rank: int
    retriever: str
