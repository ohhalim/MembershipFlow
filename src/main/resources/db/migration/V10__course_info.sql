-- #141 동아 상세페이지 골프장 부가정보 (주소/그린피/소개)
-- membership_course와 1:1 (같은 골프장의 회원권 여러 개가 각자 동일 정보를 가짐)

CREATE TABLE course_info (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    course_id        BIGINT       NOT NULL,
    address          VARCHAR(300) NULL,
    membership_intro TEXT         NULL,
    course_intro     TEXT         NULL,
    price_outlook    TEXT         NULL,                -- 시세 흐름 + 향후 전망
    green_fees       TEXT         NULL,                -- [{"grade","weekday","weekend"}] JSON 직렬화
    caddie_fee       VARCHAR(200) NULL,
    cart_fee         VARCHAR(200) NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_course_info_course (course_id),
    CONSTRAINT fk_course_info_course FOREIGN KEY (course_id) REFERENCES membership_course (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
