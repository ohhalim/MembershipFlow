"""같은 질문 12건을 기존 경로와 Jev 경로에 나란히 태워 기록한다.

`run.py` 는 Jev 단독 호출만 기록했다. 그 결과만으로는 "Jev 가 12/12 맞았다"는
말밖에 못 한다. 기존 rule_route → Gemini 가 같은 질문에서 무엇을 내놓는지 같은
파일에 남겨야 비교라고 부를 수 있다.

네 갈래를 돈다.
  rule              rule_route 만. 네트워크 없음
  current           rule_route → Gemini. 현재 제품 동작
  jev_after_rules   rule_route → Jev. jev_routing_mode=after_rules 와 같음
  jev_before_rules  Jev → rule_route. jev_routing_mode=before_rules 와 같음

기본 source 는 replay 다. 2026-09-21 live 기록의 응답을 그대로 되돌려 주므로
네트워크도 키도 필요 없지만, 그때 받은 12건을 다시 트는 것일 뿐 새로운 증거가
아니다. Gemini 는 재생할 기록이 없어 replay 에서 current 갈래는 건너뛴다.

## 제품 경로와 무엇이 같고 무엇이 다른가

같은 것: 갈래별 경로 결정은 제품과 같은 `classify()` 를 그대로 부른다.
rule_route 순서, Jev 실패·저confidence 폴백 모두 제품 코드가 판단한다.

다른 것 둘. 수치를 읽을 때 반드시 같이 봐야 한다.

1. replay 에는 Gemini 가 없다(`llm=None`). Jev 가 기권했을 때 제품은 Gemini 로
   떨어지지만 replay 는 `classify` 의 기본값 KNOWLEDGE 로 떨어진다. 2026-09-21
   기록 12건 중 1건("그거 왜 실패했어?", confidence 0.42)이 여기 걸린다.
   그래서 replay 의 일치 수는 Gemini 폴백이 없는 조건의 값이고, 제품 조건에서
   그 1건이 어떻게 갈지는 측정되지 않았다.

2. 여기서 비교하는 것은 분류 단계의 route 다. 제품의 최종 route 는 그 뒤에
   한 번 더 바뀔 수 있다. 예로 "현재 구독자 수는?" 는 분류상 METRIC 이지만
   `answer_metric` 이 날짜를 못 정해(`resolve_target_date` 가 "현재" 를
   모른다) CLARIFY 로 끝난다. 이 표의 일치율은 최종 답변 정확도가 아니다.

기존 결과 파일은 읽기만 한다. 출력은 배타 생성이라 덮어쓰지 않는다.
"""

from __future__ import annotations

import argparse
import asyncio
import importlib.util
import json
import os
import time
from collections import Counter
from pathlib import Path
from typing import Any

import httpx

from membershipflow_ai.agent.graph import build_llm, classify, rule_route
from membershipflow_ai.agent.jev_router import JevDecision, JevRouter

HERE = Path(__file__).parent
DEFAULT_REPLAY = HERE / "live-20260921-01.jsonl"
ARMS = ("rule", "current", "jev_after_rules", "jev_before_rules")


def load_cases() -> list[tuple[str, str]]:
    """`run.py` 의 CASES 를 그대로 쓴다. 문항이 갈라지면 비교가 성립하지 않는다."""
    spec = importlib.util.spec_from_file_location("jev_probe", HERE / "run.py")
    if spec is None or spec.loader is None:  # pragma: no cover - 파일이 있어야 한다
        raise SystemExit("run.py 를 읽지 못했다")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return list(module.CASES)


def load_replay(path: Path) -> dict[str, dict[str, Any]]:
    """질문 문자열 → 그때 받은 응답 본문."""
    table: dict[str, dict[str, Any]] = {}
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            response = row.get("response")
            if response is None:
                continue
            table[row["request"]["state"]] = response
    return table


def replay_router(table: dict[str, dict[str, Any]], min_confidence: float) -> JevRouter:
    def handler(request: httpx.Request) -> httpx.Response:
        question = json.loads(request.content)["state"]
        body = table.get(question)
        if body is None:
            # 기록에 없는 질문을 조용히 성공으로 만들지 않는다.
            return httpx.Response(422, json={"error": "not in replay set"})
        return httpx.Response(200, json=body)

    return JevRouter(
        api_key="replay",
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler)),
        min_confidence=min_confidence,
    )


class RecordingRouter(JevRouter):
    """classify 가 실제로 보낸 한 번의 호출 결과를 붙잡아 둔다.

    기록을 남기려고 따로 한 번 더 부르면 live 에서 호출 수와 비용이 두 배가
    되고, 두 호출의 답이 갈리면 어느 쪽이 경로를 정했는지도 알 수 없다.
    """

    def __init__(self, inner: JevRouter) -> None:
        self._inner = inner
        self.last: JevDecision | None = None

    async def classify(self, question: str) -> JevDecision:
        self.last = await self._inner.classify(question)
        return self.last


async def run_arm(
    arm: str,
    question: str,
    *,
    llm: Any,
    jev: JevRouter | None,
) -> dict[str, Any]:
    record: dict[str, Any] = {"arm": arm}
    start = time.perf_counter()
    if arm == "rule":
        ruled = rule_route(question)
        record["route"] = ruled.value if ruled else None
        record["abstained"] = ruled is None
    elif arm == "current":
        record["route"] = (await classify(llm, question)).value
        record["model"] = getattr(llm, "model", None)
    else:
        assert jev is not None
        recorder = RecordingRouter(jev)
        route = await classify(
            llm, question, jev=recorder, jev_before_rules=arm == "jev_before_rules"
        )
        record["route"] = route.value
        decision = recorder.last
        # rule_route 가 먼저 잡으면 after_rules 에서는 Jev 를 아예 부르지 않는다.
        # 그 사실 자체가 비교 대상이므로 호출 여부를 남긴다.
        record["jev_called"] = decision is not None
        if decision is not None:
            record["jev_choice"] = decision.route.value if decision.route else None
            record["jev_failure"] = decision.failure
            record["jev_confidence"] = decision.confidence
            record["jev_rejected_choice"] = decision.rejected_choice
            record["model"] = decision.model
            record["usage"] = decision.usage
            record["jev_elapsed_seconds"] = decision.elapsed_seconds
    record["elapsed_seconds"] = time.perf_counter() - start
    return record


async def main_async(args: argparse.Namespace) -> None:
    cases = load_cases()
    arms = list(args.arms)

    llm = None
    if args.source == "live":
        llm = build_llm(os.environ.get("GEMINI_API_KEY", ""), args.llm_model)
        if llm is None and "current" in arms:
            raise SystemExit("current 갈래에는 GEMINI_API_KEY 가 필요하다. 요청을 보내지 않았다")
        key = os.environ.get("TYPESAFE_API_KEY", "")
        if not key and any(arm.startswith("jev") for arm in arms):
            raise SystemExit("jev 갈래에는 TYPESAFE_API_KEY 가 필요하다. 요청을 보내지 않았다")
        jev: JevRouter | None = JevRouter(
            api_key=key, model=args.model, min_confidence=args.min_confidence
        )
    else:
        if "current" in arms:
            print("replay: Gemini 기록이 없어 current 갈래를 건너뛴다")
            arms = [arm for arm in arms if arm != "current"]
        jev = replay_router(load_replay(args.replay), args.min_confidence)

    rows: list[dict[str, Any]] = []
    with args.output.open("x", encoding="utf-8") as out:
        header = {
            "kind": "run_header",
            "source": args.source,
            "arms": arms,
            # 파일명만 남긴다. 절대 경로를 적으면 실행한 사람의 홈 경로가
            # 저장소에 들어가고 다른 머신에서 재현도 안 된다.
            "replay_file": args.replay.name if args.source == "replay" else None,
            "jev_model_requested": args.model if args.source == "live" else None,
            "llm_model": args.llm_model if args.source == "live" else None,
            "llm_available": llm is not None,
            "scope": "classification_route_only",
            "min_confidence": args.min_confidence,
            "labels_reviewed": False,
            "note": (
                "draft_expected 는 미검토 초안 라벨이다. 일치율은 일반 정확도가 아니다. "
                "replay 는 2026-09-21 기록 재생이며 새 측정이 아니다. "
                "llm_available=false 면 Jev 기권 시 Gemini 가 아니라 classify 기본값 "
                "KNOWLEDGE 로 떨어진다. scope 는 분류 단계 route 이며 제품 최종 route 는 "
                "answer_metric 등에서 더 바뀔 수 있다."
            ),
        }
        out.write(json.dumps(header, ensure_ascii=False) + "\n")
        for index, (question, expected) in enumerate(cases, 1):
            for arm in arms:
                record = await run_arm(arm, question, llm=llm, jev=jev)
                record |= {
                    "id": index,
                    "question": question,
                    "draft_expected": expected,
                    "reviewed": False,
                    "source": args.source,
                    "matches_draft": record["route"] == expected,
                }
                out.write(json.dumps(record, ensure_ascii=False) + "\n")
                out.flush()
                rows.append(record)
    summarize(rows, arms, len(cases))
    print(f"\n기록: {args.output}")


def summarize(rows: list[dict[str, Any]], arms: list[str], total: int) -> None:
    print(f"\n{'arm':18} {'draft 일치':>10} {'기권':>6} {'평균초':>8}  실패")
    for arm in arms:
        subset = [row for row in rows if row["arm"] == arm]
        matched = sum(1 for row in subset if row["matches_draft"])
        abstained = sum(1 for row in subset if row.get("abstained"))
        mean = sum(row["elapsed_seconds"] for row in subset) / max(len(subset), 1)
        failures = Counter(row["jev_failure"] for row in subset if row.get("jev_failure"))
        print(
            f"{arm:18} {matched:>6}/{total:<3} {abstained:>6} {mean:>8.3f}  "
            f"{dict(failures) or '-'}"
        )
    print("\n갈래별로 판정이 갈린 질문:")
    for index in sorted({row["id"] for row in rows}):
        group = [row for row in rows if row["id"] == index]
        routes = {row["arm"]: row["route"] for row in group}
        if len(set(routes.values())) == 1:
            continue
        question = group[0]["question"]
        print(f"  [{index}] {question}  draft={group[0]['draft_expected']}")
        for arm, route in routes.items():
            print(f"        {arm:18} → {route}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source", choices=["replay", "live"], default="replay")
    parser.add_argument("--replay", type=Path, default=DEFAULT_REPLAY)
    parser.add_argument("--arms", nargs="+", choices=ARMS, default=list(ARMS))
    parser.add_argument("--model", default="jev-latest")
    parser.add_argument("--llm-model", default="gemini-3.7-flash")
    parser.add_argument("--min-confidence", type=float, default=0.5)
    args = parser.parse_args()
    asyncio.run(main_async(args))


if __name__ == "__main__":
    main()
