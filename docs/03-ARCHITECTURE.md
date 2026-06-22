# 03 — 아키텍처

## 기술 스택 (CoinFlow와 동일 — 일관성)

- Java 21, Spring Boot 3.5.14
- Spring Web / Data JPA / Validation / WebSocket / Batch / Actuator
- Flyway + MySQL
- Jsoup (HTML 크롤링)
- springdoc-openapi (API 문서)
- 빌드: Gradle (wrapper는 CoinFlow에서 복사 재사용)

> Kafka / OAuth2 / Security는 MVP에서 일단 제외. 필요해지면 추가.
> 서버 포트는 8081 (CoinFlow와 충돌 방지).

## CoinFlow 재사용 맵

| CoinFlow | MembershipFlow |
|---|---|
| 코인 시세 실시간 수집 | 회원권 시세 수집 배치 (Spring Batch / @Scheduled) |
| 시세 DB 저장/조회 | 동일 (시계열) |
| WebSocket 실시간 전송 | 목표가 도달 알림 |
| 차트 데이터 API | 시세 추이 그래프 API |
| 회원/인증/주문 구조 | 회원/찜/알림 구조 |

**핵심 설계 포인트:** 수집 소스를 인터페이스로 추상화 → 거래소 추가가 O(1).
예: `PriceCollector` 인터페이스 + 소스별 구현체(`DongbuCollector` 등) + 등록/디스패치.

## 데이터 모델 (Flyway V1 — 이미 적용됨)

```
crawl_source        수집 소스 (name, base_url, active)
membership_course   회원권 종목 (name, region, type, source_id)
price_history       시세 시계열 (course_id, price, collected_at, source_id)
member              회원 (email, password, nickname)
watchlist           관심 종목 (member_id, course_id, target_price, alert_yn)
alert_log           알림 발송 이력 (watchlist_id, price, sent_at)
```

관계:
- `membership_course.source_id → crawl_source.id`
- `price_history.course_id → membership_course.id` (핵심 시계열, 인덱스 `(course_id, collected_at)`)
- `watchlist`: member ↔ course 다대다 + 목표가
- `alert_log.watchlist_id → watchlist.id`

실제 DDL은 `src/main/resources/db/migration/V1__init_core_schema.sql` 참조.

## 패키지 구조 (예정)

```
com.membershipflow
├── MembershipFlowApplication.java   (@EnableScheduling)
├── course/      회원권 종목 (엔티티, repo, service, controller)
├── price/       시세 (엔티티, repo, 차트 API)
├── collector/   수집 소스 추상화 + 크롤러 구현체 + 배치
├── member/      회원/인증
├── watchlist/   찜 + 목표가
└── alert/       WebSocket 알림
```
