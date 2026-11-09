#!/usr/bin/env bash
# verify.sh 가 만든 인덱스만 정확한 이름으로 지운다.
#
# 안전장치
# - raw/created-indexes.txt 에 적힌 이름만 대상으로 한다. 와일드카드를 쓰지 않는다
# - 테스트 alias prefix 로 시작하지 않는 이름은 건너뛴다 (운영 mf-ai-chunks-* 보호)
# - alias 가 붙어 있으면 테스트 alias 만 떼고, 다른 alias 가 있으면 건너뛴다
set -uo pipefail
cd "$(dirname "$0")/../../.."          # ai-assistant/

RUN=evals/results/2026-09-19-alias-switch-safety
ALIAS="${ALIAS:-mf-ai-verify-20260919}"
# 접속 정보는 앱과 같은 출처(pydantic settings + .env)에서 읽는다.
# 이 스크립트에 값을 적어 두지 않는다.
eval "$(uv run python -c "
from membershipflow_ai.config.settings import get_settings
s = get_settings()
print(f'ES={s.elasticsearch_url}')
print(f'ES_AUTH={s.elasticsearch_username}:{s.elasticsearch_password}')
")"
es() { curl -s -u "$ES_AUTH" "$@"; }

while read -r idx; do
  [ -n "$idx" ] || continue
  case "$idx" in
    "$ALIAS"-*) ;;
    *) echo "SKIP $idx (테스트 alias prefix 가 아니다)"; continue ;;
  esac

  aliases=$(es "$ES/$idx/_alias" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print('__MISSING__' if 'error' in d else ' '.join(sorted(next(iter(d.values()))['aliases'])))")
  if [ "$aliases" = "__MISSING__" ]; then echo "SKIP $idx (이미 없다)"; continue; fi

  if [ -n "$aliases" ]; then
    for a in $aliases; do
      if [ "$a" != "$ALIAS" ]; then
        echo "SKIP $idx (테스트 것이 아닌 alias 가 붙어 있다: $a)"; continue 2
      fi
    done
    es -X POST "$ES/_aliases" -H 'Content-Type: application/json' \
       -d "{\"actions\":[{\"remove\":{\"index\":\"$idx\",\"alias\":\"$ALIAS\"}}]}" >/dev/null
    echo "  $idx: 테스트 alias $ALIAS 분리"
  fi

  still=$(es "$ES/$idx/_alias" | python3 -c "
import json,sys
d=json.load(sys.stdin); print(len(next(iter(d.values()))['aliases']) if 'error' not in d else 0)")
  if [ "$still" != "0" ]; then echo "SKIP $idx (alias 가 아직 붙어 있다)"; continue; fi
  es -X DELETE "$ES/$idx" | grep -q '"acknowledged":true' && echo "deleted $idx" || echo "FAILED $idx"
done < "$RUN/raw/created-indexes.txt"

echo
echo "남은 mf-ai 인덱스:"; es "$ES/_cat/indices/mf-ai-*?h=index" | sort | sed 's/^/  /'
echo "남은 alias:";        es "$ES/_cat/aliases?h=alias,index" | sort | sed 's/^/  /'
