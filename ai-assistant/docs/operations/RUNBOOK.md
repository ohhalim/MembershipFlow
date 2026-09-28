# RAG 어시스턴트 운영 — 로컬

띄우고, 상태를 보고, 막혔을 때 어디를 보는지. 2026-09-29 기준으로 실제 실행해
확인한 절차다. **AWS 배포는 아직 하지 않았다.**

## 띄우기

```bash
cd <repo>
docker compose -f docker-compose.ai.yml --profile ai up -d
```

`--profile ai` 가 없으면 아무것도 뜨지 않는다. 세 서비스가 순서대로 올라간다.

| 서비스 | 하는 일 | 헬스체크 |
|---|---|---|
| `ai-elasticsearch` | 코드·문서 색인 (317 청크) | O |
| `ai-postgres` | pgvector | O |
| `ai-slack` | Slack 봇 (실제 인터페이스) | — |

`ai-slack` 은 앞의 둘이 healthy 가 된 뒤에 뜬다. 첫 기동은 임베딩 모델을
읽느라 **1~2분** 걸린다. 모델은 `ai_model_cache` 볼륨에 남아 다음부터 빠르다.

## 떴는지 확인

```bash
docker compose -f docker-compose.ai.yml ps
docker compose -f docker-compose.ai.yml logs ai-slack --tail 30
```

정상이면 마지막에 이게 보인다.

```
INFO  GET http://ai-elasticsearch:9200/_alias/mf-ai-chunks [status:200]
INFO  ⚡️ Bolt app is running!
```

`Bolt app is running` 이 없으면 아직 모델을 읽는 중이거나 아래 중 하나다.

## 기동 로그에서 보게 되는 경고

둘 다 기동을 막지 않는다. 다만 무슨 상태인지는 알고 있어야 한다.

```
WARNING SLACK_ALLOWED_TEAM_IDS 가 비어 있다. 모든 워크스페이스의 멘션에 응답한다
WARNING SLACK_ALLOWED_CHANNEL_IDS 가 비어 있다. 모든 채널의 멘션에 응답한다
```

허용목록이 비면 **제한 없음**이다. 초대되는 아무 채널에서나 저장소 내용을
답한다. 좁히려면 `.env` 에 팀·채널 ID 를 쉼표로 적는다.

```
WARNING tracing collector unreachable, continuing without tracing: http://127.0.0.1:6006
```

Phoenix 가 안 떠 있다는 뜻이다. 관측은 선택이라 그대로 진행한다. 트레이스를
보려면 Phoenix 를 먼저 띄운다.

## 자주 막히는 곳

**"GEMINI_API_KEY 가 없어 답변을 생성할 수 없습니다"** — `.env` 에 키가 있어도
났던 문제다. 지금은 Settings 가 `.env` 를 읽으므로 키가 있으면 나지 않는다.
그래도 나면 값이 비어 있는지 본다. CLI 를 `uv run` 으로 직접 쓸 때는 실행
디렉터리에 `.env` 가 있어야 한다.

**503 UNAVAILABLE** — 특정 모델의 과부하다. 모델명이 틀린 것이 아니다.
폴백 모델로 자동으로 넘어가므로 대개 그대로 답이 나간다. 폴백까지 전부 막히면
답변 대신 **근거만** 돌아온다(`failure: llm_unavailable`). 검색은 성공했으므로
근거 목록은 쓸 수 있다.

지금 어떤 모델이 살아 있는지 보려면:

```bash
cd ai-assistant
uv run python -c "
import json, urllib.request
from membershipflow_ai.config.settings import Settings
s = Settings()
url = f'https://generativelanguage.googleapis.com/v1beta/models?key={s.gemini_api_key}'
with urllib.request.urlopen(url, timeout=20) as r:
    d = json.load(r)
print([m['name'].replace('models/','') for m in d['models']
       if 'generateContent' in m.get('supportedGenerationMethods', [])][:20])
"
```

**alias 가 없다며 종료** — 색인이 비었거나 alias 가 안 걸렸다. 확인:

```bash
curl -s -u elastic:local-only-dev-password "http://localhost:9208/_cat/aliases/mf-ai-chunks?v"
```

**컨테이너가 계속 재시작** — 로그를 먼저 본다. 과거에 났던 두 가지는 고쳤다.
`ai-api` 가 없는 모듈을 실행하던 것(서비스 자체를 제거), 이미지에
`sentence-transformers` 가 빠져 있던 것(`--extra models` 추가).

## 색인 다시 만들기

색인은 볼륨에 남아 컨테이너를 지워도 유지된다. 다시 만들어야 하면 `BUILD.md`
의 `build` → `publish` 순서를 따른다. **기존 alias 를 바로 덮어쓰지 않는다.**

## 내려놓기

```bash
docker compose -f docker-compose.ai.yml --profile ai down          # 볼륨 유지
docker compose -f docker-compose.ai.yml --profile ai down -v       # 색인까지 삭제
```

`-v` 는 317 청크 색인과 모델 캐시를 지운다. 다시 만드는 데 시간이 걸린다.

## 자동 기동

`restart: unless-stopped` 가 걸려 있어 앱이 죽으면 컨테이너가 다시 뜬다.
실제로 컨테이너 안에서 앱 프로세스를 죽여 확인했다(`RestartCount 1` 후 복구).

`docker stop` 이나 `docker kill` 로 멈춘 것은 "사람이 멈춘 것" 이라 다시 뜨지
않는다. 정상 동작이다.

호스트를 재부팅한 뒤 자동으로 뜨게 하려면 Docker Desktop 이 로그인 시 시작되도록
설정돼 있어야 한다. 그 설정은 이 저장소 밖이다.

## 아직 검증하지 않은 것

- **실제 사람이 Slack 에서 멘션해 답을 받는 왕복.** 봇이 소켓에 붙는 것과
  사람이 멘션했을 때 답이 가는 것은 다르다. `tests/test_slack_handler.py` 는
  네트워크를 쓰지 않는 로컬 핸들러 테스트다
- 호스트 재부팅 후 자동 복구
- AWS 배포
