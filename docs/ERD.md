# MembershipFlow MVP ERD

이 문서는 MembershipFlow MVP의 **목표 스키마** DDL이다.

V1+V2는 이미 DB에 적용된 상태이며, 이 문서의 DDL과 실제 컬럼·타입이 다르다.  
V3가 V1+V2 → 목표 스키마의 갭을 전부 메우는 ALTER를 담당한다 (컬럼 rename/drop/NOT NULL/인덱스 포함).  
V4는 구독 결제 테이블을 신규 생성한다.  
Playwright 동적 크롤러, 종목 이름 정규화, Kafka, Redis, 서버 분리는 MVP 범위에서 제외한다.

> **⚠️ 스키마 드리프트 위험**  
> 이 문서의 `CREATE TABLE` DDL은 어떤 Flyway 스크립트도 그대로 실행하지 않는다.  
> 실제 스키마 = V1 CREATE + V2 ALTER + V3 ALTER + V4 CREATE 누적 결과이며,  
> V3 ALTER가 목표 스키마와 완전히 수렴하는지 시간이 지나면 반드시 검증이 필요하다.  
> 권장: Testcontainers로 V1→V4 풀 체인 실행 후 `SHOW CREATE TABLE` 결과를 이 문서 DDL과 비교하는 테스트 추가.

---

## 설계 기준

- 외부 API는 `courseId`(Long)를 사용하고, 내부 관계는 FK로 연결한다.
- `price_history`는 INSERT만 한다. UPDATE/DELETE 없음 (시계열 원장).
- `alert_log`는 INSERT 위주. `read_at` 컬럼만 PATCH 허용 (미확인 알림 조회 기능 지원). DELETE 없음 (발송 이력 원장).
- `price_history.source_id`를 반드시 보존한다. 소스별 가격 비교가 핵심 차별화이기 때문이다.
- 종목은 (name, membership_type) 복합 unique로 식별한다. 크롤러가 없으면 INSERT한다.
- 알림 중복 방지는 `alert_log`에서 24시간 내 같은 watchlist 이력 확인으로 처리한다.
- 회원 인증은 Google OAuth2 only. password 컬럼은 NULL 허용이며 사용하지 않는다.

---

## 스키마 버전

| Migration | 내용 |
|---|---|
| V1 | 코어 스키마: crawl_source, membership_course, price_history, member, watchlist, alert_log |
| V2 | member 테이블 OAuth 컬럼 추가 (provider, provider_id, name, profile_image_url, role, updated_at) |
| V3 | V1+V2 → 목표 스키마 갭 전체 해소 + collect_run·course_source_mapping 신설 + membership_course holes + price_history collect_run_id |
| V4 | 구독 결제 신규: billing_attempt, subscription_plan, subscription, payment_history |

### V3 실제 ALTER 목록

V1+V2 이후 실제 DB 상태와 목표 스키마 간 갭을 V3 한 번에 해소한다.

```sql
-- ① crawl_source: crawl_type 추가, updated_at 추가
ALTER TABLE crawl_source
    ADD COLUMN crawl_type VARCHAR(20) NOT NULL DEFAULT 'JSOUP' AFTER base_url,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;

-- ② membership_course: type→course_type rename, source_id FK·컬럼 제거,
--    membership_type·holes·active 추가, 인덱스 정비
ALTER TABLE membership_course
    DROP FOREIGN KEY fk_course_source,
    DROP COLUMN source_id,
    CHANGE COLUMN type course_type VARCHAR(20) NOT NULL,
    ADD COLUMN membership_type VARCHAR(20) NOT NULL DEFAULT 'REGULAR' AFTER course_type,
    ADD COLUMN holes           TINYINT UNSIGNED NULL AFTER membership_type,
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE AFTER holes,
    DROP INDEX idx_membership_course_type,
    ADD INDEX idx_membership_course_type (course_type),
    ADD INDEX idx_membership_course_region (region),
    ADD INDEX idx_membership_course_name (name),
    ADD UNIQUE KEY uk_course_name_type_membership (name, course_type, membership_type);
    -- (name, membership_type)만으로는 동일 이름 GOLF/CONDO 또는 지역 다른 동명 종목이 충돌 가능
    -- course_type 포함으로 최소 안전 보장; region까지 포함할지는 실제 수집 결과 보고 결정

-- ③ price_history: source_id NULL → NOT NULL, collect_run_id 추가, 인덱스
ALTER TABLE price_history
    MODIFY COLUMN source_id BIGINT NOT NULL,
    ADD COLUMN collect_run_id BIGINT NULL AFTER price,  -- 어느 수집 실행에서 만들어졌는지
    ADD INDEX idx_price_history_source_time (source_id, collected_at),
    ADD CONSTRAINT fk_price_collect_run FOREIGN KEY (collect_run_id) REFERENCES collect_run (id);

-- ③-a collect_run: 수집 실행 이력 (price_history.collect_run_id 참조원)
-- ③보다 먼저 생성해야 FK가 성립한다.
CREATE TABLE collect_run (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    source_id      BIGINT        NOT NULL,
    started_at     DATETIME      NOT NULL,
    finished_at    DATETIME      NULL,
    status         VARCHAR(20)   NOT NULL DEFAULT 'RUNNING',
    success_count  INT           NOT NULL DEFAULT 0,
    fail_count     INT           NOT NULL DEFAULT 0,
    error_message  VARCHAR(1000) NULL,
    parser_version VARCHAR(20)   NULL,
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_collect_run_source (source_id, started_at),
    CONSTRAINT fk_collect_run_source FOREIGN KEY (source_id) REFERENCES crawl_source (id),
    CONSTRAINT chk_collect_run_status CHECK (status IN ('RUNNING','SUCCESS','PARTIAL','FAIL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
-- ③-b course_source_mapping: 소스별 종목 고유 키 저장 (이력 API 호출 등에 활용)
CREATE TABLE course_source_mapping (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    course_id  BIGINT       NOT NULL,
    source_id  BIGINT       NOT NULL,
    source_key VARCHAR(100) NOT NULL,  -- 동부: "874"(sidx), 동아: "10130:1103"(custid:code)

    PRIMARY KEY (id),
    UNIQUE KEY uk_course_source (course_id, source_id),
    INDEX idx_csm_source (source_id),
    CONSTRAINT fk_csm_course FOREIGN KEY (course_id) REFERENCES membership_course (id),
    CONSTRAINT fk_csm_source FOREIGN KEY (source_id) REFERENCES crawl_source (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ⚠️ V3 스크립트 내 실행 순서: ③-a CREATE collect_run → ③ ALTER price_history → ③-b CREATE course_source_mapping

-- ④ member: provider/provider_id NOT NULL + DEFAULT, updated_at NOT NULL
ALTER TABLE member
    MODIFY COLUMN provider     VARCHAR(20)  NOT NULL DEFAULT 'GOOGLE',
    MODIFY COLUMN provider_id  VARCHAR(255) NOT NULL,
    MODIFY COLUMN updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

-- ⑤ watchlist: updated_at 추가, 알림 체크 인덱스 추가
ALTER TABLE watchlist
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at,
    ADD INDEX idx_watchlist_alert (alert_yn, target_price);

-- ⑥ alert_log: price→triggered_price rename, source_id FK 추가, read_at 추가, 인덱스 정비
ALTER TABLE alert_log
    CHANGE COLUMN price triggered_price BIGINT NOT NULL,
    ADD COLUMN source_id BIGINT NOT NULL AFTER triggered_price,
    ADD COLUMN read_at DATETIME NULL AFTER sent_at,   -- 미확인 알림 REST 조회 지원 (NULL=미확인)
    DROP INDEX idx_alert_log_watchlist,
    ADD INDEX idx_alert_log_watchlist_sent (watchlist_id, sent_at),
    ADD INDEX idx_alert_log_read (watchlist_id, read_at),
    ADD CONSTRAINT fk_alert_source FOREIGN KEY (source_id) REFERENCES crawl_source (id);
```

---

## 상태값 정책

### CourseType

```text
GOLF
CONDO
FITNESS
```

### MembershipType

동부회원권 `type2` 필드 실측값 기반. 크롤러가 `type2` → 아래 enum으로 매핑한다.  
알 수 없는 값은 `REGULAR`로 폴백.

```text
REGULAR       일반/정회원 (기본 폴백)
WEEKDAY       주중
WEEKEND       주말
FAMILY        가족/부부
INDIVIDUAL    개인 (개인회원)
CORPORATE     법인
SHAREHOLDER   주주
PREFERRED     우대
MALE          남자
FEMALE        여자
```

`type2` → `MembershipType` 매핑 예시:
- "일반", "일반 정회원" → REGULAR
- "주중" → WEEKDAY
- "주말" → WEEKEND
- "가족", "부부" → FAMILY
- "개인", "개인회원" → INDIVIDUAL
- "법인" → CORPORATE
- "주주" → SHAREHOLDER
- "우대" → PREFERRED
- "남자", "남성" → MALE
- "여자", "여성" → FEMALE
- 그 외 → REGULAR (로그 남김)

### CrawlType

```text
JSOUP         정적 HTML 파싱 (MVP 기본)
PLAYWRIGHT    동적 브라우저 렌더링 (Phase 2)
```

### MemberRole

```text
USER
ADMIN
```

### OAuth2Provider

```text
GOOGLE
```

### SubscriptionStatus

```text
ACTIVE           정상 구독 중
CANCELLED        사용자 취소 (next_billing_at까지 사용 가능)
PAYMENT_FAILED   결제 실패 (재시도 대기, 3회 미만)
SUSPENDED        구독 정지 (결제 실패 3회 누적)
```

### SubscriptionPlanCode

```text
INDIVIDUAL       개인 구독 (49,000원/월)
CORPORATE        법인 구독 (299,000원/월)
```

### PaymentStatus

```text
SUCCESS
FAIL
CANCELLED
```

---

## MySQL DDL

### 0. collect_run

수집 실행 이력. `price_history.collect_run_id`가 이 테이블을 참조한다.  
어떤 수집 실행에서 어느 가격이 들어왔는지 역추적하고 수집 실패를 모니터링한다.

```sql
CREATE TABLE collect_run (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    source_id      BIGINT        NOT NULL,
    started_at     DATETIME      NOT NULL,
    finished_at    DATETIME      NULL,                       -- 종료 전 NULL
    status         VARCHAR(20)   NOT NULL DEFAULT 'RUNNING', -- RUNNING | SUCCESS | PARTIAL | FAIL
    success_count  INT           NOT NULL DEFAULT 0,
    fail_count     INT           NOT NULL DEFAULT 0,
    error_message  VARCHAR(1000) NULL,                      -- 치명 오류 메시지
    parser_version VARCHAR(20)   NULL,                      -- 크롤러 버전 (코드 배포 추적)
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_collect_run_source (source_id, started_at),
    CONSTRAINT fk_collect_run_source FOREIGN KEY (source_id) REFERENCES crawl_source (id),
    CONSTRAINT chk_collect_run_status CHECK (status IN ('RUNNING','SUCCESS','PARTIAL','FAIL'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

### 1. crawl_source

수집 소스 (회원권 거래소 정보와 수집 방식).

```sql
CREATE TABLE crawl_source (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(100) NOT NULL,           -- 표시명. 예: 동부회원권
    base_url    VARCHAR(500) NOT NULL,           -- 크롤링 시작 URL
    crawl_type  VARCHAR(20)  NOT NULL DEFAULT 'JSOUP',  -- JSOUP | PLAYWRIGHT
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_crawl_source_name (name),
    CONSTRAINT chk_crawl_source_type CHECK (crawl_type IN ('JSOUP', 'PLAYWRIGHT'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

### 2. membership_course

회원권 종목 마스터.

같은 골프장이라도 주중/주말/가족 회원권은 별개 가격을 가지므로 별개 row로 관리한다.  
크롤러가 (name, membership_type)을 추출하면 없는 경우 자동 등록(auto-register)한다.

```sql
CREATE TABLE membership_course (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    name             VARCHAR(200) NOT NULL,           -- 골프장·리조트명. 예: 레이크사이드CC
    region           VARCHAR(100),                    -- 지역. 예: 경기, 충남
    course_type      VARCHAR(20)  NOT NULL,           -- GOLF | CONDO | FITNESS
    membership_type  VARCHAR(20)  NOT NULL DEFAULT 'REGULAR',  -- REGULAR|WEEKDAY|WEEKEND|FAMILY|INDIVIDUAL|CORPORATE|SHAREHOLDER|PREFERRED|MALE|FEMALE
    holes            TINYINT UNSIGNED NULL,           -- 골홀수. GOLF만 의미 있음. 예: 18, 27, 36. V3 ALTER로 추가
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_course_name_type_membership (name, course_type, membership_type),
    INDEX idx_membership_course_type (course_type),
    INDEX idx_membership_course_region (region),
    INDEX idx_membership_course_name (name),
    CONSTRAINT chk_course_type CHECK (course_type IN ('GOLF', 'CONDO', 'FITNESS')),
    CONSTRAINT chk_membership_type CHECK (membership_type IN ('REGULAR','WEEKDAY','WEEKEND','FAMILY','INDIVIDUAL','CORPORATE','SHAREHOLDER','PREFERRED','MALE','FEMALE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

### 3. price_history

시세 시계열 데이터. INSERT only.

소스별로 같은 종목에 다른 가격이 들어올 수 있으며, 이를 그대로 보존한다.  
차트·랭킹·소스별 최신가 조회는 이 테이블에서 집계한다.

두 사이트 모두 **단일 시세**만 제공한다 (ASK/BID 구분 없음).  
동부 `bprice`는 BID가 아니라 **전주시세**이므로 별도 저장 불필요 — 시계열 자체에서 이전값 조회.

```sql
CREATE TABLE price_history (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    course_id       BIGINT      NOT NULL,
    source_id       BIGINT      NOT NULL,
    price           BIGINT      NOT NULL,  -- 현재 시세. 원 단위 (소스 만원값 × 10000). 예: 438000000
    collect_run_id  BIGINT      NULL,      -- 어느 수집 실행에서 저장됐는지 (운영 디버깅용)
    collected_at    DATETIME    NOT NULL,

    PRIMARY KEY (id),
    INDEX idx_price_history_course_time (course_id, collected_at),   -- 차트 조회 핵심 인덱스
    INDEX idx_price_history_source_time (source_id, collected_at),

    CONSTRAINT fk_price_course      FOREIGN KEY (course_id)      REFERENCES membership_course (id),
    CONSTRAINT fk_price_source      FOREIGN KEY (source_id)      REFERENCES crawl_source (id),
    CONSTRAINT fk_price_collect_run FOREIGN KEY (collect_run_id) REFERENCES collect_run (id),
    CONSTRAINT chk_price_positive   CHECK (price > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

### 3-a. course_source_mapping

소스별 종목 고유 키 저장. 크롤러가 API 호출 시 `source_key`로 조회한다.

- 동부: `source_key = "874"` (sidx)
- 동아: `source_key = "10130:1103"` (custid:code) → 차트 API `GET /api/chart.php?id=10130&code=1103&type=y`

```sql
CREATE TABLE course_source_mapping (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    course_id  BIGINT       NOT NULL,
    source_id  BIGINT       NOT NULL,
    source_key VARCHAR(100) NOT NULL,  -- 동부: "874"(sidx), 동아: "10130:1103"(custid:code)

    PRIMARY KEY (id),
    UNIQUE KEY uk_course_source (course_id, source_id),
    INDEX idx_csm_source (source_id),
    CONSTRAINT fk_csm_course FOREIGN KEY (course_id) REFERENCES membership_course (id),
    CONSTRAINT fk_csm_source FOREIGN KEY (source_id) REFERENCES crawl_source (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

### 4. member

로그인 회원. Google OAuth2 only.

```sql
CREATE TABLE member (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    email             VARCHAR(255) NOT NULL,
    password          VARCHAR(255) NULL,          -- OAuth only, 미사용
    nickname          VARCHAR(100),               -- V1 호환 유지
    provider          VARCHAR(20)  NOT NULL DEFAULT 'GOOGLE',  -- V2: GOOGLE
    provider_id       VARCHAR(255) NOT NULL,      -- V2: Google sub (유니크 사용자 식별자)
    name              VARCHAR(100),               -- V2: Google 표시 이름
    profile_image_url VARCHAR(500),               -- V2: Google 프로필 사진 URL
    role              VARCHAR(20)  NOT NULL DEFAULT 'USER',    -- V2: USER | ADMIN
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_member_email (email),
    UNIQUE KEY uk_member_provider (provider, provider_id),
    CONSTRAINT chk_member_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT chk_member_provider CHECK (provider IN ('GOOGLE'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

### 5. watchlist

회원별 관심 종목 + 목표가.

`target_price`가 NULL이면 "찜만". 값이 있으면 "목표가 알림 대상".

```sql
CREATE TABLE watchlist (
    id            BIGINT   NOT NULL AUTO_INCREMENT,
    member_id     BIGINT   NOT NULL,
    course_id     BIGINT   NOT NULL,
    target_price  BIGINT   NULL,          -- NULL: 찜만. 값: 목표가 알림 대상 (원 단위)
    alert_yn      BOOLEAN  NOT NULL DEFAULT TRUE,
    created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_watchlist_member_course (member_id, course_id),
    INDEX idx_watchlist_alert (alert_yn, target_price),   -- 알림 체크 쿼리용

    CONSTRAINT fk_watchlist_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_watchlist_course FOREIGN KEY (course_id) REFERENCES membership_course (id),
    CONSTRAINT chk_target_price CHECK (target_price IS NULL OR target_price > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

### 6. alert_log

알림 발송 이력. INSERT only.

24시간 내 중복 발송 방지에 사용한다.  
WebSocket 전송 실패 여부와 관계없이 INSERT한다 (fire-and-forget).

```sql
CREATE TABLE alert_log (
    id               BIGINT   NOT NULL AUTO_INCREMENT,
    watchlist_id     BIGINT   NOT NULL,
    triggered_price  BIGINT   NOT NULL,    -- 알림 트리거 시점의 시세 (원 단위)
    source_id        BIGINT   NOT NULL,    -- 해당 시세의 수집 소스
    sent_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at          DATETIME NULL,        -- 회원이 REST 조회 시 확인 시각 (NULL=미확인)

    PRIMARY KEY (id),
    INDEX idx_alert_log_watchlist_sent (watchlist_id, sent_at),   -- 24h 중복 체크 쿼리용
    INDEX idx_alert_log_read (watchlist_id, read_at),             -- 미확인 조회용

    CONSTRAINT fk_alert_watchlist FOREIGN KEY (watchlist_id) REFERENCES watchlist (id),
    CONSTRAINT fk_alert_source FOREIGN KEY (source_id) REFERENCES crawl_source (id),
    CONSTRAINT chk_triggered_price CHECK (triggered_price > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

---

### 7. billing_attempt

카드 등록 prepare 시 생성되는 임시 레코드. callback에서 member_id·plan_id를 신뢰성 있게 복원하기 위해 필요하다.  
callback이 받는 값은 `customerKey`·`authKey`뿐이므로 이 테이블 없이는 어느 회원의 어느 플랜 요청인지 알 수 없다.

```sql
CREATE TABLE billing_attempt (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    member_id    BIGINT      NOT NULL,
    plan_id      BIGINT      NOT NULL,
    customer_key VARCHAR(300) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | COMPLETED | FAILED | EXPIRED
    expires_at   DATETIME    NOT NULL,                    -- prepare 후 30분 유효
    created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_billing_attempt_customer_key (customer_key),
    INDEX idx_billing_attempt_member (member_id),
    CONSTRAINT fk_billing_attempt_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_billing_attempt_plan   FOREIGN KEY (plan_id)   REFERENCES subscription_plan (id),
    CONSTRAINT chk_billing_attempt_status CHECK (status IN ('PENDING','COMPLETED','FAILED','EXPIRED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

### 7-a. subscription_plan

구독 플랜 마스터. seed data로 관리.

```sql
CREATE TABLE subscription_plan (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(20)  NOT NULL,           -- INDIVIDUAL | CORPORATE
    name        VARCHAR(100) NOT NULL,           -- 표시명. 예: 개인 구독
    price       INT          NOT NULL,           -- 원/월. 예: 49000
    description VARCHAR(500),
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_subscription_plan_code (code),
    CONSTRAINT chk_plan_code CHECK (code IN ('INDIVIDUAL', 'CORPORATE')),
    CONSTRAINT chk_plan_price CHECK (price > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

### 8. subscription

회원별 구독 상태. 1인 1구독.

`billing_key`는 토스페이먼츠 빌링 키. 자동 결제 시 사용. 외부 API 응답에 절대 포함하지 않는다. **AES-256 암호화 저장 필수.**  
`customer_key`는 UUID로 생성하며 토스 API 호출 시 회원 식별자로 사용한다. **토스 스펙 최대 300자.**  
`card_number_masked`·`card_company`는 빌링키 발급 응답(`card.number`, `cardCompany`)을 저장. 마스킹된 평문이므로 암호화 불필요. 구독 상세 화면 표시용 (예: "현대카드 43301234\*\*\*\*123\*").

```sql
CREATE TABLE subscription (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    member_id           BIGINT       NOT NULL,
    plan_id             BIGINT       NOT NULL,
    status              VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    billing_key         VARCHAR(500) NOT NULL,      -- 토스 빌링 키 (AES-256 암호화 저장, 조회 API 없음 → 발급 즉시 저장)
    customer_key        VARCHAR(300) NOT NULL,      -- UUID, 토스 customerKey (토스 스펙 최대 300자)
    card_number_masked  VARCHAR(50)  NULL,          -- 빌링키 발급 응답 card.number. 예: 43301234****123*
    card_company        VARCHAR(50)  NULL,          -- 빌링키 발급 응답 cardCompany. 예: 현대
    fail_count          INT          NOT NULL DEFAULT 0,   -- 연속 결제 실패 횟수 (0~3)
    started_at          DATETIME     NOT NULL,
    next_billing_at     DATETIME     NOT NULL,
    cancelled_at        DATETIME     NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uk_subscription_member (member_id),      -- 1인 1구독
    UNIQUE KEY uk_subscription_customer_key (customer_key),
    INDEX idx_subscription_billing (status, next_billing_at),  -- 자동 갱신 스케줄러

    CONSTRAINT fk_subscription_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_subscription_plan FOREIGN KEY (plan_id) REFERENCES subscription_plan (id),
    CONSTRAINT chk_subscription_status CHECK (status IN ('ACTIVE', 'CANCELLED', 'PAYMENT_FAILED', 'SUSPENDED')),
    CONSTRAINT chk_fail_count CHECK (fail_count >= 0 AND fail_count <= 3)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

### 9. payment_history

결제 이력. INSERT only.

`toss_order_id`는 서버가 생성하는 UUID. 토스 API 요청 시 `orderId`로 사용.  
`toss_payment_key`는 토스 결제 승인 응답(`paymentKey`)에서 받는 키. 환불/취소 시 사용.  
`billed_at`은 토스 승인 응답의 `approvedAt` 값으로 명시 세팅. 서버 시간(NOW()) 사용 금지.

```sql
CREATE TABLE payment_history (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    member_id         BIGINT       NOT NULL,
    subscription_id   BIGINT       NOT NULL,
    toss_order_id     VARCHAR(64)  NOT NULL,     -- UUID, 우리가 생성 → 토스 API orderId
    toss_payment_key  VARCHAR(200) NULL,         -- 성공 시 토스 승인 응답 paymentKey (환불/취소 시 사용)
    amount            INT          NOT NULL,     -- 원 단위 (토스 응답 totalAmount)
    status            VARCHAR(20)  NOT NULL,     -- SUCCESS | FAIL | CANCELLED
    billed_at         DATETIME     NOT NULL,     -- 토스 승인 응답 approvedAt. DEFAULT 사용 금지
    fail_reason       VARCHAR(500) NULL,         -- 실패 시 토스 failure.message

    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_toss_order (toss_order_id),
    INDEX idx_payment_member_billed (member_id, billed_at),

    CONSTRAINT fk_payment_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_payment_subscription FOREIGN KEY (subscription_id) REFERENCES subscription (id),
    CONSTRAINT chk_payment_status CHECK (status IN ('SUCCESS', 'FAIL', 'CANCELLED')),
    CONSTRAINT chk_payment_amount CHECK (amount > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

---

## 테이블별 역할 요약

| 테이블 | 역할 |
|---|---|
| `crawl_source` | 수집 소스(거래소) 마스터 + 수집 방식 구분 |
| `collect_run` | 수집 실행 이력 (성공/실패 카운트, 파서 버전) |
| `membership_course` | 회원권 종목 마스터 (자동 등록 가능) |
| `course_source_mapping` | 소스별 종목 고유 키 매핑 (크롤러 API 호출 기준 키) |
| `price_history` | 시세 시계열 원장 (INSERT only, 단일 시세 기준) |
| `member` | 로그인 회원 (Google OAuth2) |
| `watchlist` | 회원별 관심 종목 + 목표가 설정 |
| `alert_log` | 알림 발송 이력 원장 (INSERT only, 중복 방지 기준) |
| `subscription_plan` | 구독 플랜 마스터 (seed data) |
| `subscription` | 회원별 구독 상태 + 빌링 키 (1인 1구독) |
| `payment_history` | 결제 이력 원장 (INSERT only) |

---

## 관계 다이어그램

```
crawl_source ──< collect_run
crawl_source ──< course_source_mapping >── membership_course
crawl_source ─────────────┬──< price_history >──── membership_course
                          │         │
                          │    collect_run (FK, optional)
                          │
                          └──< alert_log
                                   │
                               watchlist >── membership_course
                                   │
                                member
```

---

## 구현 시 주의사항

### 가격/시세
- `price_history` 조회 시 `(course_id, collected_at)` 인덱스를 반드시 타도록 WHERE 조건 작성.
- `crawl_source.active` / `membership_course.active` 수명주기:
  - **false가 되는 조건**: MVP에서는 수동 DB 업데이트 only (관리자 API 제외). Phase 2에서 관리자 엔드포인트 추가.
  - **수집기**: `active=true`인 crawl_source만 조회해 collect() 호출.
  - **소스별 최신가 조회**: inactive crawl_source의 price_history도 DB에 잔존하므로 "소스별 최신가" API는 `JOIN crawl_source ON crawl_source.active=true` 필터를 추가해야 죽은 소스가 노출되지 않는다.
  - `membership_course.active=false`: 목록 API에서 제외(`WHERE active=true`). 가격 이력은 보존.
- "소스별 최신가" 조회: `SELECT source_id, price, MAX(collected_at) ... GROUP BY source_id` **패턴은 잘못됨**.  
  `price`가 집계 함수도 GROUP BY 대상도 아니라서 ONLY_FULL_GROUP_BY OFF 시 임의 행 price 반환, ON 시 에러.  
  올바른 패턴: `WHERE (source_id, collected_at) IN (SELECT source_id, MAX(collected_at) FROM price_history WHERE course_id=? GROUP BY source_id)` (API.md §2.1 참조).
- `membership_course` auto-register 시 race condition 가능 → `INSERT IGNORE` 사용.  
  ⚠️ `INSERT IGNORE` 충돌 시 `getGeneratedKeys()=0` — 충돌 여부와 무관하게 반드시 `SELECT`로 id 재조회 후 사용.
- `alert_log` 24h 중복 체크: `SELECT COUNT(*) FROM alert_log WHERE watchlist_id = ? AND sent_at > NOW() - INTERVAL 24 HOUR`.
- **동아 이력 백필** (`DongaCollector` 초기 실행): `/api/chart.php?type=t`로 전체 이력 수집 시 `(course_id, source_id, collected_at)` 중복 체크 필요. `INSERT IGNORE`를 사용하거나 기존 레코드 존재 여부를 SELECT로 확인 후 INSERT. 중복 인덱스가 없으므로 별도 SELECT 확인이 안전하다.
- `watchlist.updated_at`은 Hibernate dirty checking으로 자동 갱신 (`@PreUpdate`).
- `price`는 `BIGINT` (원 단위 정수). 두 사이트 모두 **만원 단위** 제공 → 저장 전 `× 10,000` 변환 필수. 가격 범위: 최소 1,000만(10,000,000) ~ 최대 수백억. BIGINT 충분.
- 동부 API `price` = 금주시세(현재가), `bprice` = 전주시세 — 두 값 모두 **단일 시세 시계열**이므로 `bid_price` 별도 컬럼 없음. 전주 대비 등락은 최근 2개 `price_history` 로우 차이로 계산.
- **`collect_run`**: 수집 실행 시작 시 INSERT(status='RUNNING'), 종료 시 UPDATE(status=SUCCESS/PARTIAL/FAIL, finished_at). `price_history.collect_run_id`는 수집 완료 후 batch UPDATE도 가능하나 INSERT 시 FK 바로 세팅하는 게 일관적.
- **Scheduler 단일 인스턴스**: MVP는 서버 1대 가정. 다중 인스턴스 배포 시 `collect_run`에 동일 시각 중복 실행 row가 쌓임. ShedLock 또는 DB 분산락이 필요하나 MVP에서는 불필요.

### 토스페이먼츠 빌링 (토스 공식 문서 기준)
- `billing_key` **조회 API가 존재하지 않는다.** 빌링키 발급 즉시 DB 저장 필수. 유실 시 구매자에게 재등록 요청해야 함.
- `billing_key`는 AES-256으로 암호화 저장. `customer_key`를 모르면 결제 불가능하므로 두 값을 분리 저장.
- `card_number_masked`·`card_company`는 빌링키 발급 응답(`card.number`, `cardCompany`)에서 추출. 마스킹 평문이므로 암호화 불필요.
- `payment_history.billed_at`은 토스 자동결제 승인 응답의 `approvedAt`(ISO 8601)을 파싱해 저장. `NOW()` 사용 금지.
- `payment_history.toss_payment_key`는 승인 응답의 `paymentKey`. 환불/취소 API(`POST /v1/payments/{paymentKey}/cancel`)에 필요.
- 구독 취소 = 다음 결제일에 자동결제 승인 API를 호출하지 않으면 됨. 토스에 별도 취소 통보 불필요. `billing_key` 삭제는 선택(`DELETE /v1/billing/{billingKey}`).
- 자동결제 스케줄링은 토스가 제공하지 않음. `@Scheduled` + `status=ACTIVE AND next_billing_at <= NOW()` 쿼리로 직접 구현.
- 결제 실패 시 토스 응답 `failure.code`·`failure.message`를 `fail_reason`에 저장.
