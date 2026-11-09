# ret-006 — 질문 범위에 필요한 근거 청크 (2026-09-22)

`LABEL-REVIEW.md` 의 1번 항목에 대한 후속 확인이다.

**2026-09-22: 사용자가 아래 최소 수정안을 채택해 적용했다.** ret-006 의
`expected_sources` 가 2건이 됐다. `reviewed` 는 `false` 그대로다 — 이번 결정은
이 문항의 근거 범위에 대한 것이고, 15건 전체를 사람이 검토했다는 주장이 아니다.

질문
> 여러 소스의 가격 중 이상치를 찾을 때 기준 가격과 이탈 정도는 어떻게 계산해?

수정 전 라벨
> `AnomalyDetectionService > evaluatePriceOutliers` 한 건

수정 후 라벨
> `AnomalyDetectionService > evaluatePriceOutliers`
> `AnomalyDetectionService > median`

## 이 파일의 실제 청크 경계

색인이 떠 있지 않아 같은 chunker(`StructureAwareChunker` + `JavaParser`)로 직접
파싱해 확인했다. 새 실험이 아니라 경계 확인이다.

| 청크 | 행 | 이 질문에 관련된 내용 |
|---|---|---|
| … | L55-61 | — |
| … | L63-75 | — |
| … | L77-100 | — |
| **`evaluatePriceOutliers`** | **L102-122** | median **호출**, 이탈 식, 임계값 **사용** |
| **`median`** | **L125-133** | median **구현** (정렬 후 중앙값) |
| … | L136-150 | — |
| `SourcePrice` | L152 | — |

**상수 정의(L43-47)는 어느 청크에도 없다.** 첫 청크가 L55 부터 시작한다.
JavaParser 가 클래스 필드 선언 영역을 섹션으로 잡지 않기 때문이다. 즉 임계값
0.5 와 최소 소스 수 3 이라는 **숫자 자체는 어떤 라벨을 붙여도 검색으로 닿지
않는다.** 라벨 `notes` 에 적힌 "현재 청크에 정의가 없어 평가 대상에서 제외" 의
원인이 이것이다.

## 답변 요지와 주장별 코드 위치

질문에 제대로 답하려면 아래 다섯 가지가 필요하다.

| # | 주장 | 코드 위치 | 현재 라벨 안에 있나 |
|---|---|---|---|
| 1 | 기준 가격은 소스 가격들의 **중앙값**이다 | L105 `double median = median(...)` | O |
| 2 | 중앙값은 **정렬 후** 홀수면 가운데 값, 짝수면 가운데 두 값의 평균 | **L126-132** (`median` 청크 L125-133) | **X** |
| 3 | 이탈 정도 = `(소스가격 - median) / median` | L109 | O |
| 4 | 절댓값이 임계값 **이하면 건너뛴다**(= 넘으면 이상치) | L110 `Math.abs(deviation) <= PRICE_OUTLIER_DEVIATION` | O (숫자 0.5 는 L45, 청크 밖) |
| 5 | 소스가 최소 개수 미만이면 검사 자체를 건너뛴다 | L103 `prices.size() < MIN_SOURCES_FOR_OUTLIER_CHECK` | O (숫자 3 은 L47, 청크 밖) |

부가로 L106 `if (median <= 0) return;` — 0 이하 중앙값이면 중단한다. 라벨 안에 있다.
4번은 코드가 "임계값 이하면 `continue`" 로 쓰여 있다. 답변에서 "넘으면 이상치" 라고
바꿔 말하려면 이 반전을 읽어야 한다.

## 판단: 수정 전 라벨은 부족했다

질문이 "**기준 가격**과 **이탈 정도**는 어떻게 계산해" 로 둘을 함께 묻는다.
이탈 정도(3번)는 현재 라벨 청크가 답한다. 그러나 **기준 가격을 어떻게 계산하는지
(2번)의 실제 답은 라벨 밖 청크에 있다.** 현재 라벨만으로는 "중앙값을 쓴다" 까지만
알 수 있고 "중앙값을 어떻게 구하는가" 는 답할 수 없다.

`evaluatePriceOutliers` 청크는 `median(...)` 을 호출만 한다. 호출식은 이름이지
계산 방법이 아니다.

한계도 같이 적는다. 이 판단은 질문 문구를 읽은 해석이고, 질문을 만든 사람이
"어떤 값을 기준으로 삼는가" 까지만 물을 의도였다면 현재 라벨로 충분하다.
그 의도는 코드로 확인할 수 없다. **이 문서는 그 선택을 대신하지 않는다.**

## 최소 수정안 (하나)

`cases.draft.jsonl` 의 ret-006 `expected_sources` 에 두 번째 항목을 추가한다.

```json
{"source_uri": "src/main/java/com/membershipflow/collect/service/AnomalyDetectionService.java",
 "anchor": "AnomalyDetectionService > median"}
```

이 안을 고른 이유.

- 질문 문구를 바꾸지 않는다. 문구를 바꾸면 이미 돌린 측정과 비교할 수 없다
- 근거 2개짜리 문항이 이미 있다(ret-009, `expected_sources` 2건). 형식상
  새로운 것을 만들지 않는다. 다만 ret-009 는 Markdown 문서 대상이고 Java 파일에서
  근거 2개를 쓴 전례는 아직 없다
- 청크가 실제로 존재하고 정확히 1건이다. 파싱 결과 경로가
  `(com.membershipflow.collect.service, AnomalyDetectionService, median)` 이라
  기존 라벨과 같은 suffix 형식으로 지정된다

### 적용 결과 (2026-09-22)

사용자 결정으로 적용했다. 바뀐 것은 `cases.draft.jsonl` 의 ret-006 한 줄뿐이다
(`expected_sources` 1건 추가, `notes` 에 이유 기록). 다른 14건과 모든 `reviewed`
값은 그대로다.

적용 후 확인한 것.

- `load_cases` 로 15건이 정상 로딩되고 ret-006 근거가 2건으로 읽힌다
- 두 anchor 가 각자의 청크에만 매칭되고 서로 오매칭되지 않는다
  (`anchor_matches` 로 4가지 조합 확인)
- 평가 관련 테스트 52건 통과 (`test_eval_loading`, `test_eval_contract`,
  `test_eval_plan`, `test_eval_scoring`)
- `retrieval-case.schema.json` 은 `reviewed: {"const": true}` 를 요구해 이 draft
  파일 15건 전부가 통과하지 않는다. **수정 전에도 15/15 실패였다.** 이 스키마는
  검토가 끝난 파일용이고, draft 는 `--allow-draft` 로 돌린다

실제 채점은 하지 않았다. ES 가 떠 있지 않다.

`reviewed` 변경은 여전히 이 문서를 읽은 사람이 한다.

### 이 수정으로도 남는 것

임계값 0.5 와 최소 소스 수 3 이라는 숫자는 **여전히 검색으로 닿지 않는다.**
상수가 어느 청크에도 안 들어가기 때문이고, 라벨을 고쳐도 해결되지 않는다.
청킹 쪽 별건이다. 이 문항의 채점 기준에서는 계속 범위 밖으로 둬야 한다.

## 다른 14건

재검토하지 않았다. `LABEL-REVIEW.md` 의 기존 정리를 그대로 둔다.
