# 로컬 Loki 로그 수집 검증

## 범위

- Spring Boot JSON 로그를 이름 있는 Docker 볼륨에 기록
- Alloy가 로그 파일을 읽어 Loki로 전달
- Grafana에서 Loki와 Prometheus 데이터소스 사용
- Loki와 Alloy 상태를 Prometheus에서 수집
- 로컬 단일 노드 검증 전용 구성

운영 환경의 저장소, 인증, 보존 정책은 이 구성에 포함하지 않는다.

## 실행

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.observability.yml \
  up -d --build backend loki alloy prometheus grafana
```

로컬 확인 주소:

- Backend: `http://127.0.0.1:8081`
- Loki readiness: `http://127.0.0.1:3100/ready`
- Alloy readiness: `http://127.0.0.1:12345/-/ready`
- Grafana: `http://127.0.0.1:3001`
- Prometheus: `http://127.0.0.1:9090`

## 로그 조회

Grafana Explore에서 Loki 데이터소스를 선택하고 다음 LogQL을 실행한다.

```logql
{job="membershipflow-backend"} | json
```

요청 ID로 한 요청의 로그만 확인한다.

```logql
{job="membershipflow-backend"} | json | request_id="local-loki-test-0001"
```

오류 로그만 확인한다.

```logql
{job="membershipflow-backend", level="ERROR"} | json
```

`request_id`와 `logger_name`은 검색 가능한 JSON 필드로 유지하되 Loki 라벨로 만들지 않는다.

## 종료

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.observability.yml \
  stop backend loki alloy prometheus grafana
```

이 명령은 로그와 Loki 데이터가 저장된 이름 있는 볼륨을 삭제하지 않는다.
