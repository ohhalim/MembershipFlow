# 7일 연속 출석 이벤트 — 진행 기록

- 관련 이슈: [#423](https://github.com/ohhalim/MembershipFlow/issues/423)
- 현업 시뮬레이션이다. 기획자·디자이너 역할은 AI가 맡았고, 요구사항 확인·설계·구현·검증은 본인이 직접 했다.

## 단계별 기록

| # | 일시 (KST) | 단계 | 한 사람 | 산출물 | 캡처 |
|---|---|---|---|---|---|
| 00 | 10/5(월) 11:24 | 기획·디자인 요청 접수, 일정·완료 기준 설정 | AI (기획자·디자이너 역할) | #423 본문 | `captures/00-issue.png` |
| 01 | 10/5(월) 19:35 (마감 18:00, 1시간 35분 지연, 사전 공유 없음) | 기획·디자인에 질문 회신 ([댓글](https://github.com/ohhalim/MembershipFlow/issues/423#issuecomment-5992764256)) | 본인 초안(질문 3개) → 요청에 따라 AI가 전체 초안 작성 → 본인 검토·게시 | #423 댓글 | `captures/01-questions.png` |
| 02 | 10/6(화) 10:00 | 킥오프: 답변 확인, 범위 확정, 일정 견적 → 오픈 10/20(화) 확정 ([미팅 로그](https://github.com/ohhalim/MembershipFlow/issues/423#issuecomment-6007183288)) | 기획·디자인 답변은 AI 역할. 견적과 미팅 중 제기한 문제 2개는 본인이 막혀서 요청 → AI 초안 → 본인 검토·채택(일정안은 본인이 선택) | #423 미팅 로그 댓글, [대화기록](https://github.com/ohhalim/MembershipFlow/issues/423#issuecomment-6007272027) (`02-kickoff-conversation.md`) | `captures/02-kickoff.png` |
| 03 | 10/6(화) 10:30 (마일스톤 표는 10/7 추가, 기한 18:00 지연) | 정책 문서 + 마일스톤 | 본인 요청 → AI 초안 → 본인 검토 | `docs/ATTENDANCE-EVENT-POLICY.md` | |
| 04 | 10/7(수) 19:28 (10/8 마일스톤 기한 전) | 진행 상황 공유: 10/8 마일스톤 → 10/12, 10/14에 오픈 가능 여부 재공유 ([댓글](https://github.com/ohhalim/MembershipFlow/issues/423#issuecomment-6036017773)) | AI 문구 초안 → 본인 승인 | #423 댓글 | |
| 05 | 10/8(목) ~ 10/11(일) | 테이블 설계: `attendance`, `coupon`, 인덱스 검토, 검증 계획 | `attendance` DDL 본인 작성. `coupon`은 AI 빈칸 템플릿 → `expires_at`·`used_at` 본인, 유니크 키 AI. 인덱스 설명은 AI가 틀리게 설명한 것을 다른 모델 리뷰로 바로잡음 | `docs/ATTENDANCE-EVENT-DESIGN.md` | |
| 06 | 10/11(일) 20:51 (10/12 마일스톤 기한 전) | 진행 상황 공유: 10/12 마일스톤 → 10/13, 뒤 일정 하루씩, 오픈 10/20 유지 ([댓글](https://github.com/ohhalim/MembershipFlow/issues/423#issuecomment-6108722393)) | 날짜는 본인 결정, 문구는 AI 초안 → 본인 승인 | #423 댓글 | |
| 07 | 10/11(일) 21:30 | V24 마이그레이션 작성, 빈 MySQL 8.0.36에 V1~V24 적용 확인. 날짜 인덱스는 효과 확인 전이라 제외 (EXPLAIN 전후 비교 후 V25 여부 결정) | V24는 본인 작성(설계 문서 DDL 옮김). 적용 확인은 AI 실행 | `V24__attendance_event.sql` | `captures/07-v24-migrate.txt` |

- 01: 본인이 먼저 쓴 질문 3개 중 출석으로 인정하는 행동, 하루의 기준은 문구를 다듬어 댓글의 기획 3·4번으로 들어갔다. 나머지는 AI 초안이다.

이후 단계(정책 문서 → 테이블·API 설계 → 실패 재현 → 구현 → 검증 → 회고)는 진행하면서 한 줄씩 추가한다.

## 캡처 원칙

- **바로 남겨야 하는 것:** 터미널 테스트 결과, 로그, DB 상태, 동시 요청 결과 건수. 다시 만들기 어려운 것들이다.
- **나중에 찍어도 되는 것:** GitHub 이슈·댓글·PR. 기록이 남아 있으므로 언제든 다시 찍을 수 있다.
- 파일 이름은 `NN-단계.png`로 하고 `captures/`에 둔다.