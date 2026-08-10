# AI 인시던트 분석기 설계

> 상태: Implementation Ready / 미구현
> 작성일: 2026-08-10
> 대상 저장소: MembershipFlow
> 첫 검증 시나리오: `/api/v1/courses` 429 증가
> 운영 목표: 단일 EC2 환경에서 안전하게 실행 가능한 1차 운영 버전

---

## 1. 배경

MembershipFlow는 Spring Boot Actuator와 Micrometer를 통해 Prometheus 메트릭을
노출하고, Grafana에서 HTTP 요청량·p95·4xx·5xx·JVM·HikariCP 상태를 조회한다.
수집 및 결제 배치의 마지막 실행 시각도 별도 Gauge로 기록한다.

현재 장애 알림 경로는 다음 두 가지다.

- Grafana alerting → Discord
- Spring `ERROR` 로그 → `DiscordLogbackAppender` → Discord

현재 구조는 운영자가 메트릭과 로그를 직접 확인해야 한다. 또한 로그는 Spring 기본 console
pattern과 Discord 중심이라 검색 가능한 중앙 저장소와 구조화된 입력이 없다.

LY Corporation의 SRELens처럼 자연어와 LLM을 관측성 데이터에 연결하되, 첫 버전은
전체 LGTM-P 스택이나 Grafana 채팅 UI를 복제하지 않는다. Prometheus metric과 Loki log를
결정적인 Evidence로 정규화하고 LLM이 해당 증거를 해석하는 읽기 전용 분석 파이프라인을
구축한다.

---

## 2. 목표

### 2.1 1차 목표

- k6로 `/api/v1/courses`의 429 증가 상황 재현
- Prometheus에서 장애 전후 핵심 메트릭 수집
- Loki에서 동일 시간 범위의 구조화 WARN·ERROR 로그 수집
- 수집 결과를 재현 가능한 Evidence JSON으로 생성
- 외부 LLM API를 통해 원인 후보·근거·제외 후보·다음 확인 항목 생성
- 분석 결과의 JSON Schema 검증 및 Slack 전달
- 실제 주입한 원인과 LLM 분석 결과 비교

### 2.2 비목표

- LLM의 운영 DB 수정
- 서버 재시작, 배포, 롤백 자동 실행
- 결제·구독·회원 데이터 조회
- 원본 요청 본문, 쿠키, 토큰, 개인정보의 LLM 전송
- 첫 버전의 Tempo·Pyroscope 도입
- 첫 버전의 다중 라운드 자율 에이전트
- 원인 후보를 검증 없이 최종 원인으로 확정

---

## 3. 현재 기반과 추가 대상

| 항목 | 현재 상태 | 이번 설계 범위 |
|---|---|---|
| Spring Actuator | 구현 | 유지 |
| Prometheus | 구현 | Evidence Collector의 조회 대상 |
| Grafana 대시보드 | 구현 | 운영 확인 및 후속 UI 후보 |
| Grafana alerting | 배치 heartbeat 알림 구현 | 429 alert 및 FastAPI webhook 추가 |
| Discord ERROR 알림 | 구현 | 기존 경로 유지 |
| Slack 분석 알림 | 미구현 | Incoming Webhook으로 구현 |
| 구조화 인시던트 이벤트 | 미구현 | 구현 |
| FastAPI 분석 서버 | 미구현 | 구현 |
| LLM API 연동 | 미구현 | 구현 |
| 구조화 file log | 미구현 | Logback JSON rolling file 구현 |
| Grafana Alloy | 미구현 | file tail, JSON parsing, Loki 전송 |
| Loki 로그 검색 | 미구현 | 1차 Evidence Collector의 조회 대상 |
| Tempo 트레이스 | 미구현 | 3차 이후 검토 |

현재 Docker Compose에 Prometheus와 Grafana는 존재하지만 Alertmanager 서비스는 없다.
따라서 1차 구현의 자동 트리거는 Grafana alerting의 webhook을 사용한다.

---

## 4. 핵심 설계 결정

1. FastAPI 코드는 기존 저장소의 `incident-analyzer/`에 배치한다.
2. FastAPI는 Spring Boot와 별도 프로세스·별도 Docker 컨테이너로 실행한다.
3. FastAPI 포트는 Nginx에 연결하지 않고 Docker 내부 네트워크에서만 노출한다.
4. Spring의 일반 API 요청 처리 경로에서는 LLM을 호출하지 않는다.
5. 장애 접수 API는 이벤트 저장 후 `202 Accepted`를 반환한다.
6. Evidence 계산과 필터링은 Python 코드와 Prometheus가 담당한다.
7. LLM은 계산된 Evidence JSON의 해석만 담당한다.
8. 첫 버전은 인시던트당 LLM API 1회 호출로 제한한다.
9. 분석 작업 상태는 기존 MySQL 8 인스턴스의 전용 database에 저장한다.
10. 분석 서버 장애는 MembershipFlow 기능 장애로 전파되지 않아야 한다.
11. API와 worker는 같은 이미지의 별도 프로세스로 실행하고 MySQL DB 큐를 공유한다.
12. 작업 선점에는 lease를 사용하고, 프로세스 종료 후 만료된 작업을 재처리한다.
13. 분석 결과 저장과 Slack 전송을 분리하고 delivery outbox로 재전송한다.
14. 입력 계약, Evidence, 프롬프트, 출력 Schema에 각각 버전을 기록한다.
15. 컨테이너 이미지는 `latest` 대신 검증된 버전 또는 digest로 고정한다.
16. 첫 운영 버전은 분석만 수행하며 자동 복구·배포·롤백 권한을 갖지 않는다.

### 4.1 결정 기록

| ID | 결정 | 선택 이유 | 재검토 조건 |
|---|---|---|---|
| ADR-001 | 기존 repository의 독립 Python module | 배포·운영 근거를 한곳에서 추적, Spring 요청 경로와 프로세스 분리 | 세 프로젝트가 동일 계약 사용 |
| ADR-002 | 기존 MySQL 8의 전용 database·계정 | 현재 운영 기술 재사용, transaction·`SKIP LOCKED`·확장 지원 | 본 서비스 DB 영향 또는 HA 요구 |
| ADR-003 | MySQL DB queue + lease | 현재 낮은 incident 빈도에서 broker 운영 비용 제거 | worker 3개에서도 backlog 지속 |
| ADR-004 | Gemini Developer API + provider adapter | 초기 Free Tier 사용, 공급자 종속을 adapter에 격리 | 품질·quota·데이터 정책 미충족 |
| ADR-005 | Slack Incoming Webhook | 단방향 결과 전달에 필요한 최소 권한 | thread interaction·ack workflow 필요 |
| ADR-006 | Prometheus metric + Loki log, Tempo 제외 | 수치 변화와 같은 시각의 예외·경고를 함께 검증 | trace 없이는 후보 구분 불가 사례 반복 |
| ADR-007 | Grafana Alloy file tail | Docker socket 노출 없이 backend rolling file 수집 | multi-host/container orchestration 전환 |
| ADR-008 | 운영 Loki TSDB + S3, 로컬만 filesystem | 운영 로그 내구성과 단일 EC2 disk 격리 | 관리형 Loki 전환 또는 보존 요구 변경 |

각 재검토는 새 기술 도입 자체가 아니라 표의 관측 조건과 측정 결과를 근거로 별도 ADR에서
진행한다.

---

## 5. 전체 구조

```text
[k6 부하테스트]
       │
       ▼
[Spring Boot] ──metric────────────▶ [Prometheus] ──alert query──┐
       │                                                        │
       │ JSON rolling log                                      ▼
       ▼                                               [Grafana alerting]
[Shared Log Volume]                                             │ HMAC webhook
       │                                                        ▼
       ▼                                                  [Incident API]
[Grafana Alloy] ──push──▶ [Loki]                          │ 202
                              │                           ▼
                              │ query           [MySQL DB Queue / Store]
                              │                           │ lease
                              │                           ▼
                              └──────────────────────▶ [Worker] ◀── [Prometheus query]
                                                          ├─ Evidence Builder
                                                          ├─ Guardrails / Masking
                                                          └─ LLM Client
                                                                  │ HTTPS
                                                                  ▼
                                                              [Gemini]
                                                                  │
                                                                  ▼
                                                    [Schema + Semantic Validation]
                                                                  │ transaction
                                                                  ▼
                                                         [Delivery Outbox]
                                                                  │ retry
                                                                  ▼
                                                   [#membershipflow-incidents]
```

FastAPI가 Prometheus와 Loki를 조회하는 동작은 읽기 전용이다. Incident API와 worker에는 Docker
socket, SSH 키, 배포 권한, MembershipFlow 운영 DB 접근 권한을 제공하지 않는다. worker만
외부 Gemini·Slack HTTPS egress가 필요하고, Incident API는 외부 egress를 사용하지 않는다.

---

## 6. 저장소 구조

```text
MembershipFlow/
├── src/                              # Spring Boot
├── prometheus/
├── grafana/
├── loki/
│   └── loki-config.yaml
├── alloy/
│   └── config.alloy
├── incident-analyzer/
│   ├── Dockerfile
│   ├── alembic.ini
│   ├── pyproject.toml
│   ├── migrations/
│   ├── app/
│   │   ├── main.py
│   │   ├── config.py
│   │   ├── api/
│   │   │   └── incidents.py
│   │   ├── domain/
│   │   │   ├── incident.py
│   │   │   ├── evidence.py
│   │   │   └── analysis.py
│   │   ├── collectors/
│   │   │   ├── prometheus.py
│   │   │   ├── loki.py
│   │   │   ├── log_normalizer.py
│   │   │   └── registry.py
│   │   ├── services/
│   │   │   ├── evidence_builder.py
│   │   │   ├── incident_service.py
│   │   │   └── analysis_service.py
│   │   ├── llm/
│   │   │   ├── client.py
│   │   │   └── gemini_client.py
│   │   ├── persistence/
│   │   │   ├── database.py
│   │   │   ├── incident_repository.py
│   │   │   ├── job_repository.py
│   │   │   └── delivery_repository.py
│   │   ├── security/
│   │   │   ├── authentication.py
│   │   │   └── masking.py
│   │   ├── worker.py
│   │   └── delivery_worker.py
│   └── tests/
├── docs/
└── docker-compose.yml
```

`llm/client.py`는 공급자 중립 인터페이스를 정의한다. 첫 구현은 Gemini Developer API를
사용하며, 실제 SDK와 모델명은 `gemini_client.py`와 환경변수에만 위치시킨다. 이후 공급자
교체가 필요해도 Evidence Builder, worker, API 계약에 영향을 주지 않게 한다.

---

## 7. 컴포넌트 책임

### 7.1 Incident API

- Grafana 기본 webhook payload 및 수동 테스트 이벤트 수신
- raw body 기준 HMAC 서명과 timestamp 검증
- payload 크기·필수 필드·허용 label·허용 alert rule 검증
- 그룹 payload의 `alerts[]`를 개별 인시던트 이벤트로 정규화
- Grafana fingerprint와 내부 dedup key로 중복 이벤트 판정
- MySQL에 incident와 job을 하나의 트랜잭션으로 저장
- LLM 처리 완료를 기다리지 않고 `202 Accepted` 반환
- `resolved` 알림은 새 LLM 호출 없이 기존 incident episode 종료 처리

### 7.2 Worker

- 실행마다 고유 `worker_id` 생성
- 실행 가능한 `PENDING` 또는 재시도 작업 조회
- `lease_owner`, `lease_until`을 조건부 갱신해 작업 원자적 선점
- Evidence Builder 실행
- LLM Client 호출
- 응답 JSON Schema 및 Evidence 참조 의미 검증
- 결과, 호출 메타데이터, Slack delivery outbox를 하나의 트랜잭션으로 저장
- 성공 시 `SUCCEEDED`, 재시도 불가 실패 시 `FAILED` 변경
- lease가 만료된 `ANALYZING` 작업을 시작 시점에 재등록
- 종료 신호 수신 시 새 작업 선점을 중단하고 현재 작업을 제한 시간 안에 마무리

API 프로세스와 worker 프로세스는 분리한다. Uvicorn worker 수를 늘려도 분석 worker가
함께 복제되지 않게 하며, 첫 배포의 분석 worker replica는 1로 고정한다.

### 7.3 Evidence Collector

- 사전에 정의된 PromQL 템플릿만 실행
- 사전에 정의된 LogQL 템플릿만 실행
- 인시던트 기준 동일 시간 범위의 메트릭과 구조화 로그 조회
- 단위 변환과 집계 결과 정규화
- 반복 로그를 signature별 count와 대표 sample로 축약
- 각 증거에 ID와 실제 쿼리 기록
- 조회 실패와 데이터 부재를 결과에 명시

### 7.4 Log Pipeline

- Spring Logback JSON rolling file 생성
- Grafana Alloy가 read-only volume에서 file tail
- Alloy가 JSON parsing·고정 label 부여 후 Loki에 push
- Loki는 TSDB index와 S3 object storage에 7일 보존
- Grafana와 incident worker만 Loki query endpoint 접근

### 7.5 LLM Client

- Evidence JSON을 외부 LLM API에 전달
- 시스템 지침과 고정 출력 Schema 적용
- timeout, 출력 크기, 재시도 제한 적용
- 토큰 사용량·지연 시간·모델명 기록
- 공급자 오류를 도메인 오류로 변환

### 7.6 Guardrails

- 민감정보 마스킹
- 허용된 메트릭과 시간 범위 제한
- 동일 fingerprint 중복 분석 제한
- LLM 최대 호출 횟수·시간·출력 크기 제한
- 근거가 없을 때 `INSUFFICIENT_EVIDENCE` 반환 강제

### 7.7 Delivery Worker

- `PENDING` Slack delivery를 outbox에서 선점
- HTTP 2xx와 응답 본문 `ok`를 성공으로 판정
- timeout, 429, 5xx만 지수 백오프와 jitter로 재시도
- 영구 오류는 `DEAD`로 전환하고 분석 결과는 유지
- 같은 `incident_id + channel + message_version`의 중복 전송 방지

첫 배포에서는 analysis loop와 delivery loop를 `incident-worker` 프로세스 안의 독립 async
task로 실행한다. 상태·재시도·metric은 분리한다. delivery backlog가 분석 지연과 독립적으로
증가하면 같은 image의 별도 process로 분리한다.

---

## 8. 처리 순서

```text
1. k6가 /api/v1/courses에 부하 발생
2. RateLimitFilter가 한도 초과 요청에 429 반환
3. Spring이 구조화 WARN log와 429 전용 metric 기록
4. Alloy가 rolling file을 tail해 Loki에 전송
5. Grafana alerting이 Prometheus 또는 Loki 임계값 초과 감지
6. Grafana가 FastAPI /internal/incidents에 webhook 전송
7. Incident API가 HMAC·timestamp·입력 검증 후 incident와 job 저장
8. FastAPI가 202 Accepted 반환
9. Worker가 lease로 job을 선점하고 같은 시간 범위의 Prometheus metric과 Loki log 조회
10. Evidence Builder가 metric·log Evidence JSON 생성
11. Gemini API가 Analysis JSON 반환
12. Worker가 JSON Schema, Evidence 참조, 의미 규칙 검사
13. MySQL에 결과와 Slack outbox 저장
14. Delivery worker가 Slack에 분석 요약과 incident ID 전송
```

---

## 9. 내부 API 계약

### 9.1 장애 접수

```http
POST /internal/incidents
Content-Type: application/json
X-Grafana-Alerting-Signature: <hex hmac sha256>
X-Grafana-Alerting-Timestamp: <unix seconds>
```

Grafana contact point는 timestamp를 포함한 HMAC-SHA256 서명을 사용한다. Incident API는
JSON parsing 전에 raw body로 서명을 검증하고 timestamp가 현재 시각과 5분 이상 차이나면
재전송 공격으로 거부한다. 문자열 비교는 constant-time 함수를 사용한다. 별도 수동 테스트
endpoint는 운영 profile에서 비활성화하고 테스트 profile에서만 Bearer token을 허용한다.

Grafana 기본 webhook 입력 예시:

```json
{
  "receiver": "incident-analyzer",
  "status": "firing",
  "alerts": [
    {
      "status": "firing",
      "labels": {
        "alertname": "CourseApiHighRateLimit",
        "service": "membershipflow-backend",
        "environment": "production",
        "severity": "warning",
        "route": "/api/v1/courses"
      },
      "annotations": {
        "summary": "courses API 429 rate exceeded threshold"
      },
      "startsAt": "2026-08-10T10:00:00+09:00",
      "fingerprint": "grafana-fingerprint"
    }
  ],
  "truncatedAlerts": 0
}
```

정규화 후 내부 이벤트 예시:

```json
{
  "source": "grafana",
  "service": "membershipflow-backend",
  "environment": "production",
  "alertName": "CourseApiHighRateLimit",
  "severity": "warning",
  "occurredAt": "2026-08-10T10:00:00+09:00",
  "route": "/api/v1/courses",
  "status": 429,
  "errorCode": "RATE_LIMITED",
  "labels": {
    "job": "membershipflow-backend"
  },
  "annotations": {
    "summary": "courses API 429 rate exceeded threshold"
  }
}
```

응답:

```http
HTTP/1.1 202 Accepted
```

```json
{
  "accepted": 1,
  "rejected": 0,
  "incidents": [
    {
      "incidentId": "inc_20260810_000001",
      "status": "PENDING",
      "deduplicated": false
    }
  ]
}
```

중복 이벤트는 새 LLM 호출을 만들지 않고 기존 인시던트를 반환한다. `truncatedAlerts > 0`은
경고 메트릭으로 기록하고, contact point의 `maxAlerts` 값과 함께 운영자가 확인한다. 알 수
없는 alert rule, `firing/resolved` 외 상태, 64KB 초과 payload는 LLM 호출 전 거부한다.

### 9.2 분석 결과 조회

```http
GET /internal/incidents/{incidentId}
Authorization: Bearer <INCIDENT_OPERATOR_TOKEN>
```

조회 API token은 Grafana HMAC secret과 분리한다. 운영 host 내부의 제한된 점검 명령에서만
사용하며 Nginx를 통해 공개하지 않는다.

### 9.3 상태 확인

```http
GET /health/live
GET /health/ready
```

`/health/live`는 프로세스 event loop만 확인한다. `/health/ready`는 MySQL 연결·간단한 query,
Alembic schema revision 일치 여부를 확인한다. Gemini, Prometheus, Slack 외부 호출은
readiness에 포함하지 않아 외부 장애 때문에 컨테이너가 무한 재시작되지 않게 한다. worker
healthcheck는 `worker_last_heartbeat_at`이 설정 주기의 3배 이내인지 별도로 확인한다.

---

## 10. 인시던트 상태 모델

```text
PENDING
   │
   ▼
ANALYZING ──────▶ SUCCEEDED
   │
   ├────────────▶ INSUFFICIENT_EVIDENCE
   │
   └────────────▶ FAILED
```

| 상태 | 의미 |
|---|---|
| `PENDING` | 이벤트 저장 완료, 분석 대기 |
| `ANALYZING` | 증거 수집 또는 LLM 호출 중 |
| `SUCCEEDED` | Schema 검증된 분석 결과 저장 완료 |
| `INSUFFICIENT_EVIDENCE` | 원인 후보를 제시할 근거 부족 |
| `FAILED` | 내부 오류, Prometheus 오류, LLM 오류 또는 Schema 오류 |

MySQL에는 다음 메타데이터를 저장한다.

- incident ID와 fingerprint
- 원본 이벤트의 마스킹된 사본
- 상태와 상태 변경 시각
- Evidence JSON
- Analysis JSON
- 모델명, 호출 지연 시간, 입력·출력 토큰 수
- 실패 코드와 재시도 가능 여부

원본 인증 헤더와 LLM API 키는 저장하지 않는다.

### 10.1 작업 상태와 delivery 상태 분리

분석 완료와 Slack 전송 성공은 별도 상태다. Slack 장애가 분석을 `FAILED`로 되돌리지 않는다.

```text
analysis_status: PENDING → ANALYZING → SUCCEEDED | INSUFFICIENT_EVIDENCE | FAILED
delivery_status: NOT_READY → PENDING → SENDING → SENT | DEAD
episode_status:  OPEN → RESOLVED
```

`FAILED`에는 `failure_stage`, 안정적인 `failure_code`, `retryable`, `attempt_count`를 저장한다.
예외 메시지 전체는 DB에 저장하지 않고 민감정보가 제거된 짧은 설명만 보존한다.

### 10.2 MySQL 논리 스키마

| 테이블 | 주요 컬럼 | 제약과 용도 |
|---|---|---|
| `incidents` | `id`, `external_fingerprint`, `dedup_key`, `episode_status`, `started_at`, `resolved_at`, `payload_version`, `masked_event_json` | 인시던트 원본과 episode 수명주기 |
| `analysis_jobs` | `id`, `incident_id`, `analysis_revision`, `status`, `available_at`, `attempt_count`, `lease_owner`, `lease_until`, `failure_code`, `created_at`, `updated_at` | DB 기반 작업 큐 |
| `evidence_bundles` | `incident_id`, `analysis_revision`, `schema_version`, `collector_version`, `window_*`, `content_json`, `content_sha256` | 재현 가능한 LLM 입력 |
| `analysis_results` | `incident_id`, `analysis_revision`, `schema_version`, `prompt_version`, `provider`, `model`, `content_json`, `input_tokens`, `output_tokens`, `latency_ms` | 검증된 분석 결과 |
| `delivery_outbox` | `id`, `incident_id`, `analysis_revision`, `destination`, `message_version`, `status`, `available_at`, `attempt_count`, `lease_until`, `last_http_status` | Slack 전송과 재시도 |
| `schema_metadata` | `revision`, `applied_at` | 마이그레이션 상태 확인 |

필수 제약:

- `incidents.id` primary, 각 하위 테이블은 `incident_id` foreign key
- `analysis_jobs(incident_id, analysis_revision)` unique
- `evidence_bundles(incident_id, analysis_revision)` unique
- `analysis_results(incident_id, analysis_revision)` unique
- `incidents(dedup_key, started_at)` 조회 인덱스
- `analysis_jobs(status, available_at, lease_until)` 선점 인덱스
- `delivery_outbox(status, available_at, lease_until)` 선점 인덱스
- `delivery_outbox(incident_id, analysis_revision, destination, message_version)` unique
- 모든 timestamp는 UTC `DATETIME(6)`로 저장, API·Slack 표시 단계에서만 KST 변환
- JSON payload는 MySQL `JSON`, hash와 식별자는 길이가 제한된 `VARCHAR/BINARY` 사용

타입 규칙:

- incident ID: 정렬 가능한 ULID `CHAR(26)`
- SHA-256: hex 문자열 대신 `BINARY(32)`
- 상태·버전·provider/model: 길이가 제한된 `VARCHAR`
- timestamp: `DATETIME(6)` UTC
- token·attempt·latency: unsigned 정수 범위 검증
- Evidence·Analysis JSON: application에서 64KB 제한 후 MySQL `JSON` 저장
- 금액은 저장하지 않으며 provider가 제공하는 token usage만 저장

DB 분리:

```text
MySQL instance: 기존 mysql:8.0 컨테이너
application database: membershipflow
incident database: membershipflow_incident
runtime user: incident_analyzer_runtime
migration user: incident_analyzer_migrator
```

runtime user에는 `membershipflow_incident.*`의 `SELECT`, `INSERT`, `UPDATE`, `DELETE`만
부여한다. migration user에만 `CREATE`, `ALTER`, `INDEX`, `REFERENCES`를 추가한다. 두 계정은
`membershipflow.*`에 접근할 수 없다. 기존 volume에는 Docker 초기화 script가 다시 실행되지
않으므로 database·계정 생성은 운영 preflight에서 명시적인 one-shot SQL로 수행한다.

Alembic 마이그레이션은 배포 전에 migration user로 별도 one-shot 실행한다. 애플리케이션
시작 시 자동 DDL 변경은 금지하고, 현재 revision이 코드 기대값과 다르면 readiness를
실패시킨다.

애플리케이션은 SQLAlchemy 2.x repository 경계 안에서 MySQL driver를 사용한다. 도메인과
service 계층은 SQLAlchemy model을 직접 반환하지 않는다. DB URL과 password는 로그에
출력하지 않고 connection acquisition timeout은 2초로 제한한다.

### 10.3 작업 선점과 lease

worker는 짧은 트랜잭션 안에서 `SELECT ... FOR UPDATE SKIP LOCKED`로 실행 가능한 job을
조회하고 lease를 갱신한다. row lock을 보유한 채 Prometheus나 Gemini를 호출하지 않는다.
네트워크 호출은 DB 트랜잭션 밖에서 수행한다.

초기값:

```text
JOB_LEASE_SECONDS=120
JOB_HEARTBEAT_SECONDS=30
JOB_MAX_ATTEMPTS=3
DELIVERY_MAX_ATTEMPTS=5
```

worker는 30초마다 lease를 연장한다. `lease_until < now`인 `ANALYZING/SENDING` 작업은
재시도 가능 상태로 돌린다. 최대 시도 횟수를 초과하면 `FAILED/DEAD`로 이동한다. 같은
incident의 LLM 호출 결과가 이미 저장돼 있으면 Gemini를 다시 호출하지 않고 다음 단계부터
재개한다.

---

## 11. 중복 방지

fingerprint 입력:

```text
environment | service | alertName | route | severity | stable dimension labels
```

정규화한 문자열을 SHA-256으로 변환한다. IP, timestamp, 현재 metric value처럼 매번 바뀌는
값은 포함하지 않는다. Grafana가 제공한 fingerprint는 원본 추적용으로 별도 저장한다.

동일 dedup key의 `firing` 이벤트가 5분 안에 반복되면 기존 OPEN episode에 연결하고 새 분석
작업을 만들지 않는다. 5분이 지나도 기존 episode가 OPEN이면 `last_seen_at`만 갱신하고,
설정된 재분석 간격이 지났을 때만 새 analysis revision을 허용한다. `resolved` 이벤트는 같은
episode를 종료하고 LLM을 호출하지 않는다. 이후 다시 `firing`되면 새 episode를 생성한다.

중복 제한 시간은 환경변수로 관리한다.

```text
INCIDENT_DEDUP_WINDOW_SECONDS=300
INCIDENT_REANALYZE_INTERVAL_SECONDS=1800
```

---

## 12. Evidence JSON

### 12.1 원칙

- 수치 계산은 Prometheus와 Python 코드에서 완료
- 각 증거에 고유 ID 부여
- 실제 PromQL과 조회 시간 범위 기록
- 값이 없는 메트릭을 0으로 변환하지 않음
- 조회 실패와 0을 구분
- 장애 전 baseline과 장애 구간 값을 함께 제공
- LLM이 Evidence에 없는 수치를 새로 만들지 못하게 제한
- Evidence에 schema, collector, query template 버전 기록
- 동일 fixture와 시간 범위에서 canonical JSON hash가 동일해야 함

### 12.2 예시

```json
{
  "incidentId": "inc_20260810_000001",
  "window": {
    "baselineFrom": "2026-08-10T09:45:00+09:00",
    "baselineTo": "2026-08-10T09:55:00+09:00",
    "incidentFrom": "2026-08-10T10:00:00+09:00",
    "incidentTo": "2026-08-10T10:03:30+09:00"
  },
  "facts": [
    {
      "id": "E1",
      "metric": "http_429_rate",
      "baseline": 0.0,
      "incident": 0.46,
      "unit": "requests_per_second",
      "queryStatus": "SUCCESS"
    },
    {
      "id": "E2",
      "metric": "course_api_p95",
      "baseline": 180.0,
      "incident": 2410.0,
      "unit": "milliseconds",
      "queryStatus": "SUCCESS"
    },
    {
      "id": "E3",
      "metric": "hikari_pending_max",
      "baseline": 0.0,
      "incident": 0.0,
      "unit": "connections",
      "queryStatus": "SUCCESS"
    },
    {
      "id": "E4",
      "metric": "http_5xx_rate",
      "baseline": 0.0,
      "incident": 0.0,
      "unit": "requests_per_second",
      "queryStatus": "SUCCESS"
    },
    {
      "id": "E5",
      "metric": "process_cpu_max",
      "baseline": 22.0,
      "incident": 34.0,
      "unit": "percent",
      "queryStatus": "SUCCESS"
    }
  ],
  "logEvidence": [
    {
      "id": "L1",
      "signature": "rate_limit_rejected|/api/v1/courses",
      "level": "WARN",
      "event": "rate_limit_rejected",
      "baselineCount": 0,
      "incidentCount": 42,
      "sample": "rate limit rejected route=/api/v1/courses clientHash=[REDACTED]",
      "queryStatus": "SUCCESS"
    }
  ],
  "knownConfiguration": {
    "rateLimitType": "fixed_window_per_ip",
    "requestsPerMinute": 120
  },
  "missingEvidence": [
    "client_retry_count"
  ]
}
```

예시 수치는 계약 설명용이며 실제 측정 결과가 아니다.

### 12.3 시간 범위 결정

alert 수신 시점 이후 10분의 데이터를 즉시 조회할 수 없으므로 고정된 “전후 각 10분”을
사용하지 않는다. 기준 시각은 Grafana alert의 `startsAt`이고, 수신 시각은 별도 저장한다.

초기 정책:

```text
scrape settle delay: 30초
baseline: startsAt - 15분 ~ startsAt - 5분
incident: startsAt ~ min(현재 - 30초, startsAt + 10분)
minimum incident window: 2분
```

incident window가 2분 미만이면 job을 즉시 실패시키지 않고 `available_at`을 뒤로 미룬다.
`startsAt`이 없거나 미래·24시간 이전이면 수신 시각으로 대체하고 Evidence 품질을
`DEGRADED_TIME_REFERENCE`로 표시한다. 모든 Prometheus·Loki query는 같은 cutoff를 사용해
증거 간 시간 범위를 일치시킨다.

---

## 13. Evidence source 조회 정책

### 13.1 Prometheus

LLM이 임의 PromQL을 실행하지 않는다. alert type별로 코드에 등록한 쿼리 템플릿만
실행한다.

`CourseApiHighRateLimit`의 1차 쿼리 후보:

| Evidence | 메트릭 |
|---|---|
| 429 발생률 | `http_server_requests_seconds_count`의 `status="429"` rate |
| 5xx 발생률 | `status=~"5.."` rate |
| API p95 | `http_server_requests_seconds_bucket` histogram quantile |
| Hikari active | `hikaricp_connections_active` |
| Hikari max | `hikaricp_connections_max` |
| Hikari pending | `hikaricp_connections_pending` |
| 프로세스 CPU | `process_cpu_usage` |
| JVM heap | `jvm_memory_used_bytes{area="heap"}` |
| JVM GC | `jvm_gc_pause_seconds_count` rate |

구현 전 확인 항목:

1. `RateLimitFilter`에서 종료된 429가 `http_server_requests_seconds_count`에 포함되는지 확인
2. 429의 `uri` 라벨이 `/api/v1/courses`로 기록되는지 확인
3. `hikaricp_connections_pending`이 운영 Prometheus에 실제 노출되는지 확인
4. Nginx 뒤에서 `request.getRemoteAddr()`가 실제 client IP로 복원되는지 확인
5. `RateLimitFilter`의 in-memory 고정 윈도우가 단일 backend 인스턴스 전제인지 기록

429 또는 URI 라벨이 충분하지 않으면 Spring에 다음 전용 Counter를 추가한다.

```text
rate_limit_rejections_total{uri="/api/v1/courses"}
```

현재 `RateLimitFilter`는 MVC handler 전에 429를 반환하므로 일반 HTTP 서버 메트릭 포함 여부를
측정 없이 전제하지 않는다. 전용 Counter의 `uri`는 원본 path가 아니라 허용된 route template
집합으로 제한하고 IP를 metric label로 사용하지 않는다.

alert type별 registry 항목은 다음 계약을 가진다.

```text
alert_name
required_labels
baseline_window
incident_window
query_templates[]
required_evidence_ids[]
known_configuration_provider
evidence_schema_version
```

PromQL 문자열에는 webhook label을 직접 연결하지 않는다. registry allowlist 값만 템플릿에
주입하고 label 값은 정규식 escape 처리한다. Prometheus 응답의 시계열·샘플 수를 검사한 뒤
초과하면 `LIMIT_EXCEEDED`로 기록한다.

쿼리 시간 범위 제한:

- baseline: 10분
- incident: 2~10분
- 전체 조회 최대: 1시간
- 최대 시계열 개수: 100
- 최대 샘플 수: Evidence 항목당 1,000

### 13.2 구조화 로그 계약

현재 Spring 기본 console pattern은 LLM Evidence로 직접 사용하지 않는다. Logback에 JSON
rolling file appender를 추가하고 console·Discord appender는 기존대로 유지한다.

필드 allowlist:

```text
timestamp
level
service
environment
logger
event
error_code
route
request_id
exception_class
message
stack_trace
```

- `request_id`: 외부 값을 그대로 신뢰하지 않고 형식·길이를 검증하거나 새 UUID 생성
- `event`: 코드에 정의한 낮은 cardinality event name
- `route`: route template만 허용, query string 제외
- `message`: 비밀정보·회원 식별자·결제 식별자 없는 문장
- `stack_trace`: Loki에는 저장하되 Gemini 전달 전 상위 20 frame과 8KB로 제한
- MDC 초기화 누락으로 다른 요청 값이 섞이지 않도록 `finally`에서 clear

전송 금지 필드:

```text
ip, email, member_id, order_id, billing_key, payment_key,
authorization, cookie, token, request_body, response_body
```

현재 `RateLimitFilter`의 `ip={}` WARN log는 Loki 도입 전에 제거한다. 필요한 경우 원문 IP가
아닌 운영 salt 기반 hash를 message가 아닌 별도 비-label field로 기록하고 24시간마다 salt를
교체한다. 기존 코드 전체의 log parameter를 audit해 `memberId`, `orderId` 등도 LLM 수집
대상 event에서 제거한다.

### 13.3 Alloy 수집 정책

backend는 `/var/log/membershipflow/application.json`에 JSON line을 기록하고 rolling policy로
파일당 50MB, 최대 3개, 최대 24시간을 유지한다. named volume을 backend에는 read-write,
Alloy에는 read-only로 mount한다. Docker socket은 Alloy에 제공하지 않는다.

Alloy pipeline:

```text
loki.source.file
→ loki.process JSON parse
→ 고정 label(service, environment, level, event)
→ 민감 field drop
→ loki.write
```

label에는 IP, request ID, route raw path, logger, exception message를 넣지 않는다. JSON parsing
실패 log는 `parse_error=true` 고정 label과 원문 없는 counter로 관측한다. Alloy positions는
별도 volume에 보존하고 최초 운영 배포는 `tail_from_end=true`로 과거 파일 재수집을 막는다.

### 13.4 Loki 저장·조회 정책

운영은 single-binary Loki, TSDB index, S3 object storage, compactor retention 7일을 사용한다.
S3 bucket은 private, public access block, server-side encryption, EC2 IAM role 최소 권한을
적용한다. local 개발에서만 filesystem storage를 허용한다.

Loki는 자체 인증 계층이 없으므로 host port와 Nginx에 공개하지 않고 Docker monitoring
network에서 Grafana·Alloy·incident worker만 접근한다.

worker는 `/loki/api/v1/query_range`만 사용하고 alert profile에 등록한 LogQL template만
실행한다. `direction=forward`, `limit=200`, query timeout 5초를 고정한다. 결과는 timestamp로
정렬한 뒤 다음 순서로 축약한다.

1. `event + error_code + exception_class + normalized message`로 signature 생성
2. baseline·incident count 계산
3. signature 상위 20개 선택
4. signature별 마스킹된 대표 sample 최대 2개 선택
5. 전체 Gemini 전달 log Evidence 최대 16KB 제한

LogQL 예시:

```text
{service="membershipflow-backend", environment="production"}
| json
| level=~"WARN|ERROR"
| event=~"rate_limit_rejected|request_failed"
```

webhook의 자유 문자열을 LogQL에 직접 연결하지 않는다. Loki 응답의 `stats`를 기록하고 검색
bytes·line 수가 정책을 넘으면 결과를 자른 뒤 `LIMIT_EXCEEDED` Evidence를 추가한다.

---

## 14. LLM 연동

### 14.1 호출 위치

LLM API는 incident worker만 호출한다.

```text
Incident worker → Google Gen AI SDK → Gemini Developer API
```

프론트엔드와 Spring Boot에는 LLM API 키를 제공하지 않는다.

### 14.2 공급자 추상화

```python
class LlmClient(Protocol):
    async def analyze(self, evidence: EvidenceBundle) -> AnalysisResult:
        ...
```

도메인 코드는 공급자 SDK의 요청·응답 타입을 직접 참조하지 않는다. 공급자를 변경해도
Evidence Builder, worker, API 계약과 테스트가 바뀌지 않게 한다.

첫 구현 공급자는 Gemini Developer API로 확정한다. 모델명은 Free Tier에서 사용할 수 있는
모델 중 구조화 JSON 출력 검증을 통과한 값을 환경변수로 선택한다. 모델명이 변경되더라도
도메인 코드가 바뀌지 않게 고정 문자열을 코드에 직접 넣지 않는다.

### 14.3 환경변수

```text
LLM_PROVIDER=gemini
GEMINI_API_KEY=
LLM_MODEL=
LLM_TIMEOUT_SECONDS=20
LLM_MAX_OUTPUT_TOKENS=4096
LLM_MAX_TRANSPORT_RETRIES=1
LLM_MAX_ANALYSES_PER_REVISION=1
LLM_CIRCUIT_BREAKER_FAILURES=5
LLM_CIRCUIT_BREAKER_OPEN_SECONDS=300
```

모델명과 API 키는 저장소에 기록하지 않는다. 운영 값은 EC2 환경변수 또는 GitHub
Actions secret으로 주입한다.

Gemini Free Tier 사용 중에는 별도 월 비용 상한을 두지 않는다. 단, Free Tier에도 프로젝트
단위 RPM·TPM·RPD 제한이 있으므로 429 응답 기록, 지수 백오프, 인시던트 중복 제거는
유지한다. 실제 적용 한도는 Google AI Studio의 활성 할당량을 기준으로 확인한다.

Free Tier로 전송한 콘텐츠는 Google 제품 개선에 사용될 수 있으므로 원본 로그를 보내지
않는다. 마스킹과 크기 제한을 통과한 Evidence JSON만 Gemini에 전달한다. 운영 데이터의
외부 활용을 허용할 수 없는 단계에서는 유료 등급 또는 다른 배포 방식을 별도로 검토한다.

### 14.4 첫 버전 호출 제한

- 인시던트당 논리 분석 호출 1회
- timeout 20초
- connect/read timeout, 429, 5xx에 한해 동일 요청 재시도 1회
- Schema 오류는 동일 응답을 자동 보정하지 않고 실패로 기록
- 출력 토큰 최대 800
- Evidence 전체 직렬화 크기 최대 64KB
- 동시 호출 1개
- 연속 일시 실패 5회 시 5분 circuit open

재시도는 1초부터 시작하는 exponential backoff와 jitter를 적용하고 provider의
`Retry-After`가 있으면 우선한다. 인증 오류와 4xx schema 오류는 재시도하지 않는다. Free
Tier의 월 비용 상한은 두지 않지만 요청량, 429, 토큰 사용량은 계속 측정한다.

### 14.5 모델 선택과 변경 절차

모델명은 `LLM_MODEL`로 pin하고 `latest` alias를 사용하지 않는다. 변경 PR에는 다음 결과를
첨부한다.

- 고정 golden incident fixture 전체 통과
- JSON Schema 유효 응답률
- 원인 후보 포함률과 잘못된 확정률
- 입력·출력 토큰과 p50/p95 지연 시간
- 기존 모델 대비 회귀 여부

모델 변경과 prompt 변경을 동시에 하지 않는다. 결과에는 `provider`, `model`,
`prompt_version`, `evidence_schema_version`, `output_schema_version`을 저장해 재현성을 확보한다.

다중 도구 호출 에이전트는 2차 이후 도입한다. 도입 시에도 최대 라운드 3회, 허용 도구
목록, 쿼리별 timeout을 적용한다.

---

## 15. LLM 출력 계약

```json
{
  "status": "ANALYZED",
  "facts": [
    {
      "statement": "429 발생률이 baseline보다 증가했다.",
      "evidenceIds": ["E1"]
    }
  ],
  "hypotheses": [
    {
      "cause": "IP 단위 고정 윈도우 제한 초과",
      "evidenceIds": ["E1", "E3", "E4"],
      "confidence": "HIGH"
    }
  ],
  "excludedCandidates": [
    {
      "cause": "DB 커넥션 풀 고갈",
      "evidenceIds": ["E3"]
    }
  ],
  "missingEvidence": [
    "클라이언트 재시도 횟수"
  ],
  "nextChecks": [
    "동일 IP의 요청 횟수와 분 단위 경계를 확인한다."
  ],
  "rootCauseConfirmed": false
}
```

검증 규칙:

- `status`: `ANALYZED` 또는 `INSUFFICIENT_EVIDENCE`
- `confidence`: `LOW`, `MEDIUM`, `HIGH`
- 모든 사실과 제외 후보는 하나 이상의 `evidenceIds` 필요
- metric `E*` 또는 log `L*` Evidence JSON에 존재하지 않는 ID 사용 금지
- 운영 분석에서는 `rootCauseConfirmed=false`만 허용
- 근거가 없으면 빈 후보 배열과 `INSUFFICIENT_EVIDENCE` 반환

여기서 `AnalysisResult.status=ANALYZED`는 LLM 출력 내용의 상태이고,
`analysis_jobs.status=SUCCEEDED`는 파이프라인 실행 상태다. 두 값을 같은 enum으로 공유하지
않는다.

Gemini structured output은 JSON 문법과 형태를 제한하지만 값의 의미까지 보장하지 않는다.
따라서 Pydantic parsing 이후 아래 의미 검증을 별도로 수행한다.

- 참조한 Evidence ID 존재 여부
- 수치가 Evidence 값과 단위를 그대로 사용하는지
- `NO_DATA`, `QUERY_FAILED`, `LIMIT_EXCEEDED` 증거를 정상 값처럼 해석하지 않는지
- 제외 후보가 실제 반증 Evidence를 참조하는지
- facts와 hypotheses의 문장 길이·개수 제한
- `rootCauseConfirmed` 강제 false

주입 실험의 정답은 평가기에만 저장하고 Gemini 입력에는 포함하지 않는다. 평가기가 분석
결과와 ground truth를 비교하며, 모델 자체가 확정 원인을 선언하지 않는다.

---

## 16. 시스템 지침

LLM에는 아래 원칙을 시스템 지침으로 전달한다.

```text
당신은 읽기 전용 인시던트 분석 보조자다.
입력으로 제공된 Evidence JSON만 근거로 사용한다.
근거가 없는 원인을 사실처럼 단정하지 않는다.
각 사실과 원인 후보에는 Evidence ID를 연결한다.
원인 후보와 확정 원인을 구분한다.
데이터가 부족하면 INSUFFICIENT_EVIDENCE를 반환한다.
서버 재시작, 배포, 데이터 수정 명령을 생성하지 않는다.
출력은 제공된 JSON Schema를 따른다.
```

프롬프트에 서비스 문서 전체나 운영 로그 전체를 삽입하지 않는다. 필요한 설정 값과 장애
분석 규칙만 Evidence 또는 버전이 관리되는 규칙 파일로 전달한다.

프롬프트는 `prompts/incident-analysis-vN.txt`로 버전 관리한다. 사용자 입력이나 로그 문자열을
시스템 지침과 결합하지 않고 JSON data field로만 전달하며, Evidence 안의 명령형 문자열은
신뢰하지 말라는 지침을 포함한다.

---

## 17. 민감정보 처리

### 17.1 전송 금지

- `Authorization`, Cookie, JWT
- OAuth access/refresh token
- Toss secret key, billing key, payment key
- 회원 이메일과 OAuth 식별자
- IP 원문
- 요청·응답 본문
- DB 비밀번호 및 connection string
- Slack Incoming Webhook URL
- 기존 Discord webhook URL

### 17.2 마스킹

- IP: SHA-256 + 운영 전용 salt 또는 완전 제거
- 이메일: 완전 제거
- UUID/order ID: 분석에 필요하지 않으면 제거
- 긴 숫자열·토큰 형태 문자열: `[REDACTED]`
- stack trace: 허용된 패키지명, 예외 클래스, 상위 프레임만 보존

마스킹은 LLM 호출 직전이 아니라 인시던트 수신 시점과 저장 전 시점에 각각 적용한다.

마스킹 테스트에는 JWT, Google OAuth token, Toss payment key, Slack/Discord webhook,
이메일, IPv4/IPv6, JDBC URL fixture를 포함한다. 마스킹 실패 또는 분류되지 않은 자유 텍스트는
외부 전송을 허용하지 않고 `SENSITIVE_DATA_BLOCKED`로 종료한다.

---

### 17.3 비밀정보 수명주기

- `GEMINI_API_KEY`, `SLACK_WEBHOOK_URL`, Grafana HMAC secret은 `.env.example`에 값 없이 이름만 기록
- GitHub Actions secret 또는 EC2의 root-only 환경 파일로 주입
- 애플리케이션 시작 로그, exception, health response에 secret 미출력
- 키 노출 시 즉시 폐기·재발급하고 Git 기록 삭제만으로 해결된 것으로 간주하지 않음
- 운영 초기 90일마다 rotation 가능 여부 점검
- secret scanner를 CI에 포함하고 테스트용 키도 실제 형식과 다른 dummy 사용

---

## 18. 인증과 네트워크

- FastAPI 서비스는 Docker `expose`만 사용하고 host `ports`를 열지 않는다.
- Nginx에는 `/internal/incidents` 라우트를 추가하지 않는다.
- Grafana와 FastAPI는 Docker 내부 서비스 이름으로 통신한다.
- Grafana webhook은 HMAC-SHA256과 timestamp로 인증·무결성·재전송 방지를 검증한다.
- 인증 실패, payload 초과, 허용되지 않은 source는 LLM 호출 없이 거부한다.
- FastAPI 컨테이너의 Prometheus 접근은 읽기 전용 HTTP 요청으로 제한한다.
- Docker network를 `public`, `monitoring`, `incident-internal`로 분리한다.
- Incident API는 `monitoring`과 `incident-internal`에만 연결한다.
- Worker는 `incident-internal`과 제한된 HTTPS egress만 사용한다.
- `/internal/incidents/{id}`는 host port와 Nginx에 공개하지 않는다.

내부 주소 예시:

```text
http://incident-analyzer:8000/internal/incidents
http://prometheus:9090/api/v1/query_range
```

### 18.1 Slack 결과 전송

첫 MVP는 Slack Incoming Webhook을 사용한 단방향 알림으로 제한한다. Slack에서 명령을
입력하거나 대화하는 Bot 기능은 분석 파이프라인의 유용성을 검증한 뒤 추가한다.

Delivery worker는 분석 결과 저장이 완료된 후 Slack 메시지를 전송한다. Slack 전송 실패는
분석 작업 실패로 처리하지 않는다.

환경변수:

```text
SLACK_WEBHOOK_URL=
SLACK_NOTIFICATION_ENABLED=true
```

Slack 메시지 구성:

```text
🚨 CourseApiHighRateLimit

상태: ANALYZED
Incident ID: inc_20260810_000001

관측 사실
• /api/v1/courses 429 증가 (E1)
• Hikari pending 0 (E3)
• HTTP 5xx 증가 없음 (E4)

원인 후보
• IP 단위 고정 윈도우 요청 제한 초과
• confidence: HIGH

추가 확인
• 동일 IP의 분당 요청 횟수 확인
```

Slack 메시지에는 원본 Evidence 전체, IP, 요청 본문, stack trace, 인증정보를 포함하지
않는다. 상세 결과는 MySQL에 보존하고 Slack에는 요약만 전달한다.

Incoming Webhook은 생성 시 선택한 channel에 고정되고 URL 자체가 secret이다. 운영 webhook은
비공개 `#membershipflow-incidents` 전용으로 생성한다. HTTP 200과 `ok`를 모두 확인하며,
400/403/404는 영구 오류, 429/5xx는 일시 오류로 분류한다.

2차 이후 Slack App을 추가할 경우 다음 규칙을 적용한다.

- Slash command 또는 Bot mention 요청의 Slack 서명 검증
- Slack 요청에 3초 안에 접수 응답
- 실제 분석은 기존 worker에서 비동기 실행
- 동일 Slack thread에 분석 결과 작성
- Slack 사용자가 임의 PromQL이나 운영 명령을 실행할 수 없도록 허용 기능 제한

### 18.2 Grafana alert routing

AI 분석 대상 rule에만 다음 label을 부여한다.

```text
ai_analyze=true
analysis_profile=course_429_v1
service=membershipflow-backend
environment=production
route=/api/v1/courses
```

첫 운영 rule은 두 개다.

- `CourseApiHighRateLimit`: Prometheus 429 전용 metric 기반
- `ApplicationErrorBurst`: Loki의 구조화 `level="ERROR"` count_over_time 기반

단일 ERROR는 기존 Discord 알림만 사용하고, 반복 ERROR가 측정된 임계값을 넘을 때만 LLM
분석을 호출한다. 결제·인증처럼 민감도가 높은 event는 log-only 자동 분석 allowlist에 바로
넣지 않고 마스킹 fixture를 통과한 profile만 추가한다.

기존 notification policy의 기본 receiver는 Discord로 유지한다. `ai_analyze=true` 대상에는
Discord receiver와 incident webhook receiver를 함께 포함한 전용 contact point를 사용해
분석기 장애 중에도 원본 alert가 사라지지 않게 한다. group key는 `alertname`, `service`,
`environment`, `route`로 제한하고 `maxAlerts`는 10으로 설정한다.

Grafana provisioning에는 rule UID, contact point UID, notification policy matcher를 코드로
관리한다. HMAC secret과 timestamp header를 활성화하고, 현재 운영 Grafana 버전에서 해당
설정이 지원되는지 확인한 뒤 image version을 pin한다. provisioning 실패 시 Grafana 시작
로그와 contact point test 결과를 배포 실패로 처리한다.

---

## 19. Docker Compose 설계

API와 worker는 같은 immutable image를 사용하되 실행 명령을 분리한다. 운영 배포에서는
`IMAGE_TAG`를 Git SHA로 고정하고 실제 배포 기록에는 image digest를 남긴다.

```yaml
incident-api:
  image: ghcr.io/ohhalim/membershipflow-incident-analyzer:${IMAGE_TAG}
  command: ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
  expose: ["8000"]
  environment:
    INCIDENT_DB_HOST: mysql
    INCIDENT_DB_NAME: membershipflow_incident
    INCIDENT_DB_USERNAME: ${INCIDENT_DB_USERNAME}
    INCIDENT_DB_PASSWORD: ${INCIDENT_DB_PASSWORD}
    DB_POOL_SIZE: 2
    DB_MAX_OVERFLOW: 0
    GRAFANA_HMAC_SECRET: ${GRAFANA_HMAC_SECRET}
    INCIDENT_OPERATOR_TOKEN: ${INCIDENT_OPERATOR_TOKEN}
  read_only: true
  tmpfs: [/tmp]
  cap_drop: [ALL]
  security_opt: [no-new-privileges:true]
  healthcheck:
    test: ["CMD", "python", "-m", "app.healthcheck", "api"]
    interval: 15s
    timeout: 3s
    retries: 3
  depends_on:
    mysql:
      condition: service_healthy
  networks: [monitoring, incident-data]
  restart: unless-stopped

incident-worker:
  image: ghcr.io/ohhalim/membershipflow-incident-analyzer:${IMAGE_TAG}
  command: ["python", "-m", "app.worker"]
  environment:
    INCIDENT_DB_HOST: mysql
    INCIDENT_DB_NAME: membershipflow_incident
    INCIDENT_DB_USERNAME: ${INCIDENT_DB_USERNAME}
    INCIDENT_DB_PASSWORD: ${INCIDENT_DB_PASSWORD}
    DB_POOL_SIZE: 2
    DB_MAX_OVERFLOW: 0
    PROMETHEUS_BASE_URL: http://prometheus:9090
    LOKI_BASE_URL: http://loki:3100
    LLM_PROVIDER: gemini
    GEMINI_API_KEY: ${GEMINI_API_KEY}
    LLM_MODEL: ${LLM_MODEL}
    SLACK_WEBHOOK_URL: ${SLACK_WEBHOOK_URL}
  read_only: true
  tmpfs: [/tmp]
  cap_drop: [ALL]
  security_opt: [no-new-privileges:true]
  depends_on:
    mysql:
      condition: service_healthy
    prometheus:
      condition: service_started
    loki:
      condition: service_healthy
  networks: [monitoring, incident-data]
  restart: unless-stopped

loki:
  image: grafana/loki:${LOKI_VERSION}
  command: ["-config.file=/etc/loki/loki-config.yaml"]
  expose: ["3100"]
  volumes:
    - ./loki/loki-config.yaml:/etc/loki/loki-config.yaml:ro
    - loki_work:/var/lib/loki
  networks: [monitoring]
  healthcheck:
    test: ["CMD-SHELL", "wget -qO- http://localhost:3100/ready | grep -q ready"]
    interval: 15s
    timeout: 3s
    retries: 5
  restart: unless-stopped

alloy:
  image: grafana/alloy:${ALLOY_VERSION}
  command: ["run", "--storage.path=/var/lib/alloy/data", "/etc/alloy/config.alloy"]
  volumes:
    - ./alloy/config.alloy:/etc/alloy/config.alloy:ro
    - backend_logs:/var/log/membershipflow:ro
    - alloy_data:/var/lib/alloy/data
  networks: [monitoring]
  depends_on:
    loki:
      condition: service_healthy
  restart: unless-stopped
```

기존 `mysql` service에는 `incident-data` 내부 network를 추가하고, incident API·worker만
이 network로 DB에 접근한다. Grafana와 API는 `monitoring` network로 연결한다. 어떤 incident
service도 host port를 publish하지 않는다.

기존 backend에는 `backend_logs:/var/log/membershipflow` read-write volume을 추가한다. Loki는
S3를 사용하더라도 WAL·cache용 `loki_work` volume이 필요하다. Grafana에는
`http://loki:3100` read-only datasource provisioning을 추가한다. Alloy와 Loki 관리 UI/API는
host에 publish하지 않는다.

Dockerfile은 non-root UID로 실행하고 dependency lockfile과 hash를 사용한다. 운영 Compose에는
CPU·memory limit, log rotation, stop grace period를 추가한다. DB migration은 새 image로
`alembic upgrade head`를 one-shot 실행한 후 API와 worker를 교체한다.

EC2 자원 사용량을 고려해 초기 제한을 설정한다.

- worker concurrency: 1
- 동시 LLM 호출: 1
- 컨테이너 메모리 목표 상한: 256MB
- API·worker DB pool 합계: 최대 4 connections
- API Uvicorn worker: 1
- graceful shutdown: 30초
- container log: `max-size=10m`, `max-file=3`
- Loki retention: 7일
- Loki query timeout: 5초
- Loki query result: 최대 200 lines, Gemini 전달 최대 16KB
- backend rolling file: 50MB × 3, 최대 24시간

현재 Compose의 `prom/prometheus:latest`, `grafana/grafana:latest`와 새 Loki·Alloy image는
배포 재현성을 위해 검증된 버전으로 pin한다. 버전 갱신은 datasource·대시보드·alert
provisioning 로드와 healthcheck를 검증하는 별도 PR로 진행한다.

---

## 20. 실패 처리

| 실패 | 처리 |
|---|---|
| Grafana webhook 중복 | fingerprint로 기존 incident 반환 |
| 잘못된 인증 | 401, 저장·LLM 호출 없음 |
| 잘못된 입력 | 422, 저장·LLM 호출 없음 |
| Prometheus timeout | Evidence에 조회 실패 기록, 필수 증거면 분석 중단 |
| Prometheus 데이터 없음 | 0으로 변환하지 않고 `NO_DATA` 기록 |
| Alloy 중단 | metric alert 유지, `log_collection_gap` 기록, Alloy 재시작 후 position부터 재개 |
| Loki timeout/5xx | metric Evidence로 계속 분석하고 `LOKI_QUERY_FAILED` 명시 |
| Loki 데이터 없음 | 0건과 수집 gap을 구분, `NO_DATA` 또는 `COLLECTION_GAP` 기록 |
| Loki/S3 저장 실패 | ingestion alert를 기존 Discord로 전달, AI 자기 분석 금지 |
| LLM timeout | 1회 제한 재시도 후 `FAILED` |
| LLM API rate limit | 지연 후 1회 재시도, 계속 실패하면 `FAILED` |
| JSON Schema 불일치 | 결과 폐기 후 `FAILED` |
| Slack 전송 실패 | 분석 결과는 보존, 알림 실패만 별도 기록 |
| FastAPI/worker 재시작 | MySQL의 `PENDING` 및 lease 만료 작업 재처리 |
| MySQL 연결 실패 | webhook 503, 기존 Grafana→Discord 알림 유지, LLM 호출 없음 |

FastAPI, Loki, Alloy, Gemini 또는 Slack 장애 시 Spring Boot와 Grafana의 기존 Discord
알림 경로는 계속 동작해야 한다. log-only alert는 Loki 장애 중 생성되지 않을 수 있으므로
핵심 가용성·DB·host 장애는 Prometheus metric alert를 primary로 유지한다.

---

## 21. 분석기 자체 관측성

FastAPI도 Prometheus 메트릭을 노출한다.

```text
incident_received_total{source,alert_name}
incident_deduplicated_total{alert_name}
incident_analysis_total{status,alert_name}
incident_analysis_duration_seconds
incident_queue_depth{status}
incident_oldest_pending_age_seconds
incident_job_lease_recovery_total
incident_evidence_query_total{status,query_name}
incident_log_lines_selected_total{alert_name}
incident_log_evidence_bytes
llm_request_total{status,provider,model}
llm_request_duration_seconds
llm_input_tokens_total
llm_output_tokens_total
llm_circuit_breaker_open
delivery_total{status,destination}
delivery_duration_seconds{destination}
delivery_oldest_pending_age_seconds
worker_last_run_timestamp_seconds
```

메트릭 라벨에 incident ID, IP, 에러 메시지처럼 cardinality가 높은 값은 사용하지 않는다.

로그에는 `incidentId`, `fingerprint`, `stage`, `status`, `durationMs`만 구조화해 기록하고
Evidence 원문과 LLM API 키는 기록하지 않는다.

분석기 자체 alert:

| Alert | 초기 조건 | 의미 |
|---|---|---|
| `IncidentAnalyzerDown` | ready 0, 5분 | API 또는 DB 연결 불가 |
| `IncidentWorkerStale` | heartbeat 3분 초과 | worker 중단 가능성 |
| `IncidentQueueBacklog` | oldest pending 5분 초과 | 처리 지연 |
| `IncidentAnalysisFailureHigh` | 15분 실패율 30% 초과, 최소 5건 | provider/schema/collector 회귀 |
| `IncidentDeliveryDead` | DEAD 1건 이상 | Slack 설정 또는 영구 오류 |
| `GeminiRateLimited` | 15분 429 3건 이상 | Free Tier 할당량 또는 폭주 |
| `AlloyLogCollectionStale` | backend file 증가 중 Alloy read offset 5분 정체 | 수집 중단 |
| `LokiIngestionErrors` | 15분 push 오류 1건 이상 | Loki/S3 저장 실패 |
| `LokiDiskPressure` | Loki working volume 85% 초과 | WAL/cache disk 위험 |
| `LokiQueryFailureHigh` | 15분 query 실패율 30% 초과 | Evidence log 조회 회귀 |

이 alert들은 AI 분석기로 다시 보내지 않고 기존 Discord 경로로만 전달해 자기 참조 루프를
방지한다.

---

## 22. 테스트 전략

### 22.1 단위 테스트

- Grafana webhook 정규화
- fingerprint 생성과 5분 중복 판정
- 민감정보 마스킹
- Prometheus 응답 정규화
- Evidence ID 생성
- LogQL registry allowlist와 escape
- log signature 생성·중복 축약·정렬
- log sample 16KB 제한과 stack trace truncation
- LLM 응답 Schema 검증
- 존재하지 않는 Evidence ID 거부
- `INSUFFICIENT_EVIDENCE` 처리
- HMAC 정상·변조·만료 timestamp 검증
- firing/resolved episode 전환
- retryable/permanent 오류 분류
- prompt injection 형태의 Evidence 문자열 무시
- 전송 금지 패턴별 마스킹

### 22.2 통합 테스트

- 가짜 Prometheus 서버 → Evidence JSON 생성
- 가짜 Loki 서버 → log Evidence 생성
- 가짜 LLM 서버 → Analysis JSON 저장
- LLM timeout 및 잘못된 JSON 응답
- worker 재시작 후 `PENDING`·lease 만료 작업 재처리
- Slack 실패와 분석 결과 보존 분리
- MySQL Testcontainers 기반 `SKIP LOCKED` 동시 선점
- API와 worker의 동일 job 중복 처리 방지
- analysis 저장과 outbox 생성의 원자성
- migration 빈 DB 적용 및 이전 revision upgrade
- Grafana 실제 webhook fixture의 계약 테스트

### 22.3 장애 주입 테스트

- Prometheus timeout·invalid JSON·부분 metric 부재
- Alloy position 손상·재시작·log rotation
- Loki timeout·429·500·partial stream·out-of-order log
- Loki log에 JWT·이메일·결제키·prompt injection 문자열 포함
- Gemini timeout·429·500·invalid schema·존재하지 않는 Evidence ID
- Slack 400·429·500 및 `Retry-After`
- worker가 LLM 응답 직후 종료되는 경우
- worker가 outbox 선점 직후 종료되는 경우
- MySQL connection kill 및 짧은 중단 후 복구
- disk full에 가까운 MySQL 오류를 영구/일시 오류로 잘못 재시도하지 않는지

### 22.4 운영 전 부하 실험

첫 실험은 로컬 또는 스테이징에서만 수행한다.

```text
대상: GET /api/v1/courses
원인: RateLimitFilter의 IP당 분당 제한 초과
조건: 제한 미만 단계 → 제한 초과 단계
정답: 고정 윈도우 rate limit 초과
```

실험 전에 다음을 기록한다.

- k6 RPS와 실행 시간
- `requests-per-minute` 설정값
- 주입한 원인
- 예상되는 메트릭 변화
- 제외되어야 할 원인 후보

### 22.5 CI 품질 게이트

PR 필수 검사:

- formatter, linter, type checker
- 단위·통합 테스트
- migration upgrade 테스트
- dependency vulnerability 및 secret scan
- Docker image build
- container non-root/read-only smoke test
- OpenAPI와 JSON Schema snapshot diff
- Alloy·Loki config syntax check
- Logback JSON 한 줄 parsing smoke test
- 고정 label cardinality 검사
- golden incident regression

실제 Gemini와 Slack을 호출하는 smoke test는 secret이 있는 수동 workflow 또는 배포 직전
환경에서만 실행한다. fork PR과 일반 CI에는 운영 secret을 제공하지 않는다.

---

## 23. 평가 지표

LLM 기능은 구현 여부가 아니라 측정 결과로 평가한다.

| 지표 | 정의 |
|---|---|
| 증거 수집 성공률 | 필수 Prometheus 쿼리가 모두 성공한 비율 |
| 로그 수집 성공률 | 주입한 WARN·ERROR가 Loki와 Evidence에 포함된 비율 |
| 로그 검색 정밀도 | 선택된 log signature 중 장애 시나리오와 관련된 비율 |
| 로그 축약률 | 원본 lines 대비 Gemini 전달 signature·sample 비율 |
| Schema 유효 응답률 | 추가 보정 없이 검증을 통과한 비율 |
| 원인 후보 포함률 | 주입한 원인이 후보에 포함된 실험 비율 |
| 근거 연결 정확도 | 사실·후보가 올바른 Evidence ID를 참조한 비율 |
| 잘못된 확정률 | 근거 부족인데 `rootCauseConfirmed=true`인 비율 |
| 분석 지연 시간 | 접수부터 결과 저장까지 p50/p95 |
| 수동 분석 시간 | 운영자가 같은 근거를 확인하는 데 걸린 시간 |
| LLM 비용 | 인시던트당 입력·출력 토큰과 API 비용 |
| 중복 차단률 | 반복 alert 중 LLM 호출 없이 제거된 비율 |

이력서와 포트폴리오에는 실제 실험 후 확인된 수치만 기록한다.

---

## 24. 단계별 구현

### Phase 0 — 관측 데이터 사전 확인

- k6로 429 재현
- 429의 Prometheus status·uri 라벨 확인
- Hikari pending 노출 여부 확인
- 부족한 경우 전용 Counter 설계
- 기존 log statement의 IP·memberId·orderId·결제 식별자 audit
- EC2 memory·disk·network headroom 측정
- S3 bucket·IAM role·retention 정책 검토

완료 조건: 민감정보 없는 metric·log fixture로 429 원인을 설명하는 최소 Evidence를 LLM 없이
JSON으로 작성 가능.

### Phase 1 — 구조화 로그와 Loki

- Logback JSON rolling file과 request ID 구현
- 민감 log 제거·마스킹 테스트
- Alloy file tail과 positions volume
- Loki single-binary TSDB + S3 + 7일 retention
- Grafana Loki datasource와 WARN·ERROR dashboard
- Alloy·Loki 자체 Prometheus scrape와 alert

완료 조건: 주입한 WARN·ERROR가 재시작·rotation 이후에도 Loki에서 시간 범위와 event로
검색되고 금지 필드가 존재하지 않음.

### Phase 2 — FastAPI 골격과 저장

- `incident-analyzer/` 생성
- `/health/live`, `/health/ready`, `POST /internal/incidents`, 결과 조회 API
- MySQL 전용 database 상태 저장
- fingerprint와 중복 제거
- 내부 인증과 마스킹
- 가짜 worker 테스트

완료 조건: 테스트 이벤트가 `PENDING → SUCCEEDED`로 변경되고 재시작 후에도 보존.

### Phase 3 — Evidence Builder

- Prometheus client
- Loki query_range client
- alert type별 쿼리 registry
- baseline·incident 구간 비교
- log signature 축약과 sample 마스킹
- Evidence JSON Schema
- 데이터 부재·조회 실패 구분

완료 조건: 실제 429 실험의 metric `E*`와 log `L*` Evidence JSON을 결정적으로 재생성 가능.

### Phase 4 — LLM API

- 공급자 중립 `LlmClient`
- 실제 API client
- 고정 시스템 지침과 출력 Schema
- timeout·재시도·토큰 제한
- 분석 결과·비용·지연 시간 저장

완료 조건: 가짜 원인이 아닌 실제 Evidence만 참조하는 유효 JSON 생성.

### Phase 5 — 자동 알림 연결

- Grafana 429 alert rule
- Loki ERROR burst alert rule
- FastAPI webhook contact point
- 기존 Discord alert 유지
- 분석 완료 Slack Incoming Webhook 메시지

완료 조건: k6 실행 후 수동 API 호출 없이 Slack에서 분석 결과 수신.

### Phase 6 — 평가

- 동일 실험 반복
- 정상·429·5xx·log-only exception 등 고정 시나리오 데이터셋
- 원인 후보 포함률, 로그 검색 정밀도, 잘못된 확정률, 지연 시간, 비용 측정
- 설계 문서와 포트폴리오에 실제 결과 기록

### Phase 7 — 후속 확장

- Tempo trace 연결
- 허용 도구 기반 최대 3라운드 agent
- Grafana 관리자용 분석 UI
- CoinFlow·HomeSweetHome 공용 서비스 분리 검토

---

## 25. MVP 완료 기준

- FastAPI가 별도 컨테이너로 실행되고 외부에 포트가 공개되지 않음
- Grafana 또는 테스트 클라이언트가 인증된 장애 이벤트를 전송할 수 있음
- 접수 API가 LLM 완료를 기다리지 않고 `202` 반환
- 동일 fingerprint의 반복 이벤트가 5분 동안 중복 분석되지 않음
- 실제 Prometheus 데이터로 Evidence JSON 생성
- 실제 Loki WARN·ERROR를 signature·count·sample 형태의 Evidence로 생성
- metric과 log Evidence가 같은 시간 cutoff 사용
- Loki log와 Gemini 입력에 금지 필드가 없음
- LLM이 고정 JSON Schema를 만족
- 모든 사실과 후보가 Evidence ID를 참조
- 근거 부족 시 `INSUFFICIENT_EVIDENCE` 반환
- LLM·FastAPI 실패가 Spring API 응답에 영향을 주지 않음
- Slack에 incident ID, 원인 후보, 근거, 다음 확인 항목 전달
- k6 429 시나리오의 실제 원인과 분석 결과 비교 기록
- 분석 시간과 토큰 사용량 기록

---

## 26. 구현 전 결정 항목

| 항목 | 초기 제안 | 확정 필요 |
|---|---|---|
| LLM 공급자 | Gemini Developer API, 공급자 중립 adapter 유지 | 확정 |
| LLM 모델 | Gemini Free Tier의 구조화 출력 지원 모델 | API 연결 테스트 후 모델명 확정 |
| LLM 월 비용 상한 | Free Tier 사용 중 별도 설정 없음 | 확정 |
| 결과 보존 기간 | 30일 | 운영 디스크 사용량 확인 |
| Slack workspace | 기존 개인 또는 프로젝트 workspace | 사용할 workspace |
| Slack 채널 | 비공개 `#membershipflow-incidents` | 확정 |
| Slack 연동 방식 | Incoming Webhook | Slack App·webhook 생성 |
| Grafana alert 임계값 | 실험 결과로 결정 | Phase 0 측정 필요 |
| 분석 저장소 | 기존 MySQL 8의 `membershipflow_incident` 전용 database | 확정 |
| 로그 수집기 | Grafana Alloy file tail | 확정 |
| Loki 운영 저장소 | TSDB index + private S3 object storage | S3 bucket·IAM 생성 필요 |
| Loki retention | 7일 | EC2·S3 사용량 실측 후 조정 |
| 분석 시간 범위 | `startsAt` 기준 baseline 10분, incident 2~10분 | 초기값 확정, 실험 후 조정 |
| 운영 자동 분석 | 로컬 검증 후 warning 이상 | 운영 적용 승인 |

실제 Gemini 연결 전 나머지 구성요소는 가짜 LLM client로 먼저 구현·검증한다.

---

## 27. 구현 경계

이 문서는 설계 기준이며 현재 구현 완료를 의미하지 않는다. 현재 확인된 구현 범위는
Prometheus, Grafana, heartbeat Gauge, Spring ERROR Discord 알림까지다.

FastAPI, MySQL incident store, Grafana webhook, LLM API, Slack Incoming Webhook, Loki,
Tempo와 정확도·비용·시간 측정 결과는 아직 구현 또는 검증되지 않았다. 각 Phase는 별도
이슈·브랜치·PR로 진행하고, 완료된 검증 결과만 `IMPLEMENTATION.md`와 포트폴리오에
반영한다.

---

## 28. 운영 SLI·SLO

첫 운영 목표는 글로벌 HA가 아니라 현재 단일 EC2에서 본 서비스와 격리된 보조 분석
파이프라인의 신뢰성이다.

| SLI | 초기 SLO | 측정 범위 |
|---|---|---|
| webhook 접수 성공률 | 월 99.5% | 유효 HMAC·허용 payload 중 202 비율 |
| webhook 접수 지연 | p95 500ms 이하 | 수신부터 DB commit까지 |
| log 수집 지연 | p95 30초 이하 | Spring 기록부터 Loki query 가능까지 |
| log 수집 완전성 | 통제 fixture 99% 이상 | 생성한 event 대비 Loki 검색 event |
| 분석 완료율 | 주간 95% 이상 | 필수 dependency 사용 가능 시간의 접수 건 |
| 분석 지연 | p95 90초 이하 | 접수부터 결과 저장까지 |
| Slack 전달 지연 | p95 120초 이하 | 결과 저장부터 `SENT`까지 |
| Schema 유효율 | golden fixture 98% 이상 | 모델·prompt version별 |
| 중복 LLM 호출 | 0건 | 같은 incident analysis revision 기준 |
| 민감정보 외부 전송 | 0건 | 마스킹·egress 감사 테스트 기준 |

Gemini, Slack, Prometheus, MySQL 장애 시간을 숨기지 않고 dependency별 실패율로 별도
표시한다. 월 SLO 미달 시 새 alert type 추가보다 재시도·증거 품질·운영 안정화 작업을
우선한다.

---

## 29. 보존·백업·복구

초기 보존 정책:

- masked incident payload: 30일
- Evidence JSON: 30일
- Analysis JSON과 호출 metadata: 90일
- delivery attempt 상세: 30일
- Loki raw structured log: 7일
- 집계 metric: Prometheus retention 정책 적용

매일 저부하 시간에 500행 단위로 삭제하고 삭제 시간·행 수·실패를 metric으로 기록한다.
원본 개인정보와 요청 본문은 보존 대상이 아니다.

Loki compactor retention을 7일로 설정하고 S3 lifecycle은 삭제 지연·실패에 대비한 14일
안전망으로 둔다. Loki filesystem backend는 local 개발만 허용한다. 로그는 재생성 불가능한
영구 기록으로 취급하지 않으므로 Loki 자체 backup·restore를 RPO 대상으로 두지 않는다.
금지 정보가 발견되면 수집을 먼저 중단하고 관련 stream/time range 삭제, secret rotation,
log statement 수정 순서로 대응한다.

`membershipflow_incident`는 기존 MySQL 백업 작업의 포함 여부를 배포 전 확인한다. 포함되지
않으면 database 단위 dump를 추가한다. 초기 복구 목표는 RPO 24시간, RTO 2시간이며 분기마다
빈 MySQL에 복원해 Alembic revision과 incident 조회를 검증한다.

같은 MySQL 인스턴스를 사용하는 대신 다음 위험을 명시적으로 수용한다.

- MySQL 전체 장애 중에는 새 incident 저장과 AI 분석 불가
- 기존 Grafana→Discord 경로는 MySQL과 무관하게 유지
- incident API·worker DB pool 합계 4개로 제한
- analyzer query에는 짧은 timeout을 적용하고 긴 transaction 금지
- analyzer 도입 후 Hikari pending 또는 MySQL connection 증가가 관측되면 자동 분석을 끄고
  별도 MySQL instance 분리를 우선 검토

---

## 30. 배포·변경·롤백

### 30.1 기능 플래그

```text
INCIDENT_INGEST_ENABLED=false
INCIDENT_ANALYSIS_ENABLED=false
LOKI_EVIDENCE_ENABLED=false
SLACK_NOTIFICATION_ENABLED=false
```

배포 직후에는 모두 `false`로 시작한다. 수동 fixture → 실제 Gemini → Slack → Grafana 자동
접수 순서로 하나씩 활성화한다. 긴급 중단은 애플리케이션 rollback보다 플래그 비활성화를
우선한다.

### 30.2 배포 순서

1. 기존 log statement의 민감정보 audit와 테스트 통과
2. EC2 headroom, S3 bucket, IAM role, Loki retention 확인
3. 구조화 Logback appender 배포 후 local JSON schema 확인
4. pin한 Loki·Alloy 배포와 health/position/ingestion 확인
5. Grafana Loki datasource와 log dashboard 확인
6. MySQL backup 성공과 여유 공간 확인
7. 전용 database·runtime/migration 계정 확인
8. Alembic migration dry-run 및 `upgrade head`
9. Git SHA로 pin한 incident image 배포
10. API liveness/readiness와 worker heartbeat 확인
11. `LOKI_EVIDENCE_ENABLED=true` 수동 fixture 검증
12. 가짜 Gemini·Slack fixture smoke test
13. 실제 Gemini 단일 수동 분석
14. Slack `#membershipflow-incidents` test message 확인
15. Grafana contact point test
16. 429·ERROR burst alert rule을 disabled 상태로 provisioning
17. k6·exception 통제 실험 후 alert rule 활성화

### 30.3 migration과 rollback

- 한 배포에서 destructive schema change 금지
- expand → application 전환 → contract 순서 사용
- 이전 application version과 최소 한 버전 호환
- migration 성공 후 application 배포 실패 시 이전 image로 rollback
- 이전 image와 호환되지 않는 contract 단계는 별도 배포로 분리
- rollback 후 lease 만료 작업과 outbox가 정상 재개되는지 확인
- Grafana rule/contact point는 provisioning 파일 revert 후 reload 검증

### 30.4 배포 증거

배포 기록에는 Git SHA, image digest, migration revision, prompt version, model name, Grafana rule
UID, smoke test incident ID를 남긴다. GitHub Actions 성공만으로 운영 배포 완료를 선언하지
않고 컨테이너 health, worker heartbeat, Slack 수신까지 확인한다.

---

## 31. 운영 런북

| 증상 | 1차 확인 | 안전한 조치 |
|---|---|---|
| Grafana alert는 있는데 incident 없음 | contact point status, HMAC 실패 metric, API readiness | secret·timestamp 설정 확인, ingest 임의 우회 금지 |
| queue 증가 | worker heartbeat, oldest pending, MySQL lock/connection | 분석 플래그 중지, worker 1회 재시작, lease 회수 확인 |
| Gemini 429 증가 | provider 429, request rate, AI Studio quota | circuit open 유지, 재시도 폭주 금지, 중복 원인 확인 |
| Schema 실패 증가 | model·prompt·schema version별 실패율 | 이전 model 또는 prompt version으로 rollback |
| Slack 미수신 | outbox status, HTTP status, channel archive 여부 | webhook 재발급 또는 delivery만 재처리 |
| MySQL 영향 의심 | Hikari pending, MySQL connections, analyzer pool | analyzer 플래그 전체 중지, pool connection 종료 |
| Loki log 누락 | Alloy position/read offset, backend file, Loki ingestion | Loki Evidence 비활성화, metric 분석 유지 |
| Loki/S3 오류 | Loki ready, compactor, IAM denial, S3 request | log-only alert 신뢰 금지, Discord 확인, IAM 임의 확대 금지 |
| 민감정보 노출 의심 | 전송 차단·Loki query·secret scan | log 수집과 분석 중지, stream 삭제·키 폐기·코드 수정 |

런북의 조치는 read-only 확인과 분석기 중단·재처리까지만 포함한다. MembershipFlow 재시작,
DB 데이터 수정, 배포, rollback은 기존 운영 절차와 사람 승인으로 수행한다.

---

## 32. 용량과 확장 전환 기준

초기 구성은 worker 1개와 DB pool 4개로 충분한 낮은 incident 빈도를 전제로 한다. 다음
측정값이 발생할 때만 확장한다.

| 관측값 | 전환 검토 |
|---|---|
| oldest pending p95 60초 초과가 7일 중 3일 발생 | worker 2~3개와 `SKIP LOCKED` 병렬 처리 |
| worker 3개에서도 queue가 5분 이상 증가 | Redis/RabbitMQ 등 외부 queue 검토 |
| analyzer 도입 후 본 서비스 DB pool pending 관측 | incident database를 별도 MySQL instance로 분리 |
| multi-host 또는 HA 요구 | managed MySQL과 외부 queue, API replica 적용 |
| Loki 도입 후 EC2 available memory p95 20% 미만 | Loki managed service 또는 별도 host 분리 |
| Loki ingestion 1GB/day 초과 또는 active stream 급증 | label·log level·중복 message audit |
| log 검색 정밀도 50% 미만 | alert profile LogQL·event taxonomy 개선 |
| trace 없이는 후보 구분이 불가능한 사례 반복 | Tempo 도입 검토 |
| 세 프로젝트가 같은 계약을 재사용 | 별도 repository/service 분리 검토 |

PostgreSQL, Kafka, Kubernetes, vector DB는 현재 문제를 해결하는 필수 요소가 아니다. 위 전환
조건과 측정값 없이 기술 교체를 진행하지 않는다.

---

## 33. 위협 모델

| 위협 | 통제 |
|---|---|
| 위조 webhook | HMAC-SHA256, constant-time 비교, Docker 내부 network |
| replay | signed timestamp 5분 제한, dedup key |
| payload 폭주 | 64KB 제한, alerts 개수 제한, allowlist, 202 이전 최소 처리 |
| PromQL injection | alert registry 고정 template, label escape, 임의 query 금지 |
| LogQL injection | alert registry 고정 template, webhook 문자열 직접 삽입 금지 |
| 조작된 애플리케이션 로그 | 로그를 명령이 아닌 비신뢰 Evidence로 취급, metric 교차 검증 |
| prompt injection | 자유 텍스트 최소화, data field 격리, Evidence 명령 불신 지침 |
| 민감정보 유출 | 2단계 마스킹, deny pattern, 전송 차단 테스트 |
| LLM 환각 | JSON Schema + 의미 검증 + Evidence ID + 확정 원인 금지 |
| SSRF | 고정 Prometheus URL, 사용자 URL 입력 금지 |
| Loki 무인증 API 노출 | host/Nginx 미공개, monitoring network allowlist |
| S3 로그 노출 | public access block, IAM 최소 권한, server-side encryption |
| secret 노출 | secret store, 로그 필터, CI secret scan, rotation |
| 공급망 위험 | dependency lock/hash, image scan, non-root/read-only container |
| 분석기의 본 서비스 영향 | 전용 DB·계정, pool 제한, timeout, kill switch |

---

## 34. 계약과 버전 관리

다음 값은 결과마다 반드시 저장한다.

```text
webhook_payload_version
normalizer_version
structured_log_schema_version
evidence_schema_version
query_registry_version
promql_registry_version
logql_registry_version
prompt_version
output_schema_version
provider
model
application_git_sha
```

계약 변경 PR에는 이전 fixture 호환 여부와 migration 필요 여부를 기록한다. Evidence schema와
output schema는 additive 변경을 우선하고 제거·이름 변경은 major version으로 분리한다.
Golden fixture에는 실제 secret·개인정보를 넣지 않는다.

---

## 35. 운영 활성화 승인 기준

다음 항목을 모두 충족하기 전에는 `INCIDENT_INGEST_ENABLED=true`로 운영 자동 분석을 켜지
않는다.

- [ ] 429 전용 metric 또는 기존 metric 포함 여부 실측
- [ ] Nginx 뒤 client IP 복원과 rate-limit 단위 실측
- [ ] 전체 log parameter audit와 금지 필드 제거
- [ ] Logback JSON schema·rotation·request ID 테스트 통과
- [ ] Alloy restart·position·rotation 수집 테스트 통과
- [ ] Loki S3·compactor·7일 retention·network 격리 확인
- [ ] Grafana Loki datasource와 ERROR burst rule 검증
- [ ] MySQL 전용 database·계정·pool 격리 확인
- [ ] HMAC 정상·변조·replay 테스트 통과
- [ ] lease 만료와 worker 강제 종료 복구 테스트 통과
- [ ] Gemini JSON Schema·의미 검증 golden fixture 통과
- [ ] 민감정보 fixture 100% 차단
- [ ] Slack outbox 재시도·중복 방지 테스트 통과
- [ ] k6 429 및 log-only exception 통제 실험 각 3회 재현
- [ ] 기존 Discord 알림 독립 동작 확인
- [ ] 기능 플래그 중단과 image rollback 연습
- [ ] 배포 후 analyzer 자체 Grafana alert 확인

---

## 36. 공식 연동 문서

- [Grafana webhook notifier와 HMAC payload](https://grafana.com/docs/grafana/latest/alerting/configure-notifications/manage-contact-points/integrations/webhook-notifier/)
- [Gemini structured output](https://ai.google.dev/gemini-api/docs/structured-output)
- [Gemini API rate limits](https://ai.google.dev/gemini-api/docs/rate-limits)
- [Gemini API pricing 및 Free Tier 데이터 조건](https://ai.google.dev/gemini-api/docs/pricing)
- [Slack Incoming Webhooks](https://api.slack.com/messaging/webhooks)
- [Loki HTTP `query_range` API](https://grafana.com/docs/loki/latest/reference/loki-http-api/)
- [Loki storage와 retention](https://grafana.com/docs/loki/latest/operations/storage/)
- [Grafana Alloy `loki.source.file`](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.source.file/)
- [Alloy를 통한 Loki log ingestion](https://grafana.com/docs/loki/latest/send-data/alloy/)
- [MySQL 8.0 locking reads와 `SKIP LOCKED`](https://dev.mysql.com/doc/refman/8.0/en/innodb-locking-reads.html)
- [MySQL 8.0 JSON data type](https://dev.mysql.com/doc/refman/8.0/en/json.html)
