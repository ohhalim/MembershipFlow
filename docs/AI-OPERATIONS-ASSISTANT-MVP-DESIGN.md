# AI 운영 어시스턴트 초기 MVP 설계

> 2026-09-09 변경: 검색 저장소는 Elasticsearch 통합으로 결정. 아래 BM25S·pgvector 및 증분 발행 관련 항목은 이전 설계이며 [변경 결정](AI-ELASTICSEARCH-DECISION.md) 우선 적용. 구현 교체는 미완료.

> 상태: Claude 1차 리뷰 반영 / 구현 전 설계
> 작성일: 2026-09-02
> 대상 저장소: MembershipFlow
> 대상 인터페이스: Slack app mention, 내부 REST API
> 구현 원칙: 전체 흐름은 end-to-end로 연결하되 각 단계는 한 가지 검증 가능한 경로만 구현

---

## 1. 배경

### 1.1 이전 작업 결과

- MembershipFlow Spring Boot 서비스에 회원, 구독, 결제, 수집, 알림 도메인 구현
- Spring Actuator, Prometheus, Grafana, JSON 로그 기반 운영 관측 경로 구현
- 운영·장애·결제 의사결정 문서를 `docs/`에 기록
- AI 인시던트 분석기는 별도 `MembershipFlow-observability` 저장소로 이전
- 현재 저장소에는 사내 지식 검색 또는 자연어 운영 지표 조회 경로 없음

### 1.2 현재 관측값

- Markdown 문서: `docs/` 하위 21개
- 문서 크기: 약 316KB
- 문서 단어 수: 약 35,751개
- Java 소스: 146개
- Java 테스트: 56개
- Controller mapping annotation: 31개
- 서비스 DB: MySQL 8
- AI 검색용 Vector DB: 미구현
- 문서 변경 감지·재인덱싱: 미구현
- 검색 평가 데이터셋·회귀 기준: 미구현
- Agent tool trajectory·LLM trace: 미구현
- Slack 질의 인터페이스: 미구현

### 1.3 제외된 접근

- LLM이 운영 MySQL에 임의 SQL 실행
- 운영 DB를 AI 서비스가 직접 조회
- 첫 버전의 문서 작성·배포·결제 변경 도구
- 첫 버전의 멀티 에이전트
- 첫 버전의 MCP 서버
- 첫 버전의 Kubernetes·GPU 모델 서빙
- 검색 품질 측정 없는 Vector Search 단독 구현
- 전체 `docs/` 무조건 수집
- 무료 외부 LLM API에 개인정보·결제정보·비공개 문서 전송

### 1.4 현재 구조의 문제 지점

- 운영 지표와 기술 문서가 서로 다른 경로에 존재
- “현재 회원 수”와 “활성 회원의 정의”가 다른 데이터 소스를 요구
- 문서 검색 결과의 정확성·근거·최신성을 측정할 기준 부재
- LLM이 선택한 도구와 검색 결과를 재현할 trace 부재
- 기술을 추가해도 개선 여부를 판단할 offline evaluation 부재

### 1.5 이번 검토 대상

- 명시적으로 허용된 Markdown·Java 수집, 구조 파싱, 청킹, 임베딩, 인덱싱, 증분 갱신
- BM25, Vector, Hybrid, Reranking 검색 비교
- 문서·섹션 단위 citation 생성과 검증
- 자연어 질문의 `METRIC`, `KNOWLEDGE`, `CLARIFY`, `OUT_OF_SCOPE` 분류
- Spring 집계 전용 API와 RAG retriever를 read-only tool로 연결
- LangGraph 기반 단일 workflow
- LangSmith 기반 trace와 offline evaluation
- Slack app mention 기반 내부 사용 흐름

---

## 2. 문제 정의

### 2.1 사용자 문제

운영자 또는 개발자가 다음 정보를 확인하려면 DB, Spring 코드, API 문서, 운영 문서를 각각
찾아야 한다.

- 현재 전체 회원 수
- 현재 유효 구독자 수
- 유효 구독자 판정 기준
- 결제 실패 시 상태 전이
- Paddle webhook 역순 도착 처리 방식
- 수집 배치 이상 탐지 기준

### 2.2 질문 유형별 정답 경로

| 질문 | 정답 경로 | RAG 사용 |
|---|---|---:|
| 현재 전체 회원 수는? | Spring aggregate API | 아니오 |
| 현재 서비스 이용 가능 구독자 수는? | Spring aggregate API | 아니오 |
| 서비스 이용 가능 구독자의 판정 기준은? | `Subscription.isActiveAt()` Java 검색 | 예 |
| 결제 실패 후 어떤 상태가 되는가? | `Subscription`·`BillingScheduler` Java 검색 | 예 |
| 그중 오늘 가입한 회원은? | 직전 대화 + aggregate API | 아니오 |
| 특정 회원의 이메일을 알려줘 | 접근 거부 | 아니오 |
| 구독을 취소해줘 | 초기 MVP 범위 밖 | 아니오 |

LLM은 수치 계산이나 DB 접근 주체가 아니다. 수치는 Spring이 계산하고 LLM은 허용된 tool의
구조화 결과만 설명한다.

### 2.3 핵심 질문과 현재 source of truth

| 질문 | 파일 | symbol 또는 heading | corpus 포함 |
|---|---|---|---:|
| 서비스 이용 가능 구독 판정 | `Subscription.java` | `isActiveAt(LocalDateTime)` | 예 |
| 결제 실패 상태 전이 | `Subscription.java` | `paymentFailed`, `syncPaddlePaymentFailed` | 예 |
| 결제 실패 재시도 대상 | `BillingScheduler.java` | `processDueBillings` | 예 |
| Paddle webhook 역순 방어 | `Subscription.java` | `isStaleExternalEvent` | 예 |
| Paddle webhook 처리 경로 | `PaddleWebhookService.java` | `process`, `syncSubscription` | 예 |
| 수집 결과 이상 탐지 | `AnomalyDetectionService.java` | public·package method 단위 | 예 |
| 결제 전체 흐름 | `docs/learning/PAYMENT-TRANSACTION-FLOW.md` | Markdown heading 단위 | 예 |

답이 없는 질문을 golden set에 추가하지 않는다. code와 문서가 충돌하면 현재 테스트가 검증하는
code를 우선 source of truth로 표시하고 문서 불일치를 별도 issue 후보로 기록한다.

---

## 3. 목표와 비목표

### 3.1 MVP 목표

1. 허용된 Markdown·Java source를 반복 가능한 pipeline으로 검색 인덱스에 반영
2. 같은 평가셋으로 BM25, Vector, Hybrid, Hybrid+Reranker 품질 비교
3. 모든 지식 답변에 실제 검색 chunk 기반 citation 제공
4. 실시간 집계 질문과 문서 질문을 서로 다른 read-only tool로 처리
5. 질문부터 최종 답변까지 tool·retrieval·LLM 단계를 trace로 확인
6. Slack에서 실제 질문·답변 흐름 수동 검증
7. LLM 공급자 없이도 parser, chunker, retrieval, routing 계약 테스트 실행
8. 개인정보와 변경 권한을 AI 서비스에 제공하지 않음

### 3.2 MVP 비목표

- 사용자 대상 공개 챗봇
- 문서 업로드 UI
- PDF, 이미지, OCR 파서
- Confluence, Jira, Google Drive connector
- Slack 과거 대화 수집
- 장기 대화 메모리
- 자율적인 다단계 ReAct loop
- write tool과 human approval workflow
- GraphRAG와 knowledge graph
- fine-tuning
- model serving·GPU 운영
- production 배포와 on-call 운영

비목표는 채용공고에 기술명이 없어서 제외한 것이 아니다. 현재 corpus와 사용 시나리오에서
실패 근거가 없기 때문에 제외한다.

---

## 4. 2026-09 기술 선택 근거

### 4.1 채용공고 표본

2026-09-02 확인한 현행 Applied AI 공고 표본에서 다음 항목이 반복됐다.

| 반복 영역 | 확인 항목 |
|---|---|
| Software Engineering | Python/Java, API, 테스트, 비동기 처리, 서비스 설계 |
| Retrieval | parsing, chunking, embedding, vector/hybrid search, reranking, citation |
| Agent | tool/function calling, structured output, workflow state |
| Quality | golden set, evaluation harness, regression, tracing, feedback loop |
| Production | access control, guardrail, PII, latency, cost, observability, CI/CD |

근거:

- [PlayStation AI Engineer](https://careers.playstation.com/ai-engineer/job/6007946004)
- [Apple Applied AI Engineer](https://jobs.apple.com/en-il/details/200670689-0836/applied-ai-engineer?team=MLAI)
- [Apple iCloud Applied AI Engineer](https://jobs.apple.com/en-il/details/200661135-0836/applied-ai-engineer-icloud-data)
- [Finda AI Agent 개발자](https://finda.career.greetinghr.com/ko/o/187418)

공고 표본은 시장 전체 비율을 의미하지 않는다. 초기 MVP의 역량 범위가 실제 직무 설명과
겹치는지 확인하는 용도로만 사용한다.

### 4.2 기술 결정

| 영역 | 선택 | 제외 또는 후속 | 판단 근거 |
|---|---|---|---|
| AI 서비스 | Python 3.13 + FastAPI | Spring AI | Python AI 생태계와 기존 Spring 서비스 경계 동시 검증 |
| 환경·lock | `uv` + `pyproject.toml` + `uv.lock` | 수동 `pip freeze` | 재현 가능한 의존성·실행 명령 |
| Workflow | LangGraph 단일 StateGraph | 멀티 에이전트, CrewAI | tool 경로와 state transition 명시 |
| LLM | Gemini provider adapter | 모델 ID 직접 분산 | 공급자 교체와 test fake 분리 |
| 생성 모델 | `gemini-3.7-flash` 환경변수 기본값 | preview 모델 | 2026-08 GA, function calling·structured output 지원 |
| Embedding | `BAAI/bge-m3` local | 외부 API 단일 의존 | 한국어 포함 100+ 언어, 1024차원, 재현 가능한 offline retrieval |
| Vector DB | PostgreSQL + pgvector | MySQL에 vector 저장, Chroma | 현재 MySQL 8에는 동일한 native vector index 경로가 없고, metadata transaction과 HNSW index를 한 저장소에서 관리 |
| Dense index | pgvector HNSW cosine | 초기 IVFFlat | 소규모 corpus에서 사전 학습 단계 불필요, recall 비교 가능 |
| Lexical | BM25S | PostgreSQL FTS를 BM25로 표기 | 실제 BM25 baseline과 index artifact 분리 |
| Hybrid | Reciprocal Rank Fusion | score 단순 합산 | BM25·cosine score scale 차이 제거 |
| Reranker | `BAAI/bge-reranker-v2-m3` | LLM reranking | 다국어 query-passage cross-encoder 재현성 |
| Trace·eval | LangSmith + 로컬 JSON 결과 | trace 없는 print log | retrieval/tool/LLM 단계와 experiment 비교 |
| Slack | Bolt Socket Mode | 첫 버전 HTTP Events API | public callback 없이 실제 Slack 흐름 검증 |
| Parser | Markdown 구조 파서 + tree-sitter-java | 전체 저장소 raw text 분할 | heading·method 경계를 citation 단위로 보존 |
| 배포 | `docker-compose.ai.yml`, `ai` profile | 운영 EC2 즉시 배포 | 기존 compose와 필수 환경변수 분리 |

참조:

- [LangSmith RAG evaluation](https://docs.langchain.com/langsmith/evaluate-rag-tutorial)
- [LangSmith evaluation lifecycle](https://docs.langchain.com/langsmith/evaluation)
- [pgvector hybrid search·RRF·cross-encoder](https://github.com/pgvector/pgvector#hybrid-search)
- [BM25S paper](https://arxiv.org/abs/2407.03618)
- [BGE-M3 model card](https://huggingface.co/BAAI/bge-m3)
- [BGE reranker v2 M3 model card](https://huggingface.co/BAAI/bge-reranker-v2-m3)
- [Gemini 3.7 Flash](https://ai.google.dev/gemini-api/docs/latest-model)
- [Slack app_mention](https://api.slack.com/events/app_mention)

### 4.3 모델·서비스 데이터 정책

- 초기 corpus는 저장소 내 허용된 기술 문서만 사용
- 회원 이메일, OAuth provider ID, 결제키, webhook payload는 corpus 제외
- 실시간 tool 응답은 집계 수치·지표 정의·기준 시각만 반환
- Gemini Free Tier 입력은 제품 개선에 사용될 수 있으므로 비공개 사내 문서 적용 금지
- 비공개 문서 적용 전 유료 데이터 처리 조건 또는 enterprise provider 별도 검토
- embedding·reranker model은 Hugging Face revision을 lock 파일과 설정에 기록
- LangSmith tracing 기본값은 비활성화
- tracing 활성화 시 기본 전송 범위는 route, tool 이름, chunk ID·rank·score, latency, token 수
- 원본 질문, 답변, retrieved chunk 본문은 `TRACE_CONTENT_ENABLED=true`인 공개 corpus 실험에서만 전송

---

## 5. 전체 구조

```text
                                ┌──────────────────────────┐
                                │ Markdown allowlist       │
                                │ docs + README            │
                                └────────────┬─────────────┘
                                             │ scan/hash
                                             ▼
┌─────────────┐                     ┌───────────────────────┐
│ ingest CLI  │────────────────────▶│ parse → chunk         │
└─────────────┘                     │ → embed → index       │
                                    └───────┬───────┬───────┘
                                            │       │
                              dense vectors │       │ BM25 artifact
                                            ▼       ▼
                                    ┌──────────┐ ┌─────────┐
                                    │pgvector  │ │ BM25S   │
                                    └────┬─────┘ └────┬────┘
                                         └─────┬──────┘
                                               ▼
                                        Hybrid + Reranker
                                               │
┌──────────┐    app mention      ┌─────────────▼────────────┐
│ Slack    │────────────────────▶│ LangGraph workflow       │
└────▲─────┘                     │ route                    │
     │                           │ ├─ knowledge tool ───────┼──▶ Retriever
     │ thread reply              │ ├─ metric tool ──────────┼──▶ Spring internal API
     │                           │ ├─ clarify               │
     └───────────────────────────│ └─ reject                │
                                 │ synthesize + validate    │
                                 └─────────────┬────────────┘
                                               │ traces/evals
                                               ▼
                                           LangSmith
```

### 5.1 프로세스 경계

| 프로세스 | 책임 |
|---|---|
| `backend` | 집계 지표 계산, 도메인 정의 소유 |
| `ai-api` | `/health`, `/v1/query`, workflow 실행 |
| `ai-slack` | Slack Socket Mode 수신·thread 답변 |
| `ai-ingest` | 수집·파싱·청킹·embedding·index 갱신 CLI |
| `ai-eval` | retrieval·routing·answer offline evaluation CLI |
| `ai-postgres` | 문서·chunk·vector·ingestion run 저장 |

`ai-api`, `ai-slack`, `ai-ingest`, `ai-eval`은 같은 image를 사용하되 command만 분리한다.

---

## 6. 저장소 구조

```text
MembershipFlow/
├── src/main/java/com/membershipflow/
│   └── aimetrics/
│       ├── controller/AiMetricsController.java
│       ├── service/AiMetricsService.java
│       └── dto/AiMetricsResponse.java
├── ai-assistant/
│   ├── pyproject.toml
│   ├── uv.lock
│   ├── Dockerfile
│   ├── .env.example
│   ├── migrations/
│   ├── src/membershipflow_ai/
│   │   ├── api/
│   │   ├── config/
│   │   ├── domain/
│   │   ├── ingestion/
│   │   │   ├── scanner.py
│   │   │   ├── markdown_parser.py
│   │   │   ├── chunker.py
│   │   │   ├── embedder.py
│   │   │   └── pipeline.py
│   │   ├── retrieval/
│   │   │   ├── bm25.py
│   │   │   ├── dense.py
│   │   │   ├── hybrid.py
│   │   │   ├── reranker.py
│   │   │   └── citations.py
│   │   ├── agent/
│   │   │   ├── state.py
│   │   │   ├── graph.py
│   │   │   ├── routing.py
│   │   │   └── tools.py
│   │   ├── integrations/
│   │   │   ├── spring_metrics.py
│   │   │   ├── slack_bot.py
│   │   │   └── langsmith.py
│   │   ├── persistence/
│   │   └── cli/
│   ├── tests/
│   │   ├── unit/
│   │   ├── integration/
│   │   └── contract/
│   └── evals/
│       ├── retrieval.jsonl
│       ├── routing.jsonl
│       └── answer.jsonl
├── docs/evidence/ai-assistant/
├── docker-compose.ai.yml
└── docs/AI-OPERATIONS-ASSISTANT-MVP-DESIGN.md
```

`aimetrics`는 기존 도메인 우선 패키지 규칙을 따른다. HTTP path의 `/internal/ai/**`는 네트워크·
인증 경계를 나타내며 Java package 계층으로 사용하지 않는다. `docs/evidence/ai-assistant/`는 첫
실험에서 새로 생성한다.

---

## 7. 검색 데이터 파이프라인

### 7.1 corpus allowlist

초기 대상은 wildcard가 아닌 `corpus.yml`의 명시 파일 목록으로 고정한다.

Markdown:

- `README.md`
- `docs/00-CONTEXT.md`
- `docs/03-ARCHITECTURE.md`
- `docs/API.md`
- `docs/ERD.md`
- `docs/TROUBLESHOOTING.md`
- `docs/DEPLOYMENT.md`
- `docs/learning/PAYMENT-TRANSACTION-FLOW.md`
- `docs/operations/payment-load-test.md`
- `docs/operations/toss-billing-review-readiness.md`
- `docs/operations/initial-payment-migration-preflight.md`

Java:

- `src/main/java/com/membershipflow/subscription/entity/Subscription.java`
- `src/main/java/com/membershipflow/subscription/repository/SubscriptionRepository.java`
- `src/main/java/com/membershipflow/subscription/scheduler/BillingScheduler.java`
- `src/main/java/com/membershipflow/subscription/service/PaddleWebhookService.java`
- `src/main/java/com/membershipflow/collect/service/AnomalyDetectionService.java`

초기 제외:

- `docs/PORTFOLIO-REVIEW.md`
- `docs/01-MARKET-RESEARCH.md`
- `docs/AI-INCIDENT-ANALYZER-DESIGN.md`: 별도 저장소로 이관된 시스템
- `docs/AI-OPERATIONS-ASSISTANT-MVP-DESIGN.md`: 검색 시스템의 자기 참조 방지
- `docs/Plan.md`, `docs/IMPLEMENTATION.md`: 초기 계획과 현재 code 불일치 가능성
- 이력서·지원서·개인정보 포함 문서
- `.env`, log, DB dump, secret, 외부 업로드 파일

파일 추가·삭제는 `corpus.yml` 변경으로 review한다. ingestion run은 실제 읽은 파일 목록과 hash를
기록하며, 지정 파일이 없으면 성공으로 건너뛰지 않고 실패한다.

### 7.2 수집과 변경 감지

1. 허용 경로 탐색
2. UTF-8 decode와 최대 파일 크기 검증
3. raw content SHA-256 계산
4. `source_uri + content_hash + parser_version` 비교
5. 신규·변경 문서만 parse·chunk·embed
6. 삭제 문서는 `deleted_at` 표시 후 검색 대상 제외
7. 한 문서의 새 chunk 저장 완료 후 active version 교체
8. BM25 artifact를 임시 경로에 작성 후 atomic rename
9. ingestion run에 성공·실패·소요시간·개수 기록

동일 입력과 동일 pipeline version에 대한 재실행은 chunk와 embedding을 중복 생성하지 않는다.

### 7.3 Markdown 파싱

추출 단위:

- document title
- heading path (`H1 > H2 > H3`)
- paragraph
- list
- table
- fenced code block
- source line start·end

규칙:

- heading과 본문 관계 유지
- code block 내부를 일반 문장처럼 분할하지 않음
- table header를 각 row chunk의 context로 보존
- navigation 성격의 빈 heading 제외
- HTML tag는 허용된 Markdown 변환 후 제거
- 문서 내부의 명령문은 data로 취급하고 system instruction과 분리

### 7.4 Java 파싱

tree-sitter-java 기반 추출 단위:

- package, import 요약
- class·enum 선언
- field 이름과 type
- constructor
- method signature·body·Javadoc·annotation
- source line start·end

규칙:

- method를 기본 검색 단위로 유지
- method 이해에 필요한 class 이름과 field type을 context prefix로 추가
- 긴 method만 statement block 경계에서 하위 분할
- 문자열 literal에 포함된 secret 형식은 ingestion 전 redaction
- compile 결과를 해석하거나 call graph를 추론하지 않음
- method가 없는 enum은 선언 전체를 한 chunk로 유지

### 7.5 청킹

첫 baseline:

- heading-aware recursive chunking
- 목표 크기: embedding tokenizer 기준 512 tokens
- overlap: 64 tokens
- 최대 크기 초과 code block: 별도 chunk
- 각 chunk에 document title과 heading path prefix 추가
- chunk ID: `sha256(source_uri + source_version + ordinal + chunk_text)`

비교 실험:

| 실험 | chunk size | overlap | 목적 |
|---|---:|---:|---|
| C1 | 256 | 32 | 짧은 사실 검색 |
| C2 | 512 | 64 | 기본값 |
| C3 | 768 | 96 | 상태 전이·설계 맥락 |

chunk 설정 비교는 tuning set만 사용한다. held-out set은 설정 확정 전 실행하지 않는다. 최종값은
tuning MRR@10을 우선하고 동률 범위에서는 더 작은 index와 낮은 p95 latency를 선택한다.

### 7.6 임베딩과 인덱싱

- embedding model: `BAAI/bge-m3`
- dimension: 1024
- normalization: model 권장 설정 고정
- distance: cosine
- batch size: 로컬 메모리 측정 후 설정
- 저장 metadata: model ID, model revision, dimension, normalized 여부
- model revision 변경 시 동일 index에 혼합 저장 금지
- HNSW index 생성 전 exact search baseline 저장
- approximate recall은 exact top-k와 비교

---

## 8. 검색과 답변

### 8.1 Retriever 단계

```text
query normalize
  ├─ BM25 top 20
  └─ Vector top 20
          ↓
RRF top 20
          ↓
Cross-encoder rerank top 5
          ↓
context budget selection
          ↓
answer + citations
```

### 8.2 Hybrid 결합

- BM25와 Vector 결과를 rank 기반 RRF로 결합
- 기본 `k`는 60으로 시작하되 평가 설정에 기록
- 한 retriever에만 있는 문서도 후보 유지
- 동일 chunk는 ID 기준 중복 제거
- source document 다양성 제한은 결과 편향 확인 후 도입

### 8.3 Reranking

- 후보: Hybrid top 20
- 모델: `BAAI/bge-reranker-v2-m3`
- 입력: `query`, `chunk text`
- 출력: relevance score와 reranked position
- timeout 또는 model load 실패 시 Hybrid 결과로 fallback
- fallback 발생 여부를 trace와 응답 metadata에 기록

### 8.4 Citation

Citation 형식:

```json
{
  "citation_id": "C1",
  "source_uri": "docs/learning/PAYMENT-TRANSACTION-FLOW.md",
  "title": "결제 트랜잭션 흐름",
  "heading_path": ["결제 승인", "실패 처리"],
  "line_start": 84,
  "line_end": 102,
  "content_hash": "...",
  "chunk_id": "..."
}
```

검증:

- 답변의 `[C1]`이 실제 retrieved chunk ID를 참조하는지 확인
- 존재하지 않는 citation ID 포함 시 답변 실패 처리
- knowledge 답변에 citation 0개이면 `INSUFFICIENT_EVIDENCE`
- citation identity는 `source_uri + content_hash + heading/symbol path`로 고정
- 인용 link의 line fragment는 현재 version에서의 탐색 편의용 보조 정보
- 문서 version과 answer trace 연결

---

## 9. Agent workflow

### 9.1 상태

```python
class AssistantState(TypedDict):
    request_id: str
    thread_id: str | None
    question: str
    route: Literal["METRIC", "KNOWLEDGE", "CLARIFY", "OUT_OF_SCOPE"] | None
    tool_calls: list[ToolCall]
    retrieved_chunks: list[RetrievedChunk]
    answer: str | None
    citations: list[Citation]
    errors: list[str]
```

### 9.2 노드

1. `normalize_input`
2. `route_question`
3. route별 실행
   - `call_metric_tool`
   - `retrieve_knowledge`
   - `ask_clarification`
   - `reject_request`
4. `generate_answer`
5. `validate_answer`
6. `persist_trace_metadata`

### 9.3 Tool 목록

| Tool | 입력 | 출력 | 권한 |
|---|---|---|---|
| `get_member_metrics` | optional date range | total, new, as_of, definition | 집계 읽기 |
| `get_subscription_metrics` | 기준 시각 | service_valid, status_counts, cancelled_service_valid, as_of, definition | 집계 읽기 |
| `search_knowledge` | query, top_k | chunks, scores, citations | 허용 문서 읽기 |

임의 SQL tool, generic HTTP tool, shell tool은 제공하지 않는다.

### 9.4 Agent 범위

- 한 요청에서 최대 tool call 2회
- tool call timeout과 전체 workflow timeout 분리
- route·tool argument는 Pydantic schema 검증
- 같은 read-only tool의 한 차례 재시도만 허용
- 숫자 응답은 tool payload 값과 정확히 일치하는지 후검증
- 지식 응답은 citation validator 통과 필수
- write intent는 `OUT_OF_SCOPE`

LangGraph 사용 이유는 “Agent” 이름을 붙이기 위해서가 아니라 route, tool, validation 경계를
명시하고 각 node의 입력·출력을 평가하기 위해서다.

---

## 10. Spring 집계 API

### 10.1 endpoint

```http
GET /internal/ai/metrics/members
GET /internal/ai/metrics/subscriptions
```

응답 예시:

```json
{
  "metric": "service_valid_subscriptions",
  "value": 17,
  "asOf": "2026-09-02T13:30:00+09:00",
  "definitionVersion": "subscription-valid-v1",
  "definition": "status=ACTIVE 또는 status=CANCELLED이고 nextBillingAt이 기준 시각 이후인 구독",
  "statusCounts": {
    "ACTIVE": 15,
    "CANCELLED": 3,
    "PAYMENT_FAILED": 2,
    "SUSPENDED": 1
  },
  "cancelledServiceValid": 2,
  "filters": {
    "timezone": "Asia/Seoul"
  }
}
```

정의:

- `service_valid`: `Subscription.isActiveAt(asOf)`와 같은 조건
- `ACTIVE`: DB status가 `ACTIVE`인 row 수
- `cancelled_service_valid`: status가 `CANCELLED`이고 `nextBillingAt > asOf`인 row 수
- `PAYMENT_FAILED`: 결제 재시도 대상일 수 있으나 현재 `isActiveAt` 기준 서비스 이용 가능에는 미포함
- `SUSPENDED`: 서비스 이용 가능에 미포함
- `nextBillingAt`: schema의 `NOT NULL` 조건을 집계 query와 integration test에서 확인

### 10.2 보안

- Nginx에 `location /internal/ { deny all; }` 명시
- `AiServiceTokenAuthenticationFilter extends OncePerRequestFilter` 구현
- filter가 `/internal/ai/**`에서 `X-AI-Service-Token`을 constant-time 비교
- 인증 성공 시 `ROLE_AI_SERVICE` authority를 가진 service principal 설정
- `SecurityConfig`에 `.requestMatchers("/internal/ai/**").hasRole("AI_SERVICE")` 추가
- service token filter를 `JwtAuthenticationFilter` 앞에 배치
- token 누락·불일치 시 JSON 401 반환
- `/admin/collect`와 같은 `permitAll + nginx 차단` 방식은 사용하지 않음
- token은 환경변수 주입, log·trace 제외
- GET 집계 endpoint만 허용
- member ID, email, OAuth ID 반환 금지
- 날짜 범위와 응답 크기 제한
- access log에 request ID, metric name, status, latency만 기록

도메인 집계 정의는 Spring service와 repository query가 소유한다. Python은 수치를 재계산하지
않는다.

---

## 11. API와 Slack 계약

### 11.1 내부 query API

```http
POST /v1/query
Content-Type: application/json
```

```json
{
  "question": "현재 유효 구독자는 몇 명이야?",
  "threadId": "optional"
}
```

```json
{
  "requestId": "uuid",
  "route": "METRIC",
  "answer": "...",
  "citations": [],
  "asOf": "2026-09-02T13:30:00+09:00",
  "traceId": "...",
  "fallbacks": []
}
```

### 11.2 Slack

- 입력: `app_mention`
- scope: `app_mentions:read`, `chat:write`
- 연결: Socket Mode
- 응답: 원 질문의 thread
- 허용 workspace·channel ID allowlist
- bot message와 retry event 중복 처리
- Slack event ID idempotency
- Slack user ID를 LLM prompt에 전달하지 않음
- 답변에 route, 기준 시각, citation link 표시
- 오류 세부 stack trace를 Slack에 반환하지 않음

HTTP Events API 전환 시 Slack signing secret 기반 서명 검증을 별도 작업으로 구현한다.

---

## 12. 평가 설계

### 12.1 Golden dataset

최소 수량:

| dataset | 최소 문항 | 사람 검토 |
|---|---:|---|
| retrieval tuning | 40 | 정답 파일·symbol/heading 확인 |
| retrieval held-out | 20 | 설정 확정 전 query 내용 비공개·수정 금지 |
| routing | 40 | route별 10개, 기대 tool 확인 |
| answer | 20 | reference facts·citation 확인 |
| security | 16 | 공격 유형별 4개, 기대 거부·정보 비노출 확인 |

LLM이 초안을 생성할 수 있으나 정답 문서와 기대 route는 사용자가 검토한 항목만 golden set에
포함한다.

retrieval 60문항은 다음 난이도를 각 20개 포함한다.

- 정확한 식별자·상태명·method 이름이 있는 lexical lookup
- 자연어로 바꿔 쓴 semantic paraphrase
- 비슷한 상태·provider·문서가 섞인 hard negative

tuning과 held-out은 난이도·source type을 층화 분리한다. chunk size, overlap, RRF `k`, candidate
수, reranker 사용 여부는 tuning set으로만 결정한다. held-out query는 설정 commit 이후 한 번
실행하며 결과 확인 후 설정을 다시 바꾸면 새 experiment로 기록한다.

### 12.2 Retrieval 지표

- Recall@1을 1차 지표로 사용
- MRR@10을 2차 지표로 사용
- Recall@3, Recall@5
- nDCG@10
- citation source precision
- p50, p95 retrieval latency
- index build time
- changed document reindex count

비교표:

| experiment | Recall@1 | MRR@10 | Recall@5 | p95 | 비고 |
|---|---:|---:|---:|---:|---|
| BM25 | 측정 전 | 측정 전 | 측정 전 | 측정 전 | baseline |
| Vector exact | 측정 전 | 측정 전 | 측정 전 | 측정 전 | baseline |
| Vector HNSW | 측정 전 | 측정 전 | 측정 전 | 측정 전 | approximate recall 포함 |
| Hybrid RRF | 측정 전 | 측정 전 | 측정 전 | 측정 전 | fusion |
| Hybrid + reranker | 측정 전 | 측정 전 | 측정 전 | 측정 전 | 최종 후보 |

### 12.3 Agent 지표

- route accuracy
- class-wise route accuracy와 macro average
- 동일 질문 반복 route consistency
- tool selection accuracy
- tool argument exact match
- tool execution success rate
- citation validity rate
- metric value consistency rate
- groundedness
- answer correctness
- refusal correctness
- end-to-end p50, p95 latency
- request당 input/output token과 추정 비용

### 12.4 평가 방법

- 코드 기반 평가 우선
  - route, tool, argument, citation ID, metric equality
- reference answer가 있는 사실 질문은 exact fact check
- groundedness·relevance는 LLM-as-judge를 보조 지표로 사용
- judge model·prompt·version 기록
- retrieval rank 결과는 동일 index·설정으로 3회 실행해 동일성 확인
- live route·answer 평가는 문항별 3회 실행해 비결정성 기록
- tuning과 held-out 결과를 별도 파일로 저장
- 실패 trace를 regression dataset으로 편입

LLM judge 점수만으로 완료를 판정하지 않는다.

### 12.5 초기 완료 기준

고정 gate:

- ingestion unit·integration test 100% 통과
- 동일 문서 재수집 시 추가 active chunk 0개
- 삭제 문서 검색 결과 0개
- parser 오류 문서 0개
- 사람 검토가 끝난 retrieval tuning 40개·held-out 20개만 평가에 사용
- held-out Recall@1 0.70 이상, MRR@10 0.80 이상
- held-out 20문항에서 1문항은 5 percentage points임을 report에 명시
- runtime retriever는 tuning MRR@10이 가장 높은 구성을 선택
- tuning MRR 차이가 0.05 미만이면 구성요소가 적고 tuning p95가 낮은 구성을 선택
- 선택한 구성과 corpus·model revision을 commit으로 고정한 뒤 held-out은 최종 검증에만 사용
- held-out 결과를 보고 구성을 바꾸면 기존 held-out을 새 선택에 사용한 사실을 기록하고, 독립 평가셋 확보 전 최종 일반화 성과로 주장하지 않음
- Hybrid·Reranker가 선택되지 않아도 실험 결과와 제외 판단을 보존
- routing 4개 class별 10문항 중 각 class 9개 이상 정답
- live routing 3회 반복 결과의 route consistency 95% 이상
- metric value consistency 100%
- citation validity 100%
- security 16문항의 PII·write·prompt injection·scope 우회 차단 100%
- live API·model weight 없이 fake embedding·fake LLM 기반 CI 100% 통과
- 실제 BGE-M3·reranker 로컬 experiment 1회 완료와 model revision 기록
- Slack 수동 시나리오 5개 모두 기대 route·응답·trace ID 확인

---

## 13. Tracing과 관측성

### 13.1 trace span

```text
assistant.request
├── agent.route
├── tool.metric | retriever.search
│   ├── retriever.bm25
│   ├── retriever.vector
│   ├── retriever.fusion
│   └── retriever.rerank
├── llm.generate
└── answer.validate
```

span attributes:

- request ID, thread ID hash
- route, tool name
- corpus version, pipeline version
- retriever configuration
- retrieved chunk ID·rank·score
- model ID·revision
- token usage, latency
- fallback, validation result, error category

금지 attributes:

- API key, Slack token, service token
- member email, OAuth ID, 결제키
- 원본 민감정보
- 기본 설정에서 원본 질문·최종 답변·retrieved chunk 본문

`LANGSMITH_TRACING=false`, `TRACE_CONTENT_ENABLED=false`를 기본값으로 사용한다. 공개 corpus에
대한 수동 evaluation에서 두 값을 명시적으로 활성화한 경우에만 질문·답변·chunk 본문을
LangSmith로 전송한다. 그 외 trace는 chunk ID·rank·score 등 metadata만 남긴다.

### 13.2 offline·online loop

```text
golden dataset
→ offline experiment
→ 설정 비교
→ 선택한 설정 배포
→ Slack trace·feedback
→ 실패 사례 분류
→ golden regression 추가
```

LangSmith 미설정 환경에서는 tracing 전송을 no-op으로 처리하고 구조화 application log와 local
evaluation JSON을 남긴다. 이 경우 “LangSmith 운영”을 완료 결과로 기록하지 않는다.

---

## 14. 보안과 실패 처리

### 14.1 위협 모델

| 위협 | 통제 |
|---|---|
| 문서 내 prompt injection | 문서를 instruction이 아닌 untrusted context로 구분, tool 권한 고정 |
| 사용자의 PII 조회 | aggregate tool만 제공, output schema 제한 |
| write 요청 | write tool 부재, route 거부 |
| 다른 채널 사용 | workspace·channel allowlist |
| secret trace 노출 | header·환경변수 redaction |
| 오래된 문서 답변 | content hash, updated_at, corpus version, citation 표시 |
| hallucinated citation | retrieved chunk ID whitelist 검증 |
| 무한 agent loop | max tool call 2, 전체 timeout |
| 과다 비용 | token·request limit, model config, usage trace |
| 외부 provider 장애 | 명확한 실패 응답, retrieval 결과 임의 생성 금지 |

RAG는 prompt injection을 제거하지 않는다. [OWASP LLM01:2025](https://genai.owasp.org/llmrisk/llm01-prompt-injection/)를 기준으로 LLM 권한보다 애플리케이션 권한 경계에서 통제한다.

### 14.2 실패 정책

| 실패 | 처리 |
|---|---|
| parser 실패 | 해당 문서 실패 기록, 기존 active version 유지 |
| embedding 실패 | 문서 version 전환 금지 |
| BM25 artifact 갱신 실패 | 이전 artifact 유지 |
| reranker timeout | Hybrid fallback, trace 표시 |
| Spring API timeout | 수치 추측 금지, 조회 실패 응답 |
| LLM timeout | 한 번 재시도 후 실패 |
| citation validation 실패 | 답변 폐기, 근거 부족 응답 |
| Slack 전송 실패 | 오류 log, 동일 event 중복 답변 방지 |

---

## 15. 테스트 전략

### 15.1 단위 테스트

- Markdown heading·table·code block 파싱
- token 기준 chunk 경계와 overlap
- deterministic chunk ID
- content hash 변경 감지
- RRF 계산과 중복 제거
- citation ID 검증
- route schema·tool argument 검증
- metric response semantic validation
- secret·PII redaction

### 15.2 통합 테스트

- PostgreSQL + pgvector migration
- 문서 ingest → active chunk 조회
- 동일 문서 재수집 idempotency
- 문서 변경·삭제 반영
- vector exact·HNSW 검색
- BM25 artifact version 전환
- Hybrid + reranker fallback
- FastAPI query contract
- Spring metric API contract
- Slack event idempotency

### 15.3 회귀·보안 테스트

- golden retrieval evaluation
- golden routing evaluation
- citation hallucination
- prompt injection 문서·질문
- PII 조회
- write 요청
- out-of-scope 질문
- provider timeout·malformed structured output

### 15.4 CI

- 기존 Java `test` job과 별도 `ai-test` job
- `uv sync --frozen`으로 model weight를 포함하지 않는 core·dev dependency만 설치
- fake embedding·fake reranker·fake LLM 기반 lint·format·type·unit test
- Testcontainers PostgreSQL + pgvector integration test
- Java unit·integration test
- live Gemini, Slack, LangSmith 호출 제외
- BGE-M3·reranker weight download와 실제 model test는 로컬 evidence command로 분리
- model adapter 계약을 fake와 실제 구현에 동일하게 적용
- 기존 compose 검증 명령을 그대로 통과
- dummy local 값으로 `docker compose -f docker-compose.yml -f docker-compose.ai.yml --profile ai config -q` 통과
- `docker-compose.ai.yml`은 parse 단계에서 Gemini·Slack·LangSmith secret을 필수로 요구하지 않음
- dependency lock 변경 검증
- secret scan

---

## 16. 구현 단위

한 PR에 전체 목적을 섞지 않고 다음 순서로 분리한다.

### PR 1 — 설계와 평가 계약

- 이 설계 문서
- corpus allowlist 초안
- golden dataset schema
- API·tool schema
- 구현 전 Claude 리뷰

완료 조건:

- 핵심 질문 7개 모두 파일·symbol/heading source mapping 보유
- `corpus.yml`에 Markdown 11개·Java 5개 명시, wildcard·자기 문서·이관 문서 0개
- retrieval tuning 40개·held-out 20개 schema와 고정 gate 문서화
- Claude 리뷰 B1~B3, H1~H4에 대한 반영 또는 기각 근거 기록
- `git diff --check` 오류 0개

### PR 2 — ingestion과 검색 baseline

- Python service scaffold
- PostgreSQL + pgvector
- Markdown·Java parser와 chunker
- BGE-M3 embedding
- 증분 ingestion
- BM25·Vector baseline
- retrieval unit·integration test

완료 조건:

- parser 대상 16개 중 실패 0개
- 동일 corpus 2회 ingest 후 active chunk 증가 0개
- 삭제 fixture 재ingest 후 검색 노출 0개
- BM25·Vector tuning 40문항 평가 command exit code 0
- 기존 compose config와 `ai` profile compose config 모두 exit code 0
- fake model 기반 `ai-test` CI test 실패 0개

### PR 3 — Hybrid·Reranking·Citation

- RRF Hybrid
- BGE reranker
- citation builder·validator
- retrieval experiment 비교

완료 조건:

- retrieval 60문항의 source mapping 검토 완료
- held-out Recall@1 0.70 이상, MRR@10 0.80 이상
- runtime retriever 선택 규칙 결과 기록
- citation validity 100%
- 실제 BGE-M3·reranker revision·p50·p95 기록

### PR 4 — Agent와 Spring metric tool

- Spring aggregate API
- LangGraph route·tool·validation
- FastAPI query endpoint
- routing·security dataset
- LangSmith trace 연결

완료 조건:

- route별 10문항 중 각각 9개 이상 정답
- live route 3회 반복 consistency 95% 이상
- Spring repository 집계와 tool 응답 값 일치 100%
- `/internal/ai/**` 무토큰·오토큰 401, 정상 service token 200
- LangSmith 공개 corpus trace 1건에서 route·tool·retrieval·validation span 확인
- 기본 trace에서 원본 질문·답변·chunk 본문 전송 0건

### PR 5 — Slack 사용 흐름

- Slack Socket Mode adapter
- channel allowlist·event idempotency
- thread response
- 수동 시나리오 기록

완료 조건:

- Slack 질문 5개 모두 thread 답변과 trace ID 연결
- 같은 Slack event 2회 전달 시 답변 1개
- 비허용 채널, write 요청, PII 요청, prompt injection 각 1건 차단

---

## 17. 이력서에 기록 가능한 완료 증거

다음 항목은 구현·측정 완료 후 실제 수치로만 작성한다.

- Markdown·Java source N개 대상 수집·구조 파싱·청킹·임베딩·증분 인덱싱 pipeline
- 동일 문서 재수집 시 중복 active chunk 0건
- BM25, Vector, Hybrid, Reranker의 Recall@1·MRR@10·p95 비교
- Spring aggregate API와 문서 retriever를 구분하는 tool routing 정확도
- 존재하는 chunk만 허용하는 citation validation 결과
- LangSmith trace 기반 tool·retrieval·LLM 병목 분석
- Slack 질문 시나리오와 실패·거부 사례
- Docker Compose와 CI에서 재현 가능한 실행·테스트

다음 표현은 현재 단계에서 사용하지 않는다.

- “상용 운영”
- “대규모 문서 처리”
- “정확도 향상” — before/after 수치 전
- “환각 제거”
- “멀티 에이전트 구축”
- “MCP 적용”

---

## 18. 구현 전 확인 항목

- [ ] Slack workspace와 테스트 channel 준비
- [ ] Gemini API key 사용 가능 여부
- [ ] LangSmith API key 사용 가능 여부
- [ ] BGE-M3·reranker local 실행 메모리와 latency 측정
- [ ] corpus allowlist 문서별 비공개 정보 확인
- [ ] Spring aggregate metric 정의 사용자 확인
- [ ] golden dataset 정답 문서 사용자 검토
- [x] Claude 1차 설계 리뷰 반영
- [x] GitHub issue #350과 `feat/350/ai-operations-assistant` 연결

---

## 19. 남은 리스크

- 명시 source 16개 규모에서는 HNSW의 성능 이점 검증 불가 가능성
- BGE-M3와 reranker 동시 적재 시 로컬 메모리 압박 가능성
- 한국어 복합어·영문 기술어 혼합 corpus에서 BM25 tokenizer 품질 저하 가능성
- LLM route의 비결정성으로 단순 질문의 latency·비용 증가 가능성
- Slack Socket Mode가 HTTP Events API의 서명 검증·durable queue 운영 경험을 대체하지 못함
- Java method 변경 시 과거 line link 재현성 저하; content hash·symbol path를 identity로 사용
- 공개 corpus trace만 content 전송하도록 제한했으나 설정 오류로 외부 전송될 가능성

위 리스크는 기술 추가가 아니라 측정 결과에 따라 다음 issue로 분리한다.

---

## 20. Claude 1차 리뷰 반영 기록

검토 대상: 현재 worktree의 이 문서와 Spring·Docker·CI 코드

| ID | 지적 | 판단 | 반영 |
|---|---|---|---|
| B1 | 핵심 질문 일부가 Markdown corpus에 근거 없음 | 채택 | 관련 Java 5개를 명시 corpus에 추가, 질문별 symbol mapping 작성 |
| B2 | 약 70~100 chunk에서 Recall@5 중심 비교 판별력 부족 | 채택 | Recall@1·MRR@10 중심, hard negative와 단순 구성 우선 규칙 추가 |
| B3 | 30문항 하나로 tuning과 gate 동시 사용 | 채택 | tuning 40·held-out 20으로 분리, held-out 실행 시점 고정 |
| H1 | `/internal/**` service token의 Spring 인증 지점 부재 | 채택 | 전용 filter, authority, matcher, filter order, 401 계약 명시 |
| H2 | AI compose 편입 시 기존 필수 env와 CI 충돌 가능 | 채택 | `docker-compose.ai.yml`·`ai` profile과 독립 config 검증 명시 |
| H3 | 수 GB local model과 일반 CI 동시 실행 불가 | 채택 | fake model CI와 실제 local model evidence 분리 |
| H4 | LangSmith trace 본문 외부 전송 정책 누락 | 채택 | tracing·본문 전송 기본 비활성, metadata-only 경로 명시 |
| M1 | subscription metric 세부 정의 불명확 | 채택 | `isActiveAt`과 status breakdown 기준 명시 |
| M2 | 상태 전이 근거 문서 allowlist 누락 | 대안 반영 | 오래된 계획 문서 대신 현재 Java source를 포함 |
| M3 | design wildcard가 자기 문서·이관 문서를 포함 | 채택 | wildcard 제거, 16개 파일 명시 |
| M4 | 결과 저장·합의 같은 순환 완료 조건 | 채택 | PR별 exit code·건수·정확도 gate로 교체 |
| M5 | route accuracy class 분포 미정 | 채택 | 4개 class별 10문항·각 9개 정답 조건 추가 |
| M6 | `internal.ai` package가 기존 도메인 구조와 불일치 | 채택 | `com.membershipflow.aimetrics`로 변경 |

리뷰 결론은 “조건부 구현 가능”이었다. 위 blocker와 high 항목을 설계에 반영했으므로 PR 1의
로컬 문서 검증과 corpus·dataset 계약 구현부터 진행한다.
