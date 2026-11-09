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