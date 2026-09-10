from __future__ import annotations

from collections.abc import Sequence

from membershipflow_ai.domain.documents import SearchHit

DEFAULT_RRF_K = 60


def reciprocal_rank_fusion(
    hit_lists: Sequence[Sequence[SearchHit]],
    *,
    k: int = DEFAULT_RRF_K,
    limit: int | None = None,
) -> list[SearchHit]:
    """Combine ranked lists by reciprocal rank, deduplicating on chunk_id.

    Score depends only on rank, so retrievers with different score scales
    (BM25 term weights vs cosine similarity) can be merged without calibration.
    """
    if k <= 0:
        raise ValueError("rrf k must be positive")

    scores: dict[str, float] = {}
    hits: dict[str, SearchHit] = {}
    contributors: dict[str, set[str]] = {}

    for hit_list in hit_lists:
        for hit in hit_list:
            chunk_id = hit.chunk.chunk_id
            scores[chunk_id] = scores.get(chunk_id, 0.0) + 1.0 / (k + hit.rank)
            contributors.setdefault(chunk_id, set()).add(hit.retriever)
            hits.setdefault(chunk_id, hit)

    ordered = sorted(scores.items(), key=lambda item: (-item[1], item[0]))
    if limit is not None:
        ordered = ordered[:limit]

    return [
        SearchHit(
            chunk=hits[chunk_id].chunk,
            score=score,
            rank=index + 1,
            retriever="rrf(" + "+".join(sorted(contributors[chunk_id])) + ")",
        )
        for index, (chunk_id, score) in enumerate(ordered)
    ]
