# Elasticsearch 내부 RAG 세부 구현 계획

- 상태: 이슈 본문·브랜치 계획 작성 / 원격 생성 전
- 상위 작업: #350 (OPEN 확인)
- 현재 브랜치: feat/350/ai-operations-assistant, upstream 없음
- 현재 미커밋 구현 보존. 새 작업은 origin/develop 기반 별도 worktree 사용
- 범위: 수집 운영 문서 검색 기반. Spring 조회·생성·Slack은 후속 이슈

## 1. 모듈과 책임

- ingestion/scanner.py: allowlist와 원문 hash, 누락·경로 이탈 차단
- ingestion/parsers.py, chunker.py: 기존 구조 재사용, 원문 행·symbol·토큰 경계 검증
- ingestion/embeddings.py: fake/실제 모델 계약 분리
- persistence/elasticsearch_store.py: 물리 인덱스 생성·bulk·검증·alias 발행
- retrieval/elasticsearch.py: keyword/vector 질의, 동일 물리 인덱스 고정
- retrieval/fusion.py: chunk_id 기반 중복 제거 및 RRF
- config/settings.py: endpoint·CA·자격증명·alias·timeout 설정
- cli/main.py: build, validate, publish, rollback, search 명령
- evals/: 평가 입력·실행 설정·결과 JSON, tuning/held-out 분리

## 2. 문서 계약

| 필드 | 타입·규칙 |
|---|---|
| chunk_id | keyword, source hash·symbol·chunk 순번·pipeline revision 기반 결정적 ID |
| body | text, Nori 적용 후보. 원문 코드 내용 보존 |
| title | text, 원문 제목 |
| symbol | keyword 및 검색용 하위 필드, 대소문자 정책 테스트 |
| source_path/source_type | keyword, 서버 allowlist 기반 |
| source_hash/corpus_version | keyword |
| line_start/line_end | integer, 원문 범위 내 검증 |
| embedding | dense_vector, 모델 차원 명시, 초기 index=false |

- dynamic=strict 적용, 예상 외 필드 거부
- 실제 토큰 길이는 embedding tokenizer 기준. regex 근사값으로 토큰 제한 준수 주장 금지
- Markdown 표·코드 블록·Java method 분할 fixture 포함
- 원문 snapshot과 build manifest를 별도 로컬 artifact로 보관

## 3. 발행 상태와 복구

- 상태: BUILDING → VALIDATED → PUBLISHED / FAILED
- manifest: corpus hash, schema/analyzer/parser/chunker 버전, 모델 revision, 차원, 소스·chunk 수, index 이름
- 단일 호스트 writer lock. 다중 호스트 ingestion은 최초 범위 제외
- 새로운 snapshot 적재 후 bulk 항목별 성공 및 기대 chunk ID 집합 확인
- refresh 후 검색 smoke, 이후 read alias 전환
- alias 응답 유실 시 alias 재조회로 완료 여부 확인. 무조건 재전환 금지
- 동일 build가 이미 활성화돼 있으면 no-op
- 미완료 index는 활성 alias 대상이 아닌지 확인 후 재빌드
- 검색 요청 시작 시 물리 index 하나 확정, keyword/vector 모두 같은 index 조회
- 이전 index 자동 삭제는 초기 제외. 실행 중 요청과 rollback 보존
- 첫 corpus가 비어 있으면 실패. 의도적인 전체 삭제는 별도 명시 옵션 필요

## 4. 검색과 실패 계약

- 입력: query, mode(keyword/vector/hybrid), top_k, 허용 source filter
- top_k 1~20, 내부 후보 수 초기 20. 값은 tuning에서 조정
- RRF 초기 k=60. 동일 점수는 chunk_id 정렬로 결과 재현
- 검색 timeout 또는 shard 실패 시 정상 결과로 처리 금지
- 일부 검색 실패 시 최초 버전은 명시적 실패. silent fallback 제외
- 출력: chunk_id, rank, score, retriever, source citation, physical_index
- 벡터 점수와 BM25 점수를 직접 더하지 않음
- ES 클라이언트 자격증명·문서 본문은 기본 trace 제외

## 5. 이슈와 PR 분리

| 순서 | 제목 | 브랜치 | 완료 기준 |
|---|---|---|---|
| 1 | feat(ai): Elasticsearch 검색 실행 기반 구성 | feat/<번호>/ai-elasticsearch-foundation | Nori 이미지·client·mapping·권한·실제 서버 smoke |
| 2 | feat(ai): 문서 snapshot 색인과 검색 대상 전환 | feat/<번호>/ai-corpus-snapshot | 변경·삭제·중간 실패·재실행·rollback 통합 테스트 |
| 3 | feat(ai): 키워드·벡터 검색과 RRF 결합 | feat/<번호>/ai-hybrid-retrieval | 검색 계약·필터·동일 index·인용·실패 테스트 |
| 4 | test(ai): 검색 평가셋과 비교 실험 구성 | test/<번호>/ai-retrieval-evaluation | 사람 검토 tuning/held-out, 비교 결과 및 미개선 시도 기록 |
| 5 | feat(ai): 수집 운영 조회와 근거 답변 연결 | feat/<번호>/ai-collection-assistant | 인증·0건 SUCCESS·조회 실패·근거 부족 E2E |
| 6 | feat(ai): 내부 사용 인터페이스와 배포 검증 | feat/<번호>/ai-internal-delivery | Slack·중복 방어·자원·배포·rollback 확인 |

- 각 브랜치는 직전 PR develop 반영 후 origin/develop에서 생성. 미병합 구현 브랜치 연쇄 금지
- 초기 1번에서 공통 scaffold만 선별 이전. 기존 untracked 파일 전체 복사·커밋 금지
- 이전 pgvector/BM25S 구현은 활성 경로 교체 검증 후 정리
- 보호 브랜치 직접 push 금지. 원격 작업은 별도 승인

## 6. 첫 이슈 본문 초안

제목: feat(ai): Elasticsearch 검색 실행 기반 구성

### 배경
- #350 내부 RAG MVP 설계 및 corpus 16개 정의
- 기존 구현: PostgreSQL 문서 활성화 후 BM25 artifact 생성
- 검색 품질·지연 측정 전. 성능 개선 목적의 교체 근거 없음
- Elasticsearch 키워드·벡터 검색 통합 결정
- 이번 범위: 실행 환경·필드 계약·실제 서버 연동 검증

### 변경 범위
- Python 환경·lock 및 Elasticsearch client 설정
- 버전 고정 Elasticsearch/Nori 이미지와 독립 Compose
- Nori 본문·keyword 식별자·dense_vector mapping
- 수집용 쓰기 권한과 조회용 읽기 권한 분리
- fake embedding 기반 실제 서버 index/search/delete smoke
- 기존 운영 Compose 구성 회귀 확인

### 완료 기준
- _analyze 한국어 fixture 및 코드 식별자 보존 검증
- mapping 생성·동일 mapping 재확인 통과
- 벡터 차원 불일치 거부 확인
- 조회 자격증명으로 쓰기 불가 확인
- localhost 실행 환경 health 및 keyword/vector smoke 통과
- 서버 없이 실행하는 단위 테스트와 서버 통합 테스트 명령 분리
- lint/type/test 및 Compose config 검증 통과

### 제외
- 전체 corpus 발행, RRF, 실제 모델 성능 평가
- Spring/Slack/LLM 호출, 운영 배포, 성능 향상 주장

### 커밋 단위
- build(ai): Python 실행 환경과 Elasticsearch client 구성
- build(ai): Nori 검색 서버와 로컬 Compose 구성
- feat(ai): 검색 필드 mapping과 연결 설정 추가
- test(ai): 검색 서버 권한과 검색 계약 검증

## 7. 브랜치 생성 절차

- 현재 dirty worktree에서 switch/stash 금지
- 원격 develop 읽기 갱신 후 별도 worktree를 origin/develop detached 상태로 생성
- 해당 worktree에서 git switch -c feat/<이슈번호>/ai-elasticsearch-foundation --no-track origin/develop
- git branch -vv로 upstream 없음 확인
- 승인된 원격 push: git push -u origin HEAD:feat/<이슈번호>/ai-elasticsearch-foundation
- 첫 이슈 본문 확정 후 GitHub 생성, 반환 번호로 브랜치명 확정
