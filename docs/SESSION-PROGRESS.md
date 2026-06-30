# 세션 진행 현황 & 다음 할 일

> 마지막 업데이트: 2026-06-30

---

## 완료된 작업

### 버그 수정
| PR | 내용 | 브랜치 | 상태 |
|----|------|--------|------|
| BE#82 | 랭킹 기준가 탐색 범위 ±1일→±7일 확장 | fix/82/ranking-base-price-search-window | ✅ main 머지 |
| BE#94 | 정렬 DB 레벨 native query (sort 502 수정) | fix/46/sort-502-loop | ✅ main 머지 |
| FE#52 | useSWRInfinite SWR 키 안정화 (정렬 무한 루프) | fix/46/swr-key-stability | ✅ main 머지 |
| FE#54 | useSWRInfinite revalidateOnFocus/Reconnect 비활성화 | fix/53/swr-revalidate-on-focus | ✅ develop 머지 |
| BE#103 | docker-compose 백엔드 healthcheck 추가 (배포 502 수정) | fix/102/deploy-healthcheck | ✅ main 머지 |

### 기능 추가
| PR | 내용 | 상태 |
|----|------|------|
| BE#86 | 랭킹 페이지네이션 (RankingPageResponse) | ✅ main 머지 |
| FE | useRankingInfinite 무한 스크롤 | ✅ main 머지 |

### 문서
| 파일 | 내용 | 상태 |
|------|------|------|
| docs/TECH-DEBT.md | 9개 기술 부채 이슈화 | ✅ main 머지 (PR#107) |

---

## 대기 중인 PR

| PR | 내용 | 방향 |
|----|------|------|
| FE#55 | [Release] SWR 탭 전환 리패치 방지 | develop→main |

---

## 기술 부채 백로그 (우선순위 순)

> 상세 내용: `docs/TECH-DEBT.md`

| TD | 이슈 | 내용 | 브랜치 | 상태 |
|----|------|------|--------|------|
| TD-1 | BE#96 | 크롤링 트랜잭션 분리 (DB 커넥션 점유 → 502) | fix/96/collect-transaction-separation | ⬜ |
| TD-2 | BE#97 | JWT 매 요청 DB 조회 제거 | fix/97/jwt-remove-db-lookup | ⬜ |
| TD-3 | BE#98 | AlertService N+1 | fix/98/alert-n-plus-one | ⬜ |
| TD-4 | BE#99 | 랭킹 findAll() 전체 메모리 조회 | — | ⬜ |
| TD-5 | FE#48 | 검색 디바운스 (글자마다 API 호출) | — | ⬜ |
| TD-6 | FE#49 | OAuth 토큰 URL 노출 | — | ⬜ |
| TD-7 | FE#50 | AccessToken localStorage XSS | — | ⬜ |
| TD-8 | FE#51 | SEO (lang, sitemap, openGraph) | — | ⬜ |
| TD-9 | BE#100 | 가격 비정규화 부재 | — | ⬜ |

---

## 다음에 할 일 (우선순위 순)

### 1. UI 데스크톱 대응 (가장 먼저)

**문제:**
- 현재 `max-w-md mx-auto`로 고정 → 데스크톱에서 가운데 좁은 열만 보임
- BottomTabBar가 모바일 전용 → 데스크톱에서 어색
- Header가 `pt-12`로 모바일 safe-area 대응 → 데스크톱에서 과도한 여백

**파일 위치:**
- `src/app/(main)/layout.tsx` — `max-w-md` 수정 포인트
- `src/components/layout/BottomTabBar.tsx` — 데스크톱에서 사이드바 또는 상단 네비로 전환
- `src/components/layout/Header.tsx` — `pt-12` 조정
- `src/app/layout.tsx` — `lang="en"` → `lang="ko"` 수정 필요

**방향 (토스증권 스타일):**
```
모바일: 현재 구조 유지 (BottomTabBar)
데스크톱(lg 이상):
  - 좌측 사이드바 네비게이션
  - 콘텐츠 영역 max-w-6xl 또는 full-width
  - 2컬럼 레이아웃 (목록 + 상세)
```

**구체적 작업:**
1. `layout.tsx`: `max-w-md` → `max-w-2xl lg:max-w-6xl` + 반응형
2. `BottomTabBar`: 모바일은 하단, `lg:` 이상은 좌측 사이드바
3. `Header`: 모바일/데스크톱 분기
4. 홈 페이지: 데스크톱에서 2컬럼 (좌: 목록, 우: 급등/급락 요약)

---

### 2. 홈 화면 개선 (UI 개선 후)

**추가할 것:**
- 상단 급등/급락 TOP3 수평 스크롤 섹션 (랭킹 API 재사용)
- CourseCard 변동률 뱃지 시각 개선 (컬러 배경)
- 두 거래소 가격 차이 표시 (동아 vs 동부)

---

### 3. 스파크라인 차트 (홈 UI 개선 후)

**배경:**
- 동아골프는 `DongaHistoryCollector`로 일별 히스토리 수집 중 → DB에 데이터 있음
- 동부는 히스토리 없음 (주간 단위)

**작업:**
- BE: 코스 목록 API에 `sparkline: number[]` 추가 (최근 7일 가격 배열)
- FE: SVG 미니 라인 차트를 CourseCard에 추가

---

### 4. AI 채팅 기능 (스파크라인 이후)

**아키텍처 결정:**
- 순수 RAG보다 **Text-to-SQL + LLM 포맷팅**이 구조화된 가격 데이터에 더 적합
- 비정형 질문(추천, 트렌드 설명)만 RAG 또는 정적 지식베이스

**토큰 비용 최적화:**
- Claude Haiku 사용 (Sonnet 대비 20배 저렴)
- 의도 분류 → SQL 실행 → LLM 포맷팅 파이프라인
- 시맨틱 캐시 (동일/유사 질문 재사용)

**포트폴리오 가치:** 실운영 서비스 + AI 연동 + 비용 최적화 = 강력한 차별점

---

### 5. 알림 기능 (이메일)

**현재 상태:**
- 백엔드 WebSocket/STOMP 완성 (목표가 도달 시 발송)
- 프론트 STOMP 클라이언트 없음 → 실제 알림 전달 안 됨
- WebSocket은 사이트 열려있을 때만 → 실용성 낮음

**방향:**
- 이메일 알림 먼저 (Google SMTP, 구글 로그인으로 이메일 이미 보유)
- 카카오 알림톡은 사업자등록 후

---

### 6. SEO (TD-8)

**빠른 작업 (반나절):**
- `lang="en"` → `lang="ko"`
- `app/sitemap.ts` 동적 생성
- `app/robots.ts`
- openGraph, metadataBase 추가
- Google Search Console 등록

**이후:**
- 코스 상세 페이지 SSR 전환 (핵심 SEO 효과)

---

## 데이터 수집 현황

| 거래소 | 수집 항목 | 미수집 (가져올 수 있음) |
|--------|-----------|------------------------|
| 동아골프 | 종목명, 금일시세 | **등락률** (`▲ 400 (0.9%)`) |
| 동부회원권 | 종목명, 구분, 홀수, 금주시세 | **전주시세**, **등락** |
| 동아 히스토리 | 일별 가격 히스토리 | — (이미 수집 중) |

---

## 진행 규칙 (잊지 말 것)

- 이슈 먼저 → 브랜치 → PR(→develop) → CI 통과 → 머지 → PR(develop→main)
- CI 에러 시 머지 금지
- develop/main 직접 커밋 금지
- PR 머지 후 브랜치 삭제 금지
- Main PR 제목에 `[Release]` 명시
