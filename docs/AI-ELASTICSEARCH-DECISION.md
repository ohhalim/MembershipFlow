# 검색 저장소 Elasticsearch 통합 결정

- 상태: 사용자 선택 반영 / 구현 교체 전
- 날짜: 2026-09-09
- 적용 우선순위: 이 문서 > AI-INTERNAL-ASSISTANT-V1.md > AI-OPERATIONS-ASSISTANT-MVP-DESIGN.md

## 배경

- 기존 corpus: Markdown 11개, Java 5개
- 현재 ingestion: 문서별 PostgreSQL 활성 버전 전환 후 BM25 파일 생성
- 현재 vector 조회: SQLAlchemy와 pgvector 결합
- 현재 검색 품질·지연 비교값: 미측정. 성능 병목 후보 제외 근거 없음
- 검토 대상: 키워드·벡터 검색의 저장소 통합, 한국어 본문·코드 식별자 검색 분리

## 결정

- 검색용 BM25S·pgvector를 Elasticsearch 단일 노드로 교체
- 기존 Spring/MySQL과 운영 조회 API 경계 유지
- scanner·parser·chunker·embedding 계약 재사용, 저장소 및 검색 어댑터 교체
- AI용 PostgreSQL은 첫 버전에서 추가하지 않음. 평가·실행 기록은 로컬 JSON artifact로 보관
- 서버·Python client·Nori 버전은 호환 확인 후 명시 고정. latest 태그 금지
- 첫 사용자 시나리오: 수집 운영 질문. 구독·회원 조회는 후속 범위

## 검색 계약

- body: Nori 한국어 분석. 기본 분석기와 평가셋 비교
- symbol, source_path, source_type, source_hash, corpus_version: 정확 필터 가능한 keyword 필드
- title: 검색용 text. 식별자 exact match와 본문 match를 구분
- embedding: 모델 차원 명시 dense_vector, 초기 index=false
- vector baseline: script_score 기반 정확 검색. HNSW는 지연 측정 후 별도 실험
- 키워드·벡터 후보를 Python RRF로 결합. 내장 RRF 라이선스에 초기 구현 의존하지 않음
- 두 검색은 요청 시작 시 결정한 동일한 물리 인덱스 조회
- citation: 원문 hash, source path, symbol, line range, 물리 index version
- 검색 결과를 재정렬하는 cross-encoder는 효과 확인 후 채택

## 인덱스 발행

1. 명시 corpus 전체 스캔. 누락 파일·파싱 실패는 발행 실패 처리
2. corpus hash·parser·chunker·embedding revision으로 빌드 식별
3. 새 물리 인덱스에 전체 snapshot 적재. 초기 규모에서는 전체 재빌드 선택
4. bulk 항목별 오류, 문서·chunk 수, 차원, 검색 smoke 검증
5. refresh 완료 후 read alias를 새 인덱스로 전환
6. 실패 시 이전 alias 유지. 이전 인덱스는 즉시 삭제하지 않고 rollback에 보존

- 동일 빌드 재실행은 no-op 또는 미완료 빌드 복구. 단일 writer 원칙
- 삭제는 새 snapshot에서 제외. allowlist에서 삭제한 항목과 읽기 실패한 파일 구분
- 부분 발행 금지. alias API 응답의 개별 실패까지 검사
- rollback: 이전 검증 인덱스로 alias 전환, 검색·citation 확인
- 증분 임베딩 캐시는 첫 버전 이후 측정에 따라 추가. 기존 증분 갱신 완료 기준은 전체 snapshot 정합성 기준으로 대체

## 교체 단위

1. 결정 문서 및 기존 문서 상태 표시
2. Elasticsearch client·설정·Nori 이미지·Compose, mapping 및 실제 서버 smoke
3. snapshot ingestion·alias 전환, 재실행·삭제·중간 실패 통합 테스트
4. keyword·vector·RRF 검색, 동일 인덱스·출처 계약 테스트
5. 실제 모델 평가, FastAPI·Spring 연동, 내부 배포

- 기존 미커밋 Python 구현은 교체 검증 전 삭제하지 않음
- 새 경로 검증 후 이전 BM25S·pgvector 모듈·의존성·migration 제거
- CI fake embedding 테스트와 실제 모델 품질 평가 분리
- 외부 공개 포트 없음, 런타임 읽기 권한과 ingestion 쓰기 권한 분리
- 원격 push·PR·배포는 실행 직전 별도 승인

## 검증 및 미확정 사항

- baseline/tuning/held-out 분리 유지. 검색 엔진 변경 자체를 개선 결과로 표기 금지
- Nori·식별자 필드, keyword/vector/hybrid 각각의 누락 사례와 p95 기록
- 자원 사용량 및 배포 서버 수용 여부 미측정
- 단일 노드 운영으로 고가용성·분산 처리 성과 주장 불가
- 실제 Elasticsearch 통합 테스트·모델 평가·배포 미완료

## 공식 근거

- 벡터 정확 검색: https://www.elastic.co/docs/solutions/search/vector/knn
- dense_vector: https://www.elastic.co/docs/reference/elasticsearch/mapping-reference/dense-vector
- alias 전환: https://www.elastic.co/guide/en/elasticsearch/reference/current/aliases.html
- Nori: https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-nori
