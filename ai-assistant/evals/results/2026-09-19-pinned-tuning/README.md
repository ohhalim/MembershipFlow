# 고정 인덱스 tuning 진단

## 배경 및 조건

- 실제 bge-m3 인덱스 build 및 격리 alias 왕복 검증 후 검색 실패 위치 확인
- 입력: cases.draft.jsonl 중 tuning 10건, reviewed=false 유지
- held_out 5건 실행 제외. 사람 검토 또는 최종 품질 승인 아님
- k=5, candidates=20, reranker 미사용
- 모델: BAAI/bge-m3, revision 5617a9f61b028005a4858fdac845db406aefb181, offline 캐시
- 고정 물리 인덱스: mf-ai-chunks-64460aa92eba4887a09aa7011522857d
- 인덱스 identity 및 기대 chunk ID 검증 후 읽기 전용 검색
- 실행 코드 SHA, 평가셋 hash, 전체 후보·반환 근거: results.json
- alias 변경, 라벨 수정, 서비스 재시작 없음

## 측정값

| 검색 | Recall@5 | 모든 근거 충족 문항/10 | MRR@5 | 후보 20개 Recall |
|---|---:|---:|---:|---:|
| keyword | 0.35 | 3 | 0.325 | 0.50 |
| vector | 0.55 | 5 | 0.525 | 0.90 |
| hybrid | 0.45 | 4 | 0.420 | 0.70 |

- Recall은 문항별 회수 근거 비율의 평균. ret-009는 근거 2개 필요
- vector: ret-002/004/008 및 ret-009의 누락 근거가 후보 20개에는 존재
- vector: ret-001은 후보 20개에도 정답 근거 부재
- hybrid: vector 후보에 있는 ret-002/004 정답이 결합 후 후보 20개에서 제외
- 후보 20개는 모드별 후보 풀. hybrid는 keyword 20 + vector 20을 RRF로 결합 후 20개로 제한
- 후보 Recall은 해당 풀에서 재정렬 시 가능한 회수 범위의 진단값. reranker 개선 결과 아님

## 판단 범위

- 이번 초안·고정 인덱스 조건에서 vector가 hybrid보다 높은 Recall 관측
- 전체 서비스에서 vector가 항상 우수하다는 결론 제외
- 후보 누락과 상위 5개 순위 손실 분리. 어휘 격차 단일 원인 확정 불가
- 과거 측정과 문항·인덱스 조건 차이 가능. 개선율 계산 제외
- 라벨 적절성은 별도 코드 검토 필요. reviewed 자동 전환 없음

## 재실행

ai-assistant 디렉터리에서 `.venv/bin/python evals/results/2026-09-19-pinned-tuning/diagnose.py`

- 기존 results.json 존재 시 실행 거부. 새 증거 디렉터리에 스크립트를 복사해 사용
- 실행 후 스크립트 변경: Ruff 정리 및 순차 실행 closure의 mode/pools 기본 인자 바인딩 명시
- 해당 결과 생성 시에도 각 모드는 await run_cases 종료 후 다음 모드 진행
