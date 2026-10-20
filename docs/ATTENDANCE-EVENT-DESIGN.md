# 7일 연속 출석 이벤트 설계

## 1. 문서 상태

- 상태: 설계 중
- 관련: #423, [정책 문서](ATTENDANCE-EVENT-POLICY.md)
- 작성
  - `attendance` DDL은 본인 작성, 리뷰 내용은 AI가 정리
  - `coupon` DDL은 AI가 빈칸 템플릿 제시 → `expires_at`, `used_at`은 본인 작성, 유니크 키 한 줄과 설명은 AI 작성 → 본인 승인
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

할인권 한 장에 한 줄. 2차 결제 적용과 쿠폰함에서도 그대로 쓴다.

```sql
CREATE TABLE coupon (
    id             BIGINT   NOT NULL AUTO_INCREMENT,
    member_id      BIGINT   NOT NULL,
    attendance_id  BIGINT   NOT NULL,   -- 어떤 출석(7일째)으로 받았는지
    discount_rate  INT      NOT NULL,   -- 할인율 (10)
    expires_at     DATETIME NULL,       -- 만료 시각 (1차에는 NULL, 2차 오픈 때 오픈일 + 30일)
    used_at        DATETIME NULL,       -- 사용한 시각 (2차에서 채움)
    created_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_coupon_attendance (attendance_id),
    CONSTRAINT fk_coupon_member FOREIGN KEY (member_id) REFERENCES member (id),
    CONSTRAINT fk_coupon_attendance FOREIGN KEY (attendance_id) REFERENCES attendance (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
```

- `uk_coupon_attendance`: 같은 7일째 출석으로 할인권을 두 번 만들면 두 번째 INSERT를 DB가 거절한다 (정책 5절 조건 2).
- `attendance_id`: 어떤 출석으로 받았는지 외래 키로 남긴다.
  - 기각한 대안: `source_type`/`source_id`로 출처를 일반화하는 방식. 외래 키를 걸 수 없고, 지금 출처는 출석 하나뿐이다.
- `expires_at` / `used_at`: 앞은 "이때 만료된다"는 예정 시각, 뒤는 "이때 사용했다"는 실제 시각이다. 기존 테이블처럼 DATETIME은 `_at`으로 끝낸다.
- 1인 최대 2장 (정책 5절 조건 3)은 유니크 키로 표현할 수 없다. 출석 요청 안에서 받은 장수를 세어 2장이면 막는다.
  - 같은 사용자의 같은 날 출석은 `uk_attendance_member_date` 때문에 하나만 커밋되고, 장수 확인과 발급은 출석과 같은 트랜잭션이다. 그래서 2장을 넘지 않는다고 **추정**한다. 동시 요청 테스트로 확인하기 전까지는 결론이 아니다.

## 3. 인덱스 검토

- 참여자 수 쿼리: `SELECT COUNT(*) FROM attendance WHERE attend_date = ?`
  - `uk_attendance_member_date (member_id, attend_date)`는 `member_id`로 먼저 정렬돼 있어서, 날짜만으로는 원하는 위치로 바로 찾아갈 수 없다. 인덱스 전체를 읽는 방식으로는 쓸 수 있다.
  - 하루 1건 유니크 키 덕분에 줄 수가 곧 사람 수다. `COUNT(DISTINCT member_id)`가 필요 없다.
- `idx_attendance_date (attend_date)`: 날짜별 조회를 위한 **후보**다. 효과가 확인되지 않아 V24에는 넣지 않는다.
  - 이유: 적용된 마이그레이션은 고칠 수 없다. V24에 넣으면 인덱스가 없는 상태가 남지 않아 전후 비교를 할 수 없다.
  - 확인 방법: V24 적용 후 같은 데이터·같은 쿼리로 인덱스 추가 전후의 `EXPLAIN`(`type`, `key`, `rows`)을 비교한다. 차이가 있으면 V25로 추가하고, 없으면 넣지 않는다. 출석 INSERT마다 갱신할 인덱스가 하나 늘어나는 비용이 있기 때문이다.

## 4. API

(작성 예정)

## 5. 검증 계획

1. `uk_attendance_member_date` 없이 동시 출석 테스트 → 2건이 저장되는 실패를 재현하고 결과를 남긴다
2. 제약을 추가한 뒤 같은 테스트 → 1건만 저장되는지 확인한다
3. 참여자 수 쿼리의 `EXPLAIN`을 `idx_attendance_date` 추가 전후로 비교한다 → 효과가 있으면 V25로 추가
4. `uk_coupon_attendance` 없이 같은 출석으로 할인권 두 번 발급 → 2장 저장 재현, 제약 추가 후 1장 확인
5. 할인권 1장을 가진 사용자가 두 번째 7일째 출석을 동시에 여러 번 요청 → 2장을 넘지 않는지 확인
