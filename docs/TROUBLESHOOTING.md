# MembershipFlow 트러블슈팅 기록

개발 중 발생한 주요 문제와 해결 과정을 기록합니다.

---

## 1. Flyway 마이그레이션 연속 실패 (V3)

### 배경
Spring Boot 서버 기동 시 Flyway V3 마이그레이션이 반복 실패하여 서버가 뜨지 않음.

### 원인 분석

**MySQL DDL은 트랜잭션이 없다.**
일반 DML(INSERT, UPDATE)은 트랜잭션 롤백이 가능하지만, DDL(ALTER TABLE, CREATE INDEX)은 즉시 커밋된다. Flyway는 마이그레이션 실패 시 스크립트 전체를 롤백하려 하지만, 이미 실행된 DDL은 DB에 반영된 상태다. 결과적으로 `flyway_schema_history`에 `success=0` 레코드가 남고, 이후 재시도가 불가능해진다.

세 가지 에러가 순차적으로 발생:

**에러 1: Duplicate column name 'crawl_type'**
이전 실패로 V3가 부분 실행되면서 `crawl_type` 컬럼이 이미 추가된 상태. 재시도 시 중복 컬럼 추가 에러.

**에러 2: Cannot change column 'source_id': used in FK constraint 'fk_price_source'**
```sql
-- 실패: FK가 걸린 컬럼은 MODIFY 불가
ALTER TABLE price_history MODIFY COLUMN source_id BIGINT NOT NULL;
```

**에러 3: Duplicate key name 'idx_membership_course_name'**
V1에서 이미 생성된 인덱스를 V3에서 다시 추가하려 함.

### 해결

1. `flyway_schema_history`에서 실패한 V3 레코드 삭제 및 부분 적용된 컬럼 직접 롤백
2. V3 스크립트 수정:
   - FK 제약 먼저 DROP → 컬럼 MODIFY → FK 재추가
   - 중복 인덱스 생성 구문 제거

```sql
-- 수정된 V3 핵심 패턴
ALTER TABLE price_history DROP FOREIGN KEY fk_price_source;
ALTER TABLE price_history MODIFY COLUMN source_id BIGINT NOT NULL;
ALTER TABLE price_history ADD CONSTRAINT fk_price_source
    FOREIGN KEY (source_id) REFERENCES crawl_source (id);
```

### 교훈
- MySQL 환경에서 Flyway 마이그레이션 실패 시 DB 수동 정리 없이는 재시도 불가
- ALTER TABLE 순서: FK DROP → 컬럼 변경 → FK 재추가
- V1~Vn 간 인덱스/컬럼 중복 여부 사전 확인 필요

---

## 2. Hibernate 기동 실패 — 컬럼 누락

### 배경
Flyway 마이그레이션 성공 후에도 `ddl-auto: validate` 설정으로 인해 서버 기동 실패.

```
org.hibernate.tool.schema.spi.SchemaManagementException:
Schema-validation: missing column [updated_at] in table [membership_course]
```

### 원인
`MembershipCourse` 엔티티에 `@UpdateTimestamp` 어노테이션으로 `updatedAt` 필드를 추가했지만, 해당 컬럼을 생성하는 마이그레이션 스크립트가 누락됨.

### 해결
V5 마이그레이션 스크립트 추가:

```sql
ALTER TABLE membership_course
    ADD COLUMN updated_at DATETIME NOT NULL
    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    AFTER created_at;
```

### 교훈
엔티티 필드 추가 시 반드시 대응하는 마이그레이션 스크립트를 함께 작성해야 한다. `ddl-auto: validate`는 이 누락을 런타임에 잡아주는 안전장치이므로 개발 환경에서도 유지하는 것이 바람직하다.

---

## 3. 크롤링 데이터 TINYINT 범위 초과

### 배경
동부회원권 크롤링 실행 시 99건 중 16건이 INSERT 실패.

```
Out of range value for column 'holes' at row 1
```

### 원인
`holes` 컬럼 타입이 `TINYINT UNSIGNED`(0~255)인데, HTML 파싱 로직이 잘못된 컬럼 위치를 읽어 골프장명 등 숫자가 아닌 값을 파싱하거나 255를 초과하는 정수를 반환했다.

```java
// Before — 범위 검증 없음
private Integer parseHoles(String text) {
    try {
        return Integer.parseInt(text.replaceAll("[^0-9]", ""));
    } catch (NumberFormatException e) {
        return null;
    }
}
```

### 해결
255 초과값은 `null`로 처리:

```java
// After
private Integer parseHoles(String text) {
    try {
        int v = Integer.parseInt(text.replaceAll("[^0-9]", ""));
        return (v > 0 && v <= 255) ? v : null;
    } catch (NumberFormatException e) {
        return null;
    }
}
```

수정 후 99건 전체 SUCCESS.

---

## 4. 프론트엔드 API 응답 타입 불일치

### 배경
홈 화면 로드 시 `courses?.map is not a function` 에러 발생.

### 원인
Spring Data의 페이지 응답 구조와 프론트 타입이 불일치.

```json
// 실제 백엔드 응답 (Spring Page)
{
  "content": [...],
  "totalElements": 298,
  "totalPages": 15,
  ...
}

// 프론트가 기대한 형태
[...]
```

추가로 가격 히스토리 API URL 오류(`/price-history` → `/prices`) 및 랭킹 API 파라미터 불일치(`type=rise` → `sort=GAIN`, `period=1` → `period=1d`)도 함께 발견됨.

### 해결
API 클라이언트 레이어에서 응답 매핑 처리:

```typescript
async getList(params): Promise<Course[]> {
  const res = await apiClient.get<{ content: Course[] } | Course[]>('/api/v1/courses')
  return Array.isArray(res) ? res : res.content
}
```

### 교훈
백엔드와 프론트를 독립적으로 개발할 때는 응답 스펙(URL, 파라미터, 필드명)을 API 문서로 먼저 합의하거나, 공유 타입을 사용해야 한다.

---

## 5. 토스페이먼츠 빌링 콜백 연속 실패

### 배경
구독 결제(빌링키 등록) 완료 후 화면이 404 → 401 → Whitelabel Error Page 순서로 연속 실패.

### 구조 이해
토스페이먼츠 빌링 플로우:
```
프론트 → 토스 결제창 → 카드 등록 완료
→ successUrl로 브라우저 리다이렉트 (GET 요청, 쿠키/토큰 없음)
→ 백엔드에서 빌링키 발급 처리
→ 프론트 완료 페이지로 리다이렉트
```

**핵심**: `successUrl`은 토스가 브라우저를 리다이렉트하는 URL이다. 브라우저 GET 요청이므로 JWT 토큰이 없다.

### 에러 1: 404 Not Found
```javascript
// Before — 프론트(3000)로 설정됨
successUrl: `${window.location.origin}/api/v1/subscriptions/callback`
```
Next.js에는 해당 라우트가 없어 404.

**해결**: 백엔드 URL로 변경
```javascript
successUrl: `${process.env.NEXT_PUBLIC_API_URL}/api/v1/subscriptions/callback`
```

### 에러 2: 401 Unauthorized
백엔드가 받았지만 Spring Security가 차단. JWT 없는 요청은 인증 실패.

**해결**: `SecurityConfig` permitAll 목록에 콜백 URL 추가
```java
.requestMatchers(
    "/api/v1/subscriptions/plans",
    "/api/v1/subscriptions/callback",  // 추가
    ...
)
.permitAll()
```

### 에러 3: 브라우저에 JSON 노출
백엔드가 빌링키 처리 후 JSON을 반환하여 사용자가 날 JSON을 보게 됨.

**해결**: 처리 후 프론트로 302 리다이렉트
```java
// Before
return ResponseEntity.ok(subscriptionService.handleCallback(customerKey, authKey));

// After
subscriptionService.handleCallback(customerKey, authKey);
return ResponseEntity.status(HttpStatus.FOUND)
    .location(URI.create(frontendUrl + "/my/subscription?success=1"))
    .build();
```

### 교훈
결제/OAuth 콜백은 브라우저 리다이렉트이므로:
1. 콜백 URL은 반드시 백엔드 주소여야 한다 (프론트 API route가 아님)
2. 인증이 필요 없는 공개 엔드포인트로 설정해야 한다
3. 처리 완료 후 사용자를 프론트 페이지로 리다이렉트해야 한다

이 패턴은 OAuth2 로그인 콜백과 동일한 구조다.

---

## 6. Git 브랜치 그래프 단절 (Squash Merge)

### 배경
PR 머지 후 `git log --graph`에서 브랜치 연결이 보이지 않고 직선으로만 표시됨.

### 원인
`gh pr merge --squash` 사용 시 피처 브랜치의 커밋들을 하나로 압축해 develop에 직접 추가. 머지 커밋이 생성되지 않아 브랜치 분기/합류 기록이 그래프에서 사라짐.

### 해결
develop 브랜치를 squash 이전 커밋으로 리셋 후 `--no-ff` 옵션으로 재머지:

```bash
git reset --hard <squash 이전 커밋>
git merge --no-ff <feature-branch>
git push --force origin develop
```

이후 모든 PR 머지는 `gh pr merge --merge` (기본 merge commit) 사용.

### 교훈
- `--squash`: 커밋 히스토리 정리에 유리하나 브랜치 그래프 단절
- `--no-ff` (merge commit): 브랜치 분기/합류가 그래프에 시각적으로 표현됨
- 팀/프로젝트의 그래프 가시성 요구에 맞는 전략을 초기에 결정하고 일관되게 유지해야 함

---

## 7. 전체 코드 리뷰 — 백엔드 API 계약 불일치

### 배경
전체 코드 리뷰 후 프론트-백엔드 API 응답 필드명 불일치로 인해 골프장 목록/상세 화면이 정상 작동하지 않음을 확인.

### 원인 1: CourseListItemResponse 필드명 불일치
백엔드가 `courseType`(enum), `priceChangeRate`, `latestCollectedAt`(LocalDateTime)을 반환했지만 프론트는 `category`(string), `changeRate`, `updatedAt`(string)을 기대.

### 해결
`CourseListItemResponse` DTO 필드명을 프론트 타입에 맞게 수정:

```java
// Before
record CourseListItemResponse(Long id, String name, CourseType courseType, Double priceChangeRate, LocalDateTime latestCollectedAt ...)
// After
record CourseListItemResponse(Long id, String name, String category, Double changeRate, String updatedAt ...)
```

### 원인 2: CourseDetailResponse — isLowest 미계산, sources 필드 부재
상세 응답에 거래소별 가격 목록(`sources`)이 없었고, 최저가 표시(`isLowest`) 로직 없음.

### 해결
`CourseDetailResponse.of()` 팩토리 메서드에서 최저가 계산 후 `isLowest` 필드 세팅:

```java
Long minPrice = rawPrices.stream()
    .map(LatestSourcePriceResponse::price)
    .filter(Objects::nonNull)
    .min(Long::compareTo).orElse(null);

List<SourcePrice> sources = rawPrices.stream().map(p ->
    new SourcePrice(p.sourceName(), p.sourceUrl(), p.price(),
        p.collectedAt() != null ? p.collectedAt().toString() : null,
        minPrice != null && minPrice.equals(p.price()))
).toList();
```

### 교훈
프론트엔드 타입(`src/lib/types.ts`)을 백엔드 DTO 설계 시 참조하거나, OpenAPI 스펙을 공유 기준으로 삼아야 한다.

---

## 8. 인증 가드 레이스 컨디션 (layout.tsx)

### 배경
로그인 없이 `/my/subscription` 등 인증 필요 페이지에 직접 접근 시, 리다이렉트 전에 자식 컴포넌트가 렌더링되어 불필요한 API 호출(401)이 발생.

### 원인
`useEffect`는 브라우저 하이드레이션 이후에 실행된다. React가 먼저 자식 컴포넌트를 렌더하고 DOM에 마운트한 뒤에야 인증 상태를 체크했기 때문에, 자식의 SWR/fetch가 먼저 실행됨.

```tsx
// Before — 자식이 인증 체크 전에 렌더됨
useEffect(() => {
  if (!auth.isAuthenticated()) router.replace('/login')
}, [router])
return <>{children}</>  // 인증 여부와 관계없이 즉시 렌더
```

### 해결
`checked` 상태를 추가해 인증 확인 전에는 `null` 반환:

```tsx
const [checked, setChecked] = useState(false)
useEffect(() => {
  if (!auth.isAuthenticated()) { router.replace('/login') }
  else { setChecked(true) }
}, [router])
if (!checked) return null
return <>{children}</>
```

### 교훈
Next.js App Router에서 클라이언트 사이드 인증 가드는 `useEffect` 단독으로는 불충분하다. 인증 완료 여부를 별도 상태로 관리해 자식 렌더를 지연시켜야 한다.

---

## 9. SWR 401 무한 재시도

### 배경
인증 필요 API(관심종목, 구독)에서 401이 발생할 때, SWR 기본 설정이 무한 재시도를 유발하여 불필요한 요청이 반복됨.

### 원인
SWR의 기본 `onErrorRetry` 전략은 모든 에러에 대해 지수 백오프로 재시도한다. 401(미인증)이나 404(리소스 없음)는 재시도해도 결과가 변하지 않음에도 반복 시도.

### 해결
각 훅에 `onErrorRetry` 커스터마이즈 적용:

```typescript
useSWR(key, fetcher, {
  onErrorRetry: (error, _key, _config, revalidate, { retryCount }) => {
    if (error?.status === 401 || error?.status === 404) return  // 재시도 없음
    if (retryCount >= 2) return
    setTimeout(() => revalidate({ retryCount }), 3000)
  },
})
```

`useMySubscription`, `useWatchlist`에 적용.

### 교훈
상태 코드별로 재시도 여부를 판단해야 한다. 401/404는 재시도 의미 없음. 서버 에러(5xx)나 네트워크 에러만 재시도 대상.

---

## 10. Next.js useSearchParams Suspense 누락

### 배경
`/my/subscription?success=1` 리다이렉트 후 화면이 간헐적으로 멈추거나 hydration 에러 발생.

### 원인
Next.js 14 App Router에서 `useSearchParams()`는 SSR 단계에서 서스펜스 경계가 없으면 렌더링을 중단(suspend)시킨다. 페이지 컴포넌트가 `useSearchParams()`를 직접 사용하면서 `<Suspense>`로 감싸지 않아 발생.

```tsx
// Before — Suspense 없이 직접 사용
export default function SubscriptionPage() {
  const searchParams = useSearchParams()  // Next.js가 경고/에러 발생
  ...
}
```

### 해결
실제 로직을 별도 컴포넌트로 분리 후 `<Suspense>`로 래핑:

```tsx
function SubscriptionPageContent() {
  const searchParams = useSearchParams()
  ...
}

export default function SubscriptionPage() {
  return (
    <Suspense>
      <SubscriptionPageContent />
    </Suspense>
  )
}
```

### 교훈
Next.js 14에서 `useSearchParams()`, `useParams()` 등 동적 훅은 반드시 `<Suspense>` 경계 안에서 사용해야 한다. `auth/callback/page.tsx`에서 이 패턴을 이미 사용 중이었으나 구독 페이지에 적용 누락.

---

## 12. 동아골프 TLS DH keySize 오류

### 배경
`DongaHistoryCollector`에서 `https://www.dongagolf.co.kr` 접속 시 SSL 핸드셰이크 실패:
```
javax.net.ssl.SSLHandshakeException: DH ServerKeyExchange does not comply to algorithm constraints
```

### 원인
Java 기본 보안 정책(`java.security`)이 `DH keySize < 1024`인 DH 알고리즘을 차단한다. 동아골프 서버가 레거시 DH 파라미터를 사용.

### 해결
`@PostConstruct`에서 런타임에 `jdk.tls.disabledAlgorithms` Security 프로퍼티에서 `DH keySize` 항목만 제거:

```java
@PostConstruct
void init() {
    String current = Security.getProperty("jdk.tls.disabledAlgorithms");
    if (current != null) {
        String updated = Arrays.stream(current.split(","))
                .map(String::trim)
                .filter(s -> !s.startsWith("DH keySize"))
                .collect(Collectors.joining(", "));
        Security.setProperty("jdk.tls.disabledAlgorithms", updated);
    }
}
```

### 교훈
레거시 HTTPS 사이트 크롤링 시 JVM 보안 정책 충돌에 주의. `jsse.enableCBCProtection=false` 같은 전역 설정보다 특정 알고리즘만 선택적으로 제거하는 것이 안전하다.

---

## 13. Jsoup `.html()` `&amp;` 인코딩으로 링크 파싱 0개

### 배경
`DongaHistoryCollector`에서 `custid`, `code` 파라미터를 추출하기 위해 `doc.html()` + regex를 사용했더니 매칭 0건.

### 원인
Jsoup의 `.html()`은 HTML 엔티티를 인코딩한다. `&` → `&amp;`. 정규식 `custid=(\d+)&code=(\d+)`가 `custid=123&amp;code=456`에는 매칭되지 않음.

### 해결
`.html()` + regex → Jsoup selector + `.attr("href")`로 변경. `.attr()`은 디코딩된 속성값을 반환:

```java
// Before
String html = doc.html();
Matcher m = Pattern.compile("custid=(\\d+)&code=(\\d+)").matcher(html);

// After
for (var a : doc.select("a[href*=/membership/info]")) {
    String href = a.attr("href");  // &amp; → & 디코딩됨
    Matcher m = CUSTID_CODE_URL.matcher(href);
    ...
}
```

### 교훈
Jsoup에서 URL 파라미터 추출은 `.html()` 대신 `element.attr("href")`를 사용해야 한다. `.html()`은 HTML 렌더링용이고, `.attr()`은 실제 속성값을 반환한다.

---

## 14. 히스토리 수집 nginx 504 Gateway Timeout

### 배경
`POST /admin/collect/history` 호출 시 298개 종목 이력 수집에 약 5분 소요. nginx 기본 타임아웃(60s) 초과로 504 반환.

### 원인
동기 방식으로 수집 완료까지 HTTP 연결을 유지해야 하는 구조.

### 해결
`@Async` + `CollectAsyncService`로 비동기 처리:

```java
@Async
public void collectHistoryAsync() {
    try {
        int saved = collectService.collectHistory();
        log.info("[동아히스토리] 비동기 수집 완료: {}건", saved);
    } catch (Exception e) {
        log.error("[동아히스토리] 비동기 수집 실패: {}", e.getMessage(), e);
    }
}
```

컨트롤러는 즉시 `202 Accepted` 반환. 수집 진행은 백그라운드 스레드.

`MembershipFlowApplication.java`에 `@EnableAsync` 추가 필수.

### 교훈
5초 이상 걸리는 작업은 HTTP 레이어에서 동기 처리하지 말고 비동기로 분리해야 한다. nginx `proxy_read_timeout`을 늘리는 것은 임시방편이며, 진짜 해결은 비동기 설계다.

---

## 16. /admin 엔드포인트 인증 누락 (보안 취약점)

### 배경
`POST /admin/collect`, `POST /admin/collect/history`가 `SecurityConfig`에서 `permitAll()`로 설정되어 있어 누구나 수집 트리거 가능한 상태였음.

### 원인
초기 개발 시 편의를 위해 `/admin/**`를 공개 경로에 포함시킨 후 수정하지 않음.

### 해결
1. `MemberRole` enum에 `ADMIN("ROLE_ADMIN")` 추가
2. `SecurityConfig`에서 `/admin/**`를 `hasRole("ADMIN")`으로 변경

```java
.requestMatchers("/admin/**").hasRole("ADMIN")
```

JWT 필터가 요청마다 DB에서 회원 정보를 새로 조회하므로 DB에서 역할 변경 즉시 적용됨 (토큰 재발급 불필요).

### 교훈
관리자 기능은 처음부터 인증/인가를 적용해야 한다. `permitAll()`은 정말 공개된 엔드포인트에만 사용.

---

## 17. nginx.conf vs nginx.https.conf 이중화로 설정 미적용

### 배경
WebSocket `/ws` 블록과 `/admin/` timeout을 `nginx.https.conf`에 추가했지만 EC2 nginx에 반영되지 않음.

### 원인
- `docker-compose.yml`이 `nginx/nginx.conf`를 마운트
- 변경은 `nginx/nginx.https.conf`에만 적용
- CD 파이프라인(PR #55)도 `nginx.https.conf`를 복사 → 실제 적용 파일(`nginx.conf`)과 달라 반영 안 됨

```yaml
# docker-compose.yml
- ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro  # 이 파일이 실제 사용됨
```

### 해결
1. `nginx.https.conf` 내용을 `nginx.conf`에 통합
2. `nginx.https.conf` 삭제
3. CD scp-action source를 `nginx/nginx.conf`로 수정
4. `DEPLOYMENT.md`, `server-setup.sh`의 잔여 참조 제거

### 교훈
nginx 설정 파일은 단일 파일로 유지하고 `docker-compose.yml` 마운트 경로와 반드시 일치시켜야 한다. "HTTPS용 별도 파일"은 혼란만 야기한다.

---

## 15. WatchlistService.update latestPrice null 반환

### 배경
관심종목 알림 설정 변경(targetPrice, alertYn) 후 프론트가 받은 응답에서 `latestPrice`가 항상 null.

### 원인
`WatchlistService.update()`가 watchlist 필드 수정 후 `WatchlistResponse.of(watchlist, null)`로 latestPrice를 null로 하드코딩해 반환.

```java
// Before
return WatchlistResponse.of(watchlist, null);  // latestPrice 항상 null
```

### 해결
업데이트 후 해당 골프장의 최신 가격을 조회해 반환:

```java
Long courseId = watchlist.getCourse().getId();
Long latestPrice = priceHistoryRepository.findLatestByCourseIds(List.of(courseId))
        .stream().findFirst().map(ph -> ph.getPrice()).orElse(null);
return WatchlistResponse.of(watchlist, latestPrice);
```

### 교훈
단일 리소스 업데이트 API는 list API와 동일한 수준의 응답 완성도를 제공해야 한다. `list()`에서는 latestPrice를 채우면서 `update()`에서는 null을 반환하는 불일치는 프론트에서 예기치 않은 UI 버그를 유발할 수 있다.
