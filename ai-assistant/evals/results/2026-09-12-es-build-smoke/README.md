# ES build smoke (2026-09-12)

성공 경로만 실행. 원격 업로드 없음.

## 고정값

| | |
|---|---|
| 코드 SHA | `d0690a95234b1146739d7a93051a567167501e8c` |
| 브랜치 | `feat/366/es-index-build`, working tree clean |
| corpus config sha256 | `73144ed7a59cf7d688f84f030b340a0b90b083c44d7b1b0acfe88588affd9308` |
| embedding | `BAAI/bge-m3`, revision 미지정(null), 1024차원 |
| 실행 환경 | `HF_HUB_OFFLINE=1` (로컬 캐시) |

## 명령

```sh
cd ai-assistant && set -a; . ./.env; set +a
uv run membershipflow-ai build --manifest /tmp/mf-build-smoke-034447.json
```

exit 0 / 37초
stdout: `validated build manifest: ...; read alias unchanged`

## 보존된 증거

- `manifest-VALIDATED.json` — 실행 산출물 원본 복사. **사후 수정하지 않음**

## 보고 기반 (파일 증거 없음)

다음은 실행 중 화면 관측이며 로그 파일로 남기지 않았다. 재조회 불가.

- 소요 37초
- alias 전/후/정리 후 3회 모두 `mf-ai-chunks -> mf-ai-chunks-806c80ac51106a9e`
- 기존 인덱스 5개 문서수 317 유지
- 실패 2건의 종료 코드와 예외 메시지
- 생성된 `mf-ai-chunks-2c3e6948e4b84fac93cf7f9a1f61c4a8` 는 삭제됨 → **재조회 불가**

## 실패 2건 — 단계 구분

둘 다 **manifest 경로 사전 확인 단계**. ES 접촉 전이다.

| 조건 | 결과 |
|---|---|
| 기존 manifest 경로 재지정 | exit 1, `FileExistsError` |
| 부모 디렉터리 없음 | exit 1, `FileNotFoundError` |

**ES 적재 중 실패 검증으로 확대 해석하지 않는다.**

## 미검증

- ES 적재 중 실패, 부분 실패 복구
- 강제 종료 시 `PREPARING`/`BUILDING` 상태 잔류
- alias 전환 경로 자체
- `revision=null` + offline 캐시는 **가중치 고정의 증거가 아니다**
- 평가셋 15건 `reviewed=false` 유지. 기술 검증과 분리
