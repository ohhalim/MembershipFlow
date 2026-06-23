# MembershipFlow MVP 구현 계획

이 문서는 MembershipFlow MVP의 구현 순서와 책임 경계를 정리한다.

기준 문서 순서:

1. [PRD.md](./02-PRD.md)
2. [Plan.md](./Plan.md) ← 이 문서
3. [ERD.md](./ERD.md)
4. [API.md](./API.md)

---

## 1. 한 줄 정의

> 여러 골프·콘도 회원권 거래소에서 시세를 자동 수집·집계하여 시계열 차트로 제공하고,
> 목표가 도달 시 실시간 WebSocket 알림을 보내며, 토스페이먼츠 빌링으로 프리미엄 구독 결제를 처리하는
> 정보 애그리게이터 백엔드 MVP.

---

## 2. MVP 목표

| 증명 항목 | 구현 방법 |
|---|---|
| 외부 데이터 자동 수집 | Jsoup 크롤러 + @Scheduled 배치, 수집기 인터페이스 추상화 |
| 시계열 저장 | price_history 테이블, (course_id, collected_at) 인덱스 |
| 소스별 가격 비교 | price_history.source_id 보존, 최신가 소스별 조회 API |
| 차트 데이터 API | 기간별 집계 조회 (DAY/WEEK/MONTH) |
| 사용자 식별 | JWT access token에서 현재 memberId 추출 |
| 관심 종목 | watchlist 테이블, 회원별 찜 + 목표가 설정 |
| 실시간 알림 | WebSocket(STOMP), 수집 완료 후 목표가 트리거 체크 |
| 구독 결제 | 토스페이먼츠 빌링 API — **Phase 5** (MVP 제외) |
| 기능 게이팅 | 구독 등급별 분리 — **Phase 5** (MVP 제외) |

---

## 3. MVP 범위

### 포함

- 구글 OAuth2 로그인 + JWT access token 발급/검증
- 현재 로그인 회원 조회
- 회원권 종목 목록 조회 (검색/필터)
- 종목 상세 + 소스별 최신 시세 비교
- 시세 차트 데이터 API (기간별)
- 상승/하락률 랭킹
- 관심 종목 찜 + 목표가 설정
- 목표가 도달 시 WebSocket 알림
- 동부회원권 Jsoup 크롤러 (수집 소스 1개 MVP)
- 시세 자동 수집 배치 (@Scheduled 매시)
- 알림 발송 이력 기록 (중복 발송 방지)
### 제외 (PRD 기준)

- 비밀번호 로그인 / 회원가입 폼
- Refresh token / HttpOnly 쿠키
- Playwright 동적 크롤러 (현재 수집 대상 사이트는 모두 Jsoup으로 처리 가능)
- 수집 소스 10개+ (설계는 N개 가능, 구현은 2개)
- 종목 이름 정규화·중복 제거
- 거래소 아웃링크 수수료
- 관리자 페이지
- Spring Batch (현재 @Scheduled, 추후 전환)
- **토스페이먼츠 빌링 / 구독 / 기능 게이팅 → Phase 5로 분리**
- Kafka / Redis / 서버 분리
- 구독 환불 (취소 시 잔여 기간 사용 허용, 환불 없음)

---

## 4. 핵심 설계 원칙

### 4.1 DB가 Source of Truth

| 테이블 | 역할 |
|---|---|
| `crawl_source` | 수집 소스 (거래소 정보, 수집 방식) |
| `membership_course` | 회원권 종목 마스터 |
| `price_history` | 모든 수집 시세 — append-only 시계열 |
| `member` | 로그인 회원 |
| `watchlist` | 회원별 관심 종목 + 목표가 |
| `alert_log` | 알림 발송 이력 — append-only |

차트·랭킹·최신가는 `price_history`에서 조회 시 집계한다. 별도 집계 테이블은 MVP에서 만들지 않는다.

### 4.2 수집기 추상화 (Collector 패턴)

`PriceCollector` 인터페이스 + 소스별 구현체 + `CollectorRegistry`.
OAuth Extractor 패턴과 동일한 Spring DI List 주입 방식.

```
PriceCollector (interface)
  getSourceName() : String          → crawl_source.name과 매핑
  collect()       : List<CollectedPrice>
```

구현체 추가 = 파일 하나 추가, 기존 코드 수정 없음. OCP 준수.

### 4.3 소스별 가격 보존

`price_history.source_id`를 반드시 보존한다. 같은 종목에 대해 소스별 다른 가격을 "소스별 최신가 비교" API로 제공하는 것이 핵심 차별화이기 때문이다.

집계(평균/최저/최고)는 조회 시 계산한다.

### 4.4 종목 자동 등록 (Auto-register)

크롤링 결과에서 `(name, course_type, membership_type)`으로 종목을 찾아 없으면 INSERT, 있으면 ID를 반환한다. 종목 마스터를 사전에 수동 입력하지 않는다.

`(name, membership_type)` 만으로는 동일 이름의 GOLF/CONDO 종목이나 동명이인 지역 종목이 충돌 가능 → `course_type` 포함.  
`region`까지 포함할지는 실제 수집 결과로 동명 케이스 확인 후 결정.  
MVP에서는 exact string match. 이름 정규화(공백·약칭 통일)는 Phase 2.

**region 정규화 (MVP 필수)**: `region` 필터가 API.md §4.1에 이미 포함되어 있으므로 크롤러가 "경기"/"경기도"/"경기 광주"를 섞어 넣으면 필터가 바로 깨진다.  
MVP에서 최소 정규화: `CollectedPrice.region` 파싱 시 아래 매핑 테이블로 변환 후 저장.
```
"경기도" → "경기",  "경기 광주" → "경기",  "경기 용인" → "경기"
"충청남도" → "충남", "충남 천안" → "충남"
"강원도" → "강원",  ...
```
확정 목록은 초기 크롤 결과를 보고 Enum `RegionNormalizer`로 관리한다. 매핑 미포함 값은 원문 그대로 저장 + 경고 로그.

### 4.5 알림 중복 방지

같은 watchlist에 대해 24시간 내 재발송하지 않는다.

`alert_log`에서 `sent_at > NOW() - INTERVAL 24 HOUR` 조건으로 확인한다.

WebSocket 전송 실패해도 `alert_log` INSERT는 기록한다 (fire-and-forget). 재전송 보장은 Phase 2.

### 4.6 JWT에서 사용자 식별

모든 watchlist·구독 조작은 JWT에서 추출한 `memberId` 기준으로 처리한다. request body나 query string의 `memberId`는 신뢰하지 않는다.

### 4.7 토스페이먼츠 빌링 API 통합

구독 결제는 토스페이먼츠 빌링 API를 사용한다.

**빌링 키 발급 흐름:**
1. 서버가 `customerKey`(UUID, 회원 식별용)를 생성해 DB 저장
2. 프런트가 토스 결제창을 열어 사용자 카드 등록
3. 토스가 우리 콜백 URL(`/api/v1/subscriptions/billing/callback`)로 `authKey` 전달
4. 서버가 `POST https://api.tosspayments.com/v1/billing/authorizations/issue` 호출 → `billingKey` 발급
5. `billingKey`를 `subscription` 테이블에 저장 (외부 노출 안 함)

**자동 결제 흐름:**
- `POST https://api.tosspayments.com/v1/billing/{billingKey}` 호출
- 요청 body: `{ customerKey, amount, orderId(UUID), orderName }`
- 토스가 카드사에 청구 → 성공/실패 응답

**자동 갱신 스케줄러:**
- @Scheduled 매일 자정: `next_billing_at <= NOW()`인 `ACTIVE` 구독 조회
- 토스 빌링 API 호출
- 성공: `next_billing_at += 1 month`, `payment_history` INSERT
- 실패: `subscription.status = PAYMENT_FAILED`, `payment_history(FAIL)` INSERT
- 연속 3회 실패: `subscription.status = SUSPENDED`, WebSocket 알림

### 4.8 구독 등급별 기능 게이팅

| 기능 | 비구독 | 구독 (ACTIVE) |
|---|---|---|
| 종목 목록·검색 | ✅ 제한 없음 | ✅ 제한 없음 |
| 소스별 최신가 비교 | ✅ 제한 없음 | ✅ 제한 없음 |
| 차트 조회 기간 | 최근 7일 | 전체 기간 |
| 목표가 알림 | ❌ | ✅ |
| 찜 개수 | 최대 3개 | 무제한 |
| 랭킹 조회 | ✅ 제한 없음 | ✅ 제한 없음 |

게이팅 구현: `SecurityConfig`에서 구독 여부를 체크하는 `@PreAuthorize` 또는 서비스 레이어에서 `subscription.status == ACTIVE` 확인.

---

## 5. 도메인 모델 요약

### CrawlSource

```text
id, name, base_url, crawl_type(JSOUP|PLAYWRIGHT), active, created_at, updated_at
```

### MembershipCourse

```text
id, name, region, course_type(GOLF|CONDO|FITNESS), membership_type(REGULAR|WEEKDAY|WEEKEND|FAMILY),
active, created_at
unique: (name, membership_type)
```

### PriceHistory

```text
id, course_id → membership_course.id,
source_id → crawl_source.id,
price(원, BIGINT), collected_at
index: (course_id, collected_at), (source_id, collected_at)
```

### Member

```text
id, email, provider(GOOGLE), provider_id(Google sub),
name, profile_image_url, role(USER|ADMIN), created_at, updated_at
unique: email, (provider, provider_id)
```

### Watchlist

```text
id, member_id → member.id, course_id → membership_course.id,
target_price(nullable), alert_yn, created_at, updated_at
unique: (member_id, course_id)
```

### AlertLog

```text
id, watchlist_id → watchlist.id,
triggered_price, source_id → crawl_source.id, sent_at
index: (watchlist_id, sent_at)
```

### SubscriptionPlan

```text
id, code(INDIVIDUAL|CORPORATE), name, price(원/월),
description, active
```

### Subscription

```text
id, member_id → member.id,
plan_id → subscription_plan.id,
status(ACTIVE|CANCELLED|PAYMENT_FAILED|SUSPENDED),
billing_key(토스 빌링 키, 암호화 저장),
customer_key(UUID, 토스 customerKey),
started_at, next_billing_at, cancelled_at(nullable)
unique: member_id (1인 1구독)
```

### PaymentHistory

```text
id, member_id → member.id,
subscription_id → subscription.id,
toss_payment_key(nullable, 성공 시),
toss_order_id(UUID, 우리가 생성),
amount(원), status(SUCCESS|FAIL|CANCELLED),
billed_at, fail_reason(nullable)
index: (member_id, billed_at)
```

---

## 6. 불변조건

### PriceHistory

```text
price > 0
collected_at IS NOT NULL
```

`price_history`는 INSERT만 한다. UPDATE/DELETE 없음.

### Watchlist

```text
target_price IS NULL OR target_price > 0
(member_id, course_id) 중복 없음
```

### AlertLog

```text
같은 watchlist_id에 대해 24시간 내 중복 발송 없음
triggered_price > 0
source_id IS NOT NULL
```

### Subscription

```text
member당 1개의 구독만 존재 (unique: member_id)
billing_key는 외부 API 응답에 절대 포함하지 않음
status 전이: ACTIVE → CANCELLED (취소)
            ACTIVE → PAYMENT_FAILED (결제 실패)
            PAYMENT_FAILED → ACTIVE (재결제 성공)
            PAYMENT_FAILED × 3 → SUSPENDED
cancelled_at IS NOT NULL ↔ status IN ('CANCELLED', 'SUSPENDED')
```

### PaymentHistory

```text
INSERT only. UPDATE/DELETE 없음.
toss_order_id는 UUID로 생성, 중복 없음
amount > 0
status = SUCCESS → toss_payment_key IS NOT NULL
status = FAIL    → fail_reason IS NOT NULL
```

---

## 7. 처리 흐름

### 7.1 수집 처리 (CollectorScheduler → CollectService)

```text
1. @Scheduled(cron = "0 0 * * * *") 매시 0분 실행
   ⚠️ Scheduler 단일 인스턴스 전제: 서버 2대 이상이면 동시 수집 발생. 다중 인스턴스 배포 시 ShedLock 필요 (MVP 이후).

2. CrawlSourceRepository에서 active=true 소스 목록 조회

3. 각 소스별 처리 (소스 단위 격리):
   a. collect_run INSERT (source_id, started_at=now(), status='RUNNING')
   b. CollectorRegistry.getCollector(sourceName) 조회
   c. collector.collect() 호출 → List<CollectedPrice> 반환
      크롤러 매너 (IP 차단 방지):
      - User-Agent: "MembershipFlow-Bot/1.0 (contact: {email})" 헤더 설정
      - 종목 단위 요청 간 delay: Thread.sleep(500~1000ms) + 랜덤 지터
      - 재시도: 연결 오류 시 지수 백오프(1s→2s→4s, 최대 3회)
      - 소스 단위 격리: 한 소스 전체 실패 → collect_run status='FAIL', 다음 소스 계속
      - 종목 단위 격리: 개별 종목 파싱 실패 → skip + fail_count++
   d. 각 CollectedPrice에 대해:
      i.  (name, course_type, membership_type)으로 MembershipCourse 조회
          → 없으면 INSERT IGNORE (동시 수집 race condition 방어)
             ⚠️ INSERT IGNORE 충돌 시 getGeneratedKeys()=0 → 충돌 여부 무관하게 SELECT로 id 재조회
      ii. PriceHistory INSERT:
          course_id, source_id, price(매도 ASK), bid_price(매수 BID, null 가능),
          collect_run_id, collected_at=now()
          → success_count++
   e. collect_run UPDATE (status=SUCCESS/PARTIAL/FAIL, finished_at=now(), 카운트 확정)
      - success=0, fail>0 → FAIL
      - success>0, fail>0 → PARTIAL
      - success>0, fail=0 → SUCCESS

4. @Transactional 범위 내 INSERT 완료 후 COMMIT 시점에 afterCommit 콜백 등록:
   TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
       @Override public void afterCommit() {
           alertService.checkAndNotify();  // COMMIT 이후 호출 보장 (price_history 조회 정합성)
       }
   });
   ⚠️ COMMIT 전에 alertService.checkAndNotify() 직접 호출 금지: price_history가 아직 미커밋이라 이전 시세 조회
```

### 7.2 알림 트리거 처리 (AlertService)

목표가 알림은 구독 ACTIVE 회원만 발송한다.

```text
1. alert_yn=true AND target_price IS NOT NULL인 Watchlist 전체 조회
   JOIN member → JOIN subscription WHERE subscription.status = 'ACTIVE'
   (비구독 회원의 watchlist는 조회 단계에서 제외)
2. 각 Watchlist에 대해:
   a. course_id의 소스별 최신가 조회 (API.md §2.1의 서브쿼리 패턴 사용 — MAX(collected_at) 단순 GROUP BY 금지)
      비교 기준: 소스별 최신가 중 최저가(MIN price) 소스 선택
      이유: 소스 중 하나라도 목표가 이하면 실제 매수 가능하므로
   b. 최저가 소스 price <= target_price 확인 (하락 방향 단방향)
   c. alert_log에서 같은 watchlist_id로 24시간 내 발송 이력 확인
   d. 조건 충족 (가격 도달 AND 24h 내 미발송) 시:
      - SimpMessagingTemplate.convertAndSendToUser(memberId, "/queue/alert", payload)
      - AlertLog INSERT (watchlist_id, triggered_price=최저가, source_id=최저가 소스 id)
3. 개별 Watchlist 오류는 로그만 남기고 다음 처리 계속 (격리)
```

### 7.3 관심 종목 추가 (WatchlistService.add)

```text
1. JWT에서 memberId 추출
2. courseId로 MembershipCourse 조회 → 없으면 COURSE_NOT_FOUND
3. (memberId, courseId) 중복 확인 → 이미 있으면 WATCHLIST_ALREADY_EXISTS
4. Watchlist INSERT (target_price=null, alert_yn=true)
5. 응답 반환
```

### 7.4 관심 종목 목표가 수정 (WatchlistService.updateTarget)

```text
1. JWT에서 memberId 추출
2. watchlistId로 Watchlist 조회 → 없으면 WATCHLIST_NOT_FOUND
3. watchlist.member_id == memberId 확인 → 불일치 시 WATCHLIST_NOT_FOUND (존재 여부 노출 안 함)
4. target_price, alert_yn dirty checking으로 업데이트 (명시적 save 불필요)
5. 응답 반환
```

### 7.5 구독 등록 흐름 (SubscriptionService)

빌링키 발급과 첫 결제를 콜백 핸들러 하나에서 처리한다.
PENDING 상태는 없다. 콜백에서 첫 결제까지 성공해야만 subscription row가 생성된다.
customerKey는 토스가 콜백 쿼리파라미터로 그대로 돌려주므로 DB 임시 저장이 불필요하다.

```text
[Step 1 — 결제창 준비]
1. 클라이언트: GET /api/v1/subscriptions/billing/prepare?planId=1
2. 서버: customerKey(UUID) 생성
         billing_attempt INSERT (member_id=JWT추출, plan_id, customer_key, expires_at=NOW()+30분)
         이유: callback은 customerKey·authKey만 받으므로 member_id·plan_id를 DB에서 조회해야 신뢰성 있음
         → { customerKey, clientKey, planId } 응답

[Step 2 — 카드 인증 (프런트 담당)]
3. 프런트: 토스 SDK requestBillingAuth({ method: "CARD", customerKey, successUrl, failUrl }) 호출
4. 토스: 카드 인증 완료 → successUrl?customerKey={key}&authKey={key} 리다이렉트

[Step 3 — 콜백: 빌링키 발급 + 첫 결제 + subscription INSERT]
5. 서버(GET /api/v1/subscriptions/billing/callback?customerKey=&authKey=):
   0. billing_attempt에서 customer_key=? AND status='PENDING' AND expires_at>NOW() 조회
      → 없으면 BILLING_KEY_ISSUE_FAILED (만료 또는 위조)
      member_id, plan_id 확정
   a. POST https://api.tosspayments.com/v1/billing/authorizations/issue
      { customerKey, authKey }
      → { billingKey, card.number, cardCompany } 수신
   b. toss_order_id(UUID) 생성
      POST https://api.tosspayments.com/v1/billing/{billingKey}
      { customerKey, amount: plan.price, orderId: toss_order_id, orderName: "MembershipFlow 구독" }
      → { paymentKey, approvedAt, totalAmount } 수신
   c. @Transactional:
      - billing_attempt UPDATE status='COMPLETED'
      - subscription INSERT (status=ACTIVE, billing_key=AES암호화(billingKey),
        customer_key, card_number_masked, card_company,
        started_at=approvedAt, next_billing_at=approvedAt+1month)
      - payment_history INSERT (SUCCESS, toss_payment_key=paymentKey,
        amount=totalAmount, billed_at=approvedAt)
6. 응답: 구독 시작 완료
```

결제 실패 시(5b에서 토스 오류): subscription row INSERT 없이 에러 응답. 사용자가 재시도 가능.

**재구독(취소 후 재가입) 정책**:  
`subscription` 테이블에는 `uk_subscription_member`(member_id UNIQUE)가 있어 1인 1행이다.  
CANCELLED 회원이 재구독하면 INSERT 시 unique 충돌 → MVP에서는 **기존 CANCELLED 행을 ACTIVE로 재활성화(UPSERT)**한다.  
구현: `ON DUPLICATE KEY UPDATE status='ACTIVE', billing_key=?, started_at=?, next_billing_at=?, fail_count=0, cancelled_at=NULL`  
재구독 시 기존 payment_history는 보존(재구독 내역과 구분을 위해 payment_history.subscription_billing_cycle 추가는 Phase 2).

### 7.6 구독 자동 갱신 흐름 (BillingScheduler)

```text
@Scheduled(cron = "0 0 0 * * *") 매일 자정

1. subscription에서 status IN ('ACTIVE', 'PAYMENT_FAILED') AND next_billing_at <= NOW() 조회
   ⚠️ PAYMENT_FAILED만 필터하면 재시도 대상이 조회 안 됨 — IN 절로 포함해야 함
   PAYMENT_FAILED는 next_billing_at 그대로 유지 → 매일 자정 재시도 조건 충족

2. 각 구독에 대해 (이중 결제 방어):
   a. 결제 시도 전 subscription에 비관적 락(SELECT ... FOR UPDATE) 획득
      → 인스턴스 재시작/중복 실행 시 동일 구독 이중 청구 방지
   b. toss_order_id(UUID) 생성 (= 토스 orderId, 재시도마다 새 UUID → 멱등키 역할 ✗)
      MVP 단순화: 토스 레벨 멱등성 미보장. payment_history 중복 방어는 락으로 충분히 처리.
   c. POST /v1/billing/{billingKey} 호출 (amount = plan.price)
   d. 성공:
      - subscription.status = ACTIVE (PAYMENT_FAILED였던 경우 복구)
      - subscription.fail_count = 0
      - subscription.next_billing_at += 1 month
      - payment_history INSERT (SUCCESS)
   e. 실패:
      - payment_history INSERT (FAIL, fail_reason)
      - subscription.fail_count += 1
      - fail_count >= 3 → status = SUSPENDED, WebSocket 알림
      - fail_count < 3  → status = PAYMENT_FAILED (next_billing_at 유지 → 내일 자정 재시도)
3. 개별 실패는 격리 (다음 구독 처리 계속)
```

### 7.7 차트 데이터 조회 (PriceService.getChart)

구독 여부에 따라 조회 가능 기간이 다르다. 비구독은 최근 7일로 강제 제한.

```text
1. courseId 유효성 확인
2. JWT에서 memberId 추출 → subscription 조회
3. from 파라미터 결정:
   - 구독 ACTIVE: 클라이언트 요청 from 그대로 사용
   - 비구독(또는 미인증): from = max(요청 from, NOW() - 7 days) 로 강제 clamp
4. price_history에서 (course_id, collected_at BETWEEN from AND to) 조회
5. interval(DAY|WEEK|MONTH) 기준 집계:
   - DAY: 일별 평균 price
   - WEEK: 주별 평균 price
   - MONTH: 월별 평균 price
6. { points: [{date, avgPrice, minPrice, maxPrice}], changeRate, subscriptionRequired: bool } 응답
   (subscriptionRequired=true면 프런트가 "구독하면 전체 기간 조회 가능" 안내 표시)
```

---

## 8. 기술 스택

| 구분 | 기술 | 이유 |
|---|---|---|
| Language | Java 21 | Virtual Thread, Record, CoinFlow와 동일 |
| Framework | Spring Boot 3.5.14 | JPA/Security/WebSocket/Batch 원스톱 |
| DB | MySQL 8.0 + Flyway | 스키마 버전 관리 |
| ORM | Spring Data JPA (Hibernate 6) | 트랜잭션, dirty checking |
| Auth | Spring Security + OAuth2 Client + JJWT 0.12.6 | ✅ Phase 1 완료 |
| 크롤링 | Jsoup 1.18.x + Java HttpClient | 정적 HTML 파싱(동아) + AJAX POST(동부). Playwright 불필요 |
| 스케줄 | @Scheduled (cron) | MVP 단순 배치 |
| 실시간 | Spring WebSocket + STOMP | 목표가 알림 push |
| API 문서 | springdoc-openapi 3 | Swagger UI |
| 테스트 | JUnit5 + Testcontainers | MySQL 컨테이너 통합 테스트 |
| 포트 | 8081 | CoinFlow(8080)와 충돌 방지 |

---

## 9. 구현 단계

### Phase 1. Auth ✅ 완료

목표: 구글 OAuth2 로그인 + JWT 인증 기반 구축.

- Spring Security + OAuth2 Client + JJWT 의존성
- Flyway V1 (코어 스키마), V2 (OAuth 컬럼)
- Google OAuth2 로그인 플로우
- JWT access token 발급/검증
- `GET /api/v1/auth/me`

완료 기준:
- 구글 로그인 후 `?token=` 쿼리 파라미터로 JWT 전달 ✅
- JWT로 `/api/v1/auth/me` 조회 가능 ✅

---

### Phase 2. Collector (수집 파이프라인)

목표: 동부회원권 시세를 자동 수집해 DB에 저장.

- Flyway V3 (membership_type, crawl_type 컬럼 추가)
- `CrawlSource` 엔티티 + Repository
- `CollectRun` 엔티티 + Repository
- `PriceCollector` 인터페이스 + `CollectedPrice` record (price, holes 포함 — bid_price 없음)
- `CollectorRegistry` (Spring DI List 주입)
- `DongbuCollector` (Jsoup 정적 HTML 파싱, 금주시세·홀수, 만원×10000)
- `DongaCollector` (Jsoup 정적 HTML 파싱, 단일 시세, 만원×10000, 이력 백필 포함)
- `MembershipCourse` 엔티티 + Repository
- `PriceHistory` 엔티티 + Repository
- `CollectService` (오케스트레이션 + 저장 + afterCommit 패턴)
- `CollectorScheduler` (@Scheduled)
- 초기 seed data: crawl_source 2건 (동부·동아)

완료 기준:
- 로컬 bootRun 후 수동 트리거로 수집 동작 확인
- price_history에 동부 + 동아 데이터 적재 (단일 price 컬럼)
- membership_course 자동 등록 (holes 포함)
- 가격 단위 검증: DB 값이 ×10,000 변환됐는지 확인 (예: 43800만원 → 438000000원)

---

### Phase 3. 조회 API

목표: 종목 목록·상세·차트·랭킹 API 제공.

- `CourseService` + `CourseController`
  - `GET /api/v1/courses` (목록, 검색, 필터)
  - `GET /api/v1/courses/{id}` (상세 + 최신가)
  - `GET /api/v1/courses/ranking` (상승/하락률)
- `PriceService` + `PriceController`
  - `GET /api/v1/courses/{id}/prices` (차트 데이터)
  - `GET /api/v1/courses/{id}/prices/latest` (소스별 최신가 비교)
- GlobalExceptionHandler + ErrorResponse

완료 기준:
- 수집된 데이터 기반으로 차트 JSON 정상 응답
- 소스별 가격 비교 응답

---

### Phase 4. 관심 종목 + WebSocket 알림

목표: 찜 기능 + 목표가 도달 실시간 알림.

- `Watchlist` 엔티티 + Repository
- `WatchlistService` + `WatchlistController`
  - `GET /api/v1/watchlist`
  - `POST /api/v1/watchlist`
  - `DELETE /api/v1/watchlist/{id}`
  - `PATCH /api/v1/watchlist/{id}`
- `AlertLog` 엔티티 + Repository
- `WebSocketConfig` (STOMP 설정)
- `AlertService` (목표가 체크 + WebSocket 발송)
- `CollectService`에서 수집 완료 후 `AlertService` 호출

완료 기준:
- 브라우저에서 WebSocket 구독 후 목표가 도달 시 메시지 수신
- 24시간 내 중복 발송 없음

---

### Phase 5. 구독 결제 (토스페이먼츠) ← **MVP 외 Phase 2**

> PRD §"MVP에서 제외": "결제 / 프리미엄 구독 제외". Phase 1~4 완료 후 별도 착수.  
> 설계 상세는 이 문서의 §4.7, §7.5, §7.6, ERD.md V4, API.md §8 참조.

목표: 구독 플랜 + 토스페이먼츠 빌링 API 연동.

- Flyway V4 (billing_attempt, subscription_plan, subscription, payment_history) — ERD.md 참조
- `TossPaymentsClient` (RestClient 기반 토스 API 호출)
- `SubscriptionService` + `SubscriptionController`
  - `GET /api/v1/subscriptions/billing/prepare` — billing_attempt 생성 + customerKey 반환
  - `GET /api/v1/subscriptions/billing/callback` — billing_attempt 조회 → billingKey 발급 + 첫 결제 + subscription INSERT
  - `GET /api/v1/subscriptions/me` — 내 구독 상태
  - `DELETE /api/v1/subscriptions` — 구독 취소
  - `GET /api/v1/subscriptions/payments` — 결제 이력
- `BillingScheduler` (@Scheduled 매일 자정 자동 갱신)
- 구독 등급별 기능 게이팅 (watchlist 개수 제한, 알림 제한, 차트 기간 제한)

완료 기준:
- 토스 테스트 키로 카드 등록 → 빌링 키 발급 → 첫 결제 성공
- subscription.status = ACTIVE, payment_history 기록 확인
- 구독 취소 후 next_billing_at까지 ACTIVE 유지

---

### Phase 6. 통합 테스트 / 안정화

목표: MVP 완료 기준 검증.

- 수집 → 저장 → 조회 end-to-end 통합 테스트
- 관심 종목 CRUD + 알림 트리거 테스트
- 잘못된 JWT / 타인 watchlist 접근 거절 테스트
- Swagger UI에서 전체 API 수동 검증

---

## 10. 패키지 구조

```text
com.membershipflow
├── MembershipFlowApplication.java        @EnableScheduling
│
├── common/
│   ├── config/
│   │   ├── SecurityConfig.java           ✅
│   │   └── WebSocketConfig.java          STOMP 엔드포인트
│   ├── security/
│   │   ├── jwt/                          ✅
│   │   └── oauth/                        ✅
│   └── exception/
│       ├── GlobalExceptionHandler.java   @RestControllerAdvice
│       ├── ErrorResponse.java
│       └── ErrorCode.java                에러 코드 enum
│
├── member/                               ✅ Phase 1 완료
│   ├── entity/
│   ├── oauth/
│   ├── repository/
│   ├── service/
│   └── controller/
│
├── collector/                            Phase 2
│   ├── entity/        CrawlSource, CollectRun
│   ├── repository/    CrawlSourceRepository, CollectRunRepository
│   ├── core/
│   │   ├── PriceCollector.java           인터페이스
│   │   ├── CollectedPrice.java           record (price, bidPrice, ...)
│   │   └── CollectorRegistry.java
│   ├── impl/
│   │   ├── DongbuCollector.java          HttpClient AJAX POST (ASK+BID)
│   │   └── DongaCollector.java           Jsoup 정적 HTML (단일 시세 + 이력 백필)
│   ├── service/       CollectService     (afterCommit 패턴 포함)
│   └── scheduler/     CollectorScheduler
│
├── course/                               Phase 3
│   ├── entity/        MembershipCourse
│   ├── repository/    CourseRepository
│   ├── service/       CourseService
│   ├── controller/    CourseController
│   └── dto/           CourseListResponse, CourseDetailResponse
│
├── price/                                Phase 3
│   ├── entity/        PriceHistory
│   ├── repository/    PriceHistoryRepository
│   ├── service/       PriceService
│   ├── controller/    PriceController
│   └── dto/           PricePointDto, PriceChartResponse, LatestPriceResponse
│
├── watchlist/                            Phase 4
│   ├── entity/        Watchlist
│   ├── repository/    WatchlistRepository
│   ├── service/       WatchlistService
│   ├── controller/    WatchlistController
│   └── dto/           WatchlistRequest, WatchlistResponse
│
├── alert/                                Phase 4
│   ├── entity/        AlertLog
│   ├── repository/    AlertLogRepository
│   └── service/       AlertService
│
└── subscription/                         Phase 5
    ├── entity/        SubscriptionPlan, Subscription, PaymentHistory
    ├── repository/    SubscriptionPlanRepository, SubscriptionRepository,
    │                  PaymentHistoryRepository
    ├── client/        TossPaymentsClient   (RestClient 기반)
    ├── service/       SubscriptionService
    ├── scheduler/     BillingScheduler     (@Scheduled 자동 갱신)
    ├── controller/    SubscriptionController
    └── dto/           SubscriptionResponse, PaymentHistoryResponse,
                       BillingPrepareResponse
```

---

## 11. 후순위 확장

아래 항목은 MVP 완료 후 별도 phase에서 다룬다.

### 종목 이름 정규화

같은 골프장을 소스마다 다르게 표기할 때 (예: "레이크사이드CC" vs "레이크사이드 컨트리클럽") 동일 코스로 병합하는 정규화 작업.

### 동아회원권 이력 백필

동아골프 `/api/chart.php?id={custid}&code={code}&type=t` (전체 기간)로 과거 주간 시세 데이터를 일괄 수집할 수 있다.  
DongaCollector 첫 구동 시 각 종목의 custid·code로 이력 API 호출 → price_history 백필.  
중복 방지: `(course_id, source_id, collected_at)` 조합이 이미 있으면 INSERT IGNORE.

### Playwright 동적 크롤러

정적 HTML·AJAX로 파싱 불가능한 JS 렌더링 사이트 추가 시 Playwright(헤드리스 브라우저) 도입.  
현재 수집 대상(동부·동아)은 모두 Jsoup으로 처리 가능하므로 Phase 2 이후로 미룬다.

### Spring Batch 전환

@Scheduled의 재시도/멱등성/이력 한계 → Spring Batch 전환 (JobRepository, Step, RetryPolicy).

### 구독 결제 / 아웃링크 수수료

거래소 아웃링크 송객 수수료 + 개인/법인 프리미엄 구독 기능.

### 알림 채널 확장

WebSocket 외 Push 알림(FCM), 이메일, SMS.

### Reconciliation

price_history와 실제 수집 로그 비교, 누락 감지.
