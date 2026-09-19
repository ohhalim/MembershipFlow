#!/usr/bin/env bash
# alias 전환 안전장치를 실제 Elasticsearch 에 대고 확인한다.
#
# 격리: 고유 테스트 alias(AI_ELASTICSEARCH_ALIAS)와 이번 실행이 만든 인덱스만 쓴다.
# 운영 alias(mf-ai-chunks)는 건드리지 않는다. 삭제는 cleanup.sh 가 따로 한다.
#
# 거부 테스트는 "거부됐다" 만으로는 부족하다. 첫 실행에서 픽스처 생성이 조용히
# 실패해 엉뚱한 가드(symbol_path)에 걸렸는데도 통과로 보였다. 그래서 종료코드와
# alias 불변에 더해 거부 사유 문자열까지 맞춘다.
#
# 사전: corpus-a/, corpus-b/ 와 raw/manifest-a.json, raw/manifest-b.json
# 자격증명은 환경변수로만 받고 로그에 남기지 않는다.
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

export AI_ELASTICSEARCH_ALIAS="$ALIAS"
export AI_EMBEDDING_PROVIDER=fake
export AI_EMBEDDING_REVISION=1


es() { curl -s -u "$ES_AUTH" "$@"; }
alias_now() {
  es "$ES/_alias/$ALIAS" |
    python3 -c "import json,sys;d=json.load(sys.stdin);print('(없음)' if 'error' in d else ','.join(sorted(d)))"
}
cli() { uv run membershipflow-ai "$@" 2>&1; }

# A/B 인덱스가 없으면 직접 build 한다. verify -> cleanup -> verify 가 그대로 돈다.
# corpus-b 는 corpus-a 에 섹션 하나가 더 있어 청크 집합이 다르다.
build_ab() {
  local n idx
  for n in a b; do
    idx=""
    [ -f "$RUN/raw/manifest-$n.json" ] && idx=$(python3 -c "
import json;print(json.load(open('$RUN/raw/manifest-$n.json')).get('physical_index',''))")
    if [ -n "$idx" ] && [ "$(es -o /dev/null -w '%{http_code}' "$ES/$idx")" = 200 ]; then continue; fi
    echo "build $n (인덱스 없음 -> 새로 만든다)"
    rm -f "$RUN/raw/manifest-$n.json"
    AI_REPOSITORY_ROOT="$PWD/$RUN/corpus-$n" AI_CORPUS_CONFIG="$PWD/$RUN/corpus-$n/corpus.yml" \
      uv run membershipflow-ai build --manifest "$RUN/raw/manifest-$n.json" | sed 's/^/  /'
  done
}
build_ab
A=$(python3 -c "import json;print(json.load(open('$RUN/raw/manifest-a.json'))['physical_index'])")
B=$(python3 -c "import json;print(json.load(open('$RUN/raw/manifest-b.json'))['physical_index'])")

pass=0; fail=0
# check <이름> <ok|refuse> <기대 alias> <기대 사유 문자열> -- <명령...>
check() {
  local name="$1" want="$2" want_alias="$3" want_msg="$4"; shift 5   # 5번째는 --
  local out rc after verdict=PASS
  out=$("$@"); rc=$?
  after=$(alias_now)
  if [ "$want" = ok ]     && [ $rc -ne 0 ]; then verdict="FAIL(exit $rc)"; fi
  if [ "$want" = refuse ] && [ $rc -eq 0 ]; then verdict="FAIL(전환을 막지 못했다)"; fi
  if [ "$after" != "$want_alias" ]; then verdict="FAIL(alias=$after, 기대=$want_alias)"; fi
  if [ -n "$want_msg" ] && ! printf '%s' "$out" | grep -qF -- "$want_msg"; then
    verdict="FAIL(사유 불일치: '$want_msg' 없음)"
  fi
  [ "$verdict" = PASS ] && pass=$((pass+1)) || fail=$((fail+1))
  printf '\n=== %-44s %s\n' "$name" "$verdict"
  printf '    exit=%s alias=%s\n' "$rc" "$after"
  printf '%s\n' "$out" | sed 's/^/    | /'
}

# _meta 를 임의로 지정한 픽스처 인덱스. build 가 아니라 이 스크립트가 만든 것이다.
# 매핑 생성이 실패하면 ES 가 dynamic 매핑으로 빈 인덱스를 만들어 테스트가 엉뚱한
# 가드에 걸리므로, 생성 결과를 반드시 확인하고 실패 시 즉시 멈춘다.
fixture() {  # fixture <index> <meta json | {}> <dims> <A 에서 복사할 문서 수>
  local idx="$1" meta="$2" dims="$3" ndocs="$4" body created
  body=$(uv run python - "$meta" "$dims" <<'PY'
import json, sys
from membershipflow_ai.persistence.elasticsearch_store import index_settings, index_mappings
meta, dims = json.loads(sys.argv[1]), int(sys.argv[2])
m = index_mappings(dims, meta)
if not meta:
    m.pop("_meta")          # _meta 자체가 없는 과거 인덱스 재현
print(json.dumps({"settings": index_settings(), "mappings": m}))
PY
) || { echo "FATAL: $idx 매핑 생성 실패"; exit 1; }
  created=$(es -X PUT "$ES/$idx" -H 'Content-Type: application/json' -d "$body")
  printf '%s' "$created" | grep -q '"acknowledged":true' || { echo "FATAL: $idx 생성 실패: $created"; exit 1; }
  echo "$idx" >> "$RUN/raw/created-indexes.txt"
  # 기록한 대로 만들어졌는지 되읽어 확인한다
  es "$ES/$idx/_mapping" | python3 -c "
import json,sys
m=json.load(sys.stdin)['$idx']['mappings']
print('    fixture $idx: _meta=%s dims=%s'%(m.get('_meta','(없음)'), m['properties']['embedding']['dims']))"
  if [ "$ndocs" -gt 0 ]; then
    python3 - "$idx" "$ndocs" "$ES" "$ES_AUTH" "$RUN" <<'PY'
import json, subprocess, sys
idx, n, es, auth, run = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5]
src = json.load(open(f"{run}/raw/manifest-a.json"))
ids = src["expected_chunk_ids"][:n]
hits = json.loads(subprocess.run(
    ["curl","-s","-u",auth,f"{es}/{src['physical_index']}/_search?size=100"],
    capture_output=True, text=True).stdout)["hits"]["hits"]
by_id = {h["_id"]: h["_source"] for h in hits}
body = "".join(json.dumps({"index":{"_index":idx,"_id":i}})+"\n"+json.dumps(by_id[i])+"\n" for i in ids)
out = json.loads(subprocess.run(
    ["curl","-s","-u",auth,"-X","POST",f"{es}/_bulk?refresh=true",
     "-H","Content-Type: application/x-ndjson","--data-binary",body],
    capture_output=True, text=True).stdout)
if out.get("errors"):
    print("    FATAL: bulk 실패", json.dumps(out)[:400]); sys.exit(1)
print(f"    fixture {idx}: {len(ids)} docs 적재")
PY
    [ $? -eq 0 ] || exit 1
  fi
}

FIXTURES="empty partial othermodel dim768 rev2 nometa metalie"

# 반복 실행 가능하게 시작 상태를 맞춘다. 이번 테스트 alias 와 이 스크립트가 만드는
# 픽스처만 정확한 이름으로 지운다. A/B 는 실제 build 산출물이라 보존한다.
reset_state() {
  local cur; cur=$(alias_now)
  if [ "$cur" != "(없음)" ]; then
    for idx in ${cur//,/ }; do
      es -X POST "$ES/_aliases" -H 'Content-Type: application/json' \
         -d "{\"actions\":[{\"remove\":{\"index\":\"$idx\",\"alias\":\"$ALIAS\"}}]}" >/dev/null
    done
    echo "시작 정리: 테스트 alias $ALIAS 제거 (인덱스는 남긴다)"
  fi
  for f in $FIXTURES; do
    es -X DELETE "$ES/$ALIAS-fixture-$f" >/dev/null 2>&1
  done
}
reset_state

echo "alias: $ALIAS"
echo "A: $A"
echo "B: $B"
echo "시작 alias: $(alias_now)"
: > "$RUN/raw/created-indexes.txt"
echo "$A" >> "$RUN/raw/created-indexes.txt"; echo "$B" >> "$RUN/raw/created-indexes.txt"

echo; echo "########## 1. 정상 경로 ##########"
check "publish A (최초 전환)"         ok "$A" "(없음) -> $A"  -- cli publish  --manifest $RUN/raw/manifest-a.json
check "publish A 재실행 (no-op)"      ok "$A" "변경 없음"      -- cli publish  --manifest $RUN/raw/manifest-a.json
check "publish B (전환)"              ok "$B" "$A -> $B"       -- cli publish  --manifest $RUN/raw/manifest-b.json
check "rollback A (manifest 로 복귀)" ok "$A" "$B -> $A"       -- cli rollback --manifest $RUN/raw/manifest-a.json

echo; echo "########## 2. manifest 근거 없음 -> 거부, alias 는 A 유지 ##########"
python3 -c "
import json;m=json.load(open('$RUN/raw/manifest-b.json'));m['status']='FAILED'
json.dump(m,open('$RUN/raw/manifest-b-failed.json','w'),ensure_ascii=False,indent=2)"
check "publish  FAILED manifest"      refuse "$A" "VALIDATED 인 build 만"     -- cli publish  --manifest $RUN/raw/manifest-b-failed.json
check "rollback FAILED manifest"      refuse "$A" "VALIDATED 인 build 만"     -- cli rollback --manifest $RUN/raw/manifest-b-failed.json
check "rollback 없는 manifest 경로"   refuse "$A" "nope.json 가 없다"          -- cli rollback --manifest $RUN/raw/nope.json
check "rollback --to (구 인터페이스)" refuse "$A" "required: --manifest"       -- cli rollback --to "$B"

echo; echo "########## 3. 인덱스 내용 불일치 -> 거부, alias 는 A 유지 ##########"
META_OK='{"schema_revision":"2","embedding_model":"fake-sha256-v1","embedding_revision":"1","dimension":1024,"snapshot_revision":"1"}'
fixture "$ALIAS-fixture-empty"   "$META_OK" 1024 0
fixture "$ALIAS-fixture-partial" "$META_OK" 1024 3
uv run python - <<'PY'
import json
R = "evals/results/2026-09-19-alias-switch-safety/raw"
base = json.load(open(f"{R}/manifest-a.json"))
alias = base["physical_index"].rsplit("-", 1)[0]
def w(name, **over):
    m = dict(base); m["physical_index"] = f"{alias}-fixture-{name}"; m.update(over)
    json.dump(m, open(f"{R}/manifest-{name}.json", "w"), ensure_ascii=False, indent=2)
w("empty"); w("partial")
w("othermodel", embedding_model="BAAI/bge-m3")
w("dim768", dimension=768)
w("rev2", embedding_revision="2")
w("nometa")
w("metalie")
PY
check "publish 빈 인덱스"             refuse "$A" "chunk count mismatch: indexed=0 expected=5" -- cli publish --manifest $RUN/raw/manifest-empty.json
check "publish 부분 색인 (5 중 3)"    refuse "$A" "chunk count mismatch: indexed=3 expected=5" -- cli publish --manifest $RUN/raw/manifest-partial.json

echo; echo "########## 4. 모델 신원 불일치 -> 거부, alias 는 A 유지 ##########"
fixture "$ALIAS-fixture-othermodel" '{"schema_revision":"2","embedding_model":"BAAI/bge-m3","embedding_revision":"1","dimension":1024,"snapshot_revision":"1"}' 1024 5
fixture "$ALIAS-fixture-dim768"     '{"schema_revision":"2","embedding_model":"fake-sha256-v1","embedding_revision":"1","dimension":768,"snapshot_revision":"1"}'  768  0
fixture "$ALIAS-fixture-rev2"       '{"schema_revision":"2","embedding_model":"fake-sha256-v1","embedding_revision":"2","dimension":1024,"snapshot_revision":"1"}' 1024 5
fixture "$ALIAS-fixture-nometa"     '{}' 1024 5
check "publish 같은 차원 다른 모델"   refuse "$A" "벡터 공간이 달라"        -- cli publish --manifest $RUN/raw/manifest-othermodel.json
check "publish 차원 불일치 (768)"     refuse "$A" "vector 검색이 실패한다"  -- cli publish --manifest $RUN/raw/manifest-dim768.json
check "publish revision 불일치"       refuse "$A" "모델 revision"            -- cli publish --manifest $RUN/raw/manifest-rev2.json
check "publish _meta 없는 인덱스"     refuse "$A" "모델 신원 기록이 없다"   -- cli publish --manifest $RUN/raw/manifest-nometa.json

# _meta 는 build 의 자기 신고다. 기록이 실제 매핑과 어긋나면 기록 쪽을 믿지 않는다.
fixture "$ALIAS-fixture-metalie"    '{"schema_revision":"2","embedding_model":"fake-sha256-v1","embedding_revision":"1","dimension":1024,"snapshot_revision":"1"}' 768 0
check "publish _meta 가 매핑과 불일치" refuse "$A" "매핑 차원(768)"          -- cli publish --manifest $RUN/raw/manifest-metalie.json

echo; echo "########## 결과 ##########"
echo "PASS=$pass FAIL=$fail"
echo "최종 alias: $(alias_now)  (기대: $A)"
echo "이번 실행이 만든 인덱스:"; sed 's/^/  /' "$RUN/raw/created-indexes.txt"
[ "$fail" -eq 0 ]
