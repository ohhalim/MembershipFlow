# MembershipFlow Incident Analyzer

현재 범위는 HMAC 기반 incident 접수, 전용 MySQL 작업 큐, Loki log Evidence 조회,
Gemini 구조화 분석, Evidence·분석 결과 저장까지다. Prometheus Evidence와 Slack 전송은
포함하지 않는다.

## 디렉터리 구조

```text
app/
├── api/             # FastAPI 요청·응답과 라우팅
├── collectors/      # Loki 등 외부 Evidence 수집
├── domain/          # 인시던트·Evidence·분석 결과 규칙
├── llm/             # LLM 공통 계약과 Gemini 구현
├── persistence/     # DB 연결·SQLAlchemy 모델·저장소
├── security/        # 웹훅 서명 검증
├── config.py        # 환경 설정
├── main.py          # API 실행 진입점
└── worker.py        # 분석 worker 실행 진입점
```

## 로컬 실행

1. database·전용 계정 생성과 migration 적용

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.incident.yml \
  --profile incident-setup \
  up --build incident-migrate
```

2. API 실행

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.incident.yml \
  up -d --build incident-api
```

3. 분석 worker 실행

`.env`에 새로 발급한 `GEMINI_API_KEY`와 실제 사용 가능한 고정 `LLM_MODEL`을 설정한 뒤
Loki가 포함된 오버레이와 함께 실행한다. API key와 모델명은 저장소에 커밋하지 않는다.

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.observability.yml \
  -f docker-compose.incident.yml \
  --profile incident-worker \
  up -d --build incident-worker
```

4. 상태 확인

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.incident.yml \
  exec incident-api python -m app.healthcheck live

docker compose \
  -f docker-compose.yml \
  -f docker-compose.incident.yml \
  exec incident-api python -m app.healthcheck ready
```

API는 host port를 열지 않고 `incident-ingress`에서 Grafana webhook만 받는다. worker만
Loki와 Gemini에 접근하며, Spring Boot와 프론트엔드에는 Gemini API key를 제공하지 않는다.

운영 CD는 동일 Dockerfile을 `membershipflow-incident-analyzer:<git-sha>`로 한 번 빌드해
API, migration, worker가 같은 불변 이미지를 사용하도록 배포한다. DB bootstrap과 migration
완료 후 API·worker를 기동하며, API readiness와 Loki readiness를 모두 통과해야 배포 성공으로
처리한다.

현재 EC2 자원 보호를 위한 컨테이너 메모리 상한은 API·worker 각 160MB, Loki 192MB,
Alloy 128MB다. 메모리 상한 초과로 analyzer가 중단되어도 기존 backend·MySQL 컨테이너의
메모리 제한이나 재시작 정책은 변경하지 않는다.

로컬 기본 비밀번호는 Compose overlay에만 존재한다. 운영에서는
`INCIDENT_DB_RUNTIME_PASSWORD`, `INCIDENT_DB_MIGRATION_PASSWORD`를 base64url 문자로 생성해
환경변수로 주입한다.

## 테스트

```bash
python -m venv .venv
.venv/bin/pip install --require-hashes -r requirements-dev.lock
.venv/bin/pytest
```

통합 테스트는 일회용 MySQL 8 Testcontainer를 사용해 migration, 권한 격리,
incident·job 원자 저장, worker 상태 전이, Evidence·분석 결과 저장을 검증한다. Loki와
Gemini는 가짜 응답으로 timeout, 5xx, 데이터 부재, 잘못된 JSON, 잘못된 Evidence ID를
검증하며 일반 CI에서 실제 Gemini API를 호출하지 않는다.

Docker Desktop for Mac에서 Docker socket 자동 탐지가 실패하면 다음과 같이 실행한다.

```bash
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock .venv/bin/pytest
```

`requirements.in`, `requirements-dev.in`은 직접 의존성 목록이고, 실제 설치에는
해시가 포함된 `requirements.lock`, `requirements-dev.lock`만 사용한다.
