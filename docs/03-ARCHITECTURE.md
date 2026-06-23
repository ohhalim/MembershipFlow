# 03 — 아키텍처 (상세 설계)

> 최초 스캐폴딩 문서에서 전면 재작성. 2026-06-22 auth 구현 완료 이후 기준.

---

## 1. 기술 스택

| 분류 | 선택 | 이유 |
|---|---|---|
| 언어/런타임 | Java 21 | Virtual Thread, Record, CoinFlow와 동일 |
| 프레임워크 | Spring Boot 3.5.14 | JPA/Security/Batch/WebSocket 원스톱 |
| DB | MySQL 8.0 + Flyway | 스키마 버전 관리 필수 (마이그레이션 히스토리) |
| ORM | Spring Data JPA (Hibernate 6) | – |
| 인증 | Spring Security + OAuth2 Client + JJWT 0.12.6 | ✅ 구현 완료 |
| 크롤링 | Jsoup (정적 HTML + AJAX) | 소스별 교체 가능하게 추상화. 동부·동아 모두 Jsoup으로 수집 가능 |
| 배치/스케줄 | @Scheduled (MVP) → Spring Batch (Phase 2) | MVP는 단순 스케줄로 충분 |
| 실시간 알림 | Spring WebSocket + STOMP | 목표가 도달 알림 |
| API 문서 | springdoc-openapi 3 | – |
| 포트 | 8081 | CoinFlow(8080)와 충돌 방지 |

---

## 2. 전체 시스템 흐름

```
[External Sites]          [Backend: 8081]                    [Frontend: 3000]
 동부회원권.com  ──Jsoup──▶  CollectorScheduler
 동아회원권.com  ──Jsoup────▶  │
                               ▼
                         CollectService
                          │   saves
                          ▼
                     price_history ──────────────────────────▶ PriceController
                     membership_course ──────────────────────▶ CourseController
                          │
                          ▼ (after collect)
                     AlertService
                      - target_price 비교
                      - WebSocket push ─────────────────────▶ /ws STOMP

[Google OAuth2]────────▶ CustomOAuth2UserService
                               │ JWT 발급
                               ▼
                         /auth/callback?token=... ─────────▶ Frontend callback
```

---

## 3. 패키지 구조

```
com.membershipflow
├── MembershipFlowApplication.java         @EnableScheduling
│
├── common/
│   ├── config/
│   │   ├── SecurityConfig.java            ✅ 완료
│   │   ├── WebSocketConfig.java           STOMP 엔드포인트 등록
│   │   └── SchedulingConfig.java          @EnableScheduling 분리 (또는 Main에 붙임)
│   ├── security/
│   │   ├── jwt/                           ✅ 완료
│   │   └── oauth/                         ✅ 완료
│   └── exception/
│       ├── GlobalExceptionHandler.java    @RestControllerAdvice
│       └── ErrorResponse.java             { code, message, timestamp }
│
├── member/                                ✅ 완료
│   ├── entity/      Member, MemberRole, OAuth2Provider, OAuth2UserPrincipal
│   ├── oauth/       Extractor 패턴
│   ├── repository/  MemberRepository
│   ├── service/     AuthService
│   └── controller/  AuthController (GET /api/v1/auth/me)
│
├── course/
│   ├── entity/      MembershipCourse
│   ├── repository/  CourseRepository
│   ├── service/     CourseService
│   ├── controller/  CourseController
│   └── dto/         CourseListResponse, CourseDetailResponse
│
├── price/
│   ├── entity/      PriceHistory
│   ├── repository/  PriceHistoryRepository
│   ├── service/     PriceService          (차트 데이터, 최신가)
│   ├── controller/  PriceController
│   └── dto/         PricePointDto, PriceChartResponse
│
├── collector/
│   ├── entity/      CrawlSource
│   ├── repository/  CrawlSourceRepository
│   ├── core/
│   │   ├── PriceCollector.java           인터페이스
│   │   ├── CollectedPrice.java           record (수집 결과 DTO)
│   │   └── CollectorRegistry.java        Spring DI List 주입 → Map<sourceName, collector>
│   ├── impl/
│   │   ├── DongbuCollector.java          Jsoup AJAX 크롤러 (동부회원권, ASK+BID)
│   │   └── DongaCollector.java           Jsoup 정적 HTML 크롤러 (동아회원권, 단일 시세)
│   ├── service/
│   │   └── CollectService.java           수집 오케스트레이션 + 저장
│   └── scheduler/
│       └── CollectorScheduler.java       @Scheduled(cron = "0 0 * * * *") 매시
│
├── watchlist/
│   ├── entity/      Watchlist
│   ├── repository/  WatchlistRepository
│   ├── service/     WatchlistService
│   ├── controller/  WatchlistController
│   └── dto/         WatchlistRequest, WatchlistResponse
│
├── alert/
│   ├── entity/      AlertLog
│   ├── repository/  AlertLogRepository
│   ├── service/     AlertService          (목표가 체크 + WebSocket 발송)
│   └── scheduler/   AlertScheduler        (수집 완료 후 호출)
│
└── subscription/
    ├── entity/      Subscription, PaymentHistory, MembershipPlan
    ├── repository/  SubscriptionRepository, PaymentHistoryRepository
    ├── service/     SubscriptionService   (빌링 키 발급 + 첫 결제 + subscription INSERT)
    │               BillingScheduler       (@Scheduled 자동 갱신)
    ├── controller/  SubscriptionController
    └── dto/         PrepareResponse, CallbackResponse, SubscriptionResponse
```

---

## 4. ERD (V1 + V2 적용됨, V3 예정)

목표 스키마 전체 DDL과 V3 마이그레이션 ALTER 구문은 **[ERD.md](./ERD.md)** 참조.

### 테이블 목록

```
crawl_source         수집 소스 (거래소별)
membership_course    회원권 종목
price_history        시세 이력 (시계열)
member               회원 (Google OAuth2)
watchlist            관심 종목 + 목표가
alert_log            알림 발송 이력

membership_plan      구독 플랜 정의 (INDIVIDUAL / CORPORATE)
subscription         구독 상태 (billing_key AES 암호화 저장)
payment_history      결제 이력 (toss_payment_key, billed_at=approvedAt)
```

### 관계 요약

```
crawl_source ──┬──< membership_course >──< price_history
               └──────────────────────────< price_history

member ──< watchlist >── membership_course
watchlist ──< alert_log

member ──< subscription >── membership_plan
subscription ──< payment_history
```

---

## 5. API 설계

### 인증 (완료)
```
GET  /oauth2/authorization/google        로그인 시작 (Spring Security 자동 제공)
GET  /login/oauth2/code/google           콜백 (Spring Security 자동)
GET  /api/v1/auth/me                     현재 로그인 회원 정보
```

### 종목 (Course)
```
GET  /api/v1/courses                     목록 + 검색
     ?type=GOLF&region=경기&q=레이크&page=0&size=20
     응답: { content: [...], totalElements, ... }

GET  /api/v1/courses/{id}                종목 상세 (최신 시세 포함)
GET  /api/v1/courses/ranking             상승률 TOP / 하락률 TOP (기간별)
     ?period=7d|30d|90d&sort=GAIN|LOSS&size=10
```

### 시세 (Price)
```
GET  /api/v1/courses/{id}/prices         차트 데이터
     ?from=2026-01-01&to=2026-06-22&interval=DAY|WEEK|MONTH
     응답: { points: [{date, price, source}, ...], min, max, change }

GET  /api/v1/courses/{id}/prices/latest  소스별 최신 시세 비교
     응답: [{ sourceName, price, collectedAt }, ...]   ← 핵심 차별화
```

### 관심 종목 (Watchlist)
```
GET    /api/v1/watchlist                 내 관심 목록 + 현재가 + 목표가
POST   /api/v1/watchlist                 종목 추가  { courseId, targetPrice? }
DELETE /api/v1/watchlist/{id}            삭제
PATCH  /api/v1/watchlist/{id}            목표가/알림ON-OFF 수정
```

### WebSocket (Alert)
```
연결: ws://localhost:8081/ws   (STOMP + SockJS)
구독: /user/queue/alert        (로그인 회원 전용 채널)
메시지: { courseId, courseName, targetPrice, currentPrice, sourceName }
```

---

## 6. 수집기(Collector) 상세 설계

### PriceCollector 인터페이스
```java
public interface PriceCollector {
    String getSourceName();             // crawl_source.name과 매핑
    List<CollectedPrice> collect();     // 실제 크롤링
}
```

### CollectedPrice (record)
```java
public record CollectedPrice(
    String  courseName,    // 크롤된 원문 이름 (title 필드)
    String  region,        // 지역 (파싱 가능하면)
    String  courseType,    // GOLF | CONDO | FITNESS
    String  membershipType,// type2 → MembershipType enum 매핑 결과
    Integer holes,         // 홀수 (GOLF only; 동부 HTML에서 파싱, 없으면 null)
    long    price,         // 현재 시세. 원 단위 (소스 만원값 × 10000)
    String  sourceName     // 어느 거래소에서 왔는지
) {}
```

**`type2` → `MembershipType` 매핑 (DongbuCollector 내):**
```java
// 알 수 없는 값은 REGULAR로 폴백 + WARN 로그
private static MembershipType mapType(String type2) {
    return switch (type2.trim()) {
        case "주중"             -> WEEKDAY;
        case "주말"             -> WEEKEND;
        case "가족", "부부"     -> FAMILY;
        case "개인", "개인회원" -> INDIVIDUAL;
        case "법인"             -> CORPORATE;
        case "주주"             -> SHAREHOLDER;
        case "우대"             -> PREFERRED;
        case "남자", "남성"     -> MALE;
        case "여자", "여성"     -> FEMALE;
        default                 -> { log.warn("Unknown type2: {}", type2); yield REGULAR; }
    };
}
```

### CollectService 흐름
```
1. CrawlSourceRepository에서 active=true 소스 목록 조회
2. CollectorRegistry에서 소스별 PriceCollector 조회
3. 소스마다:
   a. collect_run INSERT (status='RUNNING', started_at=NOW())
   b. collector.collect() 호출
   c. 결과마다:
      i.  membership_course 이름으로 조회 → 없으면 INSERT IGNORE (auto-register)
      ii. 충돌 여부와 무관하게 SELECT로 id 재조회
      iii. price_history INSERT (price=원 단위, collect_run_id 세팅)
      iv. success_count++
   d. 예외 발생 시: fail_count++ (종목 단위 격리 — 한 종목 실패가 전체 중단 안 함)
   e. collect_run UPDATE (status=SUCCESS/PARTIAL/FAIL, finished_at=NOW(), 카운트 확정)
4. 모든 소스 완료 후 AlertService.checkAndNotify() 호출 (목표가 트리거 체크)
   ⚠️ @Transactional 범위 주의: price_history INSERT COMMIT 완료 후 alert 발송
```

### 소스별 수집 방식 (실측)

| 소스 | URL | robots.txt | 수집 방식 | 가격 필드 | 업데이트 주기 |
|---|---|---|---|---|---|
| 동부회원권 | dbm-market.co.kr | 허용 | **정적 HTML** `/동부회원권/골프회원권/시세` → Jsoup (홀수 포함) | 금주시세(`price`) | 매일 |
| 동아회원권 | dongagolf.co.kr | 허용 (`/news`만 차단) | **정적 HTML** `/membership/sise/` → Jsoup | 단일 시세 | 주 1회 |
| 에이스회원권 | acemembership.co.kr | 미확인 | 추후 검토 | - | - |

#### 동부회원권 수집 상세 (DongbuCollector)

```
엔드포인트: GET http://www.dbm-market.co.kr/동부회원권/골프회원권/시세
파싱 방식:  Jsoup, CSS selector "table.regtable tbody tr"

테이블 컬럼: [골프장명, 홀수, 회원종류, 금주시세, 전주시세, 전주대비]

필드 매핑:
  td[0] → courseName
  td[1] → holes (Integer, 예: 18/27/36)
  td[2] → membershipType (type2 문자열 → enum 매핑)
  td[3] → price (금주시세, 만원 단위 → ×10000 → 원 단위)
  td[4] → 전주시세 (bprice 아님 — 무시: 이전 row로 계산 가능)
  (CONDO·FITNESS는 별도 탭 URL 필요 시 확장)
```

#### 동아회원권 수집 상세 (DongaCollector)

```
엔드포인트: GET https://www.dongagolf.co.kr/membership/sise/
파싱 방식:  Jsoup, CSS selector "table tbody tr"

필드 매핑:
  td.first a[href] → href 파라미터에서 custid·code 추출 (메타 저장 불필요, 이름으로 매핑)
  td.first a 텍스트 → courseName (예: "가야-주중", "가야우대", "88(팔팔)")
  td[1] 텍스트     → price (금일시세, 단일값, 만원 단위 → 원 단위 변환)
  holes            → null (동아 시세 목록 페이지에 홀수 없음; 상세 페이지에서 파싱 가능하나 MVP 제외)
  courseType       → HTML 탭/구분 없이 전체 골프 only (MVP)

이력 API (초기 백필 용도):
  GET /api/chart.php?id={custid}&code={code}&type=y
  응답: { data: [{price, date:'YY/MM/DD', name}], rangeStart, rangeEnd }
  → 최초 수집 시 1년치 히스토리 백필 가능 (단, 주 단위 snapshot만 제공)
```

⚠️ **가격 단위 확인 완료**: 두 사이트 모두 **만원 단위** (동아 시세 페이지 `(단위:만원)` 명시).  
`43,800` = **4억 3,800만 원**. DB 저장 시 반드시 `× 10,000`해서 원 단위로 변환.  
동아 HTML은 콤마 포함 문자열(`"43,800"`) → 콤마 제거 후 Long 변환 → × 10,000.

---

## 7. WebSocket 알림 흐름

### 연결 인증

STOMP CONNECT 시 클라이언트가 `Authorization: Bearer {accessToken}` 헤더를 전달한다.  
`ChannelInterceptor`(또는 `WebSocketMessageBrokerConfigurer`)에서 JWT를 검증한 뒤  
`StompHeaderAccessor`에 `user` principal을 설정한다.  
이후 `convertAndSendToUser(memberId, ...)` 호출 시 회원별 채널 분리가 동작한다.

```java
// 개략 구현
public class JwtChannelInterceptor implements ChannelInterceptor {
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = ...;
        if (CONNECT.equals(accessor.getCommand())) {
            String token = accessor.getFirstNativeHeader("Authorization");
            Long memberId = jwtProvider.getMemberId(token);
            accessor.setUser(() -> String.valueOf(memberId));
        }
        return message;
    }
}
```

### JWT 만료 vs 장기 연결

STOMP 연결 유지 중 1시간 후 access token이 만료돼도 연결은 끊기지 않는다 (JWT 검증은 CONNECT 시 1회만).  
**MVP 결정**: 허용. 클라이언트는 WS 재연결 시 새 토큰을 `Authorization` 헤더에 포함.  
운영에서 문제가 되면: `ChannelInterceptor`에서 SEND/SUBSCRIBE 시마다 토큰 재검증 추가.

### 알림 트리거 흐름

```
[수집 완료 — CollectService 트랜잭션 COMMIT 후]
    │
    ▼  ← TransactionSynchronizationManager.afterCommit() 콜백에서 호출
AlertService.checkAndNotify()
    │ watchlist에서 alert_yn=true + target_price IS NOT NULL 조회
    │ JOIN subscription WHERE status='ACTIVE'   ← 비구독자 알림 미발송
    │ 각 watchlist의 최신 price_history.price(매도가) 와 target_price 비교
    │ currentPrice <= targetPrice → 알림 발송 조건
    ▼
SimpMessagingTemplate.convertAndSendToUser(
    memberId, "/queue/alert", AlertMessage
)
    │
    ▼
alert_log INSERT (중복 발송 방지: 같은 watchlist 24h 내 재발송 안 함)
```

**afterCommit 패턴 — 왜 필요한가:**  
`CollectService`가 `@Transactional` 안에서 `AlertService.checkAndNotify()`를 직접 호출하면  
price_history가 아직 COMMIT되지 않아 최신 시세 조회가 이전 값을 반환한다.

```java
@Transactional
public void collectAll() {
    // ... price_history INSERT ...
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                alertService.checkAndNotify();  // COMMIT 이후 호출 보장
            }
        }
    );
}
```

**Scheduler 단일 인스턴스 제약:**  
`@Scheduled`는 동일 프로세스 내 한 스레드에서만 실행. MVP는 서버 1대 가정이므로 중복 실행 없음.  
서버를 2대 이상 띄우면 같은 시각에 양쪽이 모두 수집 → `collect_run` 중복 행, price_history 중복 INSERT.  
다중 인스턴스 배포 시에는 **ShedLock** (`@SchedulerLock` + DB lock row) 추가 필요. MVP 이후 과제.

---

## 8. 구현 순서 (M1-b 이후 로드맵)

### M1-b: 수집 파이프라인 (다음 이슈)
1. `CrawlSource` 엔티티 + Repository
2. `PriceCollector` 인터페이스 + `CollectedPrice` record
3. `CollectorRegistry`
4. `DongbuCollector` (Jsoup 정적 HTML, 동부회원권 시세 테이블 — 홀수 포함)
5. `DongaCollector` (Jsoup 정적 HTML 파싱, 동아회원권)
6. `CollectService` + `CollectorScheduler` (afterCommit 패턴 포함)
7. `MembershipCourse` 엔티티 + `PriceHistory` 엔티티 + `CollectRun` 엔티티 + `CourseSourceMapping` 엔티티
8. V3 마이그레이션 (collect_run·course_source_mapping 신설, membership_type 확장, holes 추가)
9. 수집 동작 확인 (로컬 DB에 데이터 쌓이는지, 가격 단위 검증)

### M1-c: 조회 API
1. `CourseController` (목록/상세/랭킹)
2. `PriceController` (차트 데이터, 소스별 최신가)

### M1-d: 관심종목 + 알림
1. `WatchlistController`
2. WebSocket Config + `AlertService`

### M1-e: 구독 결제
1. V3 마이그레이션 적용 (ERD.md 참조)
2. `membership_plan` 시드 데이터 INSERT
3. `SubscriptionController` — prepare / callback (빌링 키 + 첫 결제 + subscription INSERT)
4. `BillingScheduler` — 자동 갱신
5. 기능 게이팅: watchlist 한도, 알림 구독 체크, 차트 7일 clamp

### M2: 프론트엔드
- 토스증권 UI 레퍼런스
- 종목 목록 → 상세 차트 → 관심등록 → 알림 수신 → 구독 결제

---

## 9. 설계 결정 기록 (ADR)

| 결정 | 이유 |
|---|---|
| OAuth2 Only (비밀번호 없음) | 구글 계정 있는 자산가 타겟, 비밀번호 관리 복잡도 제거 |
| Access Token Only (no refresh) | MVP 단순화. 1시간 만료 후 재로그인 허용 |
| JWT를 URL 파라미터로 전달 | 프론트 미확정 MVP. 프론트 완성 시 HttpOnly 쿠키로 전환 검토 |
| @Scheduled → Spring Batch 순차 전환 | MVP는 단순함 우선. 재시도/멱등/이력 필요해지면 Batch로 업그레이드 |
| 종목 자동 등록 (exact name match) | 초기 시드 없이 크롤링만으로 DB 채우기. 중복은 Phase 2에서 정규화 |
| Extractor/Collector 모두 Spring DI List 주입 | OAuth Extractor와 동일 패턴. 구현체 추가 = 파일 하나 추가, 수정 없음 |
| price_history source_id 보존 | "소스별 가격 비교"가 핵심 차별화 → raw 데이터 보존 |
| STOMP /user/queue/alert | Spring Security와 자연스럽게 연동, 회원별 채널 분리 |
| 동아 Jsoup (Playwright 제거) | 실측 결과 동아 시세 페이지는 서버사이드 렌더링 정적 HTML → Jsoup으로 충분 |
| 가격 DB 저장 단위: 원 | 두 사이트 모두 만원 단위 제공 → 수집기에서 ×10,000 변환 후 원 단위 BIGINT 저장 |
| bid_price 제거 | 동부 `bprice`는 BID가 아니라 전주시세임을 실측 확인 → 이전값은 시계열에서 직접 조회 |
| 동아 이력 백필 가능 | `/api/chart.php` 제공. 첫 수집 시 `type=t`(전체)로 주간 스냅샷 역주입 가능 |
