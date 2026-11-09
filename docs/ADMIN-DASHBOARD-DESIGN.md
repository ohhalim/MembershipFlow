# 관리자 대시보드 설계

## 1. 문서 상태

- 상태: 설계
- 구현 범위: 미착수
- 대상 저장소
  - 백엔드: `MembershipFlow`
  - 프론트엔드: `MembershipFlow-front`
- 목적
  - 가입자 식별
  - 구독 상태 확인
  - 관심 회원권·목표가 알림 이용 현황 확인
  - 결제 문제 사용자 확인

## 2. 현재 구조

### 확인된 기능

- `MemberRole.USER`, `MemberRole.ADMIN` 권한 구분
- JWT role claim과 Spring Security authority 연결
- 관리자 수집 실행 API: `/admin/collect/**`
- 관리자 수집 이상 WebSocket 알림
- 회원, 구독, 관심 회원권, 알림 발송 이력 저장
- Google Analytics 익명 페이지 조회 수집

### 확인된 제한

- 프론트엔드 `/admin` 화면 없음
- 전체 회원·구독 조회 API 없음
- `/api/v1/auth/me` 응답에 role 없음
- 마지막 로그인 시각 없음
- 회원별 검색·상세 조회 행동 이력 없음
- Google Analytics page view와 내부 member ID 연결 없음
- 가입자·구독자 확인 시 DB 직접 조회 필요

## 3. 설계 결정

### 3.1 1차 범위: 읽기 전용 운영 대시보드

- 조회 기능만 제공
- 회원 역할 변경 제외
- 강제 구독 활성화·해지 제외
- 결제 환불·재처리 제외
- CSV 내보내기 제외
- 외부 결제 식별자·빌링키·토큰 노출 제외

결제와 이용 권한을 변경하는 명령은 조회 화면의 사용 패턴과 실제 운영 요구를 확인한 뒤 별도 작업으로 분리한다.

### 3.2 배포 구조

- 별도 관리자 서비스 추가 없음
- 기존 Next.js 저장소에 `/admin` 전용 레이아웃 추가
- 기존 Spring Boot에 `/api/v1/admin/**` 읽기 API 추가
- 기존 `/api` NGINX 프록시 사용
- API 권한 검증을 실제 보안 경계로 사용

현재 사용자 수와 운영 규모에서는 별도 관리자 애플리케이션·데이터베이스 구성이 추가 운영 비용만 증가시킨다. 관리자 기능 증가 또는 내부 운영자 증가 시 별도 배포 단위를 재검토한다.

### 3.3 데이터 기준

- 집계 기준 시간대: `Asia/Seoul`
- 응답에 `asOf` 포함
- 서비스 이용 가능 판정: `Subscription.isActiveAt(now)`와 동일한 규칙
- `ACTIVE`: 이용 가능
- `CANCELLED`이며 `now < nextBillingAt`: 잔여 기간 동안 이용 가능
- `PAYMENT_FAILED`, `SUSPENDED`: 결제 문제 사용자

관리자 조회용 이용 가능 판정을 별도로 재작성하지 않고 기존 구독 도메인 규칙을 재사용한다.

## 4. 화면 구성

### 4.1 관리자 전용 레이아웃

```text
/admin
├── 상단: 관리자 표시, 조회 기준 시각
├── 운영 지표
├── 이용 단계 현황
└── 가입자 목록
```

기존 모바일 사용자 레이아웃의 `SideNav`, `MarketSidebar`, `BottomTabBar`는 사용하지 않는다.

### 4.2 운영 지표

- 전체 가입자 수
- 오늘 신규 가입자 수
- 최근 7일 신규 가입자 수
- 관심 회원권 등록 사용자 수
- 목표가 알림 활성 사용자 수
- 현재 이용 가능한 구독자 수
- 결제 문제 사용자 수
- 최근 7일 목표가 알림 발송 수

가입 → 관심 등록 → 알림 활성 → 구독은 엄격한 순차 퍼널이 아니므로 전환율로 표시하지 않고 `이용 단계 현황`으로 표시한다.

### 4.3 가입자 목록

| 필드 | 출처 | 노출 기준 |
|---|---|---|
| 회원 ID | `member.id` | 노출 |
| 이메일 | `member.email` | 관리자 화면에만 노출, 로그 제외 |
| 이름 | `member.name` | 노출 |
| 가입 방식 | `member.provider` | 노출 |
| 가입일 | `member.created_at` | KST 표시 |
| 플랜 | `subscription_plan.name` | 미구독 시 `-` |
| 구독 상태 | `subscription.status` | 미구독 시 `NONE` |
| 이용 가능 | `Subscription.isActiveAt` | `true` / `false` |
| 관심 종목 수 | `watchlist` 집계 | 노출 |
| 알림 활성 수 | `watchlist.alert_yn` 집계 | 노출 |
| 알림 발송 수 | `alert_log` 집계 | 노출 |

### 4.4 검색·필터

- 이메일·이름 검색
- 구독 상태 필터
  - `ALL`
  - `NONE`
  - `ACTIVE`
  - `CANCELLED`
  - `PAYMENT_FAILED`
  - `SUSPENDED`
- 기본 정렬: 가입일 내림차순
- 기본 페이지 크기: 20
- 최대 페이지 크기: 50

임의 sort field는 받지 않는다. 1차 구현은 가입일 내림차순으로 고정한다.

## 5. API 계약

### 5.1 관리자 요약

```http
GET /api/v1/admin/overview
```

```json
{
  "asOf": "2026-08-28T14:00:00+09:00",
  "totalMembers": 24,
  "newMembersToday": 2,
  "newMembers7Days": 7,
  "membersWithWatchlist": 8,
  "alertEnabledMembers": 5,
  "activeSubscribers": 3,
  "paymentIssueSubscribers": 1,
  "alertsSent7Days": 12
}
```

### 5.2 가입자 목록

```http
GET /api/v1/admin/members?page=0&size=20&query=&subscriptionStatus=ALL
```

```json
{
  "content": [
    {
      "memberId": 12,
      "email": "user@example.com",
      "name": "사용자",
      "provider": "GOOGLE",
      "createdAt": "2026-08-28T10:30:00+09:00",
      "planName": "월간 구독",
      "subscriptionStatus": "ACTIVE",
      "serviceActive": true,
      "watchlistCount": 3,
      "alertEnabledCount": 2,
      "alertSentCount": 4
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 24,
  "totalPages": 2
}
```

Spring `Page` 객체를 그대로 직렬화하지 않고 명시적 페이지 응답 DTO를 사용한다.

### 5.3 인증 사용자 응답 확장

```http
GET /api/v1/auth/me
```

```json
{
  "id": 1,
  "email": "admin@example.com",
  "name": "관리자",
  "role": "ADMIN"
}
```

기존 필드 유지와 role 추가만 수행한다.

## 6. 백엔드 구조

```text
com.membershipflow.admin
├── controller
│   └── AdminDashboardController
├── service
│   └── AdminDashboardService
├── dto
│   ├── AdminOverviewResponse
│   ├── AdminMemberResponse
│   └── AdminPageResponse
└── repository
    └── AdminDashboardQueryRepository
```

### 책임

- Controller
  - 입력값 검증
  - page·size 상한 적용
  - 조회 API 제공
- Service
  - KST 기준 시각 계산
  - 구독 이용 가능 판정
  - 조회 결과 조립
  - `@Transactional(readOnly = true)` 적용
- QueryRepository
  - 회원·구독 기본 페이지 조회
  - 페이지 회원 ID 기준 관심·알림 집계
  - 요약 지표 집계

관리자 조회는 여러 도메인을 결합하는 read model이므로 기존 도메인 Repository에 관리자 전용 쿼리를 분산하지 않는다.

## 7. 조회 전략

### 7.1 가입자 목록

```text
1. 회원 + 구독 기본 정보 페이지 조회
2. 조회된 member ID 목록 추출
3. member ID별 watchlist 수·alert 활성 수 일괄 조회
4. member ID별 alert_log 수 일괄 조회
5. 응답 DTO 조립
```

회원마다 구독·관심·알림 쿼리를 반복하는 N+1 조회는 사용하지 않는다. 페이지 크기와 무관하게 고정된 쿼리 수를 유지한다.

### 7.2 요약 지표

- 의미가 다른 지표는 별도 count 쿼리로 유지
- 하나의 복잡한 native SQL로 결합하지 않음
- 첫 운영 측정 전 캐시 추가 없음
- 응답 지연 확인 후에만 30초 이내 짧은 캐시 검토

### 7.3 인덱스

검토 후보:

- `member(created_at)`
- `subscription(status, next_billing_at)`
- `alert_log(sent_at)`

마이그레이션 선반영 없음. 운영 데이터 기준 `EXPLAIN ANALYZE`, 조회 p95 측정 후 필요한 인덱스만 추가한다.

## 8. 보안·개인정보

### 8.1 API 인가

`SecurityConfig`에서 일반 인증 matcher보다 먼저 적용한다.

```java
.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
```

검증 결과:

- 미로그인: `401`
- `USER`: `403`
- `ADMIN`: `200`

프론트의 관리자 메뉴 숨김과 redirect는 사용자 경험용이다. 실제 접근 차단은 백엔드에서 수행한다.

### 8.2 관리자 계정 부여

공개 역할 변경 API를 추가하지 않는다. 최초 관리자는 운영 DB에서 명시적으로 지정한다.

```sql
UPDATE member
SET role = 'ADMIN'
WHERE email = :adminEmail;
```

### 8.3 응답 제외 필드

- password
- providerId
- billingKey
- customerKey
- externalCustomerId
- externalSubscriptionId
- 결제 토큰
- 카드 전체 번호
- refresh token

### 8.4 로그

- 관리자 member ID
- endpoint
- request ID
- result count
- 처리 시간
- 조회 시각

이메일, 이름, 검색어 원문은 로그에 기록하지 않는다. 응답에는 `Cache-Control: no-store`를 적용한다.

## 9. 프론트엔드 구조

```text
src/app/admin/
├── layout.tsx
├── page.tsx
└── __tests__/

src/components/admin/
├── AdminSummary.tsx
├── AdminMemberFilters.tsx
└── AdminMemberTable.tsx

src/lib/api/admin.ts
src/lib/hooks/useAdminDashboard.ts
```

### 접근 흐름

```text
/admin 접근
  → /api/v1/auth/me
  → checking: 로딩 화면
  → anonymous: /login 이동
  → USER: 접근 거부 화면
  → ADMIN: overview와 members 조회
```

관리자 링크는 `AuthUser.role === 'ADMIN'`일 때만 표시한다.

### 상태 처리

- 초기 로딩
- 빈 가입자 목록
- 검색 결과 없음
- 인증 서버 조회 실패
- 관리자 API `403`
- 관리자 API `5xx`
- 페이지 이동 중 이전 데이터 유지

## 10. 관측성

### 기존 지표 활용

- `http_server_requests_seconds`
- HTTP status별 관리자 API 오류
- Loki 구조화 로그
- 요청 ID

### 추가 검토 지표

- `admin_dashboard_query_seconds`
- endpoint별 조회 건수
- 결과 행 수

목표값은 구현 후 동일 조건 측정으로 확정한다. 설계 단계에서 성능 개선 수치를 미리 약속하지 않는다.

## 11. 테스트

### 백엔드

1. Security 통합 테스트
   - anonymous `401`
   - USER `403`
   - ADMIN `200`
2. Repository 통합 테스트
   - 미구독 회원
   - 활성 구독 회원
   - 해지 후 잔여 기간 회원
   - 결제 실패 회원
   - 관심·알림 집계
   - 페이지네이션 중복·누락 없음
3. Service 테스트
   - 고정 Clock 기준 오늘·7일 범위
   - `isActiveAt` 규칙과 응답 일치
4. Controller 테스트
   - size 최대 50
   - 잘못된 상태 필터 `400`
   - 명시적 페이지 응답 계약

### 프론트엔드

1. ADMIN만 화면 렌더링
2. USER 접근 거부
3. anonymous 로그인 이동
4. 요약 지표 렌더링
5. 이메일·이름 검색 요청
6. 구독 상태 필터 요청
7. 페이지 이동
8. loading·empty·error 상태

## 12. 배포·검증 순서

1. 백엔드 API와 role 응답 배포
2. 운영 관리자 계정 role 확인
3. ADMIN·USER·anonymous 권한 검증
4. 운영 DB 기준 요약 수치와 직접 count 결과 대조
5. 프론트 관리자 화면 배포
6. 가입자 목록과 운영 DB 표본 대조
7. 관리자 API 응답 시간·오류율 확인

백엔드 응답 필드 추가는 기존 프론트와 호환되므로 백엔드를 먼저 배포한다.

## 13. 완료 기준

- 관리자만 `/api/v1/admin/**` 접근 가능
- 가입자 수와 목록을 DB 직접 조회 없이 확인 가능
- 활성 구독 판정이 사용자 서비스 판정과 동일
- 관심 등록·알림 활성·알림 발송 현황 확인 가능
- 페이지당 조회 쿼리 수 고정
- 민감 결제 정보 응답·로그 미노출
- 백엔드·프론트 테스트 통과
- 운영 DB 표본 대조 완료

## 14. 2차 작업 후보

현재 데이터만으로 확인할 수 없는 항목:

- 마지막 로그인
- 사용자별 검색
- 회원권 상세 조회
- 최근 활동 시각
- 기능별 재방문

필요성이 확인되면 `user_activity_event`를 별도 이슈로 설계한다.

- 허용 이벤트 enum 사용
- 서버에서 인증된 member ID 기록
- 검색어 원문·IP·User-Agent 저장 제외
- 보존 기간 정의
- 관리자 화면에는 집계 결과만 노출

1차 관리자 대시보드와 사용자 행동 추적을 한 PR에 포함하지 않는다.
