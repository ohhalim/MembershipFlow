# 결제 준비 API 부하 테스트

## 목적

- `POST /api/v1/subscriptions/prepare`의 회원 행 잠금과 활성 결제 시도 재사용 검증
- 동시 요청에서 동일 회원에게 여러 `billing_attempt`가 생성되지 않는지 확인
- p95·p99 응답시간, 실패율, 응답 계약 측정
- 부하 테스트와 AI 인시던트→Slack 전달 검증 분리

## 테스트 범위

`prepare`는 `billing_attempt`를 저장하지만 TossPayments API를 호출하지 않는다. 다음 경로는
실결제 가능성이 있으므로 k6 테스트에서 제외한다.

- `GET /api/v1/subscriptions/callback`
- `SubscriptionService.processBilling()`
- Toss 빌링키 발급·승인 API

운영 `membershipflow.site`에서는 `smoke`만 허용한다. `contention`, `load`는 rate limiter를
비활성화한 로컬 또는 별도 staging에서 실행한다. 운영 서버는 IP당 분당 120회 제한이 있어
한 곳에서 발생시키는 k6 트래픽은 애플리케이션 동시성보다 rate limiter를 먼저 측정한다.

## 테스트 사용자 준비

1. 구독하지 않은 전용 테스트 계정 준비
2. 짧은 수명의 access token 발급
3. 예제 파일 복사

```bash
cp k6/payment-test-users.example.json k6/payment-test-users.json
chmod 600 k6/payment-test-users.json
```

`payment-test-users.json`은 `.gitignore` 대상이다. 실제 사용자 토큰, 운영 고객 토큰, Toss
키는 사용하지 않는다. 한 계정만 넣으면 같은 회원의 lock 경합을 측정하고, 여러 전용 계정을
넣으면 회원별 결제 준비 처리량을 측정한다.

## 실행

로컬 smoke:

```bash
BASE_URL=http://localhost:8081 \
PLAN_ID=1 \
PROFILE=smoke \
TOKENS_FILE=./k6/payment-test-users.json \
k6 run k6/payment-prepare-load.js
```

동일 회원 lock 경합:

```bash
BASE_URL=http://localhost:8081 \
PLAN_ID=1 \
PROFILE=contention \
TOKENS_FILE=./k6/payment-test-users.json \
k6 run k6/payment-prepare-load.js
```

staging 부하:

```bash
BASE_URL=https://staging.example.com \
PLAN_ID=1 \
PROFILE=load \
TOKENS_FILE=./k6/payment-test-users.json \
k6 run k6/payment-prepare-load.js
```

기본 임계값:

- check 성공률 `> 99%`
- prepare 실패율 `< 1%`
- prepare p95 `< 500ms`
- prepare p99 `< 1s`
- 기존 `customerKey` 재사용 실패 `0건`
- 응답에 `billingKey` 노출 `0건`

## DB 사후 검증

테스트 계정별 활성 시도는 최대 1건이어야 한다.

```sql
SELECT member_id, COUNT(*) AS active_attempts
FROM billing_attempt
WHERE status IN ('PENDING', 'PROCESSING')
GROUP BY member_id
HAVING COUNT(*) > 1;
```

결과가 0건이어야 한다. 테스트 전후 `billing_attempt`, Hikari pending, HTTP 5xx와 응답시간을
같은 `RUN_ID`와 시간 범위로 기록한다.

## Slack 경로 검증

k6 임계값 실패는 k6 프로세스의 판단이며 Spring `ERROR` 로그가 아니다. 따라서 시스템이
부하를 정상 처리하면 Slack이 오지 않는 것이 정상이다. Slack 확인을 위해 결제 오류나 실제
Toss 실패를 억지로 만들지 않는다.

배포된 incident API 컨테이너 안에서 합성 인시던트 한 건을 명시적으로 전송한다.

```bash
docker exec membershipflow-incident-api-1 \
  python -m app.operations.synthetic_incident \
  --run-id payment-smoke-YYYYMMDD-HHMM \
  --confirm
```

이 명령은 HMAC 검증→MySQL job→Loki Evidence 조회→Gemini 분석→Slack 전송 경로를 한 번
실행한다. 실제 오류 로그가 없다면 `INSUFFICIENT_EVIDENCE`가 정상 결과다. 명령 실행은 Gemini
호출과 외부 Slack 전송을 발생시키므로 운영 실행 전에 별도 승인을 받는다.
