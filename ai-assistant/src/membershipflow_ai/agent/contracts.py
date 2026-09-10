from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum

from membershipflow_ai.domain.documents import SearchHit


class Route(StrEnum):
    KNOWLEDGE = "KNOWLEDGE"
    METRIC = "METRIC"
    CLARIFY = "CLARIFY"
    OUT_OF_SCOPE = "OUT_OF_SCOPE"


@dataclass(frozen=True)
class Citation:
    source_path: str
    line_start: int
    line_end: int
    chunk_id: str
    physical_index: str


@dataclass
class AssistantAnswer:
    question: str
    route: Route
    answer: str
    citations: list[Citation] = field(default_factory=list)
    hits: list[SearchHit] = field(default_factory=list)
    grounded: bool = False
    failure: str | None = None
