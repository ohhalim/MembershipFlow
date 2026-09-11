"""grounded 는 "근거로 뒷받침되는 답변"만 참이어야 한다.

이전 구현은 LLM 응답과 검색 결과가 있기만 하면 참으로 표시했다.
답변이 근거 부족을 선언하거나 없는 인용 번호를 써도 참이었고,
사용자에게 실제보다 강한 신뢰 신호를 준다.
"""

from __future__ import annotations

import pytest

from membershipflow_ai.agent.graph import cited_indexes, verify_citations


def test_valid_citation_is_grounded() -> None:
    grounded, failure = verify_citations("occurredAt 을 비교한다 [2]", citation_count=3)
    assert grounded is True
    assert failure is None


def test_citation_out_of_range_is_not_grounded() -> None:
    grounded, failure = verify_citations("근거에 따르면 [7] 이다", citation_count=3)
    assert grounded is False
    assert failure == "unknown_citation:[7]"


def test_zero_index_citation_is_rejected() -> None:
    grounded, failure = verify_citations("설명 [0]", citation_count=3)
    assert grounded is False
    assert failure == "unknown_citation:[0]"


@pytest.mark.parametrize(
    "text",
    [
        "제공된 근거로는 답할 수 없습니다. [1]",
        "관련 근거를 찾지 못했습니다.",
        "이 질문은 답변할 수 없습니다 [2]",
    ],
)
def test_refusal_is_not_grounded(text: str) -> None:
    grounded, failure = verify_citations(text, citation_count=3)
    assert grounded is False
    assert failure == "insufficient_evidence"


def test_answer_without_citation_is_not_grounded() -> None:
    grounded, failure = verify_citations("웹훅은 순서대로 처리된다", citation_count=3)
    assert grounded is False
    assert failure == "no_citation"


def test_no_evidence_at_all_cannot_be_grounded() -> None:
    grounded, failure = verify_citations("설명 [1]", citation_count=0)
    assert grounded is False
    assert failure == "unknown_citation:[1]"


def test_cited_indexes_parses_multiple() -> None:
    assert cited_indexes("사실 [1] 과 [3] 을 참고 [1]") == {1, 3}
