# RAG 재개 지점 — 2026-09-20

## 이번 마감 범위

- 면접·코딩테스트 준비를 위한 작업 중단. 추가 기능·모델 실험 시작 없음
- alias 전환 검증, 고정 모델 build 및 전환 증거: #376, #379 머지
- 검색 후보·전체 순위·리랭커 실험: #383, #381 머지
- 후속 진단·문서·설정·lint: #385, #387, #389, #391, #393, #395, #397 머지
- Slack 팀 판정 수정: #399에서 event.team 대신 Bolt context.team_id 사용
- Slack 핸들러 5건 포함 pytest 198건, Ruff src/tests, mypy 34 source 통과
- 실제 Slack 사용자 멘션 대신 네트워크 없는 등록 핸들러 테스트 수행

## 현재 결과와 한계

- tuning 10건, reviewed=false. 기존 held_out 5건은 과거 개발에 노출되어 독립 최종 평가로 사용 불가
- vector Recall@5 0.55 → vector+rerank 0.70, MRR 0.525 → 0.4783
- ret-001 정답은 원문 vector 85위. 재작성으로 후보20에 들어와도 시험한 리랭커의 top5에는 미포함
- 후보 생성 누락과 재정렬 손실을 별개로 관측. 단일 원인 확정 없음
- 리랭커·재작성은 실험만 수행, 제품 기본값으로 채택하지 않음
- grounded=true는 인용 번호·거절 표현 검사 결과. 사실 정확성 보증 아님
- Slack 실제 사람 멘션 왕복 및 재부팅 후 자동 실행은 검증 완료로 취급하지 않음

## 다시 시작할 때

1. 최신 origin/develop에서 브랜치 생성 전 git 상태 및 원격 반영 확인
2. 아래 로컬 실행 기록을 읽고 PID·alias·Phoenix 상태 재조회. 오래된 PID로 종료 명령 실행 금지
3. Slack의 실제 사용자 멘션 1건으로 전달·응답 확인
4. evals/retrieval/LABEL-REVIEW.md 검토: 특히 ret-006의 질문 범위와 median 근거 포함 여부 결정
5. 새 독립 평가셋과 완료 기준을 정한 뒤 추가 검색 개선 검토

## 로컬 자료

- 상태·수동 재시작/중지/복귀 명령: ~/.local/state/membershipflow-ai/2026-09-20/CLOSEOUT.md
- 상태 JSON: 같은 디렉터리 status.json (기록 시점 기준, 현재 상태 보증 아님)
- Phoenix: http://127.0.0.1:6006, 프로젝트 membershipflow-ai
- 임시 로그: /tmp/mf-rag-runtime-20260920 (재부팅 후 존재 보장 없음)
- 모델 revision·인덱스 원본: evals/results/2026-09-19-pinned-model-build/manifest.json
- 복귀 후보 B: evals/results/2026-09-19-pinned-alias-roundtrip/manifest-B.json
- 기존 _meta 없는 인덱스는 복귀 대상으로 사용 불가. 임의 메타데이터 추가 금지

## 보존 정책

- 실행 원본 JSON·이전 실패 기록·브랜치 보존
- 재실행은 새 결과 디렉터리 사용. 기존 수치를 새 실험으로 덮어쓰지 않음
- .env·토큰·로컬 PID를 저장소에 커밋하지 않음

---

# 추가 — 2026-09-22 Jev 질문 분류 검토 (이슈 #402)

## 한 것

- `agent/jev_router.py`: TypeSafe Jev choice 분류 어댑터. 타임아웃·HTTP 오류·
  형식 오류·낮은 confidence 를 모두 "판단 없음"으로 처리하고 기존 경로로 폴백
- `classify()` 에 선택적 `jev` 인자. 설정 `AI_JEV_ROUTING_MODE`
  (`off` 기본 / `after_rules` / `before_rules`), `AI_JEV_MODEL`,
  `AI_JEV_MIN_CONFIDENCE`. 키는 `TYPESAFE_API_KEY` 환경변수로만 받는다
- `evals/experiments/jev-routing/compare.py`: 같은 12건을 갈래별로 태우는 비교 실행기
- confidence 를 0..1 유한 실수로 엄격 검증. bool·NaN·범위 밖 값이 통과하면
  임계값 검사가 조용히 무력화된다. 임계값 자체도 Settings 와 생성자 양쪽에서 막는다
- 테스트 67건 추가 (실패 종류별 폴백, 순서, 읽기 전용 계약, 경계값). 전체 265건 통과
- PR: #403 제품 연동, #404 실험 기록. 이슈 #402

## 채택하지 않았다

기본값은 `off` 다. 켜는 것은 설정 하나지만, 지금 있는 근거로는 켤 수 없다고 봤다.
자세한 조건은 `evals/experiments/jev-routing/README.md`.

- 비교 근거가 합성 12건 + reviewed=false 라벨이다
- Gemini 대비 실측 비교를 아직 못 했다 (키·할당량 필요)
- confidence 임계값 0.5 는 공급사 문서 예시 값이고 우리 분포에서 측정한 값이 아니다

## 부수 관측 — 기존 코드 결함, 아직 안 고침

`rule_route` 가 부분 문자열로 설명 질문을 가로챈다.

- `"환불 처리 방식은 어떻게 구현되어 있어?"` → `OUT_OF_SCOPE` (`_OUT_OF_SCOPE_HINTS` 의 "환불")
- `"현재 코드에서 구독 만료를 어떻게 판단해?"` → `METRIC` (`_METRIC_HINTS` 의 "현재")

현재 한계를 `tests/test_jev_router.py::test_after_rules_leaves_the_rule_misroute_in_place`
에 고정해 뒀다. Jev 없이도 고칠 수 있는 문제다(규칙을 어미까지 보게 하거나
설명 어구를 예외로 두는 쪽). 수정 범위와 회귀 위험을 따로 정한 뒤 진행할 것.

## 남은 목표 — Jev 보다 먼저인 것들

출시·품질 증거에 직접 걸린 순서다.

1. **Slack 실제 사용자 멘션 1건 왕복 검증.** 지금은 등록 핸들러 테스트만 했다.
   실제 사람이 멘션했을 때 전달·응답이 되는지 확인 안 됨
   → 남은 조건: `docs/operations/SLACK-E2E-READINESS.md` (2026-09-22 확인)
2. **검색 평가 라벨 검토.** `evals/retrieval/LABEL-REVIEW.md` 의 판단 필요 5가지.
   1번(ret-006 근거 범위)은 2026-09-22 에 결정돼 `median` 청크를 근거로 추가했다
   (`evals/retrieval/RET-006-EVIDENCE.md`). 2~5번은 미정이다. `reviewed` 는 15건
   전부 `false` 그대로다. **자동 승인하지 말 것**
3. **독립 평가셋.** 기존 held_out 5건은 개발 중 노출돼 최종 평가로 못 쓴다.
   새 평가셋과 완료 기준을 먼저 정한 뒤에 추가 검색 실험을 할 것
   → 판정 기준과 실행 명령: `evals/retrieval/INDEPENDENT-EVALSET-PLAN.md`

실험을 더 늘리는 것보다 위 3개가 먼저다. 위 세 문서는 자료이고 승인이 아니다.
라벨 수정·`reviewed` 변경·서비스 기동은 사람이 한다.


---

# 추가 — 2026-09-29 로컬 안정화 (이슈 #412)

## 상태: 로컬에서 끝까지 동작한다

처음으로 전체 경로가 완주했다.

```
$ uv run membershipflow-ai ask "이용 가능한 구독의 판정 기준은?"
[route] KNOWLEDGE   [grounded] True
→ subscription.status = 'ACTIVE' ... [5]
근거 5건
```

컨테이너로도 뜬다. `docker compose -f docker-compose.ai.yml --profile ai up -d`
한 줄이면 ES·Postgres·Slack 봇이 순서대로 올라간다. 절차와 막히는 지점은
`docs/operations/RUNBOOK.md`.

## 고친 것 15개 (#413, #414)

띄우는 것 자체가 안 되던 것 6개, 설정이 조용히 무시되던 것 2개, 실패 처리
5개, 운영 2개. 자세한 목록은 이슈 #412.

특히 컸던 둘.

- **`.env` 의 키를 앱이 못 읽고 있었다.** `GEMINI_API_KEY` 등 넷을 `os.environ`
  으로 직접 읽어서, `.env` 에 채워도 "키가 없다" 로 답하고 Slack 봇은 기동
  단계에서 죽었다. "왜 대답을 못 하지" 의 상당 부분이 이것이었다
- **`ai-api` 가 없는 모듈을 실행하고 있었다.** 띄우면 무한 크래시루프.
  실제 인터페이스인 Slack 봇을 compose 서비스로 올리고 이 정의는 지웠다

## 새로 안 것

- **503 은 모델별로 다르게 온다.** 같은 키·같은 순간에 3.8·3.7·3.5 가 503 인데
  3.6 과 2.5 는 정상이었다. 모델명 문제가 아니다. 폴백 체인을 넣어 기본 모델이
  막혀도 답이 나간다
- **ai-assistant 는 CI 에 없었다.** 이전 PR 들의 "test pass" 는 전부 Java 였다.
  이제 `ci-ai-assistant.yml` 이 경로 변경 시 ruff·mypy·pytest 를 돌린다

## 검증한 것 / 안 한 것

| | |
|---|---|
| 컨테이너 기동, ES 접속, 색인 재사용 | O |
| 앱 크래시 시 자동 복구 (RestartCount 1) | O |
| 전체 질의응답 경로, grounded=true | O |
| pytest 282, ruff, mypy | O |
| **실제 사람의 Slack 멘션 왕복** | **O** (2026-09-29 00:09 확인. 아래 참고) |
| 호스트 재부팅 후 자동 복구 | X |
| AWS 배포 | **하지 않기로 함** (로컬 운영) |

## 남은 판단

- Slack 허용목록이 비어 있다. 경고만 남기고 동작은 바꾸지 않았다. 좁힐지 결정
  필요
- 데이터가 6.6MB(317 청크)뿐이다. ES 대신 이미 있는 pgvector 로 줄일 여지가
  있다. 지금은 동작하므로 서두를 이유는 없다


---

# 추가 — 2026-09-29 Slack 실사용 왕복 확인

## `channel_mention_tested` 가 닫혔다

사람이 `#membershipflow-assistant` 에서 멘션한 질문에 답변이 스레드로 돌아왔다.
2026-09-20 기록부터 계속 `false` 로 남아 있던 항목이다.

```
00:09:17  Received message ... "type":"app_mention" ... channel C0C0CANRA15
00:09:17  Message processing started
          → 답변 + 근거 5건 + route/grounded 푸터 전송
```

답변 자체는 `grounded: false / failure: insufficient_evidence` 였다. 모델이
"제공된 근거로는 답할 수 없습니다" 라고 말했고 시스템이 그것을 잡아 grounded 를
내린 것이다. **고장이 아니라 설계대로 동작한 것이다.** 다만 검색이 실제 만료
판정 코드를 못 올리고 docs/API.md 계열만 올린 것은 검색 품질 과제다.

## 원인은 두 가지였고, 둘 다 설정이 아니었다

**1. 멘션이 파란색이 아니었다.** `@에러 응답 형식이...` 처럼 `@` 를 손으로
타이핑한 것은 Slack 이 멘션으로 보지 않는다. 이벤트 자체가 생기지 않으므로
봇은 아무것도 받지 못한다. 자동완성에서 골라야 한다.

설정은 처음부터 전부 정상이었다. Enable Events, Socket Mode, `app_mention`
구독, `app_mentions:read`, app_id 일치(`A0C0RNQD1JS`), 채널 참여, 단일 연결
(`num_connections:1`) 을 하나씩 확인해 전부 배제한 뒤에야 남은 결론이다.

**2. 503 의 상당 부분은 무료 티어 한도였다.**

```
Quota exceeded: generate_content_free_tier_requests
limit: 20, model: gemini-3.5-flash
```

모델당 하루 20회다. 모델명이 틀린 것이 아니었다. 한도에 걸리자 #414 에서 넣은
폴백이 `gemini-3.6-flash` 로 넘어가 답변을 만들었다. **폴백이 실제 상황에서
처음 일한 사례다.**

## 진단 중 내가 틀렸던 것

- "Event Subscriptions 에서 `app_mention` 구독이 빠진 것" 이라고 단정했다.
  근거 없이 앞서간 판단이었고 실제로는 구독돼 있었다
- 봇 토큰으로 자기를 멘션해 수신을 검증하려 했다. Bolt 는 봇 자신의 이벤트를
  미들웨어에서 걸러내므로(`ignoring_self_events_enabled` 기본 True) 대조군이
  되지 않는다. Astra 가 이 점을 지적해 바로잡았다
- 핸들러 로그 0건을 "Slack 이 안 보냈다" 로 읽었다. 원시 수신 로그와 핸들러
  진입 로그는 구분해야 한다. `AI_LOG_LEVEL=DEBUG` 를 추가한 이유다

절차와 판별표는 `docs/operations/RUNBOOK.md` 에 적었다.

## 2026-09-30 라벨 검토 재개

- 기존 15건 notes 보완, 새 held-out 후보 20건 별도 작성
- `evals/retrieval/review-20260930/REVIEW.md`에서 예상 답·근거 행별 사람 검토
- 로컬 corpus 322청크 기준 모든 anchor 단일 일치, 기존/신규 정답 청크 중복 0
- 2026-09-19 VALIDATED manifest에 필수 근거 37개 ID 모두 포함. 실제 ES 검증·검색 평가 미실행. 모든 reviewed=false 유지
- 이전 독립성 지침 정정: AI 작성 여부가 아니라 튜닝에 사용하지 않는 절차가 기준
- ret-006 변경 전후 점수 직접 비교 금지. 같은 라벨로 구성별 재채점 필요
