# MembershipFlow

골프 회원권 시세 추적 SaaS. 실운영 중 → **[membershipflow.site](https://membershipflow.site)**

회원권 거래소 여덟 곳의 시세를 매일 자동 수집해 차트 시각화, 목표가 도달 시 실시간 WebSocket 알림. 구독(월 10,000원 / 연 90,000원)으로 전체 기간 데이터 및 관심 종목 무제한.

---

## 스택

Spring Boot 3.5 · Java 21 · MySQL · JPA/Hibernate · Flyway · Spring WebSocket/STOMP  
Spring Security + JWT/OAuth2 · Jsoup · TossPayments · Paddle · Micrometer · Testcontainers  
Next.js 14 · TypeScript · Tailwind CSS · SWR  
Docker Compose · GitHub Actions · nginx · Prometheus · Grafana · Loki · Grafana Alloy · AWS EC2

테스트 312개 · 8,433줄 (단위 · 슬라이스 · Testcontainers 통합) · Flyway V1–V23 · 트러블슈팅 문서 17건

---

## 구조

```
nginx (HTTPS)
  ├── /api, /ws, /admin  →  Spring Boot :8081
  └── /*                 →  Next.js :3000

Scheduler (매일 07:00)
  └── Jsoup 크롤링 (동부회원권, 동아골프, 시세닷컴, 에이스회원권, 프리미엄회원권, 회원권 쿨거래, KB회원권거래소, 회원권SEARCH)
        │   ↑ 네트워크 I/O는 트랜잭션 밖
        └── CollectPersistenceService (@Transactional)  →  MySQL
              └── afterCommit() → AlertService → WebSocket 알림

결제 웹훅 (별도 진입점)
  └── Paddle → 서명·발생 시각·내용 검증 → 구독 상태 전이
        └── (payment_provider, external_event_id) 유니크 키로 중복 차단
```

세 축(수집 배치 · 알림 전파 · 구독 결제)이 하나의 애플리케이션 안에 있지만 트랜잭션 경계와 실패 모드가 서로 다르다.

Application EC2(t3.small)에 프론트, 백엔드, DB, node-exporter, mysqld-exporter, Alloy를 배치하고 Prometheus·Grafana·Loki는 독립 Observability EC2로 분리.

---

## 핵심 구현

**트랜잭션 경계**  
수집 진입점 `collectAll()`이 같은 클래스의 `collectOne()`을 호출하고 있었다. Spring AOP 프록시는 외부 호출에만 걸리므로 `@Transactional`이 선언돼 있어도 적용되지 않았고, 커밋이 없으니 커밋 이후에 걸어둔 목표가 알림 트리거도 영영 실행되지 않았다. 증상은 "저장은 정상인데 알림이 한 번도 안 나감"이었다.

어노테이션을 옮기는 것으로는 부족하다고 봤다. 크롤링은 외부 사이트 여덟 곳을 도는 네트워크 I/O이고, 이것까지 트랜잭션에 들어가면 DB 커넥션을 수십 초씩 붙잡는다. DB 저장과 수집 이력 갱신만 [`CollectPersistenceService`](src/main/java/com/membershipflow/collect/service/CollectPersistenceService.java)로 분리해 프록시를 경유하게 하고, 크롤링 I/O는 경계 밖에 뒀다.

정기결제 배치도 같은 종류의 문제였다. 스케줄러 메서드에 트랜잭션이 없는데 그 안에서 `PESSIMISTIC_WRITE` 락 조회를 실행해 `TransactionRequiredException`으로 매 실행 즉시 실패하고 있었다. 락 조회를 일반 조회로 바꾸고, 락이 하던 보호는 결제 직전 재검증 가드로 옮겼다. 트랜잭션 범위를 늘리지 않고 풀었다.

**시세 수집**  
Jsoup으로 여덟 소스 크롤링. 프리미엄회원권·회원권 쿨거래·KB회원권거래소·회원권SEARCH는 기존 종목과 정확히 매칭되는 시세만 저장해 단일 출처 신규 종목 증가를 방지.

동아회원권은 Java 21이 기본 차단하는 구형 DH 키 사이즈 사용. JVM 전체 보안 정책 대신 `@PostConstruct`에서 해당 항목만 런타임 제거로 최소 범위 처리.

Jsoup HTML 파싱 시 `&amp;` → `&` 디코딩 탓에 regex URL 추출 0건. `select("a[href*=...]").attr("href")` 방식으로 전환 해결.

1년치 히스토리 수집은 수십 초 소요로 nginx 504 발생. `@Async` + 즉시 202 반환으로 분리. CoinFlow HTTP 응답 분리 패턴 재적용.

**구독 결제 — 두 경로**  
TossPayments 빌링키 방식이 원래 경로다. 카드 1회 등록 후 월간·연간 주기로 자동결제하고, `BillingScheduler`가 매일 자정 만료 구독을 재청구한다. 연속 3회 실패 시 `SUSPENDED`. 빌링키는 AES-256으로 암호화해 저장한다.

정기결제가 별도 심사와 심사비를 요구해 혼자 만든 서비스로는 넘기 어려운 문턱이었다. 결제 코드를 갈아엎는 대신 `payment_provider` 컬럼과 결제사별 외부 식별자를 분리해 **기존 Toss 경로를 지우지 않고** Paddle 경로를 나란히 추가했다([V23](src/main/resources/db/migration/V23__add_paddle_payment_provider.sql)).

**결제 웹훅은 순서도 횟수도 보장하지 않는다**  
Paddle은 정기 청구를 자기 쪽에서 돌리고 결과만 웹훅으로 보낸다. 재시도 때문에 갱신 알림이 먼저 도착하면 나중에 도착한 옛날 이벤트가 최신 상태를 덮어쓴다. 같은 이벤트가 두 번 오기도 한다.

- **역순**은 도메인에서 막았다. 이벤트 발생 시각(`occurred_at`)이 현재 저장된 외부 갱신 시각보다 과거면 무시한다([`Subscription#isStaleExternalEvent`](src/main/java/com/membershipflow/subscription/entity/Subscription.java)). 최초 결제 확정 전에 도착한 구독 이벤트는 덮어쓰지 않고 `RETRY_REQUIRED`로 되돌려 결제사가 다시 보내게 한다
- **중복**은 애플리케이션 체크로는 동시 요청에서 뚫린다고 보고 DB에 맡겼다. 처리한 이벤트를 별도 테이블에 남기고 `(payment_provider, external_event_id)` 유니크 키를 걸었다. 코드는 뚫려도 유니크 키는 뚫리지 않는다
- 서명은 HMAC-SHA256에 타임스탬프 허용 오차를 함께 검증해 재전송을 막고, 비교는 `MessageDigest.isEqual`로 상수 시간 처리한다. 금액·통화·수금 방식·회원·플랜·가격 ID를 페이로드와 내부 상태로 교차 검증한다

> Paddle Sandbox에서 구독 결제 E2E와 웹훅 회귀 테스트를 통과했다. **Live 키 전환과 실결제 운영은 아직 범위 밖이다.**

**"회원당 진행 중 결제 시도는 한 건"을 스키마로 강제**  
결제 준비 요청이 동시에 들어오면 같은 회원에게 진행 중인 시도가 여러 건 생긴다. 여기서 갈라지면 이중 청구나 고아 결제로 이어진다. MySQL에는 부분 유니크 인덱스가 없어 "상태가 PENDING/PROCESSING인 행만 유일" 같은 제약을 직접 걸 수 없다.

상태에서 파생되는 생성 컬럼을 만들었다. `active_slot`은 진행 중일 때만 `1`이고 그 외에는 `NULL`이다. 여기에 `UNIQUE (member_id, active_slot)`을 걸면 NULL은 유니크 제약에 참여하지 않으므로 **완료·실패 이력은 얼마든지 쌓이면서 진행 중인 행만 회원당 한 건**으로 강제된다([V18](src/main/resources/db/migration/V18__initial_payment_processing_guard.sql)).

이 동작은 JPA나 H2로는 확인되지 않아 Testcontainers로 실제 MySQL 8을 띄워 검증했다.

**게이팅**  
구독 상태에 따라 차트 기간(비구독자 7일 clamp)과 관심 종목 한도(비구독자 3개)를 제한.

**알림**  
수집 완료 후 `afterCommit()` 트리거. 커밋 전 알림 발송 시 DB에 없는 데이터 기준 알림 발생 방지. STOMP `/user/queue/alert` push, `alert_log`로 24시간 중복 방지.

**목록 조회**  
같은 회원권의 "현재가"는 거래소별 최신 행 중 최저가다. 목록에서 종목마다 이걸 구하면 종목 수만큼 쿼리가 나가고, 정렬·랭킹·요약까지 각자 같은 계산을 반복했다.

종목 ID 목록을 받아 한 번에 처리하는 배치 쿼리로 바꿨다. `ROW_NUMBER() OVER (PARTITION BY course_id, source_id ORDER BY collected_at DESC, id DESC)`로 거래소별 최신 행을 뽑고, 그중 최저가 한 행을 대표로 고른다. 목록·상세·알림·랭킹이 같은 대표 행 정의를 쓰게 통일했다. 정렬·랭킹처럼 매번 윈도우 함수 조인이 필요한 경로는 대표 가격을 종목 테이블에 비정규화하고 조회 패턴에 맞는 복합 인덱스를 추가했다([V13](src/main/resources/db/migration/V13__add_latest_price_columns.sql), [V19](src/main/resources/db/migration/V19__price_freshness_indexes.sql)).

"최신"의 정의도 코드가 아니라 쿼리에 박았다. 비활성 거래소, 48시간을 넘긴 가격, 미래 수집 시각은 현재가 계산에서 제외한다. 과거 시세를 백필할 때 미래 시각이 섞여도 현재가가 오염되지 않는다.

> 이 구간은 **운영 트래픽이 작아 부하 테스트로 개선 폭을 측정하지 못했다.** 구조와 실행 계획 기준으로 판단한 개선이고, 측정은 남은 과제다.

**CI/CD**  
main push → 테스트 통과 → Docker 이미지 빌드 → scp로 nginx·Docker Compose·Alloy 설정 EC2 자동 복사 → 백엔드 health gate와 telemetry 상태 검증. 수동 SSH 없이 코드 변경 즉시 반영.

---

## 모니터링

Application EC2의 node-exporter·mysqld-exporter가 호스트와 MySQL 메트릭을 노출하고, Alloy가 백엔드 JSON 로그와 MySQL lock 데이터를 독립 Observability EC2의 Loki로 전송. Prometheus·Grafana·Loki는 Observability EC2에서 운영.

**왜 분리했나**  
운영 EC2(t3.small)가 응답 불능이 된 적이 있다. API·WebSocket·SSH·`docker ps`가 동시에 타임아웃됐는데 AWS 상태 검사는 전부 통과였고 애플리케이션 로그에는 아무 에러도 없었다.

정상 구간과 장애 구간의 호스트 지표를 나란히 놓고 봤다. 가용 메모리 272MiB → 25MiB, CPU iowait 0.02% → 76.65%, load average 0.03 → 47.03, blocked process 0 → 16. 추측을 줄이려고 OOM Killer 기록·하드웨어 오류·미확인 접속 흔적부터 지웠다. MySQL 433MiB · Spring Boot 430MiB · Grafana 189MiB가 1.9GiB 위에 올라가 있고 swap이 없어 완충 구간이 전혀 없었다. 메모리 압박과 높은 디스크 I/O 대기를 주요 원인 후보로 좁혔다. 재시작 이후라 동시대 지표에 공백이 있어 단정하지는 않았다.

swap을 붙이고 호스트 메모리·iowait를 Prometheus 수집 대상에 추가한 뒤, **앱과 관측이 같은 서버에 묶여 있으면 정작 필요한 순간에 관측도 같이 죽는다**는 것을 확인하고 관측 스택을 별도 서버로 분리했다. 감지하는 쪽이 감지당하는 쪽과 같은 실패 영역에 있으면 안 된다.

이 판단이 별도 프로젝트인 [MembershipFlow-observability](https://github.com/ohhalim/MembershipFlow-observability)로 이어졌다. Grafana 경보 웹훅을 받아 근거를 모으고 분석해 Slack까지 보내는 비동기 작업 파이프라인이다.

상세 배포 구조와 환경변수는 [`docs/DEPLOYMENT.md`](docs/DEPLOYMENT.md) 참고.

---

## 로컬 실행

```bash
cp .env.example .env  # DB, JWT, Google OAuth, TossPayments, Paddle(Sandbox) 키 설정
docker compose up -d
```

---

## 관련 프로젝트

- [MembershipFlow-observability](https://github.com/ohhalim/MembershipFlow-observability) — 이 서비스의 관측 전용 서버. 경보 수집 → 근거 정규화 → 분석 → 발송 파이프라인
- [CoinFlow](https://github.com/ohhalim/CoinFlow) — 비동기 응답 분리, STOMP 구조 원본
- [HomeSweetHome](https://github.com/ohhalim/HomeSweetHome-backend) — TossPayments 결제 정합성 원본
