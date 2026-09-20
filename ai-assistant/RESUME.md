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
