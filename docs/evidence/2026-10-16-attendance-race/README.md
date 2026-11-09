# 출석 동시 요청 500 재현 (#423)

## 사실
- 일시: 2026-10-16 14:5x, 로컬 (bootRun 8081, MySQL 8.0 컨테이너)
- 커밋: 56c6774
- 조건: 같은 회원(900003, 당일 출석 기록 없음)으로 `POST /api/v1/attendances` 10개를 curl 백그라운드로 동시에 보냄
- 결과

| 항목 | 값 |
|---|---|
| 200 | 1 |
| 500 | 9 |
| attendance 행 | 1 |

- 로그
```
DataIntegrityViolationException: Duplicate entry '900003-2026-10-16' for key 'attendance.uk_attendance_member_date'
```

## 해석
- `findByMemberIdAndAttendDate`로 오늘 기록을 확인한 뒤 `save`하기까지 사이에 다른 요청이 끼어들 수 있다. 10개 모두 "기록 없음"을 보고 insert를 시도했다.
- V24 유니크 제약이 중복 행은 막았다. 데이터 정합성은 지켜졌다.
- 막힌 9개 요청의 예외를 아무도 처리하지 않아 `GlobalExceptionHandler`의 마지막 핸들러에서 500이 나갔다.

## 한계
- 1회 실행 결과다. 동시 요청 수를 바꾸면 200과 500의 비율은 달라질 수 있다.
- curl 10개가 정확히 같은 순간에 출발했다는 보장은 없다. 이 결과는 "경합이 실제로 일어난다"는 증거로만 쓴다.

## 다음
- 같은 조건을 Testcontainers MySQL 통합 테스트로 고정한다 (`AttendanceConcurrencyTest`)
- 수정 후 같은 테스트와 같은 curl 조건으로 다시 측정해서 after를 이 폴더에 추가한다

## after (수정 후, 같은 조건)
- 수정: `AttendanceController`에서 `DataIntegrityViolationException`을 잡고 `attend`를 새 트랜잭션으로 한 번 더 호출
- 조건: 새 회원(900004, 당일 기록 없음)으로 같은 curl 10개 동시 요청

| 항목 | before | after |
|---|---|---|
| 200 | 1 | 10 |
| 500 | 9 | 0 |
| attendance 행 | 1 | 1 |

- 통합 테스트 `AttendanceConcurrencyTest` (Testcontainers MySQL, 스레드 10개): 수정 전 실패(예외 발생) → 수정 후 통과
