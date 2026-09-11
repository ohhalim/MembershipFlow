"""평가 실행 계약.

- 후보 깊이는 retriever 마다 달라지면 안 된다. 재정렬 효과와 후보 깊이 효과가
  섞이면 비교가 성립하지 않는다.
- 복수 근거 케이스는 부분 정답과 완전 정답을 구분해야 한다.
- 경로 복원이 불가능한 구형 색인에서는 조용히 낮은 점수를 내지 말고 멈춘다.
"""

from __future__ import annotations

from membershipflow_ai.domain.documents import ActiveChunk, SearchHit, SourceType
from membershipflow_ai.evaluation.retrieval import (
    ExpectedSource,
    RetrievalCase,
    aggregate,
    hit_record,
    score_case,
)


def make_hit(rank: int, name: str, uri: str = "S.java") -> SearchHit:
    return SearchHit(
        chunk=ActiveChunk(
            chunk_id=f"c{rank}", source_uri=uri, source_type=SourceType.JAVA,
            source_hash="h", ordinal=0, path=("pkg", "Subscription", name),
            content="body", line_start=1, line_end=2, embedding=(),
        ),
        score=1.0, rank=rank, retriever="test",
    )


def make_case(*expected: str) -> RetrievalCase:
    return RetrievalCase(
        id="ret-000", question="q", split="tuning", difficulty="semantic_paraphrase",
        expected_sources=tuple(ExpectedSource("S.java", f"Subscription > {e}") for e in expected),
        reviewed=True,
    )


def test_partial_evidence_is_not_all_evidence() -> None:
    case = make_case("isActiveAt", "cancel")
    result = score_case(case, [make_hit(1, "isActiveAt")])
    assert result.recall == 0.5
    assert result.all_evidence_found is False


def test_complete_evidence_sets_the_flag() -> None:
    case = make_case("isActiveAt", "cancel")
    result = score_case(case, [make_hit(1, "isActiveAt"), make_hit(2, "cancel")])
    assert result.recall == 1.0
    assert result.all_evidence_found is True


def test_recall_granularity_is_not_uniform_across_cases() -> None:
    # 근거 1개 케이스는 1/n, 근거 2개 케이스는 0.5/n 만큼 움직인다.
    single = score_case(make_case("isActiveAt"), [make_hit(1, "isActiveAt")])
    partial = score_case(make_case("isActiveAt", "cancel"), [make_hit(1, "isActiveAt")])
    none = score_case(make_case("isActiveAt"), [])
    assert aggregate([single, none])["recall_at_k"] == 0.5
    assert aggregate([partial, none])["recall_at_k"] == 0.25


def test_aggregate_reports_all_evidence_rate() -> None:
    full = score_case(make_case("isActiveAt"), [make_hit(1, "isActiveAt")])
    partial = score_case(make_case("isActiveAt", "cancel"), [make_hit(1, "isActiveAt")])
    summary = aggregate([full, partial])
    assert summary["all_evidence_rate"] == 0.5
    assert summary["all_evidence_cases"] == 1
    assert summary["recall_at_k"] == 0.75


def test_hit_record_keeps_identity_without_document_body() -> None:
    record = hit_record(make_hit(3, "isActiveAt"))
    assert record["chunk_id"] == "c3"
    assert record["path"] == ["pkg", "Subscription", "isActiveAt"]
    assert "content" not in record
    assert "body" not in record


def test_candidates_are_recorded_separately_from_returned() -> None:
    case = make_case("isActiveAt")
    pool = [make_hit(1, "cancel"), make_hit(2, "isActiveAt")]
    result = score_case(case, [make_hit(1, "cancel")], candidates=pool)
    assert [c["chunk_id"] for c in result.candidates] == ["c1", "c2"]
    assert [r["chunk_id"] for r in result.returned] == ["c1"]
    # 정답이 후보에는 있었으나 반환되지 않았음을 사후에 확인할 수 있어야 한다
    assert result.reciprocal_rank == 0.0
