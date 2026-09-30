# tuning 라벨 변경 후 검색 진단 (2026-09-30)

## 배경

- #408에서 ret-006의 필수 근거를 `evaluatePriceOutliers`와 `median` 2개로 변경
- 기존 점수는 ret-006 필수 근거 1개를 전제로 하므로 새 점수와 직접 비교 불가
- #417의 새 held_out 후보는 아직 사람이 검토하지 않았고 결과를 보지 않기 위해 실행하지 않음

## 실행 조건

- 입력: `evals/retrieval/cases.draft.jsonl`, `split=tuning`, 10건, `reviewed=false`
- `--allow-draft -k 5 --candidates 20 --retriever keyword --retriever vector --retriever hybrid`
- 실제 ES 물리 인덱스: `mf-ai-chunks-64460aa92eba4887a09aa7011522857d`
- 임베딩: `BAAI/bge-m3`, revision `5617a9f61b028005a4858fdac845db406aefb181`
- `HF_HUB_OFFLINE=1`; 로컬 캐시 사용
- 원본 결과: `raw.json`. 파일 안에 입력 라벨 SHA-256, 인덱스, 모델 revision, 문항별 후보/순위 포함
- 전체 실행 12.691초. 모델 로드 포함 단일 순차 실행이므로 검색 방식별 지연 비교에 사용하지 않음

## 결과

| 방식 | Recall@5 | MRR | 필수 근거 모두 찾은 문항 비율 |
|---|---:|---:|---:|
| keyword | 0.35 | 0.325 | 0.30 |
| vector | 0.50 | 0.525 | 0.40 |
| hybrid | 0.40 | 0.420 | 0.30 |

ret-006: vector/hybrid는 `evaluatePriceOutliers`만 1위로 찾고 `median`은 top5에 없음.
각각 문항 Recall=0.5, 필수 근거 충족=false. keyword는 둘 다 없음.

## 해석 범위

- 이것은 미검토 tuning 초안에 대한 진단값. 최종 성능 검증이나 독립 평가 아님
- 라벨이 바뀌었으므로 과거 vector 0.55, hybrid 0.45와의 단순 전후 차이를
  검색 성능 변화로 해석하지 않음. 검색 구성 간 비교는 이번 동일 라벨·인덱스 결과만 사용
- 기존 2026-09-19 VALIDATED manifest와 현재 로컬 corpus의 변경 소스는 README.md 1개.
  평가에 사용한 물리 인덱스는 manifest와 같은 317청크 세대. 나머지 문서의 현재 해시가
  manifest와 같다는 로컬 대조는 `review-20260930/manifest-crosscheck.json` 참조
- 신규 held_out 20건: 검색 결과 미열람, 사람 검토 전. 별도 동결·검증 필요
