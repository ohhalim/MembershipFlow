"""Runtime input contract; approval remains a separate evaluation gate."""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from jsonschema import Draft202012Validator

from membershipflow_ai.evaluation.retrieval import load_cases

VALID: dict[str, Any] = {
    "id": "ret-001", "question": "실제 질문", "split": "tuning",
    "difficulty": "lexical", "reviewed": True,
    "expected_sources": [{"source_uri": "S.java", "anchor": "S > hit"}],
}


@pytest.mark.parametrize("change", [
    {"split": "train"}, {"difficulty": "easy"}, {"id": "bad-id"},
    {"question": "q"}, {"question": 123}, {"expected_sources": []},
    {"expected_sources": [{"source_uri": "S.java"}]},
    {"expected_sources": [{"source_uri": "", "anchor": "hit"}]},
    {"expected_sources": [{"source_uri": "S.java", "anchor": ""}]},
    {"expected_sources": [{"source_uri": "S.java", "anchor": "hit", "extra": 1}]},
    {"extra": True}, {"notes": None},
])
def test_invalid_structure_agrees_with_published_schema(
    tmp_path: Path, change: dict[str, Any]
) -> None:
    case = {**VALID, **change}
    schema_path = Path(__file__).parents[1] / "evals/schemas/retrieval-case.schema.json"
    validator = Draft202012Validator(json.loads(schema_path.read_text("utf-8")))
    assert not validator.is_valid(case)
    path = tmp_path / "cases.jsonl"
    path.write_text(json.dumps(case), "utf-8")
    with pytest.raises(ValueError, match="invalid retrieval case"):
        load_cases(path)


@pytest.mark.parametrize("key", list(VALID))
def test_missing_required_field_is_rejected(tmp_path: Path, key: str) -> None:
    case = {k: v for k, v in VALID.items() if k != key}
    path = tmp_path / "cases.jsonl"
    path.write_text(json.dumps(case), "utf-8")
    with pytest.raises(ValueError, match="invalid retrieval case"):
        load_cases(path)


def test_duplicate_id_across_splits_is_rejected(tmp_path: Path) -> None:
    path = tmp_path / "cases.jsonl"
    path.write_text(
        json.dumps(VALID) + "\n\n" + json.dumps({**VALID, "split": "held_out"}), "utf-8"
    )
    with pytest.raises(ValueError, match=r":3: duplicate case id: ret-001"):
        load_cases(path)


@pytest.mark.parametrize("text", ["{", "null", "[]"])
def test_malformed_or_non_object_input_has_line_number(tmp_path: Path, text: str) -> None:
    path = tmp_path / "cases.jsonl"
    path.write_text("\n" + text, "utf-8")
    with pytest.raises(ValueError, match=":2: invalid retrieval case"):
        load_cases(path)


def test_valid_approved_case_agrees_with_schema(tmp_path: Path) -> None:
    schema_path = Path(__file__).parents[1] / "evals/schemas/retrieval-case.schema.json"
    Draft202012Validator(json.loads(schema_path.read_text("utf-8"))).validate(VALID)
    path = tmp_path / "cases.jsonl"
    path.write_text(json.dumps(VALID), "utf-8")
    case, = load_cases(path)
    assert case.reviewed is True
    assert case.expected_sources[0].anchor == "S > hit"
