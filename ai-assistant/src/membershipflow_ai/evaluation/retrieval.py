from __future__ import annotations

import json
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from membershipflow_ai.domain.documents import SearchHit


@dataclass(frozen=True)
class ExpectedSource:
    source_uri: str
    anchor: str


@dataclass(frozen=True)
class RetrievalCase:
    id: str
    question: str
    split: str
    difficulty: str
    expected_sources: tuple[ExpectedSource, ...]
    reviewed: bool
    notes: str = ""


@dataclass
class CaseResult:
    case: RetrievalCase
    matched_ranks: dict[str, int | None] = field(default_factory=dict)

    @property
    def recall(self) -> float:
        if not self.matched_ranks:
            return 0.0
        found = sum(1 for rank in self.matched_ranks.values() if rank is not None)
        return found / len(self.matched_ranks)

    @property
    def reciprocal_rank(self) -> float:
        ranks = [rank for rank in self.matched_ranks.values() if rank is not None]
        return 1.0 / min(ranks) if ranks else 0.0


def load_cases(path: Path) -> list[RetrievalCase]:
    cases: list[RetrievalCase] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        raw = json.loads(line)
        cases.append(
            RetrievalCase(
                id=raw["id"],
                question=raw["question"],
                split=raw["split"],
                difficulty=raw["difficulty"],
                expected_sources=tuple(
                    ExpectedSource(item["source_uri"], item["anchor"])
                    for item in raw["expected_sources"]
                ),
                reviewed=bool(raw.get("reviewed", False)),
                notes=raw.get("notes", ""),
            )
        )
    return cases


def anchor_matches(anchor: str, chunk_path: Sequence[str]) -> bool:
    """An anchor names a trailing part of the chunk's symbol/section path.

    `Subscription > isStaleExternalEvent` must not match a different class that
    happens to define a method with the same name, so the segments are compared
    as a contiguous suffix rather than a loose subset.
    """
    wanted = [part.strip() for part in anchor.split(">") if part.strip()]
    if not wanted or len(wanted) > len(chunk_path):
        return False
    return list(chunk_path[-len(wanted) :]) == wanted


def score_case(case: RetrievalCase, hits: Sequence[SearchHit]) -> CaseResult:
    result = CaseResult(case=case)
    for expected in case.expected_sources:
        key = f"{expected.source_uri}#{expected.anchor}"
        result.matched_ranks[key] = None
        for hit in hits:
            if hit.chunk.source_uri != expected.source_uri:
                continue
            if anchor_matches(expected.anchor, hit.chunk.path):
                result.matched_ranks[key] = hit.rank
                break
    return result


def aggregate(results: Sequence[CaseResult]) -> dict[str, Any]:
    if not results:
        return {"cases": 0}
    return {
        "cases": len(results),
        "recall_at_k": round(sum(r.recall for r in results) / len(results), 4),
        "mrr": round(sum(r.reciprocal_rank for r in results) / len(results), 4),
        "found_cases": sum(1 for r in results if r.reciprocal_rank > 0),
    }


def group_by(results: Sequence[CaseResult], key: str) -> dict[str, dict[str, Any]]:
    groups: dict[str, list[CaseResult]] = {}
    for result in results:
        groups.setdefault(getattr(result.case, key), []).append(result)
    return {name: aggregate(items) for name, items in sorted(groups.items())}


async def run_cases(
    cases: Sequence[RetrievalCase],
    retrieve: Callable[[str], Awaitable[list[SearchHit]]],
) -> list[CaseResult]:
    return [score_case(case, await retrieve(case.question)) for case in cases]
