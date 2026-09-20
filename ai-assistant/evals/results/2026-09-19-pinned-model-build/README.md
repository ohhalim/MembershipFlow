# 고정 revision 실제 모델 build 검증

## 배경

- 기존 인덱스 5개: 모델 `_meta` 부재로 강화된 publish/rollback 검사 통과 불가
- 목적: 모델 revision이 명시된 전환 후보 생성 및 실제 검색 응답 확인
- 범위: 독립 인덱스 build, 모델 신원 대조, 청크 집합 확인, 검색 smoke
- 제외: 운영 alias 전환, 서비스 재시작, 검색 품질 평가

## 실행 조건

- 코드: `7acc1f98fe755911751f014d55c4e2656b319c2b`
- 모델: `BAAI/bge-m3`
- revision: `5617a9f61b028005a4858fdac845db406aefb181`
- 로컬 캐시 사용, `HF_HUB_OFFLINE=1`, `TRANSFORMERS_OFFLINE=1`
- 프로세스 환경변수만 설정. `.env` 및 실행 중인 소비자 설정 변경 없음
- corpus 설정 hash 및 모델 정보: `observation.json`
- 원본별 hash 및 기대 청크 ID: build 산출물 원본 `manifest.json`

```sh
# ai-assistant 디렉터리에서 실행. ES 자격증명은 기존 Settings 사용
.venv/bin/python evals/results/2026-09-19-pinned-model-build/verify.py
```

- 증거 덮어쓰기 방지: 기존 observation 파일 존재 시 시작 전 거부
- 재실행 시 스크립트를 새 결과 디렉터리로 복사 후 실행. 매번 새 인덱스 생성
- 새 인덱스 자동 삭제 없음

## 관측 결과

- 상태: `PASSED`, manifest `VALIDATED`
- 새 인덱스: `mf-ai-chunks-64460aa92eba4887a09aa7011522857d` (보존, alias 미연결)
- 원본 16개, 청크 317개, 임베딩 1024차원, build 46.248초
- manifest ↔ 인덱스 `_meta` ↔ 실행 설정 대조 통과
- count 및 기대 chunk ID 집합 재검증 통과
- `isActiveAt` 키워드 검색: 2건, 첫 결과 `Subscription.isActiveAt`
- `구독의 이용 가능 조건` 벡터 검색: 5건, 첫 결과 API 문서의 내 구독 상태 조회
- 실행 전 인덱스 5개 → 실행 후 6개. 기존 5개의 alias·문서 수 동일
- 운영 alias `mf-ai-chunks` 대상 유지: `mf-ai-chunks-806c80ac51106a9e`

## 제한 및 다음 조건

- 검색 응답 확인만 수행. Recall/MRR 또는 개선 수치 아님
- 새 후보 1개 확보. 실제 모델 대상 publish/rollback 왕복 및 소비자 재시작 미검증
- 기존 인덱스와 동일 가중치·동일 검색 결과라는 증거 없음
- 기존 인덱스의 내용 불변은 전체 문서 hash로 검사하지 않음. alias·count 비교 범위
- `_meta`는 build가 기록한 정보. 개별 저장 벡터의 가중치 기원 독립 증명 아님
- 운영 전환 전 소비자 revision 고정과 검증된 복귀 경로 필요
- `run.log`의 임베딩 차원 조회 API deprecation 경고 관측. 실행 실패 없음
