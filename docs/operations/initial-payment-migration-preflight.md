# 초기 결제 상태 마이그레이션 사전 점검

`V18__initial_payment_processing_guard.sql`은 결제 시도를 자동 삭제하거나 상태 변경하지 않는다.
기존 `PENDING` 행은 결제 외부 호출 여부를 확인하기 전까지 보존한다.

## 배포 전 조회

```sql
SELECT member_id,
       COUNT(*) AS active_attempt_count,
       GROUP_CONCAT(CONCAT(id, ':', status, ':', customer_key)
                    ORDER BY id SEPARATOR ', ') AS attempts
FROM billing_attempt
WHERE status IN ('PENDING', 'PROCESSING')
GROUP BY member_id
HAVING COUNT(*) > 1;
```

아래 조회 결과가 0건이어야 `uk_billing_attempt_member_active`를 추가할 수 있다.

```sql
SELECT id, member_id, status, customer_key, order_id, billing_key, expires_at
FROM billing_attempt
WHERE status IN ('PENDING', 'PROCESSING')
ORDER BY member_id, id;
```

`billing_key` 또는 `order_id`가 있는 행은 만료·삭제·재사용하지 않는다. Toss 승인 상태와
`payment_history`를 대조한 뒤 운영자가 개별 상태를 결정한다. 중복 데이터가 남아 있으면
Flyway의 UNIQUE KEY 추가가 실패하므로 배포를 계속하지 않는다.

## 애플리케이션 동작

새 `prepare` 요청은 회원 행을 잠근 뒤 만료된 `PENDING` 중 외부 결제 정보가 없는 행만
`EXPIRED`로 표시하고 새 시도를 생성한다. 외부 결제 정보가 있는 행과 `PROCESSING` 행은
자동 만료하지 않고 `PAYMENT_IN_PROGRESS`로 차단한다.

## V19 외부 요청 멱등키

최초 결제는 두 번의 Toss POST 요청으로 구성된다.

1. 빌링키 발급: `POST /v1/billing/authorizations/issue`
2. 최초 결제 승인: `POST /v1/billing/{billingKey}`

각 요청의 `Idempotency-Key`를 외부 호출 전에 `billing_attempt`에 저장한다.

| 중단 위치 | 저장 상태 | 재처리 |
|---|---|---|
| 빌링키 발급 전·응답 유실 | `issue_idempotency_key`만 존재 | 같은 키로 빌링키 발급 재호출 |
| 빌링키 저장 후·결제 응답 유실 | `charge_idempotency_key`, `order_id` 존재 | 같은 키로 결제 승인 재호출 후 주문 조회 |
| V19 이전 PROCESSING, `order_id` 존재 | 결제 멱등키 없음 | 결제 승인 재호출 금지, `order_id` 조회만 수행 |
| V19 이전 PROCESSING, 빌링키·주문번호 없음 | 발급 멱등키 없음 | 새 키 생성·발급 재호출 금지, 10분까지 처리 상태 유지 |
| 빌링키·주문번호 없이 10분 경과 | 과금 가능한 정보 없음 | `FAILED` 처리 후 새 카드 인증 허용 |

결제 완료 처리 조건:

- 응답 `orderId`와 저장된 `order_id` 일치
- 응답 `totalAmount`와 플랜 금액 일치
- 응답 `status = DONE`
- 응답 `type = BILLING`
- 응답 `paymentKey` 존재

조건 불일치 응답은 구독·결제 내역 저장에 사용하지 않는다.
