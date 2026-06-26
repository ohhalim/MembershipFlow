# MembershipFlow

골프 회원권 시세 추적 SaaS. 실운영 중 → **[membershipflow.site](https://membershipflow.site)**

브로커 사이트 두 곳의 시세를 매일 자동 수집해 차트 시각화, 목표가 도달 시 실시간 WebSocket 알림. 구독(월 49,000원 / 299,000원)으로 전체 기간 데이터 및 관심 종목 무제한.

---

## 스택

Spring Boot 3.5 · Java 21 · MySQL · Spring WebSocket/STOMP · Jsoup · TossPayments  
Next.js 14 · TypeScript · Tailwind CSS · SWR  
Docker Compose · GitHub Actions · nginx · Prometheus · Grafana · AWS EC2

---

## 구조

```
nginx (HTTPS)
  ├── /api, /ws, /admin  →  Spring Boot :8081
  └── /*                 →  Next.js :3000

Scheduler (매일 07:00)
  └── Jsoup 크롤링 (동부회원권, 동아회원권)
        └── afterCommit() → AlertService → WebSocket 알림
```

단일 EC2(t3.small)에 프론트, 백엔드, DB, 모니터링 통합.

---

## 핵심 구현

**시세 수집**  
Jsoup으로 두 소스 크롤링. 구현 중 두 가지 이슈 해결.

동아회원권은 Java 21이 기본 차단하는 구형 DH 키 사이즈 사용. JVM 전체 보안 정책 대신 `@PostConstruct`에서 해당 항목만 런타임 제거로 최소 범위 처리.

Jsoup HTML 파싱 시 `&amp;` → `&` 디코딩 탓에 regex URL 추출 0건. `select("a[href*=...]").attr("href")` 방식으로 전환 해결.

1년치 히스토리 수집은 수십 초 소요로 nginx 504 발생. `@Async` + 즉시 202 반환으로 분리. CoinFlow HTTP 응답 분리 패턴 재적용.

**구독 결제**  
TossPayments 빌링키 방식. 카드 1회 등록 후 월 자동결제. `BillingScheduler`가 매일 자정 만료 구독 재청구, 연속 3회 실패 시 `SUSPENDED`. 빌링키 AES-256 암호화 저장.

구독 상태에 따라 차트 기간(비구독자 7일 clamp)과 관심 종목 한도(비구독자 3개) 게이팅.

**알림**  
수집 완료 후 `afterCommit()` 트리거. 커밋 전 알림 발송 시 DB에 없는 데이터 기준 알림 발생 방지. STOMP `/user/queue/alert` push, `alert_log`로 24시간 중복 방지.

**CI/CD**  
main push → 테스트 통과 → Docker 이미지 빌드 → scp로 nginx 설정·Grafana 프로비저닝 파일 EC2 자동 복사. 수동 SSH 없이 코드 변경 즉시 반영.

---

## 모니터링

Prometheus + Grafana 로컬호스트 전용. 로컬 접근은 SSH 터널 사용.

```bash
make tunnel  # Grafana: localhost:3001 / Prometheus: localhost:9090
```

---

## 로컬 실행

```bash
cp .env.example .env  # DB, JWT, Google OAuth, TossPayments 키 설정
docker compose up -d
```

---

## 관련 프로젝트

- [CoinFlow](https://github.com/ohhalim/CoinFlow) — 비동기 응답 분리, STOMP 구조 원본
- [HomeSweetHome](https://github.com/ohhalim/HomeSweetHome-backend) — TossPayments 결제 정합성 원본
