"""Opt-in Jev routing probe; default is offline request preparation."""

import argparse
import json
import os
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from membershipflow_ai.agent.graph import rule_route

CRITERIA = {
    "KNOWLEDGE": (
        "코드나 문서로 답할 수 있는 사양, 판정 기준, 처리 방식 질문. "
        "현재 실측값이나 변경 실행은 불필요."
    ),
    "METRIC": "현재 또는 특정 날짜의 건수와 상태 등 실제 시스템 데이터 조회가 필요한 질문.",
    "CLARIFY": "대상이나 요청 내용이 불분명해서 추가 질문이 필요.",
    "OUT_OF_SCOPE": "읽기 전용 범위 밖. 실제 삭제, 환불, 변경, 배포 등의 실행 요청.",
}
# Synthetic drafts, separate from retrieval labels. No private corpus is sent.
CASES = [
    ("이용 가능한 구독의 판정 기준은?", "KNOWLEDGE"),
    ("웹훅 중복 수신을 어떻게 처리해?", "KNOWLEDGE"),
    ("환불 처리 방식은 어떻게 구현되어 있어?", "KNOWLEDGE"),
    ("현재 코드에서 구독 만료를 어떻게 판단해?", "KNOWLEDGE"),
    ("오늘 수집 성공 건수는?", "METRIC"),
    ("현재 구독자 수는?", "METRIC"),
    ("2026-09-20 수집 실패 건수 알려줘", "METRIC"),
    ("그거 왜 실패했어?", "CLARIFY"),
    ("그것 좀 확인해줘", "CLARIFY"),
    ("서버 재시작해", "OUT_OF_SCOPE"),
    ("구독을 해지해줘", "OUT_OF_SCOPE"),
    ("데이터베이스 기록을 전부 지워줘", "OUT_OF_SCOPE"),
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--live", action="store_true", help="Send 12 synthetic questions to TypeSafe"
    )
    parser.add_argument("--model", default="jev-latest")
    args = parser.parse_args()
    key = os.environ.get("TYPESAFE_API_KEY")
    if args.live and not key:
        parser.error("TYPESAFE_API_KEY is required; no request sent")
    # Exclusive creation prevents overwriting previous evidence.
    with args.output.open("x", encoding="utf-8") as out:
        for index, (question, expected) in enumerate(CASES, 1):
            request = {
                "model": args.model,
                "state": question,
                "questions": {
                    "route": {
                        "type": "choice",
                        "instructions": (
                            "MembershipFlow 읽기 전용 어시스턴트의 질문을 분류하세요. "
                            "설명 요청과 실행 요청을 구분하세요."
                        ),
                        "criteria": CRITERIA,
                    }
                },
            }
            ruled = rule_route(question)
            row = {
                "id": index,
                "draft_expected": expected,
                "reviewed": False,
                "rule_route": ruled.value if ruled else None,
                "request": request,
                "mode": "live" if args.live else "offline",
            }
            if args.live:
                start = time.perf_counter()
                try:
                    req = Request(
                        "https://api.typesafe.ai/v1/systemone",
                        data=json.dumps(request).encode(),
                        headers={
                            "Authorization": f"Bearer {key}",
                            "Content-Type": "application/json",
                        },
                    )
                    with urlopen(req, timeout=30) as response:
                        body = json.load(response)
                    row["response"] = body  # includes resolved model, probabilities and usage
                    answer = body["answers"]["route"]
                    if answer["type"] != "choice" or answer["choice"] not in CRITERIA:
                        raise ValueError("unexpected route response")
                    row["matches_draft"] = answer["choice"] == expected
                except (HTTPError, URLError, ValueError, KeyError, TypeError, TimeoutError) as exc:
                    row["error_type"] = type(exc).__name__
                    if isinstance(exc, HTTPError):
                        row["http_status"] = exc.code
                    row["elapsed_seconds"] = time.perf_counter() - start
                    out.write(json.dumps(row, ensure_ascii=False) + "\n")
                    raise SystemExit(
                        "Probe stopped; see output. Error body and credentials omitted."
                    ) from None
                row["elapsed_seconds"] = time.perf_counter() - start
            out.write(json.dumps(row, ensure_ascii=False) + "\n")
            out.flush()
    print(f"{len(CASES)} records saved ({'live' if args.live else 'offline'})")


if __name__ == "__main__":
    main()
