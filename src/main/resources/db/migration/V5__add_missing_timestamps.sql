-- V5: membership_course 누락된 updated_at 추가
ALTER TABLE membership_course
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;
