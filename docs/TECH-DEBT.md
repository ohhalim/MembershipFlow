# 기술 부채 백로그

2026-06-27 전체 코드 리뷰에서 도출. 우선순위 순. 하나씩 이슈 → 브랜치 → PR로 해결.

| TD | 이슈 | 레포 |
|----|------|------|
| TD-1 | [#96](https://github.com/ohhalim/MembershipFlow/issues/96) | backend |
| TD-2 | [#97](https://github.com/ohhalim/MembershipFlow/issues/97) | backend |
| TD-3 | [#98](https://github.com/ohhalim/MembershipFlow/issues/98) | backend |
| TD-4 | [#99](https://github.com/ohhalim/MembershipFlow/issues/99) | backend |
| TD-5 | [front#48](https://github.com/ohhalim/MembershipFlow-front/issues/48) | front |
| TD-6 | [front#49](https://github.com/ohhalim/MembershipFlow-front/issues/49) | front |
| TD-7 | [front#50](https://github.com/ohhalim/MembershipFlow-front/issues/50) | front |
| TD-8 | [front#51](https://github.com/ohhalim/MembershipFlow-front/issues/51) | front |
| TD-9 | [#100](https://github.com/ohhalim/MembershipFlow/issues/100) | backend |

---

## 🔴 Critical — 데이터/트래픽 증가 시 장애

### TD-1. 크롤링이 트랜잭션 내부에서 실행
- **위치**: `CollectService.collectOne()` — `@Transactional` 메서드 안에서 `collector.collect()` (수십 초 네트워크 I/O) 호출
- **문제**: DB 커넥션을 크롤링 종료까지 점유. 소스 N개 = 커넥션 N개가 수십 초 묶임. t3.small 커넥션 풀 고갈 → 502
- **해결**: 크롤링(I/O)을 트랜잭션 밖으로, DB 저장만 `@Transactional`로 분리
- **상태**: ⬜ 미착수

### TD-2. JWT 필터 매 요청 DB 조회
- **위치**: `JwtAuthenticationFilter:40` — 모든 인증 요청마다 `memberRepository.findById()`
- **문제**: 요청량에 비례한 DB 히트. 인증에 DB 불필요
- **해결**: JWT 클레임(memberId, role)만으로 `OAuth2UserPrincipal` 구성, DB 조회 제거
- **상태**: ⬜ 미착수

### TD-3. AlertService N+1
- **위치**: `AlertService.checkAndNotify()` — 루프 안 `existsByWatchlistIdAndSentAtAfter` watchlist 수만큼 호출 + lazy `getMember()`/`getCourse()`
- **해결**: alertLog를 IN 쿼리로 일괄 조회, watchlist는 fetch join으로 member/course 동시 로드
- **상태**: ⬜ 미착수

### TD-4. 랭킹 전체 메모리 조회
- **위치**: `CourseService.getRanking()` — `findAll()`로 전 종목 메모리 적재 후 스트림 필터
- **문제**: TD(정렬 502)와 동일 패턴. 종목 증가 시 OOM/지연
- **해결**: DB 레벨 필터링 + 페이지네이션 native query (sort 수정과 동일 접근)
- **상태**: ⬜ 미착수

### TD-5. 검색 디바운스 없음 (FE)
- **위치**: `app/(main)/page.tsx:59` — `onChange`마다 `setKeyword` → SWR 키 변경 → 글자마다 API 호출
- **해결**: keyword 300ms 디바운스
- **상태**: ⬜ 미착수

### TD-6. OAuth 토큰 URL 쿼리 노출 (FE/BE)
- **위치**: `auth/callback/page.tsx:13` — `searchParams.get('token')`
- **문제**: 토큰이 브라우저 히스토리·액세스 로그·Referer에 노출
- **해결**: 백엔드 OAuth 성공 핸들러에서 HttpOnly 쿠키 발급. 프론트는 토큰 미취급
- **상태**: ⬜ 미착수

---

## 🟡 보안 / 구조

### TD-7. AccessToken localStorage 저장 (XSS)
- **위치**: `lib/auth.ts:10`
- **해결**: HttpOnly 쿠키 전환 (TD-6과 연계). RefreshToken은 이미 쿠키 사용 중
- **상태**: ⬜ 미착수

### TD-8. SEO 전무
- **위치**: `app/layout.tsx` — `lang="en"`(한국어 사이트), openGraph/metadataBase/sitemap/robots 전부 없음, 전 페이지 CSR
- **문제**: 구글 색인 안 됨 ("membershipflow 검색해도 안 뜸")
- **해결**: `lang="ko"`, openGraph+metadataBase, `app/sitemap.ts`·`app/robots.ts`, Google Search Console 등록
- **상태**: ⬜ 미착수

### TD-9. 가격 비정규화 부재
- **위치**: 정렬/랭킹마다 price_history JOIN 또는 2차 조회
- **해결**: `membership_course.latest_price` 컬럼 추가, 수집 시 갱신 → JOIN/2차 조회 제거 (TD-1·TD-4의 근본 해결)
- **상태**: ⬜ 미착수

---

## 진행 규칙
- 각 항목: 이슈 생성 → `fix|feat/{issue}/{slug}` 브랜치 → PR(→develop) → PR(develop→main, `[Release]`)
- CI 통과 후에만 머지, 머지 후 브랜치 삭제 금지
