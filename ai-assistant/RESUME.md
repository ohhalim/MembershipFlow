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
