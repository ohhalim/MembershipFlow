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
