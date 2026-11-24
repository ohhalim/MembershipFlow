## 설계 메모 (#461)

### 1. 장애 재현 계획

**목표**
Paddle이 느릴 때 결제 요청이 톰캣 스레드를 붙잡아 시세 조회까지 느려지는지 확인하고, 서킷브레이커 적용 전후를 같은 조건으로 비교합니다.

**Paddle 지연 흉내**
- `PADDLE_API_BASE_URL`을 로컬 WireMock 주소로 바꿉니다.
- `POST /transactions`는 20초 뒤에 거래 ID를 응답하게 합니다. 현재 read-timeout 15초보다 길게 잡아 결제 요청마다 15초 동안 스레드를 잡고 시간 초과로 끝나게 합니다.

**부하 조건 (k6, 두 시나리오 동시 실행)**
- 시세 조회: `GET /api/v1/courses/{courseId}/prices/latest`, 초당 20건, 로그인 없음
- 결제 거래 생성: `POST /api/v1/subscriptions/paddle/transactions`, 초당 2건, 60초
- 톰캣 최대 스레드를 기본 200에서 20으로 줄인 축소 환경에서 잽니다. 15초씩 잡히는 요청이 초당 2건이면 10초 안에 20개가 모두 찹니다.
- 숫자는 처음 값입니다. 한 번 시험 실행한 뒤 조정하고, 조정한 값은 전후 측정에 똑같이 씁니다.

**테스트 회원 제약 (코드로 확인)**
- 회원 한 명은 진행 중 결제 시도를 하나만 가질 수 있습니다. 실패한 시도는 30분 동안 PENDING으로 남고, 같은 회원의 다음 요청은 Paddle을 부르지 않고 바로 `PAYMENT_IN_PROGRESS`로 끝납니다.
- 그래서 Paddle 호출 1건마다 다른 회원이 필요합니다. 구독 중이 아닌 테스트 회원 150명과 각자의 액세스 토큰을 준비합니다. 토큰 파일은 기존 `k6/payment-test-users.example.json` 형식을 씁니다.
- 측정을 다시 할 때는 PENDING 시도를 지우거나 새 회원을 씁니다.

**재는 값**
- 시세 조회 응답 시간 p95와 실패율
- 결제 거래 생성 응답 시간 분포. 적용 후에는 서킷이 열린 뒤 실패 응답까지 걸리는 시간
- 톰캣 바쁜 스레드 수(`tomcat.threads.busy`). Spring Boot는 톰캣 MBean 등록이 기본으로 꺼져 있어 `server.tomcat.mbeanregistry.enabled=true`가 필요한지 먼저 확인합니다.

**조건 고정**
같은 PC, 같은 데이터, 같은 k6 스크립트, 같은 지연과 스레드 수로 적용 전후를 잽니다. 결과는 `docs/evidence/<날짜>-circuit-breaker/`에 before와 after로 남깁니다.
