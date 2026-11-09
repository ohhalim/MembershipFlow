# 실제 모델 publish / rollback 왕복 검증

## 배경 및 범위

- 이전 실제 모델 build: 원본 16개, 청크 317개, alias 미연결 후보 A 확보
- 이번 검토 대상: 동일 revision으로 만든 후보 B 및 격리 alias에서 CLI 전환 경로
- 제품 코드 변경 없음. 운영 alias 전환·Slack 재시작 제외

## 실행 조건

- 코드 SHA: `e28b80e` (전체 SHA는 observation.json)
- 모델: `BAAI/bge-m3`, revision `5617a9f61b028005a4858fdac845db406aefb181`
- offline 로컬 캐시, 프로세스 환경변수만 설정
- 테스트 alias: `mf-ai-verify-pinned-20260919`
- A manifest: `../2026-09-19-pinned-model-build/manifest.json`
- B manifest: `manifest-B.json` (build 산출물 원본)
- 실행: ai-assistant에서 `.venv/bin/python evals/results/2026-09-19-pinned-alias-roundtrip/verify.py`
- 기존 observation 존재 시 실행 거부. 재실행 시 새 결과 디렉터리 및 비어 있는 테스트 alias 필요
- 검증 스크립트의 실행 후 변경: Ruff 포맷만 적용, 실행 로직 변경 없음

## 관측

- B: 원본 16개·청크 317개, A와 기대 청크 ID 집합 동일
- 실제 CLI 함수 `publish` / `rollback` 호출, ES 응답 mock 없음
- 최초 A 연결 / A 재호출 no-op / A → B / B → A rollback: 4단계 통과
- 각 단계 active_index 대조 및 같은 실제 모델 질의 벡터로 검색 3건 응답 확인
- A와 B 각각 count·기대 청크 ID 재검증 통과
- 전후 기존 `mf-ai-chunks-*` 6개의 alias·문서 수 동일
- 운영 alias: `mf-ai-chunks-806c80ac51106a9e` 유지
- 정리: 테스트 alias만 정확한 대상에서 제거, 제거 승인 응답 및 alias 부재 확인
- A/B 물리 인덱스 모두 보존. B: `mf-ai-verify-pinned-20260919-a74e18dc8a24426b83e37c6b55d263d9`
- 근거: observation.json, manifest-B.json, run.log

## 소비자 설정 및 남은 조건

- 별도 새 Python 프로세스에서 기본 Settings 조회: sentence-transformers / BAAI/bge-m3 / revision=null / alias=mf-ai-chunks
- 실행 중인 Slack 프로세스 환경은 미조회. 기본 설정 조회를 실행 중 환경 확인으로 취급하지 않음
- 운영 적용 전 소비자 revision 고정 및 프로세스 재시작 필요
- A/B는 같은 corpus의 재빌드. 서로 다른 내용 버전 사이의 검색 품질 비교 아님
- 벡터 검색 응답은 통합 smoke이며 Recall/MRR 증거 아님
- 장애 도중 전환, 동시 전환, 실제 Slack 응답 및 운영 전환 미검증
- 기존 메타데이터 없는 인덱스로의 rollback은 여전히 불가
