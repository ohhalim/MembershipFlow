#!/usr/bin/env bash
set -euo pipefail

health_timeout_seconds="${BACKEND_HEALTH_TIMEOUT_SECONDS:-120}"

print_backend_diagnostics() {
  docker compose ps backend || true
  docker compose logs --tail=200 backend || true
}

if ! docker compose up -d --no-deps --wait \
  --wait-timeout "${health_timeout_seconds}" backend; then
  echo "ERROR: backend did not become healthy within ${health_timeout_seconds}s"
  print_backend_diagnostics
  exit 1
fi

backend_container="$(docker compose ps -q backend)"
if [[ -z "${backend_container}" ]]; then
  echo "ERROR: backend container was not created"
  print_backend_diagnostics
  exit 1
fi

backend_health="$(docker inspect --format \
  '{{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' \
  "${backend_container}")"
if [[ "${backend_health}" != "healthy" ]]; then
  echo "ERROR: backend health is ${backend_health}"
  print_backend_diagnostics
  exit 1
fi

echo "Backend health gate passed: ${backend_container} (${backend_health})"
