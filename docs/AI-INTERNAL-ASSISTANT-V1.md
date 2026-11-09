# 내부 개발·운영 어시스턴트 V1 실행 설계

> 2026-09-09 변경: 검색 저장소는 Elasticsearch 통합으로 결정. 아래 BM25S·pgvector 및 증분 발행 관련 항목은 이전 설계이며 [변경 결정](AI-ELASTICSEARCH-DECISION.md) 우선 적용. 구현 교체는 미완료.

- 작성일: 2026-09-09
- 상태: 설계 제안 / 기능 완료 및 품질 측정 전
- 기반: `AI-OPERATIONS-ASSISTANT-MVP-DESIGN.md`
- 적용: 기존 설계의 첫 사용 범위 구체화. 복합 질문, 수집 조회, 운영 검증은 이 문서 기준. 라이브러리 버전은 구현 시 lock 및 공식 문서 확인

## 1. 현재 구조와 검토 대상

- 기존 corpus allowlist: Markdown 11개, Java 5개
- ingestion·persistence·BM25·vector 구현 파일 존재. 통합 동작 및 품질은 이번 설계에서 미검증
- `CollectRun`: 실행 시각, 종료 시각, 상태, 성공·실패 건수, 오류 메시지 저장
- 기존 `CollectRunRepository`: 직전 실행 조회 제공. 기간별 운영 조회 계약 추가 필요
- `CollectRun.complete(0, 0)`: SUCCESS 판정. 상태와 데이터 최신성 구분 필요
- `Subscription.isActiveAt`: ACTIVE 또는 다음 결제 시각 전 CANCELLED. 일반적인 구독 정의로 임의 대체 금지
- 문서 검색 소요 시간·반복 질문 수·답변 품질: 미측정
- 검토 대상: 문서 근거와 실시간 운영 조회를 연결한 읽기 전용 답변

## 2. 첫 사용자와 질문

- 사용자: MembershipFlow 개발·운영자
- 진입점: 내부 FastAPI REST 우선, 이후 Slack thread 연결

| 유형 | 질문 예 | 정답 경로 |
|---|---|---|
| 지식 | 이용 가능한 구독의 판정 기준은? | Subscription 코드 검색, symbol·버전 인용 |
| 지식 | 결제 webhook 역순 도착을 어떻게 처리해? | 허용된 코드·문서 검색 |
| 현재 상태 | 오늘 수집 결과는? | Spring 기간별 수집 조회 |
| 복합 | 오늘 실패한 수집과 확인할 절차를 알려줘 | Spring 조회 + 해당 소스·증상 문서 검색 |
| 복합 | 현재 이용 가능한 구독자 수와 집계 기준은? | Spring 집계 + 판정 코드 검색 |
| 명확화 | 그거 실패한 이유가 뭐야? | 대상 없으면 질문, 대상 있어도 원인 근거 없으면 확인 항목 안내 |

- 초기 제외: 고객 상담, 자동 복구, 배포 실행, 결제 변경, 임의 SQL, GitHub 실시간 연결, 장기 기억
- 구현 완료·배포 완료·운영 검증 완료를 동일하게 취급하지 않음

## 3. 요청 처리 구조

```text
REST / Slack
  → 인증·요청 제한
  → 질문 분류 및 대상·기간 검증
      KNOWLEDGE → 문서 검색
      METRIC    → Spring 읽기 전용 API
      COMBINED  → Spring API → 필요한 근거 검색
      CLARIFY   → 대상·기간 확인
      OUT_OF_SCOPE → 지원 범위 안내
  → 구조화 답변 생성
  → 수치·출처 검증
  → 답변 + 근거 + 기준 시각 + trace_id
```

- 기존 4개 route에 COMBINED 추가, routing 평가 계약도 5개 class로 변경
- 검색 1회·운영 조회 1회, 요청당 최대 2개 논리 tool 호출. 재시도는 별도 횟수·총시간 제한
- 첫 버전은 단일 workflow. 도구 선택 결과와 실행 이력을 기록
- 수치 계산: Spring 책임. LLM 출력 수치와 API 결과 불일치 시 해당 답변 차단
- API 실패 시 현재 수치 추측 금지. 문서 답변만 가능한 경우 부분 응답으로 명시

## 4. 데이터와 인용

- 기존 16개 allowlist 유지, 수집 질문에 필요한 CollectRun·수집 서비스·대응 문서만 개별 검토 후 추가
- 원문에 없는 장애 대응 절차는 생성 금지. 직접 검증한 절차를 먼저 문서화
- Markdown: 제목·섹션·표 경계 보존. Java: 메서드·Javadoc·symbol 보존
- 인용: source path, symbol/heading, line range, content hash, index version
- 저장소 snapshot commit과 원문 hash 보관. 현재 파일의 행 번호만으로 과거 답변 재현 주장 금지
- 같은 문서 재수집은 중복 없이 처리. 변경·삭제·임베딩 버전 변경·실패 후 재시작 검증
- BM25와 vector가 같은 활성 corpus version을 조회하도록 publication 경계 정의
- 문서와 코드 충돌 시 둘의 버전 및 불일치 표시. 코드만으로 운영 배포 상태 확정 금지

## 5. Spring 조회 계약

- 기존 제안: `/internal/ai/metrics/members`, `/internal/ai/metrics/subscriptions`
- 추가 제안: `GET /internal/ai/collection-runs?from=...&to=...&source=...`
- 기본 오늘: Asia/Seoul 00:00부터 조회 시각까지. API timestamp는 offset 포함, DB 시각 변환은 구현 전 확인
- 조회 기간·반환 행 수 제한. 응답에 `as_of`, 적용 구간, `truncated` 포함
- 수집 응답: source, run_id, started_at, finished_at, status, success_count, fail_count
- 원본 error_message는 최초 응답 제외. 검토된 오류 코드만 추가 가능
- 실행 없음은 NO_RUN 의미로 응답, 성공으로 치환 금지
- SUCCESS + 성공 0건은 관측값 그대로 설명. 데이터 최신성·전체 정상 여부는 확인 불가 표시
- 서비스 전용 인증과 집계·조회 권한만 부여. 일반 사용자 JWT로 접근 불가 검증

## 6. 검색 실험 및 평가

- 비교 순서: BM25 → vector → RRF hybrid → reranker 추가
- 실험별 한 요소 변경. 복잡한 구성이 개선되지 않으면 단순 구성 채택
- retrieval: tuning 40문항, held-out 20문항. 유사 질문이 두 집합에 걸치지 않도록 질문군 기준 분리
- 정답: 질문, 정답 근거 span, 필수 사실, 답변 가능 여부, 검토자 기록
- AI 초안과 사람 검토 완료 상태 구분. 사람 검토 전 점수를 최종 품질로 사용 금지
- 지표: Recall@1, MRR@10, 근거로 뒷받침되지 않는 주장 비율, 올바른 답변 보류 비율
- routing: 5개 class 각 10문항. 운영 장애·기간 모호성·복합 질문 포함
- API 계약: 값·기간·권한·실패 응답을 fixture 및 실제 DB 테스트로 검증
- citation 유효성은 실제 근거가 주장을 지지하는지와 별도 평가
- 성능: 같은 장비·corpus·모델·동시성에서 p50/p95, 실패율, 토큰, 비용 기록
- tuning으로 구성 선택·고정 후 held-out 최종 평가. 재튜닝 시 독립 최종 평가셋 필요
- 개선 없는 실험도 조건·결과·제외 판단 보존. 실패 결과를 의도적으로 만들지 않음

## 7. 구현 순서와 완료 조건

1. 수집·검색 baseline: 실제 corpus 파싱, 변경·삭제·재시작 DB 테스트, BM25/vector 비교 실행
2. 지식 답변: REST 질문 → 검색 → 인용 답변 연결, 없는 근거·잘못된 인용 거부 검증
3. 운영·복합 질문: Spring 인증·조회 → 문서 검색 연결, 0건 SUCCESS·조회 실패·기간 경계 검증
4. 품질 개선: 사람 검토 평가셋, tuning 실험, held-out 결과, 채택·기각 기록
5. 내부 사용: Slack 연결, 중복 이벤트·채널 제한, 실제 사용 피드백 기록
6. 배포: 자원 사용 측정, 배포 대상 확정, health·실패 복구·rollback 검증. 원격 작업 직전 사용자 승인

- 첫 수직 구현 완료: 지식·현재 상태·복합 질문 각 1건의 실제 end-to-end 통과
- 전체 V1 완료: 기존 품질 gate에 복합 route 추가, 실제 모델 평가 및 배포 환경 검증 완료
- 로컬 fake 테스트 통과를 실제 RAG 품질·운영 완료로 표기하지 않음

## 8. 효용 검증과 후속 범위

- 실제 운영 중 질문, 참고한 근거, 수정한 답변, 사용 여부 기록
- 수동 탐색과 어시스턴트 사용의 정답 도달 시간 비교. 동일 질문 반복에 따른 학습 효과 및 1인 평가 한계 명시
- Kafka: 현재 수집량·갱신 지연 요구 미측정. 초기 도입 제외
- Batch: 우선 재실행 가능한 ingestion CLI. 정기 실행·체크포인트 요구 확인 후 별도 작업
- MCP·멀티 에이전트: 외부 도구 증가나 단일 workflow 실패 사례 확인 후 검토
- 남은 리스크: 제한된 corpus와 사용자 수, 실제 모델 자원 비용, 정답 검토 부담, 운영 환경 시각·인증 계약
