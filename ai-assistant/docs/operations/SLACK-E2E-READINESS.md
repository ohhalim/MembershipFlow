# Slack 실제 멘션 왕복 — 남은 조건 (2026-09-22 확인)

읽기 전용으로 1회 확인했다. **메시지를 보내지 않았고 서비스를 기동하지 않았다.**

## 지금 상태: 왕복 불가 (서비스 미실행)

| 항목 | 결과 |
|---|---|
| Phoenix `127.0.0.1:6006` | 연결 안 됨 |
| Elasticsearch `localhost:9208` | 연결 안 됨 |
| Spring `localhost:8081` | 연결 안 됨 |
| `membershipflow_ai` · phoenix 프로세스 | 없음 |
| 런타임 로그 `/tmp/mf-rag-runtime-20260920` | 없음 (재부팅으로 소실) |

2026-09-20 마감 기록의 PID(Slack 99360, Phoenix 68862)는 **현재 유효하지 않다.**
그 PID 로 어떤 명령도 실행하면 안 된다.

## 이미 갖춰진 것

`.env` 에 아래 키가 들어 있다. **값은 확인하지 않았고 출력하지도 않았다.**
키가 있다는 것이 값이 유효하다는 뜻은 아니다.

- `SLACK_BOT_TOKEN`, `SLACK_APP_TOKEN`
- `SLACK_ALLOWED_TEAM_IDS`, `SLACK_ALLOWED_CHANNEL_IDS`
- `GEMINI_API_KEY`

코드 쪽 조건(`interfaces/slack_app.py`)도 확인했다.

- 두 토큰이 모두 없으면 기동 시 즉시 종료한다
- `app_mention` 이벤트만 처리한다
- team 허용목록이 비어 있지 않으면 `context.team_id` 가 목록에 있어야 한다
- channel 허용목록이 비어 있지 않으면 `event.channel` 이 목록에 있어야 한다
- 같은 이벤트는 한 번만 답한다(재전달 중복 제거)

## 남은 조건 (모두 사람이 해야 함)

왕복 1건을 성립시키려면 아래가 순서대로 필요하다.

1. **Elasticsearch 기동과 alias 확인.** `mf-ai-chunks` 가 유효한 인덱스를 가리켜야
   한다. alias 가 없으면 봇이 기동 단계에서 종료한다
2. **Slack 봇 프로세스 기동** (`membershipflow-ai slack`)
3. **봇이 허용목록에 있는 채널에 초대돼 있을 것.** 초대는 Slack 워크스페이스
   권한이 있는 사람이 한다
4. **실제 사람이 그 채널에서 봇을 멘션.** 봇 자신이나 스크립트가 보낸 메시지는
   `app_mention` 왕복 검증이 되지 않는다
5. **응답 확인**: 답변 본문, `근거` 목록, `route` / `grounded` 푸터가 스레드에 뜨는지

## 검증됐다고 쓸 수 없는 것

- `tests/test_slack_handler.py` 5건은 **로컬 핸들러 단위 테스트**다.
  실제 Slack 왕복 성공이 아니다. 네트워크를 쓰지 않는다
- 2026-09-20 기록의 `slack_socket_connected: true` 는 소켓 연결만 뜻한다.
  같은 기록의 `channel_mention_tested` 는 **`false`** 다
- `auto_restart_configured` 도 `false` 다. 재부팅 후 자동 기동은 설정돼 있지 않다

## 이번에 하지 않은 것

- 메시지 발송, 사람 토큰 확보
- 서비스 기동 또는 재시작
- `.env` 값 열람, 출력
- 과거 PID 를 쓰는 어떤 명령
