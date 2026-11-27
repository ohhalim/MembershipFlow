#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAR="$(ls "$ROOT"/build/libs/MembershipFlow-*-SNAPSHOT.jar | grep -v plain | head -1)"
LOG_DIR="${LOG_DIR:-$ROOT/build/loadtest}"
mkdir -p "$LOG_DIR"

export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3307/membershipflow?serverTimezone=Asia/Seoul&characterEncoding=UTF-8"
export DB_USERNAME=root
export DB_PASSWORD=root
export PADDLE_API_BASE_URL="http://localhost:8089"
export SERVER_TOMCAT_THREADS_MAX="${TOMCAT_MAX_THREADS:-20}"
export SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true
export MANAGEMENT_SERVER_PORT="${MANAGEMENT_PORT:-8082}"
export APP_ENVIRONMENT=loadtest
export JSON_LOG_FILE="$LOG_DIR/membershipflow.json"
export DISCORD_ERROR_WEBHOOK_URL=
export APP_RATE_LIMIT_ENABLED=false

exec java -jar "$JAR" > "$LOG_DIR/backend.log" 2>&1
