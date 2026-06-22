# 04 — 로드맵 & 다음 액션

## 로드맵

- [x] **M1-a**: 스캐폴딩 + 코어 스키마 (Flyway V1) — 첫 커밋 `cf081bc` ✅
- [ ] **M1-b**: 수집 소스 1개 골라 Jsoup 크롤러 작성, `price_history` 적재
- [ ] **M2**: 시세 차트 API + 프런트 최소 화면
- [ ] **M3**: 찜 + 목표가 WebSocket 알림
- [ ] **M4**: 수집 소스 추상화 + 2번째 소스 추가

## 바로 다음 할 일 (M1-b)

1. **수집 소스 1개 정하기** — 동부·동아·삼일·회원권114·한울·광장 중에서 HTML이 제일 단순한 곳.
   - 개발자도구로 열어서 시세가 그냥 `<table>`로 박혀 있는 데가 제일 쉬움.
   - JS로 동적 렌더링하는 곳은 피함(Jsoup으로 안 긁힘 → Selenium 필요해짐).
2. **JPA 엔티티 작성** — `CrawlSource`, `MembershipCourse`, `PriceHistory` (스키마는 V1에 이미 있음, `ddl-auto: validate`).
3. **Jsoup 크롤러 1개** — 종목명 + 시세 파싱 → `membership_course` upsert + `price_history` insert.
4. **@Scheduled 배치** — 주기 수집(예: 매일 1회). 처음엔 수동 트리거 엔드포인트로 테스트.

## 수집 소스 후보 (조사 필요)

| 거래소 | 비고 |
|---|---|
| 동부회원권 | 대형 |
| 동아회원권 | 대형 |
| 삼일회원권 | 대형 |
| 회원권114 | 시세표 중심 |
| 한울회원권 | |
| 광장회원권 | |

> 다음 세션에서: 위 사이트 중 하나 열어서 시세 페이지 URL + HTML 구조 확인 →
> Claude에게 "이 URL 구조 까보고 크롤러 짜줘"라고 요청하면 됨.

## 실행 환경 메모

- 로컬에 MySQL 필요 (`membershipflow` DB). 없으면 docker-compose 추가 예정.
- `application.yml`: DB는 `DB_USERNAME`/`DB_PASSWORD` 환경변수 (기본 root/root), 포트 8081.
- 실행: `./gradlew bootRun`
- API 문서: `/swagger-ui.html`

## 끊는 지점 / 회피 방지

- 이 프로젝트는 **취업 포폴이 본체.** 수익화/신규 아이디어로 범위 키우지 말 것.
- 막히면 "더 좋은 아이템"으로 도망가지 말고 구현으로 복귀.
