# ES build 실행

## 배경

- 기존 `ingest`: PostgreSQL 문서별 활성화. ES 검색 갱신 없음
- 신규 `build`: allowlist 원본 전체 읽기 → 파싱·청킹 → 임베딩 → 별도 ES 인덱스 적재 → 개수·ID 검증
- 검색 alias 전환은 별도 `publish` 명령. `build`만으로 Slack/search가 새 원본을 조회하지 않음

## 실행

`ai-assistant` 디렉터리에서 기존 설정과 의존성 사용:

```sh
.venv/bin/membershipflow-ai build --manifest /tmp/mf-build-001.json
```

- 쓰기 대상: 새 ES 물리 인덱스, 지정 manifest 파일
- 파일 경로: 부모 디렉터리 사전 존재 필요, 기존 파일 덮어쓰기 거부
- 모델: `AI_EMBEDDING_PROVIDER`, `AI_EMBEDDING_MODEL`, `AI_EMBEDDING_REVISION` 적용
- fake embedding은 실행 계약 검증용. 검색 품질 수치로 사용 금지
- 원본이 여러 파일에 걸쳐 수정 중이면 혼합 시점의 입력 가능. 일관된 원본이 필요하면 고정된 checkout에서 실행
- 새 UUID 인덱스 사용. 동일 입력 재실행도 별도 인덱스 생성

## 결과 확인

- `status=VALIDATED`: 적재 후 count와 기대 chunk ID 확인 통과
- `status=FAILED`: 준비·적재·검증 중 실패. 실패 유형과 생성 대상 인덱스 이름 기록
- manifest: 원본별 hash, chunk IDs, 모델·revision·차원, 청킹 설정, schema revision, 물리 인덱스
- 실패 인덱스 자동 삭제 없음. 성공·실패 모두 기존 검색 alias 유지
- 강제 종료 시 PREPARING/BUILDING 상태가 남을 수 있음. VALIDATED로 취급 금지

## 검증 범위와 제한

- 로컬 테스트: 임베딩 개수·차원·유한값·영벡터 오류, 원본 누락, 적재/검증 실패, manifest 덮어쓰기, 재실행 격리
- 원본 읽기·청크 생성은 실제 corpus와 fake embedding으로 별도 확인 가능
- 실제 ES build 실행 및 keyword/vector 검색 smoke는 별도 통합 검증 대상
- VALIDATED는 답변 품질·검색 품질 인증이 아님
- regex 토큰 근사값 사용. 실제 모델 tokenizer의 토큰 제한 검증은 미포함
- 모델 revision이 미지정이면 manifest에 null 기록. 가중치까지 고정된 재현성 주장 불가
- 활성 인덱스 정리 명령 미구현

## publish / rollback

```sh
.venv/bin/membershipflow-ai publish --manifest /path/to/new-manifest.json
.venv/bin/membershipflow-ai rollback --manifest /path/to/previous-manifest.json
```

- 두 명령 모두 `VALIDATED` manifest 필요. 기존 `rollback --to <index>` 미지원
- 전환 전 검사: 대상 존재, 지원 schema revision, `symbol_path` 매핑, 벡터 매핑 차원, 기대 chunk ID 집합
- 모델 검사: manifest ↔ 인덱스 `_meta` ↔ 명령 실행 프로세스 설정 일치
- sentence-transformers revision: 40자리 소문자 commit hash 필요. null·브랜치·태그 거부
- 이미 활성인 대상도 검사 후 no-op. 전환 시 이전 인덱스 보존
- `_meta` 없는 기존 인덱스는 rollback 불가. 추정 메타데이터 추가로 우회하지 않음
- 명령 프로세스 설정 검증은 실행 중인 Slack 프로세스의 모델 설정 검증이 아님
- Slack은 시작 시 물리 인덱스를 선택하므로 alias 전환만으로 기존 프로세스의 검색 대상이 바뀌지 않음

## 기존 인덱스에서의 이행 조건

1. 사용할 모델 commit hash를 고정해 새 인덱스 build
2. manifest·인덱스 모델 정보·chunk ID 및 실제 검색 응답 확인
3. 복귀용 인덱스와 해당 manifest 보존. 기존 메타데이터 없는 인덱스로의 복귀 불가
4. 검색 소비자에도 같은 모델·revision 적용 후 전환 및 프로세스 재시작 계획 확인

- build·검색 smoke 통과는 검색 품질 승인이나 운영 전환 완료를 의미하지 않음
- 새 baseline은 재구축 결과이며, 과거 인덱스와 같은 가중치·검색 결과라는 증거가 아님
