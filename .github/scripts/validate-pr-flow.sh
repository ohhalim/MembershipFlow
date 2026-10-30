#!/usr/bin/env bash
set -euo pipefail

base_branch="${1:?base branch is required}"
head_branch="${2:?head branch is required}"

echo "PR branch flow: ${head_branch} -> ${base_branch}"

if [[ "${base_branch}" == "main" ]]; then
  if [[ "${head_branch}" == "develop" ]]; then
    echo "OK: develop -> main release flow"
    exit 0
  fi

  # 운영 장애는 develop에 배포 전 작업이 쌓여 있어도 바로 고쳐야 한다.
  # main에서 딴 hotfix 브랜치만 main으로 보낼 수 있고, 머지 후 main -> develop 동기화로 develop에 반영한다.
  if [[ "${head_branch}" =~ ^hotfix/[0-9]+/[a-z0-9][a-z0-9-]*$ ]]; then
    echo "OK: hotfix -> main flow"
    exit 0
  fi

  echo "ERROR: main 대상 PR은 develop 또는 hotfix/<이슈번호>/<키워드> 브랜치에서만 생성할 수 있습니다."
  exit 1
fi

if [[ "${base_branch}" == "develop" ]]; then
  if [[ "${head_branch}" == "main" ]]; then
    echo "OK: main -> develop post-release sync flow"
    exit 0
  fi

  if [[ "${head_branch}" =~ ^(feat|fix|refactor|chore|test|docs|setting|hotfix|perf)/[0-9]+/[a-z0-9][a-z0-9-]*$ ]]; then
    echo "OK: work branch -> develop flow"
    exit 0
  fi

  echo "ERROR: develop 대상 PR의 브랜치 이름이 허용 규칙과 다릅니다."
  echo "Allowed: <type>/<issue-number>/<keyword> or main -> develop sync"
  exit 1
fi

echo "ERROR: 지원하지 않는 PR base 브랜치입니다: ${base_branch}"
exit 1
