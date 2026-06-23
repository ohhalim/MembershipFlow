# MembershipFlow MVP API 명세

이 문서는 MembershipFlow MVP의 REST API 계약을 정의한다.

MVP API는 구글 로그인, 종목 조회, 시세 차트, 관심 종목 CRUD, WebSocket 알림, 토스페이먼츠 빌링 구독 결제를 지원한다.  
비밀번호 로그인, 입금/출금, 거래소 송객, 관리자 API는 제외한다.

---

## 1. 공통 규칙

### Base URL

```text
/api/v1
```

### 인증

인증이 필요한 API는 `Authorization` 헤더에 JWT access token을 전달한다.

```http
Authorization: Bearer {accessToken}
```

서버는 request body나 query string의 `memberId`를 신뢰하지 않는다.  
watchlist 조작은 JWT에서 추출한 현재 `memberId` 기준으로 처리한다.

**공개 vs 인증 필요 정책** (SecurityConfig 기준):

| 범위 | 정책 |
|---|---|
| `GET /api/v1/courses/**` | `permitAll` — 토큰 없어도 접근 가능 |
| `GET /api/v1/subscriptions/plans` | `permitAll` |
| `GET /internal/**` | `permitAll` (`!prod` 프로필에서만 노출) |
| `/api/v1/watchlist/**`, `/api/v1/alerts/**`, `/api/v1/subscriptions/**`, `/api/v1/auth/**` | `authenticated` |

**Optional JWT**: 공개 API에 유효한 JWT가 있으면 `Authentication`을 설정해 구독 여부·watchlist 상태 조회에 활용한다.  
토큰 없음 → 익명(비구독 7일 clamp 등 적용).  
유효하지 않은 토큰(만료·위조) → **401** (비로그인 취급 없음 — 오래된 토큰이 자동으로 하위 권한으로 강등되면 보안 위험).

### 시간

시간은 ISO-8601 문자열로 응답한다.

```text
2026-06-22T15:30:00
```

### 공통 에러 응답

```json
{
  "code": "COURSE_NOT_FOUND",
  "message": "해당 종목을 찾을 수 없습니다."
}
```

### 에러 코드

| Code | HTTP | 의미 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 요청 형식 오류 / 필수 필드 누락 |
| `UNAUTHORIZED` | 401 | 인증 실패 (JWT 없음 또는 만료) |
| `FORBIDDEN` | 403 | 권한 없음 |
| `COURSE_NOT_FOUND` | 404 | 종목을 찾을 수 없음 |
| `WATCHLIST_NOT_FOUND` | 404 | 관심 종목을 찾을 수 없음 (타인 항목 포함) |
| `WATCHLIST_ALREADY_EXISTS` | 409 | 이미 관심 등록한 종목 |
| `WATCHLIST_LIMIT_EXCEEDED` | 403 | 비구독자 찜 한도(3개) 초과 (구독 티어 권한 문제로 403 선택 — 409도 논쟁 가능) |
| `SUBSCRIPTION_REQUIRED` | 403 | 구독 필요 기능에 비구독자 접근 |
| `SUBSCRIPTION_ALREADY_EXISTS` | 409 | 이미 구독 중 |
| `SUBSCRIPTION_NOT_FOUND` | 404 | 구독 정보 없음 |
| `PAYMENT_FAILED` | 402 | 토스 결제 실패 |
| `BILLING_KEY_ISSUE_FAILED` | 502 | 토스 빌링 키 발급 실패 |
| `INVALID_PRICE_RANGE` | 400 | 유효하지 않은 가격 범위 |
| `INVALID_DATE_RANGE` | 400 | 유효하지 않은 날짜 범위 |
| `INTERNAL_ERROR` | 500 | 서버 내부 오류 |

### 에러 코드 상황별 매핑

| 상황 | 에러 코드 |
|---|---|
| Authorization 헤더 없음 또는 JWT 만료/위조 | `UNAUTHORIZED` |
| 존재하지 않는 courseId | `COURSE_NOT_FOUND` |
| 존재하지 않는 watchlistId 또는 타인 watchlist 접근 | `WATCHLIST_NOT_FOUND` |
| 이미 찜한 종목에 다시 추가 | `WATCHLIST_ALREADY_EXISTS` |
| target_price <= 0 | `INVALID_PRICE_RANGE` |
| from > to, 미래 날짜 | `INVALID_DATE_RANGE` |

> 타인의 watchlist 접근 시 `FORBIDDEN` 대신 `WATCHLIST_NOT_FOUND`를 반환한다. 항목 존재 여부를 노출하지 않기 위한 의도적인 정책이다.

---

## 2. 도메인 처리 규칙

### 2.1 최신가 기준

"최신가"는 `price_history`에서 해당 course_id의 `MAX(collected_at)` 시점의 price다.

**⚠️ 소스별 최신가 쿼리 주의**: `SELECT source_id, price, MAX(collected_at) ... GROUP BY source_id` 패턴은 잘못됐다.  
`price`가 집계 함수도 아니고 GROUP BY에도 없으므로 MySQL ONLY_FULL_GROUP_BY 모드에서 에러, OFF이면 그룹 내 임의 행의 price가 반환된다.

올바른 패턴 — 서브쿼리 방식:
```sql
SELECT ph.source_id, ph.price, ph.collected_at
FROM price_history ph
WHERE ph.course_id = ?
  AND (ph.source_id, ph.collected_at) IN (
      SELECT source_id, MAX(collected_at)
      FROM price_history
      WHERE course_id = ?
      GROUP BY source_id
  )
```

또는 윈도우 함수 방식 (권장 — tie-breaker 포함):
```sql
SELECT source_id, price, collected_at
FROM (
    SELECT source_id, price, collected_at,
           ROW_NUMBER() OVER (
               PARTITION BY source_id
               ORDER BY collected_at DESC, id DESC   -- id DESC: 동일 collected_at 복수 행 tie-break
           ) AS rn
    FROM price_history
    WHERE course_id = ?
) ranked
WHERE rn = 1
```

`(source_id, course_id, collected_at)` unique 제약이 없으므로 동일 소스·종목·수집시각 레코드가 2건 생길 수 있다. `id DESC`로 나중에 INSERT된 행을 선택한다.

### 2.2 차트 집계 기준

| interval | 집계 방법 |
|---|---|
| `DAY` | 일별 평균가 (`AVG(price) GROUP BY DATE(collected_at)`) |
| `WEEK` | 주별 평균가 (`AVG(price) GROUP BY YEARWEEK(collected_at)`) |
| `MONTH` | 월별 평균가 (`AVG(price) GROUP BY DATE_FORMAT(collected_at, '%Y-%m')`) |

반환 단위: 원(Long). 소수점 없음.

**집계 범위**: `AVG(price)`는 해당 날짜의 **모든 소스 가격을 통합 평균**낸다 — 소스별 라인 분리가 아닌 시장 합산 평균 트렌드를 보여주는 것이 의도.  
소스별 가격 비교는 §5.2 소스별 최신 시세 비교 API를 사용한다.

### 2.3 랭킹 기준

`period` 기준 시작 시점의 가격 대비 현재 가격의 변화율.

```text
changeRate = (currentPrice - basePrice) / basePrice * 100
```

`basePrice`는 해당 기간 시작 시점에서 가장 가까운 `price_history` 레코드의 price.

### 2.4 watchlist 목표가 알림 조건

```text
alert_yn = true
AND target_price IS NOT NULL
AND (비교 대상 가격) <= target_price          ← 하락 방향만 (구매 타이밍 알림, 상승 알림은 미지원)
AND (최근 24시간 내 같은 watchlist_id로 발송 이력 없음)
AND member.subscription.status = 'ACTIVE'   ← 비구독자는 알림 미발송
```

**비교 대상 가격 기준**: 소스가 여럿일 때 `MAX(collected_at)` 단순 조회는 어느 소스인지 비결정적이다 (§2.1 참조).  
비교 기준은 **소스별 최신가 중 최저가**로 한다.  
→ "소스 중 하나라도 목표가에 도달하면 실제 거래 가능" 해석이며, `alert_log.source_id`에는 해당 최저가 소스의 source_id를 저장한다.

**알림 방향**: 하락 방향(`price <= target_price`)만 지원한다. 상승 알림(목표가 이상 상승 시)은 MVP 제외.  
추후 필요 시 `watchlist.direction ENUM('BELOW','ABOVE')` 컬럼 추가로 확장 가능하다.

---

## 3. Auth

### 3.1 구글 OAuth2 로그인 시작

```http
GET /oauth2/authorization/google
```

인증: 불필요  
Spring Security가 자동으로 Google 인증 서버로 리다이렉트한다.

### 3.2 OAuth2 콜백 처리

```http
GET /login/oauth2/code/google
```

인증: 불필요  
Spring Security 내부 처리. 로그인 성공 후 프런트엔드 콜백 URL로 리다이렉트.

성공 시:
```text
{OAUTH2_REDIRECT_URI}?success=true&token={accessToken}
```

실패 시:
```text
{OAUTH2_REDIRECT_URI}?success=false&error={message}
```

### 3.3 현재 로그인 회원 조회

```http
GET /api/v1/auth/me
```

인증: 필요

Response:

```json
{
  "id": 1,
  "email": "user@gmail.com",
  "name": "홍길동"
}
```

---

## 4. Courses (종목)

### 4.1 종목 목록 조회

```http
GET /api/v1/courses
```

인증: 불필요

Query:

| Name | Required | 설명 |
|---|---|---|
| `q` | N | 이름 검색 (LIKE %q%) |
| `courseType` | N | `GOLF` \| `CONDO` \| `FITNESS` |
| `membershipType` | N | `REGULAR` \| `WEEKDAY` \| `WEEKEND` \| `FAMILY` |
| `region` | N | 지역 필터 (예: 경기, 충남) |
| `page` | N | 페이지 번호, 기본값 0 |
| `size` | N | 페이지 크기, 기본값 20, 최대 100 |

Response:

```json
{
  "content": [
    {
      "id": 1,
      "name": "레이크사이드CC",
      "region": "경기",
      "courseType": "GOLF",
      "membershipType": "WEEKEND",
      "latestPrice": 430000000,
      "latestCollectedAt": "2026-06-22T10:00:00",
      "priceChangeRate": 2.5
    }
  ],
  "totalElements": 42,
  "totalPages": 3,
  "page": 0,
  "size": 20
}
```

`latestPrice`: 가장 최근 수집된 price (소스 무관 단순 최신값).  
`priceChangeRate`: 7일 전 대비 변화율 (없으면 null).

> **⚠️ N+1 주의**: 목록 각 row마다 `latestPrice`(MAX collected_at) + `priceChangeRate`(7일 전 price) 쿼리를 개별 실행하면 페이지당 N×2 쿼리. 메인 진입 화면이라 체감 큼.  
> **배치 전략**: `PriceService.getLatestPriceBatch(courseIds)` — `WHERE course_id IN (?) AND (course_id, collected_at) IN (서브쿼리)` 로 한 번에 조회. `priceChangeRate`도 7일 전 배치 조회로 동일 처리.

### 4.2 종목 상세 조회

```http
GET /api/v1/courses/{courseId}
```

인증: 불필요

Response:

```json
{
  "id": 1,
  "name": "레이크사이드CC",
  "region": "경기",
  "courseType": "GOLF",
  "membershipType": "WEEKEND",
  "latestPrices": [
    {
      "sourceName": "동부회원권",
      "price": 430000000,
      "collectedAt": "2026-06-22T10:00:00"
    },
    {
      "sourceName": "동아회원권",
      "price": 420000000,
      "collectedAt": "2026-06-22T09:30:00"
    }
  ],
  "watchlisted": false,
  "targetPrice": null
}
```

`latestPrices`: 소스별 가장 최신 가격. 스카이스캐너 스타일 비교 뷰 핵심 데이터.  
`watchlisted`, `targetPrice`: 로그인 회원의 watchlist 상태 (비로그인 시 `watchlisted: false`, `targetPrice: null`).

### 4.3 랭킹 조회

```http
GET /api/v1/courses/ranking
```

인증: 불필요

> **캐시 권고**: 전 종목 `basePrice`·`currentPrice` 재계산을 매 요청 수행하면 전체 테이블 스캔. 수집이 매시간이므로 결과는 최대 1시간 단위로만 바뀜.  
> MVP: `@Cacheable(value = "ranking", key = "#period + #sort + #courseType")` + 10분 TTL. Redis 없이 Caffeine(로컬 캐시)로 충분.

Query:

| Name | Required | 기본값 | 설명 |
|---|---|---|---|
| `period` | N | `7d` | `1d` \| `7d` \| `30d` \| `90d` |
| `sort` | N | `GAIN` | `GAIN` (상승률) \| `LOSS` (하락률) |
| `courseType` | N | 전체 | `GOLF` \| `CONDO` \| `FITNESS` |
| `size` | N | 10 | 최대 50 |

Response:

```json
[
  {
    "rank": 1,
    "courseId": 5,
    "name": "남서울CC",
    "region": "경기",
    "courseType": "GOLF",
    "membershipType": "WEEKEND",
    "currentPrice": 130000000,
    "basePrice": 120000000,
    "changeRate": 8.33,
    "changeAmount": 10000000
  }
]
```

---

## 5. Prices (시세)

### 5.1 시세 차트 데이터

```http
GET /api/v1/courses/{courseId}/prices
```

인증: 불필요 (구독 여부에 따라 조회 기간 자동 제한)

비구독자(미인증 포함)는 `from` 파라미터와 무관하게 최근 **7일** 데이터만 반환한다.  
구독 ACTIVE 회원은 `from` 파라미터 그대로 조회 가능하다 (Plan.md §7.7 참조).

Query:

| Name | Required | 기본값 | 설명 |
|---|---|---|---|
| `from` | N | 30일 전 | 조회 시작일 (yyyy-MM-dd) — 비구독 시 7일 clamp 적용 |
| `to` | N | 오늘 | 조회 종료일 (yyyy-MM-dd) |
| `interval` | N | `DAY` | `DAY` \| `WEEK` \| `MONTH` |

Response:

```json
{
  "courseId": 1,
  "courseName": "레이크사이드CC",
  "interval": "DAY",
  "from": "2026-06-15",
  "to": "2026-06-22",
  "points": [
    {
      "date": "2026-06-15",
      "avgPrice": 420000000,
      "minPrice": 245000000,
      "maxPrice": 252000000,
      "count": 3
    },
    {
      "date": "2026-06-16",
      "avgPrice": 430000000,
      "minPrice": 430000000,
      "maxPrice": 430000000,
      "count": 1
    }
  ],
  "summary": {
    "currentPrice": 430000000,
    "basePrice": 420000000,
    "changeAmount": 2000000,
    "changeRate": 0.81,
    "minPrice": 240000000,
    "maxPrice": 255000000
  },
  "subscriptionRequired": true
}
```

`count`: 해당 날짜에 수집된 데이터 포인트 수.  
`subscriptionRequired`: 비구독자에게 `true`. 프런트가 "전체 기간은 구독 후 이용" 안내를 표시하는 데 사용한다.

**시계열 결손 처리 정책**: 수집 실패로 price_history에 구멍이 생긴 날짜는 `points` 배열에 해당 날짜 객체 자체가 없다(0이나 이전값 채움 금지 — 오해 소지).  
프런트는 날짜 누락 구간을 차트 선 끊김(gap)으로 표시.  
`summary.changeRate` 계산 시 `basePrice`가 없는 경우(시작일 데이터 없음): `changeRate=null`, `basePrice=null` 반환.  
`summary.basePrice`: 요청 `from` 날짜 ±3일 이내 가장 가까운 레코드로 fallback 허용 (±3일 범위 없으면 null).

### 5.2 소스별 최신 시세 비교

```http
GET /api/v1/courses/{courseId}/prices/latest
```

인증: 불필요

Response:

```json
[
  {
    "sourceName": "동부회원권",
    "sourceUrl": "http://www.dbm-market.co.kr",
    "price": 430000000,
    "collectedAt": "2026-06-22T10:00:00"
  },
  {
    "sourceName": "동아회원권",
    "sourceUrl": "https://www.dongagolf.co.kr",
    "price": 420000000,
    "collectedAt": "2026-06-22T09:30:00"
  }
]
```

`price`: 해당 소스의 현재 시세 (원 단위).  
이 API가 핵심 차별화 포인트다. 같은 종목을 여러 거래소에서 얼마에 파는지 한눈에 비교한다.

---

## 6. Watchlist (관심 종목)

### 6.1 내 관심 목록 조회

```http
GET /api/v1/watchlist
```

인증: 필요

Response:

```json
[
  {
    "id": 10,
    "course": {
      "id": 1,
      "name": "레이크사이드CC",
      "region": "경기",
      "courseType": "GOLF",
      "membershipType": "WEEKEND"
    },
    "latestPrice": 430000000,
    "targetPrice": 230000000,
    "alertYn": true,
    "createdAt": "2026-06-01T09:00:00"
  }
]
```

`latestPrice`: 현재 최신 시세.  
`targetPrice`: null이면 알림 미설정 상태.

### 6.2 관심 종목 추가

```http
POST /api/v1/watchlist
```

인증: 필요

Request:

```json
{
  "courseId": 1,
  "targetPrice": 230000000
}
```

`targetPrice`는 optional. null이면 찜만 등록.

Response: `201 Created`

```json
{
  "id": 10,
  "courseId": 1,
  "targetPrice": 230000000,
  "alertYn": true,
  "createdAt": "2026-06-22T15:30:00"
}
```

오류:
- `COURSE_NOT_FOUND`: courseId가 존재하지 않음
- `WATCHLIST_ALREADY_EXISTS`: 이미 등록한 종목
- `WATCHLIST_LIMIT_EXCEEDED`: 비구독자 찜 한도(3개) 초과 — 구독 시 무제한

### 6.3 관심 종목 삭제

```http
DELETE /api/v1/watchlist/{watchlistId}
```

인증: 필요

Response: `204 No Content`

오류:
- `WATCHLIST_NOT_FOUND`: 존재하지 않거나 타인 항목

### 6.4 목표가 / 알림 수정

```http
PATCH /api/v1/watchlist/{watchlistId}
```

인증: 필요

Request:

```json
{
  "targetPrice": 220000000,
  "alertYn": true
}
```

변경할 필드만 포함. null 전달 시 해당 필드 변경 없음.

`targetPrice`를 명시적으로 지우려면 `"targetPrice": 0`이 아니라 별도 엔드포인트(미구현) 사용.  
MVP에서는 0으로 전달 시 `INVALID_PRICE_RANGE` 반환.

Response: `200 OK`

```json
{
  "id": 10,
  "courseId": 1,
  "targetPrice": 220000000,
  "alertYn": true,
  "updatedAt": "2026-06-22T16:00:00"
}
```

오류:
- `WATCHLIST_NOT_FOUND`: 존재하지 않거나 타인 항목
- `INVALID_PRICE_RANGE`: targetPrice <= 0

---

## 7. WebSocket (실시간 알림)

### 연결

```text
ws://localhost:8081/ws
```

SockJS + STOMP 프로토콜 사용.

```javascript
const socket = new SockJS('/ws');
const stompClient = Stomp.over(socket);

stompClient.connect({ 'Authorization': 'Bearer ' + token }, () => {
  stompClient.subscribe('/user/queue/alert', (msg) => {
    const alert = JSON.parse(msg.body);
    console.log(alert);
  });
});
```

### 구독 채널

| 채널 | 대상 |
|---|---|
| `/user/queue/alert` | 로그인 회원 개인 알림 (목표가 도달) |

Spring Security + STOMP 통합으로 `/user/` prefix는 현재 로그인 회원에게만 전달된다.

### 알림 메시지 형식

목표가 도달 시 서버에서 push.

```json
{
  "type": "PRICE_ALERT",
  "watchlistId": 10,
  "courseId": 1,
  "courseName": "레이크사이드CC",
  "membershipType": "WEEKEND",
  "targetPrice": 230000000,
  "currentPrice": 228000000,
  "sourceName": "동부회원권",
  "triggeredAt": "2026-06-22T10:00:00"
}
```

`currentPrice <= targetPrice`일 때 발송.  
같은 watchlist에 대해 24시간 내 재발송 없음.

> **⚠️ 전달 보장 없음**: WebSocket push는 fire-and-forget. 회원이 미접속 상태면 알림이 영구 소실되며, `alert_log`에는 "발송"으로 기록된다(24h 재발송 차단까지 걸림).  
> 이를 보완하기 위해 §7.1 REST 조회 API를 제공한다.

> **⚠️ JWT 만료 vs 장기 연결**: STOMP 연결 중 1시간 후 토큰 만료 시 별도 처리 없음 — 연결은 살아있지만 권한 만료 상태가 된다. MVP 허용 사항. 클라이언트는 WS 재연결 시 새 토큰을 사용하도록 구현해야 한다.

### 7.1 미확인 알림 조회

접속 중 놓친 알림을 REST로 보완한다. WebSocket push를 못 받은 회원이 재접속 후 확인할 수 있다.

```http
GET /api/v1/alerts
```

인증: 필요

Query:

| Name | Required | 기본값 | 설명 |
|---|---|---|---|
| `since` | N | 7일 전 | 조회 시작 시각 (ISO-8601) |
| `unreadOnly` | N | false | true이면 `read_at IS NULL`만 반환 |

Response:

```json
[
  {
    "id": 15,
    "watchlistId": 10,
    "courseId": 1,
    "courseName": "레이크사이드CC",
    "targetPrice": 230000000,
    "triggeredPrice": 228000000,
    "sourceName": "동부회원권",
    "sentAt": "2026-06-22T10:00:00",
    "readAt": null
  }
]
```

### 7.2 알림 읽음 처리

```http
PATCH /api/v1/alerts/{alertId}/read
```

인증: 필요

Response: `200 OK`  
오류: `ALERT_NOT_FOUND` (404) — 존재하지 않거나 타인 알림

> `alert_log.read_at DATETIME NULL` 컬럼은 V3 ⑥ ALTER에 포함됨 (ERD.md 참조).

---

## 8. Subscriptions (구독 결제)

토스페이먼츠 빌링 API 기반 구독 결제. 카드 인증 → 콜백에서 빌링 키 발급 + 첫 결제 + 구독 등록 → 자동 갱신 구조.  
PENDING 중간 상태 없이 콜백 한 번에 빌링 키 발급과 첫 결제를 모두 처리한다 (Plan.md §7.5 참조).

### 8.1 결제창 파라미터 조회 (카드 등록 준비)

```http
GET /api/v1/subscriptions/billing/prepare?planId=1
```

인증: 필요

서버가 `customerKey`(UUID)를 생성한다. **이 단계에서 customerKey는 DB에 저장하지 않는다.**  
토스가 콜백 쿼리 파라미터로 그대로 돌려주므로 DB 임시 저장이 불필요하다.  
콜백에서 subscription row가 생성될 때 함께 저장된다.

Query:

| Name | Required | 설명 |
|---|---|---|
| `planId` | N (기본 1) | 구독할 플랜 ID |

Response:

```json
{
  "customerKey": "a1b2c3d4-...",
  "clientKey": "test_ck_...",
  "planId": 1
}
```

프런트는 이 값으로 토스 SDK를 호출한다:
```javascript
const tossPayments = TossPayments(clientKey);
tossPayments.requestBillingAuth('카드', {
  customerKey,
  successUrl: `${BACKEND_URL}/api/v1/subscriptions/billing/callback`,
  failUrl: `${FRONTEND_URL}/subscription/fail`
});
```

### 8.2 빌링 키 발급 + 첫 결제 + 구독 등록 (콜백)

```http
GET /api/v1/subscriptions/billing/callback?customerKey=...&authKey=...
```

인증: 불필요 (프런트가 리다이렉트되어 도달, 토스가 customerKey·authKey 쿼리 파라미터 전달)

카드 인증 완료 후 서버가 토스 API를 순차 호출하여 빌링 키 발급 → 첫 결제 → 구독 등록을 원자적으로 처리한다.

처리 흐름:
1. `POST https://api.tosspayments.com/v1/billing/authorizations/issue` (`customerKey`, `authKey`)  
   → `billingKey`, `card.number`(마스킹), `cardCompany` 수신
2. `POST https://api.tosspayments.com/v1/billing/{billingKey}` (`customerKey`, `amount`, `orderId`, `orderName`)  
   → `paymentKey`, `approvedAt`, `totalAmount` 수신
3. `@Transactional`:
   - `subscription` INSERT: `status=ACTIVE`, `billing_key=AES암호화(billingKey)`, `customer_key`,  
     `card_number_masked`, `card_company`, `started_at=approvedAt`, `next_billing_at=approvedAt+1month`
   - `payment_history` INSERT: `status=SUCCESS`, `toss_payment_key=paymentKey`, `billed_at=approvedAt`

결제 실패 시(2에서 토스 오류): `subscription` row 미생성, 에러 응답. 사용자가 재시도 가능.

Response: `200 OK`

```json
{
  "subscriptionId": 1,
  "status": "ACTIVE",
  "startedAt": "2026-06-22T15:00:00",
  "nextBillingAt": "2026-07-22T15:00:00",
  "cardNumberMasked": "4330-12**-****-1234",
  "cardCompany": "현대카드"
}
```

오류:
- `BILLING_KEY_ISSUE_FAILED`: 토스 빌링 키 발급 실패 (카드 인증 오류)
- `PAYMENT_FAILED`: 첫 결제 실패 (토스 오류 메시지 포함)
- `SUBSCRIPTION_ALREADY_EXISTS`: 이미 ACTIVE 구독 존재

### 8.3 내 구독 상태 조회

```http
GET /api/v1/subscriptions/me
```

인증: 필요

Response:

```json
{
  "id": 1,
  "plan": {
    "id": 1,
    "code": "INDIVIDUAL",
    "name": "개인 구독",
    "price": 49000
  },
  "status": "ACTIVE",
  "startedAt": "2026-06-22T15:00:00",
  "nextBillingAt": "2026-07-22T15:00:00",
  "cardNumberMasked": "4330-12**-****-1234",
  "cardCompany": "현대카드",
  "cancelledAt": null
}
```

구독 없으면: `404 SUBSCRIPTION_NOT_FOUND`

### 8.4 구독 취소

```http
DELETE /api/v1/subscriptions
```

인증: 필요

취소 즉시 `next_billing_at`까지 서비스 사용 가능. 환불 없음.

Response: `200 OK`

```json
{
  "id": 1,
  "status": "CANCELLED",
  "cancelledAt": "2026-06-22T16:00:00",
  "serviceEndsAt": "2026-07-22T15:00:00"
}
```

`serviceEndsAt` = 기존 `next_billing_at`. 이 시점까지는 구독 기능 사용 가능.

오류:
- `SUBSCRIPTION_NOT_FOUND`: 구독 없음
- 이미 `CANCELLED` / `SUSPENDED` 상태: `SUBSCRIPTION_NOT_FOUND`

### 8.5 결제 이력 조회

```http
GET /api/v1/subscriptions/payments
```

인증: 필요

Response:

```json
[
  {
    "id": 5,
    "amount": 49000,
    "status": "SUCCESS",
    "billedAt": "2026-06-22T15:00:00",
    "planName": "개인 구독"
  },
  {
    "id": 4,
    "amount": 49000,
    "status": "FAIL",
    "billedAt": "2026-05-22T15:00:00",
    "failReason": "카드 한도 초과",
    "planName": "개인 구독"
  }
]
```

`tossPaymentKey`, `billingKey`는 응답에 절대 포함하지 않는다.

### 8.6 구독 플랜 목록

```http
GET /api/v1/subscriptions/plans
```

인증: 불필요

Response:

```json
[
  {
    "id": 1,
    "code": "INDIVIDUAL",
    "name": "개인 구독",
    "price": 49000,
    "description": "실시간 알림 + 차트 전체 기간 + 찜 무제한"
  },
  {
    "id": 2,
    "code": "CORPORATE",
    "name": "법인 구독",
    "price": 299000,
    "description": "개인 구독 전체 + 포트폴리오 평가 대시보드 + 다중계정"
  }
]
```

---

## 9. 내부 수집 API (개발 편의)

MVP 로컬 개발에서 스케줄 대기 없이 수집을 수동 트리거하기 위한 API.  
`prod` 프로필에서는 등록하지 않는다.

```http
POST /internal/collect
```

인증: 불필요 (`!prod` 프로필에서만 노출)

Response:

```json
{
  "collectedCount": 85,
  "errorCount": 0,
  "duration": "3.2s",
  "triggeredAt": "2026-06-22T15:30:00"
}
```
