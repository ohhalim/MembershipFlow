# ES 적재 중 실패 검증 (bulk failure)

실행 20260913-172226 · 코드 SHA `d0690a95234b1146739d7a93051a567167501e8c`

## 방식

제품 코드를 바꾸지 않았다. `harness.py`가 실제 `cli.build`를 실행하되
`ElasticsearchStore.bulk_index`를 wrapper로 감싸, 원본 bound 함수에 넘기기
직전 청크 한 건의 embedding 길이를 1024 → 1023으로 줄인다.

**bulk 호출과 응답은 실제 ES가 수행한다. 실패 응답을 mock하지 않았다.**
snapshot 검증을 통과한 뒤 ES 호출 경계에서 의도적으로 손상시킨 **인공 장애**다.

입력: 임시 corpus 2개 원본, fake embedding, 청크 9건 (실제 모델 미로딩)

## 결과

| 확인 | 결과 |
|---|---|
| bulk 실패 | 1건. ES가 `document_parsing_exception` 반환 |
| build 예외 | `RuntimeError: bulk indexing failed for 1 chunks` |
| manifest status | **`FAILED`** + `error_type: RuntimeError` |
| VALIDATED 오인 | 없음 |
| verify 호출 | build 실패 시 호출되지 않음 (정상) |

### build 종료 후 별도 확인

- 실패 인덱스 실제 문서수 **8** (= 9 − 1)
- `verify` 직접 호출 → **거부**: `chunk count mismatch: indexed=8 expected=9`

> `verify` 미호출은 **코드 흐름 근거**다. harness 가 호출 여부를 계측하지 않았다.
> `bulk_index` 가 실패를 반환하면 `cli.build` 가 `verify` 이전에 `RuntimeError` 를
> 던지는 구조이며, 실행 중 계측으로 확인한 것이 아니다.

> bulk 실패의 `reason` 은 **300자 절단본**이다. ES 응답 전문이 아니다.
> 누락 ID 는 `failure` 의 chunk_id 와 손상시킨 chunk_id 가 일치하고 문서수가 8인 것으로
> 추정한 것이며, **누락 ID 를 직접 조회한 증거는 없다.**

### alias 전후

| | alias |
|---|---|
| before | `mf-ai-chunks-806c80ac51106a9e` |
| after | `mf-ai-chunks-806c80ac51106a9e` |

기존 인덱스 5개 문서수 317 유지. 실패 인덱스는 alias에 연결되지 않았다.

## 보존 파일

- `harness.py` — 검증 스크립트
- `manifest-FAILED.json` — 실행 산출물 원본
- `observation-20260913-172226.json` — bulk 실패 ID·에러 유형, alias 전후, verify 결과

## 정리

alias 연결 없음을 재확인한 뒤 이번 실행이 만든 정확한 이름 1개만 삭제했다.

`mf-ai-chunks-206c823c7c6041088bb3015c7a994635` → `{"acknowledged":true}`

wildcard 삭제 없음. 기존 인덱스·alias 변경 없음. **삭제 후 재조회 불가.**

> 삭제 전 `_alias` 조회 응답과 `DELETE` 응답의 **원본 로그는 저장하지 않았다.**
> 위 내용은 실행 중 화면 관측에 근거한 **보고 기반** 기록이다.

## 계획과 달랐던 점 — 인덱스 명명

계획은 일회용 인덱스에 `mf-smoke-bulkfail-*` 접두사를 쓰는 것이었으나 적용하지 못했다.
실제 생성 이름은 `mf-ai-chunks-<uuid>` 다.

당시 "제품 코드 변경이 필요하다"고 기록했으나 **그 판단은 틀렸다.**
`Settings` 는 `env_prefix="AI_"` 이므로 `AI_ELASTICSEARCH_ALIAS` 프로세스 환경값으로
바꿀 수 있고, `physical_index()` 가 `f"{alias}-{build_id}"` 를 쓰므로 접두사도 함께 바뀐다.
`get_settings()` 캐시 이전에 설정하면 된다. 확인하지 않고 단정한 오류다.

다만 그 경우 기존 alias 관측은 **실제 alias 이름을 따로 지정해서** 해야 한다.
이번 실행은 재현하지 않으며 제품 코드도 변경하지 않는다.

## 미검증

- 부분 실패 후 복구 경로
- 강제 종료 시 `PREPARING`/`BUILDING` 상태 잔류
- alias 전환 경로 자체
- 실제 임베딩 모델 기준 적재 (fake로 실행)
- 이 실패는 인공 주입이며 운영에서 같은 원인이 발생한다는 뜻이 아니다
