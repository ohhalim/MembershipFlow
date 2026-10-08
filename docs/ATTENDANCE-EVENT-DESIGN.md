# 7일 연속 출석 이벤트 설계

## 1. 문서 상태

- 상태: 설계 중
- 관련: #423, [정책 문서](ATTENDANCE-EVENT-POLICY.md)
- 작성: `attendance` DDL은 본인 작성, 리뷰 내용은 AI가 정리
- 마이그레이션: `src/main/resources/db/migration/V24__attendance_event.sql` (10/12 작성 예정)

## 2. 테이블

### 2.1 `attendance`

출석 한 번에 한 줄. 누가, 며칠에, 그 회차의 몇 일차인지 저장한다.

```sql
CREATE TABLE attendance (
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    member_id      BIGINT   NOT NULL,
    attend_date    DATE     NOT NULL,   -- 출석 날짜 (KST, 서버 시간 기준)
    day_no         INT      NOT NULL,   -- 이번 회차의 몇 일차인지 (1~7)
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attendance_member_date (member_id, attend_date),
    CONSTRAINT fk_attendance_member FOREIGN KEY (member_id) REFERENCES member (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

- `uk_attendance_member_date`
  - **중복 방지:** 같은 사용자가 같은 날 두 번 출석하면 두 번째 INSERT를 DB가 거절한다 (정책 5절 조건 1).
  - **조회:** 몇 일차인지 계산할 때 쓰는 "어제 기록" 조회(`member_id = ? AND attend_date = ?`)도 이 인덱스로 찾는다.
- `day_no`: 어제 기록이 있고 어제 `day_no`가 7 미만이면 `어제 + 1`, 아니면 1. 그래서 진행 상태 테이블을 따로 두지 않는다.

### 2.2 `coupon`

(작성 예정) 할인권 한 장에 한 줄. 2차 결제 적용과 쿠폰함에서도 그대로 쓴다.

## 3. 인덱스 검토

- 참여자 수 쿼리 `SELECT COUNT(*) FROM attendance WHERE attend_date = ?`가 `uk_attendance_member_date`를 쓸 수 있는가 → (검토 중)

## 4. API

(작성 예정)

## 5. 검증 계획

1. `uk_attendance_member_date` 없이 동시 출석 테스트 → 2건이 저장되는 실패를 재현하고 결과를 남긴다
2. 제약을 추가한 뒤 같은 테스트 → 1건만 저장되는지 확인한다
