"""평가 채점은 정답 anchor 를 느슨하게 맞히면 안 된다.

이름이 같은 다른 클래스의 메서드나, 상위 섹션만 겹치는 청크를 정답으로
세면 점수가 실제보다 높게 나온다.
"""

from __future__ import annotations

from membershipflow_ai.domain.documents import ActiveChunk, SearchHit, SourceType
from membershipflow_ai.evaluation.retrieval import (
    ExpectedSource,
    RetrievalCase,
    aggregate,
    anchor_matches,
    score_case,
)


def make_hit(rank: int, uri: str, path: tuple[str, ...]) -> SearchHit:
    return SearchHit(
        chunk=ActiveChunk(
            chunk_id=f"c{rank}", source_uri=uri, source_type=SourceType.JAVA,
            source_hash="h", ordinal=0, path=path, content="",
            line_start=1, line_end=2, embedding=(),
        ),
        score=1.0, rank=rank, retriever="test",
    )


def make_case(*expected: tuple[str, str]) -> RetrievalCase:
    return RetrievalCase(
        id="ret-000", question="q", split="tuning", difficulty="lexical",
        expected_sources=tuple(ExpectedSource(u, a) for u, a in expected), reviewed=True,
    )


def test_anchor_matches_trailing_segments() -> None:
    assert anchor_matches("Subscription > isActiveAt", ("pkg", "Subscription", "isActiveAt"))


def test_anchor_does_not_match_same_name_in_other_class() -> None:
    assert not anchor_matches("Subscription > cancel", ("pkg", "Order", "cancel"))


def test_anchor_must_be_contiguous_suffix() -> None:
    # 상위 섹션만 겹치는 경우는 정답이 아니다
    assert not anchor_matches("Subscription", ("pkg", "Subscription", "isActiveAt"))


def test_anchor_longer_than_path_is_not_a_match() -> None:
    assert not anchor_matches("A > B > C", ("B", "C"))


def test_first_matching_rank_is_recorded() -> None:
    case = make_case(("S.java", "Subscription > isActiveAt"))
    hits = [
        make_hit(1, "S.java", ("pkg", "Subscription", "cancel")),
        make_hit(2, "S.java", ("pkg", "Subscription", "isActiveAt")),
    ]
    result = score_case(case, hits)
    assert result.recall == 1.0
    assert result.reciprocal_rank == 0.5


def test_missing_expected_source_lowers_recall() -> None:
    case = make_case(
        ("S.java", "Subscription > isActiveAt"),
        ("S.java", "Subscription > cancel"),
    )
    hits = [make_hit(1, "S.java", ("pkg", "Subscription", "isActiveAt"))]
    result = score_case(case, hits)
    assert result.recall == 0.5
    assert result.reciprocal_rank == 1.0


def test_no_match_scores_zero() -> None:
    case = make_case(("S.java", "Subscription > isActiveAt"))
    result = score_case(case, [make_hit(1, "Other.java", ("pkg", "Other", "isActiveAt"))])
    assert result.recall == 0.0
    assert result.reciprocal_rank == 0.0


def test_aggregate_reports_found_cases() -> None:
    case = make_case(("S.java", "Subscription > isActiveAt"))
    found = score_case(case, [make_hit(2, "S.java", ("pkg", "Subscription", "isActiveAt"))])
    missed = score_case(case, [])
    summary = aggregate([found, missed])
    assert summary == {
        "cases": 2,
        "recall_at_k": 0.5,
        "all_evidence_rate": 0.5,
        "mrr": 0.25,
        "found_cases": 1,
        "all_evidence_cases": 1,
    }
