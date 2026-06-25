# MembershipFlow 구현 현황

> 2026-06-25 기준 실제 배포 운영 중인 기능만 기록한다. 계획·설계는 [Plan.md](./Plan.md) 참조.

---

## 목차

1. [백엔드](#1-백엔드)
2. [프론트엔드](#2-프론트엔드)
3. [인프라 / DevOps](#3-인프라--devops)
4. [데이터 현황](#4-데이터-현황)
5. [알려진 미구현 항목](#5-알려진-미구현-항목)

---

## 1. 백엔드

### 1.1 인증 (Auth)

| 기능 | 상태 |
|---|---|
| Google OAuth2 로그인 | ✅ |
| JWT Access Token 발급 (1시간) | ✅ |
| Refresh Token (httpOnly 쿠키, 30일, 로테이션) | ✅ |
| `GET /api/v1/auth/me` | ✅ |
| 비밀번호 로그인 | ❌ 미구현 (OAuth 전용) |

**Refresh Token 상세:**
- `refresh_token` 테이블에 AES 암호화 저장
- `POST /api/v1/auth/refresh` — 쿠키의 refresh_token으로 새 access_token 발급
- Rotation: 재발급 시 기존 토큰 폐기 후 신규 토큰 저장
- `DELETE /api/v1/auth/logout` — 서버 측 토큰 즉시 폐기

---

### 1.2 시세 수집 (Collector)

#### 수집 소스

| 소스 | URL | 방식 | 수집 주기 |
|---|---|---|---|
| 동부회원권 | dbm-market.co.kr | Jsoup 정적 HTML | 매일 오전 7시 |
| 동아회원권 | dongagolf.co.kr | Jsoup 정적 HTML | 매일 오전 7시 |

#### 수집 흐름

```
CollectorScheduler (@Scheduled 07:00)
  → CollectService.collectAll()
    → DongbuCollector.collect()    // 정적 HTML, 금주시세·홀수
    → DongaCollector.collect()     // 정적 HTML, 단일 시세
  → price_history INSERT (원 단위, course 자동 등록)
  → afterCommit() → AlertService.checkAndNotify()
```

**비동기 히스토리 수집 API:**
```
POST /admin/collect/history
→ CollectAsyncService.collectHistoryAsync() (@Async, 즉시 202 반환)
→ DongaHistoryCollector — /api/chart.php?type=y 로 1년치 주간 스냅샷 백필
```

#### 주요 기술 이슈 해결 이력

| 이슈 | 해결 방법 |
|---|---|
| 동아 TLS DH keySize 오류 | `@PostConstruct`에서 `jdk.tls.disabledAlgorithms` Security 프로퍼티의 `DH keySize` 항목 런타임 제거 |
| Jsoup `&amp;` 인코딩으로 링크 0개 파싱 | `doc.html()` + regex → `doc.select("a[href*=...]")` + `.attr("href")`로 변경 |
| 히스토리 수집 nginx 504 | `@Async` + `CollectAsyncService`로 비동기 처리 (202 즉시 반환) |
| 가격 단위 | 두 소스 모두 만원 단위 → 수집 시 `×10,000`해서 원 단위 BIGINT 저장 |

---

### 1.3 종목 API (Course)

```
GET /api/v1/courses               목록 + 검색 + 필터 (page, size 페이지네이션)
GET /api/v1/courses/{id}          상세 (소스별 최신가 포함, isLowest 표시)
GET /api/v1/courses/ranking       상승/하락률 TOP (period, sort, size)
```

**페이지네이션:** `page=0&size=20`, 최대 `size=100`, 이름순 정렬

---

### 1.4 시세 API (Price)

```
GET /api/v1/courses/{id}/prices          차트 데이터 (from, to, interval)
GET /api/v1/courses/{id}/prices/latest   소스별 최신 시세 비교
```

**구독 여부 게이팅:**
- 비구독자: `from` 파라미터와 무관하게 최근 7일로 clamp
- 구독 ACTIVE: 요청 `from` 그대로 허용 (1년 이상)
- JWT에서 memberId 추출 → `SubscriptionRepository`에서 상태 확인 (`@AuthenticationPrincipal OAuth2UserPrincipal`)

**집계 기준:**

| interval | 집계 |
|---|---|
| `DAY` | 일별 `AVG(price)` |
| `WEEK` | 주별 `AVG(price)` |
| `MONTH` | 월별 `AVG(price)` |

---

### 1.5 관심 종목 (Watchlist)

```
GET    /api/v1/watchlist           내 목록 (latestPrice 포함)
POST   /api/v1/watchlist           추가 (courseId, targetPrice)
DELETE /api/v1/watchlist/{id}      삭제
PATCH  /api/v1/watchlist/{id}      목표가·알림 수정
```

**게이팅:** 비구독자 최대 3개, 구독자 무제한 (WATCHLIST_LIMIT_EXCEEDED 403)

---

### 1.6 WebSocket 알림 (Alert)

- STOMP + SockJS (`/ws`)
- 구독 채널: `/user/queue/alert`
- 트리거: 수집 완료 `afterCommit()` → `AlertService.checkAndNotify()`
- 조건: `alert_yn=true` AND `target_price IS NOT NULL` AND `currentPrice <= targetPrice` AND 24시간 내 미발송
- 구독 ACTIVE 회원만 알림 발송

---

### 1.7 구독 결제 (Subscription — TossPayments)

```
GET  /api/v1/subscriptions/plans                  플랜 목록
GET  /api/v1/subscriptions/billing/prepare         결제창 파라미터 (customerKey 발급)
GET  /api/v1/subscriptions/billing/callback        빌링키 발급 + 첫 결제 + 구독 등록 (302 → 프론트)
GET  /api/v1/subscriptions/me                      내 구독 상태
DELETE /api/v1/subscriptions                       구독 취소
GET  /api/v1/subscriptions/payments                결제 이력
```

**플랜:**

| 코드 | 이름 | 가격 |
|---|---|---|
| INDIVIDUAL | 개인 구독 | 월 49,000원 |
| CORPORATE | 법인 구독 | 월 299,000원 |

**자동 갱신:** `BillingScheduler` — 매일 자정 `next_billing_at <= NOW()`인 ACTIVE 구독 재청구
- 연속 3회 실패 → `SUSPENDED`
- `billing_key` AES-256 암호화 저장

---

### 1.8 관리자 수집 API

```
POST /admin/collect          전체 수집 (동기, 완료 후 응답)
POST /admin/collect/history  1년 이력 수집 (비동기, 즉시 202)
```

nginx에서 `/admin/` 경로는 별도 `proxy_read_timeout 600s` 설정.

---

### 1.9 테스트

| 영역 | 방법 |
|---|---|
| 단위 테스트 | JUnit5 + `@MockitoBean` |
| 컨트롤러 테스트 | `@WebMvcTest` + MockMvc |
| 통합 테스트 | Testcontainers (MySQL) |

CI에서 `./gradlew test`로 전체 테스트 수행, 실패 시 PR 머지 차단.

---

## 2. 프론트엔드

**기술 스택:** Next.js 14 App Router, TypeScript, Tailwind CSS, SWR

### 2.1 화면 구성

| 경로 | 화면 | 인증 |
|---|---|---|
| `/` | 홈 — 종목 목록 + 검색/필터 | 불필요 |
| `/courses/[id]` | 종목 상세 — 소스별 최신가 + 차트 | 불필요 |
| `/ranking` | 상승/하락률 랭킹 | 불필요 |
| `/watchlist` | 관심 종목 목록 | 필요 |
| `/my/subscription` | 내 구독 상태 | 필요 |
| `/login` | 로그인 (Google OAuth) | — |
| `/auth/callback` | OAuth 콜백 처리 | — |

### 2.2 핵심 기능

**종목 목록 무한 스크롤:**
- `useSWRInfinite` + `IntersectionObserver`
- 스크롤 끝 도달 시 자동 다음 페이지 (`page=0&size=20`)
- 전체 298개 코스 접근 가능

**시세 차트:**
- 기간 선택: 1일 / 1주 / 1개월 / 3개월 / 1년
- `interval` 자동 선택: `1d·1w·1m → DAY`, `3m → WEEK`, `1y → MONTH`
- 비구독자: 7일 데이터만 표시 + 구독 안내
- 구독자: 1년 이상 전체 기간

**인증 흐름:**
- Google OAuth → `/api/v1/auth/me`로 회원 확인
- Access Token 로컬 스토리지 저장
- 401 응답 시 Refresh Token으로 자동 갱신 (`apiClient` axios 인터셉터)
- 갱신 실패 시 `/login`으로 리다이렉트

**구독 결제:**
- TossPayments SDK로 카드 등록 → 백엔드 콜백 → 구독 활성화
- `successUrl`: 백엔드 주소 (`/api/v1/subscriptions/billing/callback`)
- 결제 완료 후 백엔드가 302 → `/my/subscription?success=1`로 리다이렉트

### 2.3 상태 관리

- **SWR**: 서버 데이터 (종목 목록, 차트, 관심종목, 구독)
- **React useState**: UI 상태 (검색어, 카테고리, 기간 선택)
- 401/404는 SWR `onErrorRetry`에서 재시도 제외

---

## 3. 인프라 / DevOps

### 3.1 배포 환경

```
[User] → membershipflow.site → EC2 t3.small (Ubuntu 22.04)
                                  ├── nginx (80 → 443 HTTPS)
                                  │     ├── /api/*       → backend:8081
                                  │     ├── /oauth2/*    → backend:8081
                                  │     ├── /admin/*     → backend:8081 (timeout 600s)
                                  │     └── /*           → frontend:3000
                                  ├── frontend  (Next.js, Docker)
                                  ├── backend   (Spring Boot, Docker)
                                  ├── mysql     (Docker, 로컬 볼륨)
                                  ├── prometheus (127.0.0.1:9090)
                                  └── grafana   (127.0.0.1:3001)
```

- **도메인:** membershipflow.site (가비아 DNS → EC2 Elastic IP)
- **SSL:** Let's Encrypt (certbot, 자동 갱신 cron)
- **모니터링:** Prometheus + Grafana (로컬호스트 전용, 외부 미노출)

### 3.2 CI/CD

```
push to main
  → GitHub Actions
    ① Lint + Type Check + Test + Build
    ② Docker image build → ghcr.io/ohhalim/{repo}:latest
    ③ SSH → EC2: docker compose pull && docker compose up -d --no-deps {service}
```

- 백엔드, 프론트엔드 각각 독립 파이프라인
- CI 실패 시 배포 차단

### 3.3 Git 브랜치 전략

```
이슈 생성 → feat/{이슈번호}/{설명} 브랜치
→ PR (브랜치 → develop)
→ PR (develop → main)
→ CI 통과 후 머지
→ 자동 EC2 배포
```

- `develop`, `main` 직접 커밋 금지
- PR 머지 후 브랜치 삭제 안 함 (롤백 가능성 보존)

### 3.4 서버 환경 변수 (`.env`)

```
DB_PASSWORD
JWT_SECRET
AES_SECRET_KEY
GOOGLE_CLIENT_ID / GOOGLE_CLIENT_SECRET
TOSS_CLIENT_KEY / TOSS_SECRET_KEY
FRONTEND_URL (https://membershipflow.site)
OAUTH2_REDIRECT_URI
```

### 3.5 nginx 주요 설정

- `proxy_set_header X-Forwarded-For` / `X-Forwarded-Proto` → Spring의 `ForwardedHeaderFilter`로 신뢰
- `/admin/` 경로: `proxy_read_timeout 600s` (장기 수집 요청 대응)
- HTTPS 강제 리다이렉트 (80 → 443)

---

## 4. 데이터 현황

> 2026-06-25 기준

| 항목 | 수치 |
|---|---|
| 수집 종목 수 (`membership_course`) | 298개 |
| 시세 이력 (`price_history`) | 3,409건 |
| 이력 기간 | 2025-06-26 ~ 2026-05-21 (주간 스냅샷) |
| 수집 소스 | 동부회원권, 동아회원권 (2개) |

---

## 5. 알려진 미구현 항목

| 항목 | 비고 |
|---|---|
| 비구독자 차트 UX | 7일 초과 요청 시 `subscriptionRequired: true` 반환하지만 프론트에서 UI 안내 미완성 |
| 알림 읽음 처리 (`PATCH /api/v1/alerts/{id}/read`) | API 설계만 존재, 미구현 |
| 동부회원권 콘도·피트니스 탭 수집 | 현재 골프만 수집 |
| 종목 이름 정규화 | 소스별 표기 차이 미해결 (예: "레이크사이드CC" vs "레이크사이드 CC") |
| 비구독자 찜 한도 (`WATCHLIST_LIMIT_EXCEEDED`) | 게이팅 로직 구현, 프론트 안내 UI 미완성 |
| Spring Batch 전환 | 현재 `@Scheduled` + `@Async` |
| ShedLock (다중 인스턴스 스케줄 중복 방지) | 단일 서버 MVP이므로 미적용 |
| Push 알림 / 이메일 | WebSocket만 지원 |
