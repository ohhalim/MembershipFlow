# alias 전환 안전장치 실측 (2026-09-19)

실제 Elasticsearch 9.5.3 에 대고 publish/rollback 의 거부 조건을 확인했다.
대상은 두 결함 수정 커밋 `3835c21`, `022aec9`.

## 격리

- 테스트 alias `mf-ai-verify-20260919` (`AI_ELASTICSEARCH_ALIAS`). 운영 alias
  `mf-ai-chunks` 는 이번 실행에서 읽기만 했고 변경하지 않았다. 실행 전후로
  `mf-ai-chunks -> mf-ai-chunks-806c80ac51106a9e` 그대로이고 운영 인덱스 5개도
  그대로다 (`raw/cleanup-output.txt`)
- 인덱스 이름이 `{alias}-{build_id}` 라서 테스트 alias 를 바꾸면 인덱스도 자동 분리된다
- 임베딩은 `AI_EMBEDDING_PROVIDER=fake`. 전환 검증 경로가 가중치를 로드하지
  않는다는 설계를 그대로 쓴 것이고, 가중치 다운로드 없이 돌리기 위해서다
- 코퍼스는 `corpus-a/`, `corpus-b/` (각 2개 markdown). 제품 문서가 아니다

## 실행

```
./verify.sh     # 15건. A/B build 부터 픽스처 생성까지 스스로 한다
./cleanup.sh    # created-indexes.txt 에 적힌 이름만 삭제
```

`verify.sh` 는 아무것도 없는 상태에서 시작할 수 있다. A/B 인덱스가 없으면
직접 `build` 하고, 이전 실행이 남긴 테스트 alias 와 픽스처는 시작 시 정리한다.
위 결과는 `cleanup.sh` 로 전부 지운 뒤 빈 상태에서 다시 돌린 것이다.
접속 정보는 스크립트에 적지 않고 앱과 같은 settings 에서 읽는다.

원본 출력은 `raw/verify-output.txt`, `raw/cleanup-output.txt`.

## 결과: 15건 전부 통과

A = 5 chunk (corpus-a), B = 6 chunk (corpus-b). 둘 다 실제 `build` 산출물이고
manifest 는 VALIDATED.

### 정상 경로

| 항목 | 결과 |
|---|---|
| publish A (alias 없음 -> A) | 전환됨 |
| publish A 재실행 | `변경 없음`, alias 재지정 안 함 |
| publish B (A -> B) | 전환됨 |
| rollback A (B -> A) | manifest 근거로 복귀 |

### 거부 (전부 alias 는 A 유지)

| 항목 | 거부 사유 |
|---|---|
| publish FAILED manifest | `VALIDATED 인 build 만 전환할 수 있다` |
| rollback FAILED manifest | 같음 (publish 와 동일 기준) |
| rollback 없는 manifest 경로 | `manifest ... 가 없다` |
| rollback `--to <index>` | argparse: `required: --manifest` (구 인터페이스 제거됨) |
| publish 빈 인덱스 | `chunk count mismatch: indexed=0 expected=5` |
| publish 부분 색인 (5 중 3) | `chunk count mismatch: indexed=3 expected=5` |
| publish 같은 차원 다른 모델 | `벡터 공간이 달라 검색 결과가 조용히 어긋난다` |
| publish 차원 불일치 (768) | `vector 검색이 실패한다` |
| publish revision 불일치 | `모델 revision('2')이 현재 설정('1')과 다르다` |
| publish `_meta` 없는 인덱스 | `모델 신원 기록이 없다` |
| publish `_meta` 가 매핑과 불일치 | `매핑 차원(768)이 기록된 차원(1024)과 다르다` |

## harness 자체의 오류 (첫 실행)

첫 실행은 3·4절이 전부 통과로 보였지만 거짓이었다. 픽스처 인덱스를 만드는
`python3` 이 venv 밖이라 `elasticsearch` import 에 실패했고, 매핑 본문이 빈
문자열로 들어가 ES 가 dynamic 매핑 인덱스를 자동 생성했다. 그래서

- "빈 인덱스 거부" 는 chunk 수가 아니라 `symbol_path` 가드에 걸렸고
- "부분 색인 거부" 는 `_meta` 없음 가드에 걸렸다

거부는 됐지만 의도한 가드가 아니었다. 종료코드만 보는 테스트는 이걸 못 잡는다.
그래서 `check` 에 기대 사유 문자열을 추가하고, `fixture` 가 생성 응답과 되읽은
매핑을 확인한 뒤 실패 시 즉시 중단하도록 고쳤다. 위 표의 사유 문자열은 전부
실제 출력과 대조한 값이다.

## 확인하지 않은 범위

- 실제 임베딩 모델(bge-m3)로 만든 인덱스 전환. fake provider 로만 했다.
  `expected_identity()` 의 sentence-transformers 분기는 단위 테스트에서만 확인
- `_meta` 는 build 의 자기 신고다. 색인된 벡터를 실제로 검사하지 않는다.
  build 경로가 거짓을 기록하면 이 검사는 통과한다 (매핑 dims 대조는 예외)
- 전환 도중 ES 가 죽는 경우, 동시 publish 두 개, alias 가 2개 이상 인덱스를
  가리키는 비정상 상태에서의 복구
- 운영 규모(317 chunk 이상) 인덱스에서의 verify 소요 시간

## 운영상 확인된 결과 — 지금 되돌릴 수 있는 인덱스가 없다

기존 인덱스 5개를 조사한 결과 **전부 `_meta` 가 없다.** 운영 alias
`mf-ai-chunks` 가 현재 가리키는 `mf-ai-chunks-806c80ac51106a9e` 도 마찬가지다.

| 인덱스 | `_meta` | symbol_path | dims |
|---|---|---|---|
| mf-ai-chunks-0fe52eea51c20746 | 없음 | 없음 | 1024 |
| **mf-ai-chunks-806c80ac51106a9e** (현재 운영) | 없음 | 있음 | 1024 |
| mf-ai-chunks-a32289acb45a7365 | 없음 | 없음 | 1024 |
| mf-ai-chunks-a5e9ea56d94ee332 | 없음 | 없음 | 1024 |
| mf-ai-chunks-a86140d4e2b65f3b | 없음 | 없음 | 1024 |

결과적으로 이 코드에서는 **어느 인덱스로도 publish/rollback 할 수 없다.**
현재 서비스는 alias 가 이미 걸려 있어 그대로 동작하지만, 되돌릴 경로는 재build
전까지 닫혀 있다. 2026-09-12 스모크 인덱스도 `AI_EMBEDDING_REVISION` 이 비어
있어 revision=null 이므로 재build 해도 고정 전에는 전환할 수 없다.

의도한 보수적 정책이지만 대가가 가설이 아니라 측정값이다. 다음 작업은
`AI_EMBEDDING_REVISION` 을 고정하고 재build 해서 전환 가능한 인덱스를 하나
확보하는 것이다.
