# MembershipFlow Incident Analyzer

현재 범위는 전용 MySQL database, migration, incident·analysis job 저장 트랜잭션,
health endpoint까지다. Loki evidence 조회, Gemini 분석, Slack 전송은 포함하지 않는다.

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

3. 상태 확인

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

API는 로컬에서도 host port를 열지 않고 `incident-data` 내부 network에서만 실행한다.

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
incident·job 원자 저장을 검증한다.

Docker Desktop for Mac에서 Docker socket 자동 탐지가 실패하면 다음과 같이 실행한다.

```bash
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock .venv/bin/pytest
```

`requirements.in`, `requirements-dev.in`은 직접 의존성 목록이고, 실제 설치에는
해시가 포함된 `requirements.lock`, `requirements-dev.lock`만 사용한다.
