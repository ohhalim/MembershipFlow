"""Local corpus/label audit only: no embeddings, ES, LLM or retrieval scoring."""

import hashlib
import json
import subprocess
from pathlib import Path

from membershipflow_ai.evaluation.retrieval import anchor_matches, load_cases
from membershipflow_ai.ingestion.chunker import RegexTokenCounter, StructureAwareChunker
from membershipflow_ai.ingestion.parsers import ParserRegistry
from membershipflow_ai.ingestion.scanner import CorpusScanner

ROOT = Path(__file__).resolve().parents[4]
BASE = ROOT / "ai-assistant/evals/retrieval"
OUT = Path(__file__).parent


def main():
    documents = CorpusScanner(ROOT, ROOT / "ai-assistant/config/corpus.yml").scan()
    parser = ParserRegistry()
    chunker = StructureAwareChunker(RegexTokenCounter())
    chunks = [c for d in documents for c in chunker.chunk(d, parser.parse(d))]
    cases = []
    old_ids = set()
    for name in ("cases.draft.jsonl", "cases.holdout-20260930.draft.jsonl"):
        for case in load_cases(BASE / name):
            evidence = []
            for expected in case.expected_sources:
                matches = [
                    c
                    for c in chunks
                    if c.source_uri == expected.source_uri
                    and anchor_matches(expected.anchor, c.path)
                ]
                evidence.append(
                    {
                        "source_uri": expected.source_uri,
                        "anchor": expected.anchor,
                        "match_count": len(matches),
                        "chunks": [
                            {
                                "chunk_id": c.chunk_id,
                                "lines": [c.line_start, c.line_end],
                                "source_hash": c.source_hash,
                            }
                            for c in matches
                        ],
                    }
                )
                if name == "cases.draft.jsonl":
                    old_ids.update(c.chunk_id for c in matches)
            cases.append(
                {
                    "file": name,
                    "id": case.id,
                    "question": case.question,
                    "reviewed": case.reviewed,
                    "notes": case.notes,
                    "evidence": evidence,
                }
            )
    for case in cases:
        if case["file"] != "cases.draft.jsonl":
            case["overlaps_old_expected_chunks"] = any(
                c["chunk_id"] in old_ids for e in case["evidence"] for c in e["chunks"]
            )
    report = {
        "code_sha": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True
        ).strip(),
        "scope": "local current corpus, not active ES index; no retrieval measured",
        "label_sha256": {
            n: hashlib.sha256((BASE / n).read_bytes()).hexdigest()
            for n in ("cases.draft.jsonl", "cases.holdout-20260930.draft.jsonl")
        },
        "sources": [{"source_uri": d.source_uri, "source_hash": d.content_hash} for d in documents],
        "chunk_count": len(chunks),
        "cases": cases,
    }
    with (OUT / "audit.json").open("x") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
        f.write("\n")
    lines = [
        "# 문항별 근거 검토 — 2026-09-30",
        "",
        "AI 검토 자료. 사람 승인 아님. 로컬 corpus 기준이며 실제 ES 일치 여부는 별도 확인.",
        "",
    ]
    for case in cases:
        lines += [f"## {case['id']}: {case['question']}", "", case["notes"], ""]
        for e in case["evidence"]:
            lines.append(f"- `{e['source_uri']}` / `{e['anchor']}`: {e['match_count']}개")
            for c in e["chunks"]:
                lines.append(f"  - 근거 행: {c['lines'][0]}-{c['lines'][1]}")
        lines += ["", "- 사람 판정: 미검토", ""]
    with (OUT / "REVIEW.md").open("x") as f:
        f.write("\n".join(lines))
    bad = [
        (c["id"], e["anchor"], e["match_count"])
        for c in cases
        for e in c["evidence"]
        if e["match_count"] != 1
    ]
    overlaps = [c["id"] for c in cases if c.get("overlaps_old_expected_chunks")]
    print(
        json.dumps(
            {
                "cases": len(cases),
                "chunks": len(chunks),
                "nonunique_anchors": bad,
                "new_old_overlap": overlaps,
            },
            ensure_ascii=False,
        )
    )
    if bad or overlaps:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
