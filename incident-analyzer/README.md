# MembershipFlow Incident Analyzer

현재 범위는 HMAC 기반 incident 접수, 전용 MySQL 작업 큐, Loki log Evidence 조회,
Gemini 구조화 분석, Evidence·분석 결과 저장까지다. Prometheus Evidence와 Slack 전송은
포함하지 않는다.

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
